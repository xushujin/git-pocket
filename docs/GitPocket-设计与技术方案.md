# GitPocket — 产品与技术方案（Android 原生）

> 版本：v0.1（设计稿） 日期：2026-10-20
> 适用约束：Kotlin + Jetpack Compose + 无第三方 UI 库 + 无第三方 DI + 核心 Git 能力自研 + 显式迁移禁止 destructive migration
> 标注约定：**【已核实】**= 有外部依据；**【估算】**= 工程量/性能判断，未实测；**【未验证】**= 需真机/真实仓库验证；**【待确认】**= 需要产品或法务拍板

---

## 0. 技术决策速查表

| # | 决策点 | 结论 | 一句话理由 | 代价 |
|---|---|---|---|---|
| D1 | 语言/UI | Kotlin + Compose（单 Activity） | 状态驱动、主题即时生效、自绘拟态成本最低 | Compose 文本输入在超大文件上有性能边界 |
| D2 | 架构 | MVVM + 单向数据流（UDF）+ 模块化分层，不做完整 Clean | 8 个页面，Clean 的 UseCase 层会变成转发样板 | 领域逻辑需靠"纯 JVM 模块 + 测试"保证，而非目录仪式 |
| D3 | DI | 手写 `AppContainer` + 构造注入 + `viewModelFactory` | 约束禁止第三方 DI，项目规模不需要注解处理器 | 模块增多后手写装配会变长（可接受） |
| D4 | Git 实现 | **纯 Kotlin 进程内自研**（对象库/索引/引用/传输/合并） | 无系统 git 可用；JGit/libgit2 属第三方；服务端中转破坏离线与隐私 | 6~8 人周【估算】，是全项目最大风险 |
| D5 | 仓库位置 | clone 到应用私有目录 `filesDir/repos/<id>` | Git 需要对 index/objects 的完整控制权与原子写 | 不能直接编辑用户 SD 卡上的既有目录（用导出/分享补偿） |
| D6 | 认证 | HTTPS + PAT（Basic），预留 OAuth Device Flow | 零 SDK 依赖、可离线保存、可撤销 | 用户需自己生成 token（引导页要做） |
| D7 | Token 存储 | AndroidKeyStore 里的 AES-256-GCM 自研封装 | 官方平台 API，避开 `security-crypto` 弃用风险【待确认】 | 需自己管 IV/版本号/密钥轮换 |
| D8 | 数据库 | Room（官方）+ `exportSchema=true` + 显式 Migration | 官方方案、编译期 SQL 校验、迁移可测试 | 每次改表都要写迁移与测试 |
| D9 | 设置存储 | DataStore(Preferences) + 单一 `SettingsRepository` + 声明式设置注册表 | 满足"同一设置项只有一套实现" | 新增设置必须走注册表，不能就地写 UI |
| D10 | 主题 | 自定义 `NeumorphicPalette` + `CompositionLocal`，根节点唯一 `GitPocketTheme` | 全局立即生效、不重建 Activity | 拟态阴影在 API<31 需降级方案 |
| D11 | minSdk/target | minSdk 26，compileSdk/targetSdk 36 | Play 自 2026-08-31 起要求新应用与更新必须 target API 36【已核实】 | 放弃 Android 7.x 及以下（占比已很低）【估算】 |
| D12 | 发版 | tag `v*` 触发 CI，签名走 `keystore.properties` 或 CI 环境变量，`gh release` 自动发布 | 单一版本源 = tag，避免手改 versionCode | 本地打 release 包需要 keystore，否则构建直接失败（故意的） |

---

## 1. 产品定位

### 1.1 一句话定义

**GitPocket 是一个把"看仓库、改一行、提交、推走"压缩到三次点击内的 Android 原生 Git 管理器 + 轻编辑器。**

关键词是"管理器 + 轻编辑器"，不是"手机上的 VS Code"。判断某个需求该不该做，用一条标准：**它是否服务于 5 分钟以内的碎片化操作**。不服务就不做。

### 1.2 适合谁

| 用户 | 典型场景 | 为什么用 GitPocket |
|---|---|---|
| 独立开发者 / 小团队 | 通勤路上回一个 issue、改一句文案、改配置开关、发个 hotfix commit | 手机在手，不用开电脑 |
| 运维 / SRE | 改 K8s manifest、改 CI 配置、回滚一个文件 | 只需要"改一个文件 + 提交"，不需要构建 |
| 技术负责人 / Reviewer | 看 diff、看提交历史、看 PR 与 CI 状态 | 读多写少，浏览体验优先 |
| 学生 / 学习者 | 读开源仓库结构、看 commit 演进 | 低门槛浏览 Git 仓库 |
| 写作者 / 文档维护者 | 改 Markdown、发博客仓库更新 | 纯文本编辑足够 |

### 1.3 不适合谁（明确劝退）

- **要做大重构的人**：多文件重命名、跨文件符号跳转、编译运行——手机上不可能做好，别做。
- **需要跑构建/测试的人**：GitPocket 不执行任何构建。
- **强依赖 SSH 的用户**：首版只支持 HTTPS + PAT。SSH 需要自研 SSH 传输层（密钥格式、known_hosts、算法协商），成本远高于收益，**后置**。
- **Git LFS / submodule 重度仓库**：首版明确不支持，打开时给出可读提示而不是静默损坏。
- **超大单体仓库**（>2GB / >20 万文件）：首版不承诺体验，第二阶段用 `filter=blob:none` 部分克隆改善。

### 1.4 为什么是安卓原生，不是跨平台

| 维度 | 原生（Kotlin/Compose） | 跨平台（RN/Flutter） |
|---|---|---|
| 文本编辑性能 | 直接控制 `TextField`/`TextFieldState`、IME、选区、滚动 | 需桥接原生输入框，长文本与 IME 边界情况多 |
| 后台同步 | WorkManager / 前台服务 / 通知，一等公民 | 需插件或自写原生模块 |
| 密钥与生物识别 | AndroidKeyStore / BiometricPrompt 直接可用 | 同样要写原生模块 |
| 文件与 SAF | 直接可用 | 需插件，权限语义易错 |
| 包体 | 无 JS/JVM 运行时，基线小 | Flutter 引擎约 +8~10MB【估算】 |
| 本项目复杂度分布 | 80% 在 Git 核心与文本编辑性能——**恰好是跨平台最弱处** | 优势在 UI 一致性，本项目不需要 |
| 长期维护 | 单一语言、单一工具链 | 双端桥接层是长期负债 |

结论：本项目的技术难点不在"两端 UI 一致"，而在"二进制格式解析 + 大文本输入 + 后台任务"，这三项原生都更占优。跨平台在这里是负收益。

---

## 2. 核心功能清单

### 2.1 MVP（P0：没有就不能叫 Git 客户端）

| # | 功能 | 价值 | 优先级 |
|---|---|---|---|
| 1 | HTTPS 克隆 / 打开仓库（PAT 认证） | 一切的前提 | P0 |
| 2 | 仓库概览（当前分支、ahead/behind、工作区状态、最近提交） | 3 秒判断"我现在在哪、有没有未提交" | P0 |
| 3 | 分支列表 + 提交历史（分页） | 最高频的"读" | P0 |
| 4 | 文件树浏览 + 文件查看（只读，含二进制/大文件降级） | 定位要改的文件 | P0 |
| 5 | 文本编辑 + 自动保存草稿 + 撤销/重做 | 核心写入路径 | P0 |
| 6 | Diff 查看（工作区 / 暂存区 / 某次提交 vs 父提交） | 提交前的安全感 | P0 |
| 7 | 暂存 + Commit（作者信息可配） | 闭环的一半 | P0 |
| 8 | Pull（fast-forward + 简单 merge）/ Push（含非快进拒绝提示） | 闭环的另一半 | P0 |
| 9 | 冲突的"选一边"处理（ours / theirs / 手动编辑） | 不做就无法交付 | P0 |
| 10 | 设置（主题/编辑器/Git 身份/存储）+ 关于页 | 合规与可用性底线 | P0 |
| 11 | 多账号（≥2 个 host） | 很多人同时用 GitHub + 公司 GitLab | P1（MVP 内可放最后） |
| 12 | 浅色/深色 + 多套配色 | 约束要求 | P0 |

**必须最先做的 8 个**（按开发顺序，也是"能跑通闭环"的最小集）：
`1 克隆 → 2 概览 → 4 文件树/查看 → 5 编辑 → 6 diff → 7 暂存+提交 → 8 拉取/推送 → 9 冲突选边`
其余（3 历史、10 设置、11 多账号、12 主题）可与上面并行，但**不能砍**，因为它们是验收条件。

### 2.2 第二阶段（P1：从"能用"到"好用"）

| 功能 | 价值 | 为什么不在 MVP |
|---|---|---|
| PR/MR 列表 + 详情 + 评论（只读） | 碎片时间最高价值场景之一 | 需要平台 REST 适配层（GitHub/GitLab/Gitea 三套语义），与 Git 核心解耦，可后置 |
| CI 状态（commit 检查、workflow run 结果） | "我推的这次绿了吗" | 同上，且需要轮询/通知策略 |
| Issue 列表 + 只读详情 + 简单评论 | 看 issue 再改代码的链路 | 依赖 PR 层先落地 |
| 语法高亮（自研轻量 lexer，仅 diff/查看态） | 可读性提升明显 | 自研 lexer 有成本，先保证 diff 正确 |
| 行内 diff（intraline） | 小改动看得更准 | 需要二次 diff，先做行级 |
| 部分克隆（`filter=blob:none`）+ 按需取 blob | 大仓库可用性 | 需要 lazy fetch 与 UI 占位态 |
| OAuth Device Flow 登录 | 降低 token 心智负担 | PAT 已能跑通，非阻塞 |
| 生物识别解锁 token | 安全加分 | 依赖 token 存储稳定 |
| 提交信息历史建议 / 模板 | 提升提交质量 | 锦上添花 |
| Markdown 预览（自研极简渲染） | 文档仓库高频 | 渲染器成本不低，先纯文本 |

### 2.3 增强功能（P2：形成差异）

多仓库工作区（最近/收藏/分组）、平板与折叠屏双栏、桌面 Widget（快速看未提交数）、快捷方式（长按图标直达某仓库）、Patch 导入导出（`git format-patch` 自研）、`git blame` 行级归属、文件历史（单文件 log）、仓库体积分析、AI 生成 commit message（**必须默认关闭、明确告知会把 diff 发给第三方**）、Wear OS 只读状态。

### 2.4 明确后置且要写进"支持范围"的

SSH 传输、Git LFS、submodule、SHA-256 仓库、rebase/cherry-pick 交互式操作、rename detection、多 pack gc/repack 优化、hooks 执行、子模块递归。

---

## 3. 安卓原生技术方案

### 3.1 为什么 Kotlin

