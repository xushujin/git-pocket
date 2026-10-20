package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.nio.charset.StandardCharsets

enum class GitObjectType(val code: Int, val label: String) {
    COMMIT(1, "commit"),
    TREE(2, "tree"),
    BLOB(3, "blob"),
    TAG(4, "tag"),
    ;

    companion object {
        fun fromCode(code: Int): GitObjectType =
            entries.firstOrNull { it.code == code }
                ?: throw GitException.ObjectCorrupted("", "未知对象类型码 $code")

        fun fromLabel(label: String): GitObjectType =
            entries.firstOrNull { it.label == label }
                ?: throw GitException.ObjectCorrupted("", "未知对象类型 $label")
    }
}

sealed interface GitObject {
    val type: GitObjectType
    val content: ByteArray
}

class GitBlob(override val content: ByteArray) : GitObject {
    override val type: GitObjectType get() = GitObjectType.BLOB
    fun text(): String = String(content, StandardCharsets.UTF_8)
}

/** mode 用八进制原值（如 0x81A4 = 100644，0x4000 = 040000）。 */
data class GitTreeEntry(val mode: Int, val name: String, val oid: ObjectId) {
    val isDirectory: Boolean get() = mode == MODE_TREE

    companion object {
        const val MODE_TREE = 0x4000          // 040000
        const val MODE_FILE = 0x81A4          // 100644
        const val MODE_EXECUTABLE = 0x81ED    // 100755
        const val MODE_SYMLINK = 0xA000       // 120000
        const val MODE_SUBMODULE = 0xE000     // 160000
    }
}

data class GitTree(val entries: List<GitTreeEntry>) : GitObject {
    override val type: GitObjectType get() = GitObjectType.TREE
    override val content: ByteArray get() = encodeTree(entries)
}

data class GitIdent(
    val name: String,
    val email: String,
    val timeSeconds: Long,
    val tzOffset: String,
) {
    fun format(): String = "$name <$email> $timeSeconds $tzOffset"

    companion object {
        private val PATTERN = Regex("^(.+) <([^>]*)> (\\d+) ([+-]\\d{4})$")

        fun parse(raw: String): GitIdent {
            val m = PATTERN.matchEntire(raw)
                ?: return GitIdent(raw.substringBefore(" <").trim(), "", 0L, "+0000")
            return GitIdent(m.groupValues[1], m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4])
        }
    }
}

data class GitCommit(
    val tree: ObjectId,
    val parents: List<ObjectId>,
    val author: GitIdent,
    val committer: GitIdent,
    val message: String,
    /** 非标准头（gpgsig、encoding 等）原样保留，避免重写提交时破坏签名。 */
    val extraHeaders: List<Pair<String, String>> = emptyList(),
) : GitObject {
    override val type: GitObjectType get() = GitObjectType.COMMIT
    override val content: ByteArray get() = encodeCommit(this)

    val subject: String get() = message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

data class GitTag(
    val objectId: ObjectId,
    val objectType: String,
    val name: String,
    val tagger: GitIdent?,
    val message: String,
) : GitObject {
    override val type: GitObjectType get() = GitObjectType.TAG
    override val content: ByteArray get() = buildString {
        append("object ").append(objectId.hex).append('\n')
        append("type ").append(objectType).append('\n')
        append("tag ").append(name).append('\n')
        tagger?.let { append("tagger ").append(it.format()).append('\n') }
        append('\n')
        append(message)
    }.toByteArray(StandardCharsets.UTF_8)
}

// ---------------------------------------------------------------------------
// 编解码
// ---------------------------------------------------------------------------

internal fun decodeObject(type: GitObjectType, content: ByteArray): GitObject = when (type) {
    GitObjectType.BLOB -> GitBlob(content)
    GitObjectType.COMMIT -> parseCommit(content)
    GitObjectType.TREE -> parseTree(content)
    GitObjectType.TAG -> parseTag(content)
}

fun parseCommit(content: ByteArray): GitCommit {
    val text = String(content, StandardCharsets.UTF_8)
    val sep = text.indexOf("\n\n")
    val headerBlock = if (sep >= 0) text.substring(0, sep) else text
    val message = if (sep >= 0) text.substring(sep + 2) else ""

    var tree: ObjectId? = null
    val parents = mutableListOf<ObjectId>()
    var author: GitIdent? = null
    var committer: GitIdent? = null
    val extra = mutableListOf<Pair<String, String>>()

    val lines = headerBlock.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val space = line.indexOf(' ')
        val key = if (space >= 0) line.substring(0, space) else line
        val value = StringBuilder(if (space >= 0) line.substring(space + 1) else "")

        // 续行：以单个空格开头（gpgsig 等）
        var j = i + 1
        while (j < lines.size && lines[j].startsWith(" ")) {
            value.append('\n').append(lines[j].substring(1))
            j++
        }

        when (key) {
            "tree" -> tree = ObjectId(value.toString().trim())
            "parent" -> parents.add(ObjectId(value.toString().trim()))
            "author" -> author = GitIdent.parse(value.toString())
            "committer" -> committer = GitIdent.parse(value.toString())
            else -> extra.add(key to value.toString())
        }
        i = j
    }

    return GitCommit(
        tree = tree ?: throw GitException.ObjectCorrupted("", "commit 缺少 tree 头"),
        parents = parents,
        author = author ?: GitIdent("unknown", "", 0, "+0000"),
        committer = committer ?: author ?: GitIdent("unknown", "", 0, "+0000"),
        message = message,
        extraHeaders = extra,
    )
}

