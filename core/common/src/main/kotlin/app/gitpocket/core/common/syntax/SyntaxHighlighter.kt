package app.gitpocket.core.common.syntax

/**
 * 轻量词法高亮器：不做语法分析，只按"注释 / 字符串 / 数字 / 标识符"扫描。
 *
 * 设计约束：
 * - 纯 Kotlin，无 Android 依赖，可在 JVM 单测里直接验证；
 * - 只产出字符区间，具体颜色由 UI 层决定（不把主题耦合进来）；
 * - 扫描是单遍 O(n)，对 100KB 级文件也够快。
 */
object SyntaxHighlighter {

    private const val MAX_HIGHLIGHT_LENGTH = 200_000

    fun tokenize(text: String, language: Language): List<TokenSpan> {
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_LENGTH) return emptyList()
        return when (language) {
            Language.PLAIN -> emptyList()
            Language.MARKDOWN -> tokenizeMarkdown(text)
            Language.XML -> tokenizeXml(text)
            else -> tokenizeCode(text, rulesFor(language))
        }
    }

    // ------------------------------------------------------------ 通用代码

    private data class Rules(
        val keywords: Set<String>,
        val lineComment: String? = null,
        val blockComment: Pair<String, String>? = null,
        val strings: List<Char> = listOf('"'),
        val tripleQuote: Boolean = false,
    )

    private fun rulesFor(language: Language): Rules = when (language) {
        Language.KOTLIN -> Rules(
            keywords = setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var",
                "if", "else", "when", "for", "while", "do", "return", "break", "continue",
                "try", "catch", "finally", "throw", "is", "as", "in", "out", "null", "true",
                "false", "this", "super", "override", "private", "public", "internal",
                "protected", "open", "data", "sealed", "enum", "companion", "suspend",
                "inline", "lateinit", "const", "by", "init", "constructor", "typealias",
                "where", "reified", "crossinline", "noinline", "operator", "infix", "tailrec",
            ),
            lineComment = "//",
            blockComment = "/*" to "*/",
            strings = listOf('"', '\''),
            tripleQuote = true,
        )

        Language.JAVA -> Rules(
            keywords = setOf(
                "package", "import", "class", "interface", "enum", "extends", "implements",
                "public", "private", "protected", "static", "final", "abstract", "synchronized",
                "volatile", "transient", "native", "new", "return", "if", "else", "for",
                "while", "do", "switch", "case", "default", "break", "continue", "try",
                "catch", "finally", "throw", "throws", "this", "super", "null", "true",
                "false", "instanceof", "void",
            ),
            lineComment = "//",
            blockComment = "/*" to "*/",
            strings = listOf('"', '\''),
        )

        Language.JAVASCRIPT, Language.TYPESCRIPT -> Rules(
            keywords = setOf(
                "const", "let", "var", "function", "return", "if", "else", "for", "while",
                "do", "switch", "case", "default", "break", "continue", "try", "catch",
                "finally", "throw", "new", "class", "extends", "super", "this", "null",
                "undefined", "true", "false", "typeof", "instanceof", "in", "of", "import",
                "export", "from", "default", "async", "await", "yield", "interface", "type",
                "enum", "implements", "public", "private", "readonly", "as",
            ),
            lineComment = "//",
            blockComment = "/*" to "*/",
            strings = listOf('"', '\'', '`'),
        )

        Language.JSON -> Rules(keywords = setOf("true", "false", "null"), strings = listOf('"'))

        Language.PYTHON -> Rules(
            keywords = setOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "break",
                "continue", "import", "from", "as", "try", "except", "finally", "raise",
                "with", "lambda", "pass", "yield", "global", "nonlocal", "assert", "del",
                "and", "or", "not", "is", "in", "None", "True", "False", "async", "await",
            ),
            lineComment = "#",
            strings = listOf('"', '\''),
            tripleQuote = true,
        )

        Language.SHELL -> Rules(
            keywords = setOf(
                "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while",
                "case", "esac", "function", "return", "exit", "export", "local", "echo",
                "cd", "source", "set", "unset", "readonly",
            ),
            lineComment = "#",
            strings = listOf('"', '\''),
        )

        else -> Rules(keywords = emptySet())
    }

    private fun tokenizeCode(text: String, rules: Rules): List<TokenSpan> {
        val spans = ArrayList<TokenSpan>()
        val n = text.length
        var i = 0

        while (i < n) {
            val c = text[i]

            // 块注释
            val block = rules.blockComment
            if (block != null && text.startsWith(block.first, i)) {
                val close = text.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                spans.add(TokenSpan(i, end, TokenKind.COMMENT))
                i = end
                continue
            }

            // 行注释
            val line = rules.lineComment
            if (line != null && text.startsWith(line, i)) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                spans.add(TokenSpan(i, end, TokenKind.COMMENT))
                i = end
                continue
            }

            // 字符串
            if (c in rules.strings) {
                val triple = rules.tripleQuote && text.startsWith("$c$c$c", i)
                val end = scanString(text, i, c, triple)
                spans.add(TokenSpan(i, end, TokenKind.STRING))
                i = end
                continue
            }

            // 数字
            if (c.isDigit()) {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                spans.add(TokenSpan(i, j, TokenKind.NUMBER))
                i = j
                continue
            }

            // 标识符 / 注解
            if (c.isLetter() || c == '_' || c == '@' || c == '$') {
                val start = i
                if (c == '@') i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i)
                val bare = word.removePrefix("@")

                val kind = when {
                    word.startsWith("@") -> TokenKind.ANNOTATION
                    bare in rules.keywords -> TokenKind.KEYWORD
                    bare.length > 1 && bare[0].isUpperCase() && bare.all { it.isLetterOrDigit() || it == '_' } ->
                        TokenKind.TYPE
                    nextNonSpace(text, i) == '(' -> TokenKind.FUNCTION
                    else -> null
                }
                if (kind != null) spans.add(TokenSpan(start, i, kind))
                continue
            }

            i++
        }
        return spans
    }

    private fun scanString(text: String, start: Int, quote: Char, triple: Boolean): Int {
        val n = text.length
        if (triple) {
            val close = text.indexOf("$quote$quote$quote", start + 3)
            return if (close < 0) n else close + 3
        }
        var i = start + 1
        while (i < n) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i // 未闭合字符串，止于行尾
                else -> i++
            }
        }
        return n
    }

    private fun nextNonSpace(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) text[i] else null
    }

    // ------------------------------------------------------------ XML / HTML

    private fun tokenizeXml(text: String): List<TokenSpan> {
        val spans = ArrayList<TokenSpan>()
        var i = 0
        val n = text.length

        while (i < n) {
            when {
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    spans.add(TokenSpan(i, end, TokenKind.COMMENT))
                    i = end
                }

                text[i] == '<' -> {
                    var j = i + 1
                    if (j < n && (text[j] == '/' || text[j] == '?' || text[j] == '!')) j++
                    val nameStart = j
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_' || text[j] == ':')) j++
                    if (j > nameStart) spans.add(TokenSpan(nameStart, j, TokenKind.TAG))

                    // 标签内的属性
                    while (j < n && text[j] != '>') {
                        when {
                            text[j] == '"' || text[j] == '\'' -> {
                                val quote = text[j]
                                val close = text.indexOf(quote, j + 1).let { if (it < 0) n else it + 1 }
                                spans.add(TokenSpan(j, close, TokenKind.STRING))
                                j = close
                            }

                            text[j].isLetter() -> {
                                val attrStart = j
                                while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_' || text[j] == ':')) j++
                                spans.add(TokenSpan(attrStart, j, TokenKind.ATTRIBUTE))
                            }

                            else -> j++
                        }
                    }
                    i = if (j < n) j + 1 else n
                }

                else -> i++
            }
        }
        return spans
    }

    // ------------------------------------------------------------ Markdown

    private fun tokenizeMarkdown(text: String): List<TokenSpan> {
        val spans = ArrayList<TokenSpan>()
        var offset = 0
        var inFence = false

        text.split("\n").forEach { line ->
            val lineStart = offset
            val lineEnd = offset + line.length

            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                spans.add(TokenSpan(lineStart, lineEnd, TokenKind.MARKER))
            } else if (inFence) {
                if (line.isNotEmpty()) spans.add(TokenSpan(lineStart, lineEnd, TokenKind.CODE))
            } else {
                val heading = Regex("^(#{1,6})\\s").find(line)
                if (heading != null) {
                    spans.add(TokenSpan(lineStart, lineEnd, TokenKind.HEADING))
                } else {
                    Regex("^\\s*([-*+]|\\d+\\.)\\s").find(line)?.let {
                        spans.add(TokenSpan(lineStart + it.range.first, lineStart + it.range.last + 1, TokenKind.MARKER))
                    }
                    if (line.trimStart().startsWith(">")) {
                        spans.add(TokenSpan(lineStart, lineStart + line.indexOf('>') + 1, TokenKind.MARKER))
                    }
                    tokenizeMarkdownInline(line, lineStart, spans)
                }
            }
            offset = lineEnd + 1
        }
        return spans
    }

    private fun tokenizeMarkdownInline(line: String, lineStart: Int, spans: MutableList<TokenSpan>) {
        fun emit(regex: Regex, kind: TokenKind) {
            regex.findAll(line).forEach { match ->
                spans.add(TokenSpan(lineStart + match.range.first, lineStart + match.range.last + 1, kind))
            }
        }
        emit(Regex("`[^`]+`"), TokenKind.CODE)
        emit(Regex("\\*\\*[^*]+\\*\\*"), TokenKind.EMPHASIS)
        emit(Regex("~~[^~]+~~"), TokenKind.EMPHASIS)
        emit(Regex("\\[[^\\]]+]\\s*\\([^)]*\\)"), TokenKind.LINK)
    }
}
