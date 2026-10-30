package app.gitpocket.core.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * 拉起系统安装器。
 *
 * 安装包在应用私有缓存里，系统安装器没有读权限，必须经 FileProvider 授权；
 * Android 8 起还要用户单独允许"安装未知应用"，否则会静默失败 —— 所以先检查再引导。
 */
object ApkInstaller {

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 跳到"安装未知应用"授权页（部分 ROM 没有这个页面，回落到应用详情）。 */
    fun openInstallPermissionSettings(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val direct = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(direct) }.isSuccess
        if (!launched) runCatching { context.startActivity(fallback) }
    }
}
