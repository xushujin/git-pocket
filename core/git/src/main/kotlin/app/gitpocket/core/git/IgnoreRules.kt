package app.gitpocket.core.git

import java.io.File

/**
 * .gitignore 匹配（MVP 子集）：
 * - 支持 `#` 注释、空行、`!` 反选、结尾 `/` 目录限定、`*` `?` `**` 通配；
 * - 只读取仓库根目录的 .gitignore（嵌套 .gitignore 未实现，见文档"已知限制"）。
 */
class IgnoreRules private constructor(
    private val rules: List<Rule>,
) {
    private data class Rule(val regex: Regex, val negated: Boolean, val directoryOnly: Boolean)

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        var ignored = false
        rules.forEach { rule ->
            if (rule.directoryOnly && !isDirectory) return@forEach
            if (rule.regex.matches(relativePath)) ignored = !rule.negated
        }
        return ignored
    }

    companion object {
        val EMPTY = IgnoreRules(emptyList())

        fun load(workTree: File): IgnoreRules {
            val file = File(workTree, ".gitignore")
            if (!file.isFile) return EMPTY
            val rules = file.readLines()
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { raw ->
                    val negated = raw.startsWith("!")
                    val body = if (negated) raw.substring(1) else raw
                    if (body.isBlank()) return@mapNotNull null
                    val directoryOnly = body.endsWith("/")
                    val cleaned = body.removeSuffix("/").removePrefix("/")
                    val anchored = body.startsWith("/")
                    Rule(globToRegex(cleaned, anchored), negated, directoryOnly)
                }
            return IgnoreRules(rules)
        }

        private fun globToRegex(glob: String, anchored: Boolean): Regex {
            val sb = StringBuilder()
            if (anchored || glob.contains('/')) sb.append("^") else sb.append("^(?:.*/)?")

            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                if (c == '*' && i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i += 2
                    if (i < glob.length && glob[i] == '/') i++
                    continue
                }
                when {
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c in ".+()^$|{}[]\\" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}
