package app.gitpocket

import android.content.Context
import app.gitpocket.core.common.DefaultAppDispatchers
import app.gitpocket.core.common.RepoLockRegistry
import app.gitpocket.core.data.RepoRepository
import app.gitpocket.core.database.DatabaseFactory
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.datastore.settingsDataStore
import app.gitpocket.core.security.KeystoreTokenStore
import java.io.File

/**
 * 手写依赖容器（不引入 DI 框架）。
 * 所有依赖延迟初始化，首屏不触碰数据库与文件系统。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database by lazy { DatabaseFactory.create(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext.settingsDataStore) }

    val tokenStore: KeystoreTokenStore by lazy {
        KeystoreTokenStore(File(appContext.filesDir, "tokens"))
    }

    val repoRepository: RepoRepository by lazy {
        RepoRepository(
            repoRoot = File(appContext.filesDir, "repos"),
            repoDao = database.repoDao(),
            draftDao = database.draftDao(),
            settings = settings,
            locks = RepoLockRegistry(),
            dispatchers = DefaultAppDispatchers,
        )
    }
}
