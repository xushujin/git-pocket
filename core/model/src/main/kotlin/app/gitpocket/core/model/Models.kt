package app.gitpocket.core.model

/**
 * Git 对象 ID（SHA-1，40 位小写十六进制）。
 * 用 value class 包裹，避免大列表里成千上万次 String 装箱。
 */
@JvmInline
value class ObjectId(val hex: String) {
    init {
        require(hex.length == 40 && hex.all { it in HEX_CHARS }) { "非法 ObjectId: $hex" }
    }

    val short: String get() = hex.substring(0, 7)

    override fun toString(): String = hex

    private companion object {
        const val HEX_CHARS = "0123456789abcdef"
    }
}

@JvmInline
value class RepoId(val value: String)

/** 仓库元数据（对应 Room 的 repo 表）。 */
data class Repo(
    val id: RepoId,
    val owner: String,
    val name: String,
    val remoteUrl: String?,
    val localPath: String,
    val defaultBranch: String?,
    val lastOpenedAt: Long,
    val pinned: Boolean = false,
    val sizeBytes: Long = 0,
)

enum class ChangeType { ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED, CONFLICT }

/** 一条工作区/暂存区变更。 */
data class FileChange(
    val path: String,
    val type: ChangeType,
    val staged: Boolean,
    val additions: Int = 0,
    val deletions: Int = 0,
)

data class Branch(
    val name: String,
    val oid: ObjectId,
    val isRemote: Boolean,
    val isCurrent: Boolean,
)

data class CommitSummary(
    val oid: ObjectId,
    val parents: List<ObjectId>,
    val authorName: String,
    val authorEmail: String,
    val timestampSeconds: Long,
    val subject: String,
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtSeconds: Long,
)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffLineKind,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String,
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
) {
    val header: String get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
}

data class FileDiff(
    val path: String,
    val isBinary: Boolean,
    val hunks: List<DiffHunk>,
    val additions: Int,
    val deletions: Int,
    val truncated: Boolean = false,
) {
    val isIdentical: Boolean get() = !isBinary && hunks.isEmpty() && additions == 0 && deletions == 0
}

/** 仓库当前状态（用于 UI 顶部提示）。 */
enum class RepoState { CLEAN, DIRTY, DETACHED }

// ---------------------------------------------------------------------------
// 设置与主题（放在 model 层，避免 designsystem 反向依赖设置模块）
// ---------------------------------------------------------------------------

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val lightPaletteId: String = "mist",
    val darkPaletteId: String = "graphite",
    val editorFontSizeSp: Int = 14,
)
