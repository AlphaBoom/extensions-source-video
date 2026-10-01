package eu.kanade.tachiyomi.animeextension.all.rouvideo

import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideo.Companion.resolutionDesc
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LIKE_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_VIEW_KEY
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

internal object RouVideoDto {
    @Serializable
    data class VideoList(
        val videos: List<Video>,
        val pageNum: Int,
        val totalPage: Int,
        val hotSearches: List<String> = emptyList(),
    ) {
        fun toAnimePage(): AnimesPage = AnimesPage(
            videos.map { it.toSAnime() },
            pageNum < totalPage,
        )
    }

    @Serializable
    data class HotVideoList(
        val latestVideos: List<Video>,
        val dailyHotCNAV: List<Video>,
        val dailyHotSelfie: List<Video>,
        val dailyHot91: List<Video>,
        val dailyOnlyFans: List<Video>,
        val dailyJV: List<Video>,
        val hotCNAV: List<Video>,
        val hotSelfie: List<Video>,
        val hot91: List<Video>,
    ) {
        fun toAnimePage(sort: String?): AnimesPage {
            val videos = listOf(
                latestVideos,
                dailyHotCNAV,
                dailyHotSelfie,
                dailyHot91,
                dailyOnlyFans,
                dailyJV,
                hotCNAV,
                hotSelfie,
                hot91,
            ).flatten().distinctBy { it.id }.sortedWith { first, second ->
                val count = when (sort) {
                    SORT_VIEW_KEY -> second.viewCount.compareTo(first.viewCount)
                    SORT_LIKE_KEY -> compareValues(second.likeCount, first.likeCount)
                    else -> 0
                }
                count.takeIf { it != 0 } ?: second.createdAt.compareTo(first.createdAt)
            }
            return videos.toAnimePage()
        }
    }

    fun List<Video>.toAnimePage(): AnimesPage {
        return AnimesPage(
            map { video -> video.toSAnime() },
            false,
        )
    }

    @Serializable
    data class VideoDetails(
        val video: Video,
        val ev: EncodedVideoData? = null,
    )

    @Serializable
    data class EncodedVideoData(
        val d: String,
        val k: Int,
    )

    @Serializable
    data class Video(
        val id: String,
        @SerialName("vid")
        val code: String? = null,
        val name: String,
        val description: String? = null,
        val ref: String? = null,
        val tags: List<String>,
        val createdAt: String, // "2025-01-14T23:18:27.933Z"
        val viewCount: Int,
        val likeCount: Int? = null, // not available in search & relatedVideos
        val duration: Float, // in seconds
        val coverImageUrl: String,
        val nameZh: String? = null,
        @SerialName("tagsZh")
        val tagZh: List<String>? = null,
        val sources: List<Source>? = null, // not available in details
    ) {
        private val desc = StringBuilder().apply {
            sources?.firstOrNull()?.let { append("${resolutionDesc(it.resolution.toString())}\n") }
            append("Duration: ${formatDuration(duration.toInt())}\n")
            append("View: $viewCount")
            likeCount?.let { append(" - Like: $likeCount") }
            ref?.let { append("\nRef: $it") }
            description?.let { append("\n\n$description") }
        }.toString()

        private val majorCategory = tags.firstOrNull()

        fun toSAnime(): SAnime = SAnime.create().apply {
            url = id
            title = name
            thumbnail_url = coverImageUrl
            artist = majorCategory
            author = majorCategory
            description = desc
            genre = (listOfNotNull(code) + tags).joinToString()
            status = SAnime.COMPLETED
            initialized = true
        }

        fun toEpisode(): SEpisode = SEpisode.create().apply {
            name = id
            url = id
            date_upload = createdAt.toDate()
            episode_number = 1f
        }

        fun getTagList(): Set<Tag> = tags.map { Tag(it, it) }.toSet()
    }

    fun formatDuration(seconds: Int): String {
        val duration = seconds.seconds
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        val remainingSeconds = duration.inWholeSeconds % 60

        return "$hours:$minutes:$remainingSeconds"
    }

    @Serializable
    data class TagList(
        val taxonomy: Taxonomy? = null,
        val gcAV: List<TagItem> = emptyList(),
        val madouAV: List<TagItem> = emptyList(),
        val v91: List<TagItem> = emptyList(),
        val onlyfans: List<TagItem> = emptyList(),
    ) {
        fun toTagList(): Tags {
            val tags = taxonomy?.let {
                it.cats + it.genre + it.byParent.values.flatten()
            } ?: (gcAV + madouAV + v91 + onlyfans)
            return tags.map { Tag(it.name, it.name) }.distinct().toTypedArray()
        }
    }

    @Serializable
    data class Taxonomy(
        val cats: List<TagItem>,
        val genre: List<TagItem>,
        val byParent: Map<String, List<TagItem>>,
    )

    @Serializable
    data class TagItem(
        @SerialName("id")
        val name: String,
    )

    @Serializable
    data class VideoData(
        val thumbVTTUrl: String,
        val videoUrl: String,
    )

    /* Not available in details */
    @Serializable
    data class Source(
        val id: String? = null, // not available in relatedVideos
        val videoId: String? = null, // not available in relatedVideos
        val resolution: Int,
        val folder: String? = null, // not available in relatedVideos
    )

    private val DATE_FORMATTER by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
    }
}
