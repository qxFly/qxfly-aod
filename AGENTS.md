# 协作规则（AGENTS.md）

本文件是本仓库对 AI 助手的硬性约束，优先级高于临时对话里的默认习惯。

## Git

- **可以**在本地 `git add` / `git commit`。
- **禁止主动 `git push`**（包括 `git push -u origin main`、`--force`、推送 tag 等一切远端写操作）。
- 只有当用户明确说出「推送 GitHub / push / 上传」这类指令时才允许推送；否则提交后停在本地，并在回复里说明「已本地提交，未推送」。
- 不要擅自改 `origin`、不要创建/删除远端分支或 Release。

## 构建

- 唯一构建入口是仓库根目录的 `build.ps1`：
  - 只构建：`powershell -ExecutionPolicy Bypass -File .\build.ps1`
  - 构建 + 安装：`-Install`
  - 改了 Hook（`module/app/src/main/java/**`）后需要 `-Install -Reboot`
- `build.ps1` 必须保存为 UTF-8 with BOM。

## 环境备注

- adb：`D:\GitSoftware\scrcpy-win64-v3.2\adb.exe`
- 截图必须 `adb shell screencap -p /sdcard/x.png` + `adb pull`（PowerShell 直接用 `exec-out >` 会破坏二进制）。
- 当前测试机：Xiaomi 14（`23127PN0CC`，Android 16 / HyperOS 3）；HyperOS 3 起息屏显示在 `com.android.systemui` 进程里加载 `MIUIAod.apk` 插件。
