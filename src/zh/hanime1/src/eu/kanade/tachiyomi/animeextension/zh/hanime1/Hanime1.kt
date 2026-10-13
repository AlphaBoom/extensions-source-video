package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

enum class FilterUpdateState {
    NONE,
    UPDATING,
    COMPLETED,
    FAILED,
}

@Source
abstract class Hanime1 :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val supportsLatest: Boolean
        get() = true

    override val supportsRelatedAnime = false

    override val client =
        network.client
            .newBuilder()
            .addInterceptor(::checkFiltersInterceptor)
            .addInterceptor(::thumbnailInterceptor)
            .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json by injectLazy<Json>()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val caption = doc.selectFirst("div.video-description-panel > .video-caption-text")
        return SAnime.create().apply {
            genre =
                doc
                    .select(".single-video-tag")
                    .not("[data-toggle]")
                    .eachText()
                    .joinToString()
            author = doc.select("#video-artist-name").text()
            val realTitle = caption?.previousElementSibling()?.text().orEmpty()
            title = realTitle.appendInvisibleChar()
            description = caption?.text().orEmpty()
            thumbnail_url = doc.select("video[poster]").attr("poster")
            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "裏番" || type == "泡麵番") {
                // Use the series cover image for bangumi entries instead of the episode image.
                runBlocking {
                    try {
                        val animesPage =
                            getSearchAnime(
                                1,
                                realTitle,
                                AnimeFilterList(GenreFilter(arrayOf("", type)).apply { state = 1 }),
                            )
                        thumbnail_url = animesPage.animes.first().thumbnail_url
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image", e)
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select("#playlist-scroll").first()!!.select(">div")
        return nodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select(".video-title a").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (nodes.size - index).toFloat()
                name = element.select(".video-title").text()
                if (href == response.request.url.toString()) {
                    // current video
                    val timeStr =
                        jsoup
                            .select("div.video-description-panel > div.hidden-xs")
                            .text()
                            .split(" ")
                            .last()
                    date_upload =
                        runCatching { uploadDateFormat.parse(timeStr)?.time }.getOrNull() ?: 0L
                }
            }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = parseVideos(response).toHosterList()

    protected open fun parseVideos(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sourceList = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)
        return sourceList
            .map {
                val quality = it.attr("size")
                val url = it.attr("src")
                Video(videoUrl = url, videoTitle = "${quality}P", initialized = true)
            }.sortedByDescending { preferQuality == it.videoTitle }
            .ifEmpty {
                // Try to find the source from the script content.
                val videoUrl =
                    doc
                        .select("script:containsData(source)")
                        .first()!!
                        .data()
                        .substringAfter("source = '")
                        .substringBefore("'")
                listOf(Video(videoUrl = videoUrl, videoTitle = "Raw", initialized = true))
            }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList(HotFilter))

    private fun String.appendInvisibleChar(): String {
        // The search result title will be same as one episode name of anime.
        // Adding extra char makes them has different title
        return "${this}\u200B"
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsoup = response.asJsoup()
        val nodes = jsoup.select(".horizontal-row .video-item-container:not(:has(a.video-link[target]))")
        val list =
            if (nodes.isNotEmpty()) {
                nodes.map {
                    SAnime.create().apply {
                        setUrlWithoutDomain(it.select("a.video-link").attr("href"))
                        thumbnail_url = it.select(".main-thumb").attr("abs:src")
                        title = it.select(".title").text().appendInvisibleChar()
                        author =
                            it
                                .select(".subtitle")
                                .text()
                                .split("•")
                                .getOrNull(0)
                                ?.trim()
                    }
                }
            } else {
                jsoup.select("a:not([target]) > .search-videos").map {
                    SAnime.create().apply {
                        setUrlWithoutDomain(it.parent()!!.attr("href"))
                        thumbnail_url = it.select("img").attr("src")
                        title = it.select(".home-rows-videos-title").text().appendInvisibleChar()
                    }
                }
            }
        val nextPage = jsoup.select("li.page-item a.page-link[rel=next]")
        return AnimesPage(list, nextPage.isNotEmpty())
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        if (query.isNotEmpty()) {
            searchUrl.addQueryParameter("query", query)
        }
        val timeFilters = mutableListOf<TimeFilter>()
        filters.list
            .flatMap {
                when (it) {
                    is TagsFilter -> {
                        it.state.flatMap { inner ->
                            if (inner is CategoryFilter) {
                                inner.state
                            } else {
                                listOf(inner)
                            }
                        }
                    }

                    is AnimeFilter.Group<*> -> {
                        it.state
                    }

                    else -> {
                        listOf(it)
                    }
                }
            }.forEach {
                when (it) {
                    is QueryFilter -> {
                        if (it.selected.isNotEmpty()) {
                            searchUrl.addQueryParameter(it.key, it.selected)
                        }
                    }

                    is BroadMatchFilter -> {
                        if (it.state) {
                            searchUrl.addQueryParameter(it.key, "on")
                        }
                    }

                    is TagFilter -> {
                        if (it.state) {
                            searchUrl.addQueryParameter(it.key, it.name)
                        }
                    }

                    is TimeFilter -> {
                        timeFilters.add(it)
                    }

                    else -> {}
                }
            }
        if (timeFilters.isNotEmpty()) {
            timeFilters.sort()
            timeFilters.joinToString(separator = "") { it.selected }.let {
                searchUrl.addEncodedQueryParameter("date", it)
            }
        }
        if (page > 1) {
            searchUrl.addQueryParameter("page", "$page")
        }
        return GET(searchUrl.build())
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) {
            updateFilters()
        }
        return chain.proceed(chain.request())
    }

    // ============================== Thumbnails ==============================

    /*
     * Thumbnails are signed CDN links (`?secure=<sig>,<expiry>`) valid for at most ~30 days, and the
     * library keeps whatever link was saved. An expired link is swapped at request time for a fresh one
     * from the video's watch page. The image is still cached under the saved link, so each cover is
     * refreshed at most once while it stays cached. Links found on a fetched watch page (including
     * related videos) are kept in memory so concurrent or repeated loads don't fetch it again.
     */
    private val freshThumbnails = ConcurrentHashMap<String, String>()
    private val thumbnailLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val watchPageFetches = Semaphore(MAX_THUMBNAIL_REFRESHES)

    private fun rememberThumbnails(text: String) {
        if (freshThumbnails.size > MAX_REMEMBERED_THUMBNAILS) freshThumbnails.clear()
        THUMBNAIL_URL_REGEX.findAll(text).forEach { match ->
            val url = match.value
            val key = match.groupValues[1] + match.groupValues[2]
            freshThumbnails.merge(key, url) { old, new -> if (new.thumbnailExpiry() >= old.thumbnailExpiry()) new else old }
        }
    }

    private fun String.thumbnailExpiry(): Long = substringAfterLast(',').toLongOrNull() ?: Long.MAX_VALUE

    private fun HttpUrl.isThumbnailExpired(): Boolean {
        val expiry = queryParameter("secure")?.thumbnailExpiry() ?: return true
        return expiry * 1000 <= System.currentTimeMillis() + THUMBNAIL_EXPIRY_MARGIN
    }

    private fun thumbnailInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val match = THUMBNAIL_PATH_REGEX.matchEntire(request.url.encodedPath)
        // A cache-only load must not reach out for a watch page.
        if (match == null || !request.url.host.endsWith(THUMBNAIL_HOST) || request.cacheControl.onlyIfCached) {
            return chain.proceed(request)
        }
        val (videoId, suffix) = match.destructured

        if (!request.url.isThumbnailExpired()) {
            val response = chain.proceed(request)
            // The site may re-sign before the expiry it advertised.
            if (response.code != 403) return response
            response.close()
            freshThumbnails.remove(videoId + suffix)
        }
        val freshUrl = freshThumbnailUrl(videoId, suffix, chain.call())
        if (chain.call().isCanceled()) throw IOException("Canceled")
        if (freshUrl == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(freshUrl).build())
    }

    /** Returns a valid signed link, or null if none was found or [outer] was canceled while waiting. */
    private fun freshThumbnailUrl(
        videoId: String,
        suffix: String,
        outer: Call,
    ): String? {
        fun cached() = freshThumbnails[videoId + suffix]?.takeUnless { it.toHttpUrl().isThumbnailExpired() }

        cached()?.let { return it }
        val lock = thumbnailLocks.computeIfAbsent(videoId) { ReentrantLock() }
        if (!awaitUnlessCanceled(outer) { lock.tryLock(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS) }) return null
        try {
            cached()?.let { return it }
            if (!awaitUnlessCanceled(outer) { watchPageFetches.tryAcquire(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS) }) return null
            try {
                if (outer.isCanceled()) return null
                fetchWatchPage(videoId, outer)?.let(::rememberThumbnails)
            } finally {
                watchPageFetches.release()
            }
            return cached()
        } finally {
            lock.unlock()
        }
    }

    /** Retries [attempt] until it succeeds; gives up once [outer] is canceled. */
    private inline fun awaitUnlessCanceled(
        outer: Call,
        attempt: () -> Boolean,
    ): Boolean {
        while (!attempt()) {
            if (outer.isCanceled()) return false
        }
        return true
    }

    /** Fetches the watch page, canceling the request if [outer] is canceled meanwhile. */
    private fun fetchWatchPage(
        videoId: String,
        outer: Call,
    ): String? {
        val call = client.newCall(GET("$baseUrl/watch?v=$videoId", headers))
        val page = CompletableFuture<String?>()
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!call.isCanceled()) Log.e(name, "Failed to refresh thumbnail of $videoId", e)
                    page.complete(null)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    page.complete(runCatching { response.use { if (it.isSuccessful) it.body.string() else null } }.getOrNull())
                }
            },
        )
        while (true) {
            try {
                return page.get(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (outer.isCanceled()) {
                    call.cancel()
                    return null
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING
        val exceptionHandler =
            CoroutineExceptionHandler { _, _ -> filterUpdateState = FilterUpdateState.FAILED }
        GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
            val jsoup = client.newCall(GET("$baseUrl/search")).awaitSuccess().asJsoup()
            val genreList = jsoup.select("div.genre-option div.hentai-sort-options").eachText()
            val sortList =
                jsoup.select("div.hentai-sort-options-wrapper div.hentai-sort-options").eachText()
            val yearList =
                jsoup
                    .select("select#date-year option")
                    .eachAttr("value")
                    .map { it.ifEmpty { "全部年份" } }
            val monthList =
                jsoup
                    .select("select#date-month option")
                    .eachAttr("value")
                    .map { it.ifEmpty { "全部月份" } }
            val categoryDict = mutableMapOf<String, MutableList<String>>()
            var currentKey = ""
            jsoup.select("div#tags div.modal-body").first()?.children()?.forEach {
                if (it.tagName() == "h5") {
                    currentKey = it.text()
                }
                if (it.tagName() == "label") {
                    if (currentKey in categoryDict) {
                        categoryDict[currentKey]
                    } else {
                        categoryDict[currentKey] = mutableListOf()
                        categoryDict[currentKey]
                    }!!.add(it.select("input[name]").attr("value"))
                }
            }
            preferences
                .edit()
                .putString(PREF_KEY_GENRE_LIST, genreList.joinToString())
                .putString(PREF_KEY_SORT_LIST, sortList.joinToString())
                .putString(PREF_KEY_YEAR_LIST, yearList.joinToString())
                .putString(PREF_KEY_MONTH_LIST, monthList.joinToString())
                .putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                .apply()
            filterUpdateState = FilterUpdateState.COMPLETED
        }
    }

    private fun <T : AnimeFilter.Select<*>> createFilter(
        prefKey: String,
        block: (Array<String>) -> T,
    ): T {
        val savedOptions = preferences.getString(prefKey, "")
        if (savedOptions.isNullOrEmpty()) {
            return block(emptyArray())
        }
        return block(savedOptions.split(", ").toTypedArray())
    }

    private fun createCategoryFilters(): List<AnimeFilter<out Any>> {
        val result =
            mutableListOf<AnimeFilter<out Any>>(
                BroadMatchFilter(),
            )
        val savedCategories = preferences.getString(PREF_KEY_CATEGORY_LIST, "")
        if (savedCategories.isNullOrEmpty()) {
            return result
        }
        json.decodeFromString<Map<String, List<String>>>(savedCategories).forEach {
            result.add(CategoryFilter(it.key, it.value.map { value -> TagFilter("tags[]", value) }))
        }
        return result
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) },
        createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) },
        DateFilter(
            createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) },
            createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) },
        ),
        TagsFilter(createCategoryFilters()),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_VIDEO_QUALITY
                    title = "設置首選畫質"
                    entries = arrayOf("1080P", "720P", "480P")
                    entryValues = entries
                    setDefaultValue(DEFAULT_QUALITY)
                    summary =
                        "當前選擇：${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
                    setOnPreferenceChangeListener { _, newValue ->
                        summary = "當前選擇：${newValue as String}"
                        true
                    }
                },
            )
            addPreference(
                ListPreference(context).apply {
                    key = PREF_KEY_LANG
                    title = "設置首選語言"
                    summary = "該設置僅影響影片字幕"
                    entries = arrayOf("繁體中文", "簡體中文")
                    entryValues = arrayOf("zh-CHT", "zh-CHS")
                    setOnPreferenceChangeListener { _, newValue ->
                        val baseHttpUrl = baseUrl.toHttpUrl()
                        client.cookieJar.saveFromResponse(
                            baseHttpUrl,
                            listOf(
                                Cookie.parse(
                                    baseHttpUrl,
                                    "user_lang=${newValue as String}",
                                )!!,
                            ),
                        )
                        true
                    }
                },
            )
        }
    }

    companion object {
        const val PREF_KEY_VIDEO_QUALITY = "PREF_KEY_VIDEO_QUALITY"
        const val PREF_KEY_LANG = "PREF_KEY_LANG"

        const val PREF_KEY_GENRE_LIST = "PREF_KEY_GENRE_LIST"
        const val PREF_KEY_SORT_LIST = "PREF_KEY_SORT_LIST"
        const val PREF_KEY_YEAR_LIST = "PREF_KEY_YEAR_LIST"
        const val PREF_KEY_MONTH_LIST = "PREF_KEY_MONTH_LIST"
        const val PREF_KEY_CATEGORY_LIST = "PREF_KEY_CATEGORY_LIST"

        const val DEFAULT_QUALITY = "1080P"

        private const val THUMBNAIL_HOST = "hembed.com"
        private const val MAX_THUMBNAIL_REFRESHES = 2
        private const val MAX_REMEMBERED_THUMBNAILS = 2000
        private const val THUMBNAIL_EXPIRY_MARGIN = 60_000L
        private const val CANCEL_POLL_MILLIS = 200L
        private val THUMBNAIL_PATH_REGEX = Regex("""/image/thumbnail/(\d+)([a-z]?)\.jpg""")
        private val THUMBNAIL_URL_REGEX =
            Regex("""https://[\w.-]*hembed\.com/image/thumbnail/(\d+)([a-z]?)\.jpg\?secure=[\w=-]+,\d+""")
    }
}
