package app.gitpocket.feature.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.git.ConflictStrategy
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.MergeStatus
import app.gitpocket.core.git.transport.SyncProgress
import app.gitpocket.core.model.Branch
import app.gitpocket.core.model.CommitSummary
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.Repo
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SyncAction(val label: String) { PULL("拉取"), PUSH("推送") }

/** 同步弹窗状态：进度、结果、错误分开存，UI 不用自己拼。 */
data class SyncState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val action: SyncAction? = null,
    val progress: String = "",
    val result: String? = null,
    val error: String? = null,
)

/** 分支弹窗状态。 */
data class BranchState(
    val visible: Boolean = false,
    val local: List<Branch> = emptyList(),
    val remote: List<Branch> = emptyList(),
    val newName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class RepoDetailUiState(
    val loading: Boolean = true,
    val repo: Repo? = null,
    val branch: String? = null,
    val changes: List<FileChange> = emptyList(),
    val commits: List<CommitSummary> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val merging: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val sync: SyncState = SyncState(),
    val branchState: BranchState = BranchState(),
)

class RepoDetailViewModel(
    private val repoId: RepoId,
    private val repository: RepoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    init {
        refresh()
        autoFetch()
    }

    /**
     * 打开仓库时静默 fetch：只在后台更新远端跟踪引用，不弹窗、失败也不打扰。
     * 用户想看结果可以手动点「同步」。
     */
    private fun autoFetch() {
        viewModelScope.launch {
            if (!repository.autoFetchEnabled()) return@launch
            runCatching { repository.fetch(repoId) }
                .onSuccess { if (it.newObjects > 0) refresh(silent = true) }
        }
    }

    fun openSync() {
        _state.value = _state.value.copy(sync = SyncState(visible = true))
    }

    fun closeSync() {
        _state.value = _state.value.copy(sync = _state.value.sync.copy(visible = false))
    }

    // ---------------------------------------------------------------- 分支

    fun openBranches() {
        _state.value = _state.value.copy(branchState = BranchState(visible = true))
        viewModelScope.launch {
            runCatching {
                repository.branches(repoId) to repository.remoteBranches(repoId)
            }.onSuccess { (local, remote) ->
                _state.value = _state.value.copy(
                    branchState = _state.value.branchState.copy(local = local, remote = remote),
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    branchState = _state.value.branchState.copy(error = it.friendlyMessage()),
                )
            }
        }
    }

    fun closeBranches() {
        _state.value = _state.value.copy(branchState = BranchState())
    }

    fun onNewBranchNameChange(value: String) {
        _state.value = _state.value.copy(
            branchState = _state.value.branchState.copy(newName = value, error = null),
        )
    }

    fun createBranch() {
        val name = _state.value.branchState.newName.trim()
        if (name.isEmpty()) {
            _state.value = _state.value.copy(
                branchState = _state.value.branchState.copy(error = "请输入分支名"),
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                branchState = _state.value.branchState.copy(busy = true, error = null),
            )
            runCatching { repository.createBranch(repoId, name, checkout = true) }
                .onSuccess {
                    _state.value = _state.value.copy(branchState = BranchState())
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        branchState = _state.value.branchState.copy(
                            busy = false,
                            error = it.friendlyMessage(),
                        ),
                    )
                }
        }
    }

    fun checkoutBranch(name: String) {
        if (name == _state.value.branch) {
            closeBranches()
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                branchState = _state.value.branchState.copy(busy = true, error = null),
            )
            runCatching { repository.checkoutBranch(repoId, name) }
                .onSuccess {
                    _state.value = _state.value.copy(branchState = BranchState())
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        branchState = _state.value.branchState.copy(
                            busy = false,
                            error = it.friendlyMessage(),
                        ),
                    )
                }
        }
    }

    /** 从远端分支建一个同名本地分支并切过去。 */
    fun checkoutRemote(remoteName: String) {
        val local = remoteName.substringAfterLast('/')
        viewModelScope.launch {
            _state.value = _state.value.copy(
                branchState = _state.value.branchState.copy(busy = true, error = null),
            )
            runCatching {
                repository.createBranch(repoId, local, checkout = true)
            }.onSuccess {
                _state.value = _state.value.copy(branchState = BranchState())
                refresh()
            }.onFailure {
                _state.value = _state.value.copy(
                    branchState = _state.value.branchState.copy(
                        busy = false,
                        error = it.friendlyMessage(),
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 同步

    /** 把某个本地分支合并进当前分支（不切换分支）。 */
    fun mergeBranch(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                branchState = _state.value.branchState.copy(busy = true, error = null),
            )
            runCatching { repository.mergeBranch(repoId, name) }
                .onSuccess { outcome ->
                    val text = when (outcome.status) {
                        MergeStatus.UP_TO_DATE -> "已经包含 $name，无需合并"
                        MergeStatus.FAST_FORWARD -> "已快进到 $name"
                        MergeStatus.MERGED -> "已合并 $name，产生一个合并提交"
                        MergeStatus.CONFLICT ->
                            "合并 $name 时有 ${outcome.conflicts.size} 个冲突，去「变更」里解决"
                    }
                    _state.value = _state.value.copy(branchState = BranchState(), message = text)
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        branchState = _state.value.branchState.copy(
                            busy = false,
                            error = it.friendlyMessage(),
                        ),
                    )
                }
        }
    }

    /** 拉取 / 推送共用一条状态机：running 时按钮禁用，结束后刷新页面数据。 */
    fun runSync(action: SyncAction) {
        if (_state.value.sync.running) return
        _state.value = _state.value.copy(
            sync = SyncState(visible = true, running = true, action = action, progress = "连接中…"),
        )
        viewModelScope.launch {
            runCatching {
                when (action) {
                    SyncAction.PULL -> {
                        val r = repository.pull(repoId) { publishProgress(it) }
                        when {
                            r.conflicts.isNotEmpty() ->
                                "有 ${r.conflicts.size} 个文件冲突，去「变更」里逐个解决"
                            r.upToDate -> "已是最新，没有需要拉取的内容"
                            r.fastForwarded -> "已更新到 ${r.branch}（新增 ${r.newObjects} 个对象）"
                            r.merged -> "已合并 origin/${r.branch}（产生一个合并提交）"
                            else -> "拉取完成"
                        }
                    }

                    SyncAction.PUSH -> {
                        val r = repository.push(repoId) { publishProgress(it) }
                        if (r.upToDate) {
                            "远端已包含本地全部提交，无需推送"
                        } else {
                            "已推送 ${r.ref.removePrefix("refs/heads/")} 到远端"
                        }
                    }
                }
            }.onSuccess { message ->
                _state.value = _state.value.copy(
                    sync = _state.value.sync.copy(running = false, progress = "", result = message),
                )
                refresh(silent = true)
            }.onFailure { throwable ->
                _state.value = _state.value.copy(
                    sync = _state.value.sync.copy(running = false, progress = "", error = throwable.friendlyMessage()),
                )
            }
        }
    }

    private fun publishProgress(progress: SyncProgress) {
        val text = when (progress) {
            SyncProgress.Connecting -> "连接中…"
            is SyncProgress.Receiving -> "接收中 ${progress.bytes / 1024} KB"
            is SyncProgress.Unpacking -> "解包 ${progress.objects} 个对象"
            SyncProgress.Done -> "完成"
        }
        _state.value = _state.value.copy(sync = _state.value.sync.copy(progress = text))
    }

    // ---------------------------------------------------------------- 冲突

    /** 冲突整份选一边。 */
    fun resolve(path: String, strategy: ConflictStrategy) {
        viewModelScope.launch {
            runCatching { repository.resolveConflict(repoId, path, strategy) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = if (repository.conflicts(repoId).isEmpty()) {
                            "冲突都解决了，可以提交这次合并"
                        } else {
                            "已解决 $path"
                        },
                    )
                    refresh(silent = true)
                }
                .onFailure { _state.value = _state.value.copy(message = it.friendlyMessage()) }
        }
    }

    /** 手动改完后标记为已解决。 */
    fun markResolved(path: String) {
        viewModelScope.launch {
            runCatching { repository.stageFile(repoId, path) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = if (repository.conflicts(repoId).isEmpty()) {
                            "冲突都解决了，可以提交这次合并"
                        } else {
                            "已解决 $path"
                        },
                    )
                    refresh(silent = true)
                }
                .onFailure { _state.value = _state.value.copy(message = it.friendlyMessage()) }
        }
    }

    fun abortMerge() {
        viewModelScope.launch {
            runCatching { repository.abortMerge(repoId) }
                .onSuccess {
                    _state.value = _state.value.copy(message = "已放弃本次合并")
                    refresh()
                }
                .onFailure { _state.value = _state.value.copy(message = it.friendlyMessage()) }
        }
    }

    /** 撤销最近一次提交（soft reset）：改动保留在暂存区，可以改完再提交。 */
    fun undoLastCommit() {
        viewModelScope.launch {
            runCatching { repository.undoLastCommit(repoId) }
                .onSuccess { parent ->
                    _state.value = _state.value.copy(
                        message = if (parent == null) "这是第一个提交，无法撤销" else "已撤销上次提交，改动回到暂存区",
                    )
                    refresh(silent = true)
                }
                .onFailure { _state.value = _state.value.copy(message = it.friendlyMessage()) }
        }
    }

    /** silent=true 时保留现有内容，只做后台刷新（用于页面 resume）。 */
    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val repo = repository.findRepo(repoId)
                val branch = repository.branches(repoId).firstOrNull { it.isCurrent }?.name
                val changes = repository.status(repoId)
                val commits = repository.log(repoId, 50)
                val entries = repository.entries(repoId, "")
                val conflicts = repository.conflicts(repoId)
                val merging = repository.isMerging(repoId)
                RepoDetailUiState(
                    loading = false,
                    repo = repo,
                    branch = branch,
                    changes = changes,
                    commits = commits,
                    entries = entries,
                    conflicts = conflicts,
                    merging = merging,
                    // 提示条保留到用户点掉为止（否则"已放弃合并"这类反馈会被这次刷新冲掉）
                    message = _state.value.message,
                    sync = _state.value.sync,
                    branchState = _state.value.branchState,
                )
            }.onSuccess { _state.value = it }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: "读取仓库失败")
                }
        }
    }

    /** 撤销某个文件的修改；未跟踪文件会被删除。 */
    fun discard(path: String) {
        viewModelScope.launch {
            runCatching { repository.discard(repoId, path) }
                .onSuccess { refresh(silent = true) }
                .onFailure {
                    _state.value = _state.value.copy(message = it.message ?: "撤销失败：$path")
                }
        }
    }

    fun stageAll() {
        viewModelScope.launch {
            runCatching { repository.stageAll(repoId) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "暂存失败") }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/** 把引擎异常翻译成用户能懂的一句话。 */
internal fun Throwable.friendlyMessage(): String = when (this) {
    is GitException.NonFastForward -> "远端有本地没有的提交，请先拉取再推送"
    is GitException.PushRejected -> message ?: "推送被拒绝"
    is GitException.AuthFailed -> "认证失败：token 无效或没有写权限"
    is GitException.RemoteNotFound -> "仓库不存在或无权访问"
    is GitException.Network -> message ?: "网络错误"
    is GitException.DirtyWorkTree -> message ?: "工作区还有未提交的改动"
    is GitException.MergeInProgress -> message ?: "还有冲突没解决"
    is GitException.BranchExists -> message ?: "分支已存在"
    is GitException.InvalidBranchName -> message ?: "分支名不合法"
    is GitException.UntrackedOverwrite -> message ?: "有未跟踪文件会被覆盖"
    else -> message ?: "操作失败"
}
