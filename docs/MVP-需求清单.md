# GitPocket — 可直接进入开发的简化需求清单（MVP / v0.1）

> 用法：这份清单是"能开工"的最小粒度。每条都有验收标准（AC），做完打勾。
> 不在本清单里的功能，MVP 阶段**不做**（见主文档第 2.4 节"明确后置"）。
> 标注：`[P0]` 阻塞发布 / `[P1]` 可在 MVP 内后置但不可砍。

---

## 0. 开工前（Week 0，1~2 天）

- [ ] 建仓 + 目录骨架（`app` / `core:*` / `feature:*` / `build-logic`）
- [ ] `gradle/libs.versions.toml` 收敛全部依赖版本；`minSdk 26 / compileSdk 36 / targetSdk 36`
- [ ] 打开 `allWarningsAsErrors`，跑通 `./gradlew assembleDebug` 零警告
- [ ] `keystore.properties.example` + `.gitignore`（含 `*.jks`、`keystore.properties`、`local.properties`）
- [ ] 生成测试 keystore，本地能出 signed release（否则后面发版会卡）
- [ ] `scripts/gen-fixtures.sh` 用真实 git 生成 3 个 fixture 仓库（基础/多分支/含合并）
- [ ] PR CI 跑通（test + lint + assemble）

**AC**：新人按 README 三步能在 10 分钟内跑起来 debug 包。

---

## 1. 基础框架

| # | 任务 | 优先级 | 验收标准（AC） |
|---|---|---|---|
| 1.1 | 单 Activity + `AppNavHost` + 类型安全路由 | P0 | 10 个目的地可导航，返回栈正确，进程重建后恢复 |
| 1.2 | `AppContainer` 手写 DI + `viewModelFactory` | P0 | 无任何 DI 框架依赖；ViewModel 构造注入可单测 |
| 1.3 | 错误模型 `GitException` + 文案映射 | P0 | 每种异常都有中文文案 + 一个可执行动作，无堆栈直出 |
| 1.4 | 统一 `Result`/加载态组件（Loading/Empty/Error） | P0 | 三态在所有列表页复用同一组件 |
| 1.5 | 日志与脱敏 | P0 | `Authorization`、token 永不进日志；release 关闭 verbose |

---

## 2. 主题与设计系统

| # | 任务 | 优先级 | 验收标准（AC） |
|---|---|---|---|
| 2.1 | `NeumorphicPalette` + 4~6 套配色（浅/深各一套变体） | P0 | 切换后全局立即生效，不重建 Activity，不丢页面状态 |
| 2.2 | `GitPocketTheme` 唯一入口 + `CompositionLocal` | P0 | 全工程 `GitPocketTheme` 仅出现 1 次（CI grep 校验） |
| 2.3 | 系统栏图标明暗跟随主题 | P0 | 浅色下状态栏图标为深色，深色下为浅色 |
| 2.4 | 拟态组件：`RaisedCard` / `PressedButton` / `InsetField` / `FloatingAction` | P0 | API 31+ 用 RenderEffect，26–30 用渐变降级；列表项不启用模糊 |
| 2.5 | 排版/间距/动效 token | P1 | 无硬编码 sp/dp 散落在 feature 层（抽查 20 处） |
| 2.6 | 拟态性能验证 | P0 | 列表滚动 Macrobenchmark ≥ 55fps【未验证，需实测】 |

---

## 3. Git 引擎（核心，占 M1 60% 风险）

### 3.1 对象与存储
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.1.1 | `ObjectId`（value class）+ SHA-1 计算 | P0 | 与 `git hash-object` 输出一致 |
| 3.1.2 | loose object 读/写（zlib + 原子写） | P0 | 写出的对象 `git cat-file -p` 可读；`git fsck` 无错 |
| 3.1.3 | pack `.idx` v2 解析（含 large offset） | P0 | 与 `git verify-pack -v` 的 oid/offset 全量一致 |
| 3.1.4 | pack 对象读取（含 ofs-delta / ref-delta + delta 应用） | P0 | 3 个真实 pack 全对象解压成功，内容 SHA-1 校验通过 |
| 3.1.5 | thin pack 补薄 | P0 | 真实 fetch 响应可正确落库 |
| 3.1.6 | 对象缓存 LRU + 64KB 预读 | P1 | 10 万对象仓库随机读 P95 < 20ms【估算目标】 |

