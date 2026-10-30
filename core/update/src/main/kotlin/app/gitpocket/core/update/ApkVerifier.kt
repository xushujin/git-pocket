package app.gitpocket.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 安装包校验。
 *
 * 两道关，缺一不可：
 * 1. **SHA-256**：和 Release 里发布的 `.apk.sha256` 比对，防止下载被截断/篡改；
 * 2. **签名证书**：必须和当前已安装应用同一张证书，否则系统本来也装不上
 *    （INSTALL_FAILED_UPDATE_INCOMPATIBLE），提前拦下来能给出可读的原因。
 */
object ApkVerifier {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** 与已安装应用签名一致时返回 true；APK 无法解析（下载损坏）也返回 false。 */
    fun sameSignerAsInstalled(context: Context, apk: File): Boolean {
        val downloaded = signerDigests(context, apk.absolutePath) ?: return false
        val installed = signerDigests(context, context.packageName) ?: return false
        return downloaded.isNotEmpty() && downloaded == installed
    }

    /** 当前设备是否允许安装未知来源应用（Android 8 起是逐应用授权，minSdk 就是 8.0）。 */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    private fun signerDigests(context: Context, pathOrPackage: String): Set<String>? {
        val isArchive = pathOrPackage.contains('/') || pathOrPackage.endsWith(".apk")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9+：signingInfo 能正确处理密钥轮换（signingCertificateHistory）
            val info = packageInfo(context, pathOrPackage, isArchive, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return null
            val signingInfo = info.signingInfo ?: return null
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers?.map { sha256(it.toByteArray()) }?.toSet()
        } else {
            // Android 8.0 / 8.1：只有已废弃的 signatures 字段可用
            @Suppress("DEPRECATION")
            val info = packageInfo(context, pathOrPackage, isArchive, PackageManager.GET_SIGNATURES)
                ?: return null
            @Suppress("DEPRECATION")
            val signatures = info.signatures ?: return null
            signatures.map { sha256(it.toByteArray()) }.toSet()
        }
    }

    private fun packageInfo(
        context: Context,
        pathOrPackage: String,
        isArchive: Boolean,
        flags: Int,
    ): PackageInfo? = runCatching {
        if (isArchive) {
            context.packageManager.getPackageArchiveInfo(pathOrPackage, flags)
        } else {
            context.packageManager.getPackageInfo(pathOrPackage, flags)
        }
    }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
