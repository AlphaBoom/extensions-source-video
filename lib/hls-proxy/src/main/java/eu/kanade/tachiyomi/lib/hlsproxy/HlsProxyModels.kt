package eu.kanade.tachiyomi.lib.hlsproxy

import okhttp3.Headers
import okhttp3.Request

/**
 * The type of an upstream resource reached through an HLS playlist.
 */
enum class HlsResourceKind {
    PLAYLIST,
    SEGMENT,
    INITIALIZATION,
    KEY,
    OTHER,
}

/**
 * Context passed to proxy extension points.
 *
 * [depth] is zero for the entry playlist and increases for nested playlists.
 */
data class HlsResourceContext(
    val url: String,
    val kind: HlsResourceKind,
    val depth: Int,
    val headers: Headers,
)

/**
 * Rewrites an upstream playlist before its resource URLs are routed through
 * the local proxy.
 *
 * This is the main extension point for ad filtering and manifest repair.
 */
fun interface HlsPlaylistTransformer {
    fun transform(context: HlsResourceContext, playlist: String): String
}

/**
 * Rewrites an upstream request before it is executed.
 *
 * It can be used for expiring-token refresh, host failover, cookies, or
 * per-resource authentication.
 */
fun interface HlsRequestTransformer {
    fun transform(context: HlsResourceContext, request: Request): Request
}

/**
 * Optionally buffers and transforms a proxied resource body.
 *
 * Resources are streamed without buffering when no transformer supports them.
 */
interface HlsBodyTransformer {
    fun supports(context: HlsResourceContext): Boolean

    fun transform(context: HlsResourceContext, body: ByteArray): ByteArray
}

data class HlsProxyOptions(
    val playlistTransformers: List<HlsPlaylistTransformer> = emptyList(),
    val requestTransformers: List<HlsRequestTransformer> = emptyList(),
    val bodyTransformers: List<HlsBodyTransformer> = emptyList(),
    val normalizeMimeTypes: Boolean = true,
)
