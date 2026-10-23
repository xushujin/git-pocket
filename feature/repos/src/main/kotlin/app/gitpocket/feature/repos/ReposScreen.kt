package app.gitpocket.feature.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.common.Formatters
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.designsystem.GpBadge
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpExtendedFab
import app.gitpocket.core.designsystem.GpIconButton
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.Repo

@Composable
fun ReposScreen(
    onOpenRepo: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClone: () -> Unit,
) {
    val repository = LocalRepoRepository.current
    val viewModel: ReposViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ReposViewModel(repository) }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    GpScreen {
        GpTopBar(
            title = "GitPocket",
            subtitle = "口袋里的 Git 管理器",
            actions = {
                GpIconButton(Icons.Default.Settings, "设置", onOpenSettings)
            },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.loading -> GpLoading("正在准备本地仓库…")
                state.error != null -> GpEmptyState(
                    title = "加载失败",
                    description = state.error.orEmpty(),
                )

                state.repos.isEmpty() -> GpEmptyState(
                    title = "还没有仓库",
                    description = "用 HTTPS 地址克隆一个仓库开始（公开仓库无需 token）。",
                    actionLabel = "克隆仓库",
                    onAction = onOpenClone,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.repos, key = { it.id.value }) { repo ->
                        RepoCard(
                            repo = repo,
                            onClick = {
                                // 记录"最近打开"，列表按 lastOpenedAt 排序才名副其实
                                viewModel.open(repo)
                                onOpenRepo(repo.id.value)
                            },
                        )
                    }
                }
            }

            if (!state.loading && state.error == null && state.repos.isNotEmpty()) {
                GpExtendedFab(
                    text = "克隆仓库",
                    onClick = onOpenClone,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun RepoCard(repo: Repo, onClick: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    GpCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = repo.name,
                    style = typography.title,
                    color = palette.contentPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${repo.owner} · ${Formatters.relativeTime(repo.lastOpenedAt / 1000)}",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
            GpBadge(text = repo.defaultBranch ?: "main")
        }
    }
}
