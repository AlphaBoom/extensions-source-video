package keiyoushi.source

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.network.CacheControlInterceptor
import keiyoushi.network.RateLimitInterceptor
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.jsonInstance
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.brotli.Brotli
import okhttp3.brotli.BrotliInterceptor
import okhttp3.zstd.Zstd
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.days

/**
 * Suspend-first base class for Aniyomi API 17 sources, ported from Keiyoushi's `KeiSource`.
 *
 * The filter cache uses `java.util.zip` instead of zstd-kmp: Aniyomi's minified release build
 * keeps OkHttp's public API but not `com.squareup.zstd`, so extensions must not call it directly.
 */
abstract class KeiSource : AnimeHttpSource() {

    // make `headers` not lazy
    init {
        val delegate = object : Lazy<Headers> {
            override val value: Headers get() = headersBuilder().build()
            override fun isInitialized(): Boolean = true
        }

        try {
            AnimeHttpSource::class.java.getDeclaredField($$"headers$delegate").apply {
                isAccessible = true
                set(this@KeiSource, delegate)
            }
        } catch (e: ReflectiveOperationException) {
            Log.w("KeiSource", "Unable to make headers dynamic", e)
        }
    }

    /**
     * Customizes the OkHttpClient builder. Subclasses can override this to configure timeouts,
     * add application/network interceptors, or configure cookie/cache/dns settings.
     */
    protected open fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this

    final override val client: OkHttpClient by lazy {
        network.client.newBuilder().apply {
            with(interceptors()) {
                check(
                    this.any { it.javaClass.simpleName == "UncaughtExceptionInterceptor" },
                ) {
                    "UncaughtExceptionInterceptor must be present in default client"
                }

                check(
                    this.any { it.javaClass.simpleName == "UserAgentInterceptor" },
                ) {
                    "UserAgentInterceptor must be present in default client"
                }

                check(
                    this.any { it.javaClass.simpleName == "CloudflareInterceptor" },
                ) {
                    "CloudflareInterceptor must be present in default client"
                }
            }

            with(networkInterceptors()) {
                check(
                    this.none { it.javaClass.simpleName == "IgnoreGzipInterceptor" },
                ) {
                    "IgnoreGzipInterceptor must not be present in default client"
                }

                check(
                    this.none { it is BrotliInterceptor },
                ) {
                    "BrotliInterceptor must not be present in default client"
                }
            }

            configureClient()

            // cf interceptor below source specific interceptors
            interceptors().apply {
                // some sources remove the cf interceptor, so do nullable check
                val cloudflareInterceptor = firstOrNull { it.javaClass.simpleName == "CloudflareInterceptor" }
                if (cloudflareInterceptor != null) {
                    remove(cloudflareInterceptor)
                    add(cloudflareInterceptor)
                }
            }

            // last application interceptor
            addInterceptor(CompressionInterceptor(Brotli, Gzip, Zstd))

            // allow caching when server doesn't explicitly say anything
            addNetworkInterceptor(CacheControlInterceptor())

            // keep the source's rate limiter as the last network interceptor
            networkInterceptors().apply {
                val rateLimiter = firstInstanceOrNull<RateLimitInterceptor>()
                if (rateLimiter != null) {
                    remove(rateLimiter)
                    add(rateLimiter)
                }
            }
        }.build()
    }

    /**
     * Customizes the base Headers builder. Subclasses can override this to append source-specific
     * headers.
     *
     * Note: "User-Agent", "Referer", and "Origin" are already added by default.
     */
    protected open fun Headers.Builder.configureHeaders(): Headers.Builder = this

    final override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .configureHeaders()

    /**
     * Fetches a page of popular anime from the source.
     *
     * @param page The page number to retrieve.
     * @return An [AnimesPage] containing the list of anime and whether there is a next page.
     */
    abstract override suspend fun getPopularAnime(page: Int): AnimesPage

    /**
     * Whether the source supports fetching a list of latest anime updates.
     */
    override val supportsLatest get() = true

