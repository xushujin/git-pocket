package app.gitpocket.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditorUiState(
    val loading: Boolean = true,
    val path: String = "",
    val content: String = "",
    val original: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val fontSizeSp: Int = 14,
    val showLineNumbers: Boolean = true,
    val error: String? = null,
    val message: String? = null,
) {
    val lineCount: Int get() = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
}

class EditorViewModel(
    private val repoId: RepoId,
    private val path: String,
    private val repository: RepoRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState(path = path))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var autoSaveJob: Job? = null
    private var autoSaveEnabled = false

    init {
        viewModelScope.launch {
            settings.autoSave.collect { autoSaveEnabled = it }
        }
        viewModelScope.launch {
            settings.showLineNumbers.collect { enabled ->
                _state.value = _state.value.copy(showLineNumbers = enabled)
            }
        }
        viewModelScope.launch {
            settings.theme.collect { theme ->
                _state.value = _state.value.copy(fontSizeSp = theme.editorFontSizeSp)
            }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val disk = repository.readFile(repoId, path)
                val draft = repository.draft(repoId, path)
                // 草稿与磁盘不同则优先恢复草稿，并在状态里提示
                when {
                    disk == null && draft == null -> null
                    draft != null && draft != disk -> draft
                    else -> disk
                }
            }.onSuccess { content ->
                val text = content.orEmpty()
                _state.value = _state.value.copy(
                    loading = false,
                    content = text,
                    original = text,
                    dirty = false,
                    error = if (content == null) "无法以文本方式打开该文件（可能是二进制或不存在）" else null,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "打开失败")
            }
        }
    }

    fun onContentChange(value: String) {
        _state.value = _state.value.copy(
            content = value,
            dirty = value != _state.value.original,
        )
        if (!autoSaveEnabled) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(800)
            save()
        }
    }

    fun save() {
        val current = _state.value
        if (!current.dirty || current.loading) return
        viewModelScope.launch {
            _state.value = current.copy(saving = true, message = null)
            runCatching { repository.saveFile(repoId, path, current.content) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        saving = false,
                        original = current.content,
                        dirty = false,
                        message = "已保存",
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        saving = false,
                        message = "保存失败：${it.message}",
                    )
                }
        }
    }

    /** 放弃本次未保存的编辑，内容回退到上次保存（或磁盘）的版本。 */
    fun revert() {
        autoSaveJob?.cancel()
        _state.value = _state.value.copy(
            content = _state.value.original,
            dirty = false,
            message = "已撤销未保存的修改",
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
