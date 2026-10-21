# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [Unreleased]

### Added

- 自研 Git 内核（纯 Kotlin）：loose object 读写、packfile v2 读取（含 ofs/ref delta）、
  index v2/v3 读写、refs（loose + packed-refs）、commit/tree 序列化、工作区状态、Myers 行级 diff。
- 拟态设计系统：6 套配色（Mist / Paper / Moss / Graphite / Midnight / Ocean），主题全局即时生效。
- 页面：仓库列表、仓库详情（变更/历史/文件）、文件浏览、编辑器（自动保存 + 草稿）、
  diff、提交、设置、关于。
- Room 数据库（v2）+ 显式迁移 1→2 + schema 导出。
- AndroidKeyStore AES-256-GCM token 存储。
- CI：PR 校验、tag 触发签名发布。
- **HTTPS 克隆**：smart HTTP v0（pkt-line / side-band-64k / ofs-delta），
  公开仓库与 PAT 私有仓库均可；收到 pack 后拆为 loose object 并检出工作区。

### Fixed

- 首次启动崩溃：`repo` 表插入时 `accountId="local"` 没有对应账号行，触发
  `SQLITE_CONSTRAINT_FOREIGNKEY (787)`。现在先落本地账号行再插仓库，并让种子逻辑幂等
  （重复启动不会重复提交）。
- APK 产物名与应用名对齐：`GitPocket.apk` / `GitPocket-debug.apk`。
- 克隆入口不显眼（右上角小图标）：改为首页右下角「+ 克隆仓库」扩展悬浮按钮。
- 从编辑器返回仓库详情后不刷新，仍显示"工作区干净"（改为页面 resume 时静默刷新）。
- 作者未设置时提交被直接拦截（仓库层已有兜底身份，改为允许提交并提示可修改）。

### Known issues

- fetch / pull / push 尚未实现（克隆已完成，增量同步为下一阶段）。
- 克隆后对象以 loose 形式存储（不做 pack + idx 落盘），大仓库占用偏大。
- 冲突处理 UI 未接入。
- 编辑器行号栏在软换行下会错位（当前禁用软换行规避）。
