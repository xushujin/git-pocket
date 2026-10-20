package app.gitpocket.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.model.FileDiff
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiffUiState(
    val loading: Boolean = true,
    val diff: FileDiff? = null,
    val error: String? = null,
)

class DiffViewModel(
    private val repoId: RepoId,
    private val path: String,
    private val repository: RepoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiffUiState())
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = DiffUiState(loading = true)
            runCatching { repository.diff(repoId, path) }
                .onSuccess { _state.value = DiffUiState(loading = false, diff = it) }
                .onFailure {
                    _state.value = DiffUiState(loading = false, error = it.message ?: "计算 diff 失败")
                }
        }
    }
}
