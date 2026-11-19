# 排障二分协议(✅ 已解决:1.6.0 在三个进程全部加载成功)

## 1.9.1 闪退 postmortem(2.0.0 修复)——本项目最隐蔽的 bug

```
FATAL EXCEPTION: aodlyric-test-inject
java.lang.NoClassDefFoundError: Lio/github/libxposed/api/XposedModule;
  at MainActivity.lambda$injectTestLyric$3
```

链条:1.6.0 把 libxposed API 从 dex 移出(修复 Hook 进程加载)→
**App 进程里这些类也不存在了** → 任何在 App 进程触达 `AodLyricModule`
类静态方法的代码路径(其父类是 XposedModule)都会 NoClassDefFoundError:

- `SuperLyricSource.register()` 调 `AodLyricModule.i` → **App 直连注册
  静默失败** → 卡片四"注册 OK"变成零帧、无歌词 ← 1.8~1.9.1 的"收不到歌词"
- 测试按钮的后台线程调 `AodLyricModule.i` → 线程无 handler → **闪退**

修复:日志门面移入 `AodLog`(纯 android.util.Log,不继承任何 libxposed
类型),Hook 进程里由 AodLyricModule 构造函数 `attachXposed` 挂上
framework-log 通道;全部 36 处调用点改走 AodLog。

**架构铁律**:一个类如果会被"非 Hook 进程"加载,它的父类链上绝对不能有
只有 Hook 进程才存在的类型。双进程模块里,日志/工具门面必须是独立的
进程无关类。

## 1.7.1 闪退 postmortem(1.7.2 修复)

```
AndroidRuntime: Unable to start activity
  at MainActivity.fmt(MainActivity.java:309)
  at MainActivity.refreshState(214)
  at MainActivity.onCreate(95)
```

根因:`fmt()` 里 `DateFormat.getDateFormat(null)` 传 null Context → NPE。
**讽刺的是这反而是好消息**:`fmt()` 只在有上报数据时才会执行——之前从未崩,
是因为从未有数据;这次崩,说明 Provider 通道第一次真正送来了内容。

教训:新代码路径第一次跑到 = 旧 bug 第一次暴露。修复后 refreshState 整体
包 Throwable(UI 渲染 bug 不允许再带崩整个 App——这个进程还承担着歌词
主源和 Provider 服务两个职责)。

另外日志确认:App 进程里 `setHiddenApiExemptions` 被 hidden API 拦截
(denied)——meta-reflection 在 targetSdk 34 的受限进程里不可用,与之前
分析一致;App 直连 SuperLyric 不依赖豁免,无碍。

## 2026-11-10 23:27 实测(用户设备)

```
module loaded: process=com.android.systemui      api=102 framework=LSPosed 2.2.1
module loaded: process=com.miui.aod:keyguardeditor
module loaded: process=com.miui.aod
所有进程:hooks installed / SuperLyric register OK / provider ok
SystemUI 进程:report lyric -> provider ok   ← 歌词数据已经流进渲染进程
```

## 遗留噪音(无害)

- 非 AOD 进程里 `hidden api bypass unavailable`:那些是 SystemUI 的附属进程
  或重复打开的 App 进程,meta-reflection 在 targetSdk 34 的受限进程里被拦,
  而三个关键进程 frameworkProperties=3(hiddenApiRestricted=false)不受影响,
  豁免成功。附属进程不需要 SuperLyric,可忽略。
- `serverApiVersion=0`:设备端 SuperLyric 是 3.x 系(super_lyric 传统接口),
  全量歌词经 legacy 通道,version_code 40 ≥ 35 所以有全量。
- `onStop: STOPPED`:音乐停了,正常。

## 二分结果汇总

| 探针 | dex 内容 | 结果 |
|---|---|---|
| A (cn.aodlyric.probe) | 仅探针类 | **加载 ✅** |
| C (probec) | A + manifest 组件 + superlyricapi meta | **加载 ✅** |
| D (probed) | A + kotlin-stdlib | **加载 ✅** |
| E (probee) | A + SLApi + **libxposed** + bypass + annotation | ✗(未提供输出) |
| B (probeb) | A + 全部依赖 | ✗(未提供输出) |
| 主模块 1.5.0 | A内容 + 全部依赖 + 完整逻辑 | ✗ |

**结论:E 是"D 减 libxposed 加 SLApi"的差集,E 失败而 D 成功 → 毒药锁定在
libxposed API 类被打进 dex**(bypass/annotation 也随 E 被排除,若 1.6.0 仍不加载
再回来细分)。旁证:SuperLyric 3.3 的 dex 里同样有 libxposed 类但能加载——
区别是它的宿主机制/类加载器形态,不深究;**不打包它**是 libxposed 的规范做法
(官方即 compileOnly),框架运行时会注入自己的实现。

## 1.6.0 的修复

dex 输入改为:我们自己的类 + SuperLyricApi + kotlin-stdlib。
libxposed API、hiddenapibypass、androidx.annotation 全部移出 dex:

- libxposed:保留在 javac classpath(compileOnly,框架运行时提供);
- bypass:改为**反射调用**(`Class.forName` + invoke),类不在 dex 也能工作,
  拿不到就退回 meta-reflection;
- annotation:CLASS retention,运行时从不加载。

