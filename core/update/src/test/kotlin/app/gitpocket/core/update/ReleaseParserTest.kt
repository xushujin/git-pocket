package app.gitpocket.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private fun release(
        tag: String = "v1.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = APK_AND_SHA,
    ) = """
        {
          "tag_name": "$tag",
          "name": "GitPocket $tag",
          "body": "### 修复\n- 拉取不再覆盖未提交改动",
          "published_at": "2026-10-23T02:50:00Z",
          "draft": $draft,
          "prerelease": $prerelease,
          "assets": [$assets]
        }
    """.trimIndent()

    @Test
    fun `解析正常发布并挑出 apk 与校验和`() {
        val info = ReleaseParser.parse(release())
        assertNotNull(info)
        info!!
        assertEquals("1.2.0", info.versionName)
        assertEquals("v1.2.0", info.tagName)
        assertEquals(2_098_623L, info.apkSize)
        assertEquals(APK_URL, info.apkUrl)
        assertEquals(SHA_URL, info.sha256Url)
        assertTrue(info.notes.contains("拉取"))
    }

    @Test
    fun `草稿与预发布不算更新`() {
        assertNull(ReleaseParser.parse(release(draft = true)))
        assertNull(ReleaseParser.parse(release(prerelease = true)))
    }

    @Test
    fun `没有 apk 资产时返回 null`() {
        val onlyMapping = """
            {"name": "mapping.txt.gz", "size": 123, "browser_download_url": "https://example.com/mapping.txt.gz"}
        """.trimIndent()
        assertNull(ReleaseParser.parse(release(assets = onlyMapping)))
    }

    @Test
    fun `缺少校验和资产时只返回 apk 信息`() {
        val onlyApk = """
            {"name": "GitPocket-1.2.0.apk", "size": 10, "browser_download_url": "$APK_URL"}
        """.trimIndent()
        val info = ReleaseParser.parse(release(assets = onlyApk))!!
        assertEquals(APK_URL, info.apkUrl)
        assertNull(info.sha256Url)
    }

    @Test
    fun `非法 JSON 抛可读异常`() {
        val error = runCatching { ReleaseParser.parse("<html>403</html>") }.exceptionOrNull()
        assertTrue(error is UpdateException)
        assertTrue(error!!.message!!.contains("解析失败"))
    }

    @Test
    fun `校验和文件按 sha256sum 格式取第一段`() {
        val hex = "9ec2e3804d1f75a7cd32ed33e9552809178ab3af8b964a4294f012685e291163"
        assertEquals(hex, ReleaseParser.parseSha256File("$hex  GitPocket-1.2.0.apk"))
        assertEquals(hex, ReleaseParser.parseSha256File("${hex.uppercase()}\tGitPocket-1.2.0.apk\n"))
        // 长度不对 / 非十六进制都当作无效，避免拿半截哈希去比对
        assertNull(ReleaseParser.parseSha256File("abc  file"))
        assertNull(ReleaseParser.parseSha256File("zz2e3804d1f75a7cd32ed33e9552809178ab3af8b964a4294f012685e291163"))
    }

    private companion object {
        const val APK_URL = "https://github.com/xushujin/git-pocket/releases/download/v1.2.0/GitPocket-1.2.0.apk"
        const val SHA_URL = "https://github.com/xushujin/git-pocket/releases/download/v1.2.0/GitPocket-1.2.0.apk.sha256"
        const val APK_AND_SHA = """
            {"name": "GitPocket-1.2.0.apk", "size": 2098623, "browser_download_url": "$APK_URL"},
            {"name": "GitPocket-1.2.0.apk.sha256", "size": 96, "browser_download_url": "$SHA_URL"},
            {"name": "mapping.txt.gz", "size": 456, "browser_download_url": "https://example.com/mapping.txt.gz"}
        """
    }
}
