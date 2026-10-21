# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [Unreleased]

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
