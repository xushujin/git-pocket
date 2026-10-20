package app.gitpocket.core.common

import android.util.Log

/**
 * 日志门面。
 * 硬规则：token、Authorization、URL 里的凭据一律不得进日志（release 只输出 WARN 以上）。
 */
object AppLog {

    private const val TAG = "GitPocket"

    fun d(message: String) {
        if (BuildConfigCompat.DEBUG) Log.d(TAG, redact(message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, redact(message), throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, redact(message), throwable)
    }

    /** 去掉 URL 中的 userinfo 与常见 token 参数。 */
    fun redact(text: String): String {
        var result = text.replace(Regex("://[^/@\\s]+@"), "://***@")
        result = result.replace(Regex("(?i)(access_token|token|authorization)=([^&\\s]+)"), "$1=***")
        return result
    }
}

/** 避免依赖 BuildConfig（core 模块没有 buildConfig），用 ApplicationInfo 判断。 */
internal object BuildConfigCompat {
    val DEBUG: Boolean = runCatching {
        val clazz = Class.forName("app.gitpocket.BuildConfig")
        clazz.getField("DEBUG").getBoolean(null)
    }.getOrDefault(false)
}
