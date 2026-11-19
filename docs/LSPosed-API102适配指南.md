# 新版 AOD 歌词模块 · LSPosed API 102 适配指南

> 依据：`io.github.libxposed:api:102.0.0` 与 `101.0.0` 的 AAR 逐类 `javap` 对比（见附录）。
> 所有签名均来自实测，非推测。

---

## 0. 先说清楚：API 102 相对 101 到底加了什么

| 位置 | 101 | 102 |
|---|---|---|
| `XposedInterface.LIB_API` | `101` | `102` |
| `XposedInterface.API_101 / API_102` | 不存在 | **新增**两个常量 |
| `XposedModuleInterface` | 4 个生命周期回调 | **+2**：`onHotReloading`、`onHotReloaded` |
| `HookBuilder` | `setPriority` / `setExceptionMode` / `intercept` | **+ `setId(String)`** |
| `HookHandle` | `getExecutable` / `unhook` | **+ `getId()` / `replaceHook(Hooker)`** |
| 新增嵌套接口 | — | `HotReloadingParam`、`HotReloadedParam` |

**没有变化的**（也就意味着 101 的代码基本能直接升）：

- `XposedModule` 抽象类、`PackageLoadedParam`、`PackageReadyParam`、`SystemServerStartingParam`、`ModuleLoadedParam`
- `Chain`（`proceed` / `proceedWith` / `getArg` …）
- `ExceptionMode`（`DEFAULT` / `PROTECTIVE` / `PASSTHROUGH`）
- 常量 `PROP_CAP_SYSTEM = 1`、`PROP_CAP_REMOTE = 2`、`PROP_RT_API_PROTECTION = 4`
- 优先级常量 `PRIORITY_DEFAULT = 50` / `LOWEST` / `HIGHEST`

> **一句话**：API 102 的实质增量只有 **热重载**（hot reload）。
> 其余都是为了让热重载成立而加的配套设施（给 Hook 起 id、能按 id 换实现）。

---

## 1. 工程配置

### 1.1 依赖

```kotlin
dependencies {
    // 编译期 API，不打包进 APK
    compileOnly("io.github.libxposed:api:102.0.0")

    // 需要「动态作用域 / 远程 SharedPreferences / 热重载结果回调」时才加
    implementation("io.github.libxposed:service:102.0.0")
}
```

`service` 会往你的 `AndroidManifest` 合并一个 provider：

```xml
<provider
    android:name="io.github.libxposed.service.XposedProvider"
    android:authorities="${applicationId}.XposedService"
    android:exported="true" />
```

### 1.2 模块声明（放在 `app/src/main/resources/META-INF/xposed/`）

`module.prop`

```properties
minApiVersion=101
targetApiVersion=102
staticScope=false
```

> **建议 `minApiVersion` 写 101，不要写 102。**
> Maven 上 `io.github.libxposed:api` 只有 101.0.0 / 101.0.1 / 102.0.0 三个版本，没有 100。
> 写 101 能让模块同时装在 101 和 102 的 LSPosed 上；热重载能力用运行时判断降级：
> `if (getApiVersion() >= XposedInterface.API_102) { ... }`
> （参考：SuperLyric 自己就是 `minApiVersion=101` / `targetApiVersion=101`）

`scope.list`

```text
com.miui.aod
```

> `staticScope=false` 表示作用域运行时可变（用户在 LSPosed 里勾选）。
> 若要做成**不可变作用域**（更省内存、更安全），改成 `staticScope=true`。

`java_init.list`  ← **入口类清单，必须有**

```text
cn.aodlyric.xiaowine.AodLyricModule
```

### 1.3 入口声明方式变了

旧框架用 `assets/xposed_init`；libxposed 改用 **`META-INF/xposed/java_init.list`**，
内容是一行一个入口类的全限定名（实测 SuperLyric 的该文件内容只有一行
`com.hchen.superlyric.HookEntrance`）。

**不是靠扫描 `META-INF/xposed` 目录自动发现** —— 少这个文件模块就不会被加载，
而且**不会有任何报错**，只会表现为「模块显示已启用但什么都不生效」。

---

## 2. 代码骨架

### 2.1 从旧 API 迁移对照表

| 旧（`de.robv.android.xposed`） | 新（`io.github.libxposed.api` 102） |
|---|---|
| `implements IXposedHookLoadPackage` | `extends XposedModule` |
| `handleLoadPackage(lpparam)` | `onPackageLoaded` / `onPackageReady` |
| `initZygote(startupParam)` | `onModuleLoaded(param)` |
| `XposedBridge.hookMethod(m, cb)` + `beforeHookedMethod` | `hook(m).intercept(chain -> …)` |
| `XposedBridge.log(s)` | `log(Log.DEBUG, TAG, s)` |
| `XSharedPreferences` | `getRemotePreferences(name)` |
| `XposedHelpers.findClass` | 自己用 `ClassLoader.loadClass` |
| `param.setResult(v)` / `param.getResult()` | `return v;` / `return chain.proceed();` |
| `param.args[i]` | `chain.getArg(i)` |
| `XModuleResources.createInstance(modulePath, null)` | `createPackageContext(getModuleApplicationInfo().packageName, 0)` |

