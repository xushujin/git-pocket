package app.gitpocket.core.data

import androidx.compose.runtime.staticCompositionLocalOf
import app.gitpocket.core.datastore.SettingsRepository

/**
 * 无 DI 框架的依赖提供方式：Application 装配一次，Compose 树里按需取。
 * 这样 feature 模块不需要依赖 app 模块。
 */
val LocalRepoRepository = staticCompositionLocalOf<RepoRepository> {
    error("RepoRepository 未提供：请检查 MainActivity 的 CompositionLocalProvider")
}

val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository> {
    error("SettingsRepository 未提供：请检查 MainActivity 的 CompositionLocalProvider")
}
