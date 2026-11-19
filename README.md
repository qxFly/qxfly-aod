# qxfly-aod

息屏歌词 —— 适配 `com.miui.aod`（息屏与锁屏编辑）的 LSPosed 模块 + 配套 App。

- **歌词来源**：SuperLyric（Binder，`super_lyric.v4`）
- **框架**：LSPosed libxposed API **101 兼容**（用 102 编译，`targetApiVersion=101`）
- **App 界面**：Kotlin + Jetpack Compose + MiuiX（不使用 Gradle 的自建工具链）

## 构建

```powershell
# 只构建，产物在 dist\
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 构建 + 安装到设备
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install

# 构建 + 安装 + 重启（改了 Hook 代码时需要）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install -Reboot

# 丢弃中间产物，全量重建
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

- 版本号唯一来源：`module\app\src\main\AndroidManifest.xml`
- 产物：`dist\qxfly-aod-<versionName>.apk`（已 zipalign、已签名）
- 首次构建前需要准备 `.deps` 工具链，见 [docs/COMPOSE_SETUP.md](docs/COMPOSE_SETUP.md)

## 文档

| 文档 | 内容 |
|---|---|
| [docs/README.md](docs/README.md) | 主文档：原理、Hook 链路、版本历史、真机验证记录 |
| [docs/BISECT.md](docs/BISECT.md) | 历史故障排查与复盘 |
| [docs/COMPOSE_SETUP.md](docs/COMPOSE_SETUP.md) | 无 Gradle 的 Kotlin + Compose 工具链 |
| [docs/LSPosed-API102适配指南.md](docs/LSPosed-API102适配指南.md) | libxposed API 102 适配说明 |
| [docs/项目分析报告.md](docs/项目分析报告.md) | 项目结构分析 |
| [docs/assets/](docs/assets/) | 界面参考图 |

## 目录

```
build.ps1        唯一构建入口
dist/            构建产物（已忽略）
docs/            文档与参考图
module/          模块与 App 源码、打包脚本、签名密钥
work/            工具链 / 打包辅助脚本
.deps/           本地工具链（已忽略）
```