### 2.2 模块主类

```java
package cn.aodlyric.xiaowine;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class AodLyricModule extends XposedModule {

    private static final String TAG = "AodLyric";

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", systemServer=" + param.isSystemServer()
                + ", api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        // 太早：类还没加载，只能做最轻量的判断
        if (!"com.miui.aod".equals(param.getPackageName())) return;
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!"com.miui.aod".equals(param.getPackageName())) return;

        ClassLoader cl = param.getClassLoader();
        LyricBridge.install(this, param.getApplicationInfo(), cl);
    }

    // ── API 102 专属：热重载 ────────────────────────────────
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        // 返回 false 可中止本次热重载
        // 这里把需要跨重载保留的状态塞进去
        param.setSavedInstanceState(LyricBridge.snapshot());
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        // 旧 Hook 句柄由框架交还，模块自行决定复活哪些
        LyricBridge.restore(param.getSavedInstanceState(), param.getOldHookHandles());
        log(Log.INFO, TAG, "hot reloaded, old handles=" + param.getOldHookHandles().size());
    }
}
```

### 2.3 Hook `MediaMetadata#getString` 注入 LRC

这是分析报告里定下的**方案 A**，用 API 102 写出来是这样：

```java
static void install(XposedInterface xp, ApplicationInfo self, ClassLoader cl) {
    try {
        Method getString = MediaMetadata.class.getMethod("getString", String.class);

        // setId 是 102 新增，热重载时可按 id 定位/替换
        xp.hook(getString)
          .setId("lyric-metadata-getString")
          .setPriority(XposedInterface.PRIORITY_DEFAULT)
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)   // 自身出错不影响宿主
          .intercept(chain -> {
              Object key = chain.getArg(0);
              if (LYRIC_KEY.equals(key)) {
                  String lrc = LyricBridge.currentLrc();
                  if (lrc != null) return lrc;      // 替换返回值
              }
              return chain.proceed();               // 走原逻辑
          });
    } catch (Throwable t) {
        xp.log(Log.ERROR, TAG, "hook getString failed", t);
    }
}
```

要点：

1. **`intercept` 只有「拦截」一种语义**，没有 before/after 之分。要「改参数」→ 用
   `chain.proceed(newArgs)`；要「改返回值」→ 直接 `return`；要「只做旁观」→ `return chain.proceed()`。
2. **`setExceptionMode(PROTECTIVE)` 很关键** —— 模块抛异常时静默吞掉，
   否则会顺着 `MediaMetadata.getString` 冒到 AOD 的 MAML 渲染链上把息屏搞崩。
3. **必须按 key 精确过滤**，`getString` 还被用来取 TITLE/ARTIST/ALBUM，
   不过滤会把标题变成歌词。
4. **返回值要缓存**。`getString` 在 AOD 帧循环里可能每秒被调多次，
   每次重建 LRC 字符串会掉帧。只在「切歌」或「onLyric 变更」时重算一次。

### 2.4 接 SuperLyric

```java
SuperLyric.receiver().register(new LyricListener() {
    @Override public void onLyric(LyricView view) {
        Lyric doc = view.getLyric();
        String lrc = LrcBuilder.build(doc);     // 见下
        cache.put(doc.getId(), lrc);
        current = lrc;
    }
    @Override public void onStop(Stop stop) {
        current = null;
    }
});
```

LRC 拼装（**注意必须是 CRLF**，`MusicLyricParser.doParse` 用 `"\r\n"` 切行）：

```java
static String build(Lyric doc) {
    StringBuilder sb = new StringBuilder(doc.getLines().size() * 32);
    for (Line line : doc.getLines()) {
        long ms = line.getStart();
        sb.append('[')
          .append(String.format("%02d:%02d.%02d",
                  ms / 60000, (ms % 60000) / 1000, (ms % 1000) / 10))
          .append(']')
          .append(line.getText())
          .append("\r\n");
    }
    return sb.toString();
}
```

### 2.5 隐藏 API：`ServiceManager`

`SuperLyricApi` 的 `BinderHub` 走 `android.os.ServiceManager.getService("super_lyric.v4")`。
libxposed **不会**自动给你豁免隐藏 API，先用框架属性探测：

```java
long props = getFrameworkProperties();
boolean restricted = (props & XposedInterface.PROP_RT_API_PROTECTION) != 0;
```

- `restricted == false` → 直接调，不用管；
- `restricted == true` → 需要 `HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")`
  （或等价的反射豁免手段），否则 `getService` 抛 `NoSuchMethodError`。

> 这是整个迁移里**最可能翻车的一步**，建议第一版原型只验证这一件事。

---

## 3. 三条落地路线，选一条

