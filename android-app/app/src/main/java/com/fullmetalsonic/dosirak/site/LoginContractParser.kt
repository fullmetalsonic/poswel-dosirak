package com.fullmetalsonic.dosirak.site

import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import java.util.Locale

/** Recognizes the observed login contract without running page JavaScript. */
internal object LoginContractParser {
    private enum class Kind { WORD, STRING, SYMBOL }
    private data class Token(val kind: Kind, val value: String)
    private val handler = tokens("""
        if (${'$'}("#uid").val() == "") { alert("__uid_notice__"); return false; }
        if (${'$'}("#pwd").val() == "") { alert("__pwd_notice__"); return false; }
        ${'$'}("#loginform").attr('action', 'https://dosirak.poswel.co.kr/login.check.php');
        ${'$'}("#loginform").attr('method', 'post');
        ${'$'}("#loginform").submit();
    """)!!
    private val binding = tokens("""${'$'}(".btn-login").on("click", function(){ setSubmit(); });""")!!
    private val buttonSelector = binding.take(7)
    private val relevantSource = Regex("\\bsetSubmit\\b|\\.btn-login\\b")

    fun fields(document: Document, baseUrl: HttpUrl): Map<String, List<String>> {
        val inputs = listOf("uid", "pwd", "utk").map { name ->
            document.select("input[name=$name]").singleOrNull() ?: fail()
        }
        val form = inputs.first().parents().firstOrNull { it.tagName() == "form" } ?: fail()
        if (inputs.any { it.hasAttr("disabled") || it.parents().firstOrNull { parent -> parent.tagName() == "form" } !== form } ||
            inputs[0].hasAttr("readonly") || inputs[1].attr("type").lowercase(Locale.ROOT) != "password" ||
            inputs[2].attr("type").lowercase(Locale.ROOT) != "hidden") fail()
        val action = form.attr("action").trim()
        val method = form.attr("method").trim().lowercase(Locale.ROOT)
        val fixedPost = method == "post" && action.isNotEmpty() && baseUrl.resolve(action) == baseUrl.resolve("/login.check.php")
        if (!fixedPost) {
            if (action.isNotEmpty() || method.isNotEmpty() || form.id() != "loginform" ||
                document.select("#loginform").singleOrNull() !== form) fail()
            val button = document.select("a.btn-login").singleOrNull() ?: fail()
            if (button.attr("href") != "#" || button.attr("onclick").isNotBlank() ||
                inputs[0].id() != "uid" || inputs[1].id() != "pwd" || !observedInlineContract(document)) fail()
        }
        val values = SiteParser.fields(form)
        if (listOf("uid", "pwd", "utk").any { values[it]?.size != 1 } || values["utk"]?.singleOrNull().isNullOrBlank()) fail()
        return values
    }

    private fun observedInlineContract(document: Document): Boolean {
        val scripts = document.select("script:not([src])").map { it.data() }
            .filter { relevantSource.containsMatchIn(it) }
            .map { tokens(it) ?: return false }
        val definitions = scripts.flatMap { script ->
            script.indices.filter { script[it] == Token(Kind.WORD, "function") && script.getOrNull(it + 1) == Token(Kind.WORD, "setSubmit") }
                .map { script to it }
        }
        val (script, start) = definitions.singleOrNull() ?: return false
        if (script.getOrNull(start + 2) != Token(Kind.SYMBOL, "(") || script.getOrNull(start + 3) != Token(Kind.SYMBOL, ")") ||
            script.getOrNull(start + 4) != Token(Kind.SYMBOL, "{")) return false
        var depth = 1
        var end = start + 5
        while (end < script.size && depth > 0) {
            if (script[end] == Token(Kind.SYMBOL, "{")) depth++
            if (script[end] == Token(Kind.SYMBOL, "}")) depth--
            end++
        }
        if (depth != 0 || !matches(script.subList(start + 5, end - 1), handler, allowNotices = true)) return false
        val bindings = scripts.flatMap { source ->
            source.indices.filter { at(source, it, buttonSelector) }.map { source to it }
        }
        val (source, position) = bindings.singleOrNull() ?: return false
        return at(source, position, binding)
    }

    private fun at(source: List<Token>, start: Int, pattern: List<Token>): Boolean =
        start + pattern.size <= source.size && matches(source.subList(start, start + pattern.size), pattern)

    private fun matches(source: List<Token>, pattern: List<Token>, allowNotices: Boolean = false): Boolean =
        source.size == pattern.size && source.indices.all { index ->
            val expected = pattern[index]
            if (allowNotices && expected.kind == Kind.STRING && expected.value in setOf("__uid_notice__", "__pwd_notice__")) {
                source[index].kind == Kind.STRING
            } else source[index] == expected
        }

    private fun tokens(source: String): List<Token>? {
        val result = mutableListOf<Token>()
        var position = 0
        while (position < source.length) {
            val character = source[position]
            when {
                character.isWhitespace() -> position++
                source.startsWith("//", position) || source.startsWith("<!--", position) || source.startsWith("-->", position) -> {
                    position = source.indexOf('\n', position).takeIf { it >= 0 } ?: source.length
                }
                source.startsWith("/*", position) -> {
                    val end = source.indexOf("*/", position + 2).takeIf { it >= 0 } ?: return null
                    position = end + 2
                }
                character == '\'' || character == '"' -> {
                    val start = position++
                    var closed = false
                    while (position < source.length) {
                        val current = source[position++]
                        if (current == '\\') { if (position >= source.length) return null; position++ }
                        else if (current == character) { closed = true; break }
                    }
                    if (!closed) return null
                    val value = ScriptLiterals.arguments("literal(" + source.substring(start, position) + ")", "literal")?.singleOrNull() ?: return null
                    result.add(Token(Kind.STRING, value))
                }
                character.isLetterOrDigit() || character == '_' || character == '$' -> {
                    val start = position++
                    while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_' || source[position] == '$')) position++
                    result.add(Token(Kind.WORD, source.substring(start, position)))
                }
                else -> {
                    val operator = listOf("===", "!==", "==", "!=", "&&", "||").firstOrNull { source.startsWith(it, position) }
                    val symbol = operator ?: character.toString()
                    result.add(Token(Kind.SYMBOL, symbol)); position += symbol.length
                }
            }
        }
        return result
    }

    private fun fail(): Nothing = throw SiteException("LOGIN_CONTRACT", "로그인 폼·입력항목·요청 연결을 확인할 수 없습니다.")
}
