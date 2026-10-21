package app.gitpocket.core.common.syntax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    private fun spans(text: String, language: Language): List<Pair<String, TokenKind>> =
        SyntaxHighlighter.tokenize(text, language).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `Kotlin 关键字 字符串 注释 数字`() {
        val code = "val name = \"git\" // 注释\nval n = 42"
        val result = spans(code, Language.KOTLIN)

        assertTrue(result.contains("val" to TokenKind.KEYWORD))
        assertTrue(result.contains("\"git\"" to TokenKind.STRING))
        assertTrue(result.contains("// 注释" to TokenKind.COMMENT))
        assertTrue(result.contains("42" to TokenKind.NUMBER))
    }

    @Test
    fun `块注释与三引号字符串`() {
        val code = "/* a\nb */\nval s = \"\"\"raw\ntext\"\"\""
        val result = spans(code, Language.KOTLIN)

        assertTrue(result.any { it.second == TokenKind.COMMENT && it.first.startsWith("/*") })
        assertTrue(result.any { it.second == TokenKind.STRING && it.first.startsWith("\"\"\"") })
    }

    @Test
    fun `函数调用识别为 FUNCTION`() {
        val result = spans("println(\"x\")", Language.KOTLIN)
        assertTrue(result.contains("println" to TokenKind.FUNCTION))
    }

    @Test
    fun `Markdown 标题 围栏 行内代码 加粗`() {
        val md = "# 标题\n```kotlin\nval a = 1\n```\n正文 `code` 和 **粗体**"
        val result = spans(md, Language.MARKDOWN)

        assertTrue(result.any { it.second == TokenKind.HEADING && it.first.startsWith("# 标题") })
        assertTrue(result.any { it.second == TokenKind.MARKER && it.first.startsWith("```") })
        assertTrue(result.contains("val a = 1" to TokenKind.CODE))
        assertTrue(result.contains("`code`" to TokenKind.CODE))
        assertTrue(result.contains("**粗体**" to TokenKind.EMPHASIS))
    }

    @Test
    fun `XML 标签与属性`() {
        val xml = "<a href=\"x\">hi</a>"
        val result = spans(xml, Language.XML)

        assertTrue(result.contains("a" to TokenKind.TAG))
        assertTrue(result.contains("href" to TokenKind.ATTRIBUTE))
        assertTrue(result.contains("\"x\"" to TokenKind.STRING))
    }

    @Test
    fun `纯文本不产生 span`() {
        assertEquals(0, SyntaxHighlighter.tokenize("hello world", Language.PLAIN).size)
    }

    @Test
    fun `未知扩展名退回 PLAIN`() {
        assertEquals(Language.PLAIN, Language.fromPath("a.unknownext"))
        assertEquals(Language.KOTLIN, Language.fromPath("Main.kt"))
        assertEquals(Language.MARKDOWN, Language.fromPath("README.md"))
    }

    @Test
    fun `span 区间不越界且有序`() {
        val code = "val a = \"x\"\nfun b() { return }"
        val spans = SyntaxHighlighter.tokenize(code, Language.KOTLIN)
        var last = -1
        spans.forEach { span ->
            assertTrue(span.start >= last)
            assertTrue(span.end <= code.length)
            assertTrue(span.start < span.end)
            last = span.start
        }
    }
}