    /**
     * Fetches a page of latest anime updates from the source.
     *
     * @param page The page number to retrieve.
     * @return An [AnimesPage] containing the list of updated anime and whether there is a next page.
     */
    abstract override suspend fun getLatestUpdates(page: Int): AnimesPage

    /**
     * Fetches a page of anime matching the query and filters.
     *
     * @param page The page number to retrieve.
     * @param query The search query.
     * @param filters The list of filters to apply.
     * @return An [AnimesPage] containing the matching anime and whether there is a next page.
     */
    abstract suspend fun getSearchAnimeList(page: Int, query: String, filters: AnimeFilterList): AnimesPage

    /**
     * Fetches details of a single anime directly using its HTTP URL.
     * Used for resolving URL search queries in the browser, search bar or deeplinks.
     *
     * @param url The [HttpUrl] of the anime.
     * @return The [SAnime] details if resolved successfully, or null.
     */
    protected open suspend fun getAnimeByUrl(url: HttpUrl): SAnime? = throw Exception("getAnimeByUrl not implemented")

    /**
     * Fetches a page of anime list from given url.
     * Not needed for most sources
     *
     * @param url the [HttpUrl] of an anime list
     * @param page the page number to retrieve
     * @return an [AnimesPage] containing the list of anime and whether there is a next page
     */
    protected open suspend fun getAnimesByUrl(url: HttpUrl, page: Int): AnimesPage {
        val anime = getAnimeByUrl(url)

        return AnimesPage(listOfNotNull(anime), hasNextPage = false)
    }

    final override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        query.toHttpUrlOrNull()?.also { url ->
            return getAnimesByUrl(url, page)
        }

