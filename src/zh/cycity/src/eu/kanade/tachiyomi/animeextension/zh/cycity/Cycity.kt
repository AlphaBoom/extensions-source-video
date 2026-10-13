package eu.kanade.tachiyomi.animeextension.zh.cycity

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.time.OffsetDateTime
import java.util.Calendar

/**
 * 次元城动画：前端为 React SPA，数据来自同域的 JSON API（/api）。
 * 列表、详情、选集可匿名访问；播放地址（/api/v2/sections/{id}/play-url）需要登录。
 */
@Source
abstract class Cycity :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val supportsLatest = true

    override val supportsRelatedAnime = false

    private val apiUrl by lazy { "$baseUrl/api" }

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-App-Name", "cyc_web")
            .set("X-App-Version", "cycweb")
            .set("X-Time-Zone", "Asia/Shanghai")
            .build()
    }

    private fun api(path: String): HttpUrl.Builder = "$apiUrl/$path".toHttpUrl().newBuilder()

    private inline fun <reified T> Response.apiResult(): ApiResponse<T> {
        if (header("Content-Type")?.contains("json") != true) {
            close()
            // 站点按 IP 所在地区拦截访问时返回 HTML 403 页面
            throw Exception(if (code == 403) "次元城不向当前 IP 所在地区提供服务" else "请求失败（HTTP $code）")
        }
        return parseAs()
    }

    private inline fun <reified T> Response.parseData(): T {
        val result = apiResult<T>()
        if (result.code != 0) throw Exception(result.msg.trim().ifEmpty { "请求失败（${result.code}）" })
        return result.data ?: throw Exception("返回数据为空")
    }

    // ============================== List ==============================

    private fun videoListRequest(page: Int, zoneId: String = "1", orderBy: String, configure: HttpUrl.Builder.() -> Unit = {}): Request {
        val url = api("videos")
            .addQueryParameter("zone_id", zoneId)
            .addQueryParameter("order_by", orderBy)
            .addQueryParameter("page", "$page")
            .addQueryParameter("page_size", "$PAGE_SIZE")
            .apply(configure)
        return GET(url.build(), apiHeaders)
    }

    private fun videoListParse(response: Response): AnimesPage {
        val data = response.parseData<VideoPage>()
        val pager = data.pager
        val hasNextPage = pager != null && pager.page * pager.pageSize < pager.total
        return AnimesPage(data.list.map { it.toSAnime() }, hasNextPage)
    }

    private fun VideoItem.toSAnime() = SAnime.create().apply {
        url = "/anime/$videoId"
        title = this@toSAnime.title
        thumbnail_url = coverUrl
    }

    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(POPULAR_PREF, false)) {
        GET(api("index/weekday").addQueryParameter("weekday", "${today()}").build(), apiHeaders)
    } else {
        videoListRequest(page, orderBy = "hits")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.endsWith("/index/weekday")) {
            val videos = response.parseData<WeekdayList>().list.flatMap { it.videos }
            return AnimesPage(videos.map { it.toSAnime() }, false)
        }
        return videoListParse(response)
    }

    /** 1 = 周一 … 7 = 周日 */
    private fun today(): Int {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
    }

    override fun latestUpdatesRequest(page: Int): Request = videoListRequest(page, orderBy = "update_time")

    override fun latestUpdatesParse(response: Response): AnimesPage = videoListParse(response)

    // ============================== Search ==============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("筛选条件（关键字搜索时仅频道生效）"),
        ZoneFilter(),
        CategoryFilter(),
        YearFilter(),
        SortFilter(),
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val zoneId = filters.selected<ZoneFilter>()
        if (query.isNotBlank()) {
            // 搜索接口的 zone_id 可选，未选频道时搜索全部频道
            val url = api("videos/search")
                .addQueryParameter("q", query.trim())
                .apply { if (zoneId.isNotEmpty()) addQueryParameter("zone_id", zoneId) }
                .addQueryParameter("page", "$page")
                .addQueryParameter("page_size", "$PAGE_SIZE")
            return GET(url.build(), apiHeaders)
        }
        // 列表接口必须指定频道，未选时默认 TV番组
        return videoListRequest(page, zoneId.ifEmpty { "1" }, filters.selected<SortFilter>().ifEmpty { "update_time" }) {
            filters.selected<CategoryFilter>().takeIf { it.isNotEmpty() }?.let { addQueryParameter("tag", it) }
            filters.selected<YearFilter>().takeIf { it.isNotEmpty() }?.let { addQueryParameter("year", it) }
        }
    }

    private inline fun <reified T : SelectFilter> AnimeFilterList.selected(): String = filterIsInstance<T>().firstOrNull()?.selected.orEmpty()

    override fun searchAnimeParse(response: Response): AnimesPage = videoListParse(response)

    // ============================== Details ==============================

    /** 新站为 /anime/{id}，旧站为 /bangumi/{id}.html，两者 id 相同。 */
    private fun String.videoId(): String = VIDEO_ID_REGEX.find(this)?.groupValues?.get(1)
        ?: throw Exception("无法识别的番剧链接，请重新搜索后迁移")

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${anime.url.videoId()}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET(api("videos/${anime.url.videoId()}").build(), apiHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val detail = response.parseData<VideoDetail>()
        return SAnime.create().apply {
            url = "/anime/${detail.id}"
            title = detail.title
            thumbnail_url = detail.coverUrl
            author = detail.director?.filter { it.isNotBlank() }?.joinToString()?.ifEmpty { null }
            artist = detail.writer?.ifBlank { null }
            genre = detail.tags?.filter { it.isNotBlank() }?.joinToString()?.ifEmpty { null }
            status = when (detail.completed) {
                true -> SAnime.COMPLETED
                false -> SAnime.ONGOING
                null -> SAnime.UNKNOWN
            }
            description = buildString {
                detail.description?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
                val extra = listOfNotNull(
                    detail.subtitle?.takeIf { it.isNotBlank() }?.let { "别名：$it" },
                    detail.englishTitle?.takeIf { it.isNotBlank() }?.let { "英文名：$it" },
                    detail.year?.let { "年份：$it" },
                    detail.area?.takeIf { it.isNotBlank() }?.let { "地区：$it" },
                    detail.language?.takeIf { it.isNotBlank() }?.let { "语言：$it" },
                    detail.score?.takeIf { it > 0 }?.let { "评分：$it" },
                    detail.actor?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { "声优：${it.joinToString()}" },
                )
                if (extra.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(extra.joinToString("\n"))
                }
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val videoId = anime.url.videoId()
        val detail = client.newCall(animeDetailsRequest(anime)).await().parseData<VideoDetail>()
        return detail.playFrom.orEmpty().flatMap { source ->
            fetchSections(videoId, source.code).mapIndexed { index, section ->
                SEpisode.create().apply {
                    url = "/anime/$videoId/play/${section.id}"
                    name = section.title?.ifBlank { null } ?: "第${index + 1}集"
                    episode_number = (index + 1).toFloat()
                    scanlator = source.title?.ifBlank { null } ?: source.code
                }
            }
        }.reversed()
    }

    private suspend fun fetchSections(videoId: String, playerCode: String): List<Section> {
        val sections = mutableListOf<Section>()
        var page = 1
        while (true) {
            val url = api("videos/$videoId/sections")
                .addQueryParameter("player_code", playerCode)
                .addQueryParameter("page", "$page")
                .addQueryParameter("page_size", "$SECTION_PAGE_SIZE")
                .build()
            val data = client.newCall(GET(url, apiHeaders)).await().parseData<SectionPage>()
            sections += data.list
            val total = data.pager?.total ?: 0
            if (data.list.isEmpty() || sections.size >= total) break
            page++
        }
        return sections
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val sectionId = SECTION_ID_REGEX.find(episode.url)?.groupValues?.get(1)
            ?: throw Exception("剧集链接已过期，请刷新剧集列表")
        val playUrl = fetchPlayUrl(sectionId)
        val url = playUrl.url?.takeIf { it.isNotBlank() } ?: throw Exception("未获取到播放地址")
        val video = Video(
            videoUrl = url,
            videoTitle = playUrl.name?.ifBlank { null } ?: "默认线路",
            headers = headers,
            initialized = true,
        )
        return listOf(video).toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    private suspend fun fetchPlayUrl(sectionId: String, retry: Boolean = true): PlayUrl {
        val token = authToken()
        val request = GET(
            api("v2/sections/$sectionId/play-url").build(),
            apiHeaders.newBuilder().set("Authorization", "Bearer $token").build(),
        )
        val result = client.newCall(request).await().apiResult<PlayUrl>()
        if (result.code == 401 && retry) {
            clearToken()
            return fetchPlayUrl(sectionId, retry = false)
        }
        if (result.code != 0) throw Exception(result.msg.trim().ifEmpty { "获取播放地址失败（${result.code}）" })
        return result.data ?: throw Exception("未获取到播放地址")
    }

    // ============================== Auth ==============================

    private suspend fun authToken(): String {
        val cached = preferences.getString(PREF_TOKEN, null)
        if (!cached.isNullOrEmpty() && !tokenExpiresSoon()) return cached
        if (!cached.isNullOrEmpty()) {
            runCatching { requestToken(POST("$apiUrl/auth/refresh", apiHeaders.newBuilder().set("Authorization", "Bearer $cached").build())) }
                .getOrNull()?.let { return it }
        }
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            throw Exception("次元城需要登录后才能播放，请在扩展设置中填写账号和密码")
        }
        return requestToken(POST("$apiUrl/auth/login", apiHeaders, LoginRequest(username, password).toJsonRequestBody()))
    }

    private suspend fun requestToken(request: Request): String {
        val result = client.newCall(request).await().apiResult<AuthToken>()
        val auth = result.data
        if (result.code != 0 || auth == null) throw Exception("登录失败：${result.msg.trim().ifEmpty { "${result.code}" }}")
        preferences.edit()
            .putString(PREF_TOKEN, auth.token)
            .putString(PREF_TOKEN_EXPIRES, auth.expiresAt.orEmpty())
            .apply()
        return auth.token
    }

    private fun tokenExpiresSoon(): Boolean {
        val expiresAt = preferences.getString(PREF_TOKEN_EXPIRES, null) ?: return false
        val expires = runCatching { OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() }.getOrNull() ?: return false
        return expires - System.currentTimeMillis() < TOKEN_REFRESH_MARGIN
    }

    private fun clearToken() {
        preferences.edit().remove(PREF_TOKEN).remove(PREF_TOKEN_EXPIRES).apply()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "用户名"
            summary = "播放需要登录次元城账号"
            setOnPreferenceChangeListener { _, _ ->
                clearToken()
                true
            }
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "密码"
            summary = "仅保存在本机，用于获取播放地址"
            setOnBindEditTextListener { it.inputType = INPUT_TYPE_PASSWORD }
            setOnPreferenceChangeListener { _, _ ->
                clearToken()
                true
            }
        }.also(screen::addPreference)
        SwitchPreferenceCompat(screen.context).apply {
            key = POPULAR_PREF
            title = "热门动画显示番剧周表"
            summary = "开启后，“热门”内容会显示当天应该更新的动画，但不一定更新"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 48
        private const val SECTION_PAGE_SIZE = 100
        private const val TOKEN_REFRESH_MARGIN = 5 * 60 * 1000L

        // InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        private const val INPUT_TYPE_PASSWORD = 0x00000081

        private const val POPULAR_PREF = "POPULAR_DISPLAY"
        private const val PREF_USERNAME = "username"
        private const val PREF_PASSWORD = "password"
        private const val PREF_TOKEN = "auth_token"
        private const val PREF_TOKEN_EXPIRES = "auth_token_expires"

        private val VIDEO_ID_REGEX = Regex("""/(?:anime|bangumi)/(\d+)""")
        private val SECTION_ID_REGEX = Regex("""/play/(\d+)$""")
    }
}
