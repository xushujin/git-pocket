package app.gitpocket.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gitpocket.core.designsystem.GpCard
import app.gitpocket.core.designsystem.GpScreen
import app.gitpocket.core.designsystem.GpSectionHeader
import app.gitpocket.core.designsystem.GpTopBar
import app.gitpocket.core.designsystem.LocalGitPocketTypography
import app.gitpocket.core.designsystem.LocalNeumorphicPalette

@Composable
fun AboutScreen(appVersion: String, onBack: () -> Unit) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

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
                AboutLine("暂不支持", "SSH、Git LFS、submodule、SHA-256 仓库、force push、rebase")
                AboutLine("暂不支持", "重命名检测：改名会被当成删除 + 新增")
            }

            GpSectionHeader("权限说明")
            GpCard {
                AboutLine("INTERNET", "克隆仓库时访问远端")
                AboutLine("ACCESS_NETWORK_STATE", "判断在线 / 离线，弱网提示")
                AboutLine("USE_BIOMETRIC", "冷启动校验指纹 / 面容 / 锁屏密码（可选，普通权限）")
                AboutLine("不申请", "存储读写、通讯录、位置、相机、短信等权限")
            }

            GpSectionHeader("隐私")
            GpCard {
                Text(
                    text = "不收集任何个人数据，不集成第三方统计或广告 SDK。\n" +
                        "仓库数据与草稿只保存在本机应用私有目录；token 由 AndroidKeyStore 加密后存储，不上传。",
                    style = typography.body,
                    color = palette.contentSecondary,
                )
            }
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
