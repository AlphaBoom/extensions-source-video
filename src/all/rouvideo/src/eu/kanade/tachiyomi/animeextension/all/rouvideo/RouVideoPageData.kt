package eu.kanade.tachiyomi.animeextension.all.rouvideo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/** Reads TanStack's serialized loader data as data, without executing page JavaScript. */
internal object RouVideoPageData {
    private const val ROUTER_PREFIX = "\$_TSR.router=(\$R=>"

    fun parse(document: Document, json: Json): JsonObject {
        val script = document.select("script").map { it.data() }
            .firstOrNull { ROUTER_PREFIX in it }
            ?: throw IllegalStateException("RouVideo page data not found")
        val reader = Reader(script, script.indexOf(ROUTER_PREFIX) + ROUTER_PREFIX.length, json)
        val router = reader.value().jsonObject
        return router["matches"]!!.jsonArray.mapNotNull {
            it.jsonObject["l"] as? JsonObject
        }.lastOrNull() ?: throw IllegalStateException("RouVideo loader data not found")
    }

    private class Reader(val source: String, var position: Int, val json: Json) {
        private val references = mutableMapOf<Int, JsonElement>()
        private var depth = 0

        fun value(): JsonElement {
            require(++depth <= 128) { "RouVideo page data is too deeply nested" }
            whitespace()
            val result = when (source.getOrNull(position)) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                '$' -> reference()
                '!' -> {
                    position++
                    when (source.getOrNull(position++)) {
                        '0' -> JsonPrimitive(true)
                        '1' -> JsonPrimitive(false)
                        else -> unsupported()
                    }
                }
                'n' -> literal("null", JsonNull)
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'u' -> literal("undefined", JsonNull)
                'v' -> {
                    literal("void", JsonNull)
                    expect('0')
                    JsonNull
                }
                '-', in '0'..'9' -> numberValue()
                else -> unsupported()
            }
            depth--
            return result
        }

        private fun objectValue(): JsonObject {
            expect('{')
            val fields = linkedMapOf<String, JsonElement>()
            if (!consume('}')) {
                do {
                    whitespace()
                    val key = if (source.getOrNull(position) == '"') {
                        stringValue().jsonPrimitive.content
                    } else {
                        val start = position
                        while (source.getOrNull(position)?.let { it.isLetterOrDigit() || it == '_' || it == '$' } == true) position++
                        if (start == position) unsupported()
                        source.substring(start, position)
                    }
                    expect(':')
                    fields[key] = value()
                } while (consume(','))
                expect('}')
            }
            return JsonObject(fields)
        }

        private fun arrayValue(): JsonArray {
            expect('[')
            val items = mutableListOf<JsonElement>()
            if (!consume(']')) {
                do {
                    items.add(value())
                } while (consume(','))
                expect(']')
            }
            return JsonArray(items)
        }

        private fun reference(): JsonElement {
            if (!source.startsWith("\$R[", position)) unsupported()
            position += 3
            val start = position
            while (source.getOrNull(position)?.isDigit() == true) position++
            val index = source.substring(start, position).toIntOrNull() ?: unsupported()
            expect(']')
            if (consume('=')) {
                return value().also { references[index] = it }
            }
            return references[index] ?: unsupported()
        }

        private fun stringValue(): JsonElement {
            val start = position
            expect('"')
            while (position < source.length) {
                when (source[position++]) {
                    '\\' -> position++
                    '"' -> return json.parseToJsonElement(source.substring(start, position))
                }
            }
            unsupported()
        }

        private fun numberValue(): JsonElement {
            val start = position
            while (source.getOrNull(position)?.let { it in "0123456789.eE+-" } == true) position++
            return json.parseToJsonElement(source.substring(start, position))
        }

        private fun literal(text: String, result: JsonElement): JsonElement {
            if (!source.startsWith(text, position)) unsupported()
            position += text.length
            return result
        }

        private fun whitespace() {
            while (source.getOrNull(position)?.isWhitespace() == true) position++
        }

        private fun consume(char: Char): Boolean {
            whitespace()
            if (source.getOrNull(position) != char) return false
            position++
            return true
        }

        private fun expect(char: Char) {
            if (!consume(char)) unsupported()
        }

        private fun unsupported(): Nothing =
            throw IllegalArgumentException("Unsupported RouVideo page data at position $position")
    }
}