| 路线 | 做法 | 适合 |
|---|---|---|
| **A. 纯 libxposed** | 直接用 `io.github.libxposed:api:102.0.0`，自己写 `XposedModule` | 推荐。依赖最少，签名最清晰 |
| **B. 借 HookTool 3.2.2** | `com.github.HChenX:HookTool:3.2.2`，用 `AbsModule` + `@HookThis` + `CoreTool.hookAllMethod` | 想和 SuperLyric 保持同一套写法、少写模板代码 |
| **C. 直接抄 SuperLyric 的分层** | `:hook-anchors` / `:processor` 那套 | 打算长期维护、多 App 适配 |

**B 的代价**：多一个 jitpack 依赖 + 一个注解处理器，且 `@HookThis` 是 SOURCE 保留，
Kotlin 写的 provider 不生效（SuperLyric 的 CLAUDE.md 里明确写了这个坑）。
如果只是给 `com.miui.aod` 做一个小模块，**A 路线足够**。

---

## 4. 升级检查清单

- [ ] `compileOnly("io.github.libxposed:api:102.0.0")`
- [ ] `META-INF/xposed/module.prop`：`minApiVersion=101` / `targetApiVersion=102` / `staticScope=false`
- [ ] `META-INF/xposed/scope.list` 含 `com.miui.aod`
- [ ] `META-INF/xposed/java_init.list` 含入口类全限定名（**漏了会静默失效**）
- [ ] 入口类 `extends XposedModule`，删掉所有 `de.robv.android.xposed.*` import
- [ ] 所有 `beforeHookedMethod/afterHookedMethod` 改写为 `intercept` + `chain.proceed()`
- [ ] 所有 `XposedBridge.log` 换成 `log(priority, TAG, msg)`
- [ ] `XSharedPreferences` 换成 `getRemotePreferences(name)`
- [ ] `XModuleResources` 换成 `createPackageContext(getModuleApplicationInfo().packageName, 0)`
- [ ] 关键 Hook 都加 `setId(...)`（热重载友好）
- [ ] 有副作用的 Hook 都设 `ExceptionMode.PROTECTIVE`
- [ ] 实现 `onHotReloading` / `onHotReloaded`（可选，但不实现就跑不了 LSPosed 的热重载调试）
- [ ] 隐藏 API 探测 `PROP_RT_API_PROTECTION` 并做好豁免

---

## 附录：102 关键签名（javap 实测）

```java
public interface XposedModuleInterface {
    default void onModuleLoaded(ModuleLoadedParam param);
    default void onPackageLoaded(PackageLoadedParam param);
    default void onPackageReady(PackageReadyParam param);
    default void onSystemServerStarting(SystemServerStartingParam param);
    default boolean onHotReloading(HotReloadingParam param);   // 102 新增
    default void onHotReloaded(HotReloadedParam param);        // 102 新增
}

interface HotReloadingParam {                 // 102 新增
    Bundle getExtras();
    void setSavedInstanceState(Object state);
}

interface HotReloadedParam extends ModuleLoadedParam {   // 102 新增
    Bundle getExtras();
    Object getSavedInstanceState();
    List<HookHandle> getOldHookHandles();
}

interface XposedInterface {
    int API_101 = 101;   // 102 新增常量
    int API_102 = 102;   // 102 新增常量
    int LIB_API = 102;
    long PROP_CAP_SYSTEM = 1L;
    long PROP_CAP_REMOTE = 2L;
    long PROP_RT_API_PROTECTION = 4L;
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST  = Integer.MIN_VALUE;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    int getApiVersion();
    String getFrameworkName();
    String getFrameworkVersion();
    long getFrameworkVersionCode();
    long getFrameworkProperties();
    HookBuilder hook(Executable e);
    HookBuilder hookClassInitializer(Class<?> c);
    boolean deoptimize(Executable e);
    Invoker<?, Method> getInvoker(Method m);
    <T> CtorInvoker<T> getInvoker(Constructor<T> c);
    void log(int priority, String tag, String msg);
    void log(int priority, String tag, String msg, Throwable t);
    ApplicationInfo getModuleApplicationInfo();
    SharedPreferences getRemotePreferences(String name);
    String[] listRemoteFiles();
    ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException;

    interface HookBuilder {
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode mode);
        HookBuilder setId(String id);          // 102 新增
        HookHandle intercept(Hooker hooker);
    }

    interface HookHandle {
        Executable getExecutable();
        void unhook();
        String getId();                        // 102 新增
        HookHandle replaceHook(Hooker hooker); // 102 新增
    }

    interface Hooker { Object intercept(Chain chain) throws Throwable; }

    interface Chain {
        Executable getExecutable();
        Object getThisObject();
        List<Object> getArgs();
        Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException;
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
        Object proceedWith(Object result) throws Throwable;
        Object proceedWith(Object result, Object[] args) throws Throwable;
    }

    enum ExceptionMode { DEFAULT, PROTECTIVE, PASSTHROUGH }
}
```
