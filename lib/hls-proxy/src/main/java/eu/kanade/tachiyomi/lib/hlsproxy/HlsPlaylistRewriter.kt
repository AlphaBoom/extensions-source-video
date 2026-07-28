package eu.kanade.tachiyomi.lib.hlsproxy

import okhttp3.HttpUrl.Companion.toHttpUrl

internal object HlsPlaylistRewriter {

    fun rewrite(
        playlist: String,
        sourceUrl: String,
        register: (String, HlsResourceKind) -> String,
    ): String {
        val baseUrl = sourceUrl.toHttpUrl()
        var nextLineKind = HlsResourceKind.SEGMENT

        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    nextLineKind = HlsResourceKind.PLAYLIST
                    line
                }
                line.startsWith("#") -> {
                    val attributeKind = line.attributeResourceKind()
                    URI_ATTRIBUTE.replace(line) { match ->
                        val uri = match.groupValues[1]
                        val absoluteUrl = baseUrl.resolve(uri)?.toString()
                        when {
                            absoluteUrl != null -> """URI="${register(absoluteUrl, attributeKind)}""""
                            uri.hasAbsoluteScheme() -> match.value
                            else -> throw IllegalArgumentException("Invalid HLS resource URL")
                        }
                    }
                }
                line.isBlank() -> line
                else -> {
                    val uri = line.trim()
                    val absoluteUrl = baseUrl.resolve(uri)?.toString()
                    val rewritten = when {
                        absoluteUrl != null -> register(absoluteUrl, nextLineKind)
                        uri.hasAbsoluteScheme() -> uri
                        else -> throw IllegalArgumentException("Invalid HLS resource URL")
                    }
                    rewritten.also { nextLineKind = HlsResourceKind.SEGMENT }
                }
            }
        }
    }

    private fun String.attributeResourceKind(): HlsResourceKind {
        return when {
            startsWith("#EXT-X-KEY", ignoreCase = true) ||
                startsWith("#EXT-X-SESSION-KEY", ignoreCase = true) -> HlsResourceKind.KEY
            startsWith("#EXT-X-MAP", ignoreCase = true) -> HlsResourceKind.INITIALIZATION
            startsWith("#EXT-X-PART", ignoreCase = true) -> HlsResourceKind.SEGMENT
            startsWith("#EXT-X-PRELOAD-HINT", ignoreCase = true) -> {
                if (contains("TYPE=MAP", ignoreCase = true)) {
                    HlsResourceKind.INITIALIZATION
                } else {
                    HlsResourceKind.SEGMENT
                }
            }
            startsWith("#EXT-X-MEDIA", ignoreCase = true) ||
                startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) ||
                startsWith("#EXT-X-RENDITION-REPORT", ignoreCase = true) -> HlsResourceKind.PLAYLIST
            else -> HlsResourceKind.OTHER
        }
    }

    private fun String.hasAbsoluteScheme(): Boolean = SCHEME_PREFIX.containsMatchIn(this)

    private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SCHEME_PREFIX = Regex("""^[a-z][a-z0-9+.-]*:""", RegexOption.IGNORE_CASE)
}
