package app.gitpocket.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import app.gitpocket.core.common.markdown.MarkdownParser
import app.gitpocket.core.common.syntax.Language
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.designsystem.GpConfirmDialog
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpIconButton
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.MarkdownView
import app.gitpocket.core.designsystem.rememberSyntaxTransformation
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
    val language = remember(path) { Language.fromPath(path) }
    val isMarkdown = language == Language.MARKDOWN
    var preview by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var revertDialog by remember { mutableStateOf(false) }

    // 有未保存修改时拦截返回，交给用户确认；不直接静默保存
    BackHandler(enabled = state.dirty) { exitDialog = true }

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
                if (isMarkdown) {
                    GpTextButton(
                        text = if (preview) "编辑" else "预览",
                        onClick = { preview = !preview },
                    )
                }
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
            isMarkdown && preview -> {
                val blocks = remember(state.content) { MarkdownParser.parse(state.content) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    MarkdownView(blocks = blocks)
                }
            }

            else -> {
                val lineStyle = typography.mono.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = state.fontSizeSp.sp,
                    lineHeight = (state.fontSizeSp + 6).sp,
                )
                val verticalScroll = rememberScrollState()
                val syntax = rememberSyntaxTransformation(state.content, language)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    BasicTextField(
                        value = state.content,
                        onValueChange = viewModel::onContentChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                        textStyle = lineStyle.copy(color = palette.contentPrimary),
                        cursorBrush = SolidColor(palette.accent),
                        visualTransformation = syntax,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.message ?: if (isMarkdown && preview) {
                            "预览模式 · 共 ${state.lineCount} 行"
                        } else {
                            "${state.lineCount} 行 · ${state.content.length} 字符"
                        },
                        style = typography.caption,
                        color = palette.contentSecondary,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    if (state.dirty) {
                        Text(
                            text = "撤销",
                            style = typography.caption,
                            color = palette.danger,
                            modifier = Modifier
                                .clickable { revertDialog = true }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = "查看 diff",
                        style = typography.caption,
                        color = palette.accent,
                        modifier = Modifier
                            .clickable(onClick = onOpenDiff)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (exitDialog) {
        GpConfirmDialog(
            title = "有未保存的修改",
            message = "保存后再离开，还是放弃这次修改？",
            confirmText = "保存并退出",
            dismissText = "放弃修改",
            onConfirm = {
                exitDialog = false
                viewModel.save()
                onBack()
            },
            onDismiss = {
                exitDialog = false
                viewModel.revert()
                onBack()
            },
            onCancel = { exitDialog = false },
        )
    }

    if (revertDialog) {
        GpConfirmDialog(
            title = "撤销未保存的修改",
            message = "内容会回到上次保存的版本，这一步不能撤销。",
            confirmText = "撤销修改",
            dismissText = "取消",
            onConfirm = {
                revertDialog = false
                viewModel.revert()
            },
            onDismiss = { revertDialog = false },
            onCancel = { revertDialog = false },
        )
    }
}