1. **协程 + Flow 天然匹配"IO 密集 + 状态驱动"**：clone/fetch 是长任务，需要结构化并发、取消、进度回调；`Flow` 直接喂给 Compose。
2. **sealed class 建模 Git 对象**：`GitObject = Blob | Tree | Commit | Tag`，`when` 穷尽检查，比 Java 的继承 + instanceof 安全得多。
3. **空安全**：解析二进制时大量 `String?`/`ByteArray?`，Kotlin 类型系统能挡掉一类崩溃。
4. **`value class` / 内联**：`ObjectId`、`RefName` 用 value class 包 `String`，避免大列表里成千上万次装箱。
5. **核心模块纯 JVM**：`core:git` 不依赖 Android SDK，单测直接在 JVM 跑，秒级反馈；未来可平移到 KMP。
6. **与 Compose 同一编译器生态**：Compose 编译器插件、Baseline Profile、Strong Skipping 都按 Kotlin 优化。

### 3.2 为什么 Jetpack Compose

1. **主题切换即时全局生效**：`CompositionLocal` 一变，整棵树重组，不需要 `recreate()`，也不会丢页面状态。
2. **拟态风格必须自绘**：拟态 = 双色偏移阴影 + 内外高光。用 XML drawable/9-patch 做不出版本一致的效果，Compose 的 `drawWithCache` / `Modifier.drawBehind` 可以直接控制。
3. **diff 与文件列表需要虚拟化 + 精确控制行高**：`LazyColumn` + `key` + `contentType` 比 RecyclerView 样板少，且能按需固定行高避免测量抖动。
4. **状态即 UI**：`UiState` 驱动，配合 `collectAsStateWithLifecycle()` 自动处理生命周期。
5. **官方生态闭环**：Navigation-Compose、Lifecycle、Paging、Room、DataStore 全部官方，符合"不引第三方"。

代价（必须写进风险）：`BasicTextField` 在**超大文本**（>1MB）下输入延迟明显【未验证，需真机实测】。应对：编辑上限 + 只读大文件查看器 + `TextFieldState`（Compose Foundation 1.7+ 的新输入 API，支持更细粒度状态读取）。

### 3.3 拟态风格与主题系统

#### 3.3.1 调色板数据结构（唯一真源）

```kotlin
@Immutable
data class NeumorphicPalette(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val base: Color,            // 背景基色（拟态的"底"）
    val surface: Color,         // 凸起面（通常比 base 略亮）
    val lightShadow: Color,     // 左上高光
    val darkShadow: Color,      // 右下暗影
    val contentPrimary: Color,
    val contentSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
)
```

内置 4~6 套：`Mist`（冷灰蓝，默认浅色）、`Paper`（暖米白）、`Graphite`（深灰）、`Midnight`（深蓝黑）、`Moss`（低饱和绿）。每套提供 light/dark 变体，**浅色与深色各自独立选配色**，避免"深色下某套配色对比度崩掉"。

约束：`diffAddFg/diffDelFg` 与对应背景的对比度 ≥ 4.5:1，且 diff 不只靠颜色区分（见 5.2）。

#### 3.3.2 主题只有一条链路

```
DataStore(theme.*) ──► SettingsRepository.themeState: Flow<ThemeState>
                                │
                                ▼
        MainActivity.setContent { GitPocketTheme(state) { AppNavHost() } }
                                │
                     CompositionLocalProvider(
                        LocalNeumorphicPalette, LocalGitPocketTypography,
                        LocalGitPocketSpacing, LocalGitPocketMotion
                     )
```

硬规则（写进 CONTRIBUTING 与 CI 检查项）：
- **全工程只允许出现一处 `GitPocketTheme`**（在 `MainActivity` / `AppRoot`）。
- **禁止在 feature 模块里调用 `isSystemInDarkTheme()`**，深浅判断只发生在 `ThemeState.resolve()`。
- 主题相关设置项只在"设置 → 外观"出现一次；仓库页/编辑器里的"深色切换"只能**深链到同一 section**，不得另写实现。
- 系统栏（状态栏/导航栏）图标明暗必须跟随主题：`WindowInsetsControllerCompat.isAppearanceLightStatusBars` 在 `GitPocketTheme` 的 `SideEffect` 里统一设置——这是最常见的漏项。

#### 3.3.3 拟态组件实现

```kotlin
fun Modifier.neumorphicRaised(
    shape: Shape = RoundedCornerShape(20.dp),
    depth: Dp = 6.dp,
    pressed: Boolean = false,
): Modifier = composed {
    val p = LocalNeumorphicPalette.current
    val d = if (pressed) depth / 2 else depth
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val blur = d.toPx()
        val offset = blur * 0.6f
        onDrawBehind {
            // 先铺底
            drawOutline(outline, p.base)
            // 暗影（右下）
            drawIntoCanvas { c ->
                val paint = Paint().apply {
                    color = p.base
                    asFrameworkPaint().apply {
                        setShadowLayer(blur, offset, offset, p.darkShadow.toArgb())
                    }
                }
                c.saveLayer(size.toRect(), paint)
                drawOutline(outline, p.base)
                c.restore()
            }
            // 高光（左上）
            // ... 同上，偏移 -offset，颜色 p.lightShadow
            drawOutline(outline, p.surface)
        }
    }
}
```

**性能红线（重要）**：
- `setShadowLayer` 的模糊在硬件加速画布上并非所有 API 都稳定生效【未验证】。稳妥做法是 **API 31+ 用 `RenderEffect.createBlurEffect`**，**API 26–30 用"双段线性渐变"模拟**（无模糊、零额外开销，视觉约 80% 接近）。
- **列表项（文件行、提交行、diff 行）一律不用模糊阴影**，只用 1dp 双色描边 + 内高光。模糊只用于首屏卡片、FAB、主按钮这类"少而大"的元素。
- `Paint` 必须在 `drawWithCache`/`remember` 里创建，禁止在 `drawBehind` 每帧 new。
- 暗色拟态的物理隐喻不成立（深色下"凸起"靠暗影看不出来），暗色改用**内阴影 + 顶部 1dp 高光线**表达层级，不要硬套浅色公式。

### 3.4 整体架构：MVVM + UDF，模块化分层

```
:app                    单 Activity、导航宿主、AppContainer 装配
:core:model             纯 Kotlin 数据模型（无 Android 依赖）
:core:git               ★ 纯 JVM Git 实现（对象/索引/引用/合并/传输）
:core:git-transport     HTTP smart protocol（仅 java.net，无第三方）
:core:database          Room + 显式迁移
:core:datastore         设置（DataStore + 声明式设置注册表）
:core:security          AndroidKeyStore AES-GCM token 封装
:core:designsystem      主题、拟态组件、图标、动效、排版
:core:common            Result/错误模型、调度器、日志、时间
:feature:repos          首页（仓库列表/克隆）
:feature:browse         仓库详情、分支、历史、文件树
:feature:editor         编辑器 + 草稿
:feature:diff           diff 渲染
:feature:commit         暂存与提交
:feature:sync           拉取/推送/冲突
:feature:settings       设置 + 关于
:feature:platform       PR/CI/Issue（第二阶段）
```

依赖方向严格单向：`feature → core`，`core:git` 不依赖任何 Android 模块，`feature` 之间不互相依赖（共享 UI 放 `core:designsystem`）。

**为什么是 MVVM + UDF，不是纯 MVI，也不是完整 Clean：**

| 方案 | 优点 | 本项目为何不选/如何折中 |
|---|---|---|
| 纯 MVI（Intent/Reducer/Effect 全家桶） | 状态可回溯、易测 | 8 个页面里 6 个是"加载-展示-一个动作"，Reducer 会退化成 `when(intent)` 转发，样板 > 收益。**折中**：每页 `UiState`(data class) + `onAction(Action)` 单入口 + `Channel<Effect>` 单次事件，形似 MVI 但不引入 reducer 框架 |
| 完整 Clean（domain 层 UseCase 每操作一个类） | 边界清晰 | `StageFileUseCase` 只是 `repo.stage(path)` 的一行转发。**折中**：只有跨仓库/跨数据源的组合逻辑才建 UseCase（如 `CommitAndPushUseCase`），其余直接 ViewModel → Repository |
| MVVM 裸奔（ViewModel 里直接调 Git） | 快 | 不可测、不可换实现。**折中**：ViewModel 只依赖 Repository 接口，接口背后是 `core:git` |

分层职责：
- **UI（Compose）**：只做渲染 + 把用户操作转成 `Action`。禁止在 composable 里调用 Git、读文件、发网络。
- **ViewModel**：持有 `StateFlow<UiState>`，调 Repository，做状态编排与错误文案映射。构造注入，无 Android Context（需要时用 `SavedStateHandle` / `Application` 通过工厂传入）。
- **Repository**：聚合 `core:git` + Room + DataStore，负责缓存策略、并发串行化、错误翻译。
- **core:git**：纯函数式/命令式混合的 Git 引擎，输入输出都是 `core:model` 类型，抛 `GitException` 子类。

**并发模型（关键）**：
- 每个仓库一个 `Mutex`（`RepoLocks`，`ConcurrentHashMap<RepoId, Mutex>`），**同一仓库的写操作（commit/checkout/pull/push/index 写）必须串行**，否则 index 会写坏。读操作可并行。
- 所有 Git 调用 `suspend` + `withContext(Dispatchers.IO)`；diff/解析用 `Dispatchers.Default`。
- 长任务（clone/fetch/push）通过 `Flow<Progress>` 上报，UI 可取消；取消时 `HttpURLConnection.disconnect()` 并清理半成品目录。

**错误模型**：
```kotlin
sealed class GitException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotARepository(val path: String) : GitException("不是 Git 仓库：$path")
    class AuthFailed(val host: String) : GitException("认证失败：$host")
    class NonFastForward(val branch: String) : GitException("远端有新提交，请先拉取")
    class Conflict(val paths: List<String>) : GitException("存在冲突文件")
    class ObjectCorrupted(val oid: String) : GitException("对象损坏：$oid")
    class UnsupportedFeature(val feature: String) : GitException("暂不支持：$feature")
    class Network(val retryable: Boolean, cause: Throwable) : GitException("网络错误", cause)
}
```
UI 层把异常映射成"用户能懂的一句话 + 一个可执行动作"，**禁止把堆栈直接抛给用户，也禁止 catch 后静默返回空列表**。

**DI（无框架）**：
```kotlin
class AppContainer(app: Application) {
    val database by lazy { GitPocketDatabase.create(app) }
    val settings by lazy { SettingsRepository(app.dataStore) }
    val tokenStore by lazy { KeystoreTokenStore() }
    val gitEngine by lazy { GitEngine(objectStoreFactory, transportFactory) }
    val repoRepository by lazy { RepoRepositoryImpl(database.repoDao(), gitEngine, app.filesDir) }
}

@Composable
fun <VM : ViewModel> containerViewModel(factory: (AppContainer) -> VM): VM {
    val c = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { factory(c) } })
}
```

### 3.5 Git 能力如何实现（本方案的核心）

#### 3.5.1 三条路线对比

