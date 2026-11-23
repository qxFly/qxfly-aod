# AOD Lyric（息屏歌词）— 构建说明

本仓库只包含 App 源码与构建脚本。按下面的步骤准备工具链，即可构建出与 Release 一致的 APK。

## 1. 环境要求

| 依赖 | 版本 / 路径 | 说明 |
|---|---|---|
| Windows PowerShell | 5.1+ | 构建脚本为 `.ps1` |
| Python | 3.x（PATH 中可用） | 资源收集 / 依赖解析脚本 |
| JDK | **21** | 需含 `bin\javac.exe` |
| Android SDK Build-Tools | **37.0.0** | 需含 `aapt2.exe` / `zipalign.exe` / `lib\d8.jar` / `lib\apksigner.jar` |
| Android Platform | **android-36** | 需含 `android.jar` |

JDK 与 Android SDK 体积大且有许可限制，不随仓库分发。可用以下环境变量指向你自己的安装：

```powershell
$env:QXFLY_JDK         = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13+9"
$env:QXFLY_BUILD_TOOLS = "C:\Android\Sdk\build-tools\37.0.0"
$env:QXFLY_ANDROID_JAR = "C:\Android\Sdk\platforms\android-36\android.jar"
```

不设置时，构建脚本会使用 `.deps` 下的默认布局（见第 3 节）。

## 2. 一键准备构建依赖

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup_deps.ps1
```

脚本会把以下内容装进仓库内的 `.deps/`（已被 `.gitignore` 忽略）：

- Kotlin **2.4.21** 编译器、Compose / Serialization 编译器插件、stdlib
- Compose **1.12.0** + Miuix **0.9.4** 依赖图（AAR → `m094-clean`、去重 → `m094-dedup`、合并 → `compose-classes-m094.jar`）
- 三个小型预编译 API jar（libxposed 102、SuperLyric API 4.0、annotation）

首次运行需要联网，Compose 依赖解析可能需要几分钟。

## 3. `.deps` 目录布局（默认）

```
.deps/
├─ jdk21/jdk-21.0.13+9/              # 可用 QXFLY_JDK 覆盖
├─ android-sdk/
│  ├─ bt/android-37.0/               # 可用 QXFLY_BUILD_TOOLS 覆盖
│  └─ p36/android-36/android.jar     # 可用 QXFLY_ANDROID_JAR 覆盖
├─ kotlin24/kotlinc/                 # setup_deps.ps1 生成
├─ libs/                             # Kotlin 插件 + stdlib + annotation
├─ libxposed/102/classes.jar         # prebuilt
├─ superlyricapi-4.0/classes.jar     # prebuilt
├─ m094-clean/                       # Compose/Miuix AAR 集合
├─ m094-dedup/
└─ compose-classes-m094.jar          # 合并后的编译期 classpath
```

## 4. 构建

```powershell
# 只构建
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 构建 + 安装到已连接设备
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install

# 构建 + 安装 + 重启（改了 Hook 代码后需要）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install -Reboot

# 丢弃中间产物后全量构建
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

产物：`dist\qxfly-aod-<versionName>.apk`（已 zipalign、已签名）。
中间产物在 `module\build\`，可随时删除。

## 5. 版本号规则（硬性）

`versionName` 固定格式：`<主版本>.<次版本>.<YYMMDD>.<NNN>`

- 主版本 `2`、次版本 `4`
- `YYMMDD` = 构建当天日期（2026-11-23 → `261123`）
- `NNN` = 该自然月内的第几次构建，三位补零，跨月重置为 `001`
- `versionCode` = `YYMMDDNNN`（如 `261123001`）

示例：`2.4.260315.007` = 主版本 2、次版本 4、2026-03-15 构建、该月第 7 次构建。

唯一来源是 `module/app/src/main/AndroidManifest.xml`；`module.prop` 在打包时自动同步。

## 6. 签名

仓库包含 `module/debug.keystore`（debug 签名）。**不要替换**，否则无法覆盖安装已发布的版本。
如需发布自己的构建，请自行生成 keystore 并修改 `build.ps1` 中的签名参数。

## 7. 单元测试

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\run-tests.ps1
```

纯 JVM 断言测试（无 JUnit），覆盖纯逻辑类（锚点、LRC、样式、歌名校验、进度外推）。
