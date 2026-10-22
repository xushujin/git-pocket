# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [Unreleased]

## [1.1.0] - 2026-10-22

### 新增

- **分支管理**：仓库详情页右上角「分支」可查看本地分支与远端跟踪分支，
  点击切换、输入名字新建；点远端分支会建同名本地分支并切过去。
  工作区有未提交改动时明确拒绝切换（不做静默 stash）。
- **三方合并与冲突解决**：拉取不再只做快进，分叉时自动三方合并并产生合并提交；
  冲突时写冲突标记 + index stage 1/2/3，变更列表里逐个处理
  （用我的 / 用对方的 / 手动改完标记已解决），顶部提示条可放弃合并。
  冲突未解决时禁止提交。
- **撤销上次提交**（历史页）：soft reset，提交从历史移除、改动回到暂存区。
- **编辑器重做**：回退之后可以重做，继续输入会丢弃重做分支。
- **大文件只读降级**：超过 512 KB 的文件只读预览（截断到 20 万字符），不再卡死输入框。
- **diff 折叠与跳转**：点 hunk 头折叠，工具条可上/下一处变更、一键展开。

### 变更

- `pull` 语义从「fetch + 快进」改为「fetch + 合并」，分叉不再提示"无法拉取"。
- 撤销栈改为快照式实现，支持重做；`maxSize` 语义为「可回退步数」。

### 说明

- 合并只做行级 diff3：不做 rename detection，二进制 / 符号链接 / 子模块冲突只能整份选一边。
- 新增 20 项 `MergeTest`（含真实 `git merge` 对拍：两个父提交、UU 状态、abort 后工作区一致）。

## [1.0.4] - 2026-10-22

### 新增

- **fetch / pull / push（HTTPS）**：仓库详情页「同步」→ 拉取 / 推送。
  - 拉取只做快进（fast-forward），分叉时明确提示，不静默合并；
  - 推送只推当前分支，非快进直接拒绝并提示"先拉取"；
  - 支持 PAT 认证，认证失败 / 被拒绝都有可读提示与进度。
- 打开仓库时按设置自动后台 fetch（失败不打扰，可在设置里关）。

### 说明

- 新增 `GitFetcher` / `GitPusher` / `PackWriter` / `ObjectWalk`。
  push 生成的 pack 不带 delta：体积略大，但省掉基对象管理，出错面小。
- 端到端测试用真实的 `git http-backend` 起本地 smart HTTP 服务，
  覆盖首次 fetch、增量 fetch、push、非快进拒绝四种场景。

## [1.0.3] - 2026-10-22

### 修复

- 设置页「生物识别解锁」仍显示"第二阶段"：该行文案是硬编码的，没走设置注册表，已改为实际行为描述。
- 关于页 / README / 设计文档与实现不一致：HTTPS 克隆早已支持却仍标"第二阶段"；
  删除并未申请的 `POST_NOTIFICATIONS` 说明；移除示例仓库相关描述。

### 变更

- 网络超时设置真正生效：克隆时按「连接 / 读取超时」传参。
  此前两个步进器点了没有任何反应，也不会落库。

## [1.0.2] - 2026-10-22

### 新增

- 文件列表改用自绘图标（文件夹 / 文件），跟随主题配色，不再用 emoji。
- 编辑器默认进入**预览**，点「编辑」才可修改；预览支持 Markdown 渲染与代码语法高亮。
- 编辑时顶栏新增「回退」：按输入段落回退，连续输入合并为一步，停顿 / 换行断开。
- 生物识别解锁：开启后冷启动需验证指纹 / 面容 / 锁屏密码；设备不支持时明确提示并可关闭。

### 修复

- 编辑页顶部返回按钮在有未保存修改时会直接离开，现在与系统返回一致，先弹确认。

## [1.0.1] - 2026-10-21

### 修复

- 设置页输入作者名 / 作者邮箱时，第二个字的光标会跳到开头。
  输入框改为本地缓冲（`TextFieldValue`），不再等 DataStore 异步回写；
  外部值只在未聚焦时同步，覆盖首次加载与外部修改。

## [1.0.0] - 2026-10-21

首个可验收版本：自研 Git 内核 + 完整本地工作流 + HTTPS 克隆。

### 新增

#### Git 内核（`core:git`，纯 Kotlin，未使用 JGit / libgit2）

- loose object 读写与 SHA-1 校验
- packfile v2 读取（idx v2、ofs-delta、ref-delta、尾部校验和）
- index v2/v3 读写、refs（loose + packed-refs + peeled）
- commit / tree / tag 编解码，工作区状态（含 racy-clean 处理）
- Myers 行级 diff，大文件降级为统计
- HTTPS 克隆：smart HTTP v0（pkt-line、side-band-64k、ofs-delta、Basic/PAT 认证）

#### 界面

- 拟态设计系统，6 套配色（Mist / Paper / Moss / Graphite / Midnight / Ocean），主题全局即时生效
- 仓库列表、仓库详情（变更 / 历史 / 文件）、文件浏览、编辑器、diff、提交、设置、关于
- 空列表直接引导克隆，**不再预置示例仓库**（旧版本留下的 `local-demo` 会在升级后自动清理）
- 语法高亮：Kotlin / Java / JS / TS / JSON / XML / Python / Shell / Markdown（自研词法器）
- Markdown 预览：标题、段落、列表、引用、分割线、表格、围栏代码块（带高亮）、行内样式
- 手动保存 + 离开确认 + 撤销未保存修改
- 变更列表按文件撤销（恢复到 HEAD，新增文件删除）

#### 数据与安全

- Room v2 + 显式迁移 1→2 + schema 导出，无 destructive fallback
- 设置注册表（DataStore），一个设置项只有一处实现
- AndroidKeyStore AES-256-GCM 加密存储 token

#### 工程

- PR：单元测试 + lint（不产出 APK）
- tag：签名构建 → 校验签名 → 自动创建 GitHub Release
- APK 命名 `GitPocket-<版本>.apk` / `GitPocket-debug.apk`
- Release 资产：APK + SHA-256 + `mapping.txt.gz`（不上传 AAB；mapping 压缩后约 2 MB）
- 版本号：tag 决定 versionName，versionCode 取构建时间戳（保证可覆盖安装）

### 修复

- 首次启动崩溃：`SQLITE_CONSTRAINT_FOREIGNKEY (787)`（repo 的外键账号行缺失）
- 从编辑器返回详情页不刷新；页签被重置到「变更」
- 撤销文件变更后编辑器仍显示旧内容（草稿未清理）
- 作者未设置时提交被直接拦截
- 克隆仓库中符号链接被误报为已修改

### 已知限制

- **未实现 fetch / pull / push**：只能克隆，改动无法推回远端
- 冲突处理 UI、PR / CI / Issue 集成未做
- 克隆后对象以 loose 存储（不写 pack + idx），大仓库占用偏大
- 编辑器行号栏在长行自动换行时会与文本错位
- Markdown 不支持嵌套列表、可点击链接、HTML 内嵌
