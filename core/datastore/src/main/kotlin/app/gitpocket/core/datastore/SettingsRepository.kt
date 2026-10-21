package app.gitpocket.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gitpocket.core.model.ThemeMode
import app.gitpocket.core.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class SettingsSection(val title: String) {
    APPEARANCE("外观"),
    EDITOR("编辑器"),
    GIT("Git"),
    NETWORK("网络"),
    STORAGE("存储"),
    SECURITY("安全"),
}

/**
 * 设置项注册表：每个设置项只有一个 key、一个默认值、一处定义。
 * UI 由 [SettingsSpecs] 驱动渲染，禁止在页面里再写一套读写逻辑。
 */
data class SettingSpec<T>(
    val id: String,
    val section: SettingsSection,
    val title: String,
    val description: String?,
    val key: Preferences.Key<T>,
    val defaultValue: T,
)

object SettingsSpecs {

    val themeMode = SettingSpec(
        id = "appearance.theme_mode",
        section = SettingsSection.APPEARANCE,
        title = "主题模式",
        description = "跟随系统，或强制浅色 / 深色",
        key = stringPreferencesKey("theme_mode"),
        defaultValue = ThemeMode.SYSTEM.name,
    )

    val lightPalette = SettingSpec(
        id = "appearance.palette.light",
        section = SettingsSection.APPEARANCE,
        title = "浅色配色",
        description = null,
        key = stringPreferencesKey("palette_light"),
        defaultValue = "mist",
    )

    val darkPalette = SettingSpec(
        id = "appearance.palette.dark",
        section = SettingsSection.APPEARANCE,
        title = "深色配色",
        description = null,
        key = stringPreferencesKey("palette_dark"),
        defaultValue = "graphite",
    )

    val editorFontSize = SettingSpec(
        id = "editor.font_size",
        section = SettingsSection.EDITOR,
        title = "字号",
        description = "影响编辑器与 diff 视图（12–20sp）",
        key = intPreferencesKey("editor_font_size"),
        defaultValue = 14,
    )

    val autoSave = SettingSpec(
        id = "editor.auto_save",
        section = SettingsSection.EDITOR,
        title = "自动保存",
        description = "关闭后需要手动点右上角保存（默认关闭）",
        key = booleanPreferencesKey("editor_auto_save"),
        defaultValue = false,
    )

    val showLineNumbers = SettingSpec(
        id = "editor.line_numbers",
        section = SettingsSection.EDITOR,
        title = "显示行号",
        description = null,
        key = booleanPreferencesKey("editor_line_numbers"),
        defaultValue = true,
    )

    val tabWidth = SettingSpec(
        id = "editor.tab_width",
        section = SettingsSection.EDITOR,
        title = "制表符宽度",
        description = null,
        key = intPreferencesKey("editor_tab_width"),
        defaultValue = 4,
    )

    val authorName = SettingSpec(
        id = "git.author_name",
        section = SettingsSection.GIT,
        title = "作者名",
        description = "提交时写入 author / committer",
        key = stringPreferencesKey("git_author_name"),
        defaultValue = "",
    )

    val authorEmail = SettingSpec(
        id = "git.author_email",
        section = SettingsSection.GIT,
        title = "作者邮箱",
        description = null,
        key = stringPreferencesKey("git_author_email"),
        defaultValue = "",
    )

    val autoFetch = SettingSpec(
        id = "git.auto_fetch",
        section = SettingsSection.GIT,
        title = "打开仓库时自动 fetch",
        description = "弱网环境可关闭",
        key = booleanPreferencesKey("git_auto_fetch"),
        defaultValue = true,
    )

    val connectTimeout = SettingSpec(
        id = "network.connect_timeout",
        section = SettingsSection.NETWORK,
        title = "连接超时（秒）",
        description = null,
        key = intPreferencesKey("network_connect_timeout"),
        defaultValue = 10,
    )

