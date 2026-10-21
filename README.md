# GitPocket

**Git 装进口袋，改动随手落地。**

一个 Android 原生、轻量、适合碎片化操作的 Git 管理器 + 轻编辑器。不是手机上的 VS Code。

---

## 当前实现状态（v0.1 开发中）

这一版把「本地 Git 内核 + 完整 UI 骨架」打通了，**网络传输层尚未实现**。

| 能力 | 状态 | 说明 |
|---|---|---|
| Git 对象库（loose object 读写、SHA-1 校验） | ✅ 已实现 | 与 `git hash-object` / `git cat-file` 对拍 |
| packfile 读取（idx v2、ofs/ref delta） | ✅ 已实现 | 真实 `git gc` 产物验证，含校验和 |
| index v2/v3 读写 | ✅ 已实现 | 写回后 `git status` 干净 |
| commit / tree 序列化 | ✅ 已实现 | `git fsck --strict` 通过 |
| 工作区状态（含 racy-clean 处理） | ✅ 已实现 | 与 `git status --porcelain` 一致 |
| 行级 diff（Myers） | ✅ 已实现 | 大文件降级 |
| 仓库浏览 / 文件树 / 编辑器 / 草稿 | ✅ 已实现 | 自动保存 800ms |
| **语法高亮**（Kotlin/Java/JS/TS/JSON/XML/Python/Shell/Markdown） | ✅ 已实现 | 自研词法器，无第三方库 |
| **Markdown 预览**（标题/列表/引用/表格/代码块/行内样式） | ✅ 已实现 | 原生 Compose 渲染，非 WebView |
| **手动保存 + 撤销** | ✅ 已实现 | 默认不自动保存；离开前确认，可撤销未保存修改 |
| **按文件撤销变更** | ✅ 已实现 | 变更列表逐条撤销，恢复到 HEAD |
| 暂存 / 提交 | ✅ 已实现 | 提交后 `git log` 可读 |
| 主题系统（6 套配色，拟态） | ✅ 已实现 | 全局即时生效 |
| 设置 / 关于页 | ✅ 已实现 | 设置项走注册表 |
| **clone（HTTPS + PAT）** | ✅ 已实现 | smart HTTP v0，真实 GitHub 仓库验证 |
| **fetch / pull / push** | ❌ 未实现 | 第二阶段 |
| **冲突处理 UI** | ❌ 未实现 | 引擎已有 diff3 规划，UI 待接 |
| PR / CI / Issue | ❌ 未实现 | 第二阶段 |

> 说明：示例仓库由本应用的 Git 引擎在首次启动时真实创建，不是 mock 数据。

## 支持范围

- 已支持：HTTPS 克隆（公开仓库 + PAT 私有仓库）、本地仓库的浏览、编辑、暂存、提交、diff、历史。
- 未支持：fetch / pull / push、SSH、Git LFS、submodule 递归、SHA-256 仓库、force push、rebase。

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

1. 确认 `main` 上 PR CI 全绿；
2. 更新 `CHANGELOG.md`；
3. 打 tag 并推送：
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
4. CI 自动执行：单测 → lint → 签名构建 → `apksigner verify` 校验证书 → 创建 GitHub Release；
5. 人工检查 Release 资产：`GitPocket-<版本>.apk`、`GitPocket-<版本>.apk.sha256`、`mapping.txt.gz`；
6. 手动上架（首版不做自动上架，避免误发）。

版本号唯一真源是 tag：`v1.2.3` → `versionName=1.2.3`、`versionCode=10203`。本地无 tag 时为 `0.0.0-dev`。

## CI 流程

| 工作流 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/pr.yml` | PR / push main | 单元测试、lint（**不产出 APK**） |
| `.github/workflows/release.yml` | 推送 tag `v*.*.*` | 签名构建、签名校验、自动创建 Release |

**APK 只有 tag 能触发构建**，其他任何提交都不会产出安装包。

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

## 项目结构

```
app/                    单 Activity、导航、依赖装配
core/model              纯 Kotlin 数据模型
core/git                ★ 纯 JVM Git 引擎（对象/索引/引用/pack/diff）
core/common             调度器、格式化、日志脱敏、仓库锁
core/designsystem       主题、拟态组件、排版
core/database           Room + 显式迁移 + schema 导出
core/datastore          设置注册表（DataStore）
core/security           AndroidKeyStore token 封装
core/data               仓库门面（Git + Room + 设置）
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
- 编辑器行号栏在长行自动换行时会与文本错位（逻辑行号正确，视觉对齐会漂）。
- Markdown 预览不支持嵌套列表、可点击链接、HTML 内嵌。
- 迁移测试为仪器测试，需要模拟器/真机执行。

## 许可证

TBD（发布前确定）。
