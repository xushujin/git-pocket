package app.gitpocket.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 下载 APK 到应用私有缓存目录，边下边报进度。
 *
 * 取舍：
 * - 先写 `.part` 再改名，避免中断后留下一个看起来完整的 APK；
 * - 每读一块就 `ensureActive()`，用户点取消能立刻停下来（HttpURLConnection 本身不可中断）；
 * - 只在进度变化超过 1% 或 200ms 时回调，避免每 64KB 就重组一次界面。
 */
class UpdateDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    suspend fun download(
        info: UpdateInfo,
        directory: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = File(directory, "GitPocket-${info.versionName}.apk")
        val part = File(directory, "${target.name}.part")
        part.delete()
        target.delete()

        val connection = open(info.apkUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateException("下载失败（HTTP $code）")

            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var downloaded = 0L
            var lastReportedBytes = -1L
            var lastReportedAt = 0L

            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val changedEnough = declared <= 0 ||
                            downloaded - lastReportedBytes >= declared / 100 ||
                            now - lastReportedAt >= 200
                        if (changedEnough) {
                            lastReportedBytes = downloaded
                            lastReportedAt = now
                            onProgress(downloaded, declared)
                        }
                    }
                }
            }
            onProgress(downloaded, if (declared > 0) declared else downloaded)

            if (declared > 0 && downloaded != declared) {
                throw UpdateException("下载不完整：期望 $declared 字节，实际 $downloaded 字节")
            }
            if (!part.renameTo(target)) {
                throw UpdateException("无法保存安装包（重命名失败）")
            }
            target
        } catch (io: IOException) {
            part.delete()
            throw UpdateException("下载中断：${io.message ?: "网络异常"}", io)
        } catch (t: Throwable) {
            part.delete()
            throw t
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
            // GitHub 的资产地址会 302 到 objects.githubusercontent.com
            instanceFollowRedirects = true
            useCaches = false
        }
    }
}
