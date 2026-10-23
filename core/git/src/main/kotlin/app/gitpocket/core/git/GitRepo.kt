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

    /** 远端跟踪分支（refs/remotes 下的引用），只用于展示，不直接检出。 */
    fun remoteBranches(): List<Branch> =
        refs.listRefs("refs/remotes/").map { (name, oid) ->
            Branch(
                name = name.removePrefix("refs/remotes/"),
                oid = oid,
                isRemote = true,
                isCurrent = false,
            )
        }.sortedBy { it.name }

    fun currentBranch(): String? = refs.currentBranch()

    /**
     * 切换分支：要求工作区干净（未跟踪文件不拦），否则 checkout 会静默覆盖用户改动。
     * 目标树里没有、但 index 里有的文件会被删掉，避免"幽灵文件"。
     */
    fun checkoutBranch(name: String) {
        if (isMerging) throw GitException.MergeInProgress()
        val refName = "refs/heads/$name"
        val target = refs.read(refName) ?: throw GitException.RefNotFound(refName)
        if (refs.currentBranch() == name) return

        requireCleanWorkTree()

        val commit = objects.read(target) as? GitCommit ?: throw GitException.RefNotFound(refName)
        checkoutTreeClean(commit.tree)
        refs.updateHead(refName)
    }

    /**
     * 在当前 HEAD 上新建分支；[checkout] 为 true 时建完直接切过去。
     *
     * [startPoint] 为空表示以当前 HEAD 为起点；从远端分支建本地分支时必须显式传
     * 远端引用的 oid —— 否则建出来的分支指向的是"我现在所在的位置"，而不是远端那个提交。
     */
    fun createBranch(name: String, checkout: Boolean, startPoint: ObjectId? = null) {
        validateBranchName(name)
        val refName = "refs/heads/$name"
        if (refs.read(refName) != null) throw GitException.BranchExists(name)
        // 要切过去就先确认能切：否则会出现"分支建好了但没切过去"的半成功状态
        if (checkout) requireCleanWorkTree()
        val head = startPoint ?: refs.head().headOid ?: throw GitException.RefNotFound("HEAD")
        refs.update(refName, head)
        if (checkout) checkoutBranch(name)
    }

    /** 工作区有未提交改动时抛 [GitException.DirtyWorkTree]（未跟踪文件不算）。 */
    private fun requireCleanWorkTree() {
        val dirty = status().filter { it.type != ChangeType.UNTRACKED }
        if (dirty.isNotEmpty()) throw GitException.DirtyWorkTree(dirty.size)
    }

    private fun validateBranchName(name: String) {
        val invalid = name.isBlank() ||
            name.startsWith("-") ||
            name.endsWith("/") ||
            name.endsWith(".") ||
            ".." in name ||
            name.any { it.isWhitespace() || it in "~^:?*[\\" } ||
            name.split('/').any { it.isBlank() || it.startsWith(".") }
        if (invalid) throw GitException.InvalidBranchName(name)
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
        val rawIndex = readIndex()
        val index = rawIndex.toMap()
        // 冲突路径在 index 里有 stage 1/2/3，没有 stage 0，必须单独归类，
        // 否则会被误报成"已暂存删除 + 未跟踪"。
        val conflicted = rawIndex.entries.filter { it.stage != 0 }.map { it.path }.toSet()
        val result = mutableListOf<FileChange>()

        // 已暂存：index vs HEAD
        index.forEach { (path, entry) ->
            if (path in conflicted) return@forEach
            val head = headFiles[path]
            when {
                head == null -> result.add(FileChange(path, ChangeType.ADDED, staged = true))
                head.oid != entry.oid -> result.add(FileChange(path, ChangeType.MODIFIED, staged = true))
            }
        }
        headFiles.keys.filterNot { it in index || it in conflicted }.forEach {
            result.add(FileChange(it, ChangeType.DELETED, staged = true))
        }

        // 未暂存：工作区 vs index
        index.forEach { (path, entry) ->
            if (path in conflicted) return@forEach
            val file = File(workTree, path)

            // 符号链接：git 存的是链接目标字符串，必须按链接本身比较，
            // 否则 File.length() 会跟随链接取到目标文件大小，误报 modified。
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
            if (isLink) {
                val target = runCatching {
                    java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
                }.getOrNull()
                val bytes = target?.toByteArray(StandardCharsets.UTF_8)
                val changed = bytes == null ||
                    bytes.size.toLong() != entry.size ||
                    hashBytes(bytes) != entry.oid
                if (changed) result.add(FileChange(path, ChangeType.MODIFIED, staged = false))
                return@forEach
            }

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

        // 冲突：单独一条，UI 用红色「!」并给出解决入口
        conflicted.sorted().forEach {
            result.add(FileChange(it, ChangeType.CONFLICT, staged = false))
        }

        // 未跟踪
        val tracked = index.keys + conflicted
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

    /** 工作区文件大小（字节）；不存在返回 0。用于编辑器判断是否超限。 */
    fun workFileSize(path: String): Long {
        val file = File(workTree, path)
        return if (file.isFile) file.length() else 0
    }

    fun readWorkFile(path: String): String? {        val file = File(workTree, path)
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

    /** 读 .git/config 里的 remote url；没有配置时返回 null。 */
    fun remoteUrl(remote: String = "origin"): String? {
        val config = File(gitDir, "config")
        if (!config.isFile) return null
        var inRemoteSection = false
        var result: String? = null
        for (raw in config.readLines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inRemoteSection = line.startsWith("[remote \"$remote\"]")
            } else if (inRemoteSection && line.startsWith("url") && result == null) {
                result = line.substringAfter('=').trim().ifBlank { null }
            }
        }
        return result
    }

    /**
     * 快进合并：仅当当前分支是 [target] 的祖先时才移动。
     *
     * 真正的三方合并见 [merge]；这里只负责"纯快进"这条最省事的路径。
     */
    fun fastForward(branch: String, target: ObjectId): Boolean {
        val refName = "refs/heads/$branch"
        val current = refs.read(refName) ?: return false
        if (current == target) return false
        if (!ObjectWalk.isAncestor(objects, current, target)) return false

        val commit = objects.read(target) as? GitCommit ?: return false
        checkoutTreeClean(commit.tree)
        refs.update(refName, target)
        return true
    }

    // ---------------------------------------------------------------- 分支合并

    /** 是否处于"合并未完成"状态（存在 .git/MERGE_HEAD）。 */
    val isMerging: Boolean get() = File(gitDir, "MERGE_HEAD").isFile

    /** 待解决的冲突文件（index 里 stage != 0 的路径）。 */
    fun conflicts(): List<String> =
        readIndex().entries.filter { it.stage != 0 }.map { it.path }.distinct().sorted()

    /**
     * 把 [target] 合并进当前分支。
     *
     * 三种结果：
     * - 已经包含对方 → [MergeStatus.UP_TO_DATE]
     * - 可以快进 → 直接移动分支（不产生 merge commit）
     * - 有分叉 → 三方合并；能自动合就产生 merge commit，否则写冲突标记 + index stage 1/2/3，
     *   并留下 MERGE_HEAD，由 UI 引导逐个解决。
     *
     * 前置条件：工作区必须干净（未跟踪文件除外），否则抛 [GitException.DirtyWorkTree]。
     */
    fun merge(target: ObjectId, label: String, ident: GitIdent): MergeOutcome {
        if (isMerging) throw GitException.MergeInProgress()
        val head = refs.head()
        val headOid = head.headOid
        val targetCommit = objects.read(target) as? GitCommit
            ?: throw GitException.RefNotFound(label)

        // 不需要动工作区的情况先返回，避免为了"已经是最新"还去扫一遍工作区
        if (headOid != null) {
            if (headOid == target) return MergeOutcome(MergeStatus.UP_TO_DATE, emptyList(), headOid)
            if (ObjectWalk.isAncestor(objects, target, headOid)) {
                return MergeOutcome(MergeStatus.UP_TO_DATE, emptyList(), headOid)
            }
        }

        // 从这里开始一定会改写工作区（快进或三方合并），必须先确认工作区干净。
        // 顺序很重要：以前把这段放在快进判断之后，导致"能快进"时直接 checkoutTree
        // 把未提交的改动覆盖掉，且不报任何错。
        requireCleanWorkTree()

        if (headOid == null) {
            checkoutTreeClean(targetCommit.tree)
            moveHead(head, target)
            return MergeOutcome(MergeStatus.FAST_FORWARD, emptyList(), target)
        }
        if (ObjectWalk.isAncestor(objects, headOid, target)) {
            checkoutTreeClean(targetCommit.tree)
            moveHead(head, target)
            return MergeOutcome(MergeStatus.FAST_FORWARD, emptyList(), target)
        }

        val baseOid = ObjectWalk.mergeBase(objects, headOid, target)
            ?: throw GitException.UnsupportedFeature("两个分支没有共同祖先（unrelated histories）")
        val baseFiles = flattenTree((objects.read(baseOid) as GitCommit).tree)
        val oursFiles = flattenTree((objects.read(headOid) as GitCommit).tree)
        val theirsFiles = flattenTree(targetCommit.tree)

        val merged = LinkedHashMap<String, MergedFile>()
        val conflicts = LinkedHashMap<String, ConflictFile>()

        (baseFiles.keys + oursFiles.keys + theirsFiles.keys).sorted().forEach { path ->
            val base = baseFiles[path]
            val ours = oursFiles[path]
            val theirs = theirsFiles[path]

            when {
                ours == theirs -> ours?.let { merged[path] = MergedFile(it.mode, it.oid) }
                base == ours -> theirs?.let { merged[path] = MergedFile(it.mode, it.oid) }
                base == theirs -> ours?.let { merged[path] = MergedFile(it.mode, it.oid) }
                else -> {
                    when (val result = mergeFile(base, ours, theirs, label)) {
                        is FileMergeResult.Clean -> merged[path] = result.file
                        is FileMergeResult.Conflicted ->
                            conflicts[path] = ConflictFile(base, ours, theirs, result.text)
                    }
                }
            }
        }

        // 1) 删除工作区里"三方都没有"的旧文件
        val keep = merged.keys + conflicts.keys

        // 与 git 一致：合并结果要写的文件如果本地有同名未跟踪文件，拒绝合并而不是覆盖
        val blockers = keep.filter { path -> path !in oursFiles && File(workTree, path).exists() }
        if (blockers.isNotEmpty()) throw GitException.UntrackedOverwrite(blockers.sorted())

        oursFiles.keys.forEach { path ->
            if (path !in keep) File(workTree, path).delete()
        }

        // 2) 写工作区 + 重建 index（自动合并的部分 stage 0，冲突的写 stage 1/2/3）
        val index = GitIndex.empty()
        merged.forEach { (path, file) ->
            val entry = writeWorkTreeEntry(path, file)
            index.put(entry)
        }
        conflicts.forEach { (path, conflict) ->
            writeConflictFile(path, conflict)
            listOfNotNull(
                conflict.base?.let { 1 to it },
                conflict.ours?.let { 2 to it },
                conflict.theirs?.let { 3 to it },
            ).forEach { (stage, treeEntry) ->
                index.put(
                    IndexEntry(
                        path = path,
                        oid = treeEntry.oid,
                        mode = treeEntry.mode,
                        stage = stage,
                    )
                )
            }
        }
        index.writeTo(indexFile)

        if (conflicts.isNotEmpty()) {
            File(gitDir, "MERGE_HEAD").writeText("${target.hex}\n", StandardCharsets.US_ASCII)
            File(gitDir, "MERGE_MSG").writeText(
                "合并 $label 到 ${refs.currentBranch() ?: "HEAD"}\n",
                StandardCharsets.UTF_8,
            )
            return MergeOutcome(MergeStatus.CONFLICT, conflicts.keys.sorted().toList(), null)
        }

        // 3) 无冲突：直接产生 merge commit
        val treeId = buildTreeLevel(index.entries.map { it.path to it })
        val commit = GitCommit(
            tree = treeId,
            parents = listOf(headOid, target),
            author = ident,
            committer = ident,
            message = "合并 $label 到 ${refs.currentBranch() ?: "HEAD"}",
        )
        val commitId = objects.write(commit)
        moveHead(head, commitId)
        File(gitDir, "MERGE_HEAD").delete()
        File(gitDir, "MERGE_MSG").delete()
        return MergeOutcome(MergeStatus.MERGED, emptyList(), commitId)
    }

    /** 冲突解决：整份选我的 / 选对方的。手动编辑走 [stage]。 */
    fun resolveConflict(path: String, strategy: ConflictStrategy) {
        val index = readIndex()
        val stages = index.entries.filter { it.path == path && it.stage != 0 }
        if (stages.isEmpty()) return

        val wanted = if (strategy == ConflictStrategy.OURS) 2 else 3
        val chosen = stages.firstOrNull { it.stage == wanted }
        val file = File(workTree, path)

        // 先清掉该路径的全部 stage，再写 stage 0（或整条删除）
        index.remove(path)
        if (chosen == null) {
            file.delete()
        } else {
            val blob = objects.read(chosen.oid) as? GitBlob
                ?: throw GitException.ObjectCorrupted(chosen.oid.hex, "冲突版本不是 blob")
            file.parentFile?.mkdirs()
            file.writeBytes(blob.content)
            if (chosen.mode == GitTreeEntry.MODE_EXECUTABLE) file.setExecutable(true, true)
            val last = file.lastModified()
            index.put(
                IndexEntry(
                    path = path,
                    oid = chosen.oid,
                    mode = chosen.mode,
                    stage = 0,
                    size = blob.content.size.toLong(),
                    mtimeSeconds = last / 1000,
                    mtimeNanos = ((last % 1000) * 1_000_000).toInt(),
                    ctimeSeconds = last / 1000,
                    ctimeNanos = ((last % 1000) * 1_000_000).toInt(),
                )
            )
        }
        index.writeTo(indexFile)
    }

    /** 放弃本次合并：工作区与 index 回到 HEAD。 */
    fun abortMerge() {
        if (!isMerging) return
        val head = headCommit() ?: throw GitException.RefNotFound("HEAD")
        checkoutTreeClean(head.tree)
        File(gitDir, "MERGE_HEAD").delete()
        File(gitDir, "MERGE_MSG").delete()
    }

    /** 撤销最近一次提交（soft reset）：HEAD 回退一个父提交，工作区与 index 不动。 */
    fun undoLastCommit(): ObjectId? {
        val head = refs.head()
        val oid = head.headOid ?: return null
        val commit = objects.read(oid) as? GitCommit ?: return null
        val parent = commit.parents.firstOrNull() ?: return null
        moveHead(head, parent)
        return parent
    }

    private fun moveHead(head: Head, target: ObjectId) {
        when (head) {
            is Head.Symbolic -> refs.update(head.target, target)
            is Head.Detached -> File(gitDir, "HEAD").writeText("${target.hex}\n", StandardCharsets.US_ASCII)
        }
    }

    /** 合并单个文件：能自动合就返回结果，否则返回带冲突标记的文本（二进制为 null）。 */
    private fun mergeFile(
        base: GitTreeEntry?,
        ours: GitTreeEntry?,
        theirs: GitTreeEntry?,
        label: String,
    ): FileMergeResult {
        // 删除 / 新增交叉（一方删了、另一方改了）无法自动合
        if (ours == null || theirs == null) return FileMergeResult.Conflicted(null)
        // 子模块 / 符号链接 / 二进制：一律交给人
        if (ours.mode == GitTreeEntry.MODE_SUBMODULE || theirs.mode == GitTreeEntry.MODE_SUBMODULE) {
            return FileMergeResult.Conflicted(null)
        }
        if (ours.mode == GitTreeEntry.MODE_SYMLINK || theirs.mode == GitTreeEntry.MODE_SYMLINK) {
            return FileMergeResult.Conflicted(null)
        }

        val oursBlob = objects.read(ours.oid) as? GitBlob ?: return FileMergeResult.Conflicted(null)
        val theirsBlob = objects.read(theirs.oid) as? GitBlob ?: return FileMergeResult.Conflicted(null)
        if (isBinary(oursBlob.content) || isBinary(theirsBlob.content)) {
            return FileMergeResult.Conflicted(null)
        }

        val baseText = base?.let { objects.read(it.oid) as? GitBlob }?.text() ?: ""
        val result = Diff3.merge(
            baseText = baseText,
            oursText = oursBlob.text(),
            theirsText = theirsBlob.text(),
            oursLabel = "HEAD",
            theirsLabel = label,
        )
        if (result.conflicts > 0) return FileMergeResult.Conflicted(result.text)

        val oid = objects.write(GitBlob(result.text.toByteArray(StandardCharsets.UTF_8)))
        return FileMergeResult.Clean(MergedFile(ours.mode, oid))
    }

    private fun writeWorkTreeEntry(path: String, file: MergedFile): IndexEntry {
        val blob = objects.read(file.oid) as? GitBlob
            ?: throw GitException.ObjectCorrupted(file.oid.hex, "合并结果不是 blob")
        val target = File(workTree, path)
        target.parentFile?.mkdirs()
        target.writeBytes(blob.content)
        if (file.mode == GitTreeEntry.MODE_EXECUTABLE) target.setExecutable(true, true)
        val last = target.lastModified()
        return IndexEntry(
            path = path,
            oid = file.oid,
            mode = file.mode,
            stage = 0,
            size = blob.content.size.toLong(),
            mtimeSeconds = last / 1000,
            mtimeNanos = ((last % 1000) * 1_000_000).toInt(),
            ctimeSeconds = last / 1000,
            ctimeNanos = ((last % 1000) * 1_000_000).toInt(),
        )
    }

    private fun writeConflictFile(path: String, conflict: ConflictFile) {
        val target = File(workTree, path)
        val text = conflict.mergedText
        if (text != null) {
            target.parentFile?.mkdirs()
            target.writeText(text, StandardCharsets.UTF_8)
            return
        }
        // 删除 / 修改冲突：保留"还在的那一方"，让用户能看到内容再决定
        val fallback = conflict.ours ?: conflict.theirs
        if (fallback == null) {
            target.delete()
            return
        }
        val blob = objects.read(fallback.oid) as? GitBlob ?: return
        target.parentFile?.mkdirs()
        target.writeBytes(blob.content)
    }

    /** 检出目标树，并删掉 index 里有、目标树里没有的文件。 */
    private fun checkoutTreeClean(treeId: ObjectId) {
        val targetFiles = flattenTree(treeId)
        readIndex().entries.filter { it.stage == 0 }.map { it.path }.forEach { path ->
            if (path !in targetFiles) File(workTree, path).delete()
        }
        checkoutTree(treeId)
    }

    /**
     * 撤销单个文件的工作区修改：
     * - HEAD 里有该文件 -> 用 HEAD 版本覆盖工作区，并同步 index（避免残留 modified）；
     * - HEAD 里没有（新增/未跟踪）-> 直接删除文件。
     * 返回 true 表示已撤销。
     */
    fun discardWorkFile(path: String): Boolean {
        val entry = headCommit()?.let { flattenTree(it.tree)[path] }
        val file = File(workTree, path)

        if (entry == null) {
            return file.delete()
        }

        val blob = objects.read(entry.oid) as? GitBlob ?: return false
        file.parentFile?.mkdirs()

        if (entry.mode == GitTreeEntry.MODE_SYMLINK) {
            file.delete()
            val target = String(blob.content, StandardCharsets.UTF_8)
            val created = runCatching {
                java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
            }.isSuccess
            if (!created) file.writeBytes(blob.content)
        } else {
            file.writeBytes(blob.content)
            if (entry.mode == GitTreeEntry.MODE_EXECUTABLE) file.setExecutable(true, true)
        }

        val last = file.lastModified()
        val seconds = last / 1000
        val nanos = ((last % 1000) * 1_000_000).toInt()
        val index = GitIndex.read(indexFile)
        // 撤销冲突文件 = 放弃解决，回到 HEAD 版本，同时清掉 stage 1/2/3
        index.remove(path)
        index.put(
            IndexEntry(
                path = path,
                oid = entry.oid,
                mode = entry.mode,
                stage = 0,
                size = blob.content.size.toLong(),
                mtimeSeconds = seconds,
                mtimeNanos = nanos,
                ctimeSeconds = seconds,
                ctimeNanos = nanos,
            )
        )
        index.writeTo(indexFile)
        return true
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

    /**
     * 把某个 tree 检出到工作区并重建 index（clone 的收尾动作）。
     * 返回写出的文件数。
     */
    fun checkoutTree(treeId: ObjectId, onProgress: (Int) -> Unit = {}): Int {
        val files = flattenTree(treeId)
        val index = GitIndex.empty()
        var written = 0

        files.forEach { (path, entry) ->
            // 子模块只登记 gitlink，不落文件
            if (entry.mode == GitTreeEntry.MODE_SUBMODULE) {
                index.put(IndexEntry(path = path, oid = entry.oid, mode = entry.mode, stage = 0))
                return@forEach
            }

            val blob = objects.read(entry.oid) as? GitBlob ?: return@forEach
            val file = File(workTree, path)
            file.parentFile?.mkdirs()

            var effectiveMode = entry.mode
            if (entry.mode == GitTreeEntry.MODE_SYMLINK) {
                val target = String(blob.content, StandardCharsets.UTF_8)
                val created = runCatching {
                    java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
                }.isSuccess
                if (!created) {
                    // 文件系统不支持符号链接时降级为普通文件，并把 index 里的 mode 改成普通文件，
                    // 否则 git 会一直报 typechange。
                    file.writeBytes(blob.content)
                    effectiveMode = GitTreeEntry.MODE_FILE
                }
            } else {
                file.writeBytes(blob.content)
                if (entry.mode == GitTreeEntry.MODE_EXECUTABLE) {
                    file.setExecutable(true, true)
                }
            }

            val lastModified = file.lastModified()
            val seconds = lastModified / 1000
            val nanos = ((lastModified % 1000) * 1_000_000).toInt()
            index.put(
                IndexEntry(
                    path = path,
                    oid = entry.oid,
                    mode = effectiveMode,
                    stage = 0,
                    size = blob.content.size.toLong(),
                    mtimeSeconds = seconds,
                    mtimeNanos = nanos,
                    ctimeSeconds = seconds,
                    ctimeNanos = nanos,
                )
            )
            written++
            if (written % 50 == 0) onProgress(written)
        }

        index.writeTo(indexFile)
        onProgress(written)
        return written
    }

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
        // 暂存即"这个冲突我解决完了"：先清掉 stage 1/2/3，再写 stage 0
        index.remove(path)
        val file = File(workTree, path)
        if (!file.isFile) {
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
        // 冲突文件必须显式解决（选一边或改完手动暂存），批量暂存会把冲突标记提交上去
        changes.filter { !it.staged && it.type != ChangeType.DELETED && it.type != ChangeType.CONFLICT }
            .forEach { change ->
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
        val pending = index.entries.filter { it.isConflict }
        if (pending.isNotEmpty()) {
            throw GitException.UnsupportedFeature("还有 ${pending.map { it.path }.distinct().size} 个冲突文件未解决")
        }
        val effective = index.entries.filter { !it.isConflict }
        if (effective.isEmpty()) throw GitException.NothingToCommit()

        val treeId = buildTreeLevel(effective.map { it.path to it })
        val head = refs.head()

        // 合并提交：MERGE_HEAD 存在时把对方也记为父提交
        val mergeHead = File(gitDir, "MERGE_HEAD")
            .takeIf { it.isFile }
            ?.readText(StandardCharsets.US_ASCII)
            ?.trim()
            ?.let { runCatching { ObjectId(it) }.getOrNull() }

        val parents = listOfNotNull(head.headOid, mergeHead).distinct()
        val commit = GitCommit(
            tree = treeId,
            parents = parents,
            author = ident,
            committer = ident,
            message = message,
        )
        val id = objects.write(commit)

        moveHead(head, id)
        if (mergeHead != null) {
            File(gitDir, "MERGE_HEAD").delete()
            File(gitDir, "MERGE_MSG").delete()
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

    private fun hashFile(file: File): ObjectId = hashBytes(file.readBytes())

    private fun hashBytes(bytes: ByteArray): ObjectId {
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

/** 合并结果类型。 */
enum class MergeStatus { UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICT }

/**
 * 一次合并的结果。
 * [conflicts] 只在 [MergeStatus.CONFLICT] 时非空；[commit] 在产生 merge commit 时有值。
 */
data class MergeOutcome(
    val status: MergeStatus,
    val conflicts: List<String>,
    val commit: ObjectId?,
)

/** 冲突解决策略：整份用我的 / 整份用对方的。手动编辑走 [GitRepo.stage]。 */
enum class ConflictStrategy { OURS, THEIRS }

/** 合并后的单个文件（只要 mode + oid，路径由 map 的 key 决定）。 */
internal data class MergedFile(val mode: Int, val oid: ObjectId)

/** 冲突文件的三方版本；[mergedText] 为带冲突标记的文本，二进制为 null。 */
internal data class ConflictFile(
    val base: GitTreeEntry?,
    val ours: GitTreeEntry?,
    val theirs: GitTreeEntry?,
    val mergedText: String?,
)

internal sealed interface FileMergeResult {
    data class Clean(val file: MergedFile) : FileMergeResult
    data class Conflicted(val text: String?) : FileMergeResult
}
