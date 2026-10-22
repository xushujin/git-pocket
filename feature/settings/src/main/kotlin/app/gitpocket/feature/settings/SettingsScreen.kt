package app.gitpocket.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.designsystem.GitPocketPalettes
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpSectionHeader
import app.gitpocket.core.designsystem.GpTextField
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.ThemeMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val settings = LocalSettingsRepository.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(settings) } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    GpScreen {
        GpTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            GpSectionHeader("外观")
            GpCard {
                SettingLabel("主题模式", "跟随系统，或强制浅色 / 深色")
                ChoiceRow(
                    options = listOf(
                        ThemeMode.SYSTEM.name to "跟随系统",
                        ThemeMode.LIGHT.name to "浅色",
                        ThemeMode.DARK.name to "深色",
                    ),
                    selected = state.theme.mode.name,
                    onSelect = { viewModel.setThemeMode(ThemeMode.valueOf(it)) },
                )
                Spacer(Modifier.height(12.dp))
                SettingLabel("浅色配色", "仅影响浅色模式")
                ChoiceRow(
                    options = GitPocketPalettes.light.map { it.id to it.displayName },
                    selected = state.theme.lightPaletteId,
                    onSelect = viewModel::setLightPalette,
                )
                Spacer(Modifier.height(12.dp))
                SettingLabel("深色配色", "仅影响深色模式")
                ChoiceRow(
                    options = GitPocketPalettes.dark.map { it.id to it.displayName },
                    selected = state.theme.darkPaletteId,
                    onSelect = viewModel::setDarkPalette,
                )
            }

            GpSectionHeader("编辑器")
            GpCard {
                StepperRow(
                    title = "字号",
                    description = "影响编辑器与 diff（12–20sp）",
                    value = state.theme.editorFontSizeSp,
                    onChange = viewModel::setFontSize,
                    step = 1,
                )
                ToggleRow(
                    title = "自动保存",
                    description = "停止输入 800ms 后自动落盘",
                    checked = state.autoSave,
                    onCheckedChange = viewModel::setAutoSave,
                )
                ToggleRow(
                    title = "显示行号",
                    description = null,
                    checked = state.showLineNumbers,
                    onCheckedChange = viewModel::setShowLineNumbers,
                )
                StepperRow(
                    title = "制表符宽度",
                    description = null,
                    value = state.tabWidth,
                    onChange = viewModel::setTabWidth,
                    step = 2,
                )
            }

            GpSectionHeader("Git")
            GpCard {
                SettingLabel("作者名", "提交时写入 author / committer")
                GpTextField(
                    value = state.authorName,
                    onValueChange = viewModel::setAuthorName,
                    placeholder = "例如：张三",
                )
                Spacer(Modifier.height(12.dp))
                SettingLabel("作者邮箱", null)
                GpTextField(
                    value = state.authorEmail,
                    onValueChange = viewModel::setAuthorEmail,
                    placeholder = "you@example.com",
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "打开仓库时自动 fetch",
                    description = "打开仓库时自动拉取远端更新（fetch 支持后生效）",
                    checked = state.autoFetch,
                    onCheckedChange = viewModel::setAutoFetch,
                )
            }

            GpSectionHeader("网络")
            GpCard {
                StepperRow(
                    title = "连接超时（秒）",
                    description = "克隆 / 拉取时的建连超时",
                    value = state.connectTimeout,
                    onChange = viewModel::setConnectTimeout,
                    step = 1,
                )
                StepperRow(
                    title = "读取超时（秒）",
                    description = "大仓库拉取可适当调大",
                    value = state.readTimeout,
                    onChange = viewModel::setReadTimeout,
                    step = 10,
                )
            }

            GpSectionHeader("安全")
            GpCard {
                ToggleRow(
                    title = "生物识别解锁",
                    description = "打开应用时校验指纹 / 面容 / 锁屏密码",
                    checked = state.biometric,
                    onCheckedChange = viewModel::setBiometric,
                )
            }

            GpSectionHeader("关于")
            GpCard(onClick = onOpenAbout, padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "关于 GitPocket",
                        style = LocalGitPocketTypography.current.body,
                        color = LocalNeumorphicPalette.current.contentPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "›",
                        style = LocalGitPocketTypography.current.body,
                        color = LocalNeumorphicPalette.current.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingLabel(title: String, description: String?) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = typography.bodyStrong, color = palette.contentPrimary)
        if (description != null) {
            Text(description, style = typography.caption, color = palette.contentSecondary)
        }
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = typography.caption,
                    color = if (active) palette.accent else palette.contentSecondary,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = typography.body, color = palette.contentPrimary)
            if (description != null) {
                Text(description, style = typography.caption, color = palette.contentSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.contentSecondary,
                uncheckedTrackColor = palette.base,
            ),
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    description: String?,
    value: Int,
    onChange: (Int) -> Unit,
    step: Int,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = typography.body, color = palette.contentPrimary)
            if (description != null) {
                Text(description, style = typography.caption, color = palette.contentSecondary)
            }
        }
        StepButton("−") { onChange(value - step) }
        Text(
            text = "$value",
            style = typography.bodyStrong,
            color = palette.contentPrimary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        StepButton("+") { onChange(value + step) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = typography.bodyStrong, color = palette.accent)
    }
}