fun encodeCommit(commit: GitCommit): ByteArray {
    val sb = StringBuilder()
    sb.append("tree ").append(commit.tree.hex).append('\n')
    commit.parents.forEach { sb.append("parent ").append(it.hex).append('\n') }
    sb.append("author ").append(commit.author.format()).append('\n')
    sb.append("committer ").append(commit.committer.format()).append('\n')
    commit.extraHeaders.forEach { (key, value) ->
        sb.append(key).append(' ')
        // 多行头：除首行外每行前加一个空格
        sb.append(value.split('\n').joinToString("\n "))
        sb.append('\n')
    }
    sb.append('\n')
    sb.append(commit.message)
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

fun parseTree(content: ByteArray): GitTree {
    val entries = mutableListOf<GitTreeEntry>()
    var p = 0
    while (p < content.size) {
        val space = indexOf(content, ' '.code.toByte(), p)
        if (space < 0) throw GitException.ObjectCorrupted("", "tree 条目缺少空格分隔")
        val modeText = String(content, p, space - p, StandardCharsets.US_ASCII)
        val mode = modeText.toIntOrNull(8)
            ?: throw GitException.ObjectCorrupted("", "tree 条目 mode 非法：$modeText")

        val nul = indexOf(content, 0, space + 1)
        if (nul < 0) throw GitException.ObjectCorrupted("", "tree 条目缺少 NUL 终止")
        val name = String(content, space + 1, nul - space - 1, StandardCharsets.UTF_8)

        if (nul + 21 > content.size) throw GitException.ObjectCorrupted("", "tree 条目 oid 截断")
        val oid = ObjectId(Sha1.toHex(content.copyOfRange(nul + 1, nul + 21)))

        entries.add(GitTreeEntry(mode, name, oid))
        p = nul + 21
    }
    return GitTree(entries)
}

fun encodeTree(entries: List<GitTreeEntry>): ByteArray {
    var size = 0
    val encoded = entries.map { e ->
        val mode = Integer.toOctalString(e.mode).toByteArray(StandardCharsets.US_ASCII)
        val name = e.name.toByteArray(StandardCharsets.UTF_8)
        val oid = Sha1.fromHex(e.oid.hex)
        size += mode.size + 1 + name.size + 1 + 20
        Triple(mode, name, oid)
    }
    val out = ByteArray(size)
    var p = 0
    encoded.forEach { (mode, name, oid) ->
        mode.copyInto(out, p); p += mode.size
        out[p++] = ' '.code.toByte()
        name.copyInto(out, p); p += name.size
        out[p++] = 0
        oid.copyInto(out, p); p += 20
    }
    return out
}

fun parseTag(content: ByteArray): GitTag {
    val text = String(content, StandardCharsets.UTF_8)
    val sep = text.indexOf("\n\n")
    val header = if (sep >= 0) text.substring(0, sep) else text
    val message = if (sep >= 0) text.substring(sep + 2) else ""
    var objectId: ObjectId? = null
    var objectType = ""
    var name = ""
    var tagger: GitIdent? = null
    header.split("\n").forEach { line ->
        val sp = line.indexOf(' ')
        if (sp > 0) {
            val key = line.substring(0, sp)
            val value = line.substring(sp + 1)
            when (key) {
                "object" -> objectId = ObjectId(value.trim())
                "type" -> objectType = value.trim()
                "tag" -> name = value.trim()
                "tagger" -> tagger = GitIdent.parse(value)
            }
        }
    }
    return GitTag(
        objectId = objectId ?: throw GitException.ObjectCorrupted("", "tag 缺少 object 头"),
        objectType = objectType,
        name = name,
        tagger = tagger,
        message = message,
    )
}

private fun indexOf(bytes: ByteArray, value: Byte, from: Int): Int {
    var i = from
    while (i < bytes.size) {
        if (bytes[i] == value) return i
        i++
    }
    return -1
}