### 3.2 引用与索引
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.2.1 | refs 读（loose + packed-refs + peeled） | P0 | 与 `git show-ref` 输出一致 |
| 3.2.2 | ref 写（`.lock` + rename 原子） | P0 | 并发写测试无损坏 |
| 3.2.3 | HEAD symref 读写 | P0 | 切换分支后 `git status` 显示正确分支 |
| 3.2.4 | 分支名大小写冲突检测 | P0 | 在不敏感文件系统上拒绝 `Feature`/`feature` 并存并给出明确错误 |
| 3.2.5 | index v2 读 | P0 | 与 `git ls-files --stage` 逐项一致（含 mode/oid/stage） |
| 3.2.6 | index v2 写 | P0 | 写回后真实 `git status --porcelain` 输出与应用内状态一致 |
| 3.2.7 | 扩展区处理（TREE 保留、未知可选丢弃、未知必需拒绝写） | P0 | 构造含未知扩展的 index 验证行为 |

### 3.3 模型与图
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.3.1 | commit/tree/tag 解析（含 gpgsig 多行、encoding） | P0 | 与 `git cat-file -p` 字段一致；签名提交重写后 gpgsig 不被破坏 |
| 3.3.2 | commit/tree 序列化 | P0 | 序列化→解析往返一致；`git fsck --strict` 通过 |
| 3.3.3 | 非 UTF-8 文件名保留（ByteArray） | P1 | 中文/emoji/GBK 文件名不丢字节 |
| 3.3.4 | merge-base（双向 BFS） | P0 | 与 `git merge-base` 在 20 组用例上一致（允许非最小解，需记录） |
| 3.3.5 | ahead/behind 计算 | P0 | 与 `git rev-list --count --left-right` 一致 |

### 3.4 工作区与状态
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.4.1 | 工作区扫描（stat 快路径 + 哈希兜底） | P0 | 与 `git status` 一致；1 万文件仓库扫描 < 1.5s【估算目标】 |
| 3.4.2 | status 计算（untracked/modified/deleted/staged/conflict） | P0 | 与 `git status --porcelain=v1` 逐行一致 |
| 3.4.3 | checkout（写文件 + 更新 index） | P0 | 切换分支后工作区与 index 一致；不覆盖未提交修改（先拦截） |
| 3.4.4 | 忽略规则（`.gitignore` 基础语法） | P1 | 常见模式（`*.log`、`dir/`、`!keep`）匹配正确 |

### 3.5 diff 与合并
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.5.1 | 行级 Myers diff + 前后缀裁剪 | P0 | 与 `git diff` 的 hunk 边界一致（允许等价切分） |
| 3.5.2 | 大文件阈值降级 | P0 | >5000 行或 >1MB 时返回统计 + 前 500 行，UI 显示降级提示 |
| 3.5.3 | diff3 三方合并 + 冲突标记 | P0 | 与 `git merge` 产生的冲突标记逐行对照一致 |
| 3.5.4 | 冲突写入 index stage 1/2/3 | P0 | `git ls-files -u` 显示三段 |
| 3.5.5 | 冲突解决（ours/theirs/manual）+ 清 stage | P0 | 解决后 `git status` 显示 resolved |

### 3.6 传输
| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 3.6.1 | pkt-line 编解码 + sideband 分流 | P0 | 单测覆盖 `0000`/`0001`/长度包含自身 |
| 3.6.2 | `GET /info/refs` + protocol v2（回落 v0） | P0 | 三家服务器均能列 refs |
| 3.6.3 | clone（fetch pack → 落库 → checkout） | P0 | 克隆 GitHub/GitLab/Gitea 私有仓库成功，`git fsck` 无错 |
| 3.6.4 | fetch（增量、have/want 协商） | P0 | 二次 fetch 只下载新对象（对比流量/对象数） |
| 3.6.5 | pull（ff + 简单 merge） | P0 | ff 与非 ff 两条路径都验证 |
| 3.6.6 | push（零 delta pack + report-status-v2） | P0 | 推送到三家服务器成功，`git clone` 下来结构正确 |
| 3.6.7 | 非快进预检 | P0 | 远端有新提交时本地直接拒绝并提示，不等服务器 `ng` |
| 3.6.8 | 超时/取消/退避重试 | P0 | 断网 5 秒内给出可读错误；取消后无残留临时文件 |

---

## 4. 数据层

| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 4.1 | Room v1（account/repo/ref_snapshot/commit_cache/draft/op_log） | P0 | `exportSchema=true`，schema JSON 入库 |
| 4.2 | 显式迁移骨架 + 迁移测试 | P0 | 至少一个 1→2 迁移 + `MigrationTestHelper` 用例通过 |
| 4.3 | 禁止 destructive migration 的守卫 | P0 | 代码中不存在 `fallbackToDestructiveMigration*`；缺失迁移时进安全模式（只读）而非删库 |
| 4.4 | DataStore 设置 + 声明式设置注册表 | P0 | 每个设置项只有一个 key、一个默认值、一处 UI |
| 4.5 | Token 加密（AndroidKeyStore AES-256-GCM） | P0 | 明文不落盘；删除应用后密文不可解 |
| 4.6 | 仓库目录管理 + 配额 | P1 | 超限提示，不静默删除 |
| 4.7 | 草稿自动保存（800ms debounce + ON_STOP） | P0 | 强杀进程后重进可恢复，丢失 ≤ 1 次输入停顿 |

