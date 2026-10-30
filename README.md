# GitPocket

**Git 装进口袋，改动随手落地。**

一个 Android 原生、轻量、适合碎片化操作的 Git 管理器 + 轻编辑器。不是手机上的 VS Code。

---

## 能力清单（v1.3.0）

本地 Git 内核、完整界面、HTTPS 克隆 / 拉取 / 推送、分支切换、三方合并与冲突解决均已打通。

| 能力 | 状态 | 说明 |
|---|---|---|
| Git 对象库（loose object 读写、SHA-1 校验） | ✅ 已实现 | 与 `git hash-object` / `git cat-file` 对拍 |
| packfile 读取（idx v2、ofs/ref delta） | ✅ 已实现 | 真实 `git gc` 产物验证，含校验和 |
| index v2/v3 读写 | ✅ 已实现 | 写回后 `git status` 干净 |
| commit / tree 序列化 | ✅ 已实现 | `git fsck --strict` 通过 |
| 工作区状态（含 racy-clean 处理） | ✅ 已实现 | 与 `git status --porcelain` 一致 |
| 行级 diff（Myers） | ✅ 已实现 | 大文件降级；支持折叠与上/下一处跳转 |
| 仓库浏览 / 文件树 / 编辑器 / 草稿 | ✅ 已实现 | 默认预览，点「编辑」才可写；>512KB 只读；保存后自动留底（草稿仅记录已保存内容） |
| **语法高亮**（Kotlin/Java/JS/TS/JSON/XML/Python/Shell/Markdown） | ✅ 已实现 | 自研词法器，无第三方库 |
| **Markdown 预览**（标题/列表/引用/表格/代码块/行内样式） | ✅ 已实现 | 原生 Compose 渲染，非 WebView |
| **手动保存 + 还原** | ✅ 已实现 | 默认不自动保存；离开前确认，可还原到上次保存 |
| **回退 / 重做** | ✅ 已实现 | 按输入段落回退，连续输入合并为一步 |
| **生物识别解锁** | ✅ 已实现 | 冷启动校验指纹 / 面容 / 锁屏密码，可关闭 |
| **按文件撤销变更** | ✅ 已实现 | 变更列表逐条撤销，恢复到 HEAD |
| **分支切换 / 新建分支** | ✅ 已实现 | 工作区有未提交改动时明确拒绝，不做静默 stash |
| 暂存 / 提交 | ✅ 已实现 | 提交后 `git log` 可读；可在历史页撤销上次提交 |
| 主题系统（6 套配色，拟态） | ✅ 已实现 | 全局即时生效 |
| 设置 / 关于页 | ✅ 已实现 | 设置项走注册表 |
| **clone（HTTPS + PAT）** | ✅ 已实现 | smart HTTP v0，真实 GitHub 仓库验证 |
| **fetch / pull / push（HTTPS）** | ✅ 已实现 | 用真实 `git http-backend` 做端到端对拍 |
| **三方合并 + 冲突解决** | ✅ 已实现 | 行级 diff3；index 写 stage 1/2/3，可选我的 / 对方的 / 手动改 |
| **应用内在线更新** | ✅ 已实现 | 查 GitHub Release → 下载（带进度）→ 校验 SHA-256 与签名 → 交给系统安装器 |

## 支持范围

- 已支持：HTTPS 克隆 / 拉取 / 推送（公开仓库 + PAT 私有仓库）、本地仓库的浏览、编辑、暂存、提交、diff、历史、
  分支切换与新建、从远端分支建本地分支、分叉后的三方合并与冲突解决。
- 未支持：SSH、Git LFS、submodule（只登记 gitlink，不 init / update）、SHA-256 仓库、force push、rebase、
  rename detection（改名 = 删除 + 新增）。
- 拉取/合并前要求工作区干净：有未提交改动时**直接拒绝**，不会覆盖你的修改（未跟踪文件不拦）。

## 分支与合并