| 路线 | 可行性 | 成本 | 结论 |
|---|---|---|---|
| A. 纯 Kotlin 进程内自研 | 可行，Git 格式与协议均有公开文档 | 6~8 人周【估算】达到 clone/pull/commit/push | **选它**（约束要求，且离线/隐私最优） |
| B. 集成 JGit（纯 Java） | 技术可行 | 接入 2~3 天，但引入 ~3MB dex + JavaEWAH/slf4j 传递依赖，Android 上初始化与内存开销明显【估算】 | 违反"核心能力自研"，且体积/依赖不可控 |
| C. 打包 git 可执行文件（NDK/termux 式） | 可行 | 需交叉编译 + GPLv2 合规评估 + 进程管理 + 存储权限 | 法律与体积风险高，**不推荐**，仅作最后兜底记录 |
| D. 服务端中转（GitHub API 提交文件） | 最简单 | 2 周 | **否决**：不能离线、token 出境、无法处理 merge/冲突、受 API 速率限制、非通用 Git |

> 诚实的取舍说明：如果允许引入 libgit2（NDK 编译，~2MB/ABI），可省下 6~8 人周，且经过工业级验证。当前约束下不做，但**建议把它写成"架构预留"**：`GitEngine` 是接口，自研实现是 `NativeKotlinGitEngine`，未来可替换为 `Libgit2GitEngine` 而不动上层。

#### 3.5.2 分层与实现要点

```
core:git
├── object/     ObjectId, ObjectReader/Writer, LooseObject, PackFile, PackIndex, DeltaApplier
├── ref/        RefStore（loose + packed-refs）, HeadRef, RefTransaction
├── index/      GitIndex（DIRC v2/v3）, IndexEntry, IndexLock, TreeExtension, ReucExtension
├── model/      GitBlob/GitTree/GitCommit/GitTag 解析与序列化
├── graph/      CommitGraph, MergeBase, AheadBehind
├── merge/      ThreeWayMerge, Diff3, ConflictMarker
├── diff/       MyersDiff, LineIndex, HunkBuilder
├── worktree/   WorkingTreeScanner, StatusCalculator, Checkout, SafeWriter
└── porcelain/  Clone/Pull/Push/Commit/Checkout/Add（对上层暴露的唯一入口）
```

**逐项实现要点（这些是最容易翻车的地方）：**

1. **zlib**：用平台 `java.util.zip.Inflater/Deflater`（JDK 自带，非第三方）。注意 `Inflater` 不是线程安全的，用 `ThreadLocal` 或每次新建。
2. **loose object**：`<type> <size>\0<content>` → deflate → 存 `objects/ab/cdef...`。写入必须**先写临时文件再 `rename`**（原子），并校验 SHA-1（`MessageDigest.getInstance("SHA-1")`）。
3. **packfile**（读）：
   - `.idx` v2：256 项 fanout → sha1 表 → CRC 表 → 4 字节偏移表 → 大偏移表（>2GB）。必须校验 fanout 单调性，否则索引损坏会导致越界。
   - pack 对象头：type(3bit) + size(变长)。`OBJ_OFS_DELTA` 的负偏移是**变长编码且带特殊偏置**（`offset = (offset + 1) << 7 | (b & 0x7f)` 的逆运算），这是最常见的实现错误。
   - delta 应用：源大小 varint、目标大小 varint，然后 opcode：`0x80` 位表示 copy（带 4 个可选偏移/长度字节），否则 insert（低 7 位为长度，0 表示 0x80 长度的特殊情况需处理）。
   - **thin pack**：fetch 返回的 pack 可能引用本地已有对象作为 delta base，必须"补薄"（fix-thin）：把缺失 base 的对象追加进 pack 并重算 trailer SHA-1，否则整包不可用。
   - 校验：pack 末尾 20 字节 SHA-1 必须校验；`.idx` 与 pack 的校验和不匹配时拒绝使用并重下。
4. **refs**：loose ref 文件 + `packed-refs`（注意 `^` 开头的 peeled 行）。写 ref 用 `<ref>.lock` + `rename`。**Android 文件系统通常大小写不敏感**，`Feature` 与 `feature` 两个分支会互相覆盖 → 必须检测大小写冲突并拒绝操作，给出明确错误。
5. **index（DIRC）**：12 字节头（`DIRC` + version + entryCount）+ 每项 62 字节 + path + 8 字节对齐填充。扩展区签名**首字母大写 = 可选，小写 = 必需**：遇到未知的必需扩展必须拒绝写回（不能丢），未知可选扩展可安全丢弃（会丢 `TREE` 缓存性能，但语义正确）。写回前必须拿到 `index.lock`。
6. **commit/tree/tag**：解析 `tree/parent/author/committer/gpgsig/encoding` 头；**`gpgsig` 必须原样保留**（多行续行以空格开头），否则重写提交会破坏签名。非 UTF-8 文件名用 `ByteArray` 保存，UI 层做 best-effort 解码 + 转义显示。
7. **status**：不能只比 mtime/size（Git 自己也这么做但会误判）。可靠策略：先比 `stat`（size + mtime + ino），命中则视为未变；未命中则计算 blob SHA-1 比对 index 中的 oid。**只对未命中的文件做哈希**，这是大仓库性能的关键。
8. **merge**：`merge-base` 用 commit graph 上的双向 BFS（按 commit time 优先队列，找到第一个公共祖先，不追求"最佳"）。三方合并用行级 diff3；冲突写入 index 的 stage 1/2/3 并生成冲突标记。**MVP 不做 rename detection**，重命名会被视为 delete+add，必须在"支持范围"里写明。
9. **transport（smart HTTP）**：
   - `GET /info/refs?service=git-upload-pack` 拿能力列表；优先 **protocol v2**（`Git-Protocol: version=2`），失败回落 v0。
   - `POST /git-upload-pack` 发 `want/have/done`，响应是 pkt-line + sideband（channel 1 数据 / 2 进度 / 3 错误）。
   - `POST /git-receive-pack` 推送，响应 `report-status-v2`（`unpack ok` + 每 ref 的 `ok/ng`）。
   - pkt-line 长度是 **4 位十六进制且包含自身 4 字节**；`0000` 是 flush，`0001` 是 delimiter（v2）。这类细节必须有单测。
   - 认证：`Authorization: Basic base64(user:token)`。各平台差异见 3.6。
   - **超时与取消**：连接 10s、读 60s（fetch）、写 120s（push）；`suspendCancellableCoroutine` 里注册 `invokeOnCancellation { conn.disconnect() }`，并在读循环每 64KB `ensureActive()`。
10. **push 的 pack 生成（最容易踩坑）**：
    - **MVP 策略：生成"零 delta"的 pack**——所有对象完整 zlib 压缩、不做 delta。体积大 30~50%【估算】，但实现简单、绝不会因 delta 链错误被服务器拒绝。这是典型的"简单但可靠"取舍。
    - 必须包含：新 commit、其 tree、所有新 blob、以及服务器可能没有的 tag 对象。
    - 拒绝非快进：先 `ls-refs` 比对远端分支 oid 是否为本地祖先，不是则本地直接拒绝并提示 pull，而不是等服务器 `ng`。
11. **对象读取性能**：`RandomAccessFile` + 64KB 预读缓冲 + 解压对象 LRU（默认 2000 个 / 64MB，可配）。不做 mmap（Android 上 `MappedByteBuffer` 释放不可控）。

#### 3.5.3 明确的能力边界（写进关于页"支持范围"）

支持：HTTPS（GitHub / GitLab / Gitea / 自建 GitLab）、protocol v0/v2、SHA-1 仓库、单分支 clone、fetch/pull（ff + 简单 merge）、commit、push（非 force）、分支创建/切换/删除（非当前分支）。
不支持：SSH、LFS、submodule、SHA-256 仓库、shallow clone 的后续 deepen【待确认实现成本】、force push、rebase、cherry-pick、hooks、`git gc`（仅提供"重新打包"的后续版本）。

### 3.6 登录、Token、仓库缓存、离线草稿

**认证方式**
| 平台 | 用户名 | 密码 | 备注 |
|---|---|---|---|
| GitHub（classic PAT） | GitHub 用户名 | token | 需 `repo` scope |
| GitHub（fine-grained） | `x-access-token` | token | 推荐，权限最小化 |
| GitLab | `oauth2` 或用户名 | PAT | scope `api` 或 `read_repository`+`write_repository` |
| Gitea | 用户名 | token | — |

不支持账号密码（GitHub 已废弃，GitLab 也在推 token）。引导页给"如何生成 token"的分步说明 + 一键复制仓库地址解析。

**Token 存储**
```
AndroidKeyStore (AES-256-GCM, 不可导出)
        │  包裹
        ▼
TokenCipher.encrypt(plain) -> (iv, ciphertext, keyVersion)
        │
        ▼
DataStore(Proto) / 私有文件  token.<accountId>.bin
```
- 明文 token **绝不进 Room、绝不进日志、绝不进崩溃上报**。HTTP 日志统一脱敏（`Authorization` 整行替换）。
- 提供"撤销本地 token"（删除密文 + 清 KeyStore 别名）。
- 生物识别解锁（第二阶段）：`BiometricPrompt` 成功后才解密 token，失败降级为重新输入。

**仓库缓存**
- 位置：`filesDir/repos/<repoId>/`（`repoId` = 随机 UUID，不用 owner/name，避免重命名与大小写问题）。
- 目录结构就是标准 `.git` 内容（`objects/ refs/ index HEAD config`），**便于导出为真实仓库**。
- 配额：单仓库默认 500MB、全局 2GB，超限时提示"清理其他仓库"或"仅保留最近 30 天"，**不静默删除**。
- Room 只存元数据与轻量索引（repo 行、ref 快照、commit 摘要缓存），**绝不把 blob 内容写进数据库**。

**离线草稿**
- 表 `draft(repoId, path, baseOid, content, updatedAt, status)`。
- 触发：编辑停顿 800ms debounce + `ON_STOP` 强制落盘 + 手动 Ctrl/Save 立即落盘。
- 恢复：进入编辑器时三方比对 `baseOid`（草稿基于的 blob）、磁盘当前 blob、草稿内容 → 三种态：无冲突直接恢复 / 磁盘已变（提示"文件已在别处修改"）/ 草稿过期（保留但标记）。
- 提交成功后删除对应草稿；`git checkout`/`pull` 覆盖文件时若存在草稿，**先提示再继续**。

### 3.7 大列表与 diff 的性能优化

**文件列表**
- 只加载**当前目录一层**，绝不递归整棵树（10 万文件仓库递归会 OOM）。
- `LazyColumn` + 稳定 `key = path` + `contentType`（目录/文件/符号链接分开），让复用正确。
- 目录项超过 500 条启用 Paging 3（官方），页大小 100。
- 排序与过滤在 IO 线程完成，结果缓存；`stat` 批量执行（一次 `readdir` 拿 name+d_type，避免逐文件 `stat`）。
- 文件图标用矢量资源 + `remember` 缓存，不用 Compose 现绘复杂图形。

