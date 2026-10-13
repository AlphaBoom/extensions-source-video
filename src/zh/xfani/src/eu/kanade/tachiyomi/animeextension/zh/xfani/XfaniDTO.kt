package eu.kanade.tachiyomi.animeextension.zh.xfani

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeDto(
    val id: Int,
    val title: String,
    @SerialName("title_original")
    val titleOriginal: String? = null,
    val aliases: List<String>? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val description: String? = null,
    val director: String? = null,
    val actors: List<String>? = null,
    @SerialName("meta_tags")
    val metaTags: List<String>? = null,
    @SerialName("is_finished")
    val isFinished: Boolean? = null,
    @SerialName("release_year")
    val releaseYear: Int? = null,
    @SerialName("bangumi_score")
    val bangumiScore: Double? = null,
    @SerialName("total_count")
    val totalCount: Int? = null,
)

@Serializable
data class SearchRequest(
    @SerialName("search_term")
    val searchTerm: String,
    @SerialName("page_number")
    val pageNumber: Int,
    @SerialName("items_per_page")
    val itemsPerPage: Int,
    @SerialName("sort_by")
    val sortBy: String,
    @SerialName("sort_order")
    val sortOrder: String = "desc",
)

@Serializable
data class SourceDto(
    val id: Int,
    val code: String,
    val name: String,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val kind: String? = null,
    val title: String? = null,
    @SerialName("episode_number")
    val episodeNumber: Double? = null,
)

@Serializable
data class PlaybackRequest(
    val action: String,
    @SerialName("episode_id")
    val episodeId: Long,
    @SerialName("source_id")
    val sourceId: Int,
)

@Serializable
data class PlaybackResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val url: String? = null,
    @SerialName("retry_after")
    val retryAfter: Int? = null,
)
