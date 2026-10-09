package com.fullmetalsonic.dosirak.site

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Reads one plain server-provided literal; it never evaluates JavaScript or uses HTTP Date. */
internal object ServerTimeParser {
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val literal = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
    private val seoul = ZoneId.of("Asia/Seoul")

    fun parse(html: String): Long {
        val tokens = SiteParser.document(html).select("script:not([src])").flatMap { tokenize(it.data()) }
        val definitions = tokens.indices.filter { i -> tokens[i] == "servertimeinfo" && tokens.getOrNull(i + 1) == "=" }
        if (definitions.size != 1) unavailable()
        val index = definitions.single()
        if (tokens.getOrNull(index - 1) !in setOf("var", "let", "const", ";", "{" , "}" , null)) unavailable()
        val raw = tokens.getOrNull(index + 2) ?: unavailable()
        if (raw.length != 21 || raw.first() !in setOf('\'', '"') || raw.last() != raw.first() ||
            !literal.matches(raw.substring(1, raw.lastIndex)) || tokens.getOrNull(index + 3) != ";") unavailable()
        return try {
            LocalDateTime.parse(raw.substring(1, raw.lastIndex), formatter).atZone(seoul).toInstant().toEpochMilli()
        } catch (_: RuntimeException) { unavailable() }
    }

    private fun unavailable(): Nothing = throw SiteException("TIME_UNAVAILABLE", "사이트 시각 형식을 확인할 수 없습니다.")

    private fun tokenize(source: String): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        while (pos < source.length) {
            val ch = source[pos]
            when {
                ch.isWhitespace() -> pos++
                source.startsWith("//", pos) -> {
                    pos = source.indexOf('\n', pos + 2).let { if (it < 0) source.length else it + 1 }
                }
                source.startsWith("/*", pos) -> {
                    val end = source.indexOf("*/", pos + 2)
                    if (end < 0) unavailable()
                    pos = end + 2
                }
                ch in setOf('\'', '"', '`') -> {
                    val start = pos++
                    var ended = false
                    while (pos < source.length) {
                        val current = source[pos++]
                        if (current == '\\') { if (pos >= source.length) unavailable(); pos++ }
                        else if (current == ch) { ended = true; break }
                    }
                    if (!ended) unavailable()
                    result += source.substring(start, pos)
                }
                ch == '/' && regexMayStart(result.lastOrNull()) -> {
                    pos++
                    var characterClass = false
                    var ended = false
                    while (pos < source.length) {
                        val current = source[pos++]
                        if (current == '\\') { if (pos >= source.length) unavailable(); pos++ }
                        else if (current == '[') characterClass = true
                        else if (current == ']') characterClass = false
                        else if (current == '/' && !characterClass) { ended = true; break }
                        else if (current == '\n' || current == '\r') unavailable()
                    }
                    if (!ended) unavailable()
                    while (pos < source.length && source[pos].isLetter()) pos++
                    result += "<regexp>"
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '$' -> {
                    val start = pos++
                    while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')) pos++
                    result += source.substring(start, pos)
                }
                else -> { result += ch.toString(); pos++ }
            }
        }
        return result
    }

    private fun regexMayStart(previous: String?): Boolean = previous == null || previous in setOf(
        "=", "(", "[", "{", ",", ";", ":", "!", "?", "&", "|", "return", "case", "throw", "=>"
    )
}
