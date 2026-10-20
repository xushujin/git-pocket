package app.gitpocket.feature.repo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.gitpocket.core.common.Formatters
import app.gitpocket.core.data.LocalRepoRepository
import app.gitpocket.core.designsystem.GpEmptyState
import app.gitpocket.core.designsystem.GpLoading
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.RepoId

@Composable
fun BrowseScreen(
    repoId: String,
    path: String,
    onBack: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val repository = LocalRepoRepository.current
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repoId, path) {
        loading = true
        runCatching { repository.entries(RepoId(repoId), path) }
            .onSuccess { entries = it; error = null }
            .onFailure { error = it.message ?: "读取目录失败" }
        loading = false
    }

    GpScreen {
        GpTopBar(
            title = path.substringAfterLast('/', missingDelimiterValue = "/"),
            subtitle = if (path.isEmpty()) "仓库根目录" else path,
            onBack = onBack,
        )

        when {
            loading -> GpLoading()
            error != null -> GpEmptyState("读取失败", error.orEmpty())
            entries.isEmpty() -> GpEmptyState("空目录", "这里还没有文件。")
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                style = typography.body,
                color = palette.contentPrimary,
            )
            Text(
                text = if (entry.isDirectory) "目录" else Formatters.bytes(entry.sizeBytes),
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }
        Text(
            text = if (entry.isDirectory) "›" else "编辑",
            style = typography.caption,
            color = palette.accent,
        )
    }
}