- 仓库详情页右上角 **「分支」**：列出本地分支与远端跟踪分支，点本地分支即切换，点远端分支会**以远端那个提交为起点**
  建同名本地分支并切过去；输入名字可新建分支。切换前要求工作区干净（未跟踪文件不拦），不做自动 stash。
- 分支列表里每个非当前分支右侧有 **「合并」**：把该分支合进当前分支，不用先切过去。
- **「同步」→ 拉取** = fetch + 合并：能快进就快进，分叉时自动三方合并，并产生一个合并提交。
  工作区有未提交改动时会直接拒绝（不会覆盖），先提交或撤销再拉。
- 合并出冲突时不静默失败：变更列表里冲突文件标红「!」，可一键「用我的 / 用对方的」，也可以进编辑器手动改完点「已改好」；
  全部解决后变更页会变成「合并已就绪」，点「提交这次合并」即可（此时产生两个父提交的 merge commit）。
  顶部提示条可「放弃合并」，工作区回到合并前。
- 历史页右上角「撤销上次提交」= soft reset：提交从历史移除，改动回到暂存区。

## 添加自己的仓库

1. 首页右下角 **「+ 克隆仓库」** 按钮；
2. 填 HTTPS 地址，例如 `https://github.com/owner/repo.git`；
3. 公开仓库直接点「开始克隆」；私有仓库补 Token（GitHub 用 fine-grained PAT，contents 只读即可）；
4. 克隆完成后会自动进入仓库，之后可离线浏览、编辑、提交。

> 仓库会被完整复制到应用私有目录（`files/repos/<owner>-<name>-<id>`），
> 不是直接操作手机上的文件夹。

## 环境要求

- JDK 21
- Android SDK：platform 36、build-tools 36.0.0
- Gradle 8.11.1（随 wrapper 提供）

## 本地开发流程

```bash
git clone <repo> git-pocket && cd git-pocket

# 1. 指向本机 Android SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2. 编译 debug 包
./gradlew :app:assembleDebug

# 3. 跑 Git 内核单测（需要本机有 git，用于真实文件对拍）
./gradlew :core:git:test

# 4. 安装到设备
./gradlew :app:installDebug
```

本地构建不需要签名配置；**只有 release 构建需要**。

## 构建流程

| 任务 | 命令 | 产物 |
|---|---|---|
| Debug | `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/GitPocket-debug.apk` |
| Release APK | `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/GitPocket-<版本>.apk` |
| Release AAB（可选，仅上架 Play 需要） | `./gradlew :app:bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` |
| 全量校验 | `./gradlew test lint assembleDebug` | 测试报告 + lint 报告 |
| **验收包** | `./scripts/build-release.sh` | `GitPocket-<版本>.apk` + 版本号/签名/SHA-256 一览 |

## 交付约定（重要）

**日常验收一律给 release 包**，不给 debug 包。

```bash
./scripts/build-release.sh
```

脚本会打印版本号、路径、大小、SHA-256、签名指纹和 mapping 路径。

- 版本号：`versionName` 由 tag 决定（`v1.0.0` → `1.0.0`），无 tag 时为 `0.1.0-dev.<yyMMdd.HHmm>`；
  `versionCode` 一律取构建时间戳 `yyMMddHH`，保证任何包都能覆盖安装（tag 派生的小数字会低于开发包）。
- 签名：本机 keystore 在 `~/.gitpocket/keys/gitpocket-release.jks`（不进版本库）。
  **换 key 就必须卸载重装**，所以这个文件务必备份。
- 崩溃日志：release 开了 R8，堆栈需用 `app/build/outputs/mapping/release/mapping.txt` 还原。
  Release 里上传的是 `mapping.txt.gz`（26MB → 2MB），用前先 `gunzip mapping.txt.gz`。
  报问题时请带上版本号（设置 → 关于 里能看到）。

## 签名流程

两条通道，**同一份解析实现**（`app/build.gradle.kts` 的 `releaseSigningProps`）。

### 通道 1：本地 `keystore.properties`