        return getSearchAnimeList(page, query, filters)
    }

    private val filterFetchInFlight = AtomicBoolean(false)
    private val filterFetchAttemptCount = AtomicInteger(0)
    private val maxFilterFetchAttempts = 3
    private val filterCacheDir: File get() =
        applicationContext.cacheDir.resolve("source_$id")
            .apply { mkdirs() }
    private val filterCacheFile: File get() =
        filterCacheDir.resolve("filters.json.gz")

    /**
     * Whether this source fetches its filters from the network
     * implement [fetchFilterData] when true
     */
    protected open val supportsFilterFetching: Boolean get() = false

    /**
     * Text shown as a filter header hint while filters are unavailable
     * Do not override by hand
     */
    protected open val filterFetchHint: String get() = "Tap 'Reset' to load filters"

    /**
     * Fetches filter data from the network.
     *
     * Only called when [supportsFilterFetching] is true, on a background coroutine.
     * Implementations should perform whatever requests are necessary and return
     * the result as a [JsonElement]
     *
     * Throwing is treated as a failed attempt and retried (see [maxFilterFetchAttempts]);
     */
    protected open suspend fun fetchFilterData(): JsonElement = JsonNull

    /**
     * Builds an [AnimeFilterList] from previously cached/fetched filter data.
     *
     * This is called synchronously every time [getFilterList] is invoked, so it must be
     * fast and must NOT perform I/O or network requests — only convert [data] into filters.
     *
     * @param data The cached filter data or null when no data is available yet
     * (e.g. first launch or fetching failed or cache deleted).
     */
    protected open fun getFilterList(data: JsonElement?): AnimeFilterList = AnimeFilterList()

    final override fun getFilterList(): AnimeFilterList {
        if (!supportsFilterFetching) return getFilterList(data = null)

        val (cached, isFresh) = readFilterCacheState()
        val parsed = cached?.let {
            try {
                getFilterList(it)
            } catch (e: Exception) {
                Log.e(name, "Failed to parse filter data", e)
                null
            }
        }

        if (parsed == null || !isFresh) {
            triggerBackgroundFilterFetch()
        }

        if (parsed != null) return parsed

        return AnimeFilterList(
            buildList {
                addAll(getFilterList(data = null))
                if (isNotEmpty()) {
                    add(AnimeFilter.Separator())
                }
                add(AnimeFilter.Header(filterFetchHint))
            },
        )
    }

    private data class FilterCacheState(val data: JsonElement?, val isFresh: Boolean)

    private fun readFilterCacheState(): FilterCacheState {
        val file = filterCacheFile
        if (!file.exists()) return FilterCacheState(data = null, isFresh = false)

        val age = System.currentTimeMillis() - file.lastModified()
        val isFresh = age <= 3.days.inWholeMilliseconds

        val data = try {
            GZIPInputStream(file.inputStream()).use { input ->
                jsonInstance.decodeFromStream(JsonElement.serializer(), input)
            }
        } catch (_: Exception) {
            null
        }

        return FilterCacheState(data, isFresh)
    }

    private fun writeFilterCache(data: JsonElement) {
        val tmpFile = File.createTempFile("filters", ".tmp", filterCacheDir)

        try {
            GZIPOutputStream(tmpFile.outputStream()).use { output ->
                jsonInstance.encodeToStream(JsonElement.serializer(), data, output)
            }

            if (!tmpFile.renameTo(filterCacheFile)) {
                filterCacheFile.delete()
                if (!tmpFile.renameTo(filterCacheFile)) {
                    throw IOException("Failed to move $tmpFile to $filterCacheFile")
                }
            }
        } finally {
            tmpFile.delete()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun triggerBackgroundFilterFetch() {
        if (filterFetchAttemptCount.get() >= maxFilterFetchAttempts) return
        if (!filterFetchInFlight.compareAndSet(false, true)) return

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val data = fetchFilterData()
                writeFilterCache(data)
                filterFetchAttemptCount.set(0)
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch filter data", e)
                filterFetchAttemptCount.incrementAndGet()
            } finally {
                filterFetchInFlight.set(false)
            }
        }
    }

    /**
     * Fetches updated information for an anime.
     *
     * Depending on the provided flags or source availability, this may include
     * updated anime metadata, available episodes, or both.
     *
     * If a value is not requested, the existing provided value can be returned as-is.
     * The host app may apply any returned updates regardless of the flags,
     * so it’s best to avoid returning unintended or inaccurate data.
     *
     * @param anime The anime to fetch updates for.
     * @param episodes Existing episodes of the anime
     * @param fetchDetails Whether to include updated anime details.
     * @param fetchEpisodes Whether to include available episodes.
     */
    abstract suspend fun fetchAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate

    private val updatesInFlight = ConcurrentHashMap<String, Boolean>()

    final override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        check(fetchDetails || fetchEpisodes) { "getAnimeEpisodeUpdate called with nothing to fetch (fetchDetails=false, fetchEpisodes=false)" }

        check(updatesInFlight.putIfAbsent(anime.url, true) == null) { "getAnimeEpisodeUpdate must not be called concurrently for same anime" }

        try {
            val update = fetchAnimeEpisodeUpdate(anime, episodes, fetchDetails, fetchEpisodes)

            return SAnimeEpisodeUpdate(
                anime = update.anime.apply { initialized = true },
                episodes = update.episodes,
            )
        } finally {
            updatesInFlight.remove(anime.url)
        }
    }

    /**
     * Whether the source supports retrieving related anime.
     */
    override val supportsRelatedAnime get() = false

    /**
     * Fetches a list of related anime for the specified anime.
     *
     * Only called when [supportsRelatedAnime] is true.
     *
     * @param anime The reference anime.
     * @return A list of [AnimeRelation]s.
     */
    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    /**
     * Returns the absolute web URL for the provided anime.
     * Used for the WebView ("Open in WebView") and sharing features.
     *
     * @param anime The anime.
     * @return The absolute URL of the anime.
     */
    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    /**
     * Returns the absolute web URL for the provided episode.
     * Used for the WebView ("Open in WebView") and sharing features.
     *
     * @param episode The episode.
     * @return The absolute URL of the episode.
     */
    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    /**
     * Get the list of hosters an episode has. Each hoster's videos are either returned
     * preloaded in [Hoster.videoList] or fetched lazily through [getVideoList].
     *
     * @param episode the episode.
     * @return the hosters for the episode.
     */
    abstract override suspend fun getHosterList(episode: SEpisode): List<Hoster>
}
