package app.gitpocket.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpDialog
import app.gitpocket.core.designsystem.GpPrimaryButton
import app.gitpocket.core.designsystem.GpProgressBar
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpSectionHeader
import app.gitpocket.core.designsystem.GpTextButton
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette
import app.gitpocket.core.update.UpdateRepository

@Composable
fun AboutScreen(appVersion: String, updater: UpdateRepository, onBack: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    val viewModel: UpdateViewModel = viewModel(
        factory = viewModelFactory {
            initializer { UpdateViewModel(updater, appVersion) }
        }
    )
    val update by viewModel.state.collectAsStateWithLifecycle()

    GpScreen {
        GpTopBar(title = "关于", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            GpCard {
                Text("GitPocket", style = typography.display, color = palette.contentPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "版本 $appVersion",
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Git 装进口袋，改动随手落地。",
                    style = typography.body,
                    color = palette.accent,
                )
            }

            GpSectionHeader("更新")
            GpCard {
                UpdatePanel(
                    appVersion = appVersion,
                    state = update,
                    onCheck = viewModel::check,
                    onDownload = viewModel::download,
                    onCancel = viewModel::cancelDownload,
                    onInstall = viewModel::installPending,
                    onOpenPermission = viewModel::openInstallPermissionSettings,
                )
            }

            GpSectionHeader("技术栈")
            GpCard {
                AboutLine("语言", "Kotlin 2.0")
                AboutLine("界面", "Jetpack Compose + Material 3（无第三方 UI 库）")
                AboutLine("架构", "MVVM + 单向数据流，模块化分层")
                AboutLine("Git 引擎", "纯 Kotlin 自研（对象库 / 索引 / packfile / diff），未使用 JGit 或 libgit2")
                AboutLine("存储", "Room + DataStore + AndroidKeyStore")
            }

            GpSectionHeader("支持范围")
            GpCard {
                AboutLine("已支持", "本地仓库：浏览、编辑、暂存、提交、查看 diff 与历史")
                AboutLine("已支持", "Git 对象：loose object、packfile v2（含 ofs/ref delta）、index v2/v3")
                AboutLine("已支持", "HTTPS 克隆：公开仓库与 PAT 私有仓库")
                AboutLine("已支持", "HTTPS 拉取 / 推送：PAT 需 repo 写权限")
                AboutLine("已支持", "分支：查看、切换、新建、直接合并进当前分支；工作区脏时拒绝切换")
                AboutLine("已支持", "三方合并：拉取自动合并，冲突可选我的 / 对方的 / 手动改")
                AboutLine("暂不支持", "SSH、Git LFS、submodule（只登记 gitlink，不 init/update）、SHA-256 仓库、force push、rebase")
                AboutLine("暂不支持", "重命名检测：改名会被当成删除 + 新增")
            }

            GpSectionHeader("权限说明")
            GpCard {
                AboutLine("INTERNET", "克隆 / 拉取 / 推送、检查更新时访问远端")
                AboutLine("ACCESS_NETWORK_STATE", "随 INTERNET 一并声明，用于系统网络状态判断")
                AboutLine("USE_BIOMETRIC", "启动与界面重建时校验指纹 / 面容 / 锁屏密码（可选，普通权限）")
                AboutLine("REQUEST_INSTALL_PACKAGES", "应用内更新：下载完成后交给系统安装器（仍需你手动确认安装）")
                AboutLine("不申请", "存储读写、通讯录、位置、相机、短信等权限")
            }

            GpSectionHeader("隐私")
            GpCard {
                Text(
                    text = "不收集任何个人数据，不集成第三方统计或广告 SDK。\n" +
                        "仓库数据与草稿只保存在本机应用私有目录；token 由 AndroidKeyStore 加密后存储，不上传。\n" +
                        "检查更新只请求 GitHub 的公开 Release 接口，不携带任何账号信息。",
                    style = typography.body,
                    color = palette.contentSecondary,
                )
            }

            GpSectionHeader("许可证")
            GpCard {
                AboutLine("Apache License 2.0", "Copyright 2026 GitPocket contributors")
            }
        }
    }

    val info = update.info
    if (update.dialogVisible && info != null) {
        GpDialog(
            title = "发现新版本 ${info.versionName}",
            onDismissRequest = viewModel::dismissDialog,
            dismissButton = {
                GpTextButton("稍后", viewModel::dismissDialog)
            },
            confirmButton = {
                GpPrimaryButton("下载并安装", viewModel::download)
            },
        ) {
            Text(
                text = "当前版本 $appVersion → ${info.versionName}" +
                    if (info.apkSize > 0) " · ${Formatters.bytes(info.apkSize)}" else "",
                style = typography.bodyStrong,
                color = palette.contentPrimary,
            )
            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = info.notes,
                    style = typography.caption,
                    color = palette.contentSecondary,
                )
            }
        }
    }
}

/** 更新卡片：按状态机渲染一行按钮 + 一行状态，下载时多一条进度条。 */
@Composable
private fun UpdatePanel(
    appVersion: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val info = state.info

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("当前版本", style = typography.caption, color = palette.contentSecondary)
            Text(appVersion, style = typography.bodyStrong, color = palette.contentPrimary)
        }
        when (state.phase) {
            UpdatePhase.Checking -> Text(
                "正在检查…",
                style = typography.caption,
                color = palette.contentSecondary,
            )

            UpdatePhase.Downloading -> GpTextButton("取消", onCancel)
            UpdatePhase.Installing -> Text(
                "正在校验…",
                style = typography.caption,
                color = palette.contentSecondary,
            )

            UpdatePhase.Available -> GpPrimaryButton("下载并安装", onDownload)
            UpdatePhase.NeedsPermission -> GpPrimaryButton("立即安装", onInstall)
            // 安装器被用户中途取消后，包还在缓存里，给一个重新打开的入口
            UpdatePhase.ReadyToInstall -> GpTextButton("重新打开安装器", onInstall)

            else -> GpTextButton("检查更新", onCheck)
        }
    }

    if (info != null && state.phase != UpdatePhase.UpToDate) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "最新版本 ${info.versionName}",
            style = typography.caption,
            color = palette.accent,
        )
    }

    when (state.phase) {
        UpdatePhase.Downloading -> {
            Spacer(Modifier.height(10.dp))
            GpProgressBar(progress = state.progress)
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("已下载 ").append(Formatters.bytes(state.downloadedBytes))
                    if (state.totalBytes > 0) {
                        append(" / ").append(Formatters.bytes(state.totalBytes))
                        append("（").append((state.progress * 100).toInt()).append("%）")
                    }
                },
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }

        UpdatePhase.Installing -> {
            Spacer(Modifier.height(10.dp))
            GpProgressBar(progress = 1f)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "下载完成，正在校验安装包…",
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }

        else -> Unit
    }

    val note = state.error ?: state.message
    if (note != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = note,
            style = typography.caption,
            color = if (state.error != null) palette.danger else palette.contentSecondary,
        )
    }

    if (state.phase == UpdatePhase.NeedsPermission) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GpTextButton("去设置允许安装", onOpenPermission)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "允许后回到这里点「立即安装」",
                style = typography.caption,
                color = palette.contentSecondary,
            )
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = typography.caption, color = palette.contentSecondary)
        Text(value, style = typography.body, color = palette.contentPrimary)
    }
}
