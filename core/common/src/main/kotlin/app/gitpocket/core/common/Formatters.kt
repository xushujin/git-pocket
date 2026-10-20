package app.gitpocket.core.common

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** 时间与体积的展示格式化。集中一处，避免各页面各写一套。 */
object Formatters {

    fun relativeTime(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val delta = (nowSeconds - epochSeconds).coerceAtLeast(0)
        return when {
            delta < 60 -> "刚刚"
            delta < 3600 -> "${delta / 60} 分钟前"
            delta < 86_400 -> "${delta / 3600} 小时前"
            delta < 2_592_000 -> "${delta / 86_400} 天前"
            else -> {
                val date = java.util.Calendar.getInstance().apply {
                    timeInMillis = epochSeconds * 1000
                }
                String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    date.get(java.util.Calendar.YEAR),
                    date.get(java.util.Calendar.MONTH) + 1,
                    date.get(java.util.Calendar.DAY_OF_MONTH),
                )
            }
        }
    }

    fun bytes(size: Long): String {
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB")
        var value = size.toDouble() / 1024
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}

/** 同一仓库的写操作串行化，避免 index 并发写坏。 */
class RepoLockRegistry {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(repoId: String, block: suspend () -> T): T {
        val mutex = locks.getOrPut(repoId) { Mutex() }
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
