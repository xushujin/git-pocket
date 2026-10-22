package app.gitpocket.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.datastore.SettingsSnapshot
import app.gitpocket.core.datastore.SettingsSpecs
import app.gitpocket.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 所有设置项都经由 [SettingsRepository] 的注册表读写，
 * 页面里不保存第二份状态（避免"同一个设置两套实现"）。
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsSnapshot())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.snapshot.collect { _state.value = it }
        }
    }

    fun setThemeMode(mode: ThemeMode) = update(SettingsSpecs.themeMode, mode.name)

    fun setLightPalette(id: String) = update(SettingsSpecs.lightPalette, id)

    fun setDarkPalette(id: String) = update(SettingsSpecs.darkPalette, id)

    fun setFontSize(size: Int) = update(SettingsSpecs.editorFontSize, size.coerceIn(12, 20))

    fun setAutoSave(enabled: Boolean) = update(SettingsSpecs.autoSave, enabled)

    fun setShowLineNumbers(enabled: Boolean) = update(SettingsSpecs.showLineNumbers, enabled)

    fun setTabWidth(width: Int) = update(SettingsSpecs.tabWidth, width.coerceIn(2, 8))

    fun setAuthorName(value: String) = update(SettingsSpecs.authorName, value)

    fun setAuthorEmail(value: String) = update(SettingsSpecs.authorEmail, value)

    fun setAutoFetch(enabled: Boolean) = update(SettingsSpecs.autoFetch, enabled)

    fun setConnectTimeout(seconds: Int) = update(SettingsSpecs.connectTimeout, seconds.coerceIn(5, 60))

    fun setReadTimeout(seconds: Int) = update(SettingsSpecs.readTimeout, seconds.coerceIn(30, 300))

    fun setBiometric(enabled: Boolean) = update(SettingsSpecs.biometric, enabled)

    private fun <T> update(spec: app.gitpocket.core.datastore.SettingSpec<T>, value: T) {
        viewModelScope.launch { settings.set(spec, value) }
    }
}
