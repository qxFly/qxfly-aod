# qxfly-aod

息屏歌词 —— 适配 `com.miui.aod`（息屏与锁屏编辑）的 LSPosed 模块 + 配套 App。

- **歌词来源**：SuperLyric（Binder，`super_lyric.v4`）与旧版广播，App 进程合并后经 ContentProvider 提供给 Hook
- **刷新方式**：播放期间把 `DOZE_SUSPEND` 改写为 `DOZE`，让合成器持续送帧（见 `ThemeProbeV2`）
- **框架**：LSPosed libxposed API **101 兼容**（用 102 编译，`targetApiVersion=101`）
- **App 界面**：Kotlin + Jetpack Compose + MiuiX（不使用 Gradle 的自建工具链）
- **歌词渲染**：自绘 `LyricTextView`，宽度可占满整屏，支持圆角背景、描边、自定义字体

## 构建

完整依赖准备与构建步骤见 **[BUILDING.md](BUILDING.md)**。

```powershell
# 1) 准备 .deps（Kotlin 2.4.21 + Compose/Miuix 依赖）
powershell -ExecutionPolicy Bypass -File .\tools\setup_deps.ps1

# 2) 构建（产物在 dist\）
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 构建 + 安装（-Reboot 会在安装后重启设备）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install -Reboot
```

需要自备 JDK 21 与 Android SDK（build-tools 37.0.0 / platform android-36），
可用 `QXFLY_JDK`、`QXFLY_BUILD_TOOLS`、`QXFLY_ANDROID_JAR` 指向本地安装。

## 版本号

`versionName` 固定为 `<主版本>.<次版本>.<YYMMDD>.<NNN>`，例如 `2.4.261123.001`：
主版本 2、次版本 4、2026-11-23 构建、该月第 1 次构建。`versionCode` 为 `YYMMDDNNN`。
唯一来源：`module/app/src/main/AndroidManifest.xml`。

## 目录

```
build.ps1        唯一构建入口（aapt2 / javac / kotlinc / d8 / zipalign / apksigner）
BUILDING.md      工具链准备与构建说明
module/          模块与 App 源码、打包脚本、签名密钥、JVM 单测
tools/           构建依赖脚本与小型预编译 API jar
dist/            构建产物（已忽略）
.deps/           本地工具链（已忽略，setup_deps.ps1 生成）
```

## 签名

仓库包含 `module/debug.keystore`，用于产出可覆盖安装的同一签名 APK。
如需自行发布，请替换为自己的 keystore 并修改 `build.ps1` 的签名参数。

## 运行要求

- 已安装 LSPosed（libxposed API 101+），模块作用域包含 `com.android.systemui`
- 歌词来源：安装并启用 SuperLyric（或其兼容播放器）
- 仅适配 MIUI / HyperOS 的息屏显示（`com.miui.aod`）
