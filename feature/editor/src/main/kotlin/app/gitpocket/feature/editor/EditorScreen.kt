package app.gitpocket.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.designsystem.MarkdownView
import app.gitpocket.core.designsystem.rememberHighlightedCode
import app.gitpocket.core.designsystem.rememberSyntaxTransformation
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // 默认进预览：打开文件只是"看一眼"是绝大多数场景，写操作需要显式点「编辑」
    var editing by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var revertDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun attemptLeave() {
        if (state.dirty) exitDialog = true else onBack()
    }

    // 有未保存修改时拦截返回，交给用户确认；不直接静默保存
    BackHandler(enabled = state.dirty) { exitDialog = true }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        // 失败信息留在页面上，别 2 秒就消失（用户还没读完就没了）
        if (message.startsWith("保存失败")) return@LaunchedEffect
        delay(2000)
        viewModel.clearMessage()
    }

    GpScreen {
        GpTopBar(
            title = path.substringAfterLast('/'),
            subtitle = when {
                state.tooLarge -> "只读 · 文件超过 512 KB"
                state.saving -> "保存中…"
                state.dirty -> "未保存的修改"
                editing -> "编辑中"
                else -> "预览"
            },
            onBack = ::attemptLeave,
            actions = {
                // 放在顶栏而不是底部状态栏：键盘弹起时底部会被 IME 盖住，点不到
                if (editing && state.canUndo) {
                    GpTextButton(text = "回退", onClick = viewModel::undo)
                }
                if (editing && state.canRedo) {
                    GpTextButton(text = "重做", onClick = viewModel::redo)
                }
                if (!state.readOnly) {
                    GpTextButton(
                        text = if (editing) "预览" else "编辑",
                        onClick = { editing = !editing },
                    )
                }
                GpIconButton(
                    icon = Icons.Default.Check,
                    contentDescription = "保存",
                    onClick = viewModel::save,
                    enabled = state.dirty && !state.saving && !state.readOnly,
                )
            },
        )

        when {
            state.loading -> GpLoading("正在打开文件…")
            state.error != null -> GpEmptyState("无法打开", state.error.orEmpty())
            else -> {
                if (editing) {
                    EditorPane(
                        state = state,
                        language = language,
                        modifier = Modifier.weight(1f),
                        onContentChange = viewModel::onContentChange,
                    )
                } else {
                    PreviewPane(
                        state = state,
                        language = language,
                        modifier = Modifier.weight(1f),
                    )
                }

                StatusBar(
                    state = state,
                    editing = editing,
                    onRevert = { revertDialog = true },
                    onOpenDiff = onOpenDiff,
                )
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
                // 必须等写盘完成再返回：先 onBack 会取消 viewModelScope，保存根本没跑
                scope.launch {
                    if (viewModel.saveNow()) onBack()
                }
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
            title = "还原到上次保存的版本？",
            message = "本次所有未保存的编辑都会丢弃，这一步不能撤销。",
            confirmText = "还原",
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

/** 只读预览：Markdown 走渲染器，其余文件按语言高亮显示。 */
@Composable
private fun PreviewPane(
    state: EditorUiState,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val vertical = rememberScrollState()

    if (state.content.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "空文件",
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }
        return
    }

    if (language == Language.MARKDOWN) {
        val blocks = remember(state.content) { MarkdownParser.parse(state.content) }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(vertical)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            MarkdownView(blocks = blocks)
        }
        return
    }

    val highlighted = rememberHighlightedCode(state.content, language)
    val style = typography.mono.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSizeSp.sp,
        lineHeight = (state.fontSizeSp + 6).sp,
        color = palette.contentPrimary,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(vertical)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = highlighted,
            style = style,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun EditorPane(
    state: EditorUiState,
    language: Language,
    modifier: Modifier = Modifier,
    onContentChange: (String) -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val lineStyle = typography.mono.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSizeSp.sp,
        lineHeight = (state.fontSizeSp + 6).sp,
    )
    val verticalScroll = rememberScrollState()
    val syntax = rememberSyntaxTransformation(state.content, language)

    Row(
        modifier = modifier
            .fillMaxWidth()
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
            onValueChange = onContentChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            textStyle = lineStyle.copy(color = palette.contentPrimary),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = syntax,
        )
    }
}

@Composable
private fun StatusBar(
    state: EditorUiState,
    editing: Boolean,
    onRevert: () -> Unit,
    onOpenDiff: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.message ?: buildString {
                append(state.lineCount).append(" 行")
                if (editing) append(" · ").append(state.content.length).append(" 字符")
            },
            style = typography.caption,
            color = palette.contentSecondary,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (state.dirty) {
            Text(
                text = "还原",
                style = typography.caption,
                color = palette.danger,
                modifier = Modifier
                    .clickable(onClick = onRevert)
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
