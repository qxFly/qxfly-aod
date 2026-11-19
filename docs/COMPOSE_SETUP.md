# Kotlin + Compose 工具链（可随时使用）

> **状态（2026-11-19 目录整理）**：本文记录的是 **Miuix 0.8.8 / Kotlin 2.3** 旧链路的
> 搭建过程，现仅作参考资料保留。当前构建统一走根目录 `build.ps1`
> （Kotlin 2.4.21 + Miuix 0.9.4，依赖 `.deps/kotlin24`、`.deps/libs`、
> `.deps/m094-clean`、`.deps/compose-classes-m094.jar`）。
> 文中提到的 `module\build_compose.ps1`、`module\build.ps1` 已删除。

无 Gradle 的 Kotlin + Compose(MiuiX) 编译链，装在 `.deps/` 内，不影响系统。

## 一键安装 / 验证 / 构建

```powershell
powershell -ExecutionPolicy Bypass -File work\setup_compose.ps1   # 首次装依赖
powershell -ExecutionPolicy Bypass -File work\verify_compose.ps1  # 验证能否编译
powershell -ExecutionPolicy Bypass -File module\build_compose.ps1 # 出 APK
```

产物：`AodLyric-compose.apk`（实测 19.8 MB，6 个 dex）。
Java-only 构建仍走 `module\build.ps1`，两条链互不影响。

## 组件与位置

| 组件 | 路径 | 版本 |
|---|---|---|
| Kotlin 编译器 | `.deps/kotlin/kotlinc/lib/kotlin-compiler.jar` | 2.3.20 |
| Compose 编译器插件 | `.deps/compose-compiler/kotlin-compose-compiler-plugin-2.3.20.jar` | 2.3.20 |
| Kotlin stdlib | `.deps/libs/kotlin-stdlib-2.3.20.jar` | 2.3.20 |
| Compose + MiuiX 类 | `.deps/compose-classes.jar` | Compose 1.10.5 / MiuiX 0.8.8 |
| 原始构件 | `.deps/compose-libs-clean/`（329 个） | — |
| 去重后的 jar | `.deps/compose-libs-dedup/`（230 个） | — |

## 六个必须知道的坑（都已解决，别踩回去）

### 1. `kotlinc.bat` 会毁掉 Windows classpath

`kotlinc.bat` 对 `-classpath` 里的分号重新分词，条目被静默丢弃。必须直接调编译器：

```powershell
java -cp kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ...
```

### 2. `@argfile` 不被展开

`kotlinc.bat` 不认 `@file`（报 `argfile not found`），K2JVMCompiler 直接调也不展开。
所以参数必须内联，classpath 只能短——这就是要合并 fat jar 的原因。

### 3. 必须保留 `META-INF/*.kotlin_module` ★ 最隐蔽

Kotlin 靠 `.kotlin_module` 文件把**包映射到顶层函数所在的类**。合并 jar 时只复制
`.class` 会得到这样的结果：

- `androidx.compose.runtime.Composer`（**类**）→ 能解析 ✅
- `Text` / `dp` / `padding`（**顶层函数**）→ 全部 `unresolved reference` ❌

类能解析而函数不能，就是缺 `.kotlin_module`。当前 `compose-classes.jar` 含 98 个
`.kotlin_module`（用 `work/fatjar3.py` 从原始 AAR 里抽回）。

### 4. KMP 库的真实类在 `-android` 变体里

`androidx.compose.ui:ui` 的 AAR **没有 classes.jar**（只有清单+元数据）。真正的类在
`ui-android`。解析器必须为每个构件补取 `-android` 变体（`work/fix_variants.py`）。

### 5. 必须做版本冲突消解 + 类去重

Compose 的依赖图会带来同一构件的多个版本（`ui-android` 出现 1.8.2/1.9.x/1.10.x）。
不做冲突消解会下载六份；不按类去重，d8 会报
`Type X is defined multiple times`。

- `work/resolve_compose4.py` — BFS 遍历 + 取最高版本
- `work/dedup_libs2.py` — 按模块优先级决定每个类归谁

