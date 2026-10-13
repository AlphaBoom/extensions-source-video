package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Application
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Rule34Video :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    override val supportsRelatedAnime = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client =
        network.client
            .newBuilder()
            .addInterceptor(ddgInterceptor)
            .build()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun seasonListSelector(): String = ""

    override fun seasonFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.e("Rule34Video", "Loading popular videos from uploader ID: $uploaderId, page: $page, URL: $url")
            GET(url)
        } else {
            Log.e("Rule34Video", "Uploader filter enabled but ID is blank, loading latest updates.")
            GET("$baseUrl/latest-updates/$page/")
        }
    } else {
        GET("$baseUrl/latest-updates/$page/")
    }

    // Video cards carry a video id; ad cards in the same grid do not. Uploader pages use the `ma-v` card layout.
    override fun popularAnimeSelector() = "div.item.thumb[data-video-card-id], div.item.ma-v[data-video-card-id]"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.th, a.ma-thumb")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element
            .selectFirst("div.thumb_title, a.ma-v__t")
            ?.text()
            ?.ifEmpty { null }
            ?: link.attr("title")
        thumbnail_url = element.selectFirst("img.thumb")?.imageUrl()
    }

    // Covers are lazy-loaded: the first few cards carry the real URL in `src`,
    // the rest use a data URI placeholder in `src` and the real URL in `data-rd-src`.
    private fun Element.imageUrl(): String? = THUMBNAIL_ATTRS
        .asSequence()
        .map { attr("abs:$it") }
        .firstOrNull { it.startsWith("http") }

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() = (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            // Watch URLs look like /video(s)/<id>/ with an optional /<slug>/; open the video page directly.
            val url = query.toHttpUrl()
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val isVideoUrl =
                url.host.removePrefix("www.") == baseUrl.toHttpUrl().host &&
                    segments.getOrNull(0) in listOf("video", "videos") &&
                    segments.getOrNull(1)?.all(Char::isDigit) == true
            if (!isVideoUrl) {
                throw Exception("Unsupported url")
            }
            val path = "/" + segments.joinToString("/") + "/"
            val document = client.newCall(GET(baseUrl + path, headers)).awaitSuccess().asJsoup()
            val anime =
                animeDetailsParse(document).apply {
                    setUrlWithoutDomain(path)
                    initialized = true
                }
            return AnimesPage(listOf(anime), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val orderFilter = filters.getUriPart<OrderFilter>()
        val categoryFilter = filters.getUriPart<CategoryBy>()
        val sortType =
            when (orderFilter) {
                "latest-updates" -> "post_date"
                "most-popular" -> "video_viewed"
                "top-rated" -> "rating"
                else -> ""
            }

        val tagFilter = (filters.find { it is TagFilter } as? TagFilter)?.state ?: ""

        val url = "$baseUrl/search_ajax.php?tag=${tagFilter.ifBlank { "." }}"
        val response = client.newCall(GET(url, headers)).execute()
        tagDocument = response.asJsoup()

        val tagSearch = filters.getUriPart<TagSearch>()

        return if (query.isNotEmpty()) {
            if (query.startsWith(PREFIX_SEARCH)) {
                val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
                GET("$baseUrl/search/$newQuery")
            } else {
                GET("$baseUrl/search/${query.replace(Regex("\\s"), "-")}/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch")
            }
        } else {
            GET("$baseUrl/search/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")?.text().orEmpty()
        thumbnail_url = FLASHVARS_PREVIEW_REGEX.find(document.html())?.groupValues?.get(1)

        val info = document.selectFirst("#tab_video_info") ?: document

        fun group(label: String) = info.select("div.vp-group:has(> div.label:contains($label))")

        val artists = group("Artist").select("a.item span.name").eachText()
        author = artists.joinToString()

        description =
            buildString {
                // wholeText() keeps <br> line breaks
                info.selectFirst("div.vp-desc")?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(it)
                    append("\n\n")
                }

                // Upload date, views and duration, in that order
                val meta = document.select("div.vp-meta span.item_info > span").eachText()
                meta.getOrNull(0)?.let { append("Uploaded: $it\n") }

                if (artists.isNotEmpty()) {
                    append("Artists: ${artists.joinToString()}\n")
                }

                val categories = group("Categories").select("a.item span").eachText().joinToString()
                if (categories.isNotEmpty()) {
                    append("Categories: $categories\n")
                }

                val uploader = info.selectFirst("div.col:has(> div.label:contains(Uploaded by)) a.item")?.text().orEmpty()
                if (uploader.isNotEmpty()) {
                    append("Uploader: $uploader\n")
                }

                meta.getOrNull(1)?.let { views ->
                    val count = views.substringAfter("(", "").substringBefore(")").ifEmpty { views }
                    append("Views: ${count.replace(",", "")}\n")
                }
                meta.getOrNull(2)?.let { append("Duration: $it\n") }

                val qualities =
                    document
                        .select(DOWNLOAD_LINK_SELECTOR)
                        .map { it.ownText().substringAfter(" ") }
                        .joinToString()
                if (qualities.isNotEmpty()) {
                    append("Quality: $qualities")
                }
            }.trim()

        genre =
            info
                .select("div.vp-tags a.tag_item:not(.tag_item_suggest)")
                .eachText()
                .joinToString()

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Video"
        },
    )

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun hosterListParse(response: Response): List<Hoster> = parseVideos(response).toHosterList()

    protected open fun parseVideos(response: Response): List<Video> {
        val videoHeaders =
            headersBuilder()
                .set("Referer", "$baseUrl/")
                .set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                .build()

        val document = response.asJsoup()

        // The download sheet lists every quality as a plain get_file link. The player can
        // follow its redirect to the CDN itself, so the links are passed through unresolved.
        val downloads =
            document.select(DOWNLOAD_LINK_SELECTOR).mapNotNull { element ->
                val url =
                    element
                        .attr("abs:href")
                        .toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.removeAllQueryParameters("download")
                        ?.removeAllQueryParameters("download_filename")
                        ?.build()
                        ?.toString()
                        ?: return@mapNotNull null
                val quality = element.ownText().substringAfter(" ")
                Video(videoUrl = url, videoTitle = quality, headers = videoHeaders, initialized = true)
            }
        if (downloads.isNotEmpty()) return downloads

        // Fall back to the player configuration when downloads are not offered.
        val flashvars = document.selectFirst("script:containsData(flashvars)")?.data().orEmpty()
        val values = FLASHVARS_ENTRY_REGEX.findAll(flashvars).associate { it.groupValues[1] to it.groupValues[2] }
        return values.keys.filter { FLASHVARS_URL_KEY_REGEX.matches(it) }.mapNotNull { key ->
            val url = values[key]?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val quality = values["${key}_text"] ?: url.substringAfterLast("_").substringBefore(".")
            Video(videoUrl = url, videoTitle = quality, headers = videoHeaders, initialized = true)
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: return this
        return sortedWith(
            compareByDescending<Video> { it.videoTitle == quality }
                .thenByDescending { it.videoTitle.filter(Char::isDigit).toIntOrNull() ?: 0 },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_UPLOADER_FILTER_ENABLED_KEY
                title = "Filter by Uploader"
                summary = "Load videos only from the specified uploader ID."
                setDefaultValue(false)
            }.also(screen::addPreference)

        EditTextPreference(screen.context)
            .apply {
                key = PREF_UPLOADER_ID_KEY
                title = "Uploader ID"
                summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
                dialogTitle = "Enter Uploader ID"
                setOnPreferenceChangeListener { _, newValue ->
                    newValue?.toString().isNullOrBlank().not()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = PREF_QUALITY_TITLE
                entries = PREF_QUALITY_ENTRIES
                entryValues = entries
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)
    }

    // ============================== Filters ===============================
    private var tagDocument = Document("")

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList.addAll(
            document.select("div.item").map {
                val tagValue = it.selectFirst("input")!!.attr("value")
                val tagName = it.selectFirst("label")!!.text()
                Pair(tagName, tagValue)
            },
        )
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
        preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
    ) {
        AnimeFilterList() // If uploader filter is enabled and ID is set, show no other filters
    } else {
        AnimeFilterList(
            OrderFilter(),
            CategoryBy(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Entered a \"tag\", click on \"filter\" then Click \"reset\" to load tags."),
            TagFilter(),
            TagSearch(tagsResults(tagDocument)),
        )
    }

    private class TagFilter : AnimeFilter.Text("Click \"reset\" without any text to load all A-Z tags.", "")

    private class TagSearch(
        results: Array<Pair<String, String>>,
    ) : UriPartFilter(
            "Tag Filter ",
            results,
        )

    private class CategoryBy :
        UriPartFilter(
            "Category Filter ",
            arrayOf(
                Pair("All", ""),
                Pair("Straight", "2109"),
                Pair("Futa", "15"),
                Pair("Gay", "192"),
                Pair("Music", "4747"),
                Pair("Iwara", "1821"),
            ),
        )

    private class OrderFilter :
        UriPartFilter(
            "Sort By ",
            arrayOf(
                Pair("Latest", "latest-updates"),
                Pair("Most Viewed", "most-popular"),
                Pair("Top Rated", "top-rated"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private val THUMBNAIL_ATTRS = listOf("data-rd-src", "data-original", "data-src", "src")
        private const val DOWNLOAD_LINK_SELECTOR = "a.tag_item_download[href]"
        private val FLASHVARS_ENTRY_REGEX = Regex("""(\w+):\s*'((?:[^'\\]|\\.)*)'""")
        private val FLASHVARS_URL_KEY_REGEX = Regex("""video_(?:alt_)?url\d*""")
        private val FLASHVARS_PREVIEW_REGEX = Regex("""preview_url:\s*'(https?://[^']+)'""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
