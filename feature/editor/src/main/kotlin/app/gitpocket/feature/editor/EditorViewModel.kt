package app.gitpocket.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.common.text.UndoStack
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
    val readOnly: Boolean = false,
    val tooLarge: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
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

    /** 撤销栈：合并规则见 [UndoStack]。 */
    private val undoStack = UndoStack()

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
                // 超过编辑上限的文件只读预览：整份塞进 BasicTextField 会卡死（已实测）
                val size = repository.fileSize(repoId, path)
                if (size > MAX_EDIT_BYTES) {
                    val text = repository.readFile(repoId, path)
                    return@runCatching Loaded(
                        content = text?.take(MAX_PREVIEW_CHARS).orEmpty(),
                        readOnly = true,
                        tooLarge = true,
                    )
                }
                val disk = repository.readFile(repoId, path)
                val draft = repository.draft(repoId, path)
                // 草稿与磁盘不同则优先恢复草稿，并在状态里提示
                Loaded(
                    content = when {
                        disk == null && draft == null -> null
                        draft != null && draft != disk -> draft
                        else -> disk
                    },
                )
            }.onSuccess { loaded ->
                val text = loaded.content.orEmpty()
                undoStack.clear()
                _state.value = _state.value.copy(
                    loading = false,
                    content = text,
                    original = text,
                    dirty = false,
                    canUndo = false,
                    canRedo = false,
                    readOnly = loaded.readOnly,
                    tooLarge = loaded.tooLarge,
                    error = when {
                        loaded.tooLarge -> null
                        loaded.content == null -> "无法以文本方式打开该文件（可能是二进制或不存在）"
                        else -> null
                    },
                    message = if (loaded.tooLarge) {
                        "文件超过 ${MAX_EDIT_BYTES / 1024} KB，只能预览前 ${MAX_PREVIEW_CHARS / 1000}K 字符"
                    } else {
                        null
                    },
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "打开失败")
            }
        }
    }

    private data class Loaded(
        val content: String?,
        val readOnly: Boolean = false,
        val tooLarge: Boolean = false,
    )

    fun onContentChange(value: String) {
        val current = _state.value
        if (value == current.content || current.readOnly) return
        undoStack.record(current.content, value)
        _state.value = current.copy(
            content = value,
            dirty = value != current.original,
            canUndo = undoStack.canUndo,
            canRedo = undoStack.canRedo,
        )
        if (!autoSaveEnabled) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(800)
            save()
        }
    }

    /** 回退一步（用户说的"回退"：去掉刚写的那一段）。 */
    fun undo() {
        val previous = undoStack.undo() ?: return
        applyHistory(previous, "已回退一步")
    }

    /** 重做一步。 */
    fun redo() {
        val next = undoStack.redo() ?: return
        applyHistory(next, "已重做一步")
    }

    private fun applyHistory(content: String, message: String) {
        val current = _state.value
        _state.value = current.copy(
            content = content,
            dirty = content != current.original,
            canUndo = undoStack.canUndo,
            canRedo = undoStack.canRedo,
            message = message,
        )
    }

    fun save() {
        viewModelScope.launch { saveNow() }
    }

    /**
     * 保存并等结果。**退出页面前必须用这个**：
     * `save()` 只是 launch 一个协程，紧接着 pop 页面会让 viewModelScope 被取消，
     * 写盘还没开始就死了 —— "保存并退出"会静默丢改动。
     *
     * 返回 true 表示内容已经在磁盘上（包括"本来就没有改动"）。
     */
    suspend fun saveNow(): Boolean {
        val current = _state.value
        if (current.loading) return false
        if (!current.dirty || current.readOnly) return true

        _state.value = current.copy(saving = true, message = null)
        return runCatching { repository.saveFile(repoId, path, current.content) }
            .fold(
                onSuccess = {
                    // 保存期间用户可能又输入了：这时不能把 dirty 清掉，否则新内容会被当成已保存
                    val latest = _state.value
                    val stillDirty = latest.content != current.content
                    _state.value = latest.copy(
                        saving = false,
                        original = current.content,
                        dirty = stillDirty,
                        message = if (stillDirty) "已保存，但之后又有新改动" else "已保存",
                    )
                    true
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        saving = false,
                        message = "保存失败：${it.message ?: "未知错误"}",
                    )
                    false
                },
            )
    }

    /** 放弃本次未保存的编辑，内容回退到上次保存（或磁盘）的版本。 */
    fun revert() {
        autoSaveJob?.cancel()
        undoStack.clear()
        _state.value = _state.value.copy(
            content = _state.value.original,
            dirty = false,
            canUndo = false,
            canRedo = false,
            message = "已还原到上次保存的版本",
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        /** 编辑上限：超过这个大小只读预览（BasicTextField 在 MB 级文本下会明显卡顿）。 */
        const val MAX_EDIT_BYTES = 512 * 1024L

        /** 只读预览最多渲染的字符数，避免一次性布局几十万字符。 */
        const val MAX_PREVIEW_CHARS = 200_000
    }
}
