package app.gitpocket.feature.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.git.transport.CloneProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloneUiState(
    val url: String = "",
    val username: String = "",
    val token: String = "",
    val running: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val clonedRepoId: String? = null,
)

class CloneViewModel(private val repository: RepoRepository) : ViewModel() {

    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(url = value, error = null)
    }

    fun onUsernameChange(value: String) {
        _state.value = _state.value.copy(username = value, error = null)
    }

    fun onTokenChange(value: String) {
        _state.value = _state.value.copy(token = value, error = null)
    }

    fun start() {
        val current = _state.value
        if (current.running) return
        if (current.url.isBlank()) {
            _state.value = current.copy(error = "请填写仓库地址")
            return
        }

        viewModelScope.launch {
            _state.value = current.copy(running = true, error = null, progress = "准备中…")
            var lastText = ""
            runCatching {
                repository.clone(
                    url = current.url,
                    username = current.username,
                    token = current.token,
                ) { progress ->
                    val text = progress.describe()
                    if (text != lastText) {
                        lastText = text
                        _state.value = _state.value.copy(progress = text)
                    }
                }
            }.onSuccess { repo ->
                _state.value = _state.value.copy(
                    running = false,
                    progress = "完成",
                    clonedRepoId = repo.id.value,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    running = false,
                    progress = "",
                    error = error.userMessage(),
                )
            }
        }
    }
}

private fun CloneProgress.describe(): String = when (this) {
    CloneProgress.Connecting -> "连接远端…"
    is CloneProgress.Receiving -> "接收对象 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    is CloneProgress.Unpacking -> if (objects == 0) "解包中…" else "已解包 $objects 个对象"
    is CloneProgress.CheckingOut -> if (files == 0) "检出文件…" else "已检出 $files 个文件"
}
