package app.gitpocket.feature.commit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommitUiState(
    val loading: Boolean = true,
    val changes: List<FileChange> = emptyList(),
    val message: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val committing: Boolean = false,
    val committedOid: String? = null,
    val error: String? = null,
)

class CommitViewModel(
    private val repoId: RepoId,
    private val repository: RepoRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CommitUiState())
    val state: StateFlow<CommitUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val (name, email) = settings.authorIdentity()
            runCatching { repository.status(repoId) }
                .onSuccess { changes ->
                    _state.value = _state.value.copy(
                        loading = false,
                        changes = changes,
                        authorName = name,
                        authorEmail = email,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: "读取变更失败")
                }
        }
    }

    fun onMessageChange(value: String) {
        _state.value = _state.value.copy(message = value, error = null)
    }

    fun commit() {
        val current = _state.value
        if (current.message.isBlank()) {
            _state.value = current.copy(error = "请填写提交信息")
            return
        }
        viewModelScope.launch {
            _state.value = current.copy(committing = true, error = null)
            runCatching {
                repository.stageAll(repoId)
                repository.commit(repoId, current.message.trim())
            }.onSuccess { oid ->
                _state.value = _state.value.copy(
                    committing = false,
                    committedOid = oid.take(7),
                    changes = emptyList(),
                    message = "",
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    committing = false,
                    error = it.message ?: "提交失败",
                )
            }
        }
    }
}
