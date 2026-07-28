package eu.kanade.tachiyomi.lib.hlsproxy

import java.io.ByteArrayOutputStream

/**
 * Removes JPEG/PNG/GIF disguise blocks injected into HLS media segments.
 *
 * This transformer is opt-in because it buffers complete segments. Its
 * detection strategy is derived from yuzono/anime-extensions m3u8server's
 * AutoDetector and adapted for this proxy's transformer API.
 */
object FakeImageJunkTransformer : HlsBodyTransformer {

    override fun supports(context: HlsResourceContext): Boolean {
        return context.kind == HlsResourceKind.SEGMENT
    }

    override fun transform(context: HlsResourceContext, body: ByteArray): ByteArray {
        val ranges = FakeImageJunkDetector.detect(body)
        if (ranges.isEmpty()) return body

        val outputSize = body.size - ranges.sumOf { it.last - it.first + 1 }
        val output = ByteArrayOutputStream(outputSize.coerceAtLeast(0))
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) {
                output.write(body, cursor, range.first - cursor)
            }
            cursor = range.last + 1
        }
        if (cursor < body.size) {
            output.write(body, cursor, body.size - cursor)
        }
        return output.toByteArray()
    }
}

private object FakeImageJunkDetector {

    fun detect(data: ByteArray): List<IntRange> {
        if (data.size < 3) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < data.size - 2) {
            if (!data.hasImageMagicAt(index)) {
                index++
                continue
            }

            val end = findVideoBoundary(data, index + 1)
                ?: minOf(data.size, index + DEFAULT_JUNK_BLOCK_SIZE)
            if (end > index) {
                ranges += index until end
                index = end
            } else {
                index++
            }
        }
        return ranges.merge()
    }

    private fun findVideoBoundary(data: ByteArray, start: Int): Int? {
        val end = minOf(data.size, start + SEARCH_LIMIT)
        for (index in start until end) {
            when {
                data.isMpegTsAt(index) -> return index
                data.isMp4At(index) -> return index
                data.isAviAt(index) -> return index
            }
        }
        return null
    }

    private fun ByteArray.hasImageMagicAt(index: Int): Boolean {
        val jpeg = index + 2 < size &&
            this[index] == 0xff.toByte() &&
            this[index + 1] == 0xd8.toByte() &&
            this[index + 2] == 0xff.toByte()
        val png = index + 3 < size &&
            this[index] == 0x89.toByte() &&
            this[index + 1] == 0x50.toByte() &&
            this[index + 2] == 0x4e.toByte() &&
            this[index + 3] == 0x47.toByte()
        val gif = index + 2 < size &&
            this[index] == 0x47.toByte() &&
            this[index + 1] == 0x49.toByte() &&
            this[index + 2] == 0x46.toByte()
        return jpeg || png || gif
    }

    private fun ByteArray.isMpegTsAt(start: Int): Boolean {
        if (start >= size || this[start] != MPEG_TS_SYNC) return false

        var matches = 0
        var index = start
        val end = minOf(size, start + 1024)
        while (index < end) {
            if (this[index] == MPEG_TS_SYNC) matches++
            index += MPEG_TS_PACKET_SIZE
        }
        return matches >= 2
    }

    private fun ByteArray.isMp4At(start: Int): Boolean {
        return start + 7 < size &&
            this[start + 4] == 'f'.code.toByte() &&
            this[start + 5] == 't'.code.toByte() &&
            this[start + 6] == 'y'.code.toByte() &&
            this[start + 7] == 'p'.code.toByte()
    }

    private fun ByteArray.isAviAt(start: Int): Boolean {
        return start + 11 < size &&
            this[start] == 'R'.code.toByte() &&
            this[start + 1] == 'I'.code.toByte() &&
            this[start + 2] == 'F'.code.toByte() &&
            this[start + 3] == 'F'.code.toByte() &&
            this[start + 8] == 'A'.code.toByte() &&
            this[start + 9] == 'V'.code.toByte() &&
            this[start + 10] == 'I'.code.toByte() &&
            this[start + 11] == ' '.code.toByte()
    }

    private fun List<IntRange>.merge(): List<IntRange> {
        if (isEmpty()) return emptyList()

        val result = mutableListOf<IntRange>()
        var current = sortedBy { it.first }.first()
        sortedBy { it.first }.drop(1).forEach { next ->
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    private const val DEFAULT_JUNK_BLOCK_SIZE = 252
    private const val SEARCH_LIMIT = 8 * 1024
    private const val MPEG_TS_PACKET_SIZE = 188
    private const val MPEG_TS_SYNC = 0x47.toByte()
}
