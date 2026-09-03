package eu.kanade.tachiyomi.lib.hlsproxy

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class HlsProxyServer(
    private val client: OkHttpClient,
    private val fallbackClient: OkHttpClient?,
    private val maxSessions: Int,
    private val sessionTtlMillis: Long,
) : NanoHTTPD(LOOPBACK_HOST, 0) {

    fun createSession(
        playlistUrl: String,
        headers: Headers,
        options: HlsProxyOptions,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val session = ProxySession(
            id = sessionId,
            headers = headers,
            options = options,
        )
        session.resources[ENTRY_PLAYLIST_PATH] = ProxyResource(
            url = playlistUrl,
            kind = HlsResourceKind.PLAYLIST,
            depth = 0,
        )

        synchronized(sessions) {
            purgeExpiredSessions()
            sessions[sessionId] = session
        }

        return localUrl(sessionId, ENTRY_PLAYLIST_PATH)
    }

    override fun serve(httpSession: IHTTPSession): Response {
        if (httpSession.method != Method.GET && httpSession.method != Method.HEAD) {
            return errorResponse(Status.METHOD_NOT_ALLOWED, "Method Not Allowed")
        }

        val path = httpSession.uri.substringBefore("?").trim('/')
        val sessionId = path.substringBefore("/")
        val resourcePath = path.substringAfter("/", "")
        val proxySession = synchronized(sessions) {
            purgeExpiredSessions()
            sessions[sessionId]?.also { it.lastAccessMillis = System.currentTimeMillis() }
        } ?: return errorResponse(Status.NOT_FOUND, "Not Found")
        val resource = proxySession.resources[resourcePath]
            ?: return errorResponse(Status.NOT_FOUND, "Not Found")

        return runCatching {
            proxy(
                httpSession = httpSession,
                proxySession = proxySession,
                resource = resource,
            )
        }.getOrElse {
            errorResponse(HttpStatus(502, "Bad Gateway"), "Upstream request failed")
        }
    }

    private fun proxy(
        httpSession: IHTTPSession,
        proxySession: ProxySession,
        resource: ProxyResource,
    ): Response {
        val context = HlsResourceContext(
            url = resource.url,
            kind = resource.kind,
            depth = resource.depth,
            headers = proxySession.headers,
        )
        val bodyTransformers = proxySession.options.bodyTransformers
            .filter { it.supports(context) }
        val request = buildUpstreamRequest(
            httpSession = httpSession,
            proxySession = proxySession,
            context = context,
            forceFullResource = bodyTransformers.isNotEmpty(),
        )
        val upstream = execute(request)
        val responseContext = context.copy(url = upstream.request.url.toString())

        if (httpSession.method == Method.HEAD) {
            if (bodyTransformers.isNotEmpty() && upstream.isSuccessful) {
                return upstream.use {
                    val body = it.body.bytes()
                        .applyTransformers(responseContext, bodyTransformers)
                    transformedEmptyResponse(
                        status = it.toProxyStatus(),
                        contentType = it.contentType(responseContext, proxySession.options),
                        contentLength = body.size.toLong(),
                    )
                }
            }
            return upstream.use {
                emptyResponse(
                    status = it.toProxyStatus(),
                    contentType = it.contentType(responseContext, proxySession.options),
                    contentLength = it.body.contentLength(),
                    upstream = it,
                )
            }
        }

        if (!upstream.isSuccessful) {
            return streamResponse(upstream, responseContext, proxySession.options)
        }

        return when (resource.kind) {
            HlsResourceKind.PLAYLIST -> {
                playlistResponse(
                    upstream = upstream,
                    proxySession = proxySession,
                    resource = resource,
                    context = responseContext,
                    transformers = bodyTransformers,
                )
            }
            else -> {
                resourceResponse(
                    upstream = upstream,
                    context = responseContext,
                    options = proxySession.options,
                    transformers = bodyTransformers,
                    rangeHeader = httpSession.headers["range"],
                )
            }
        }
    }

    private fun buildUpstreamRequest(
        httpSession: IHTTPSession,
        proxySession: ProxySession,
        context: HlsResourceContext,
        forceFullResource: Boolean,
    ): Request {
        var request = Request.Builder()
            .url(context.url)
            .headers(proxySession.headers)
            .method(
                if (httpSession.method == Method.HEAD && !forceFullResource) "HEAD" else "GET",
                null,
            )
            .apply {
                FORWARDED_REQUEST_HEADERS.forEach { name ->
                    httpSession.headers[name.lowercase()]?.let { header(name, it) }
                }
            }
            .build()

        proxySession.options.requestTransformers.forEach { transformer ->
            request = transformer.transform(context, request)
        }
        if (forceFullResource) {
            request = request.newBuilder()
                .removeHeader("Range")
                .removeHeader("If-Range")
                .build()
        }
        return request
    }

    private fun execute(request: Request): OkHttpResponse {
        return runCatching { client.newCall(request).execute() }
            .getOrElse { primaryFailure ->
                val fallback = fallbackClient ?: throw primaryFailure
                fallback.newCall(request).execute()
            }
    }

    private fun playlistResponse(
        upstream: OkHttpResponse,
        proxySession: ProxySession,
        resource: ProxyResource,
        context: HlsResourceContext,
        transformers: List<HlsBodyTransformer>,
    ): Response {
        return upstream.use {
            var playlist = it.body.bytes()
                .applyTransformers(context, transformers)
                .toString(Charsets.UTF_8)
            proxySession.options.playlistTransformers.forEach { transformer ->
                playlist = transformer.transform(context, playlist)
            }
            check(playlist.trimStart().startsWith("#EXTM3U")) {
                "Invalid HLS playlist"
            }

            val rewritten = HlsPlaylistRewriter.rewrite(
                playlist = playlist,
                sourceUrl = context.url,
            ) { url, kind ->
                val depth = if (kind == HlsResourceKind.PLAYLIST) {
                    resource.depth + 1
                } else {
                    resource.depth
                }
                registerResource(proxySession, url, kind, depth)
            }

            bytesResponse(
                status = it.toProxyStatus(),
                contentType = HLS_MIME,
                body = rewritten.toByteArray(),
                upstream = it,
            )
        }
    }

    private fun resourceResponse(
        upstream: OkHttpResponse,
        context: HlsResourceContext,
        options: HlsProxyOptions,
        transformers: List<HlsBodyTransformer>,
        rangeHeader: String?,
    ): Response {
        if (transformers.isEmpty()) {
            return streamResponse(upstream, context, options)
        }

        return upstream.use {
            val body = it.body.bytes().applyTransformers(context, transformers)
            transformedBytesResponse(
                status = it.toProxyStatus(),
                contentType = it.contentType(context, options),
                body = body,
                rangeHeader = rangeHeader,
            )
        }
    }

    private fun streamResponse(
        upstream: OkHttpResponse,
        context: HlsResourceContext,
        options: HlsProxyOptions,
    ): Response {
        val body = upstream.body
        val stream = ClosingInputStream(body.byteStream(), upstream)
        val response = if (body.contentLength() >= 0) {
            newFixedLengthResponse(
                upstream.toProxyStatus(),
                upstream.contentType(context, options),
                stream,
                body.contentLength(),
            )
        } else {
            newChunkedResponse(
                upstream.toProxyStatus(),
                upstream.contentType(context, options),
                stream,
            )
        }
        return response.copyHeadersFrom(upstream)
    }

    private fun bytesResponse(
        status: IStatus,
        contentType: String,
        body: ByteArray,
        upstream: OkHttpResponse,
    ): Response {
        return newFixedLengthResponse(
            status,
            contentType,
            ByteArrayInputStream(body),
            body.size.toLong(),
        )
            .copyHeadersFrom(upstream)
    }

    private fun transformedBytesResponse(
        status: IStatus,
        contentType: String,
        body: ByteArray,
        rangeHeader: String?,
    ): Response {
        if (rangeHeader == null) {
            return newFixedLengthResponse(
                status,
                contentType,
                ByteArrayInputStream(body),
                body.size.toLong(),
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Cache-Control", "no-store")
            }
        }

        val range = rangeHeader.toByteRange(body.size)
            ?: return errorResponse(Status.RANGE_NOT_SATISFIABLE, "Range Not Satisfiable")
                .apply { addHeader("Content-Range", "bytes */${body.size}") }
        val rangedBody = body.copyOfRange(range.first, range.last + 1)
        return newFixedLengthResponse(
            Status.PARTIAL_CONTENT,
            contentType,
            ByteArrayInputStream(rangedBody),
            rangedBody.size.toLong(),
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes ${range.first}-${range.last}/${body.size}")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun transformedEmptyResponse(
        status: IStatus,
        contentType: String,
        contentLength: Long,
    ): Response {
        return newFixedLengthResponse(
            status,
            contentType,
            ByteArrayInputStream(ByteArray(0)),
            0,
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Cache-Control", "no-store")
            addHeader("Content-Length", contentLength.toString())
        }
    }

    private fun emptyResponse(
        status: IStatus,
        contentType: String,
        contentLength: Long,
        upstream: OkHttpResponse,
    ): Response {
        return newFixedLengthResponse(
            status,
            contentType,
            ByteArrayInputStream(ByteArray(0)),
            0,
        ).apply {
            if (contentLength >= 0) {
                addHeader("Content-Length", contentLength.toString())
            }
        }.copyHeadersFrom(upstream)
    }

    private fun registerResource(
        session: ProxySession,
        url: String,
        kind: HlsResourceKind,
        depth: Int,
    ): String {
        val key = ProxyResourceKey(url, kind, depth)
        val path = synchronized(session) {
            session.resourcePaths[key] ?: run {
                val newPath = "${session.counter.incrementAndGet()}.${kind.extension}"
                session.resources[newPath] = ProxyResource(url, kind, depth)
                session.resourcePaths[key] = newPath
                newPath
            }
        }
        return localUrl(session.id, path)
    }

    private fun localUrl(sessionId: String, path: String): String {
        return "http://$LOOPBACK_HOST:$listeningPort/$sessionId/$path"
    }

    private fun OkHttpResponse.contentType(
        context: HlsResourceContext,
        options: HlsProxyOptions,
    ): String {
        if (context.kind == HlsResourceKind.PLAYLIST) return HLS_MIME

        val upstreamType = header("Content-Type")
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (!options.normalizeMimeTypes) {
            return upstreamType ?: DEFAULT_BINARY_MIME
        }

        return when (context.kind) {
            HlsResourceKind.SEGMENT -> {
                if (upstreamType == null || upstreamType.startsWith("image/")) MPEG_TS_MIME else upstreamType
            }
            HlsResourceKind.INITIALIZATION -> {
                if (upstreamType == null || upstreamType.startsWith("image/")) MP4_MIME else upstreamType
            }
            HlsResourceKind.KEY -> DEFAULT_BINARY_MIME
            else -> upstreamType ?: DEFAULT_BINARY_MIME
        }
    }

    private fun OkHttpResponse.toProxyStatus(): IStatus {
        return Status.lookup(code) ?: HttpStatus(code, message.ifBlank { "HTTP $code" })
    }

    private fun Response.copyHeadersFrom(upstream: OkHttpResponse): Response {
        FORWARDED_RESPONSE_HEADERS.forEach { name ->
            upstream.header(name)?.let { addHeader(name, it) }
        }
        addHeader("Cache-Control", upstream.header("Cache-Control") ?: "no-store")
        return this
    }

    private fun errorResponse(status: IStatus, message: String): Response {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, message)
    }

    private fun String.toByteRange(bodySize: Int): IntRange? {
        if (!startsWith("bytes=", ignoreCase = true) || ',' in this || bodySize <= 0) {
            return null
        }

        val range = substringAfter('=').trim()
        val startValue = range.substringBefore('-').trim()
        val endValue = range.substringAfter('-', "").trim()
        if (startValue.isEmpty()) {
            val suffixLength = endValue.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val start = (bodySize.toLong() - suffixLength).coerceAtLeast(0).toInt()
            return start until bodySize
        }

        val start = startValue.toLongOrNull()?.takeIf { it in 0 until bodySize.toLong() }
            ?: return null
        val end = if (endValue.isEmpty()) {
            bodySize - 1L
        } else {
            endValue.toLongOrNull()?.coerceAtMost(bodySize - 1L) ?: return null
        }
        if (end < start) return null
        return start.toInt()..end.toInt()
    }

    private fun ByteArray.applyTransformers(
        context: HlsResourceContext,
        transformers: List<HlsBodyTransformer>,
    ): ByteArray {
        var body = this
        transformers.forEach { transformer ->
            body = transformer.transform(context, body)
        }
        return body
    }

    private fun purgeExpiredSessions() {
        val expiration = System.currentTimeMillis() - sessionTtlMillis
        sessions.entries.removeAll { it.value.lastAccessMillis < expiration }
    }

    private data class ProxySession(
        val id: String,
        val headers: Headers,
        val options: HlsProxyOptions,
        val resources: ConcurrentHashMap<String, ProxyResource> = ConcurrentHashMap(),
        val resourcePaths: MutableMap<ProxyResourceKey, String> = mutableMapOf(),
        val counter: AtomicInteger = AtomicInteger(),
        @Volatile var lastAccessMillis: Long = System.currentTimeMillis(),
    )

    private data class ProxyResource(
        val url: String,
        val kind: HlsResourceKind,
        val depth: Int,
    )

    private data class ProxyResourceKey(
        val url: String,
        val kind: HlsResourceKind,
        val depth: Int,
    )

    private data class HttpStatus(
        private val code: Int,
        private val reason: String,
    ) : IStatus {
        override fun getRequestStatus(): Int = code

        override fun getDescription(): String = "$code $reason"
    }

    private class ClosingInputStream(
        input: InputStream,
        private val response: OkHttpResponse,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private val HlsResourceKind.extension: String
        get() = when (this) {
            HlsResourceKind.PLAYLIST -> "m3u8"
            HlsResourceKind.SEGMENT -> "ts"
            HlsResourceKind.INITIALIZATION -> "mp4"
            HlsResourceKind.KEY -> "key"
            HlsResourceKind.OTHER -> "bin"
        }

    private val sessions = object : LinkedHashMap<String, ProxySession>(maxSessions, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProxySession>?): Boolean {
            return size > maxSessions
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val ENTRY_PLAYLIST_PATH = "playlist.m3u8"
        private const val HLS_MIME = "application/vnd.apple.mpegurl"
        private const val MPEG_TS_MIME = "video/mp2t"
        private const val MP4_MIME = "video/mp4"
        private const val DEFAULT_BINARY_MIME = "application/octet-stream"

        private val FORWARDED_REQUEST_HEADERS = listOf(
            "Range",
            "If-Range",
            "If-None-Match",
            "If-Modified-Since",
        )
        private val FORWARDED_RESPONSE_HEADERS = listOf(
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Expires",
        )
    }
}