历史教训(本轮排障过程中修掉的,供回忆):
1. `resources.arsc` 必须 STORED + 4 字节对齐(targetSdk≥30);
2. zipalign 的输入必须是 staged.apk;
3. kotlin-stdlib 必须进 dex(否则 Intrinsics 崩);
4. `Class.getDeclaredMethod` 经 invoke 调用只收两参数;
5. libxposed API 不能打进 dex(本轮);
6. `java_init.list` 不要尾部换行;`module.prop` 不要注释行;
7. 日志必须双通道(android.util.Log + xp.log),否则 logcat 看不到。

## 已确认的事实

| # | 事实 | 证据 |
|---|---|---|
| 1 | LSPosed 2.2.1 正常运行 | SuperLyric 的模块日志在打 |
| 2 | SuperLyric 发布链路正常 | com.salt.music 注册为 publisher;service 在尝试推送 |
| 3 | 我们的 App 直连 SuperLyric 成功 | 卡片四"注册 OK";DeadObjectException 正是 service 往**已被杀的我们 App 进程**推送失败 |
| 4 | 模块在 LSPosed 列表里、可启用、无警告、可勾作用域 | 你上一轮确认 |
| 5 | LSPosed 从未加载我们的模块(任何进程) | 模块日志里没有任何 `[cn.aodlyric.xiaowine,...]` 行 |

截图里那串 DeadObjectException **不是问题本身**——它是 SuperLyric 往我们 App 进程推歌词时,
App 进程已经被 MIUI 杀了。它反而证明:歌词源和发布链路全通。

## 第二轮二分(ProbeC 已证明 manifest 无毒;ProbeB 结果待定)

| 探针 | 包名 | 内容 | 大小 |
|---|---|---|---|
| A | cn.aodlyric.probe | 纯净 | 13 KB → **已证实能加载** |
| C | cn.aodlyric.probec | +Provider 组件 +superlyricapi meta | 13 KB → **已证实能加载** |
| B | cn.aodlyric.probeb | +全部依赖 dex(kotlin+SLApi+libxposed+bypass) | 717 KB |
| **D** | cn.aodlyric.probed | **+仅 kotlin-stdlib** | 660 KB |
| **E** | cn.aodlyric.probee | +其余全部依赖(**不含** kotlin) | 74 KB |

判定(装 B/D/E,看哪个有 `PROBE-x constructed`):

| B | D | E | 结论 |
|---|---|---|---|
| ✓ | ✓ | ✓ | 依赖全无害 → 毒药在主模块自己的类代码 |
| ✗ | ✗ | ✓ | **kotlin-stdlib dex 是毒药** |
| ✗ | ✓ | ✗ | SLApi/libxposed/bypass 某个是毒药 |
| ✓ | ✗ | ✓ | 不可能(逻辑冲突),出现即 D/E 之一装岔了 |

注:SuperLyric 3.3 的 APK **同样把 libxposed API 打进了 dex** 且能加载,
所以 libxposed 打包本身不必然是毒;但 3.3 是纯 Java(无 kotlin)。
你设备上装的 SuperLyric 版本若也是 3.x,则 kotlin 路径从未被它验证过。

**AodLyricProbe-1.0.apk**(13 KB)是一个只剩"呼吸"的模块:一个类、
构造函数 + 三个生命周期回调,每个都直接 `Log.w` 一行,没有任何依赖、
没有 Hook、没有 Provider。结构和 1.5.0 完全一致(同一工具链打包)。

### 步骤

```bash
adb install AodLyricProbe-1.0.apk
# LSPosed 里:启用 Probe,作用域勾 com.miui.aod(+ com.android.systemui)
# 重启 com.miui.aod / SystemUI / 手机

adb logcat -s AodLyricProbe
```

预期(加载成功):

```
AodLyricProbe: PROBE constructed
AodLyricProbe: PROBE onModuleLoaded: process=com.miui.aod...
AodLyricProbe: PROBE onPackageLoaded: pkg=com.miui.aod
AodLyricProbe: PROBE onPackageReady: pkg=com.miui.aod
```

### 判定

| 结果 | 结论 | 下一步 |
|---|---|---|
| **Probe 有输出** | 我们的 APK 打包没问题;1.5.0 里有**某个具体内容**导致加载失败 | 把主模块逐块回加,定位是哪个(SuperLyricApi 类?Provider?application 组件?) |
| **Probe 无输出** | 我们的**构建产物**结构性缺了 LSPosed 需要的东西(Gradle 打的包有、我们没有) | 对比 SuperLyric 3.3 真实 APK 的更多细节;或改用 gradle 构建一次作对照 |
| Probe 输出但主模块仍不输出 | 主模块 dex 里某处类初始化即崩,导致框架放弃 | 抓详细日志(管理器→详细日志)搜 cn.aodlyric |

## 关于截图里的 DeadObjectException

那些警告是 SuperLyric 系统服务往**已死的接收端**推送失败的记录,时间上紧跟
播放进行。两个来源之一:

1. 我们 App 进程(你打开过主模块 App,离开后被杀)——最可能;
2. 想确认的话:打开主模块 App 保持前台播放,这些警告应当停止。

它不代表 Hook 有问题,也顺带证明了 SuperLyric 的全链路是活的。
