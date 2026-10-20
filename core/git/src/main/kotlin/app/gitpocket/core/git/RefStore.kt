package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.io.File
import java.nio.charset.StandardCharsets

sealed interface Head {
    /** 指向某个分支；oid 为 null 表示该分支尚未有提交（unborn）。 */
    data class Symbolic(val target: String, val oid: ObjectId?) : Head

    /** 分离头指针。 */
    data class Detached(val oid: ObjectId) : Head
}

/** HEAD 当前指向的提交（unborn 时为 null）。 */
val Head.headOid: ObjectId?
    get() = when (this) {
        is Head.Symbolic -> oid
        is Head.Detached -> oid
    }

/**
 * 引用存储：loose refs + packed-refs。
 * 写入使用 `<ref>.lock` + rename，保证原子性。
 */
class RefStore(private val gitDir: File) {

    fun head(): Head {
        val file = File(gitDir, "HEAD")
        if (!file.isFile) return Head.Symbolic("refs/heads/main", null)

        val text = file.readText(StandardCharsets.UTF_8).trim()
        if (text.startsWith("ref: ")) {
            val target = text.removePrefix("ref: ").trim()
            return Head.Symbolic(target, read(target))
        }
        return Head.Detached(ObjectId(text))
    }

    fun currentBranch(): String? = (head() as? Head.Symbolic)?.target?.removePrefix("refs/heads/")

    fun read(name: String): ObjectId? {
        val loose = File(gitDir, name)
        if (loose.isFile) {
            val text = loose.readText(StandardCharsets.UTF_8).trim()
            if (text.startsWith("ref: ")) return read(text.removePrefix("ref: ").trim())
            return runCatching { ObjectId(text) }.getOrNull()
        }
        return packedRefs()[name]
    }

    fun listRefs(prefix: String = "refs/"): Map<String, ObjectId> {
        val result = LinkedHashMap<String, ObjectId>()
        packedRefs().forEach { (name, oid) -> if (name.startsWith(prefix)) result[name] = oid }

        val root = File(gitDir, prefix)
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".lock") }
                .forEach { f ->
                    val name = f.relativeTo(gitDir).invariantSeparatorsPath
                    if (name.startsWith(prefix)) {
                        val oid = runCatching { read(name) }.getOrNull()
                        if (oid != null) result[name] = oid
                    }
                }
        }
        return result
    }

    fun update(name: String, id: ObjectId) {
        val target = File(gitDir, name)
        target.parentFile?.mkdirs()
        val lock = File(target.parentFile, "${target.name}.lock")
        try {
            lock.writeText("${id.hex}\n", StandardCharsets.US_ASCII)
            if (!lock.renameTo(target)) {
                target.delete()
                if (!lock.renameTo(target)) throw GitException.Io(target.path, java.io.IOException("更新引用失败"))
            }
        } finally {
            if (lock.exists()) lock.delete()
        }
    }

    fun updateHead(target: String) {
        val headFile = File(gitDir, "HEAD")
        headFile.writeText("ref: $target\n", StandardCharsets.US_ASCII)
    }

    private var packed: Map<String, ObjectId>? = null

    private fun packedRefs(): Map<String, ObjectId> {
        packed?.let { return it }
        val file = File(gitDir, "packed-refs")
        if (!file.isFile) {
            packed = emptyMap()
            return emptyMap()
        }
        val map = LinkedHashMap<String, ObjectId>()
        file.readLines(StandardCharsets.UTF_8).forEach { line ->
            when {
                line.isBlank() || line.startsWith("#") -> Unit
                line.startsWith("^") -> {
                    // peeled 行：只对 tag 有意义，这里忽略
                }
                else -> {
                    val sp = line.indexOf(' ')
                    if (sp == 40) {
                        val oid = runCatching { ObjectId(line.substring(0, 40)) }.getOrNull()
                        val name = line.substring(41).trim()
                        if (oid != null) map[name] = oid
                    }
                }
            }
        }
        packed = map
        return map
    }
}
