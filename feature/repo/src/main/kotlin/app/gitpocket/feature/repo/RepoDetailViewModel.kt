package app.gitpocket.feature.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.transport.SyncProgress
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

data class RepoDetailUiState(
    val loading: Boolean = true,
    val repo: Repo? = null,
    val branch: String? = null,
    val changes: List<FileChange> = emptyList(),
    val commits: List<CommitSummary> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val sync: SyncState = SyncState(),
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
                            r.upToDate -> "已是最新，没有需要拉取的内容"
                            r.fastForwarded -> "已更新到 ${r.branch}（新增 ${r.newObjects} 个对象）"
                            else -> "本地与远端已分叉，无法快进；需要先处理分叉再拉取"
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
                RepoDetailUiState(
                    loading = false,
                    repo = repo,
                    branch = branch,
                    changes = changes,
                    commits = commits,
                    entries = entries,
                    sync = _state.value.sync,
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
    else -> message ?: "操作失败"
}
