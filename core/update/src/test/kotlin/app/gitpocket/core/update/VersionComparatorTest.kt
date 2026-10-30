package app.gitpocket.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `按数值比较而不是字符串`() {
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.9"))
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.9"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `段数不同时缺的段按 0 算`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertTrue(VersionComparator.isNewer("1.2.1", "1.2"))
        assertTrue(VersionComparator.isNewer("1", "0.9.9"))
    }

    @Test
    fun `忽略 v 前缀与预发布后缀`() {
        assertEquals(0, VersionComparator.compare("v1.2.0", "1.2.0"))
        assertEquals(0, VersionComparator.compare("1.2.0-beta.1", "1.2.0"))
        assertEquals(0, VersionComparator.compare("1.2.0+build7", "1.2.0"))
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.0-beta.3"))
    }

    @Test
    fun `相等与更旧都返回 false`() {
        assertFalse(VersionComparator.isNewer("1.2.0", "1.2.0"))
        assertFalse(VersionComparator.isNewer("1.1.0", "1.2.0"))
        assertFalse(VersionComparator.isNewer("v1.1.0", "1.2.0"))
    }

    @Test
    fun `开发版本号不会被当成更新`() {
        // 本地验收包是 0.1.0-dev.<时间戳>，不应该把任何正式版"比下去"，
        // 但正式版 1.2.0 一定比它新 —— 这正是"开发包能收到更新"的前提。
        assertTrue(VersionComparator.isNewer("1.2.0", "0.1.0-dev.261023.1036"))
        assertFalse(VersionComparator.isNewer("0.1.0", "0.1.0-dev.261023.1036"))
    }

    @Test
    fun `脏数据不崩`() {
        assertEquals(0, VersionComparator.compare("", ""))
        assertTrue(VersionComparator.isNewer("1.2.0", ""))
        assertEquals(0, VersionComparator.compare("abc", "abc"))
    }
}
