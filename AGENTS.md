# 协作规则（AGENTS.md）

本文件是本仓库对 AI 助手的硬性约束，优先级高于临时对话里的默认习惯。

## Git

- **可以**在本地 `git add` / `git commit`。
- **禁止主动 `git push`**（包括 `git push -u origin main`、`--force`、推送 tag 等一切远端写操作）。
- 只有当用户明确说出「推送 GitHub / push / 上传」这类指令时才允许推送；否则提交后停在本地，并在回复里说明「已本地提交，未推送」。
- **本地 `main` = 完整开发历史**（含 `docs/`、`work/`、`AGENTS.md`）；**远端 `main` = 仅源码发布树**
  （`module/`、`tools/`、`build.ps1`、`BUILDING.md`、`README.md`、`.gitignore`）。
  - **禁止把本地 `main` 直接 push 到远端**（会把 docs/work 带回去）。
  - 更新发布源码：用独立干净源码树提交后推送，或显式指定干净提交强推。
  - 强推前的旧远端 main 已备份为本地标签 `backup-main-0b4654c`。
- 不要擅自改 `origin`、不要创建/删除远端分支或 Release。

## 执行方式

- **预估耗时超过 2 分钟的任务，一律交给子代理（subagent）执行**，不要在主会话里同步等待：
  构建 + 装机 + 重启、长时间真机采样、大文件反编译/全量搜索等。
- 主会话只做秒级操作（读代码、改一行、快查日志）和最后的结论汇总。

## 文档

- **`docs/IMPLEMENTATION.md` 是功能实现索引，采用更新制**：
  - **优先阅读**：需要了解任何功能的实现方式、代码位置、解包结论时，**先读该文件**，不要默认去翻源码或重新解包。
  - 只有该文件**没有记录、或记录不清楚**时，才读源码 / 解包 / 反编译；拿到结论后**立即同步回该文件**
    （关键源码片段进对应功能节的「关键源码」，解包结论进「附录 B：解包取证档案」）。
  - 每次 App 功能有实现、更新、变更，**必须同步更新该文件**：
    在该功能的「演进记录」里**追加**一条（日期/版本 → 做法 → 结果或废弃原因），
    **旧条目一律保留不删**；同时更新「当前实现」小节与「功能索引」表。
  - 新增功能：先在「功能索引」加一行，再补一节（当前实现 + 关键源码 + 实现路径 + 演进记录）。
  - 定位代码用 `文件路径` + `类#方法`，**不要写行号**（会漂移）。
  - 关键源码片段必须标明所属 `类#方法`，改动实现时同步更新片段。
- `docs/README.md` 面向使用者（现象/取证/结论 + 按轮次更新日志）；`docs/BISECT.md` 是历史排查复盘。

## 版本号（硬性规则）

- `versionName` 格式固定为 **`<主版本>.<次版本>.<YYMMDD>.<NNN>`**，示例：`2.4.260315.007`
  - 主版本 = `2`，次版本 = `4`（当前产品线；变更需用户明确同意）
  - `YYMMDD` = **构建当天日期**（2026-11-23 → `261123`）
  - `NNN` = **该自然月内的第几次构建**，三位补零，跨月重置为 `001`
- `versionCode` 与 `versionName` 对应：`YYMMDDNNN`（如 `261123001`）
- 唯一来源：`module/app/src/main/AndroidManifest.xml`；`module.prop` 打包时自动同步
- **每次构建前必须按规则更新版本号**；同一自然月内再次构建，`NNN` 必须递增
- 示例解析：`2.4.260315.007` = 主版本 2、次版本 4、2026 年 3 月 15 日构建、该月第 7 次构建

## 构建

- 唯一构建入口是仓库根目录的 `build.ps1`：
  - 只构建：`powershell -ExecutionPolicy Bypass -File .\build.ps1`
  - 构建 + 安装：`-Install`
  - 改了 Hook（`module/app/src/main/java/**`）后需要 `-Install -Reboot`
- `build.ps1` 必须保存为 UTF-8 with BOM。

## 环境备注

- adb：`D:\GitSoftware\scrcpy-win64-v3.2\adb.exe`
- 截图必须 `adb shell screencap -p /sdcard/x.png` + `adb pull`（PowerShell 直接用 `exec-out >` 会破坏二进制）。
- 当前测试机：Xiaomi `2509FPN0BC`（Android 17 / SDK 37 / HyperOS 4.0，主屏 **1200x2608**，另有副屏）；旧机 Xiaomi 14（`23127PN0CC`，Android 16 / HyperOS 3）。
- HyperOS 3 起息屏显示在 `com.android.systemui` 进程里加载 `MIUIAod.apk` 插件。