**Diff**
- 算法：行级 Myers（`O(ND)`），先做"公共前后缀裁剪"再进 Myers，小改动接近线性。
- 阈值降级：单文件 > 5000 行 或 > 1MB 或 变更块 > 2000 → 只显示"新增 X 行 / 删除 Y 行 + 前 500 行预览"，并提供"在查看器中打开"。
- 计算在 `Dispatchers.Default`，可取消；结果按 `(repoId, path, baseOid, targetOid)` 缓存到 `cacheDir`（LRU 50MB）。
- 渲染：`LazyColumn` 固定行高（等宽字体 + 明确 `lineHeight`），`contentType` 区分"上下文行/新增行/删除行/折叠行"，避免测量抖动。
- 折叠未变更区域（默认折叠 > 6 行的上下文），提供"上一处/下一处变更"跳转（`LazyListState.animateScrollToItem`）。
- 行内 diff 只对**当前可见的变更行**计算（`derivedStateOf` + 可见项），否则大文件会卡。

**其他**
- 主题切换不重建 Activity；`CompositionLocal` 变化只重组读取它的节点。
- 启动：Baseline Profile（`androidx.baselineprofile`）+ R8 full mode；首屏只查 `repo` 表（带 `LIMIT`），commit 历史等进入详情页再加载。
- 图片/大文件查看：不加载进内存，超过 2MB 的二进制只显示"大小/类型/哈希"。

### 3.8 机型碎片化

| 风险点 | 应对 |
|---|---|
| 系统版本跨度（26~36） | minSdk 26；拟态阴影按 API 分档（31+ RenderEffect / 26–30 渐变模拟）；`WindowInsets` 全部走 `WindowInsetsCompat` |
| 折叠屏/平板 | `WindowSizeClass`（官方）分三档：Compact 单栏、Medium 列表+详情、Expanded 双栏（diff 分栏） |
| 字体缩放（最大 2.0） | 不用固定 `height`，用 `padding` + `wrapContentHeight`；工具栏文字超长走 `maxLines` + `ellipsis` |
| 深色/浅色/系统跟随 | 主题三态（跟随/浅/深）+ 每套配色的 light/dark 变体，对比度校验 |
| 输入法遮挡 | `imePadding()` + `adjustResize`；编辑器底部工具条随 IME 上移 |
| 厂商后台限制（MIUI/EMUI/ColorOS） | 后台同步用 WorkManager + 前台服务（`dataSync` 类型，Android 14+ 必须声明）；在设置里给"后台同步可能被系统限制"的说明与跳转指引 |
| 16KB page size（Android 15+） | 本应用无 native so，不受影响；**若未来引入 libgit2 必须重新评估**【待确认】 |
| edge-to-edge 强制（Android 15+） | 从一开始就 `enableEdgeToEdge()`，所有页面消费 insets，不留"以后适配"的债 |
| 存储空间不足 | 写对象前检查可用空间（< 100MB 拒绝 clone 并提示）；写入失败回滚临时文件 |

### 3.9 可测试 / 可维护 / 可迁移

- **可测试**：`core:git` 纯 JVM → 单测无需 Robolectric；Repository 通过接口 mock；ViewModel 用 `kotlinx-coroutines-test` + Turbine【第三方测试库，仅测试源集，需确认是否允许——若严格禁止则手写 `Flow` 收集器】。
- **可维护**：单一职责模块 + 依赖单向 + 禁止跨 feature 引用；所有 Git 错误类型化；所有设置项走注册表。
- **可迁移**：`GitEngine` 接口隔离实现（未来可换 libgit2）；`core:git` 无 Android 依赖（可迁 KMP）；数据层用 Repository 接口（未来可换 SQLDelight）。

---

## 4. 信息架构

### 4.1 导航图

```
MainActivity
└── NavHost(startDestination = "repos")
    ├── repos                      首页（仓库列表）
    │   ├── clone                 克隆（URL 输入 → 认证 → 进度）
    │   └── account               账号管理
    ├── repo/{repoId}              仓库详情（Tab 容器）
    │   ├── tab=changes           变更（工作区 + 暂存区）
    │   ├── tab=history           提交历史
    │   ├── tab=files             文件树（默认分支 or 当前分支）
    │   └── tab=pulls             PR/MR（第二阶段，先隐藏）
    ├── browse/{repoId}?path=&ref= 文件浏览（目录）
    ├── viewer/{repoId}?path=&ref= 只读查看（大文件/二进制/图片）
    ├── editor/{repoId}?path=      编辑
    ├── diff/{repoId}?scope=&path=&base=&target=   diff
    ├── commit/{repoId}            提交
    ├── sync/{repoId}?op=pull|push 同步进度（也可作为底部抽屉）
    ├── conflict/{repoId}          冲突处理
    └── settings                   设置
        ├── appearance             外观（主题唯一入口）
        ├── editor                 编辑器
        ├── git                    Git 身份与行为
        ├── storage                存储与缓存
        ├── security               安全
        └── about                  关于
```

导航实现：`navigation-compose`（官方）+ 类型安全路由（`@Serializable` route 类，kotlinx.serialization 为 Kotlin 官方序列化库，非 UI 库；**【待确认】** 若团队把"官方"限定为 androidx，则改用手写 `sealed class Route` + `SavedStateHandle` 传参，成本 +1 天）。

### 4.2 各页面设计

**首页 `repos`**
- 顶部：应用名 + 全局同步指示（转圈/离线横幅）+ 设置入口。
- 主区：仓库卡片列表（拟态凸起卡）——仓库名、owner、当前分支、未提交文件数徽标、ahead/behind、最后打开时间。
- 空态：一张"克隆你的第一个仓库"引导卡 + 支持平台说明。
- 主操作：FAB「+」→ 克隆 / 从剪贴板识别 URL（自动解析 `https://host/owner/repo(.git)`）。
- 长按仓库：置顶 / 重新拉取 / 移除本地副本（二次确认 + 说明"仅删本地，不影响远端"）。
- 下拉刷新：所有仓库 fetch（可取消）。

**仓库详情 `repo/{id}`**
- 顶部 Header：仓库名 + 分支选择器（点开底部抽屉，含本地/远端分支与搜索）+ ahead/behind + 「拉取」「推送」按钮（拟态圆钮）。
- Tab：变更 / 历史 / 文件 /（PR）。
- 变更 Tab：已暂存 / 未暂存两段（可折叠），每行显示状态字母（M/A/D/R/U）+ 路径 + 行数增删；行左滑 = 暂存/取消暂存，右滑 = 丢弃（需二次确认）。
- 历史 Tab：提交列表（作者头像色块 + 首行 + 相对时间 + 短 oid），点击进 diff；顶部显示当前分支。
- 文件 Tab：直接进文件树根目录（复用 `browse` 的列表组件，不重复实现）。

**文件浏览 `browse`**
- 顶部：面包屑（可点击回退任意层级）+ 分支/ref 切换 + 「…」菜单（排序、显示隐藏文件、在此新建文件）。
- 列表：目录在前，文件在后；右侧显示大小/修改时间（可关）。
- 每行：图标 + 名称 +（若在工作区有变更）状态点。
- 点击目录 → 进下一层（保留滚动位置）；点击文件 → 按类型路由：文本 → `editor`（若 < 编辑上限）或 `viewer`；图片 → `viewer`；二进制 → `viewer`（元信息）。
- 长按：复制路径 / 分享 / 删除（二次确认）/ 查看历史（第二阶段）。

**编辑器 `editor`**
- 顶栏：文件名（可点开路径全称）+ 未保存标记 + 「保存」（自动保存开启时显示为状态而非按钮）+ 「…」（换行符、编码、撤销/重做、查找）。
- 主体：行号 gutter（与文本同步滚动）+ 文本区（`BasicTextField(state)`，等宽字体，可调字号 12–20sp）。
- 底部工具条（IME 弹出时随之上移）：撤销、重做、查找、Tab/缩进、行尾。
- 只读模式：文件超上限、二进制、或仓库处于"安全模式"时，隐藏键盘并显示横幅。
- 退出拦截：有未保存修改 → 底部弹层「保存并退出 / 放弃修改 / 取消」，**不弹系统对话框**。

**Diff `diff`**
- 顶栏：文件名 + 范围切换（工作区 ↔ 暂存区 ↔ 提交）+ 「上一处/下一处变更」。
- 主体：统一视图（手机默认）/ 分栏（横屏或 Expanded）。行号双列（旧/新），变更块左侧 3dp 色条 + `+/-` 符号。
- 折叠：连续未变更 > 6 行折叠为「⋯ 展开 24 行」。
- 底部：文件间左右滑动切换（`HorizontalPager`），顶部显示 "3/12"。
- 大文件降级：显示统计 + 前 500 行 + 「在查看器中打开」。

**提交 `commit`**
- 变更清单（复选框，默认全选已暂存项）+ 每项可展开预览 diff。
- 提交信息输入（多行，记忆上次使用；第二阶段做历史建议）。
- 选项：提交后立即推送（开关，记住上次选择）、提交到当前分支（显示分支名，点击可切换——**默认不提供新建分支**，避免误操作）。
- 底部主按钮：「提交 (n 个文件)」，未填信息时禁用并提示。
- 结果：成功 → 返回详情页 + Snackbar「已提交 abc1234」+「撤销」按钮（`reset --soft` 到父提交，5 秒内有效）。

**设置 `settings`**（唯一入口，全部来自注册表）

| 分组 | 配置项 | 类型/默认 | 说明 |
|---|---|---|---|
| 外观 | 主题模式 | 跟随系统/浅色/深色 | 全局唯一 |
| 外观 | 浅色配色 | Mist（默认）/Paper/Moss | 6 套中选 |
| 外观 | 深色配色 | Graphite（默认）/Midnight/Moss | 独立于浅色 |
| 外观 | 编辑器字号 | 12–20sp，默认 14 | 影响编辑器与 diff |
| 外观 | 动态取色 | 关（默认） | 开启会覆盖配色，需提示冲突【待确认是否保留】 |
| 编辑器 | 自动保存 | 开（默认） | 关闭后需手动保存 |
| 编辑器 | 制表符宽度 | 2/4/8，默认 4 | — |
| 编辑器 | 默认换行符 | 跟随文件/ LF / CRLF | 默认跟随 |
| 编辑器 | 显示行号 | 开 | — |
| Git | 作者名 / 邮箱 | 空 | 提交前必须填，未填时提交页拦截 |
| Git | 克隆默认分支 | 远端 HEAD | — |
| Git | 进入仓库自动 fetch | 开 | 弱网下可关 |
| 网络 | 超时（连接/读） | 10s/60s | 高级项，折叠 |
| 存储 | 已用空间 / 清理缓存 | — | 显示明细，可清 diff 缓存 |
| 安全 | 生物识别解锁 token | 关 | 第二阶段 |
| 关于 | — | — | 见下 |

