package eu.kanade.tachiyomi.animeextension.zh.cycity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ApiResponse<T>(
    val code: Int,
    val msg: String = "",
    val data: T? = null,
)

@Serializable
class Pager(
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 0,
    val total: Int = 0,
)

@Serializable
class VideoItem(
    @SerialName("video_id") val videoId: Int,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
class VideoPage(
    val list: List<VideoItem> = emptyList(),
    val pager: Pager? = null,
)

@Serializable
class WeekdayVideos(
    val weekday: Int,
    val videos: List<VideoItem> = emptyList(),
)

@Serializable
class WeekdayList(
    val list: List<WeekdayVideos> = emptyList(),
)

@Serializable
class VideoDetail(
    val id: Int,
    val title: String,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val subtitle: String? = null,
    @SerialName("english_title") val englishTitle: String? = null,
    val director: List<String>? = null,
    val actor: List<String>? = null,
    val writer: String? = null,
    val area: String? = null,
    val language: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val remarks: String? = null,
    val completed: Boolean? = null,
    val tags: List<String>? = null,
    @SerialName("play_from") val playFrom: List<PlayFrom>? = null,
)

@Serializable
class PlayFrom(
    val code: String,
    val title: String? = null,
    val count: Int = 0,
)

@Serializable
class Section(
    val id: Long,
    val title: String? = null,
)

@Serializable
class SectionPage(
    val list: List<Section> = emptyList(),
    val pager: Pager? = null,
)

@Serializable
class PlayUrl(
    val name: String? = null,
    val url: String? = null,
)

@Serializable
class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
class AuthToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)
