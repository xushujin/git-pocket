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
import androidx.compose.foundation.layout.heightIn
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
import app.gitpocket.core.designsystem.GpDialog
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpPrimaryButton
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.GpTextField
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.git.ConflictStrategy
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
    var undoCommitTarget by remember { mutableStateOf(false) }

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
                GpTextButton(text = "分支", onClick = viewModel::openBranches)
                GpTextButton(text = "同步", onClick = viewModel::openSync)
            },
        )

        GpTabRow(
            tabs = DetailTab.entries.map { it.title },
            selectedIndex = tabIndex,
            onSelect = { tabIndex = it },
        )

        if (state.merging) {
            MergeBanner(
                conflicts = state.conflicts.size,
                onAbort = viewModel::abortMerge,
            )
        }

        state.message?.let { text ->
            MessageBanner(text = text, onDismiss = viewModel::clearMessage)
        }

        when {
            state.loading -> GpLoading()
            state.error != null -> GpEmptyState("读取失败", state.error.orEmpty())
            else -> when (tab) {
                DetailTab.CHANGES -> ChangesTab(
                    changes = state.changes,
                    conflicts = state.conflicts,
                    merging = state.merging,
                    onOpenFile = onOpenFile,
                    onStageAll = viewModel::stageAll,
                    onCommit = onOpenCommit,
                    onDiscard = { discardTarget = it },
                    onResolveOurs = { viewModel.resolve(it, ConflictStrategy.OURS) },
                    onResolveTheirs = { viewModel.resolve(it, ConflictStrategy.THEIRS) },
                    onMarkResolved = viewModel::markResolved,
                )

                DetailTab.HISTORY -> HistoryTab(
                    commits = state.commits,
                    onUndoLastCommit = { undoCommitTarget = true },
                )

                DetailTab.FILES -> FilesTab(state.entries, onOpenDirectory, onOpenFile)
            }
        }
    }

    if (state.branchState.visible) {
        BranchDialog(
            branch = state.branch,
            branchState = state.branchState,
            onNameChange = viewModel::onNewBranchNameChange,
            onCreate = viewModel::createBranch,
            onCheckout = viewModel::checkoutBranch,
            onCheckoutRemote = viewModel::checkoutRemote,
            onMerge = viewModel::mergeBranch,
            onDismiss = viewModel::closeBranches,
        )
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

    if (undoCommitTarget) {
        GpConfirmDialog(
            title = "撤销最近一次提交？",
            message = "提交会从历史里移除，改动原样回到暂存区，可以改完重新提交。已推送的提交不建议撤销。",
            confirmText = "撤销提交",
            dismissText = "取消",
            onConfirm = {
                undoCommitTarget = false
                viewModel.undoLastCommit()
            },
            onDismiss = { undoCommitTarget = false },
            onCancel = { undoCommitTarget = false },
        )
    }
}

/** 一次性提示条：操作结果 / 失败原因，点一下消失。 */
@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = typography.caption, color = palette.accent)
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
    conflicts: List<String>,
    merging: Boolean,
    onOpenFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onCommit: () -> Unit,
    onDiscard: (String) -> Unit,
    onResolveOurs: (String) -> Unit,
    onResolveTheirs: (String) -> Unit,
    onMarkResolved: (String) -> Unit,
) {
    if (changes.isEmpty()) {
        // 合并时"没有变更"其实是"冲突都解决完了、等着提交合并提交"，
        // 直接给空态会把用户堵在这里（没有去提交的入口）。
        if (merging) {
            GpEmptyState(
                title = "合并已就绪",
                description = "冲突都解决了，提交后会生成一个合并提交（两个父提交）。",
                actionLabel = "提交这次合并",
                onAction = onCommit,
            )
        } else {
            GpEmptyState(
                title = "工作区干净",
                description = "没有未提交的改动。去「文件」里改点什么吧。",
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(changes, key = { "${it.path}-${it.staged}-${it.type}" }) { change ->
            ChangeRow(
                change = change,
                onClick = { onOpenFile(change.path) },
                onDiscard = { onDiscard(change.path) },
                onResolveOurs = { onResolveOurs(change.path) },
                onResolveTheirs = { onResolveTheirs(change.path) },
                onMarkResolved = { onMarkResolved(change.path) },
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
            enabled = conflicts.isEmpty(),
        )
        GpPrimaryButton(
            text = if (conflicts.isEmpty()) "去提交" else "先解决冲突",
            onClick = onCommit,
            modifier = Modifier.weight(1f),
            enabled = conflicts.isEmpty(),
        )
    }
}

@Composable
private fun ChangeRow(
    change: FileChange,
    onClick: () -> Unit,
    onDiscard: () -> Unit,
    onResolveOurs: () -> Unit,
    onResolveTheirs: () -> Unit,
    onMarkResolved: () -> Unit,
) {
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
                    text = when {
                        change.type == ChangeType.CONFLICT -> "冲突 · 需要选择保留哪一边"
                        change.staged -> "已暂存"
                        else -> "未暂存"
                    },
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
            if (change.type != ChangeType.CONFLICT) {
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

        if (change.type == ChangeType.CONFLICT) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GpTextButton(text = "用我的", onClick = onResolveOurs)
                GpTextButton(text = "用对方的", onClick = onResolveTheirs)
                GpTextButton(text = "已改好", onClick = onMarkResolved)
            }
        }
    }
}

/** 合并未完成的提示条：明确告诉用户"还有几个冲突"，并给放弃入口。 */
@Composable
private fun MergeBanner(conflicts: Int, onAbort: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.danger.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (conflicts > 0) "合并中：还有 $conflicts 个冲突" else "合并中：冲突已解决，可以提交",
            style = typography.caption,
            color = palette.danger,
            modifier = Modifier.weight(1f),
        )
        GpTextButton(text = "放弃合并", onClick = onAbort)
    }
}

