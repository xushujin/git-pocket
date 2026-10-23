package app.gitpocket.feature.commit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.data.LocalSettingsRepository
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpPrimaryButton
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpSectionHeader
import app.gitpocket.core.designsystem.GpTextField
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.ChangeType
import app.gitpocket.core.model.RepoId

@Composable
fun CommitScreen(
    repoId: String,
    onBack: () -> Unit,
    onCommitted: () -> Unit,
) {
    val repository = LocalRepoRepository.current
    val settings = LocalSettingsRepository.current
    val id = remember(repoId) { RepoId(repoId) }
    val viewModel: CommitViewModel = viewModel(
        factory = viewModelFactory { initializer { CommitViewModel(id, repository, settings) } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    LaunchedEffect(state.committedOid) {
        if (state.committedOid != null) onCommitted()
    }

    GpScreen {
        GpTopBar(
            title = "提交",
            subtitle = when {
                state.conflicts.isNotEmpty() -> "还有 ${state.conflicts.size} 个冲突未解决"
                state.merging -> "合并提交（两个父提交）"
                state.changes.isEmpty() -> "没有待提交的改动"
                else -> "${state.changes.size} 个文件"
            },
            onBack = onBack,
        )

        when {
            state.loading -> GpLoading()
            // 合并提交即使没有任何文件差异也要能提交（树可能等于当前分支）
            state.changes.isEmpty() && !state.merging && state.committedOid == null -> GpEmptyState(
                title = "工作区干净",
                description = "先去编辑一个文件，再回来提交。",
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.changes, key = { "${it.path}-${it.staged}" }) { change ->
                        GpCard(padding = 12.dp) {
                            Row {
                                Text(
                                    text = change.path,
                                    style = typography.body,
                                    color = palette.contentPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = when (change.type) {
                                        ChangeType.ADDED, ChangeType.UNTRACKED -> "新增"
                                        ChangeType.DELETED -> "删除"
                                        ChangeType.CONFLICT -> "冲突"
                                        else -> "修改"
                                    },
                                    style = typography.caption,
                                    color = palette.contentSecondary,
                                )
                            }
                        }
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    GpSectionHeader("提交信息")
                    GpTextField(
                        value = state.message,
                        onValueChange = viewModel::onMessageChange,
                        placeholder = "例如：fix: 修正首页标题",
                        singleLine = false,
                        minLines = 3,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "作者：${state.authorName.ifBlank { "GitPocket User" }} <${state.authorEmail.ifBlank { "user@gitpocket.app" }}>" +
                            if (state.authorName.isBlank()) "（可在设置里修改）" else "",
                        style = typography.caption,
                        color = palette.contentSecondary,
                    )
                    if (state.conflicts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "冲突文件：${state.conflicts.joinToString("、")}",
                            style = typography.caption,
                            color = palette.danger,
                        )
                    }
                    if (state.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.error.orEmpty(),
                            style = typography.caption,
                            color = palette.danger,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GpPrimaryButton(
                        text = when {
                            state.committing -> "提交中…"
                            state.merging && state.changes.isEmpty() -> "提交合并"
                            else -> "提交 ${state.changes.size} 个文件"
                        },
                        onClick = viewModel::commit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.committing && state.message.isNotBlank() && state.conflicts.isEmpty(),
                    )
                }
            }
        }
    }
}
