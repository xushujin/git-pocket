package app.gitpocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.designsystem.GitPocketTheme
import app.gitpocket.core.model.ThemeSettings
import app.gitpocket.navigation.AppNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GitPocketApplication).container

        setContent {
            // 主题只有这一处入口：设置变化 -> 重组整棵树，不重建 Activity
            val theme by container.settings.theme.collectAsStateWithLifecycle(
                initialValue = ThemeSettings()
            )

            GitPocketTheme(settings = theme) {
                CompositionLocalProvider(
                    LocalRepoRepository provides container.repoRepository,
                    LocalSettingsRepository provides container.settings,
                ) {
                    AppNavHost(appVersion = BuildConfig.VERSION_NAME)
                }
            }
        }
    }
}