**关于页 `about`**
| 区块 | 内容 |
|---|---|
| 应用名 | GitPocket（+ 图标） |
| 版本号 | `versionName (versionCode)` + 构建时间 + commit short sha（由 CI 注入） |
| 技术栈 | Kotlin x.y / Jetpack Compose x.y / Room x.y / 自研 Git 引擎（无 JGit/libgit2） |
| 支持范围 | 平台：GitHub / GitLab / Gitea（HTTPS）；协议 v0/v2；不支持：SSH、LFS、submodule、SHA-256 仓库、force push |
| 权限说明 | 见下 |
| 开源许可 | 自研代码许可 + 依赖许可列表（Compose/Room 等 Apache-2.0） |
| 隐私 | 不收集任何数据；token 仅存本机 KeyStore；无第三方统计 SDK |

权限说明（关于页必须逐条写"为什么"）：
| 权限 | 用途 | 备注 |
|---|---|---|
| `INTERNET` | 克隆/拉取/推送、PR/CI 查询 | 必需 |
| `ACCESS_NETWORK_STATE` | 判断在线/离线，弱网提示 | 必需 |
| `POST_NOTIFICATIONS` | 后台同步完成/失败通知（Android 13+ 运行时申请） | 第二阶段，可拒绝 |
| `USE_BIOMETRIC` | 解锁 token | 第二阶段，可选 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 大仓库 clone/push 期间保持前台 | 第二阶段 |
| 不申请 | 存储读写权限（用 SAF 导出）、通讯录、位置、相机 | 明确声明"不申请" |

---

## 5. 交互与体验原则

### 5.1 防误操作
1. **破坏性操作三件套**：二次确认（底部弹层，不是系统 Dialog）+ 滑动需越过 40% 阈值 + 5 秒 Undo Snackbar。适用：丢弃修改、删除分支、删除本地仓库、强制覆盖草稿。
2. **提交不可怕，推送才可怕**：commit 是本地操作，给 Undo（soft reset）；push 前若检测到 `behind > 0` 直接拦下并提示先 pull。
3. **返回键语义**：编辑页有未保存修改 → 拦截并给三选一；其余页面正常返回。禁止"返回即丢弃"。
4. **不提供危险默认**：默认不 force push、默认不新建分支、默认不删除远端分支、默认不执行 `reset --hard`。
5. **滑动操作可关闭**：设置里提供"关闭列表滑动操作"（部分用户会误触）。

### 5.2 Diff 更清晰
- **三重编码**：颜色 + 左侧 3dp 色条 + 行首 `+`/`-`/空格。色弱用户也能读。
- 行号双列对齐，删除行保留原行号，新增行显示新行号。
- 变更块之间留 8dp 间隔，上下文行用次级文字色。
- 单文件超过一屏时提供"变更地图"（右侧竖条标出所有变更位置，可拖动）。
- 暗色下 diff 背景用低饱和（避免刺眼），前景色对比度 ≥ 4.5:1。
- 大 diff 先给摘要（`+128 −36 · 7 个变更块`），再按需展开。

### 5.3 编辑尽量轻量
- 点击文件**直接进入编辑**（可编辑文件不做"查看→编辑"两跳）。
- 无"保存/取消"弹窗；自动保存 + 顶栏状态点（`●` 未保存 / `✓` 已保存 / `⟳` 保存中）。
- 撤销/重做走自研命令栈（按编辑批次合并，不是每个字符一条），支持 100 步。
- 查找：底部内联搜索条（不占满屏），显示 `3/17` 并可跳转。
- 大文件（> 512KB）默认只读并提示"文件较大，已切换为只读模式"，避免输入卡死。
- 换行符与编码在顶栏「…」里可见可改，改动时提示"将转换整个文件"。

### 5.4 提交流程顺滑
- 从变更页点「提交」→ 直接进提交页，已暂存项默认勾选。
- 提交信息记住上次内容（同仓库），空信息时按钮禁用而不是提交后报错。
- 「提交并推送」一个开关搞定，记住选择。
- 成功后回到详情页并刷新状态，Snackbar 提供 Undo（仅本地 commit）。
- 失败时错误信息定位到具体原因（认证失败 / 非快进 / 网络 / 冲突），并给一个可点的主行动按钮。

### 5.5 大仓库 / 慢网络 / 弱网
| 场景 | 策略 |
|---|---|
| 大仓库 clone | 进度分阶段显示（枚举 refs → 下载 pack → 解包 → 检出），可取消；取消后清理临时目录 |
| 大目录 | 分页 + 骨架屏；滚动中不做 `stat` |
| 慢网络 | 分级超时；`Range` 断点续传仅用于 pack 下载（若服务器支持）【未验证】 |
| 弱网/断网 | 顶部常驻"离线模式"横幅；写操作排队（commit 可离线，push 入队）；恢复后提示"有 1 个待推送" |
| 服务器限流（403/429） | 读 `Retry-After`，指数退避（1s→2s→4s，最多 3 次），失败给"稍后再试"而不是无限转圈 |
| 大 pack 解包 | 流式解包（边读边写对象），不整包进内存；显示已处理对象数 |

### 5.6 输入 / 保存 / 同步的稳定性
- **原子写**：所有文件写入走 `临时文件 → fsync → rename`；失败保留原文件。
- **写前校验**：保存前比对磁盘当前 blob 与编辑器加载时的 `baseOid`，不一致则提示"文件已被外部修改"，给"覆盖/另存为草稿/重新加载"。
- **串行化**：同一仓库的写操作经 `Mutex`；UI 上对同一仓库禁用并发的提交按钮（按钮进入 loading 态）。
- **崩溃恢复**：草稿 800ms 落盘，进程被杀最多丢 800ms 输入；重启后进入仓库时提示"发现未完成的草稿"。
- **IME 处理**：`imePadding()`，编辑器不因键盘弹出而跳动；光标滚动到可视区。
- **同步状态机**：`Idle → Fetching → Merging → Pushing → Done/Failed`，UI 只读这个状态，不允许各页面自己维护"是否在同步"。

### 5.7 Compose 下的状态管理与页面切换
- 每页一个 `ViewModel`，`StateFlow<UiState>`，`collectAsStateWithLifecycle()`。
- 单次事件用 `Channel<Effect>(BUFFERED)` + `receiveAsFlow()`，禁止用 `StateFlow` 发一次性事件（会重放）。
- 导航参数走类型安全 route；跨页面结果用 `SavedStateHandle`（如"提交成功"回传）。
- `LaunchedEffect` 的 key 必须明确且稳定，禁止 `LaunchedEffect(Unit)` 里读会变的状态。
- 页面切换保留滚动位置：`rememberSaveable` + `LazyListState` 的 `rememberLazyListState()`（配合 Navigation 的 `saveState/restoreState`）。
- 禁止在 composable 里做 IO；禁止在 ViewModel 里持有 `Context`（需要 `Application` 时通过工厂传入）。

---

## 6. 开发里程碑

> 工期为**单人全职估算**，标注【估算】，未经团队速率校准。

### M1 — v0.1「闭环可用」（10~12 周【估算】）

| 项 | 内容 |
|---|---|
| 目标 | 能在手机上完成"克隆 → 改一个文件 → 提交 → 推送"的完整闭环 |
| 范围 | 单账号、单仓库、HTTPS+PAT、clone/fetch/pull(ff)/commit/push、文件树、编辑器、行级 diff、冲突选一边、主题 4 套、设置、关于页 |
| 交付物 | 可安装 APK（debug + signed release）、`core:git` 单测覆盖核心解析、真实 fixture 验证报告、README、CI 跑通 tag 发版 |
| 主要风险 | packfile + thin pack + push pack 生成（占 M1 风险的 60%）；Compose 文本输入在 200KB 文件下的表现【未验证】；不同 Git 服务器 push 兼容性 |
| 验收 | 在 GitHub / GitLab / Gitea 三个真实仓库上完成闭环；`git fsck` 无报错；`git status` 输出与应用内状态一致 |

拆分建议（按周）：
1. W1–W2：`core:git` 对象库（loose + pack 读）+ refs + 真实 fixture 测试框架
2. W3–W4：index 读写 + status + worktree checkout
3. W5–W6：transport v2 + clone/fetch（含 thin pack）
4. W7：diff（Myers）+ merge（diff3）+ 冲突写 index
5. W8：push（零 delta pack）+ 非快进检测
6. W9–W10：UI 骨架 + 主题系统 + 首页/详情/文件树
7. W11：编辑器 + 草稿 + diff 页 + 提交页
8. W12：冲突页 + 设置/关于 + 打磨 + 发版

### M2 — v0.5「好用」（+6~8 周【估算】）

| 项 | 内容 |
|---|---|
| 目标 | 从"能用"到"愿意每天用" |
| 范围 | PR/MR 列表与详情、CI 状态、Issue 只读、多账号、语法高亮（自研 lexer）、行内 diff、部分克隆（blob:none）、生物识别、后台同步通知 |
| 交付物 | 平台 API 适配层（GitHub/GitLab/Gitea 各一套 + 统一模型）、性能基线报告（首屏 < 1.2s、滚动 60fps） |
| 主要风险 | 三个平台 API 语义差异导致适配层膨胀；部分克隆的 lazy fetch 与离线冲突；语法高亮的性能 |
| 验收 | 大仓库（>50 万文件）能打开文件树；PR 列表 200 条滚动不掉帧；离线打开不崩 |

### M3 — v1.0「完整产品」（+8 周【估算】）

| 项 | 内容 |
|---|---|
| 目标 | 稳定、可长期维护、可上架 |
| 范围 | 平板/折叠双栏、Widget、快捷方式、blame、文件历史、仓库体积分析、patch 导入导出、无障碍（TalkBack）全量适配、崩溃恢复与诊断包导出 |
| 交付物 | 上架素材（截图/描述/隐私政策）、性能与稳定性报告（崩溃率 < 0.5%）、完整文档集 |
| 主要风险 | 双栏带来的状态同步复杂度；blame 的性能（需要逐提交 diff，成本高，可能降级为"仅最近 200 次提交"） |
| 验收 | TalkBack 可完成主流程；冷启动 P90 < 1.5s；连续 1 小时操作无 ANR |

---

## 7. 风险与难点

### 7.1 最大的技术难点

**不是 UI，是"自研 Git 引擎的二进制格式与传输协议正确性"。** 具体排序：

| 排名 | 难点 | 为什么难 | 缓解 |
|---|---|---|---|
| 1 | packfile 解析（ofs-delta / ref-delta / thin pack） | 变长编码细节多，错一个字节整包报废；thin pack 需要补 base 并重算校验和 | 用真实 pack 做字节级 fixture；与 `git verify-pack -v` 输出逐项比对 |
| 2 | push 的 pack 生成 + report-status 解析 | 服务器实现差异（GitHub/GitLab/Gitea 对 capability 的要求不同） | 零 delta 全量 pack；三家服务器真机实测；失败时保留原始响应便于诊断 |
| 3 | index 写回 | 62 字节定长 + 对齐填充 + 扩展区；写坏会导致 `git status` 全乱 | 写完立刻用真实 `git status --porcelain` 校验（CI 内） |
| 4 | 三方合并正确性 | diff3 的冲突边界判定容易多/少标冲突 | 用 git 自身产生的 merge 冲突作为对照 fixture |
| 5 | 大文本编辑性能 | Compose `BasicTextField` 在 MB 级文本下的输入延迟 | 编辑上限 512KB；超限只读；`TextFieldState` 细粒度读取【未验证】 |

