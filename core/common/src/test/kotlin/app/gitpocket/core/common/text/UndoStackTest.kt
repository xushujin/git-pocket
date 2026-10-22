package app.gitpocket.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private var now = 0L

    private fun newStack(maxSize: Int = 100) = UndoStack(
        maxSize = maxSize,
        burstMs = 1_500L,
        clock = { now },
    )

    /** 模拟连续输入：每个字符间隔 200ms。 */
    private fun type(stack: UndoStack, from: String, text: String, gapMs: Long = 200L): String {
        var current = from
        text.forEach { ch ->
            val next = current + ch
            stack.record(current, next)
            current = next
            now += gapMs
        }
        return current
    }

    @Test
    fun `一口气输入合并成一步`() {
        val stack = newStack()
        val text = type(stack, "", "abc")

        assertEquals("abc", text)
        assertTrue(stack.canUndo)
        assertEquals("", stack.undo())
        assertFalse(stack.canUndo)
    }

    @Test
    fun `停顿后再输入是独立的一步`() {
        val stack = newStack()
        val first = type(stack, "", "aaa")
        now += 2_000
        val second = type(stack, first, "bbb")

        assertEquals("aaabbb", second)
        assertEquals("aaa", stack.undo())
        assertEquals("", stack.undo())
        assertNull(stack.undo())
    }

    @Test
    fun `换行会断开合并`() {
        val stack = newStack()
        val first = type(stack, "", "abc")
        val second = type(stack, first, "\ndef")

        assertEquals("abc\ndef", second)
        assertEquals("abc\n", stack.undo())
    }

    @Test
    fun `删除是独立的一步`() {
        val stack = newStack()
        stack.record("abcdef", "abcde")

        assertEquals("abcdef", stack.undo())
    }

    @Test
    fun `粘贴多个字符不会被当成打字合并`() {
        val stack = newStack()
        val typed = type(stack, "", "x")
        stack.record(typed, typed + "这是一段粘贴内容")

        assertEquals("x", stack.undo())
    }

    @Test
    fun `超过上限时丢弃最老的记录`() {
        val stack = newStack(maxSize = 2)
        stack.record("", "a"); now += 2_000
        stack.record("a", "ab"); now += 2_000
        stack.record("ab", "abc"); now += 2_000

        assertEquals("ab", stack.undo())
        assertEquals("a", stack.undo())
        assertNull(stack.undo())
    }

    @Test
    fun `内容没变不入栈`() {
        val stack = newStack()
        stack.record("same", "same")
        assertFalse(stack.canUndo)
    }

    @Test
    fun `清空后不可回退`() {
        val stack = newStack()
        type(stack, "", "abc")
        stack.clear()
        assertFalse(stack.canUndo)
        assertNull(stack.undo())
    }
}
