package app.gitpocket.core.update

/**
 * 解析 GitHub Releases 的 Atom 订阅（`releases.atom`）。
 *
 * 为什么需要它：`api.github.com` 对匿名调用有每小时 60 次的限制，按 **出口 IP** 计。
 * 用户在公司网络、运营商 NAT 后面时，这个额度经常被别人用光，直接 403 —— 这时如果
 * 只认 API，检查更新就彻底不可用了。`releases.atom` 是普通页面请求，不受该限制。
 *
 * 代价：Atom 里没有资产列表，只能按发布规范拼下载地址
 * （`releases/download/<tag>/GitPocket-<version>.apk`）。CI 就是按这个规则上传的，
 * 拼错时下载会 404，用户看到的是"下载失败"而不是静默装错包。
 */
object AtomParser {

    private val entryRegex = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("/releases/tag/([^\"<]+)")
    private val updatedRegex = Regex("<updated>([^<]+)</updated>")
    private val titleRegex = Regex("<title>([^<]*)</title>")

    fun parse(xml: String, owner: String, repo: String): UpdateInfo? {
        // 订阅按时间倒序，第一条就是最新发布
        val entry = entryRegex.find(xml)?.groupValues?.get(1) ?: return null
        val tag = tagRegex.find(entry)?.groupValues?.get(1)?.trim()?.trimEnd('/') ?: return null
        if (tag.isBlank()) return null

        val version = tag.removePrefix("v").removePrefix("V")
        val base = "https://github.com/$owner/$repo/releases/download/$tag"
        val apkUrl = "$base/GitPocket-$version.apk"

        return UpdateInfo(
            versionName = version,
            tagName = tag,
            notes = "",
            publishedAt = updatedRegex.find(entry)?.groupValues?.get(1).orEmpty(),
            apkUrl = apkUrl,
            // Atom 不带资产大小，调用方可以再用 HEAD 请求补上
            apkSize = 0,
            sha256Url = "$apkUrl.sha256",
        )
    }

    /** 订阅标题（`GitPocket v1.2.0` 之类），仅用于排查问题时显示。 */
    fun title(xml: String): String? =
        entryRegex.find(xml)?.groupValues?.get(1)
            ?.let { titleRegex.find(it)?.groupValues?.get(1)?.trim() }
}