### 7.2 最容易做复杂的地方（要主动砍）

- **rename detection**：实现成本高、误判多。砍掉，接受"重命名显示为 D+A"。
- **完整 commit graph + generation number**：只做 BFS merge-base，不做增量位图。
- **多 pack + gc/repack**：首版单 pack（或少量 pack）+ 只读；不做自动 repack。
- **语法高亮**：不做通用 TextMate 引擎，只做 6~8 种语言的轻量 lexer。
- **Markdown 渲染**：不做完整 CommonMark，只做标题/列表/代码块/链接。
- **设置项**：不提供"高级 Git 配置编辑器"（那是给 1% 用户挖的坑）。

### 7.3 建议后置的功能
SSH、LFS、submodule、force push、rebase/cherry-pick、hooks、多 pack 优化、rename detection、AI commit message、Wear OS。

### 7.4 适合"简单但可靠"的地方（明确取舍）

| 地方 | 简单方案 | 放弃的东西 |
|---|---|---|
| push pack | 零 delta 全量对象 | 30~50% 额外流量【估算】 |
| merge-base | 双向 BFS 首个公共祖先 | 极端情况下的"最小"merge base |
| 大文件 diff | 统计 + 前 500 行 | 完整可视 diff |
| 二进制文件 | 只显示元信息 | 图片 diff/预览（第二阶段做图片预览） |
| 网络层 | `HttpURLConnection` + 手写重试 | OkHttp 的连接池/HTTP2 多路复用（可接受，Git 传输是单流） |
| 认证 | PAT | OAuth 的流畅体验（第二阶段补） |
| 存储 | 应用私有目录 | 直接编辑 SD 卡既有仓库（用导出补偿） |

### 7.5 必须写测试的内容

| 模块 | 测试类型 | 关键用例 |
|---|---|---|
| `core:git` 对象层 | JVM 单测 + 真实 fixture | loose 读写往返、pack 索引越界、delta 应用、thin pack 补薄、SHA-1 校验失败 |
| `core:git` index | JVM 单测 + 交叉验证 | v2 读写往返、路径对齐填充、扩展区保留/丢弃、大小写冲突检测 |
| `core:git` refs | JVM 单测 | packed-refs 解析、peeled 行、lock 竞争 |
| `core:git` diff/merge | JVM 单测 + git 对照 | 空文件、全删、CRLF、无尾换行、二进制、冲突块边界 |
| transport | JVM 单测（本地假服务器）+ 集成 | pkt-line 编解码、sideband 分流、v2→v0 回落、401/403/429 |
| porcelain | 集成（真仓库） | clone→改→commit→push 全链路 |
| Room 迁移 | `MigrationTestHelper` | 每个版本 n-1→n、n-2→n、大数据量迁移 |
| Settings | JVM 单测 | 默认值、类型映射、主题解析、非法值回落 |
| ViewModel | JVM 单测 | 状态机流转、错误映射、取消 |
| Compose UI | 仪器测试（少量） | 提交流程、主题切换、返回拦截 |

### 7.6 必须做"真实文件验证"的内容（不能只看编译通过）

1. **packfile**：用 `git clone --bare` 生成真实 pack，与 `git verify-pack -v` 的输出逐对象比对（oid/type/size/offset）。
2. **index**：我方写出的 index 用真实 `git status --porcelain` 校验；再用 `git ls-files --stage` 比对条目。
3. **commit/tree**：我方序列化的对象用 `git cat-file -p` 反解，字段逐个比对；`git fsck --strict` 必须无错。
4. **push**：推到真实 GitHub/GitLab/Gitea 私有仓库，然后 `git clone` 下来用 `git log --graph` 校验结构。
5. **merge**：构造真实冲突仓库，与 `git merge` 产生的冲突标记逐行对照。
6. **zlib 边界**：0 字节文件、1 字节文件、10MB 随机二进制。
7. **大小写冲突**：在大小写不敏感文件系统上验证拒绝逻辑。

---

## 8. 数据迁移与持久化策略

### 8.1 存储分层

| 层 | 技术 | 存什么 | 为什么不放别处 |
|---|---|---|---|
| 结构化元数据 | Room（SQLite） | 账号元数据、仓库、ref 快照、commit 摘要缓存、草稿、操作日志 | 需要查询/排序/事务 |
| 轻量设置 | DataStore(Preferences) | 全部设置项 | 需要 Flow、原子写、无 schema 负担 |
| 二进制仓库 | 文件系统（应用私有目录） | objects/refs/index/HEAD/config | Git 格式就是文件，进 DB 无意义且会撑爆 |
| 密钥 | AndroidKeyStore + 私有文件 | token 密文、密钥句柄 | 需要硬件级保护 |
| 临时数据 | `cacheDir` | diff 结果、解压中间文件、日志、DB 升级备份 | 系统可回收，不需要保证 |

### 8.2 表设计（Room，v1）

```sql
CREATE TABLE account (
  id TEXT PRIMARY KEY NOT NULL,
  host TEXT NOT NULL,
  username TEXT NOT NULL,
  tokenRef TEXT NOT NULL,          -- 指向密文文件，不存明文
  createdAt INTEGER NOT NULL,
  lastUsedAt INTEGER
);
CREATE UNIQUE INDEX idx_account_host_user ON account(host, username);

CREATE TABLE repo (
  id TEXT PRIMARY KEY NOT NULL,
  accountId TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  owner TEXT NOT NULL,
  name TEXT NOT NULL,
  remoteUrl TEXT NOT NULL,
  localPath TEXT NOT NULL,
  defaultBranch TEXT,
  lastOpenedAt INTEGER NOT NULL,
  sizeBytes INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_repo_lastOpened ON repo(lastOpenedAt DESC);

CREATE TABLE ref_snapshot (          -- 缓存，可随时重建
  repoId TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  oid TEXT NOT NULL,
  isRemote INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  PRIMARY KEY(repoId, name)
);

CREATE TABLE commit_cache (          -- 只缓存列表展示字段，正文不缓存
  repoId TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  oid TEXT NOT NULL,
  parents TEXT NOT NULL,           -- 逗号分隔，最多 2 个
  authorName TEXT NOT NULL,
  authoredAt INTEGER NOT NULL,
  subject TEXT NOT NULL,
  PRIMARY KEY(repoId, oid)
);
CREATE INDEX idx_commit_time ON commit_cache(repoId, authoredAt DESC);

CREATE TABLE draft (
  id TEXT PRIMARY KEY NOT NULL,
  repoId TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  path TEXT NOT NULL,
  baseOid TEXT,                    -- 草稿基于的 blob，可空（新文件）
  content TEXT NOT NULL,
  updatedAt INTEGER NOT NULL,
  status TEXT NOT NULL             -- CLEAN | DIRTY | CONFLICT | STALE
);
CREATE UNIQUE INDEX idx_draft_path ON draft(repoId, path);

CREATE TABLE op_log (                -- 诊断用，保留 30 天
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repoId TEXT,
  type TEXT NOT NULL,              -- CLONE/FETCH/PULL/PUSH/COMMIT
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  result TEXT,                     -- OK/FAILED/CANCELLED
  message TEXT
);
```

> 注意：`draft.content` 用 TEXT 存全文。单草稿上限 512KB（与编辑上限一致），避免 DB 膨胀；超限不建草稿，改为提示。

### 8.3 Schema 演进规则（硬规则）

1. **`exportSchema = true`**，schema JSON 提交进仓库：`core/database/schemas/<dbClass>/<version>.json`。CI 校验"改了 entity 但没升 version"（Room 编译期会报错，配合 `room.schemaLocation` 即可）。
2. **禁止 `fallbackToDestructiveMigration()`**，也禁止 `fallbackToDestructiveMigrationOnDowngrade()`。代码审查 + 自定义 lint 规则（或简单 grep 的 CI 检查）双重拦截。
3. **只增不删**：删除列/表至少跨两个版本——先停止读写（vN），再物理删除（vN+2）。
4. **新增 NOT NULL 列必须带 DEFAULT，或分两步**（先 nullable → 回填 → 再改 NOT NULL，SQLite 不支持直接改列约束，需要建新表 + 复制）。
5. **迁移只做结构变更 + 必要的回填，不做业务逻辑**（不要在迁移里调 Repository）。
6. **每个迁移配一个测试**，且必须覆盖"从 n-2 直接升到 n"的路径。

### 8.4 迁移文件组织

```
core/database/
├── schemas/app.gitpocket.core.database.GitPocketDatabase/1.json
├── schemas/.../2.json
└── src/main/kotlin/app/gitpocket/core/database/
    ├── GitPocketDatabase.kt        // version = 3, @Database(...)
    ├── migration/
    │   ├── Migrations.kt           // val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    │   ├── Migration1To2.kt
    │   └── Migration2To3.kt
    └── entity/...
```

```kotlin
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新增可空列，避免 NOT NULL 无默认值导致失败
            db.execSQL("ALTER TABLE repo ADD COLUMN defaultBranch TEXT")
            db.execSQL("UPDATE repo SET defaultBranch = 'main' WHERE defaultBranch IS NULL")
        }
    }
    val ALL = arrayOf(MIGRATION_1_2)
}

fun create(context: Context) = Room.databaseBuilder(context, GitPocketDatabase::class.java, "gitpocket.db")
    .addMigrations(*Migrations.ALL)
    // 不调用 fallbackToDestructiveMigration：迁移缺失时宁可崩溃也不要删数据
    .build()
```

### 8.5 版本升级 / 兼容 / 回滚风险

| 风险 | 表现 | 处理 |
|---|---|---|
| 迁移缺失 | Room 抛 `IllegalStateException: A migration from X to Y was required` | 视为 P0 缺陷；但**运行时兜底**：捕获后进入"安全模式"（只读 + 顶部横幅"数据需要更新，请升级应用"），绝不删库 |
| 迁移失败（磁盘满/约束冲突） | 迁移事务回滚 | 升级前自动备份：`cacheDir/db-backup/v{old}-{ts}.db`，保留最近 3 份/7 天；失败时提示可导出备份 |
| 用户降级安装（DB 版本 > 代码版本） | Room 抛 `Downgrade not supported` | 捕获后进入安全模式，提示"请升级到最新版本"；**不提供降级迁移**（无法预知未来 schema） |
| 迁移耗时过长（10 万行） | ANR | 迁移在 Room 的 open 回调里同步执行；表规模可控（草稿/缓存），预估 < 2s【估算】；超 1 万行时先清理缓存表再迁移 |
| 字段语义变更 | 老数据含义错 | 不改语义，只加新列 + 双写过渡 |

**验证迁移正确性（三件套）**：
1. `MigrationTestHelper` + 每个历史版本 schema JSON → 建 vN 库、插数据、迁到 vN+1、断言数据与结构。
2. **真机升级测试**：安装 vN 的 release APK → 造数据 → 覆盖安装 vN+1 → 检查数据完整（进发版 checklist）。
3. **大数据量测试**：插入 1 万条草稿 + 5 万条 commit_cache 后跑迁移，记录耗时。

