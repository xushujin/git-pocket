package app.gitpocket.feature.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.model.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
        // 新版本不再预置示例仓库；这里只清理旧版本留下的 local-demo
        viewModelScope.launch { runCatching { repository.removeLegacyDemo() } }
        viewModelScope.launch {
            repository.observeRepos()
                // 数据库打不开（磁盘满 / 迁移失败）时不能让页面永远停在"正在准备"，
                // 否则 error 分支是死代码，用户只看到一个转圈
                .catch { error ->
                    _state.value = _state.value.copy(loading = false, error = error.userMessage())
                }
                .collect { repos ->
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
    is android.database.sqlite.SQLiteException ->
        "本地数据库初始化失败，请重启应用；若仍失败请把下面这行反馈给开发者：\n${message?.take(120)}"
    else -> message ?: "未知错误"
}
