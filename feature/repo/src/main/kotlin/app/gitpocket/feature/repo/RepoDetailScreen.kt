package app.gitpocket.feature.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.common.Formatters
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.designsystem.GpBadge
import app.gitpocket.core.designsystem.FileTypeIcon
import app.gitpocket.core.designsystem.FileTypeIcon
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpConfirmDialog
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpPrimaryButton
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.ChangeType
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.RepoId

private enum class DetailTab(val title: String) { CHANGES("变更"), HISTORY("历史"), FILES("文件") }

@Composable
fun RepoDetailScreen(
    repoId: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenCommit: () -> Unit,
    onOpenDirectory: (String) -> Unit,
) {
    val repository = LocalRepoRepository.current
    val id = remember(repoId) { RepoId(repoId) }
    val viewModel: RepoDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { RepoDetailViewModel(id, repository) } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 用 rememberSaveable：从编辑器返回时不会重置到「变更」页（NavHost 会保存该状态）
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = DetailTab.entries[tabIndex]
    var discardTarget by remember { mutableStateOf<String?>(null) }

    // 从编辑器 / 提交页返回时静默刷新，否则会一直显示进入时的旧状态
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(silent = true)
        onPauseOrDispose { }
    }

    GpScreen {
        GpTopBar(
            title = state.repo?.name ?: "仓库",
            subtitle = state.branch?.let { "当前分支 · $it" },
            onBack = onBack,
            actions = {
                if (state.changes.isNotEmpty()) {
                    GpBadge("${state.changes.size} 变更")
                }
                GpTextButton(text = "同步", onClick = viewModel::openSync)
            },
        )

        GpTabRow(
            tabs = DetailTab.entries.map { it.title },
            selectedIndex = tabIndex,
            onSelect = { tabIndex = it },
        )

        when {
            state.loading -> GpLoading()
            state.error != null -> GpEmptyState("读取失败", state.error.orEmpty())
            else -> when (tab) {
                DetailTab.CHANGES -> ChangesTab(
                    changes = state.changes,
                    onOpenFile = onOpenFile,
                    onStageAll = viewModel::stageAll,
                    onCommit = onOpenCommit,
                    onDiscard = { discardTarget = it },
                )

                DetailTab.HISTORY -> HistoryTab(state.commits)
                DetailTab.FILES -> FilesTab(state.entries, onOpenDirectory, onOpenFile)
            }
        }
    }

    if (state.sync.visible) {
        SyncDialog(
            sync = state.sync,
            branch = state.branch,
            onPull = { viewModel.runSync(SyncAction.PULL) },
            onPush = { viewModel.runSync(SyncAction.PUSH) },
            onDismiss = viewModel::closeSync,
        )
    }

    discardTarget?.let { path ->
        GpConfirmDialog(
            title = "撤销这个文件的修改？",
            message = "$path 会恢复到上次提交的版本；如果是新增文件则直接删除。此操作不可撤销。",
            confirmText = "撤销修改",
            dismissText = "取消",
            onConfirm = {
                val target = path
                discardTarget = null
                viewModel.discard(target)
            },
            onDismiss = { discardTarget = null },
            onCancel = { discardTarget = null },
        )
    }
}

@Composable
private fun GpTabRow(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) palette.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = typography.bodyStrong,
                    color = if (selected) palette.accent else palette.contentSecondary,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ChangesTab(
    changes: List<FileChange>,
    onOpenFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onCommit: () -> Unit,
    onDiscard: (String) -> Unit,
) {
    if (changes.isEmpty()) {
        GpEmptyState(
            title = "工作区干净",
            description = "没有未提交的改动。去「文件」里改点什么吧。",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(changes, key = { "${it.path}-${it.staged}" }) { change ->
            ChangeRow(
                change = change,
                onClick = { onOpenFile(change.path) },
                onDiscard = { onDiscard(change.path) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GpPrimaryButton(
            text = "全部暂存",
            onClick = onStageAll,
            modifier = Modifier.weight(1f),
        )
        GpPrimaryButton(
            text = "去提交",
            onClick = onCommit,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChangeRow(change: FileChange, onClick: () -> Unit, onDiscard: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val letter = when (change.type) {
        ChangeType.ADDED -> "A"
        ChangeType.MODIFIED -> "M"
        ChangeType.DELETED -> "D"
        ChangeType.RENAMED -> "R"
        ChangeType.UNTRACKED -> "U"
        ChangeType.CONFLICT -> "!"
    }
    val color = when (change.type) {
        ChangeType.ADDED, ChangeType.UNTRACKED -> palette.success
        ChangeType.DELETED, ChangeType.CONFLICT -> palette.danger
        else -> palette.accent
    }
    GpCard(onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(letter, style = typography.caption, color = color)
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(change.path, style = typography.body, color = palette.contentPrimary)
                Text(
                    text = if (change.staged) "已暂存" else "未暂存",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
            Text(
                text = "撤销",
                style = typography.caption,
                color = palette.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDiscard)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.HistoryTab(commits: List<app.gitpocket.core.model.CommitSummary>) {
    if (commits.isEmpty()) {
        GpEmptyState("还没有提交", "提交第一个改动后，这里会显示历史。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(commits, key = { it.oid.hex }) { commit ->
            val palette = LocalNeumorphicPalette.current
            val typography = LocalGitPocketTypography.current
            GpCard(padding = 12.dp) {
                Text(commit.subject, style = typography.bodyStrong, color = palette.contentPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${commit.authorName} · ${Formatters.relativeTime(commit.timestampSeconds)} · ${commit.oid.short}",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FilesTab(
    entries: List<app.gitpocket.core.model.FileEntry>,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        GpEmptyState("空目录", "这个目录下没有文件。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            val palette = LocalNeumorphicPalette.current
            val typography = LocalGitPocketTypography.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry.path)
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileTypeIcon(isDirectory = entry.isDirectory)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.name,
                    style = typography.body,
                    color = palette.contentPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (entry.isDirectory) "" else Formatters.bytes(entry.sizeBytes),
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
        }
    }
}

/**
 * 同步弹窗：拉取 / 推送。
 *
 * 刻意只做这两个动作：手机上没有冲突解决 UI，真正的 merge 做不了，
 * 所以 pull 只做快进；推不上去时明确告诉用户"先拉取"。
 */
@Composable
private fun SyncDialog(
    sync: SyncState,
    branch: String?,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!sync.running) onDismiss() },
        containerColor = palette.base,
        title = { Text("同步", style = typography.title, color = palette.contentPrimary) },
        text = {
            Column {
                Text(
                    text = branch?.let { "当前分支 · $it" } ?: "当前分支未知",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GpTextButton(
                        text = "拉取",
                        enabled = !sync.running,
                        onClick = onPull,
                    )
                    GpTextButton(
                        text = "推送",
                        enabled = !sync.running,
                        onClick = onPush,
                    )
                }

                if (sync.running) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${sync.action?.label.orEmpty()}中 · ${sync.progress}",
                        style = typography.caption,
                        color = palette.accent,
                    )
                }
                sync.result?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = typography.body, color = palette.contentPrimary)
                }
                sync.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = typography.body, color = palette.danger)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !sync.running) {
                Text("关闭", style = typography.bodyStrong, color = palette.accent)
            }
        },
    )
}