### 6. Compose 必须进 dex，且是多 dex

Compose 运行时在**运行时**加载，不能只放编译期 classpath。加进 d8 后方法数超 64K，
d8 产出 `classes.dex`…`classes6.dex`，`stage_apk.py` 已支持把整个输出目录打包
（传目录而非单个 dex 路径）。

## Compose 插件是否真的生效

看方法签名。未加插件时是：

```
public static final void ComposeSmokeTest(java.lang.String)
```

加了插件后变成：

```
public static final void ComposeSmokeTest(java.lang.String,
        androidx.compose.runtime.Composer, int)
```

`Composer` 参数和 `$lambda` 重组函数就是插件注入的产物。`work/verify_compose.ps1`
会自动做这个检查。

## 写 Compose 代码的位置

```
module/app/src/main/kotlin/    ← Kotlin/Compose 源码（当前只有冒烟测试）
module/app/src/main/java/      ← 现有 Java UI，保持不动
```

两条构建链会一起编译这两处，所以可以渐进迁移：先把新页面写成 Compose，等稳定后再
替换旧界面。

## 体积代价（需知）

| 项目 | 数值 |
|---|---|
| compose-classes.jar | 64.3 MB |
| 生成的 APK | 19.8 MB（原来 0.74 MB） |
| dex 数量 | 6 |
| 安装后 dex 展开 | ≈51 MB |

模块 APK 从 0.74 MB 涨到 19.8 MB，主要是 Compose 运行时 + MiuiX + 全部传递依赖。
当前的 Java UI 不需要这些，所以 **`build.ps1` 仍然是不含 Compose 的 0.74 MB 版本**，
只有主动用 `build_compose.ps1` 才会把 Compose 打进去。


## m094 依赖管线坑（upgrade_m094.py，2026-11-14 踩坑实录）

1. **lifecycle-common-java8 会毒化 2.11.0**：旧构件 `lifecycle-common-java8:2.3.0`
   里的 `DefaultLifecycleObserver` 继承 `FullLifecycleObserver`，而 lifecycle 2.11.0
   已移除该类（jvm 变体叫 lifecycle-common-jvm，java8 坐标只剩空壳转发）。
   dedup 按类名先到先得，`java8` 字母序排在 `jvm` 前面就把 2.3.0 的类塞进了 fat jar
   —— 运行时 `NoClassDefFoundError: FullLifecycleObserver`，启动即崩。
   修复：dedup EXCLUDE 里排除 `lifecycle-common-java8`。

2. **包归属重分配会静默丢类**：dedup 的 pkg_index 启发式把
   `androidx/compose/ui/graphics` 包划给 `ui-graphics-android`，但
   `ReusableGraphicsLayerScope` 等约 71 个类其实定义在 `ui-android` 里，
   所有权被转走后没人输出 -> dex 缺类，Compose 首帧渲染时崩。
   修复：只有当接管者（包归属构件）真的定义了该类时才转移所有权。

3. **裸 KMP 构件混入图**：resolve 时会抓到 `androidx.compose.ui:ui:1.2.1` 这类
   远古坐标（被某处 pin 拖进来），它的 res 与 `ui-android 1.12.0` 在 aapt2 link
   冲突。collect_aar_res2.py 与 dedup 都按「同一 (group, base) 只留最高版本，
   -android 优先」过滤。

4. **PowerShell foreach 驱动 aapt2 会杀 shell 守护进程**：35 个 AAR res 的编译
   循环中途守护进程死亡（无报错）。必须用 `work/aarzip_compile.py`
   （Python subprocess 驱动）。

5. **essentials 检查名单的 MISS 可能是误报**：扩展函数的宿主类名不含函数名
   （`setContent` 在 `ComponentActivityKt`、`textureBlur` 在 `TextureEffectKt`、
   `collectAsStateWithLifecycle` 在 `FlowExtKt`）。确认要在 class 文件
   常量池里搜字节串，别按文件名猜。
