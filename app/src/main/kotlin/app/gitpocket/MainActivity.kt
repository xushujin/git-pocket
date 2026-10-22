package app.gitpocket

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.datastore.SettingsSnapshot
import app.gitpocket.core.designsystem.GitPocketTheme
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.ThemeSettings
import app.gitpocket.navigation.AppNavHost

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GitPocketApplication).container

        setContent {
            // 主题只有这一处入口：设置变化 -> 重组整棵树，不重建 Activity
            val theme by container.settings.theme.collectAsStateWithLifecycle(
                initialValue = ThemeSettings()
            )
            // 先别给默认值：设置没读出来之前不能组合内容，否则会"先看到仓库再被锁上"
            val snapshot by container.settings.snapshot.collectAsStateWithLifecycle(
                initialValue = null
            )

            GitPocketTheme(settings = theme) {
                CompositionLocalProvider(
                    LocalRepoRepository provides container.repoRepository,
                    LocalSettingsRepository provides container.settings,
                ) {
                    val loaded = snapshot
                    if (loaded == null) {
                        // 设置读取中（毫秒级），铺一层底色避免内容闪现
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(LocalNeumorphicPalette.current.base),
                        )
                    } else {
                        // 开启生物识别后，冷启动必须先解锁才组合内容
                        BiometricGate(
                            enabled = loaded.biometric,
                            settings = container.settings,
                        ) {
                            AppNavHost(appVersion = BuildConfig.VERSION_NAME)
                        }
                    }
                }
            }
        }
    }
}
