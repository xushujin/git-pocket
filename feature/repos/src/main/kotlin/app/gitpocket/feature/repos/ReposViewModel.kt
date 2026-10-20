package app.gitpocket.feature.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.model.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReposUiState(
    val loading: Boolean = true,
    val repos: List<Repo> = emptyList(),
    val error: String? = null,
)

class ReposViewModel(private val repository: RepoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReposUiState())
    val state: StateFlow<ReposUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.ensureSeedRepo() }
                .onFailure { _state.value = ReposUiState(loading = false, error = it.userMessage()) }
        }
        viewModelScope.launch {
            repository.observeRepos().collect { repos ->
                _state.value = ReposUiState(loading = false, repos = repos)
            }
        }
    }

    fun open(repo: Repo) {
        viewModelScope.launch { repository.touch(repo.id) }
    }
}

internal fun Throwable.userMessage(): String = when (this) {
    is app.gitpocket.core.git.GitException -> message ?: "Git 操作失败"
    else -> message ?: "未知错误"
}
