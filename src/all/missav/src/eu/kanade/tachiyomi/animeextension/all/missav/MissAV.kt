package eu.kanade.tachiyomi.animeextension.all.missav

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.hlsproxy.FakeImageJunkTransformer
import keiyoushi.lib.hlsproxy.HlsProxy
import keiyoushi.lib.hlsproxy.HlsProxyOptions
import keiyoushi.lib.javcoverfetcher.JavCoverFetcher
import keiyoushi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import keiyoushi.lib.playlistutils.PlaylistUtils
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

// The domain picker comes from the mirrors in build.gradle.kts and applies after an app restart.
@Source
abstract class MissAV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    private val preferences by getPreferencesLazy()

    override val supportsLatest = true

    override val supportsRelatedAnime = true

    private val docHeaders: Headers by lazy {
        headers.newBuilder().apply {
            set("Origin", baseUrl)
            set("Referer", "$baseUrl/")
        }.build()
    }

    private val playlistExtractor by lazy {
        PlaylistUtils(client, docHeaders)
    }

    private val hlsProxy by lazy { HlsProxy(client) }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en/today-hot?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/en/new?page=$page", docHeaders)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val genre = filters.firstInstanceOrNull<GenreList>()?.selected
            if (query.isNotEmpty()) {
                addEncodedPathSegments("en/search")
                addPathSegment(query.trim())
            } else if (genre != null) {
                addEncodedPathSegments(genre)
            } else {
                addEncodedPathSegments("en/new")
            }
            filters.firstInstanceOrNull<SortFilter>()?.selected?.let {
                addQueryParameter("sort", it)
            }
            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        if (document.selectFirst("div[x-data*=handleRecommendResponse]") != null) {
            val url = response.request.url
            val pathSegments = url.pathSegments
            // pathSegments are already percent-decoded.
            val query = pathSegments.getOrNull(pathSegments.indexOf("search") + 1)
                ?: throw Exception("Failed to parse search query from URL: $url")
            val page = url.queryParameter("page")?.toIntOrNull() ?: 1
            client.newCall(fallbackApiSearch(query, page))
                .execute().use {
                    if (!it.isSuccessful) {
                        Log.e("MissAv", "Failed to fetch search results: ${it.code}")
                        throw Exception("No more results found")
                    }

                    val data = it.body.string().parseAs<RecommendationsResponse>()
                    recommMap[query] = data.recommId
                    return data.toAnimePage()
                }
        }

        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return AnimesPage(entries, hasNextPage)
    }

    private val recommMap: MutableMap<String, String> = ConcurrentHashMap()

    private fun fallbackApiSearch(query: String, page: Int): Request {
        val recommId = recommMap[query]
        return if (page == 1 || recommId == null) {
            val body = MissAvApi.searchData(query)
                .toRequestBody(JSON_MEDIA_TYPE)
            POST(MissAvApi.searchURL(getUuid()), docHeaders, body)
        } else {
            val body = MissAvApi.recommData
                .toRequestBody(JSON_MEDIA_TYPE)
            POST(MissAvApi.recommURL(recommId), docHeaders, body)
        }
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> {
        val body = MissAvApi.relatedData(getUuid(), anime.url.substringAfterLast("/"))
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = client.newCall(POST(MissAvApi.relatedURL(), docHeaders, body)).awaitSuccess()
        val animes = response.body.string().parseAs<List<RelatedResponse>>()
            .flatMap { it.toAnimeList() }
            .distinctBy { it.url }
        return if (animes.isEmpty()) emptyList() else listOf(AnimeRelation("Related", animes))
    }

    override fun getFilterList() = getFilters()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val jpTitle = document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover = document.selectFirst("video.player")?.attr("abs:data-poster")

        return SAnime.create().apply {
            title = document.selectFirst("h1.text-base")!!.text()
            genre = document.getInfo("/genres/")
            author = listOfNotNull(
                document.getInfo("/directors/"),
                document.getInfo("/makers/"),
            ).joinToString()
            artist = document.getInfo("/actresses/")
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst("div.mb-1")?.text()?.also { append("$it\n") }

                document.getInfo("/labels/")?.also { append("\nLabel: $it") }
                document.getInfo("/series/")?.also { append("\nSeries: $it") }

                document.select("div.text-secondary:not(:has(a)):has(span)")
                    .eachText()
                    .forEach { append("\n$it") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else {
                siteCover
            }
        }
    }

    private fun Element.getInfo(urlPart: String) = select("div.text-secondary > a[href*=$urlPart]")
        .eachText()
        .joinToString()
        .takeIf(String::isNotBlank)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Episode"
        },
    )

    override fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, docHeaders)

    override fun hosterListParse(response: Response): List<Hoster> = videoListParse(response).toHosterList()

    private fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val playlists = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(Unpacker::unpack)?.ifEmpty { null }
            ?: return emptyList()

        val masterPlaylist = playlists.substringAfter("source=\"").substringBefore("\";")

        // Segments carry fake image headers; the local proxy strips them before playback.
        val proxyOptions = HlsProxyOptions(bodyTransformers = listOf(FakeImageJunkTransformer))
        return playlistExtractor.extractFromHls(masterPlaylist, referer = "$baseUrl/")
            .map { hlsProxy.proxy(it, proxyOptions).copy(initialized = true) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES.toTypedArray()
            entryValues = PREF_QUALITY_VALUES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    private fun getUuid(): String = preferences.getString(PREF_UUID_KEY, null) ?: synchronized(this) {
        // Double-check pattern to avoid generating UUID if another thread already did
        preferences.getString(PREF_UUID_KEY, null) ?: run {
            val uuid = MissAvApi.generateUUID()
            preferences.edit().putString(PREF_UUID_KEY, uuid).apply()
            uuid
        }
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()

        private const val PREF_UUID_KEY = "missav_uuid"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
