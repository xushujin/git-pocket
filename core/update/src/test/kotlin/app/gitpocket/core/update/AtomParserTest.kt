package app.gitpocket.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomParserTest {

    @Test
    fun `取最新一条发布并拼出资产地址`() {
        val info = AtomParser.parse(FEED, "xushujin", "git-pocket")!!
        assertEquals("v1.2.0", info.tagName)
        assertEquals("1.2.0", info.versionName)
        assertEquals(
            "https://github.com/xushujin/git-pocket/releases/download/v1.2.0/GitPocket-1.2.0.apk",
            info.apkUrl,
        )
        assertEquals("${info.apkUrl}.sha256", info.sha256Url)
        assertEquals("2026-10-23T02:50:00Z", info.publishedAt)
        // Atom 没有资产大小，交给调用方用 HEAD 补
        assertEquals(0L, info.apkSize)
    }

    @Test
    fun `没有 entry 时返回 null`() {
        assertNull(AtomParser.parse("<feed></feed>", "a", "b"))
        assertNull(AtomParser.parse("", "a", "b"))
    }

    @Test
    fun `title 能取到但只用于排查`() {
        assertTrue(AtomParser.title(FEED)!!.contains("1.2.0"))
    }

    private companion object {
        val FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <id>tag:github.com,2008:https://github.com/xushujin/git-pocket/releases</id>
              <title>Release notes from git-pocket</title>
              <entry>
                <id>tag:github.com,2008:Repository/123/v1.2.0</id>
                <updated>2026-10-23T02:50:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/xushujin/git-pocket/releases/tag/v1.2.0"/>
                <title>GitPocket v1.2.0</title>
                <content type="html">&lt;p&gt;修复拉取覆盖未提交改动&lt;/p&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/123/v1.1.3</id>
                <updated>2026-10-23T01:30:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/xushujin/git-pocket/releases/tag/v1.1.3"/>
                <title>GitPocket v1.1.3</title>
                <content type="html">&lt;p&gt;Apache-2.0&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()
    }
}