```bash
cp keystore.properties.example keystore.properties
# 编辑并填入真实路径与密码（该文件已在 .gitignore 中）
```

本机当前使用 `~/.gitpocket/keys/gitpocket-release.jks`（与已交付的验收包同一把 key）。

```properties
storeFile=/absolute/path/to/release.jks
storePassword=******
keyAlias=gitpocket
keyPassword=******
```

### 通道 2：CI 环境变量

| 变量 | 含义 |
|---|---|
| `GITPOCKET_KEYSTORE_FILE` | keystore 文件绝对路径（CI 中由 secret 解码到临时目录） |
| `GITPOCKET_STORE_PASSWORD` | keystore 密码 |
| `GITPOCKET_KEY_ALIAS` | key 别名 |
| `GITPOCKET_KEY_PASSWORD` | key 密码 |

> **缺签名时 release 构建会直接失败**，不会静默产出未签名包或回落 debug 签名。

## 发版流程

1. 本地跑 `./gradlew test lintDebug`；
2. 更新 `CHANGELOG.md`；
3. 打 tag 并推送：
   ```bash
   git tag -a v1.2.0 -m "GitPocket v1.2.0" && git push origin v1.2.0
   ```
4. CI 自动执行：单测 + lint → 签名构建 → `apksigner verify` 校验证书 → 创建 GitHub Release；
5. 人工检查 Release 资产：`GitPocket-<版本>.apk`、`GitPocket-<版本>.apk.sha256`、`mapping.txt.gz`；
6. 手动上架（首版不做自动上架，避免误发）。

`versionName` 由 tag 决定（`v1.2.3` → `1.2.3`）：`scripts/build-release.sh` 在没显式传 `GIT_TAG` 时会取
当前提交上的 tag（`git describe --tags --exact-match`），都没有才回落到 `0.1.0-dev.<yyMMdd.HHmm>`；
`versionCode` 一律取构建时间戳 `yyMMddHH`，保证任何包都能覆盖安装。

## CI 流程

| 工作流 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/release.yml` | **推送 tag `v*.*.*`** | 单测 + lint、签名构建、签名校验、自动创建 Release |
| `.github/workflows/verify.yml` | 推 `main` / `develop`、PR、手动 | 单元测试、lint（不产出 APK） |

出包仍然**只由 tag 触发**，日常提交不会产出安装包。

## CI 配置（GitHub Actions Secrets）

仓库 → `Settings` → `Secrets and variables` → `Actions` → `New repository secret`，逐个添加：

| Secret 名称 | 值 | 用途 |
|---|---|---|
| `KEYSTORE_BASE64` | 本机 keystore 的 base64（见下方命令） | 签名证书 |
| `STORE_PASSWORD` | `testpass`（当前值，建议更换） | keystore 密码 |
| `KEY_ALIAS` | `gitpocket` | key 别名 |
| `KEY_PASSWORD` | `testpass`（当前值，建议更换） | key 密码 |

生成 `KEYSTORE_BASE64` 并复制到剪贴板：

```bash
base64 -i ~/.gitpocket/keys/gitpocket-release.jks | tr -d '\n' | pbcopy
```

`GITHUB_TOKEN` 由 Actions 自动注入，**不需要配置**；工作流里已声明
`permissions: contents: write`，用于创建 Release。

> 换密码不会改变签名指纹（仍是同一张证书），但改完要同步更新 `keystore.properties`
> 和上面的 `STORE_PASSWORD` / `KEY_PASSWORD`。

## 应用内更新

入口：**设置 → 关于 → 更新**。

流程与取舍：

1. `GET https://api.github.com/repos/xushujin/git-pocket/releases/latest`，按 `versionName` 比较（只比数字段，
   `1.10.0 > 1.9.9`）；草稿与预发布不提示，匿名限流（60 次/小时）命中时给明确文案。