@Composable
private fun ColumnScope.HistoryTab(
    commits: List<app.gitpocket.core.model.CommitSummary>,
    onUndoLastCommit: () -> Unit,
) {
    if (commits.isEmpty()) {
        GpEmptyState("还没有提交", "提交第一个改动后，这里会显示历史。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "undo-last-commit") {
            val palette = LocalNeumorphicPalette.current
            val typography = LocalGitPocketTypography.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "撤销上次提交",
                    style = typography.caption,
                    color = palette.danger,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onUndoLastCommit)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
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
 * 分支弹窗：本地分支切换 + 新建分支 + 从远端建本地分支。
 *
 * 切换前引擎会检查工作区是否干净，脏工作区直接拒绝并给出原因，
 * 不做"自动 stash"（手机上看不见 stash，出了事用户找不回来）。
 */
@Composable
private fun BranchDialog(
    branch: String?,
    branchState: BranchState,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onCheckout: (String) -> Unit,
    onCheckoutRemote: (String) -> Unit,
    onMerge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    GpDialog(
        title = "分支",
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭", style = typography.bodyStrong, color = palette.accent)
            }
        },
    ) {
        Column {
            Text(
                text = branch?.let { "当前 · $it" } ?: "当前分支未知",
                style = typography.caption,
                color = palette.contentSecondary,
            )
            Spacer(Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(branchState.local, key = { "l-${it.name}" }) { item ->
                    BranchRow(
                        name = item.name,
                        selected = item.isCurrent,
                        enabled = !branchState.busy,
                        onClick = { onCheckout(item.name) },
                        onMerge = if (item.isCurrent) null else ({ onMerge(item.name) }),
                    )
                }
                if (branchState.remote.isNotEmpty()) {
                    item(key = "remote-header") {
                        Text(
                            text = "远端分支（点击建本地分支）",
                            style = typography.caption,
                            color = palette.contentSecondary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(branchState.remote, key = { "r-${it.name}" }) { item ->
                        BranchRow(
                            name = item.name,
                            selected = false,
                            enabled = !branchState.busy,
                            onClick = { onCheckoutRemote(item.name) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            GpTextField(
                value = branchState.newName,
                onValueChange = onNameChange,
                placeholder = "新分支名，例如 feature/login",
            )
            Spacer(Modifier.height(8.dp))
            GpTextButton(
                text = "新建并切换",
                enabled = !branchState.busy,
                onClick = onCreate,
            )
            branchState.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = typography.caption, color = palette.danger)
            }
        }
    }
}

@Composable
private fun BranchRow(
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onMerge: (() -> Unit)? = null,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = if (selected) typography.bodyStrong else typography.body,
            color = if (selected) palette.accent else palette.contentPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (selected) {
            Text("当前", style = typography.caption, color = palette.accent)
        } else if (onMerge != null) {
            // 合并进当前分支：不进这个分支也能合，手机上来回切换代价太高
            Text(
                text = "合并",
                style = typography.caption,
                color = palette.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onMerge)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 同步弹窗：拉取 / 推送。
 *
 * 拉取 = fetch + 合并：能快进就快进，分叉时走三方合并；
 * 合并出冲突不静默失败，而是回到「变更」页逐个解决。
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

    GpDialog(
        title = "同步",
        onDismissRequest = { if (!sync.running) onDismiss() },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !sync.running) {
                Text("关闭", style = typography.bodyStrong, color = palette.accent)
            }
        },
    ) {
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
    }
}
