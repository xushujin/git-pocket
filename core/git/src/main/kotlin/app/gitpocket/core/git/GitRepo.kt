package app.gitpocket.core.git

import app.gitpocket.core.model.Branch
import app.gitpocket.core.model.ChangeType
import app.gitpocket.core.model.CommitSummary
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.FileDiff
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.ObjectId
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue

/**
 * 一个工作区仓库的门面。所有面向 UI 的操作都从这里进出。
 *
 * 并发约定：同一个仓库的写操作必须由调用方串行化（上层用 Mutex），
 * 本类自身只保证单次调用的文件原子性。
 */
class GitRepo private constructor(
    val workTree: File,
    val gitDir: File,
) : Closeable {

    val objects: ObjectDatabase = ObjectDatabase(gitDir)
    val refs: RefStore = RefStore(gitDir)

    private val indexFile: File = File(gitDir, "index")
    private val ignoreRules: IgnoreRules by lazy { IgnoreRules.load(workTree) }

    // ---------------------------------------------------------------- 读取

    fun readIndex(): GitIndex = GitIndex.read(indexFile)

    fun headCommit(): GitCommit? = refs.head().headOid?.let { readCommitOrNull(it) }

    private fun readCommitOrNull(id: ObjectId): GitCommit? =
        runCatching { objects.read(id) as? GitCommit }.getOrNull()

    fun log(limit: Int = 50): List<CommitSummary> {
        val start = refs.head().headOid ?: return emptyList()
        val queue = PriorityQueue<Pair<ObjectId, GitCommit>>(
            compareByDescending { it.second.committer.timeSeconds }
        )
        val seen = HashSet<ObjectId>()
        val result = mutableListOf<CommitSummary>()

        readCommitOrNull(start)?.let { queue.add(start to it) }
        while (queue.isNotEmpty() && result.size < limit) {
            val (id, commit) = queue.poll()
            if (!seen.add(id)) continue
            result.add(
                CommitSummary(
                    oid = id,
                    parents = commit.parents,
                    authorName = commit.author.name,
                    authorEmail = commit.author.email,
                    timestampSeconds = commit.author.timeSeconds,
                    subject = commit.subject.ifBlank { "(无提交信息)" },
                )
            )
            commit.parents.forEach { parent ->
                readCommitOrNull(parent)?.let { queue.add(parent to it) }
            }
        }
        return result
    }

    fun branches(): List<Branch> {
        val head = refs.head()
        val current = (head as? Head.Symbolic)?.target
        return refs.listRefs("refs/heads/").map { (name, oid) ->
            Branch(
                name = name.removePrefix("refs/heads/"),
                oid = oid,
                isRemote = false,
                isCurrent = name == current,
            )
        }.sortedBy { it.name }
    }

    /** 把某个 tree 递归摊平成 path -> entry（只含文件）。 */
    fun flattenTree(treeId: ObjectId, prefix: String = ""): Map<String, GitTreeEntry> {
        val tree = objects.read(treeId) as? GitTree ?: return emptyMap()
        val out = LinkedHashMap<String, GitTreeEntry>()
        tree.entries.forEach { entry ->
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isDirectory) {
                out.putAll(flattenTree(entry.oid, path))
            } else {
                out[path] = entry
            }
        }
        return out
    }

    fun headFiles(): Map<String, GitTreeEntry> =
        headCommit()?.let { flattenTree(it.tree) } ?: emptyMap()

    fun status(): List<FileChange> {
        val headFiles = headFiles()
        val index = readIndex().toMap()
        val result = mutableListOf<FileChange>()

        // 已暂存：index vs HEAD
        index.forEach { (path, entry) ->
            val head = headFiles[path]
            when {
                head == null -> result.add(FileChange(path, ChangeType.ADDED, staged = true))
                head.oid != entry.oid -> result.add(FileChange(path, ChangeType.MODIFIED, staged = true))
            }
        }
        headFiles.keys.filterNot { it in index }.forEach {
            result.add(FileChange(it, ChangeType.DELETED, staged = true))
        }

        // 未暂存：工作区 vs index
        index.forEach { (path, entry) ->
            val file = File(workTree, path)
            if (!file.isFile) {
                result.add(FileChange(path, ChangeType.DELETED, staged = false))
                return@forEach
            }
            val changed = if (file.length() != entry.size) {
                true
            } else {
                // stat 快路径 + racy-clean 兜底：
                // index 写入与文件修改落在同一秒时，mtime 不可信，必须重新哈希。
                val lastModified = file.lastModified()
                val sameSecond = lastModified / 1000 == entry.mtimeSeconds
                val sameNanos = ((lastModified % 1000) * 1_000_000).toInt() == entry.mtimeNanos
                val racyClean = entry.mtimeSeconds >= (indexFile.lastModified() / 1000) - 1
                if (!sameSecond || !sameNanos || racyClean) hashFile(file) != entry.oid else false
            }
            if (changed) result.add(FileChange(path, ChangeType.MODIFIED, staged = false))
        }

        // 未跟踪
        val tracked = index.keys
        workTree.walkTopDown()
            .onEnter { dir ->
                dir.name != ".git" && (dir == workTree || !isIgnoredDir(dir))
            }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(workTree).invariantSeparatorsPath
                if (relative !in tracked && !ignoreRules.isIgnored(relative, false)) {
                    result.add(FileChange(relative, ChangeType.UNTRACKED, staged = false))
                }
            }

        return result
    }

    fun workTreeEntries(directory: String = ""): List<FileEntry> {
        val dir = if (directory.isEmpty()) workTree else File(workTree, directory)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.name != ".git" }
            ?.map { file ->
                val path = if (directory.isEmpty()) file.name else "$directory/${file.name}"
                FileEntry(
                    name = file.name,
                    path = path,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0,
                    modifiedAtSeconds = file.lastModified() / 1000,
                )
            }
            ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
    }

    fun readWorkFile(path: String): String? {
        val file = File(workTree, path)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (isBinary(bytes)) return null
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun fileContentAtHead(path: String): String? {
        val entry = headFiles()[path] ?: return null
        val blob = objects.read(entry.oid) as? GitBlob ?: return null
        if (isBinary(blob.content)) return null
        return blob.text()
    }

    fun diffWorktree(path: String): FileDiff {
        val file = File(workTree, path)
        if (!file.isFile) return FileDiff(path, false, emptyList(), 0, 0)
        val newBytes = file.readBytes()
        if (isBinary(newBytes)) return FileDiff(path, true, emptyList(), 0, 0)

        val indexEntry = readIndex().entry(path)
        val oldText = indexEntry?.let { (objects.read(it.oid) as? GitBlob)?.text() } ?: ""
        return LineDiff.diff(oldText, String(newBytes, StandardCharsets.UTF_8), path)
    }

    fun diffAgainstHead(path: String): FileDiff {
        val file = File(workTree, path)
        if (!file.isFile) return FileDiff(path, false, emptyList(), 0, 0)
        val newBytes = file.readBytes()
        if (isBinary(newBytes)) return FileDiff(path, true, emptyList(), 0, 0)
        val oldText = fileContentAtHead(path) ?: ""
        return LineDiff.diff(oldText, String(newBytes, StandardCharsets.UTF_8), path)
    }

    // ---------------------------------------------------------------- 写入

    fun writeWorkFile(path: String, content: String) {
        val file = File(workTree, path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(content, StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw GitException.Io(file.path, java.io.IOException("写入失败"))
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun stage(path: String) {
        val index = readIndex()
        val file = File(workTree, path)
        if (!file.isFile) {
            index.remove(path)
            index.writeTo(indexFile)
            return
        }
        val bytes = file.readBytes()
        val oid = objects.write(GitBlob(bytes))
        val lastModified = file.lastModified()
        val seconds = lastModified / 1000
        val nanos = ((lastModified % 1000) * 1_000_000).toInt()
        index.put(
            IndexEntry(
                path = path,
                oid = oid,
                mode = if (file.canExecute()) GitTreeEntry.MODE_EXECUTABLE else GitTreeEntry.MODE_FILE,
                stage = 0,
                size = bytes.size.toLong(),
                mtimeSeconds = seconds,
                mtimeNanos = nanos,
                ctimeSeconds = seconds,
                ctimeNanos = nanos,
            )
        )
        index.writeTo(indexFile)
    }

    fun stageAll() {
        val changes = status()
        val index = readIndex()
        changes.filter { !it.staged && it.type != ChangeType.DELETED }.forEach { change ->
            val file = File(workTree, change.path)
            if (file.isFile) {
                val bytes = file.readBytes()
                val oid = objects.write(GitBlob(bytes))
                val lastModified = file.lastModified()
                index.put(
                    IndexEntry(
                        path = change.path,
                        oid = oid,
                        mode = if (file.canExecute()) GitTreeEntry.MODE_EXECUTABLE else GitTreeEntry.MODE_FILE,
                        size = bytes.size.toLong(),
                        mtimeSeconds = lastModified / 1000,
                        mtimeNanos = ((lastModified % 1000) * 1_000_000).toInt(),
                        ctimeSeconds = lastModified / 1000,
                        ctimeNanos = ((lastModified % 1000) * 1_000_000).toInt(),
                    )
                )
            }
        }
        changes.filter { it.type == ChangeType.DELETED && !it.staged }.forEach { index.remove(it.path) }
        index.writeTo(indexFile)
    }

    fun commit(message: String, ident: GitIdent): ObjectId {
        val index = readIndex()
        val effective = index.entries.filter { !it.isConflict }
        if (effective.isEmpty()) throw GitException.NothingToCommit()

        val treeId = buildTreeLevel(effective.map { it.path to it })
        val head = refs.head()
        val commit = GitCommit(
            tree = treeId,
            parents = listOfNotNull(head.headOid),
            author = ident,
            committer = ident,
            message = message,
        )
        val id = objects.write(commit)

        when (head) {
            is Head.Symbolic -> refs.update(head.target, id)
            is Head.Detached -> File(gitDir, "HEAD").writeText("${id.hex}\n")
        }
        return id
    }

    private fun buildTreeLevel(entries: List<Pair<String, IndexEntry>>): ObjectId {
        val files = entries.filter { !it.first.contains('/') }
        val dirs = entries.filter { it.first.contains('/') }
            .groupBy { it.first.substringBefore('/') }

        val treeEntries = mutableListOf<GitTreeEntry>()
        files.forEach { (name, entry) ->
            treeEntries.add(GitTreeEntry(entry.mode, name, entry.oid))
        }
        dirs.forEach { (dirName, children) ->
            val childId = buildTreeLevel(children.map { it.first.substringAfter('/') to it.second })
            treeEntries.add(GitTreeEntry(GitTreeEntry.MODE_TREE, dirName, childId))
        }
        treeEntries.sortWith(TREE_ORDER)
        return objects.write(GitTree(treeEntries))
    }

    // ---------------------------------------------------------------- 内部

    private fun hashFile(file: File): ObjectId {
        val bytes = file.readBytes()
        val header = "blob ${bytes.size}\u0000".toByteArray(StandardCharsets.US_ASCII)
        return Sha1.digest(header, bytes)
    }

    private fun isIgnoredDir(dir: File): Boolean {
        val relative = dir.relativeTo(workTree).invariantSeparatorsPath
        return ignoreRules.isIgnored(relative, true)
    }

    override fun close() {
        objects.close()
    }

    companion object {
        private fun isBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, 8000)
            for (i in 0 until limit) if (bytes[i] == 0.toByte()) return true
            return false
        }

        private val TREE_ORDER = Comparator<GitTreeEntry> { a, b ->
            val an = a.name + if (a.isDirectory) "/" else ""
            val bn = b.name + if (b.isDirectory) "/" else ""
            compareUnsigned(an.toByteArray(StandardCharsets.UTF_8), bn.toByteArray(StandardCharsets.UTF_8))
        }

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return x - y
            }
            return a.size - b.size
        }

        fun open(directory: File): GitRepo? {
            val gitDir = File(directory, ".git")
            if (!gitDir.isDirectory) return null
            return GitRepo(directory, gitDir)
        }

        fun init(directory: File): GitRepo {
            val gitDir = File(directory, ".git")
            if (gitDir.isDirectory) return GitRepo(directory, gitDir)

            File(gitDir, "objects").mkdirs()
            File(gitDir, "refs/heads").mkdirs()
            File(gitDir, "refs/tags").mkdirs()
            File(gitDir, "HEAD").writeText("ref: refs/heads/main\n", StandardCharsets.US_ASCII)
            File(gitDir, "config").writeText(
                """
                [core]
                	repositoryformatversion = 0
                	filemode = true
                	bare = false
                	logallrefupdates = true
                """.trimIndent() + "\n",
                StandardCharsets.US_ASCII,
            )
            return GitRepo(directory, gitDir)
        }
    }
}