    val readTimeout = SettingSpec(
        id = "network.read_timeout",
        section = SettingsSection.NETWORK,
        title = "读取超时（秒）",
        description = "大仓库拉取可适当调大",
        key = intPreferencesKey("network_read_timeout"),
        defaultValue = 60,
    )

    val biometric = SettingSpec(
        id = "security.biometric",
        section = SettingsSection.SECURITY,
        title = "生物识别解锁",
        description = "打开应用时校验指纹 / 面容后才解密 token（第二阶段）",
        key = booleanPreferencesKey("security_biometric"),
        defaultValue = false,
    )

    val all: List<SettingSpec<*>> = listOf(
        themeMode, lightPalette, darkPalette,
        editorFontSize, autoSave, showLineNumbers, tabWidth,
        authorName, authorEmail, autoFetch,
        connectTimeout, readTimeout,
        biometric,
    )

    fun bySection(section: SettingsSection): List<SettingSpec<*>> = all.filter { it.section == section }
}

/** 设置快照：UI 一次订阅，避免每项各收一条流。 */
data class SettingsSnapshot(
    val theme: ThemeSettings = ThemeSettings(),
    val autoSave: Boolean = false,
    val showLineNumbers: Boolean = true,
    val tabWidth: Int = 4,
    val authorName: String = "",
    val authorEmail: String = "",
    val autoFetch: Boolean = true,
    val connectTimeout: Int = 10,
    val readTimeout: Int = 60,
    val biometric: Boolean = false,
)

class SettingsRepository(private val store: DataStore<Preferences>) {

    fun <T> observe(spec: SettingSpec<T>): Flow<T> =
        store.data.map { it[spec.key] ?: spec.defaultValue }

    suspend fun <T> set(spec: SettingSpec<T>, value: T) {
        store.edit { it[spec.key] = value }
    }

    /** 主题是派生值，仍只从同一份 Preferences 计算，不额外维护第二份状态。 */
    val theme: Flow<ThemeSettings> = store.data.map { it.toThemeSettings() }

    val snapshot: Flow<SettingsSnapshot> = store.data.map { prefs ->
        SettingsSnapshot(
            theme = prefs.toThemeSettings(),
            autoSave = prefs[SettingsSpecs.autoSave.key] ?: false,
            showLineNumbers = prefs[SettingsSpecs.showLineNumbers.key] ?: true,
            tabWidth = prefs[SettingsSpecs.tabWidth.key] ?: 4,
            authorName = prefs[SettingsSpecs.authorName.key].orEmpty(),
            authorEmail = prefs[SettingsSpecs.authorEmail.key].orEmpty(),
            autoFetch = prefs[SettingsSpecs.autoFetch.key] ?: true,
            connectTimeout = prefs[SettingsSpecs.connectTimeout.key] ?: 10,
            readTimeout = prefs[SettingsSpecs.readTimeout.key] ?: 60,
            biometric = prefs[SettingsSpecs.biometric.key] ?: false,
        )
    }

    val autoSave: Flow<Boolean> = observe(SettingsSpecs.autoSave)
    val showLineNumbers: Flow<Boolean> = observe(SettingsSpecs.showLineNumbers)
    val tabWidth: Flow<Int> = observe(SettingsSpecs.tabWidth)

    suspend fun authorIdentity(): Pair<String, String> {
        val prefs = store.data.first()
        return (prefs[SettingsSpecs.authorName.key].orEmpty()) to
            (prefs[SettingsSpecs.authorEmail.key].orEmpty())
    }

    private fun Preferences.toThemeSettings(): ThemeSettings = ThemeSettings(
        mode = runCatching {
            ThemeMode.valueOf(this[SettingsSpecs.themeMode.key] ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
        lightPaletteId = this[SettingsSpecs.lightPalette.key] ?: "mist",
        darkPaletteId = this[SettingsSpecs.darkPalette.key] ?: "graphite",
        editorFontSizeSp = (this[SettingsSpecs.editorFontSize.key] ?: 14).coerceIn(12, 20),
    )
}
