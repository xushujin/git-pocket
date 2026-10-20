package app.gitpocket.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpIconButton
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(
    repoId: String,
    path: String,
    onBack: () -> Unit,
    onOpenDiff: () -> Unit,
) {
    val repository = LocalRepoRepository.current
    val settings = LocalSettingsRepository.current
    val id = remember(repoId) { RepoId(repoId) }
    val viewModel: EditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer { EditorViewModel(id, path, repository, settings) }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    BackHandler(enabled = state.dirty) {
        viewModel.save()
        onBack()
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2000)
            viewModel.clearMessage()
        }
    }

    GpScreen {
        GpTopBar(
            title = path.substringAfterLast('/'),
            subtitle = when {
                state.saving -> "保存中…"
                state.dirty -> "未保存的修改"
                else -> "已保存"
            },
            onBack = onBack,
            actions = {
                GpIconButton(
                    icon = Icons.Default.Check,
                    contentDescription = "保存",
                    onClick = viewModel::save,
                    enabled = state.dirty && !state.saving,
                )
            },
        )

        when {
            state.loading -> GpLoading("正在打开文件…")
            state.error != null -> GpEmptyState("无法打开", state.error.orEmpty())
            else -> {
                val lineStyle = typography.mono.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = state.fontSizeSp.sp,
                    lineHeight = (state.fontSizeSp + 6).sp,
                )
                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .verticalScroll(verticalScroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (state.showLineNumbers) {
                        Column(Modifier.padding(end = 10.dp)) {
                            repeat(state.lineCount) { index ->
                                Text(
                                    text = "${index + 1}",
                                    style = lineStyle,
                                    color = palette.contentSecondary.copy(alpha = 0.45f),
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                    ) {
                        BasicTextField(
                            value = state.content,
                            onValueChange = viewModel::onContentChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = lineStyle.copy(color = palette.contentPrimary),
                            cursorBrush = SolidColor(palette.accent),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDiff)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.message ?: "${state.lineCount} 行 · ${state.content.length} 字符",
                        style = typography.caption,
                        color = palette.contentSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "查看 diff",
                        style = typography.caption,
                        color = palette.accent,
                    )
                }
            }
        }
    }
}