2. 下载 `GitPocket-<版本>.apk` 到 `cacheDir/updates/`，边下边报进度（先写 `.part` 再改名，中断不留半成品）。
3. 校验两道：**SHA-256** 对比同版本发布的 `.apk.sha256`；**签名证书**必须与已安装应用一致。
   任一不过就删包并拒绝安装（签名不一致系统本来也装不上，提前拦能给出可读原因）。
4. `FileProvider` 授权 + `ACTION_VIEW` 拉起系统安装器 —— 不静默安装，仍由用户在系统界面确认。

首次使用需在系统里允许 GitPocket「安装未知应用」（`REQUEST_INSTALL_PACKAGES` + 逐应用授权），
关于页会引导跳转。

发布侧要求：Release 必须带 `GitPocket-<版本>.apk` 与 `.apk.sha256`（CI 已自动产出），
tag 形如 `v1.3.0`；两者缺一都会让客户端拒绝安装。

## 项目结构

```
app/                    单 Activity、导航、依赖装配
core/model              纯 Kotlin 数据模型
core/git                ★ 纯 JVM Git 引擎（对象/索引/引用/pack/diff）
core/common             调度器、格式化、语法/Markdown 解析、撤销栈、仓库锁
core/designsystem       主题、拟态组件、排版
core/database           Room + 显式迁移 + schema 导出
core/datastore          设置注册表（DataStore）
core/security           AndroidKeyStore token 封装
core/data               仓库门面（Git + Room + 设置）
core/update             应用内更新（查 Release、下载进度、SHA-256 + 签名校验、拉起安装器）
feature/repos           首页
feature/repo            仓库详情 / 文件浏览
feature/editor          编辑器 / diff
feature/commit          提交
feature/settings        设置 / 关于
```

## 测试策略

- `core/git` 是纯 JVM 模块，单测直接跑，无 Robolectric。
- 关键用例**用真实 git 对拍**：`git cat-file`、`git ls-files --stage`、`git status --porcelain`、`git fsck --strict`、`git verify-pack`。
- 无 git 环境时相关用例自动跳过（`Assume`），CI 上必须全部执行。

```bash
./gradlew :core:git:test
# 报告：core/git/build/reports/tests/test/index.html
```

## 数据与隐私

- 不收集任何数据，不集成统计/广告 SDK。
- 仓库与草稿保存在应用私有目录。
- token 由 AndroidKeyStore（AES-256-GCM）加密后落盘，明文不写日志、不入库。

## 已知限制

- packfile 读取会把单个对象解压进内存（大对象需流式改造）。
- 克隆 / 拉取下来的对象一律按 loose object 落盘，没有 idx/pack 落盘与 `gc`：
  大仓库的 `.git/objects` 文件数会偏多、占空间略大。
- 合并只做行级 diff3：不做 rename detection，重命名会被当成删除 + 新增；
  两侧在同一位置插入不同内容时会报冲突（宁多报不漏内容）。
- 二进制 / 符号链接 / 子模块的冲突只能整份选一边，不能逐行合并。
- 切换分支、拉取、合并都要求工作区干净，没有自动 stash。
- 编辑器行号栏在长行自动换行时会与文本错位（逻辑行号正确，视觉对齐会漂）。
- 超过 512 KB 的文件只读预览（截断到 20 万字符），避免 `BasicTextField` 卡顿。
- 超过 5000 行的文件不显示逐行 diff，只给"新增/删除行数"的统计。
- Markdown 预览不支持嵌套列表、可点击链接、HTML 内嵌。
- 生物识别在冷启动与界面重建时校验；从后台切回不会重新上锁（避免每次切应用都验一次）。
- 迁移测试为仪器测试，需要模拟器/真机执行（当前仓库未包含 androidTest 用例）。

## 许可证

[Apache License 2.0](LICENSE)（`Copyright 2026 GitPocket contributors`）。
保留版权声明与许可声明即可自由使用、修改与再分发；如需二次开源请保留 `LICENSE` 文件，
并对改动过的文件作出说明。详见许可证第 4、6 节。
