package app.gitpocket.core.update

import android.content.Context
import java.io.File

/**
 * 应用内更新：查最新 Release → 下载 APK → 校验 → 交给系统安装器。
 *
 * 这里只做"能独立测试、不依赖 UI"的那部分；进度和状态由 ViewModel 持有。
 */
class UpdateRepository(
    private val context: Context,
    private val client: UpdateClient = UpdateClient(),
    private val downloader: UpdateDownloader = UpdateDownloader(),
    private val downloadDir: File = File(context.cacheDir, "updates"),
) {

    /** 有新版本返回 [UpdateCheck.Available]，否则 [UpdateCheck.UpToDate]。 */
    suspend fun check(currentVersion: String): UpdateCheck {
        val info = client.latestRelease() ?: return UpdateCheck.UpToDate
        return if (VersionComparator.isNewer(info.versionName, currentVersion)) {
            UpdateCheck.Available(info)
        } else {
            UpdateCheck.UpToDate
        }
    }

    /**
     * 下载并校验。返回的文件一定通过了 SHA-256（若有）与签名比对，
     * 校验不过会删掉文件并抛 [UpdateException]。
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val apk = downloader.download(info, downloadDir, onProgress)

        val expected = info.sha256Url?.let { runCatching { client.sha256Of(it) }.getOrNull() }
        if (expected != null) {
            val actual = ApkVerifier.sha256(apk)
            if (!actual.equals(expected, ignoreCase = true)) {
                apk.delete()
                throw UpdateException("安装包校验失败（SHA-256 不匹配），已删除下载内容")
            }
        }

        if (!ApkVerifier.sameSignerAsInstalled(context, apk)) {
            apk.delete()
            throw UpdateException("安装包签名与当前应用不一致，已拒绝安装")
        }
        return apk
    }

    fun canInstall(): Boolean = ApkVerifier.canInstallPackages(context)

    /** 已经下载好的安装包（用于"重新打开安装器"；进程被杀后仍能找到）。 */
    fun downloadedApk(versionName: String): File? =
        File(downloadDir, "GitPocket-$versionName.apk").takeIf { it.isFile }

    fun install(apk: File) = ApkInstaller.install(context, apk)

    fun openInstallPermissionSettings() = ApkInstaller.openInstallPermissionSettings(context)

    /** 清掉除 [keep] 之外的旧安装包（每次下载新版本前调用）。 */
    fun clearOldDownloads(keep: File? = null) {
        downloadDir.listFiles()?.forEach { file ->
            if (keep == null || file.absolutePath != keep.absolutePath) {
                runCatching { file.delete() }
            }
        }
    }
}
