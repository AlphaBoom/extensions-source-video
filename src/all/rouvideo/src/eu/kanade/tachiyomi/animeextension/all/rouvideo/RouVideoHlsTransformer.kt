package eu.kanade.tachiyomi.animeextension.all.rouvideo

import eu.kanade.tachiyomi.lib.hlsproxy.HlsBodyTransformer
import eu.kanade.tachiyomi.lib.hlsproxy.HlsResourceContext
import eu.kanade.tachiyomi.lib.hlsproxy.HlsResourceKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * Unwraps the PNG container used by rou.video for HLS manifests and segments.
 */
internal object RouVideoHlsTransformer : HlsBodyTransformer {

    override fun supports(context: HlsResourceContext): Boolean {
        return context.kind == HlsResourceKind.PLAYLIST ||
            context.kind == HlsResourceKind.SEGMENT
    }

    override fun transform(context: HlsResourceContext, body: ByteArray): ByteArray {
        if (!body.startsWith(PNG_SIGNATURE)) return body

        var offset = PNG_SIGNATURE.size
        while (offset + CHUNK_HEADER_SIZE <= body.size) {
            val chunkLength = readUInt32(body, offset)
            val dataStart = offset + CHUNK_HEADER_SIZE
            if (chunkLength > (body.size - dataStart - CRC_SIZE).toLong()) return body

            val dataEnd = dataStart + chunkLength.toInt()
            val type = String(body, offset + 4, CHUNK_TYPE_SIZE, Charsets.US_ASCII)
            if (type == ROUD_CHUNK) {
                if (chunkLength < 1) return body

                val compressed = body[dataStart].toInt() and 1 != 0
                val payload = body.copyOfRange(dataStart + 1, dataEnd)
                return if (compressed) inflate(payload) else payload
            }

            offset = dataEnd + CRC_SIZE
        }

        return body
    }

    private fun inflate(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(body)).use { input ->
            input.copyTo(output)
        }
        return output.toByteArray()
    }

    private fun readUInt32(body: ByteArray, offset: Int): Long {
        return ((body[offset].toLong() and 0xff) shl 24) or
            ((body[offset + 1].toLong() and 0xff) shl 16) or
            ((body[offset + 2].toLong() and 0xff) shl 8) or
            (body[offset + 3].toLong() and 0xff)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private const val CHUNK_HEADER_SIZE = 8
    private const val CHUNK_TYPE_SIZE = 4
    private const val CRC_SIZE = 4
    private const val ROUD_CHUNK = "roUd"
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )
}
