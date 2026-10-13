package eu.kanade.tachiyomi.animeextension.zh.xfani

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 稀饭动漫 Next：前端为 Next.js，数据来自 Supabase（api.xifanacg.com）。
 * 列表/详情/搜索走 PostgREST，选集线路取自页面 SSR 数据，播放地址由 issue-web-playback 签发。
 */
@Source
abstract class Xfani :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val supportsLatest: Boolean
        get() = true

    override val supportsRelatedAnime = false

    private val apiUrl = "https://api.xifanacg.com"

    private val json by injectLazy<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager =
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit

                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    override val client: OkHttpClient by lazy {
        if (preferences.getBoolean(PREF_KEY_IGNORE_SSL_ERROR, false)) {
            network.client.newBuilder().ignoreAllSSLErrors()
        } else {
            network.client.newBuilder().addInterceptor(::checkSSLErrorInterceptor)
        }.build()
    }

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .set("apikey", API_KEY)
            .set("Authorization", "Bearer $API_KEY")
            .build()
    }

    private val preferredSourceCode: String
        get() {
            val index = preferences.getString(PREF_KEY_VIDEO_SOURCE, DEFAULT_VIDEO_SOURCE)!!
                .toIntOrNull() ?: 0
            return SOURCE_CODES.getOrElse(index) { SOURCE_CODES[0] }
        }

    private fun checkSSLErrorInterceptor(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: SSLHandshakeException) {
            throw SSLHandshakeException("SSL证书验证异常，可以尝试在设置中忽略SSL验证问题。")
        }
    }

    // ============================== List ==============================

    private fun animeTableUrl(): HttpUrl.Builder = "$apiUrl/rest/v1/animes".toHttpUrl().newBuilder()
        .addQueryParameter("select", ANIME_COLUMNS)
        .addQueryParameter("is_published", "eq.true")

    private fun HttpUrl.Builder.page(page: Int): HttpUrl.Builder = addQueryParameter("limit", "$PAGE_SIZE")
        .addQueryParameter("offset", "${(page - 1) * PAGE_SIZE}")

    private fun HttpUrl.Builder.orderBy(sort: String): HttpUrl.Builder {
        val direction = if (sort == "title") "asc" else "desc.nullslast"
        return addQueryParameter("order", "$sort.$direction,id.desc")
    }

    override fun popularAnimeRequest(page: Int): Request = GET(animeTableUrl().orderBy("view_count").page(page).build(), apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage = animeTableParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(animeTableUrl().orderBy("updated_at").page(page).build(), apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = animeTableParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "view_count"
        if (query.isNotBlank()) {
            val body = SearchRequest(
                searchTerm = query.trim(),
                pageNumber = page,
                itemsPerPage = PAGE_SIZE,
                sortBy = sort,
                sortOrder = if (sort == "title") "asc" else "desc",
            )
            return POST(
                "$apiUrl/rest/v1/rpc/search_animes",
                apiHeaders.newBuilder().set(HEADER_PAGE, "$page").build(),
                json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE),
            )
        }
        val url = animeTableUrl()
        filters.forEach { filter ->
            if (filter !is SelectFilter || filter.selected.isEmpty()) return@forEach
            when (filter) {
                is FormatFilter -> url.addQueryParameter("format", "eq.${filter.selected}")
                is StatusFilter -> url.addQueryParameter("is_finished", "eq.${filter.selected}")
                is YearFilter -> url.addQueryParameter("release_year", filter.selected)
                is TagFilter -> url.addQueryParameter("meta_tags", "cs.{\"${filter.selected}\"}")
                else -> {}
            }
        }
        return GET(url.orderBy(sort).page(page).build(), apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.endsWith("/rpc/search_animes")) {
            val list = response.parseAs<List<AnimeDto>>()
            val total = list.firstOrNull()?.totalCount ?: 0
            val page = response.request.header(HEADER_PAGE)?.toIntOrNull() ?: 1
            return AnimesPage(list.map { it.toSAnime() }, page * PAGE_SIZE < total)
        }
        return animeTableParse(response)
    }

    private fun animeTableParse(response: Response): AnimesPage {
        val list = response.parseAs<List<AnimeDto>>()
        return AnimesPage(list.map { it.toSAnime() }, list.size >= PAGE_SIZE)
    }

    private fun AnimeDto.toSAnime() = SAnime.create().apply {
        url = "/anime/$id"
        title = this@toSAnime.title
        thumbnail_url = coverUrl
        author = director?.takeIf { it.isNotBlank() }
        artist = actors?.filter { it.isNotBlank() }?.joinToString()?.takeIf { it.isNotBlank() }
        genre = metaTags?.joinToString()
        status = when (isFinished) {
            true -> SAnime.COMPLETED
            false -> SAnime.ONGOING
            null -> SAnime.UNKNOWN
        }
        description = buildString {
            this@toSAnime.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
            val extra = listOfNotNull(
                titleOriginal?.takeIf { it.isNotBlank() }?.let { "原名：$it" },
                aliases?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                    ?.let { "别名：${it.joinToString(" / ")}" },
                releaseYear?.let { "年份：$it" },
                bangumiScore?.takeIf { it > 0 }?.let { "评分：$it" },
            )
            if (extra.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extra.joinToString("\n"))
            }
        }
    }

    // ============================== Details ==============================

    /** 旧版站点的条目 URL 为 /bangumi/{vod_id}.html，新站保留了 legacy_vod_id 映射。 */
    private fun legacyVodId(url: String): String? = LEGACY_URL_REGEX.find(url)?.groupValues?.get(1)

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = "$apiUrl/rest/v1/animes".toHttpUrl().newBuilder()
            .addQueryParameter("select", ANIME_COLUMNS)
        val legacyId = legacyVodId(anime.url)
        if (legacyId != null) {
            url.addQueryParameter("legacy_vod_id", "eq.$legacyId")
        } else {
            url.addQueryParameter("id", "eq.${anime.url.animeId()}")
        }
        return GET(url.build(), apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<List<AnimeDto>>().firstOrNull()?.toSAnime()
        ?: throw Exception("番剧不存在或已下架")

    override fun getAnimeUrl(anime: SAnime): String {
        // 旧链接在新站已失效，WebView 直接打开首页
        return if (legacyVodId(anime.url) != null) baseUrl else baseUrl + anime.url
    }

    private fun String.animeId(): String = ANIME_ID_REGEX.find(this)?.groupValues?.get(1) ?: throw Exception("无法识别的番剧链接：$this")

    private suspend fun resolveAnimePath(anime: SAnime): String {
        if (legacyVodId(anime.url) == null) return "/anime/${anime.url.animeId()}"
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val dto = response.parseAs<List<AnimeDto>>().firstOrNull()
            ?: throw Exception("该番剧在新版站点中不存在，请重新搜索后迁移")
        return "/anime/${dto.id}"
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animePath = resolveAnimePath(anime)
        val document = client.newCall(GET(baseUrl + animePath, headers)).awaitSuccess().asJsoup()
        val sources = parseSources(document)
        val episodes = LinkedHashMap<Long, EpisodeDto>()
        sources.sortedByDescending { it.code == preferredSourceCode }.forEach { source ->
            source.episodes.forEach { episodes.putIfAbsent(it.id, it) }
        }
        return episodes.values
            .sortedWith(compareBy({ it.kind != "main" }, { it.episodeNumber ?: Double.MAX_VALUE }))
            .map { episode ->
                SEpisode.create().apply {
                    url = "$animePath/play/${episode.id}"
                    name = episode.displayName()
                    episode_number = episode.episodeNumber?.toFloat() ?: -1f
                }
            }
            .reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun EpisodeDto.displayName(): String {
        val number = episodeNumber?.let {
            if (it % 1.0 == 0.0) it.toLong().toString().padStart(2, '0') else it.toString()
        }
        val prefix = when {
            number == null -> kind?.uppercase().orEmpty()
            kind == null || kind == "main" -> "第${number}集"
            else -> "${kind.uppercase()} $number"
        }
        return listOf(prefix, title.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
            .ifEmpty { "第${id}集" }
    }

    /**
     * 从 Next.js 的 flight 数据（self.__next_f.push 的字符串片段）中取出线路及其选集列表。
     */
    private fun parseSources(document: Document): List<SourceDto> {
        val flight = document.select("script:containsData(self.__next_f.push)").joinToString("") { script ->
            FLIGHT_CHUNK_REGEX.findAll(script.data()).joinToString("") { match ->
                json.parseToJsonElement("\"${match.groupValues[1]}\"").jsonPrimitive.content
            }
        }
        val start = flight.indexOf(SOURCES_MARKER).takeIf { it >= 0 }
            ?.let { it + SOURCES_MARKER.length - 2 }
            ?: throw Exception("未找到播放线路，可能需要登录或页面结构已变化")
        val end = findJsonEnd(flight, start)
        return json.decodeFromString<List<SourceDto>>(flight.substring(start, end))
    }

    private fun findJsonEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> if (--depth == 0) return i + 1
            }
        }
        throw Exception("播放线路数据不完整")
    }

    // ============================== Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeId = EPISODE_ID_REGEX.find(episode.url)?.groupValues?.get(1)?.toLong()
            ?: throw Exception("无法识别的剧集链接，请刷新剧集列表")
        val document = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().asJsoup()
        val sources = parseSources(document)
            .filter { source -> source.episodes.any { it.id == episodeId } }
            .sortedByDescending { it.code == preferredSourceCode }
        if (sources.isEmpty()) throw Exception("本集暂无可用线路")

        // 首选线路立即签发播放地址，其余线路在切换时再签发，避免一次性请求过多触发限流
        val firstUrl = runCatching { issuePlayback(episodeId, sources.first().id) }
        if (sources.size == 1) firstUrl.getOrThrow()
        return sources.mapIndexed { index, source ->
            val videoUrl = if (index == 0) firstUrl.getOrNull() else null
            Hoster(
                hosterUrl = playbackKey(episode.url, source),
                hosterName = source.name,
                videoList = videoUrl?.let { listOf(playbackVideo(it, source.name)) },
                lazy = videoUrl == null,
                memo = buildJsonObject {
                    put(MEMO_EPISODE_ID, episodeId)
                    put(MEMO_SOURCE_ID, source.id)
                },
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeId = hoster.memo[MEMO_EPISODE_ID]?.jsonPrimitive?.long
        val sourceId = hoster.memo[MEMO_SOURCE_ID]?.jsonPrimitive?.int
        if (episodeId == null || sourceId == null) throw Exception("无法识别的播放线路")
        return listOf(playbackVideo(issuePlayback(episodeId, sourceId), hoster.hosterName))
    }

    private fun playbackVideo(url: String, title: String) = Video(videoUrl = url, videoTitle = title, initialized = true)

    private fun playbackKey(episodeUrl: String, source: SourceDto): String = (baseUrl + episodeUrl).toHttpUrl().newBuilder()
        .setQueryParameter("source", source.code)
        .setQueryParameter("source_id", source.id.toString())
        .build()
        .toString()

    private fun playbackRequest(episodeId: Long, sourceId: Int): Request = POST(
        "$apiUrl/functions/v1/issue-web-playback",
        apiHeaders,
        json.encodeToString(PlaybackRequest("fallback", episodeId, sourceId))
            .toRequestBody(JSON_MEDIA_TYPE),
    )

    private suspend fun issuePlayback(episodeId: Long, sourceId: Int): String {
        val response = client.newCall(playbackRequest(episodeId, sourceId)).await()
        return parsePlayback(response)
    }

    private fun parsePlayback(response: Response): String {
        val result = response.use {
            runCatching { json.decodeFromString<PlaybackResponse>(it.body.string()) }.getOrNull()
        } ?: throw Exception("获取播放地址失败（HTTP ${response.code}）")
        val url = result.url
        if (result.ok && !url.isNullOrBlank()) {
            // 部分线路返回未编码的中文路径，统一转成合法 URL
            return url.toHttpUrl().toString()
        }
        throw Exception(
            when (result.error) {
                "rate_limited" -> "请求过于频繁，请${result.retryAfter?.let { "$it 秒后" } ?: "稍后"}重试"
                "unauthenticated", "forbidden" -> "该线路需要登录后播放，请切换其他线路"
                "not_found" -> "该线路暂无本集资源"
                "service_unavailable" -> "播放服务暂不可用，请稍后重试"
                else -> "获取播放地址失败：${result.error ?: "HTTP ${response.code}"}"
            },
        )
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("关键词搜索时仅排序生效"),
        FormatFilter(),
        StatusFilter(),
        YearFilter(),
        TagFilter(),
        SortFilter(),
    )

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(screen.context).apply {
                    key = PREF_KEY_VIDEO_SOURCE
                    title = "请设置首选视频源线路"
                    entries = arrayOf("稀饭新番主线-1", "稀饭新番主线-2", "稀饭备用-1")
                    entryValues = arrayOf("0", "1", "2")
                    setDefaultValue(DEFAULT_VIDEO_SOURCE)
                    summary = "%s"
                },
            )
            addPreference(
                SwitchPreferenceCompat(screen.context).apply {
                    key = PREF_KEY_IGNORE_SSL_ERROR
                    title = "忽略SSL证书校验"
                    setDefaultValue(false)
                    setOnPreferenceChangeListener { _, _ ->
                        Toast.makeText(screen.context, "重启应用后生效", Toast.LENGTH_SHORT).show()
                        true
                    }
                },
            )
        }
    }

    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromString<T>(it.body.string())
    }

    companion object {
        const val PREF_KEY_VIDEO_SOURCE = "PREF_KEY_VIDEO_SOURCE"
        const val PREF_KEY_IGNORE_SSL_ERROR = "PREF_KEY_IGNORE_SSL_ERROR"

        const val DEFAULT_VIDEO_SOURCE = "0"

        /** 与设置项 entryValues 的下标一一对应。 */
        private val SOURCE_CODES = listOf("xfxf1", "AL", "CS")

        /** 网页前端内置的 Supabase publishable key（公开的匿名访问密钥）。 */
        private const val API_KEY = "sb_publishable_OBIVAWACIX6lPXrO98_z24_HcsmalkA"

        private const val PAGE_SIZE = 24
        private const val HEADER_PAGE = "X-Xfani-Page"
        private const val ANIME_COLUMNS =
            "id,title,title_original,aliases,cover_url,description,director,actors," +
                "meta_tags,is_finished,release_year,bangumi_score"
        private const val MEMO_EPISODE_ID = "episode_id"
        private const val MEMO_SOURCE_ID = "source_id"
        private const val SOURCES_MARKER = "\"sources\":[{"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val LEGACY_URL_REGEX = Regex("""/bangumi/(\d+)\.html""")
        private val ANIME_ID_REGEX = Regex("""/anime/(\d+)""")
        private val EPISODE_ID_REGEX = Regex("""/play/(\d+)""")
        private val FLIGHT_CHUNK_REGEX = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"]\)""")
    }
}
