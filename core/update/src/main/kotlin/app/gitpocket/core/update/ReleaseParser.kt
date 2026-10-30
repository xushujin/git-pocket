package app.gitpocket.core.update

import org.json.JSONObject

/**
 * 解析 GitHub `releases/latest` 的返回体。
 *
 * 抽成纯函数是为了能单测：Release 的 JSON 结构（assets 数组、draft/prerelease 标记）
 * 一旦变化，这里最先炸，而它又不需要网络就能验证。
 */
object ReleaseParser {

    /** 草稿或预发布不算"最新版本"；没有可下载的 APK 也当作没有更新。 */
    fun parse(json: String): UpdateInfo? {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw UpdateException("更新信息解析失败：返回内容不是合法 JSON")
        }
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null

        val tag = root.optString("tag_name").ifBlank { return null }
        val assets = root.optJSONArray("assets") ?: return null

        var apkUrl: String? = null
        var apkSize = 0L
        var shaUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").lowercase()
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            when {
                name.endsWith(".apk.sha256") || name.endsWith(".sha256") -> shaUrl = url
                name.endsWith(".apk") -> {
                    apkUrl = url
                    apkSize = asset.optLong("size", 0L)
                }
            }
        }
        val download = apkUrl ?: return null

        return UpdateInfo(
            versionName = tag.removePrefix("v").removePrefix("V"),
            tagName = tag,
            notes = root.optString("body").trim(),
            publishedAt = root.optString("published_at"),
            apkUrl = download,
            apkSize = apkSize,
            sha256Url = shaUrl,
        )
    }

    /**
     * `.sha256` 文件内容是 `<hex>  <文件名>`（sha256sum 的格式）。
     * 只取第一段十六进制串，并统一成小写。
     */
    fun parseSha256File(text: String): String? {
        val hex = text.trim().substringBefore(' ').substringBefore('\t').trim().lowercase()
        return hex.takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdef" } }
    }
}