### 8.6 什么该持久化，什么只能临时缓存

| 数据 | 策略 | 理由 |
|---|---|---|
| 账号（host/username） | 持久化 | 避免重复输入 |
| token 明文 | **绝不落盘明文**，只存 KeyStore 包裹的密文 | 安全 |
| 仓库本地副本 | 持久化（可被用户删除） | 是用户资产 |
| ref 快照 | 持久化但可重建 | 加速首屏，损坏可丢弃 |
| commit 摘要 | 持久化 + LRU 上限（每仓库 5000 条） | 加速历史列表，可重建 |
| 草稿 | 持久化（唯一"用户可能丢失"的数据，必须最稳） | 用户输入不可重建 |
| diff 计算结果 | **仅 cacheDir** | 可重算，占用大 |
| 语法高亮 token | 仅内存 + 小 LRU | 可重算 |
| HTTP 响应原文 | 仅内存（诊断时导出） | 可能含敏感信息 |
| 操作日志 | 持久化 30 天，不含 token/URL 参数 | 排障需要 |
| 崩溃堆栈 | 仅本地文件，用户主动导出 | 隐私 |

---

## 9. 工程规范与发布规范

### 9.1 目录结构

```
git-pocket/
├── settings.gradle.kts
├── build.gradle.kts                 # 统一编译选项（含零警告开关）
├── gradle/libs.versions.toml        # 版本目录（唯一版本源）
├── gradle/wrapper/                  # Gradle 8.11.1
├── app/
│   ├── build.gradle.kts             # 签名唯一实现 + tag 派生版本号
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/app/gitpocket/
│       │   ├── MainActivity.kt      # 唯一主题入口 + CompositionLocalProvider
│       │   ├── GitPocketApplication.kt
│       │   ├── AppContainer.kt      # 手写 DI 容器
│       │   └── navigation/AppNavHost.kt
│       └── res/                     # 主题、图标（自适应图标 + monochrome）
├── core/
│   ├── model/          # 纯 Kotlin：ObjectId/Repo/Diff/ThemeSettings
│   ├── git/            # 纯 JVM：对象库/packfile/index/refs/diff（24 个单测）
│   ├── common/         # 调度器、格式化、日志脱敏、仓库锁
│   ├── designsystem/   # 主题、拟态组件、排版
│   ├── database/       # Room v2 + 显式迁移 + schemas/1.json、2.json
│   ├── datastore/      # 设置注册表（DataStore）
│   ├── security/       # AndroidKeyStore AES-GCM token 封装
│   └── data/           # 仓库门面（Git + Room + 设置）+ CompositionLocal
├── feature/
│   ├── repos/          # 首页仓库列表
│   ├── repo/           # 仓库详情（变更/历史/文件）+ 文件浏览
│   ├── editor/         # 编辑器 + diff
│   ├── commit/         # 提交
│   └── settings/       # 设置 + 关于
├── scripts/gen-fixtures.sh
├── docs/               # 本方案 + MVP 需求清单
├── .github/workflows/  # pr.yml / release.yml
├── keystore.properties.example
├── CHANGELOG.md
└── README.md
```

> 与设计稿的差异（已落地版本为准）：未使用 `build-logic` 复合构建（改为根 `build.gradle.kts`
> 统一 compilerOptions，减少构建复杂度）；feature 模块由 8 个合并为 5 个（browse 并入 repo，
> diff 并入 editor），避免过度拆分带来的样板。其余约束（无第三方 UI/DI、自研 Git 内核、
> 单一主题入口、设置注册表、显式迁移）全部保留。

### 9.2 签名方案（两条通道，一套实现）

```kotlin
// build-logic: SigningConventionPlugin.kt
fun Project.resolveReleaseSigning(): SigningConfig? {
    // 通道 1：本地 keystore.properties（不进版本库）
    val propsFile = rootProject.file("keystore.properties")
    val fromFile = if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use(::load) }
        SigningConfig().apply {
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    } else null

    // 通道 2：CI 环境变量（keystore 由 secret 解码到临时路径）
    val fromEnv = System.getenv("GITPOCKET_KEYSTORE_FILE")?.let { path ->
        SigningConfig().apply {
            storeFile = file(path)
            storePassword = System.getenv("GITPOCKET_STORE_PASSWORD")
            keyAlias = System.getenv("GITPOCKET_KEY_ALIAS")
            keyPassword = System.getenv("GITPOCKET_KEY_PASSWORD")
        }
    }

    return fromFile ?: fromEnv   // 本地优先，CI 次之；两条通道共用同一实现
}
```

规则：
- `keystore.properties` 与 `*.jks` **必须进 `.gitignore`**；仓库只保留 `keystore.properties.example`。
- 请求 release 构建但解析不到签名 → **直接 `throw GradleException`**，绝不静默回落到 debug 签名（否则会出现"发出去的包是 debug 签名"的事故）。
- CI 里 keystore 以 base64 存 secret，构建前解码到 `$RUNNER_TEMP`，构建后删除。
- 发布前用 `apksigner verify --print-certs` 断言证书指纹与预期一致。

### 9.3 CI 构建方案

```yaml
# .github/workflows/pr.yml
name: pr
on: [pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit tests (JVM)
        run: ./gradlew testDebugUnitTest --stacktrace
      - name: Migration tests
        run: ./gradlew :core:database:connectedDebugAndroidTest   # 需 emulator runner
      - name: Lint
        run: ./gradlew lintDebug
      - name: Assemble
        run: ./gradlew assembleDebug
      - name: 真实文件验证
        run: ./scripts/verify-index.sh
```

```yaml
# .github/workflows/release.yml
name: release
on:
  push:
    tags: ['v[0-9]+.[0-9]+.[0-9]+']
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4

      - name: Decode keystore
        env: { KS: '${{ secrets.KEYSTORE_BASE64 }}' }
        run: echo "$KS" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Build signed release
        env:
          GITPOCKET_KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          GITPOCKET_STORE_PASSWORD: ${{ secrets.STORE_PASSWORD }}
          GITPOCKET_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          GITPOCKET_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
          GIT_TAG: ${{ github.ref_name }}
        run: ./gradlew :app:assembleRelease :app:bundleRelease

      - name: Verify signature
        run: |
          apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
          # 断言证书 SHA-256 等于预期值，防止签错 key

      - name: Publish
        env: { GH_TOKEN: '${{ secrets.GITHUB_TOKEN }}' }
        run: |
          gh release create "$GITHUB_REF_NAME" \
            app/build/outputs/apk/release/app-release.apk \
            app/build/outputs/mapping/release/mapping.txt \
            --title "GitPocket $GITHUB_REF_NAME" --generate-notes
```

### 9.4 版本号与 tag 方案（单一版本源）

- **唯一真源 = git tag**（`v1.2.3`）。
- CI 中：`versionName = tag 去掉 v`；`versionCode = major*10000 + minor*100 + patch`（如 1.2.3 → 10203）。**禁止手改 versionCode**。
- 本地开发：无 tag 时 `versionName = "0.0.0-dev"`，`versionCode = 1`，避免本地包覆盖线上包。
- tag 规范：`vMAJOR.MINOR.PATCH`，只允许在 `main` 上打；打 tag 前必须 PR CI 绿。
- 发版 checklist：改 CHANGELOG → 打 tag → 等 CI → 检查 Release 资产（APK/Bundle/mapping/SHA256）→ 手动上架（首版不做全自动上架，避免误发）。

### 9.5 编译零警告的落地

| 项 | 配置 |
|---|---|
| Kotlin | `allWarningsAsErrors = true`（全模块），`freeCompilerArgs += "-Xjvm-default=all"` 之类按需 |
| Java | `options.compilerArgs += ["-Werror"]` |
| Lint | `lint { warningsAsErrors = true; abortOnError = true; checkDependencies = true }`，仅 release 变体启用（debug 允许临时警告） |
| 抑制 | `@Suppress` 必须带原因注释，且 CI 统计数量（只减不增） |
| 依赖 | `dependencyAnalysis` 或手写检查未使用依赖（可选，非阻塞） |

> 现实提醒：`warningsAsErrors` 在引入新 AGP/Compose 版本时可能因弃用警告批量失败。建议**先在 `main` 上打开，依赖升级用独立 PR**，不要在功能 PR 里混。

### 9.6 README 结构（必须覆盖）

```
# GitPocket
一句话介绍 + 截图（3 张：仓库列表 / diff / 编辑器）
## 特性
## 支持范围与不支持项        ← 明确列出 SSH/LFS/submodule 等
## 环境要求（JDK 21、AGP x.y、Android Studio x.y）
## 本地开发流程
  1. clone 仓库
  2. cp keystore.properties.example keystore.properties 并填写
  3. ./gradlew :app:assembleDebug
  4. 运行单测 / 仪器测试
  5. 代码风格与提交规范
## 构建流程（debug / release / bundle）
## 签名流程（本地 keystore.properties / CI 环境变量，二者等价，缺失即失败）
## 发版流程（改 CHANGELOG → tag → CI → 校验资产 → 上架）
## CI 流程（PR 做什么、tag 做什么、需要哪些 secrets）
## 项目结构
## 测试策略（单测范围 + 真实文件验证）
## 数据与隐私（token 存哪、收集什么）
## 常见问题（认证失败、非快进、大仓库）
## 许可证
```

### 9.7 单元测试范围（按优先级）

1. `core:git` 全部解析器（P0，覆盖率目标 ≥ 80%，关键解析器 ≥ 90%）
2. `core:git` porcelain 的状态机（P0）
3. Room 迁移（P0，每个版本）
4. Repository 缓存与并发（P1）
5. ViewModel 状态流转（P1）
6. 设置注册表映射（P1）
7. Compose UI 关键路径（P2，仪器测试 5~8 个）

> 覆盖率是参考指标，**不以覆盖率为目标**；`core:git` 的 fixture 测试比行覆盖率更有价值。

### 9.8 需要补充的文档清单

| 文档 | 内容 | 何时写 |
|---|---|---|
| `Git引擎实现说明.md` | 对象/索引/pack/协议的实现细节与已知偏差 | M1 期间 |
| `数据迁移手册.md` | 如何加迁移、如何测试、回滚预案 | M1 前 |
| `发版手册.md` | 逐步操作 + 事故处理（签错 key、CI 失败） | M1 前 |
| `测试与验证策略.md` | fixture 生成、交叉验证脚本用法 | M1 前 |
| `CONTRIBUTING.md` | 分支模型、提交规范、代码审查清单（含"禁止 destructive migration"等硬规则） | M1 前 |
| `PRIVACY.md` | 隐私政策（上架必需） | M2 |
| `CHANGELOG.md` | 版本变更 | M1 起 |
| `ADR/` | 关键决策记录（本文件第 0 节可拆成 ADR-001~012） | 持续 |

---

## 10. 命名与品牌

