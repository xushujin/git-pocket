package app.gitpocket.core.git

import app.gitpocket.core.model.DiffLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    @Test
    fun `内容相同不产生 hunk`() {
        val diff = LineDiff.diff("a\nb\n", "a\nb\n", "a.txt")
        assertTrue(diff.hunks.isEmpty())
        assertEquals(0, diff.additions)
        assertEquals(0, diff.deletions)
    }

    @Test
    fun `单行替换产生一个删除与一个新增`() {
        val diff = LineDiff.diff("a\nb\nc\n", "a\nB\nc\n", "a.txt")
        assertEquals(1, diff.hunks.size)
        assertEquals(1, diff.additions)
        assertEquals(1, diff.deletions)
        val kinds = diff.hunks.first().lines.map { it.kind }
        assertTrue(kinds.contains(DiffLineKind.REMOVED))
        assertTrue(kinds.contains(DiffLineKind.ADDED))
    }

    @Test
    fun `新增行能正确编号`() {
        val diff = LineDiff.diff("a\nc\n", "a\nb\nc\n", "a.txt")
        assertEquals(1, diff.additions)
        assertEquals(0, diff.deletions)
        val added = diff.hunks.first().lines.first { it.kind == DiffLineKind.ADDED }
        assertEquals("b", added.text)
        assertEquals(2, added.newLineNumber)
        assertEquals(null, added.oldLineNumber)
    }

    @Test
    fun `删除行保留旧行号`() {
        val diff = LineDiff.diff("a\nb\nc\n", "a\nc\n", "a.txt")
        assertEquals(1, diff.deletions)
        val removed = diff.hunks.first().lines.first { it.kind == DiffLineKind.REMOVED }
        assertEquals("b", removed.text)
        assertEquals(2, removed.oldLineNumber)
        assertEquals(null, removed.newLineNumber)
    }

    @Test
    fun `相距较远的变更会拆成多个 hunk`() {
        val old = (1..40).joinToString("\n") { "line$it" }
        val new = old.replace("line2\n", "line2-changed\n").replace("line38", "line38-changed")
        val diff = LineDiff.diff(old, new, "a.txt")
        assertEquals(2, diff.hunks.size)
    }

    @Test
    fun `超大文件降级为统计结果`() {
        val old = (1..6000).joinToString("\n") { "line$it" }
        val new = old + "\nextra"
        val diff = LineDiff.diff(old, new, "big.txt")
        assertTrue(diff.truncated)
        assertTrue(diff.hunks.isEmpty())
    }

    @Test
    fun `二进制内容被识别`() {
        val diff = LineDiff.diff("a\u0000b", "a\u0000c", "bin")
        assertTrue(diff.isBinary)
        assertFalse(diff.isIdentical)
    }

    @Test
    fun `空文件到有内容`() {
        val diff = LineDiff.diff("", "hello", "new.txt")
        assertEquals(1, diff.additions)
        assertEquals(0, diff.deletions)
    }
}
