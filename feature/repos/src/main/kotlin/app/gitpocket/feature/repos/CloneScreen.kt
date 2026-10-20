package app.gitpocket.feature.repos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpPrimaryButton
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTextField
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette

@Composable
fun CloneScreen(
    onBack: () -> Unit,
    onCloned: (String) -> Unit,
) {
    val repository = LocalRepoRepository.current
    val viewModel: CloneViewModel = viewModel(
        factory = viewModelFactory { initializer { CloneViewModel(repository) } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    LaunchedEffect(state.clonedRepoId) {
        state.clonedRepoId?.let(onCloned)
    }

    GpScreen {
        GpTopBar(
            title = "克隆仓库",
            subtitle = "HTTPS · GitHub / GitLab / Gitea",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            GpTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                placeholder = "https://github.com/owner/repo.git",
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            GpCard {
                Text(
                    text = "私有仓库需要 Personal Access Token",
                    style = typography.bodyStrong,
                    color = palette.contentPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "公开仓库留空即可。GitHub 建议用 fine-grained token（只读 contents 权限即可克隆）。",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
                Spacer(Modifier.height(12.dp))
                GpTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = "用户名（可留空，默认 x-access-token）",
                )
                Spacer(Modifier.height(8.dp))
                GpTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    placeholder = "Token（公开仓库可留空）",
                    isPassword = true,
                )
            }

            if (state.progress.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.progress,
                    style = typography.body,
                    color = palette.accent,
                )
            }
            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.error.orEmpty(),
                    style = typography.caption,
                    color = palette.danger,
                )
            }

            Spacer(Modifier.height(24.dp))
            GpPrimaryButton(
                text = if (state.running) "克隆中…" else "开始克隆",
                onClick = viewModel::start,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running && state.url.isNotBlank(),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "说明：克隆会把仓库完整复制到应用私有目录（不是直接操作你手机上的文件夹）。" +
                    "当前版本只拉取默认分支所在的对象集合，暂不支持断点续传。",
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }
    }
}
