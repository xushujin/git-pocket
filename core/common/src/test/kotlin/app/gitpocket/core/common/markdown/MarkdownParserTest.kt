package app.gitpocket.core.common.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `标题按级别解析`() {
        val blocks = MarkdownParser.parse("# 一级\n\n### 三级")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Heading(1, "一级"), blocks[0])
        assertEquals(MdBlock.Heading(3, "三级"), blocks[1])
    }

    @Test
    fun `围栏代码块保留语言与内容`() {
        val md = "```kotlin\nval a = 1\nval b = 2\n```"
        val blocks = MarkdownParser.parse(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nval b = 2", code.code)
    }

    @Test
    fun `未闭合围栏吃到文末`() {
        val blocks = MarkdownParser.parse("```\nabc\ndef")
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("abc\ndef", code.code)
    }

    @Test
    fun `列表与引用`() {
        val md = "- a\n- b\n\n1. x\n2. y\n\n> 引用"
        val blocks = MarkdownParser.parse(md)
        assertEquals(MdBlock.BulletList(listOf("a", "b")), blocks[0])
        assertEquals(MdBlock.OrderedList(1, listOf("x", "y")), blocks[1])
        assertEquals(MdBlock.Quote("引用"), blocks[2])
    }

    @Test
    fun `有序列表保留起始序号`() {
        val blocks = MarkdownParser.parse("3. c\n4. d")
        assertEquals(MdBlock.OrderedList(3, listOf("c", "d")), blocks[0])
    }

    @Test
    fun `分割线`() {
        val blocks = MarkdownParser.parse("上\n\n---\n\n下")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Divider, blocks[1])
    }

    @Test
    fun `连续行合并为段落`() {
        val blocks = MarkdownParser.parse("第一行\n第二行\n\n新段落")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("第一行\n第二行"), blocks[0])
        assertEquals(MdBlock.Paragraph("新段落"), blocks[1])
    }

    @Test
    fun `空文档返回空列表`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("\n\n").isEmpty())
    }
}
