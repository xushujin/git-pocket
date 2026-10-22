package app.gitpocket.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.DiffLine
import app.gitpocket.core.model.DiffLineKind
import app.gitpocket.core.model.FileDiff
import app.gitpocket.core.model.RepoId
import kotlinx.coroutines.launch

@Composable
fun DiffScreen(
    repoId: String,
    path: String,
    onBack: () -> Unit,
) {
    val repository = LocalRepoRepository.current
    val id = remember(repoId) { RepoId(repoId) }
    val viewModel: DiffViewModel = viewModel(
        factory = viewModelFactory { initializer { DiffViewModel(id, path, repository) } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    GpScreen {
        GpTopBar(
            title = path.substringAfterLast('/'),
            subtitle = state.diff?.let { "+${it.additions}  −${it.deletions}" } ?: "与 HEAD 比较",
            onBack = onBack,
        )

        when {
            state.loading -> GpLoading("正在计算 diff…")
            state.error != null -> GpEmptyState("无法比较", state.error.orEmpty())
            state.diff == null -> GpEmptyState("没有内容", "文件可能已被删除。")
            else -> DiffBody(state.diff!!)
        }
    }
}

@Composable
private fun DiffBody(diff: FileDiff) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    when {
        diff.isBinary -> GpEmptyState("二进制文件", "不展示逐行差异，仅显示文件级变更。")
        diff.truncated -> GpEmptyState(
            title = "文件过大",
            description = "已跳过逐行比对：约 +${diff.additions} / −${diff.deletions} 行。",
        )
        diff.hunks.isEmpty() -> GpEmptyState("没有差异", "工作区内容与 HEAD 一致。")
        else -> {
            val collapsed = remember { mutableStateMapOf<Int, Boolean>() }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var cursor by remember { mutableIntStateOf(0) }

            // 折叠后每个 hunk 只占 1 个 item，跳转前要按当前折叠状态重算下标
            fun itemIndexFor(hunkIndex: Int): Int {
                var index = 0
                for (i in 0 until hunkIndex) {
                    index += if (collapsed[i] == true) 1 else 1 + diff.hunks[i].lines.size
                }
                return index
            }

            fun jumpTo(hunkIndex: Int) {
                val target = hunkIndex.coerceIn(0, diff.hunks.lastIndex)
                cursor = target
                scope.launch { listState.animateScrollToItem(itemIndexFor(target)) }
            }

            Column(Modifier.fillMaxWidth()) {
                DiffToolbar(
                    current = cursor + 1,
                    total = diff.hunks.size,
                    onPrev = { jumpTo(cursor - 1) },
                    onNext = { jumpTo(cursor + 1) },
                    onExpandAll = { collapsed.clear() },
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    diff.hunks.forEachIndexed { index, hunk ->
                        item(key = "hunk-$index") {
                            val isCollapsed = collapsed[index] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (index == cursor) palette.accent.copy(alpha = 0.16f)
                                        else palette.accent.copy(alpha = 0.08f)
                                    )
                                    .clickable { collapsed[index] = !isCollapsed }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = if (isCollapsed) "▸" else "▾",
                                    style = typography.caption.copy(fontFamily = FontFamily.Monospace),
                                    color = palette.accent,
                                )
                                Text(
                                    text = hunk.header,
                                    style = typography.caption.copy(fontFamily = FontFamily.Monospace),
                                    color = palette.accent,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                                if (isCollapsed) {
                                    Text(
                                        text = "（${hunk.lines.size} 行已折叠）",
                                        style = typography.caption,
                                        color = palette.contentSecondary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                        if (collapsed[index] != true) {
                            items(hunk.lines.size, key = { "$index-$it" }) { lineIndex ->
                                DiffRow(hunk.lines[lineIndex])
                            }
                        }
                    }
                }
            }
        }
    }
}

/** diff 工具条：上/下一处变更 + 展开全部。手机屏幕窄，放顶部比放底部更不容易被手势区挡住。 */
@Composable
private fun DiffToolbar(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExpandAll: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 $current / $total 处变更",
            style = typography.caption,
            color = palette.contentSecondary,
            modifier = Modifier.weight(1f),
        )
        GpTextButton(text = "上一处", onClick = onPrev, enabled = current > 1)
        GpTextButton(text = "下一处", onClick = onNext, enabled = current < total)
        GpTextButton(text = "展开", onClick = onExpandAll)
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val background = when (line.kind) {
        DiffLineKind.ADDED -> palette.diffAddBg
        DiffLineKind.REMOVED -> palette.diffDelBg
        DiffLineKind.CONTEXT -> palette.surface.copy(alpha = 0f)
    }
    val foreground = when (line.kind) {
        DiffLineKind.ADDED -> palette.diffAddFg
        DiffLineKind.REMOVED -> palette.diffDelFg
        DiffLineKind.CONTEXT -> palette.contentPrimary
    }
    val marker = when (line.kind) {
        DiffLineKind.ADDED -> "+"
        DiffLineKind.REMOVED -> "−"
        DiffLineKind.CONTEXT -> " "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = line.oldLineNumber?.toString().orEmpty(),
            style = typography.mono.copy(fontSize = 11.sp),
            color = palette.contentSecondary.copy(alpha = 0.6f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp).padding(end = 4.dp),
        )
        Text(
            text = line.newLineNumber?.toString().orEmpty(),
            style = typography.mono.copy(fontSize = 11.sp),
            color = palette.contentSecondary.copy(alpha = 0.6f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp).padding(end = 6.dp),
        )
        Text(
            text = marker,
            style = typography.mono,
            color = foreground,
            modifier = Modifier.width(14.dp),
        )
        Box(Modifier.weight(1f)) {
            Text(
                text = line.text.ifEmpty { " " },
                style = typography.mono,
                color = foreground,
            )
        }
    }
}
