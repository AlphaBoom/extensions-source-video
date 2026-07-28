package eu.kanade.tachiyomi.lib.hlsproxy

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.Closeable

/**
 * A loopback HTTP proxy for HLS playback.
 *
 * The proxy gives the player conventional local URLs while keeping signed
 * upstream URLs and request headers in memory. It also exposes transformation
 * hooks for manifest filtering, request repair, and segment deobfuscation.
 */
class HlsProxy(
    private val client: OkHttpClient,
    private val fallbackClient: OkHttpClient? = null,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MILLIS,
) : Closeable {

    init {
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(sessionTtlMillis > 0) { "sessionTtlMillis must be positive" }
    }

    @Volatile
    private var server: HlsProxyServer? = null

    /**
     * Creates a local `.m3u8` URL for [playlistUrl].
     *
     * Set [options] per playback so different extensions or extractors can
     * attach independent filtering and repair policies.
     */
    fun proxy(
        playlistUrl: String,
        headers: Headers = Headers.Builder().build(),
        options: HlsProxyOptions = HlsProxyOptions(),
    ): String {
        return getOrStartServer().createSession(playlistUrl, headers, options)
    }

    /**
     * Returns a copy of [video] whose player-facing URL uses this proxy.
     */
    fun proxy(
        video: Video,
        options: HlsProxyOptions = HlsProxyOptions(),
    ): Video {
        val upstreamUrl = video.videoUrl ?: video.url
        val upstreamHeaders = video.headers ?: Headers.Builder().build()
        return video.copy(
            videoUrl = proxy(upstreamUrl, upstreamHeaders, options),
        )
    }

    fun isRunning(): Boolean = server?.isAlive ?: false

    override fun close() {
        synchronized(this) {
            server?.stop()
            server = null
        }
    }

    private fun getOrStartServer(): HlsProxyServer {
        server?.takeIf { it.isAlive }?.let { return it }

        return synchronized(this) {
            server?.takeIf { it.isAlive }
                ?: HlsProxyServer(
                    client = client,
                    fallbackClient = fallbackClient,
                    maxSessions = maxSessions,
                    sessionTtlMillis = sessionTtlMillis,
                ).also {
                    it.start()
                    server = it
                }
        }
    }

    companion object {
        private const val DEFAULT_MAX_SESSIONS = 8
        private const val DEFAULT_SESSION_TTL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
