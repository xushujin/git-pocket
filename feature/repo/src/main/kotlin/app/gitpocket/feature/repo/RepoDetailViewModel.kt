package app.gitpocket.feature.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.model.CommitSummary
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.Repo
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RepoDetailUiState(
    val loading: Boolean = true,
    val repo: Repo? = null,
    val branch: String? = null,
    val changes: List<FileChange> = emptyList(),
    val commits: List<CommitSummary> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

class RepoDetailViewModel(
    private val repoId: RepoId,
    private val repository: RepoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    init {
        refresh()
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
