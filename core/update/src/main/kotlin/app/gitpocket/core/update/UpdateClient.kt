package app.gitpocket.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * GitHub Release 查询。
 *
 * 只用 `java.net`，不引第三方 HTTP 库（与 `core:git` 的传输层保持同一套取舍）。
 *
 * 两条通道：
 * 1. `api.github.com`（信息最全：更新说明、资产大小、草稿/预发布标记）；
 * 2. 匿名额度被用光（403/429）时退到 `releases.atom` —— 普通页面请求，不受该限制。
 *    代价是拿不到更新说明，资产地址按发布规范拼出来。
 */
class UpdateClient(
    private val owner: String = "xushujin",
    private val repo: String = "git-pocket",
    private val userAgent: String = "GitPocket",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {

    private val latestUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
    private val atomUrl = "https://github.com/$owner/$repo/releases.atom"

    /** 返回最新正式版；仓库还没有 Release 时返回 null。 */
    suspend fun latestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        val response = request(latestUrl, accept = "application/vnd.github+json")
        when (response.code) {
            HttpURLConnection.HTTP_OK -> ReleaseParser.parse(response.body)
            HttpURLConnection.HTTP_NOT_FOUND -> null
            // 限流：换一条不受 API 额度限制的通道
            HttpURLConnection.HTTP_FORBIDDEN, 429 -> fromAtom()
            else -> throw UpdateException("查询更新失败（HTTP ${response.code}）")
        }
    }

    /** 读取校验和文件（纯文本）。 */
    suspend fun sha256Of(url: String): String? = withContext(Dispatchers.IO) {
        val response = request(url, accept = "text/plain")
        if (response.code != HttpURLConnection.HTTP_OK) return@withContext null
        ReleaseParser.parseSha256File(response.body)
    }

    private suspend fun fromAtom(): UpdateInfo? {
        val response = request(atomUrl, accept = "application/atom+xml")
        if (response.code != HttpURLConnection.HTTP_OK) {
            throw UpdateException("查询更新失败（HTTP ${response.code}）")
        }
        val info = AtomParser.parse(response.body, owner, repo) ?: return null
        // Atom 没有资产大小，补一个 HEAD 请求让进度条能算出百分比
        val size = contentLength(info.apkUrl)
        return if (size > 0) info.copy(apkSize = size) else info
    }

    private suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url).apply { requestMethod = "HEAD" }
            try {
                if (connection.responseCode in 200..299) connection.contentLengthLong else -1L
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(-1L)
    }

    private data class Response(val code: Int, val body: String)

    private suspend fun request(url: String, accept: String): Response {
        val connection = open(url)
        return try {
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            coroutineContext.ensureActive()
            Response(code, body)
        } catch (io: IOException) {
            throw UpdateException("网络不可用：${io.message ?: "连接失败"}", io)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw UpdateException("下载地址非法：$url")
        if (parsed.protocol != "https") throw UpdateException("只允许 https 下载：$url")
        return (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            useCaches = false
        }
    }
}