---

## 5. 页面（按此顺序开发）

| # | 页面 | 优先级 | AC |
|---|---|---|---|
| 5.1 | 首页仓库列表（空态/加载/错误/下拉刷新） | P0 | 20 个仓库滚动 60fps；空态有克隆引导 |
| 5.2 | 克隆流程（URL 解析 → 认证 → 进度 → 可取消） | P0 | 从剪贴板识别 URL；取消后无残留目录 |
| 5.3 | 仓库详情（Header + 变更/历史/文件 Tab） | P0 | ahead/behind 与命令行一致；Tab 切换保留滚动位置 |
| 5.4 | 文件树（面包屑 + 分层加载 + 状态点） | P0 | 1 万文件目录不卡；返回保留滚动位置 |
| 5.5 | 只读查看器（大文件/二进制/图片元信息） | P0 | >512KB 不进入编辑器，明确提示 |
| 5.6 | 编辑器（行号 + 自动保存 + 撤销/重做 + 查找） | P0 | 200KB 文件输入无卡顿【未验证】；退出拦截生效 |
| 5.7 | diff 页（统一视图 + 折叠 + 上下变更跳转） | P0 | 大 diff 走降级；色弱可辨（符号 + 色条） |
| 5.8 | 提交页（勾选 + 信息 + 提交并推送） | P0 | 空信息禁用按钮；成功后可 Undo（soft reset） |
| 5.9 | 同步进度（pull/push，可取消） | P0 | 进度分阶段；失败给具体原因 + 主行动按钮 |
| 5.10 | 冲突处理（选 ours/theirs/手动） | P0 | 三个动作都验证；解决后状态刷新 |
| 5.11 | 设置（外观/编辑器/Git/网络/存储/安全） | P0 | 主题项只有一处入口；改动即时生效 |
| 5.12 | 关于（名称/版本/技术栈/支持范围/权限） | P0 | 五项齐全；权限逐条说明"为什么" |

---

## 6. 工程与发布

| # | 任务 | 优先级 | AC |
|---|---|---|---|
| 6.1 | `SigningConventionPlugin`（两条通道一套实现） | P0 | 缺签名时 release 构建**失败**，不回落到 debug |
| 6.2 | tag 驱动版本号 | P0 | `v1.2.3` → versionName 1.2.3 / versionCode 10203；本地为 `0.0.0-dev` |
| 6.3 | `release.yml`（tag → 签名 → apksigner verify → gh release） | P0 | 一次真实 tag 发版跑通，资产含 APK + mapping + sha256 |
| 6.4 | `pr.yml`（test + lint + assemble + 真实文件验证） | P0 | PR 红灯能拦住问题 |
| 6.5 | README（构建/签名/发版/本地开发/CI） | P0 | 新人按文档可独立发一次版 |
| 6.6 | CHANGELOG + 发版 checklist | P1 | 发版时有据可依 |

---

## 7. 测试与验证（不可省）

- [ ] `core:git` 单测：对象/索引/refs/diff/merge/pkt-line，覆盖率 ≥ 80%
- [ ] 真实 fixture 交叉验证：`git verify-pack -v` / `git ls-files --stage` / `git status --porcelain` / `git fsck --strict`
- [ ] 我方写出的 index 由真实 `git status` 校验（CI 内自动跑）
- [ ] 迁移测试：n-1→n、n-2→n、大数据量
- [ ] 三家服务器集成测试（GitHub / GitLab / Gitea 私有仓库）
- [ ] 真机矩阵：Android 8 / 12 / 14 / 16，各一台；低端机（4GB RAM）一台
- [ ] 弱网测试：限速 100KB/s + 30% 丢包，clone 可完成或给出可读错误

---

## 8. 发布前 checklist

- [ ] `./gradlew clean test lintRelease assembleRelease` 全绿且零警告
- [ ] `apksigner verify --print-certs` 证书指纹与预期一致
- [ ] 关于页版本号 = tag 版本
- [ ] 三个平台真实仓库跑通「克隆 → 改文件 → 提交 → 推送」
- [ ] `git fsck` 对应用产生的仓库无报错
- [ ] 升级安装（v0.1 → v0.1.1）数据不丢
- [ ] 隐私政策链接可访问（上架必需）
- [ ] Release 资产齐全（APK / AAB / mapping.txt / SHA256）

---

## 9. 明确不做（MVP 阶段写进"支持范围"）

SSH、Git LFS、submodule、SHA-256 仓库、force push、rebase、cherry-pick、hooks、rename detection、PR/CI/Issue、语法高亮、行内 diff、多账号（可后置到 MVP 末）、平板双栏、后台自动同步。