| 项 | 内容 |
|---|---|
| 产品标语（一句话） | **Git 装进口袋，改动随手落地。** |
| 商店简介（≤80 字） | 轻量 Android Git 管理器：浏览仓库、改一行、提交、推送，几分钟搞定。原生 Compose，拟态界面，支持 GitHub / GitLab / Gitea。 |
| 启动页短口号 | **Pocket your changes.**（中文备选：**改动，随手提交。**） |
| 图标方向 | "口袋"轮廓 + 内嵌分支节点（三个圆点 + 折线）。要求：单色可辨识（16dp 下仍能看出是口袋+分支）、无文字、避免使用 GitHub 的 Octocat 或任何第三方商标、圆角方形底（自适应图标 foreground/background 分层，monochrome 层供 Android 13 主题图标）。配色建议：深靛蓝底 + 米白线条，或与默认主题 `Mist` 的 accent 一致。 |
| 视觉风格 | 拟态（Neumorphism）+ 低饱和：圆角 20–28dp，间距 4/8/12/16/24 栅格，字号 12/14/16/20/24sp，动效 120–200ms `FastOutSlowIn`，阴影只用在"少而大"的元素上。**禁止**：渐变滥用、玻璃拟态混搭、多种阴影方向并存。 |
| 命名建议 | 包名 `app.gitpocket.android`（或 `io.gitpocket.app`）。**"GitPocket" 商标与域名可用性未验证【待确认】**，上架前需检索。命名避坑：不要用 "Git" 开头做应用名主体（商店检索易与 GitHub 官方混淆），"GitPocket" 作为整体词可以。 |

---

## 11. 额外文案

- **商店短介绍**：GitPocket — 口袋里的 Git 管理器。看仓库、改一行、提交推送，一分钟搞定。
- **团队内部一句话总结**：GitPocket 是用 Kotlin + Compose 自研 Git 引擎的安卓原生轻客户端，只做"碎片时间能做完的 Git 操作"，不做手机 IDE。
- **产品海报宣传语**：**一行代码的距离，一个口袋的 Git。**

---

## 12. 未验证 / 待确认清单

| # | 项 | 状态 | 影响 | 验证方式 |
|---|---|---|---|---|
| 1 | Play 自 2026-08-31 起要求 target API 36 | **已核实**（developer.android.com） | 决定 compile/targetSdk | — |
| 2 | `androidx.security:security-crypto` 弃用状态与替代方案 | 待确认 | token 存储选型 | 查 Jetpack release notes；本方案已改用 KeyStore 自研，风险已规避 |
| 3 | Compose `BasicTextField` + `TextFieldState` 在 200KB~1MB 文本下的输入延迟 | **未验证** | 决定编辑上限与降级策略 | 真机（低端机 + 旗舰机）实测输入延迟与内存 |
| 4 | `setShadowLayer` 在硬件加速画布上的实际表现（API 26–30） | **未验证** | 拟态视觉一致性 | 真机逐版本截图比对 |
| 5 | RenderEffect 模糊在列表滚动时的帧率 | **未验证** | 是否允许在列表用模糊 | Macrobenchmark + FrameMetrics |
| 6 | 三家服务器（GitHub/GitLab/Gitea）对 push pack 与 capability 的兼容性 | **未验证** | M1 能否按时交付 | 三家私有仓库真机跑通 |
| 7 | thin pack 在各服务器的实际出现频率 | **未验证** | 补薄逻辑是否必需（保守假设：必需） | 抓取真实 fetch 响应分析 |
| 8 | Android 文件系统大小写敏感性差异 | **未验证**（多数设备不敏感） | 分支名冲突处理 | 多机型实测 + 代码内做运行时探测 |
| 9 | 10 万行 diff 的计算耗时与内存 | **未验证** | 阈值设定（5000 行）是否合理 | Benchmark 实测后调整 |
| 10 | 是否允许 kotlinx.serialization / Turbine（"第三方"边界） | **待确认** | 导航参数与测试写法 | 团队/规范拍板；已有手写替代方案 |
| 11 | "GitPocket" 商标与包名可用性 | **待确认** | 上架 | 商标检索 + 域名查询 |
| 12 | 是否允许 CI 使用第三方 Action（如 gradle/actions） | **待确认** | CI 写法 | 一般允许（仅构建工具链，非应用依赖） |
| 13 | 工期估算 10~12 周 | **估算** | 排期 | 需按团队速率校准 |
| 14 | push 零 delta pack 的体积增幅 30~50% | **估算** | 流量体验 | 实测对比 |

---

## 13. 结论性取舍（一句话版）

**用"自研 Git 引擎 + 极简 UI + 明确的不支持清单"换"离线、隐私、可控"，用"零 delta push / 行级 diff / 简单 merge"换"6 周内能交付"，用"模块化 + 显式迁移 + 真实文件验证"换"半年后还能改得动"。**

---

## 14. 实现进度与验证记录（2026-10-20）

本节记录**代码实际落地状态**，与上文设计稿逐项对应；未落地的部分明确标注，不当作已完成。

### 14.1 已实现并通过验证

| 能力 | 代码位置 | 验证方式 | 结果 |
|---|---|---|---|
| loose object 读写 + SHA-1 校验 | `core/git/ObjectDatabase.kt` | 单测往返 + 已知 oid 对齐 | ✅ |
| packfile 读取（idx v2 / ofs-delta / ref-delta / 校验和） | `core/git/PackFile.kt` | `git gc --aggressive` 真实产物 + `git cat-file -p` 逐对象比对 | ✅ 24 项内核测试全绿 |
| index v2/v3 读写 | `core/git/GitIndex.kt` | `git ls-files --stage` 对齐；写回后 `git status` 干净 | ✅ |
| commit/tree 序列化 | `core/git/GitObject.kt`、`GitRepo.kt` | `git fsck --strict` 通过；`git log` / `git ls-tree` 一致 | ✅ |
| 工作区状态（含 racy-clean 处理） | `core/git/GitRepo.kt` | 与 `git status --porcelain` 一致 | ✅ |
| 行级 diff（Myers + 大文件降级） | `core/git/LineDiff.kt` | 8 项结构断言 | ✅ |
| 仓库浏览 / 文件树 / 编辑器 / 自动保存 | `feature/repo`、`feature/editor` | 编译 + 人工路径审查 | ⚠️ 未真机运行 |
| 暂存 / 提交 | `core/data/RepoRepository.kt` | 单测经真实 git 校验 | ✅ |
| 主题系统（6 套配色 + 拟态组件） | `core/designsystem` | 编译通过；无真机截图 | ⚠️ 未真机验证 |
| 设置注册表 / 关于页 | `core/datastore`、`feature/settings` | 编译通过 | ⚠️ 未真机运行 |
| Room v2 + 显式迁移 1→2 | `core/database` | schema 1.json / 2.json 已生成；迁移测试需模拟器 | ⚠️ 部分验证 |
| AndroidKeyStore AES-GCM token | `core/security` | 编译通过 | ⚠️ 未真机验证 |

### 14.2 构建与质量门禁

| 项目 | 命令 | 结果 |
|---|---|---|
| 零警告编译 | `./gradlew :app:assembleDebug -Pgitpocket.werror=true` | ✅ 通过（`allWarningsAsErrors=true`） |
| 单元测试 | `./gradlew test` | ✅ 27 项通过（core:git 24 + core:common 3），0 失败 0 跳过 |
| Lint | `./gradlew lintDebug` | ✅ 0 error 0 warning（已关闭版本升级类噪声与 adaptive-icon 目录误报） |
| Debug 包 | `./gradlew :app:assembleDebug` | ✅ 10 MB |
| Release 包（R8） | `./gradlew :app:assembleRelease` | ✅ 1.2 MB，已签名 |
| 签名校验 | `apksigner verify --print-certs` | ✅ `CN=GitPocket Test`，SHA-256 `8a9f59e0…` |

### 14.3 明确未实现（不要当作已完成）

- **网络传输**：clone / fetch / pull / push（smart HTTP v2）——下一阶段，`core/git` 尚无 transport 模块。
- **冲突处理 UI**：引擎侧 diff3 未实现，UI 也未接。
- **PR / CI / Issue**：未开始。
- **语法高亮 / 行内 diff / 部分克隆**：未开始。
- **仪器测试**：迁移测试已编写但未运行（无模拟器）；Compose UI 无测试。
- **真机验证**：已在 Android 14 (API 34) x86_64 模拟器上跑通主流程（见 14.5），
  但**未在真实手机上验证**（厂商 ROM、字体缩放、折叠屏均未覆盖）。

### 14.5 端到端验证（Android 14 模拟器，2026-10-20）

无头模拟器（API 34 / x86_64），安装 `GitPocket-debug.apk` 后实测：

| 步骤 | 结果 |
|---|---|
| 冷启动 | ✅ 无崩溃，首页显示示例仓库卡片（demo / main） |
| 仓库详情 | ✅ 分支显示 main；变更 Tab 空态正确；历史 Tab 显示 3 条提交（标题与短 oid 正确） |
| 文件树 | ✅ src 目录 + notes.md + README.md，大小正确 |
| 编辑器 | ✅ 行号、等宽字体、字符计数正确；点击可聚焦、IME 弹出、输入生效 |
| 自动保存 | ✅ 输入后 800ms 落盘（`run-as cat` 校验磁盘内容已更新） |
| 返回刷新 | ✅ 从编辑器返回后变更 Tab 显示 `M notes.md / 未暂存` |
| 提交 | ✅ 提交后回到详情页并显示"工作区干净" |
| 产物校验 | ✅ 导出仓库到桌面：`git log` 4 条、`git status` 干净、`git fsck --strict` 通过、`git show --stat` 显示 1 file changed |
| 主题切换 | ✅ 设置里切到深色后全局立即生效（首页/设置页均为深色，未重建页面） |

本轮修复的三个问题：
1. 首次启动崩溃 `SQLITE_CONSTRAINT_FOREIGNKEY (787)`：`repo.accountId` 指向的账号行不存在。
   现在先落本地账号再插仓库，并让种子逻辑幂等（重复启动不重复提交）。
2. 从编辑器返回详情页不刷新，一直显示旧状态。改为 `LifecycleResumeEffect` 静默刷新。
3. 提交页在作者未设置时直接拦截。仓库层本就有兜底身份，改为允许提交并提示可在设置里修改。

### 14.4 与设计稿的偏差（以实现为准）

| 设计稿 | 实际 | 原因 |
|---|---|---|
| build-logic 复合构建 | 根 `build.gradle.kts` 统一 compilerOptions | 减少构建复杂度，收益不明显 |
| 8 个 feature 模块 | 5 个（browse 并入 repo，diff 并入 editor） | 避免空壳模块与样板 |
| 类型安全路由（kotlinx.serialization） | 字符串路由 + `Uri.encode` | 规避"是否允许第三方库"的边界争议 |
| Paging 3 | 暂未引入 | 当前数据量小，先保持简单 |
| 动态取色 | 未实现 | 与多套配色冲突，设计上已标记待确认 |
| 冲突页 / 同步页 | 未实现 | 依赖网络层与 diff3 |
