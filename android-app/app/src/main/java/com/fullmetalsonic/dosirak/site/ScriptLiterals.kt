package com.fullmetalsonic.dosirak.site

/** Parses only literal call arguments; never evaluates page JavaScript. */
internal object ScriptLiterals {
    fun arguments(source: String, function: String): List<String>? {
        val prefix = Regex("^\\s*" + Regex.escape(function) + "\\s*\\(").find(source) ?: return null
        var pos = prefix.range.last + 1
        val args = mutableListOf<String>()
        while (pos < source.length) {
            while (pos < source.length && source[pos].isWhitespace()) pos++
            if (source.getOrNull(pos) == ')') {
                pos++
                return args.takeIf { source.substring(pos).trim().removeSuffix(";").isBlank() }
            }
            val current = source.getOrNull(pos) ?: return null
            val value: String
            if (current == '\'' || current == '"') {
                val parsed = string(source, pos) ?: return null
                value = parsed.first
                pos = parsed.second
            } else if (source.startsWith("$(this)", pos)) {
                value = "$(this)"
                pos += 7
            } else {
                val start = pos
                while (pos < source.length && source[pos] != ',' && source[pos] != ')') pos++
                value = source.substring(start, pos).trim()
                if (!value.matches(Regex("[0-9]+(?:\\.[0-9]+)?|this|\\$\\(this\\)"))) return null
            }
            args += value
            while (pos < source.length && source[pos].isWhitespace()) pos++
            when (source.getOrNull(pos)) {
                ',' -> pos++
                ')' -> Unit
                else -> return null
            }
        }
        return null
    }

    fun completionAlert(source: String): Boolean = Regex("\\balert\\s*\\(").findAll(source).any { match ->
        var pos = match.range.last + 1
        while (pos < source.length && source[pos].isWhitespace()) pos++
        val literal = string(source, pos)
        literal?.first == "주문이 완료 되었습니다." && source.substring(literal.second).trimStart().startsWith(")")
    }

    private fun string(source: String, start: Int): Pair<String, Int>? {
        val quote = source.getOrNull(start)?.takeIf { it == '\'' || it == '"' } ?: return null
        val result = StringBuilder()
        var pos = start + 1
        while (pos < source.length) {
            val ch = source[pos++]
            when {
                ch == quote -> return result.toString() to pos
                ch == '\n' || ch == '\r' -> return null
                ch != '\\' -> result.append(ch)
                else -> {
                    val next = source.getOrNull(pos++) ?: return null
                    when (next) {
                        '\n' -> Unit
                        '\r' -> if (source.getOrNull(pos) == '\n') pos++
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'v' -> result.append('\u000b')
                        '0' -> { if (source.getOrNull(pos)?.isDigit() == true) return null; result.append('\u0000') }
                        'x', 'u' -> {
                            val count = if (next == 'x') 2 else 4
                            val raw = source.substring(pos, minOf(pos + count, source.length))
                            if (raw.length != count) return null
                            val code = raw.toIntOrNull(16) ?: return null
                            result.append(code.toChar())
                            pos += count
                        }
                        in '1'..'9' -> return null
                        else -> result.append(next)
                    }
                }
            }
        }
        return null
    }
}
