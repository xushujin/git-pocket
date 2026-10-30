package app.gitpocket.core.update

/**
 * 版本比较：只比数字段。
 *
 * 覆盖实际会遇到的几种写法：
 * - `1.2.0` / `v1.2.0` / `1.2` / `1`（段数不同，缺的段按 0 算）
 * - `1.2.0-beta.1` / `1.2.0+build7`（后缀忽略：预发布版不该盖过正式版）
 * - `1.10.0` vs `1.9.9`（按数值比，不是按字符串比）
 */
object VersionComparator {

    /** a > b 返回正数，a < b 返回负数，相等返回 0。 */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        return 0
    }

    /** [candidate] 是否比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun segments(raw: String): List<Int> {
        val trimmed = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            // 预发布 / 构建元数据不参与比较
            .substringBefore('-')
            .substringBefore('+')
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('.').map { part ->
            // "0rc1" 这种脏数据只取前导数字
            val digits = part.takeWhile { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }
    }
}
