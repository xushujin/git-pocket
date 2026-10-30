package app.gitpocket.core.update

/** 一次可用的更新。字段都来自 GitHub Release，不含任何本地状态。 */
data class UpdateInfo(
    /** 语义化版本，去掉前缀 v：`v1.2.0` → `1.2.0` */
    val versionName: String,
    val tagName: String,
    /** Release 说明（Markdown 原文） */
    val notes: String,
    val publishedAt: String,
    val apkUrl: String,
    val apkSize: Long,
    /** 校验和文件地址（`<apk>.sha256`），发布流程会一并上传；缺省时只做签名校验 */
    val sha256Url: String?,
)

/** 检查更新的结果：要么有新版本，要么已经是最新，要么出错。 */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
