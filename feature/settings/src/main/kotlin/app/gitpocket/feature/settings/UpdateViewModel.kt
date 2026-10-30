package app.gitpocket.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.update.UpdateCheck
import app.gitpocket.core.update.UpdateInfo
import app.gitpocket.core.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 更新流程的状态机：一次只允许一个阶段在跑，UI 只负责按阶段渲染。 */
enum class UpdatePhase {
    /** 还没查过 */
    Idle,
    Checking,
    UpToDate,
    /** 有新版本，等用户决定 */
    Available,
    Downloading,
    /** 下载完成、正在校验/拉起安装器 */
    Installing,
    /** 安装器已拉起 */
    ReadyToInstall,
    /** 缺少"安装未知应用"授权 */
    NeedsPermission,
    Failed,
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val info: UpdateInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
    val error: String? = null,
    /** 是否显示"发现新版本"弹窗（下载开始后自动关掉） */
    val dialogVisible: Boolean = false,
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }

    val busy: Boolean
        get() = phase == UpdatePhase.Checking ||
            phase == UpdatePhase.Downloading ||
            phase == UpdatePhase.Installing
}

class UpdateViewModel(
    private val updater: UpdateRepository,
    private val currentVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var downloadedApk: File? = null

    fun check() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = UpdatePhase.Checking,
                error = null,
                message = null,
                dialogVisible = false,
            )
            runCatching { updater.check(currentVersion) }
                .onSuccess { result ->
                    _state.value = when (result) {
                        is UpdateCheck.Available -> _state.value.copy(
                            phase = UpdatePhase.Available,
                            info = result.info,
                            dialogVisible = true,
                            error = null,
                        )

                        UpdateCheck.UpToDate -> _state.value.copy(
                            phase = UpdatePhase.UpToDate,
                            info = null,
                            message = "已是最新版本（$currentVersion）",
                        )
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.Failed,
                        error = error.userText(),
                    )
                }
        }
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(dialogVisible = false)
    }

    /** 下载 → 校验 → 拉起系统安装器。整个过程只跑一份，重复点击无效。 */
    fun download() {
        val info = _state.value.info ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = UpdatePhase.Downloading,
                dialogVisible = false,
                downloadedBytes = 0,
                totalBytes = info.apkSize,
                message = null,
                error = null,
            )
            runCatching {
                updater.clearOldDownloads()
                updater.download(info) { done, total ->
                    _state.value = _state.value.copy(
                        downloadedBytes = done,
                        totalBytes = if (total > 0) total else info.apkSize,
                    )
                }
            }.onSuccess { apk ->
                downloadedApk = apk
                _state.value = _state.value.copy(phase = UpdatePhase.Installing, message = "下载完成，正在校验…")
                if (updater.canInstall()) {
                    runCatching { updater.install(apk) }
                        .onSuccess {
                            _state.value = _state.value.copy(
                                phase = UpdatePhase.ReadyToInstall,
                                message = "已打开系统安装器，按提示完成安装",
                            )
                        }
                        .onFailure { error ->
                            _state.value = _state.value.copy(
                                phase = UpdatePhase.Failed,
                                error = "无法打开安装器：${error.message ?: "未知原因"}",
                            )
                        }
                } else {
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.NeedsPermission,
                        message = "需要允许 GitPocket 安装应用",
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    // 用户主动取消：回到"发现新版本"，不当作失败
                    _state.value = _state.value.copy(
                        phase = UpdatePhase.Available,
                        downloadedBytes = 0,
                        dialogVisible = true,
                    )
                    return@onFailure
                }
                _state.value = _state.value.copy(
                    phase = UpdatePhase.Failed,
                    error = error.userText(),
                )
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    fun openInstallPermissionSettings() = updater.openInstallPermissionSettings()

    /** 从"安装未知应用"设置页回来后，用户点「立即安装」/「重新打开安装器」。 */
    fun installPending() {
        val apk = downloadedApk
            ?: _state.value.info?.let { updater.downloadedApk(it.versionName) }
            ?: return
        downloadedApk = apk
        if (!updater.canInstall()) {
            _state.value = _state.value.copy(phase = UpdatePhase.NeedsPermission)
            return
        }
        runCatching { updater.install(apk) }
            .onSuccess {
                _state.value = _state.value.copy(
                    phase = UpdatePhase.ReadyToInstall,
                    message = "已打开系统安装器，按提示完成安装",
                )
            }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    phase = UpdatePhase.Failed,
                    error = "无法打开安装器：${error.message ?: "未知原因"}",
                )
            }
    }

    private fun Throwable.userText(): String =
        message?.takeIf { it.isNotBlank() } ?: "更新失败，请稍后重试"
}
