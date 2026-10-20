package app.gitpocket.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    private val now = 1_700_000_000L

    @Test
    fun `相对时间覆盖刚发生到多年前`() {
        assertEquals("刚刚", Formatters.relativeTime(now - 10, now))
        assertEquals("3 分钟前", Formatters.relativeTime(now - 180, now))
        assertEquals("2 小时前", Formatters.relativeTime(now - 7_200, now))
        assertEquals("3 天前", Formatters.relativeTime(now - 3 * 86_400, now))
        // 超过 30 天走绝对日期，具体值依赖本地时区，这里只校验格式
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(Formatters.relativeTime(now - 100 * 86_400, now)))
    }

    @Test
    fun `相对时间对时钟回拨不产生负数`() {
        assertEquals("刚刚", Formatters.relativeTime(now + 60, now))
    }

    @Test
    fun `体积格式化保留一位小数`() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 MB", Formatters.bytes(1024L * 1536))
        assertEquals("2.0 GB", Formatters.bytes(1024L * 1024 * 2048))
    }
}
