# AOD Lyric（重建版）— 息屏歌词 · SuperLyric 桥接模块

**适配目标**：`com.miui.aod` DEV-2455.1.0.1-09282249（息屏与锁屏编辑）
**歌词来源**：SuperLyric（Binder，`super_lyric.v4`）
**框架**：LSPosed libxposed API **101 兼容**（`io.github.libxposed:api:102.0.0` 编译，
`targetApiVersion=101` 声明；102 专属的 `HookBuilder.setId` 用 `getApiVersion()` 守卫）

## 一键构建（根目录 `build.ps1`）

```powershell
# 在仓库根目录执行：只构建，产物在 dist\
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 构建 + 安装到已连接设备
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install

# 构建 + 安装 + 重启（改了 Hook 代码时必须重启才生效）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install -Reboot

# 丢弃中间产物，全量重建
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

- 包名：`cn.qxfly.aod`；Provider authority：`cn.qxfly.aod.state`；
- **版本号唯一来源**：`module\app\src\main\AndroidManifest.xml`
  （`versionName` / `versionCode`，当前 **1.0.261119.001 / 261119001**）。
  打包时自动同步到 APK 内的 `META-INF/xposed/module.prop`，不需要改两处。
  版本号格式：`主.次.YYMMDD.当月第N次`，例如 `2.4.260315.007`。
- 产物：`dist\qxfly-aod-<versionName>.apk`（已 zipalign、已签名），
  脚本最后打印体积与 sha256；
- 中间产物：`module\build\`（可随时删）；签名密钥：`module\debug.keystore`
  （**不要删**，换了密钥就无法覆盖安装）；
- 其它文档统一放在 `docs\`：`BISECT.md`（历史故障复盘）、
  `COMPOSE_SETUP.md`（工具链搭建）、`LSPosed-API102适配指南.md`、
  `项目分析报告.md`、`assets\`（界面参考图）；
- 注意：`build.ps1` 含中文注释，**必须保存为 UTF-8 with BOM**，
  否则 Windows PowerShell 5.1 会按 ANSI 解析并报语法错误。

### 包名切换：从 `cn.aodlyric.xiaowine` 到 `cn.qxfly.aod`

1.0 起应用包名改为 `cn.qxfly.aod`（Provider authority 同步为 `cn.qxfly.aod.state`）。
包名变更后 **LSPosed 视为全新模块**，旧的启用状态与作用域不会迁移，首次安装后必须：

1. `adb uninstall cn.aodlyric.xiaowine`（或不卸载，两个包可共存）；
2. 安装 `qxfly-aod-1.0.apk`；
3. 打开 LSPosed → 模块 → 找到「息屏歌词」→ 启用，作用域勾选
   `com.miui.aod` 与 `com.android.systemui`；
4. 重启手机（或 `adb reboot`）。

未完成第 3 步时表现是：App 能开，但 `adb logcat -s AodLyric` 一行输出都没有
（Hook 根本没被加载），息屏不显示歌词。旧包的样式设置随卸载丢失，需重新保存一次歌词外观。

## 8.1.0：可靠性 / 性能 / 信息量（本轮）

### 后台可靠性

| 问题 | 现象 | 处理 |
|---|---|---|
| 完整歌词来源随 Activity 死亡 | 关掉 App 后只剩单行歌词（legacy 传输不带全文） | `LyricPublisher` 提到 `Application`：进程活着就保持注册，`onDestroy` 不再 `unregisterAll` |
| HyperOS 冻结后台进程 | 息屏歌词偶发不刷新 | 新增可选的 `KeepAliveService` 前台服务（默认关闭），设置页可开；另提供「忽略电池优化」入口 |

### 主线程负载

* **Kicker 降载**：原来每 1 秒在主线程做一次完整结构性重建（`reinflate` + `forceTraversal`）。
  现在改为分档：内容变化后 4 秒内保持 1 秒一次，之后每 3 秒一次；等待中的脉冲会强制走重档。
* **脉冲补发**：`requestPulse()` 在 4 秒节流窗口内不再直接丢弃，而是置 `sPulseWanted`，
  由 kicker 在窗口打开的第一时间补发 —— 快歌不再出现「换了行但面板没刷新」。
* **Provider 轮询自适应**：保留 1 秒 tick，但真正的 `content call` 只在
  ContentObserver 标记脏 / 心跳到点（播放中 5s、AOD 可见 10s、否则 30s）时执行；
  早期版本是两个宿主进程每秒各做一次 binder 调用，暂停时也一样。
* **渲染签名不含进度**：进度是外推出来的，每秒都在变，把它算进变更标记等于每秒强制重绘一次。
  现在只有开了「显示播放进度」才把位置纳入签名。
* **状态快照不再秒级落盘**：歌词/日志记录重启后本来就被丢弃（防幽灵歌词），
  却曾经每次更新都重写整个快照文件。现在只有设置类更新才持久化。

### 新增的歌词信息

外观页 → **附加信息**，四项均可独立开关：

| 开关 | 效果 |
|---|---|
| 显示歌名与歌手 | 歌词上方一行，58% 字号、55% 透明度 |
| 显示下一句 | 歌词下方淡色预览（`LyricView.getUpcoming()`，或 legacy 全量载荷的下一行） |
| 显示播放进度 | `1:23 / 4:05`，暂停时追加 `⏸` |
| 暂停时保留歌词 | 暂停时把当前行降到 55% 透明度保留，而不是消失 |

歌名/歌手/下一句来自歌词源：优先本进程 v4 流，其次 legacy（3.x）载荷的
`title/artist/allLyrics`，最后读 UI 进程经 `StateKeys` 发布的快照。
**进度与播放状态来自系统 `MediaSession`**（见 8.1.1）：`serverApiVersion=0`
的旧版 SuperLyric 既不触发 v4 回调，legacy 载荷里也没有 `duration/position`，
唯一稳定来源是宿主进程（SystemUI，持有 `MEDIA_CONTENT_CONTROL`）直接读
活动会话的 `PlaybackState` / `MediaMetadata`。

### 其它

* **样式预设**：极简 / 粗描边 / 胶囊底，只改观感不动锚点。
* **导入导出**：SAF 选择文件，JSON 带 `format` + `version`，便于以后兼容。
* **诊断页**：设置 → 诊断信息。展示跨进程快照、Hook 进程日志尾（点「请求 Hook 日志」
  写 `diagRequest`，Hook 在下一次轮询把环形缓冲 30 行回传）、本机日志，一键复制。
  另有「记录调试日志」开关，把 `AodLog.d` 打开。
* **日志分级**：`AodLog` 增加环形缓冲（240 行）与 debug 级别；高频路径的
  `AodLog.i` 降为 `d`，正常使用不再刷屏。
* **快照 schema**：`schema=2`，`OBSOLETE_KEYS` 在加载时清掉历史死键。
* **大文件拆分**：`AodOverlayController` 3013 → 2700 行，抽出
  `AnchorIds`（锚点 id 折叠规则，纯逻辑）、`ComponentCollector`（组件树枚举）、
  `MamlBridge`（MAML 反射 + 面板刷新杠杆）。
* **JVM 测试**：`work/run-tests.ps1` 编译并运行 `module/test/java` 下的纯逻辑用例
  （锚点映射 / LRC 生成 / 渲染签名 / 歌名交叉校验 / 进度外推），共 56 条断言，
  不引入 JUnit。
* **LRC 转义修正**：原来先转义 `<` 再转义 `&`，把 `a<b` 变成 `&amp;lt;`，
  面板于是显示字面量 `&lt;`。现在先 `&` 后 `<`，并有测试覆盖。

新增构建检查（可选，不影响 APK）：

```powershell
powershell -ExecutionPolicy Bypass -File .\work\run-tests.ps1
```

## 8.1.1：下一句 / 进度实测修复（2026-11-20 第十八轮）

### 现象

外观页开了「下一句」和「播放进度」，息屏上一行都没有；歌名歌手也时有时无。

### 真机取证（不是推测）

Java 进程侧 `SuperLyric.serverApiVersion() == 0` → 这是 **3.x 旧版协议**，
`LyricListener.onLyric(LyricView)` / `onStop` 之外的所有 v4 回调
（`getPosition/getDuration/getPlaybackState/getUpcoming`）根本不触发。
Provider 快照当时是 `lyricNext=` 空、`lyricDuration/lyricPosition/lyricTitle`
全部 MISSING、`lyricPlayState=stopped`（实际正在播放）。

把 legacy 载荷逐字段打出来后结论更硬：

```
full=false  upcoming=0  idx=-1/0  dur=-1  pos=-1  id=null
secondary=-  extra=-  album=…  translation=…
```

即：`com.salt.music` 的歌词发布端**只发当前句**（连 `allLyrics`、行号、
时长、位置都没有），所以下一句 / 进度在旧链路上无解。

### 修复

1. **进度改读系统 `MediaSession`**（新文件 `MediaSessionSource`）。
   宿主是 `com.android.systemui`，已持有 `MEDIA_CONTENT_CONTROL`，
   `getActiveSessions()` 拿到活动会话后直接读 `PlaybackState`
   （state / position / speed / lastPositionUpdateTime）与 `MediaMetadata`
   （duration / title / artist）。读取走 `MediaController` 的本地缓存，
   1 Hz 轮询不做 binder 往返；会话列表只在当前会话失效或长期终态时才枚举
   （`MediaController` 无公开 `release()`，频繁枚举会累积回调 binder）。
2. **歌名歌手优先用歌词源**：legacy 载荷的 `title/artist` 是干净的原始值；
   部分播放器把 `artist` 写成 `"歌手 - 歌名"`，覆盖只在本地无值时才用，
   且 `trackLine()` 遇到 `artist.contains(title)` 不再重复拼一遍。
3. **`ModuleReporter.reportPlayback()`**：把 `title/artist/next/duration/
   position/posAt/playState` 合并成一条 lyric 上报（900 ms 节流），
   legacy 接收端与 media session 都往这里写，App 端快照不再是「stopped」。
4. **`LegacyLyricReceiver.Sink.onPlayback()`**：把 `hasDuration/getDuration`、
   `hasPosition/getPosition`、`lyricId`、`fullPayload/upcomingCount` 一并传出，
   顺带把 `getUpcomingLyrics()` 作为下一句的首选来源。
5. **进度行容错**：`duration<=0` 但位置已知时仍显示 `0:42`，不再整块消失。
6. **「显示进度」时轮询到手**：AOD 可见且「有行、开了进度」时 poller 每个
   tick 都刷新（`AodOverlayController.hasContent()`），否则维持 5/10/30s 心跳。
7. **顺手修掉两个真 bug**：
   * `hideOverlay()` 在 poll 线程调 `restoreExpandedHeights()` → 
     `CalledFromWrongThreadException`，容器高度没能还原；现在整体 post 到主线程。
   * `applyText()` 每帧都 `reinflate()`（remove + add），tree 模式下会让整个
     AOD 每秒抖一次；现在 tree 模式跳过。
8. `LyricDisplay` 的渲染签名在开进度时才带位置，进度秒跳不会触发结构性重建。

### 实测（Xiaomi 14 / HyperOS 3 / 品冠《戒心伤》）

息屏上三行同时出现，进度实时跳动：

```
品冠 - 戒心伤
有一些爱 一碰就流浪
2:36 / 3:30
```

Provider 快照同步为
`lyricTitle=戒心伤`、`lyricArtist=品冠`、`lyricDuration=210217`、
`lyricPosition=122494`、`lyricPlayState=playing`。

### 仍然存在的限制（明确写清楚）

**下一句依赖完整歌词文档。** 本机 `com.salt.music` + SuperLyric 3.3 只发
当前句（`full=false`、无 `allLyrics`、无 `extra`），所以下一句无法凭空生成，
开关打开也不会显示；换到会发布全量文档的播放器 / 新协议时自动生效。
外观页该项说明文字已注明这一点。

## 8.1.2：息屏歌词「几秒后不动」取证 + 歌名交叉校验（2026-11-21 第十九轮）

### 现象

息屏歌词、进度走几秒就停住；另外歌名行有时显示**别的歌**（如正在播日文歌，
标题却是「羽·泉 - 奔跑」）。

### 取证 A：PowerKeeper 因静音暂停音频（环境限制，非代码回归）

7 组对照实验坐实的因果链（设备 Xiaomi 14 / HyperOS 3，播放器 `com.salt.music`）：

```
STREAM_MUSIC Muted=true (streamVolume=0)
  → 播放器持续写静音数据
  → MIUI PowerKeeper: isLongTimeMusicSilent out of time 60S
  → pauseAudioTracks success (sessionId=249, audioFlag=1 type=5)
  → 播放位置冻结 → 歌词回调断流 → 息屏画面不动
```

| 条件 | 结果 |
|---|---|
| 静音 + 模块 ON | 12s 后 `pauseAudioTracks`，歌词停 |
| 静音 + 模块 OFF | 17s 后照样暂停 → **与本模块无关** |
| 解除静音 + 模块 ON | 120s **0 次暂停**，歌词每 5–7 秒换行，`audioTrackData` 数据健康 |
| 重新静音 | 30s 再次复现暂停 |

代码侧 `grep AudioManager|setStreamMute|adjustStreamVolume|requestAudioFocus`
= 0 匹配，模块从不碰音量。**结论：这是环境限制** —— 息屏歌词要正常，
`STREAM_MUSIC` 必须未静音且音量 > 0。解除静音用
`input keyevent KEYCODE_VOLUME_UP`（`cmd media_session volume --stream 3 --set 10`
不会解除 Muted）。

另注：`LegacyLyricReceiver.handleLyric` 的 `mLastText` 去重会静默丢弃重复行，
「0 条 legacy lyric 日志」≠ 回调断流，要看 `lyricAt` 是否仍在刷新。

### 取证 B：歌名脏数据（两个源都不能单独信）

* **SuperLyric legacy 载荷的曲目信息会滞后**：实际已切到别的歌，载荷仍是
  `track=奔跑/羽·泉 album=拾伍 重唱集`；
* **会话 `METADATA_KEY_TITLE` 不是曲名**：`com.salt.music` 把**当前歌词行**
  写进 TITLE（真机日志 `now playing: 仍想穿越命运浩瀚 / …`，2 秒一变），
  真实歌手在 `ARTIST` 里（`茅野愛衣 - あの日のままで`）。

### 修复

1. **`TrackInfo.resolve()`（新文件，纯逻辑 + 单测）交叉校验两个源**：
   * 会话 `ARTIST` 里找不到载荷 `artist` → 载荷元数据属于旧歌，改用会话值；
     此时若会话 TITLE 只是屏幕上的歌词行（盐歌模式）则**不作曲名**，
     标题行降级为只显示歌手 —— 宁可少显示，不能显示错歌。
   * 无矛盾 → 载荷优先（原始值干净），会话只补空，维持原行为。
   真机验证：息屏中 `dispatch next` 切歌，标题行跟上新曲
   （`说走就走 [mqms2] - 周杰伦`），不再残留旧曲。
2. **进度外推加时效上限**（`MediaSessionSource.POSITION_MAX_EXTRAPOLATION_MS
   = 30s`）：播放器偶发「state=PLAYING 但 `lastPositionUpdateTime` 冻结」，
   原逻辑会照常按时钟外推，几分钟就把显示顶到 `4:50/4:50` 钉死在结尾；
   现在超过 30s 的外推一律截断，会话恢复刷新时显示自动纠正。
3. **测试**：`TrackInfoTest`（9 组场景）+ `MediaSessionSourceTest`（外推边界）
   共 25 条新断言，`work/run-tests.ps1` 56 条全过。

### 实测（切歌前后息屏截图，间隔 15s）

```
切歌前：茅野愛衣 - あの日のままで / スカート揺れる浜辺 / 0:31 / 5:33
切歌后：说走就走 [mqms2] - 周杰伦 / SP : Universal Music… / 0:16 / 4:26
+15s ：说走就走 [mqms2] - 周杰伦 / 你的侧脸逆着光 背对夕阳 / 0:32 / 4:26
```

标题、歌词行、进度三者同步更新，`pauseAudioTracks` 计数 0、
`lyric poll iteration failed` 0。

## 8.1.3：面板冻结真因修复（2026-11-22 第二十轮）

8.1.2 解决了「数据不动」，但还剩一个更隐蔽的问题：**数据一直在更新，物理屏
却停在某一帧不动**。`screencap` 截图里歌词在变，肉眼却看不到刷新。

### 根因链

```
AOD 稳定后 → 显示状态被钉在 DOZE_SUSPEND(4)
           → 合成器不再向物理屏送帧（缓冲区仍在更新，screencap 因此照旧）
pulse 广播 → 被 AOSP DozeTriggers.requestPulse 的守卫丢弃
           （mPulsePending / mAllowPulseTriggers / canPulse()）
我们的 doze 守卫 → 挂在 com.miui.aod.doze.DozeMachine，该类从未被加载
```

关键认知修正：真正运行的 doze 服务是 SystemUI 的
`com.android.keyguard.doze.MiuiDozeService`（用 SystemUI 内置 AOSP
`com.android.systemui.doze.DozeMachine`），AOD 插件里的
`com.miui.aod.doze.DozeMachine` 根本不参与。

### 修复：播放期间把 `DOZE_SUSPEND` 改写成 `DOZE`

AOSP 为「不支持 doze-suspend 的屏幕」自带 `DozeSuspendScreenStatePreventingAdapter`，
做法就是 `if (state == 4) state = 3`。照搬，但加播放门控：

| 组件 | 作用 |
|---|---|
| hook `android.service.dreams.DreamService.setDozeScreenState(int)` | 唯一拦截点（`MiuiDozeService` 继承 `DreamService` 且未覆写） |
| hook `MiuiDozeService.onCreate` | 提前捕获服务实例，不必等分钟边界 |
| hook `DozeMachine.<init>` | 捕获状态机，读 `mState` 决定释放时是否恢复 4 |
| `sSelfApply` 标记 | 区分自身调用与机器请求，避免周期性重申污染簿记 |

「欠恢复」不能用简单布尔：我们改写后机器认为已经设过 4，不会再请求，清标记
就会永久停在 `DOZE`。所以恢复前读机器状态，仅当 `DOZE_AOD_PAUSED` 时才发 4。

小米触控特性 `mode 19 (keep-screen-on)` / `mode 11 (aod-animate)` 实测
**不改变电源状态**（`setTouchMode` 还是 PRIVATE STATIC，需要
`getDeclaredMethod` + `setAccessible`），保留作辅助，不指望它。

### 真机实测（Xiaomi 14 / Android 16 / HyperOS 3）

| 场景 | `Display State` | 10 秒帧数（`dumpsys gfxinfo com.android.systemui`） |
|---|---|---|
| 播放 + 息屏 | `DOZE`，跨分钟边界不掉 | **+1150（约 115 fps）** |
| 暂停 + 息屏 | `DOZE_SUSPEND` | **0** |

日志：

```
AodLyric: doze service captured: com.android.keyguard.doze.MiuiDozeService
AodLyric: panel hold: DOZE_SUSPEND -> DOZE
AodLyric: panel state -> DOZE
AodLyric: overlay idle (no lyric)
AodLyric: panel state -> DOZE_SUSPEND
```

省电行为符合预期：只有播放期间保持 `DOZE`，暂停/空闲立刻交还面板自刷新。

## 8.0.0：Miuix 界面（骨架合并）

`miuix/` 下的 Miuix 模板骨架（Miuix 0.9.4 + Compose，Gradle 构建）已合并进主模块：

```
App 进程（MainActivity, ComponentActivity）
  ├─ Java 侧：Provider 调用 / SuperLyric 直连注册 / 测试注入 —— 逻辑全部留在这里
  ├─ ModuleData (Kotlin) ── 状态流（cards/lyric）+ 动作回调（fun interface，Java lambda 可实现）
  └─ Compose 侧（module/app/src/main/kotlin/cn/aodlyric/xiaowine/ui/）
       ├─ core/     AppSettings(SharedPreferences)、AppTheme、AppNavigation(Route/AppNavigator)、ModuleData
       ├─ base/     MiuixPage（页面骨架）、GlassBar（毛玻璃）
       ├─ pages/    HomePage（正在播放+状态卡片）、SettingsPage（主题+歌词外观）、AboutPage
       └─ MiuixAppRoot（底栏导航 + miuix-nav 独立标签栈）、ModuleUi.mount()（Java 入口）
```

要点：

- **数据流不反转**：Provider/SuperLyric 逻辑留在已验证的 Java 代码里，Compose 纯展示；
  Compose 挂载失败时回退旧 Java 界面（`mountMiuixUi()` 返回 false）。
- **设置存储**：主题偏好用 SharedPreferences（骨架原为 DataStore，去掉依赖）；
  歌词外观仍走 Provider（Hook 进程轮询生效）。`LyricStyle` 是 Java 可变类，
  Compose 里用 `StyleDraft` 数据类镜像，保存时转换回去。
- **依赖升级**（`work/upgrade_m094.py` 产出的独立工具链，不影响 0.8.8 旧路径）：
  Miuix 0.9.4（6 模块拆分：ui/preference/nav/blur/icons/squircle）+ Compose 1.12.0 +
  lifecycle 2.11.0 + navigationevent 1.1.2 + serialization-json 1.11.0。
- **Kotlin 2.4.21 工具链**（`work/fetch_toolchain24.py`）：miuix 0.9.4 是 2.4.20 元数据，
  app 必须用 ≥2.4 编译；**@Serializable 需要 serialization 编译器插件**
  （`kotlin-serialization-compiler-plugin`，构件名不是 kotlinx- 前缀），否则
  `rememberNavBackStack` 运行时拿不到序列化器。
- 构建：根目录 `build.ps1`（当时为 `module\build_miuix2.ps1`，已在目录整理时合并）
  → `dist\qxfly-aod-<versionName>.apk`。

### 8.0.0 真机验证结果（2026-11-14，HyperOS 4.0 / Android 17，LSPosed 2.2.1）

| 项 | 结果 |
|---|---|
| 应用启动 + Miuix Compose UI 渲染 | ✅ 三 Tab（歌词/设置/关于）、卡片、滑块、开关全部正常，无崩溃 |
| LSPosed Hook 加载 | ✅ SystemUI + com.miui.aod 两进程均装上（`lyric bridge installed`） |
| Provider → Hook → AOD 渲染链路 | ✅ 注入测试歌词，AOD 屏幕实拍可见，`overlay alive ... aodVisible=true` 心跳正常 |
| 应用内「注入测试歌词」按钮 | ✅ `test lyric injected (LRC + LINE)` → Hook `provider LRC picked up: 98 chars` |
| 歌词外观编辑 + 保存并应用 | ✅ 位置 0.70→0.37→0.90、字号 21→20 实时反映到 AOD（`MiuiX style saved`） |
| 组件锚点定位（8.0.1） | ✅ 见下节 |
| 指纹崩溃守卫（8.0.1） | ✅ 见下节 |
| 对齐方式 / 插入组件树 / 颜色盘+预览（8.0.2） | ✅ 见下节 |
| HiddenApiBypass | ✅ `HiddenApiBypass.addHiddenApiExemptions(L) -> true`（需 `.deps/libs/hiddenapibypass-6.2-classes.jar` 进 dex） |

真机调试小抄：

- `adb shell content call` 无法写入带冒号的多行 LRC（binding 解析按 `:` 切分），
  测试注入用应用内按钮，或只写 `lyricLine`（覆盖层渲染它）。
- 远程模拟点击需在开发者选项开启「USB 调试（安全设置）」，否则
  `input tap/keyevent` 报 `SecurityException: INJECT_EVENTS`。
- 熄屏后 AOD 进程才开始轮询 Provider；测试注入后要熄屏等 2-3 秒再看。

### 组件锚点定位（息屏歌词 8.0.0+）

歌词可以用息屏组件的**原始位置信息**定位，替代盲目百分比偏移：

```
链路：Hook 枚举命名可见组件（挂载 5s 后，跳过歌词自身，含 bounds）
      → Provider TYPE_COMPONENTS（componentsList，"id|class|bounds" 行）
      → App 设置页「定位模式」卡片：跟随组件定位开关 + 锚点下拉（中文名·id bounds）
        + 上方/下方 + 间距(px)
      → LyricStyle.anchorId/anchorPos/anchorGap 经 Provider 下发
      → Hook applyOffset 锚点模式：DFS 找锚点 → 祖先链累加 top+translationY
        换算到 clock_container 坐标系 → 下方=anchorBottom+gap / 上方=anchorTop-gap-歌词高
      → kicker 每秒重算（0.5px 阈值去抖），防烧屏漂移天然跟随
找不到锚点自动回退百分比偏移。
```

真机验证（HyperOS 4.0）：时钟行下方 ✓、时钟行上方 ✓、整个时钟区（gradient_layout）
下方 +24px（推荐默认，与农历/电量/通知不重叠）✓。

调试提示：手机锁屏 PIN 挡住 UI 测试时，可用 `adb shell content call
--uri content://cn.aodlyric.xiaowine.state --method update
--extra type:s:style --extra styleAnchorId:s:<id> --extra styleAnchorPos:s:1
--extra styleAnchorGap:s:24` 直写样式（绑定格式 `key:s:value`，值不能含冒号）。

### 指纹崩溃守卫（8.0.1）

播放中歌词每 4s 发一次 `com.android.systemui.doze.pulse` 刷新面板，pulse 完成
后 MIUI 的 `DozeMachine` 会停在 `DOZE_PULSE_DONE`（手动广播的完成回执链不完整，
机器不会自动回 `DOZE`）。此时按指纹（硬件 HAL → `MiuiGxzwManager.onFpsPointerDown`
→ `fireFingerprintPressed`）请求 `DOZE_PULSING`，`validateTransition` 抛
`Illegal Transition: DOZE_PULSE_DONE -> DOZE_PULSING` 杀死 SystemUI。

修复：hook `DozeMachine.validateTransition`，只吞掉**离开 `DOZE_PULSE_DONE`** 的
`IllegalStateException`（让指纹按压正常进入 PULSING 解锁），其他状态对照常抛错。

**关键坑**：`DozeMachine` 由 AOD 插件类加载器（`/product/priv-app/MIUIAod/MIUIAod.apk`
的 PathClassLoader）加载，`install()` 时用 host ClassLoader `Class.forName` 看不到
→ 第一次修复（LyricBridge 直装）静默失败。最终方案：守卫移入 `ThemeProbeV2` 的
loadClass 监听器（`TARGET_DOZE_MACHINE`，与 DozeServicePluginImpl 同款机制），
类被定义的瞬间从产出的 loader 装 hook。真机验证：按压不再崩溃，日志出现
`suppressed illegal doze transition (...)`。

### 对齐方式 / 插入组件树 / 颜色盘（8.0.2）

- **对齐方式**：新 UI 补上 `align`（居中[与组件一致，默认] / 靠左 / 靠右），
  Hook 侧 `applyStyle` 经 TextView gravity 应用。
- **插入组件树（真文档流）**：定位模式新增开关。开启后歌词不再用 translationY，而是
  `applyTreeInsert` 从锚点向上爬，找到最近的**垂直 LinearLayout 祖先**（流式容器），
  把歌词作为锚点块的**兄弟节点**插到其上/下方——歌词占据自身布局空间，把下方组件
  **按文档流顶开**（间距变成真实的 flow margin，其余 margin 清零，拷贝的 weight 归零）。
  两级策略：① 锚点的垂直 LL 祖先内兄弟插入；② 爬到 AOD root 都没有且锚点本身是垂直
  LL → 插入锚点内部开头/末尾；都失败回退 translationY 锚点模式 → 百分比偏移。
  **GradientLinearLayout 排除**：其 `dispatchDraw` 用 `saveLayer` + `PorterDuff SRC_IN`
  把所有子像素替换成主题渐变色（主题文字就是靠这个上色的 alpha 掩码），歌词插进去
  自定义颜色/描边/emoji 会被压成主题蓝（实测 ✅ emoji 变蓝色方块）——运行时反射检测
  `getGradientOverlayDrawable() != null` 则跳过、爬到其父级插入（仍是真文档流）。
  真机验证（树 dump 实测边界）：纯时钟主题歌词插在时钟块下后 `date_lunar` 从 y=68 顶到
  y=418、`battery` 从 y=135 顶到 y=485、`gradient_layout` 高度 195→513 自动撑开、
  通知图标容器同步下移；实拍歌词本色渲染（鲑红+✅ emoji）、无重影无遮挡。kicker 每秒
  校验槽位（同容器同索引同 margin = no-op）；树模式 `bringToFront` 全部旁路、
  `reinflate` 保留自身 LayoutParams；爬升以 AOD root 为界；探测结果按容器实例缓存。
- **颜色盘 + 预览**：颜色行改为色板圆点（6 预设）+「＋」打开 HSV 颜色盘
  （色相/饱和度/明度滑条 + 实时色块 + hex）；样式卡片顶部新增所见即所得预览
  （深色底按草稿字号/颜色/字体/粗细/描边/阴影/对齐/上一句实时渲染，描边用
  8 向偏移副本模拟）。

### 2026-11-15 修复（真机验证，HyperOS 4.0）

1. **无歌词不再占位**：`applyTreeInsert` 入口加 idle 守卫——`!sActive || 空文本`
   时把歌词从流式容器**拔出**（`lyric pulled out of flow`），空 View 的 24px margin
   不再把下方组件顶开；`attach`/`setStyle`/`scheduleRefront` 的延迟重排也不会再插回。
   真机验证：boot 无歌词期拔出、组件保持原位，音乐恢复后重新入流。
2. **锚点候选过滤**：`collectComponents` 重写——递归携带绝对坐标（absX/absY），
   过滤零尺寸 / GONE / ViewStub / 无名视图；按名字去重之外再按**绝对 bounds 去重**
   （first wins），「名字不同但同一位置」的锚点只剩一个。真机验证：候选从 60+ 条
   降到 14 条有效锚点，`add_icon`/`invert_btn`/`date_dual`/ViewStub/`aod_bg`(869x0)
   等无效项消失，`image_shower` 与 `image_shower_container` 同位只留一个。
3. **歌词与下方组件间距**：主题组件自带大 margin（image→时钟块 87px），歌词入流后
   该 margin 落在歌词下方，视觉上「歌词离时间组件太远」。修复：歌词入流时把**紧邻
   歌词下方组件的 topMargin 收缩为 min(主题原值, anchorGap)**（永不放大），歌词拔出/
   隐藏时恢复主题原值。真机验证：时间块 y 787→724（=歌词底 700+24px gap，与
   anchorGap=24 一致），容器高度 1120→1057，拔出时 `restored next block top margin
   87px`，实拍歌词紧贴时间组件。
4. **双击亮屏失效**：歌词脉冲后 `DozeMachine` 停在 `DOZE_PULSE_DONE`，其
   `transitionPolicy` 对新 `DOZE_REQUEST_PULSE` 检查 `canPulse()`（PULSE_DONE=false）
   → 双击唤醒的脉冲请求被静默丢弃（`Dropping pulse request because current state
   can't pulse`）。新增 hook：仅当请求=`DOZE_REQUEST_PULSE` 且结果与当前 `mState`
   均为 `DOZE_PULSE_DONE` 时放行为 `DOZE_REQUEST_PULSE`（无前置校验，之后正常
   PULSING→PULSE_DONE）。Hook 已确认装上（`hooked DozeMachine.transitionPolicy
   (re-pulse from PULSE_DONE)`），手势实测待用户进行（日志 `allowed re-pulse from
   DOZE_PULSE_DONE`）。

### 2026-11-15 第二轮（真机验证）

1. **双击亮屏失效（根因修正）**：MIUI 的息屏双击唤醒路径是
   `DozeHost.onDoubleTap → CommonUtils.requestWakeUp → PowerManager.wakeUp("NODOZE")`，
   与 DozeMachine 无关。歌词更新**确实每 4s 触发一次 pulse**（`setText` →
   `requestPulse` 节流）：pulse 进行中屏幕被短暂点亮，此时双击的 `wakeUp` 是
   空操作，pulse 结束屏幕回落息屏——双击意图被静默吞掉（触摸本身没有被丢弃，
   `DoubleTapHelper` 能看到两次 tap）。修复三层：① hook `transitionPolicy`
   记录 DozeMachine 状态（日志 `doze state -> X`）；② hook
   `CommonUtils.requestWakeUp`，若到达时机器处于 REQUEST_PULSE/PULSING/
   PULSE_DONE（pulse 周期中）则武装重发；③ 机器回到 DOZE/DOZE_AOD 时立即重发
   `wakeUp`（快路径），否则 2.6s 定时兜底。日志标记：`double-tap wake request
   (doze state X)` / `double-tap wake swallowed by in-flight doze pulse;
   retry armed` / `re-issued double-tap wake (...)`。
   注意 `PowerManager.wakeUp` 是隐藏 API（公开 SDK stub 无此方法），走反射
   （优先 2 参带 reason，回退 1 参）。`CommonUtils` 在首次双击时才加载，
   loadClass watcher 会即时装上 hook。
2. **锚点简化为 4 项**：选择器只保留 4 个粗粒度区域——大时钟（`clock`）、
   图片（`image`）、时间日期组件（`timedate`）、通知（`notify`）。Hook 侧
   `collectComponents` 先做原始遍历（保留绝对坐标/可见/零尺寸过滤/同位去重），
   再映射到 4 区域输出（候选缺失的区域自动省略，如大时钟主题只有 clock+notify
   两项）；`resolveAnchor` 统一解析：区域 id 按候选表取主题中第一个存在的视图，
   旧细粒度 id（`image_shower_container`/`battery_step_container`/`aod_icons_container`
   等）经 `legacyToCanonical` 关键字映射到最近区域（旧配置无需迁移，Hook 日志
   `legacy anchor '...' mapped to region '...' via ...`）；保存时 UI 侧同样归一化
   （SettingsPage `canonicalAnchorValue`），下拉选中项按映射高亮。

### 2026-11-15 第三轮（真机验证：重挂载 + 大字号裁切 + UI 归纳）

1. **idle 拔出后歌词永远不重挂载（死锁 bug）**：空闲时歌词会从息屏里拔出
   （`lyric pulled out of flow (no active lyric)`），之后新歌词激活要靠重挂载——
   但 `applyText` / kicker / `setStyle` / `hideOverlay` 都用 `view.post(...)`，
   **脱离树的 View 的 post 进 attach run-queue，在重新挂载前永不执行**，而
   重挂载恰恰就在这些 runnable 里 → 死锁，日志特征
   `overlay alive: attached=false parent=null`。修复：新增 `postOnMain()`
   （主线程判断 + 主 Handler post，不走 View#post），替换全部四处调用。
   验证：熄屏注入歌词 → `lyric inserted into flow LinearLayout at index 2`
   正常出现，不再卡 detached。
2. **最大字号（40sp）内容被裁切（两层原因，两个修复）**：
   - 主题内容列是固定高度，大字号把内容撑出列边界，被父容器
     `clipChildren` 裁掉 → 新增 `relaxClipping(root, tv)`，在 `applyOffset`
     （所有挂载路径必经）沿歌词祖先链到 AOD root 逐层
     `setClipChildren(false)+setClipToPadding(false)`（幂等，安全于每秒 kicker）。
   - 上面只解决父容器裁切；View 自身高度仍被 measure spec 钳制在 1 行
     （`bounds=...x156`，第二行换行内容被自身边界裁掉）→
     `LyricTextView` 重写 `onMeasure`：先按原始 spec 量一次，再用
     UNSPECIFIED 高度量出文本自然高度，取更大者上报——列允许向下溢出到
     AOD 窗口的空区。同时描边 `onDraw` 改为按 `Layout` 逐行绘制（多行时
     每行都有描边，坐标含 padding 手动偏移）。验证：40sp 歌词
     「最大字号裁切验证行」完整两行显示。
3. **App 内容归纳（去 debug 化）**：新建 `StyleEditor.kt`（StyleDraft/预览/
   颜色盘/滑条/锚点编辑，删除测试注入按钮），全部歌词外观编辑移入歌词页
   （HomePage = 正在播放 + 运行状态 + 歌词外观）；`SettingsPage` 只保留
   App 自身设置（动态取色/配色模式/主题色/调色板/超椭圆/毛玻璃）；
   字符串补充 `home_section_style`，`settings_subtitle` 改为「App 界面主题」。

### 2026-11-15 第四轮（真机验证：图片下方裁切 + 锚点重枚举 + 双击真正根因）

1. **歌词插到图片组件下方时，时间日期/电量/通知被裁（测量挤压，不是裁切）**：
   - 第一层：`relaxClipping` 只覆盖歌词的祖先链，被推挤的是**兄弟子树**
     （如 `clockGradientLinearLayout` 装着时间日期+电量）→ 先把插入容器的整
     个子树 relax，再扩到「从 flow 一路向上到窗口根的整棵 AOD 树」递归
     `setClipChildren(false)`。
   - 第二层（真因）：树 dump 显示主题内容列固定 1135px，而
     `ImageSelectorShowerView` 是**预算式自定义容器**——按「剩余空间」依次分配
     给末尾子级。歌词占位后电量 60→28px、通知 138→0px 被按差额挤压
     （截图里电量被拦腰截断就是这个 28px）。整树 relax 当然无效：不是裁切。
   - 修复：`expandFlowSpace()` 沿 flow → 根（排除窗口根）扩大容器高度——
     固定 px 容器 +delta，`MATCH_PARENT` 容器 pin 到 `measuredHeight + delta`
     （日志 `expanded lyric budget by 350px: image_selector_shower(match->1485)
     aod_mode_layout_horizontal(match->2958)`），原值记入 `sExpandedHeights`，
     歌词拔出时 `restoreExpandedHeights()` 还原主题原始高度。
   - 两个踩坑：① 插入后立刻取 `tv.getHeight()` 会拿到换行前的过时值（156 而
     非 326），改为 `postOnMainDelayed(..., 400ms)`；② 歌词后续换行变高需补预算
     → kicker 每秒 `maybeExpandBudget()` 校验 `sExpandedDelta`，不够就再扩。
   - 验证：歌词两行 + 时间日期 + 电量「⚡84%」+ USB + 通知图标全部完整。

2. **换息屏样式后 App 锚点下拉框不更新**：hook 侧用 `sDumpedTree`
   （进程级一次性守卫）上报组件列表，`com.miui.aod` 进程存活期间换样式永不重报
   （用户看到的「过一会儿自己好了」其实是进程被杀重启后重新枚举）→ 改为**每次
   息屏挂载都枚举上报**（tree dump 仍保持一次性）；App 侧补
   `MainActivity.onResume → refreshState()`，因为 MIUI 冻结后台 App 期间
   ContentObserver 回调会丢。验证：日志两次挂载各上报一次
   （`anchor regions reported: [...]`）。

3. **双击亮屏不可靠——真正根因：MIUI 的门，不是触摸通路**：注册指纹时概率明显
   变大，曾误判为「触摸送达受指纹影响」。实测日志（按 hook 进程 pid 过滤）：
   - 手指触摸**确实到达** AOD 窗口：`DozeHost: onTouch:0/1/2` 大量出现；
   - 但 `CommonUtils.requestWakeUp` **一次都没被调用**（该类直到首次双击才加载，
     日志里连 `CommonUtils loaded by` 都没有）；
   - 全系统 `NODOZE` 唤醒原因为**零条**。
   对照反编译，`DozeHost.onTouch` 的双击识别有三个前置条件：

   ```java
   if (mSupportGestureWakeup && SettingsHolder.mOpenDoubleTapGoToSleep
           && AODSettings.needKeepScreenOnAtFirst()) {
       mDoubleTapHelper.onTouchEvent(ev);   // ← 从未执行
   }
   ```

   实测三个值：`support_gesture_wakeup=true`（`/product/etc/device_features/
   popslide.xml`）✔、`settings get system gesture_wakeup` = `1` ✔、
   `needKeepScreenOnAtFirst()` = **false** ✘——它跟随息屏样式，只有超级壁纸类
   返回 true，自定义图片样式恒为 false，于是门永久关闭。
   指纹相关性因此有了正解：注册指纹后 MIUI 为屏下 FOD 保留常驻息屏触摸监听，
   双击还有一条独立唤醒通道；未注册时只剩这扇关着的门，所以「小概率偶尔有用」
   （抢在歌词 pulse 点亮屏幕的瞬间，wakeUp 才有效）。
   - 修复：**模块自己识别双击**，复用已验证可用的 AOD 容器
     `dispatchTouchEvent` hook（`installTouchWatch`），在其中做「两次短按抬手、
     间隔 <300ms、位移小」判定，命中后调 `ThemeProbeV2.doubleTapWake()`：
     立即 `PowerManager.wakeUp(uptime, "com.android.systemui:NODOZE")`，
     pulse 周期内额外 arm 一次重试作兜底。完全绕开 MIUI 的三个门。
   - 延迟踩坑：最初为「pulse 期间 wakeUp 无效」而**等待** pulse 结束再发，
     结果歌词每 4s 一次 pulse，几乎永远在 PULSING，实测延迟 9 秒（读起来像
     「没反应」）。改为**先立即发**、再用重试兜底——实测双击到亮屏 3–4ms
     （`module double-tap recognised` → `woke screen (module double-tap)`）。
   - 收尾：重试定时器在屏幕上已亮起时直接丢弃（`already interactive`），
     避免「双击亮屏后立刻按电源键熄屏」被 2.6s 后的兜底重试误唤醒。
   - 后续修正（用户实测反馈）：模块只替代**样式相关**的第三个条件，**必须仍尊重
     用户的系统设置**。MIUI 的双击开关就是
     `Settings.System["gesture_wakeup"]`（`SettingsHolder` 中 `mOpenDoubleTapGoToSleep`
     即由它读取）。首版漏掉了这一点，用户关闭系统「双击亮屏」后模块仍会亮屏。
     现在每次双击命中后先读该 key，为 0 则直接忽略并记日志
     `module double-tap ignored: gesture_wakeup is off`（读取失败同样按关闭处理，
     绝不违背用户设置）；安装触摸监听时也会打印当前值
     `double-tap wake (gesture_wakeup)=on/off`，方便核对。

### 2026-11-15 第五轮（功能清理 + 按钮反馈 + 真机验证）
   - 删除 `.tmp/`（日志与反编译产物，2.6 GB）；
   - 删除 `miuix/` + `miuix.zip`（140 MB，实测构建脚本无任何引用）；
   - 删除 `--only=cn/`（反编译误生成的目录）、`.cache/`（空缓存）、`probe/`（早期探针工程）；
   - 删除 `module/build/` 全部中间产物，**只保留 `debug.keystore`**（apksigner 签名必需）；
   - 删除 `AodLyric-miuix.apk(+idsig)`、`cn.aodlyric.xiaowine-main.zip`、
     `SuperLyric-master.zip`、`work/target.apk`（与根目录宿主 APK 哈希相同）、
     `work/aodlyric.apk`、`work/SuperLyric-3.3.apk`；
   - `work/` 下 174 个一次性排查脚本删除，**只留 5 个构建依赖**：
     `collect_aar_res2.py`、`aarzip_compile.py`、`gen_extra_r.py`、
     `fetch_toolchain24.py`、`upgrade_m094.py`；
   - 删除前把 `work/*.py` 与根目录 `.md` 打包为 `_backup_2026-11-15.zip`（181 文件 / 0.1 MB）。
   - 验证：清空 `module/build` 后完整构建一次成功，装机后哈希与本地一致。
2. **App 代码优化（第一批）**：
   - 硬编码中文全部资源化：配色模式 6 项、主题色 7 项、字体族 4 项、
     取色器样例 `Aa 歌词`、卡片空态 `读取中…` / `暂无投喂记录`、
     恢复默认提示 → `strings.xml`；
   - `CardsData` 的默认文案改为空串，由 Compose 层用资源兜底
     （`ifEmpty { stringResource(...) }`），文案只在一处维护；
   - `colorModeLabel()` 不再在非 Composable 的 `map` 里调用 `stringResource`，
     改为组合期一次性解析成 `Map<ColorSchemeMode, String>` 传入。
   - 验证：`SuperLyric registered: OK`、无 `FATAL EXCEPTION` /
     `Resources.NotFoundException`，息屏歌词渲染正常。



1. **「无效果/冗余」功能核查结论**（逐项实测，不是看代码猜）：
   - **描边 → 保留并修复**：Hook 侧本来就走 `LyricTextView` 双画笔描边，但描边色
     固定黑色、且没有入口，黑底 AOD 上等于没有效果。现在描边开启时出现
     **描边颜色**色板（预置色 + HSV 取色器），`styleStrokeColor` 随样式下发。
     真机验证：白字 + 6px 蓝色描边截图能看到清晰蓝边。
   - **阴影 → 保留并修复**：原来固定 `0xB4000000`（近黑）阴影，在黑色息屏上不可见，
     预览也用同一个颜色，所以滑条看起来完全没用。现在阴影色由文字色派生
     （约 45% 透明度光晕），Hook 与预览共用同一规则；`styleShadowColor`
     这个无入口的配置项整条删除。
   - **垂直位置 → 保留但收窄入口**：`offsetRatio` 只在百分比模式下生效
     （锚点模式/插入组件树时位置由锚点决定）。旧界面在锚点模式下仍显示该滑条，
     拖动无任何变化；现在**锚点模式开启时隐藏该滑条**，日志/界面都能自证。
   - **显示上一句 → 删除**：`showPrev` 只存在于样式、Provider 键、预览和开关里，
     Hook 侧从未读过它（grep 全仓无消费方），属于纯装饰开关。已删除
     `styleShowPrev` 键、字段、UI 开关与预览占位；顺带清掉 Provider 里已无读者的
     `lyricPrev/lyricNext` 上报链。
   - **调色板风格 / 配色规范 → 保留**：不是空壳。反编译核对 Miuix 0.9.4 的
     `ThemeController.currentColors` 在 Monet 分支确实读取 `colorSpec` /
     `paletteStyle`（字节码偏移 359/467 处调用 getter），二者只影响动态取色，
     所以 UI 只在「动态取色」开启时显示，行为正确。
   - **超椭圆圆角 → 保留**：`MiuixAppTheme` 通过
     `CompositionLocalProvider(LocalSquircleEnabled provides ...)` 下发；
     Miuix 的 `Card` 等组件走 `squircleSurface`，即真实生效（非仅存配置）。

2. **按钮反馈与提示**：
   - 字体等枚举选项原来用普通 `TextButton`，点完只改了草稿、外观无变化 →
     改为带选中态的 chip（选中：主色底 + 白字；未选中：浅底 + 描边），
     字体选项现在点哪个哪个高亮。恢复默认后选中态也会回到「系统」。
   - 「保存并应用」的提示原来永久留在界面上，且提示文字出现会把按钮行挤走，
     第二次点击落在旧坐标上（“点不中”）→ 提示改为 **2 秒自动消失**，并放进
     **固定高度占位槽**，按钮位置全程不动；连续点击已实测（19:48:31 与
     19:49:17 两次 `style saved`，按钮位置一致）。
   - 「恢复默认」也会给出一次性提示「已恢复默认，点保存并应用生效」。

3. **删除无效功能**：
   - **正在播放**：删除首页卡片、`LyricData` 流与 Java 兜底界面里的对应视图。
     **保留** App 进程的 SuperLyric 注册——它是唯一拿到完整 LRC 的通道
     （本进程 `superlyricapi_version_code=40`），Provider 投喂依赖它，不能一起删。
   - **主题兼容性**：长期无效（主题里根本没有 `MusicControl` 元素，自绘才是主路径），
     整链删除：`MusicControlScreenElement` 构造 hook、`report()/reportProbe`、
     `TYPE_PROBE` 与 `PROBE_*` 状态键、Provider 分支、首页卡片与判定文案。
     **保留** `ScreenElementRoot.addElement` hook——它给 `setMamlRoot` 提供实例，
     `driveFramerate()`（`requestUpdate` + `updateDateTimeVariable`）靠它刷新息屏，
     不是探测功能。
   - 同时删除仅服务于旧 Java 兜底界面的 `Ui.java` / `StyleEditor.java`；Compose
     挂载失败时只显示一段说明文字，不再保留第二套功能入口。

4. 真机验证（build43/44）：
   - 首页只剩「运行状态（模块 + 投喂记录）」与「歌词外观」；
   - 息屏注入 `刷新验证AAA` → 4s 后截图 → 再注入 `刷新验证BBB` → 截图，
     文字随之变化，证明刷新链路在重构后仍正常；
   - 描边蓝色可见、阴影光晕可见（各一张截图）。

### 2026-11-15 第六轮（开发目录清理 + App 代码优化）

1. **开发目录清理（保守方案，回收约 2.8 GB）**：
   - 删除 `.tmp/`（日志、反编译产物、截图，2.6 GB）；
   - 删除 `miuix/` + `miuix.zip`（140 MB，构建脚本实测无任何引用）；
   - 删除 `--only=cn/`（反编译误生成目录）、`.cache/`（空缓存）、`probe/`（早期探针工程）；
   - 删除 `module/build/` 全部中间产物，**只保留 `debug.keystore`**（apksigner 必需）；
   - 删除 `AodLyric-miuix.apk(+idsig)`、`cn.aodlyric.xiaowine-main.zip`、
     `SuperLyric-master.zip`、`work/target.apk`（与根目录宿主 APK 哈希相同）、
     `work/aodlyric.apk`、`work/SuperLyric-3.3.apk`；
   - `work/` 下 174 个一次性排查脚本删除，**只留 5 个构建依赖**：
     `collect_aar_res2.py`、`aarzip_compile.py`、`gen_extra_r.py`、
     `fetch_toolchain24.py`、`upgrade_m094.py`；`work/apk_src/`（宿主反编译源码）保留；
   - 删除前把 `work/*.py` 与根目录 `.md` 打包为 `_backup_2026-11-15.zip`
     （181 文件 / 0.1 MB）；
   - 验证：清空 `module/build` 后完整构建一次成功，装机后设备哈希与本地一致。

2. **App 代码优化（第一批）**：
   - 硬编码中文全部资源化：配色模式 6 项、主题色 7 项、字体族 4 项、
     取色器样例 `Aa 歌词`、卡片空态 `读取中…` / `暂无投喂记录`、恢复默认提示；
   - `CardsData` 默认文案改为空串，由 Compose 层用资源兜底
     （`ifEmpty { stringResource(...) }`），文案只在一处维护；
   - `colorModeLabel()` 不再在非 Composable 的 `map` 里调用 `stringResource`，
     改为组合期一次性解析为 `Map<ColorSchemeMode, String>` 传入；
   - 验证：`SuperLyric registered: OK`，无 `FATAL EXCEPTION` /
     `Resources.NotFoundException`，息屏歌词渲染正常。

### 2026-11-16 第七轮（歌词翻译 + 全屏 AOD 适配）

1. **歌词翻译显示（可开关）**：
   - `StateKeys` 新增 `styleShowTranslation`（样式开关）与
     `lyricTranslation`（实时译文，随 `lyricLine` 一起下发）；
   - 两条歌词通道都带译文：legacy 3.x 的 `SuperLyricLine.getTranslation()`
     与 4.x 的 `Line.getTranslation()`；发行方（如 Salt Player）不发译文时
     `translation=null`，只显示原文——**开关打开永远不会隐藏任何内容**；
   - 渲染：单 `TextView` 内用换行分隔，译文用 `SpannableString`
     单独设字号（原文 × 0.62）与透明度（约 70%），样式、描边、阴影不受影响；
   - App 样式编辑器新增「显示翻译」开关（粗体行下方），样式草稿与
     `LyricStyle` 双向映射同步更新；风格戳（style stamp）纳入新键，
     修改开关后无需重启即可热生效；
   - `content call` 注入双行文本真机验证：普通 AOD 与全屏 AOD 均正确显示
     原文 + 小号淡色译文。

2. **全屏 AOD（系统设置「息屏显示-与锁屏界面一致」）适配**：
   - 根因排查：全屏 AOD 模式下屏幕内容由**锁屏（keyguard）在
     `NotificationShade` 窗口内绘制**（`KeyguardClockContainer#miui_keyguard_clock_container`
     的大时钟），MIUIAod 的内容树被宿主整体淡出（`AODView
     aod_mode_layout_horizontal` alpha=0），挂在锚点流里的歌词随之不可见
     （视图活着、日志正常、就是不显示）；
   - **shadow 模式**：连续 2 个 1s kicker tick 检测到歌词祖先链 alpha<0.05
     即接通——把歌词从隐藏树提升为 keyguard `AodView#aod_root_view`
     （alpha=1，可见）的直接子级，垂直位置锚定**可见的**锁屏大时钟
     （`time_accessibility_overlay` 的屏幕坐标，即 HyperOS 时钟的真实区域），
     上/下与间距沿用样式配置；找不到可见时钟时按实测比例 0.663 屏高兜底；
   - shadow 模式下跳过锚点流插入 / 预算扩高（目标树不可见，操作无意义），
     `scheduleReparent` 不再把歌词放回隐藏树，`detach` 时重置状态；
   - 真机验证：全屏 AOD 下歌词 + 译文显示在时钟下方，位置、字号正确；
     切回普通 AOD（`full_screen_aod_on=0`）回归正常（alpha=1、锚点流内、
     shadow 未触发），测试后设置已恢复为 1。

> **为什么 shadow 模式不会出现双时钟**：keyguard 的时钟与通知是
> `aod_root_view` 的**兄弟节点**，shadow 模式只把歌词挂进
> `aod_root_view` 内部——被淡出的 AOD 内容不会因此重新出现，
> 不会与锁屏自身内容叠加。

### 2026-11-16 第八轮（垂直位置修复 + 自定义字体）

1. **「垂直位置」滑条真实生效（原功能形同虚设的两处根因）**：
   - **根因 A（shadow 模式，全屏 AOD）**：第七轮的 shadow 模式把歌词固定
     锚在可见时钟下方，`offsetRatio` 完全没接入。修复：无锚点时 ratio 映射为
     「可见时钟顶部 → 屏幕底部」区间
     （`target = clockTop + ratio * (screenH - clockTop)`），找不到可见时钟时
     退化为 ratio × 屏高；锚点模式仍走「可见时钟 + 上/下 + 间距」。
   - **根因 B（普通模式）**：关闭锚点切回百分比模式后，歌词视图**仍留在
     锚点时代插入的布局流槽位里**（如 `ImageSelectorShowerView` 内），
     滑条只在这个错误基线上挪动。修复：ratio 模式下若父容器是垂直流
     （且不是时钟容器本身，避免每 tick 反复重挂），先恢复被放宽的
     margin、把视图从流中移出，重新挂回 `clock_container`/root 作为
     浮动层（日志 `lyric released from flow (ratio mode)`），再按
     `base + ratio × 容器高` 计算 translationY。
   - 真机验证：普通 AOD 关锚点后，ratio 0.1 → y=600、0.5 → y=1006、
     0.85 → y=1245，与 `base + ratio×h`（h=1016）逐位吻合；截图确认
     0.5 时歌词在中部、0.85 时在时钟块下方。

2. **自定义字体（新功能，端到端）**：
   - **App 端导入**：样式编辑器字体区新增第 5 个「自定义」chip；选中后
     出现导入行（当前文件名 + 「导入字体」按钮）。SAF `OpenDocument`
     （MIME 含 `font/*`、`application/x-font-ttf`、`application/x-font-otf`、
     `application/octet-stream`）选中后拷贝为固定槽位
     `filesDir/fonts/custom.ttf`（覆盖式），**边拷边校验魔数**
     （TrueType `0x00010000`/`true`/`ttcf`、OpenType `OTTO`），拒绝非字体
     文件；原始文件名存 SharedPreferences（`style`/`custom_font_label`）
     仅用于展示。
   - **Hook 端读取**：App 私有目录 systemui 读不到，经 exported Provider
     新增 `openFile` 服务 `content://cn.qxfly.aod.state/font/custom.ttf`
     （路径段拍平防穿越，只服务 fonts 目录）。API 36 公开 API 没有
     `createFromFile(FileDescriptor)`，因此把字体流拷入 systemui 自己的
     `cacheDir/aod_custom_font.ttf`（Typeface 会 mmap，文件须常驻）再
     `createFromFile(String)`，进程级缓存。
   - **重导入热生效**：`styleTypeface` 保持 `custom` 不变时 style stamp
     不会变化，Hook 将永远用缓存的旧字体。新增 `styleFontStamp`
     （导入文件 mtime+size，App 保存样式时写入，仅作为 stamp 成分），
     stamp 变化 → `setStyle` → `CustomFontLoader.invalidate()` → 重拷重载。
   - **预览区限制**：该 Compose 版本无 `Font(Typeface)` 公开包装（javap
     验证 fat jar 的 `FontKt` 只有资源 ID 变体），预览区自定义字体暂以
     系统默认渲染，真实字体在 AOD 上生效（strings 已注明）。
   - 真机验证：`DroidSansMono.ttf` 走通全链路（SAF 导入 →
     `styleTypeface=custom` → `custom font loaded (aod_custom_font.ttf)` →
     `lyric style applied ... typeface=custom`）；改用 `NotoSerifCJK`
     截图确认中文明显变为宋体渲染；重新导入后无需重启即换字体。
     验证后样式已恢复（系统字体、锚点 image/下方、字号 26.059072）。

> **注意**：设备里当前留有一次测试导入的 `TestSerif.ttc`（存在应用私有
> 目录，选择「自定义」时会显示该名字）。暂无「清除字体」入口，后续可加；
> 重新导入任意字体即可覆盖。

### 2026-11-17 第九轮（歌词背景 + 设置清理 + 界面重构）

1. **歌词背景（新功能，端到端真机验证）**：
   - **样式链路**：`StateKeys` 新增 `styleBgEnabled`/`styleBgColor`/`styleBgAlpha`
     （并入 `STYLE_KEYS`，Provider 按组拷贝）；`LyricStyle` 增加
     `bgEnabled`/`bgColor`(默认黑)/`bgAlpha`(默认 0.45) 字段与 bundle
     序列化；`ProviderLrcSource` 的 style stamp 追加三键，改动即触发
     `setStyle` 热生效（无需重启）。
   - **Hook 端绘制**（`LyricTextView.onDraw`）：开启时给 TextView 设
     额外 padding（水平 20dp / 垂直 12dp = 色块外边距，保证色块完整在
     视图内），按**每个换行行**的实际文本边界 union 画圆角 pill
     （`drawRoundRect`，圆角半径 min(24dp, 行高/2)），填充色 = bgColor ×
     alpha；关闭时 padding 归零。`AodOverlayController.applyStyle` 经
     `setLyricBackground(enabled,color,alpha)` 接入。
   - **UI**：样式编辑器「阴影」下方新增「背景」行（开关 + 摘要），
     开启后显示背景颜色色板（复用 `ColorSwatches` + 自定义
     `ColorPickerDialog`）与「背景不透明度」滑条（0–100%）；预览区
     文字包上同款圆角色块（Compose 侧 `background + clip + padding`）。
   - 真机验证：AOD 上黑色 44% pill 贴合原文与译文行；透明度拉到 88%
     后色块明显加深；换白色背景确认颜色链路生效；最终样式已恢复为
     黑色 44%。

2. **设置页删除三个无效项**：删掉「调色板风格」「配色规范」下拉与
   「超椭圆圆角」开关（SettingsPage + `AppSettingsState` 三字段 +
   SharedPreferences 三键 + `AppTheme` 的 `ThemeController` 参数与
   `LocalSquircleEnabled` 注入 + strings），保留动态取色/配色模式/
   主题色/毛玻璃。

3. **App 界面按参考图重构**（`参考/主页.jpg`、`参考/关于页.jpg`）：
   - **主页**：大标题下「运行状态」改为两列卡——左侧彩色 Hook 状态
     大卡（绿「Hook 已连接」/红「Hook 未连接」/灰读取中）+ 右侧两张
     数字统计卡（投喂行号 / LRC 长度）；Hook 未注入时显示红色警示
     横幅（原因与处理提示）；新增「信息」卡（系统版本 / 应用版本 /
     Xposed 框架 / 设备型号，粗体键 + 灰值）；歌词外观（StyleEditor）
     保留在下方。
   - **数据支撑**：`CardsData` 扩展结构化字段（hooksInstalled、
     moduleApi、framework、track、lyricLine、lyricIndex、lyricChars），
     `MainActivity.refreshStateInner` 直接发布完整数据类，Compose 不
     再解析展示文案；快照读取失败时旧字段透传不清空。
   - **关于页**：放弃 MiuixPage 骨架，改为自定义页面——柔和粉紫
     （深色主题深灰紫）渐变背景 + 返回箭头 + 居中 App 图标（圆角底
     `ic_media_play`）/ 应用名（主题色渐变字）/ 版本，下方灰色小节
     标签（应用 / 使用步骤 / 技术栈）+ 白色圆角行卡。
   - 真机验证：主页状态卡/统计卡/信息卡与参考图布局一致；设置页
     仅剩四项；关于页渐变 + 居中头部 + 行卡与参考图风格一致。

4. **构建环境备注**：本轮多次出现「长驻构建进程被系统杀掉」
   （opencode shell 守护与计划任务方式均中途死亡），改为按构建脚本
   步骤拆成短命令逐个执行（aapt2 compile/link → javac → kotlinc →
   javac(MainActivity) → d8 → stage/zipalign/sign），全部通过，
   `qxfly-aod-1.0.apk` 正常产出并安装；重启后 Hook 生效。

### 2026-11-17 第十轮（歌词总开关 + 息屏歌词残影修复）

1. **歌词总开关（新功能，端到端真机验证）**：
   - **状态链路**：`StateKeys` 新增 `MODULE_ENABLED`（module 区，
     Boolean）；`AodStateProvider` 的 TYPE_MODULE 分支仅当写入方带了
     `moduleEnabled` 才覆盖（hook 自身的 api/framework 上报不会冲掉
     用户选择；api/framework 同样改 `copyIfPresent`，总开关写入不再
     清空框架信息，该键随重启持久化）。
   - **UI**：主页顶部新增「息屏歌词」总开关卡（SwitchPreference，
     summary「总开关：关闭后息屏不再显示歌词」）；`ModuleData` 增
     `ModuleEnabledSetter` + `CardsData.moduleEnabled`，MainActivity
     直写 Provider（type=module bundle）并回读填充。
   - **Hook 门禁**：轮询每秒检查 `moduleEnabled()`——关 → 强制
     `setText("", null)`（自绘 overlay idle + 一次性 pulse 清原生主题）；
     `currentLrc()` 总开关关 → 注入空 LRC；attach 初始行走
     `displayLine()` 门。
   - 真机验证：播放中 App 内点关，自绘 overlay 与原生主题歌词 3 秒内
     消失（媒体卡片不受影响）；点开立即恢复；关→开状态过重启保留；
     shell 注入开关不再清空 moduleApi/framework。

2. **息屏歌词残影修复（bug，真机验证）**——歌曲停止后 AOD 歌词
   不消失、杀进程/重启也无法去掉：
   - **根因三条**：① Provider 把 LYRIC_LRC/LYRIC_LINE 持久化且
     `loadPersisted` 原样重载 → 重启后残影；② SuperLyric 暂停/杀进程
     不投递 onStop，且 `ProviderLrcSource.refresh` 忽略空串 → 永远
     显示最后一行；③ `setText("")` idle 路径不请求 doze pulse，原生
     主题（照片式息屏的歌词胶囊）不重渲染。
   - **修复**：
     - `loadPersisted()` 剔除所有 `lyric*` 运行态键（样式/状态仍持久化）。
     - `ProviderLrcSource`：空串视为清除（LRC/行/翻译）；新增
       `mLyricAt`/`moduleEnabled()`/`displayLine()`/`displayTranslation()`/
       `isStale()`（STALE_MS=180s watchdog，喂歌即自愈）。
     - `LyricBridge` 轮询：master-switch 门 + 双路 staleness 门
       （provider lyricAt 与本进程 SuperLyric 流 lastEventAt 取最大，
       两路都静默 >3 分钟才判死）；stale/关 → `setText("", null)`。
     - `currentLrc()`：`sForceEmptyLrc` one-shot 空注入（onStop/watchdog
       触发，getString/parseLyric 消费一次）。
     - `SuperLyricSource` 两条 onStop 路径 + `MainActivity` 的
       `onStop`（`AppReporter.publishStop`）都清 Provider 的
       LINE/TRANSLATION/LRC；`ModuleReporter.reportLyricStop`。
     - `AodOverlayController.setText`：active→idle 转换时请求一次
       doze pulse（`sLastText` 去重保证每次转换只 pulse 一次），
       原生主题随之以空 LRC 重渲染、歌词胶囊消失。
   - 真机验证：暂停 → `onStop: STOPPED` 日志、Provider 歌词键全清、
   AOD 自绘 overlay 与原生主题歌词均消失；两次重启后快照无任何
   `lyric*` 键、AOD 无残影。

3. **回归**：歌词背景（用户已换薰衣草紫背景色，主题胶囊正常渲染）、
   翻译显示、锚点定位、主页/设置页/关于页、Hook 状态卡均正常。

### 2026-11-17 第十一轮（歌词背景整体化 + 参考界面框架调研）

1. **参考界面框架调研（结论：Miuix，非 AndroidX/Material）**：
   - 参考图（`参考/主页.jpg`、`参考/关于页.jpg`）对应的 App 为
     **HyperIsland**（包名 `io.github.hyperisland`，HyperOS 灵动岛
     增强，LSPosed-Modules-Repo 条目的上游为 `1812z/HyperIsland`）。
   - 经 GitHub API 查证：仓库 topics 含 **miuix**，源码为 Kotlin +
     Compose，且存在 `MiuixComponents.kt`、`LiquidGlassNavigationBar.kt`
     等 —— 与本项目相同用的是 **Miuix（top.yukonga.miuix.kmp）**，
     不是 AndroidX Material。
   - 注意别混淆：**HyperBridge**（D4vidDf）才是 Material3 写法的
     同类模块；HyperIsland 不是。

2. **歌词背景整体化（UI 改动，真机验证）**：
   - **改动前**：`LyricTextView.onDraw` 按换行后的每一行各画一个圆角
     pill（`drawRoundRect`，半径 min(24dp, 行高/2)），长歌词 + 译文
     时出现一串胶囊。
   - **改动后**：先对 `layout` 所有行取 union 边界（min LineLeft /
     max LineRight / min LineTop / max LineBottom），再于边界外扩
     `mBgPadX/mBgPadY`（20/12dp）画**一个**圆角矩形，半径固定 24dp。
     原文与译文在同一个 TextView（"\n" 连接），一个背景自然包裹
     两者；背景色/透明度/开关（`setLyricBackground`）与 padding
     机制不变。
   - 真机验证：播放 → 息屏，AOD 歌词为一个整体圆角面板包住 6 行
     原文 + 2 行译文（用户薰衣草紫背景色正常）；暂停 → 歌词与背景
     一并消失、无残影（第十轮 onStop 清理链路回归通过）。
   - 构建：仅 Java 变更，增量编译 `LyricTextView.java` → d8 →
     stage/zipalign/sign，install -r + reboot。

### 2026-11-19 目录整理（唯一构建脚本 + 文档/产物归位）

- **构建入口合并**：删除 `module\build.ps1`、`module\build_compose.ps1`、
  `module\build_miuix2.ps1`，根目录只保留 `build.ps1`。参数：`-Install`、
  `-Install -Reboot`、`-Clean`；不带参数只构建。
- **版本号单一来源**：脚本从 `AndroidManifest.xml` 读取 versionName /
  versionCode，打包时自动写进 APK 内的 `module.prop`（不再需要手工同步两处）。
- **产物目录**：`dist\qxfly-aod-<versionName>.apk`；签名改为不生成
  `.idsig`（`--v4-signing-enabled false`）。签名密钥移到
  `module\debug.keystore`，`-Clean` 不再有丢密钥的风险。
- **文档归位**：`docs\` 下集中放 README、BISECT、COMPOSE_SETUP、
  LSPosed-API102 指南、项目分析报告，界面参考图放 `docs\assets\`
  （原 `参考\` 目录已删除）。
- **辅助脚本**：`work\collect_aar_res2.py`、`aarzip_compile.py`、
  `gen_extra_r.py` 不再硬编码工作区路径，改为按脚本自身位置推导。
- 全流程实测：`-Clean` 全量构建成功（约 21s，8.71 MB，2 dex，489 个资源符号），
  签名证书与之前一致（可覆盖安装）。

### 2026-11-19 第十七轮（关于页 Logo 与应用图标统一）

1. **一套图形，两处渲染**：应用图标（`mipmap-anydpi-v26/ic_launcher.xml`
   + `drawable/ic_launcher_background.xml` + `drawable/ic_launcher_foreground.xml`）
   与关于页 `AboutPage.AppMark` 现在共用同一组数值：
   - 底色：靛蓝 → 紫 → 玫红 对角渐变，色标 `#5B4BFF / #7C4DFF / #B65CF5 / #FF6FA5`
     （偏移 0 / 0.34 / 0.68 / 1），左上角一层柔和高光；
   - 前景：三条左对齐、宽度递减的白色圆角横条（歌词行），
     108 视口下 `left 29.4 / 条高 7.6 / tops 35.2·50.2·65.2 / widths 49.5·40.8·32.1`，
     不透明度 1.0 / 0.92 / 0.80。
   - 之前两边数值其实不一致（vector 的条更窄、更靠右），这次按同一组
     坐标重写，桌面图标与关于页肉眼基本重合。
2. **比例微调**：条高 0.065→0.0704、宽度阶梯改为每级递减约 0.08，
   整组在图标内垂直居中；视觉上比原来「窄、挤在左上」更饱满。
3. **`android:roundIcon` 一并指向新图标**，`monochrome` 层复用前景（主题图标可用）。
4. **顺手修掉一个配色错位**：关于页的 `dark` 原来取 `isSystemInDarkTheme()`，
   而页面其余部分跟随 App 自己的配色。App 设浅色 + 系统深色时会渲染成
   「蓝紫渐变底 + 深色版本号」，对比度很差。改为按实际背景亮度判断
   （`MiuixTheme.colorScheme.background.luminance() < 0.5f`，与 `MiuixAppRoot`
   / `HomePage` 一致），此时回到浅色渐变 + 深色文字。
5. 真机（Xiaomi 2509FPN0BC / Android 17 / HyperOS 4.0.5）验证：关于页标题渐变
   `#5B4BFF→#A855F7→#F0629B` 与 Logo 同色系；桌面图标与关于页图形一致；
   系统深色 + App 浅色组合下版本号恢复可读。

### 2026-11-19 第十六轮（多设备兼容性加固）

1. **AOD 目标类匹配放宽（`ThemeProbeV2`）**：
   - 原来按精确类名匹配 `com.miui.aod.doze.DozeServicePluginImpl` /
     `DozeService` / `DozeMachine` / `com.miui.aod.utils.CommonUtils` /
     `com.miui.maml.ScreenElementRoot`，厂商换包名（如
     `com.xiaomi.aod.*`）或改内部路径就直接失效。
   - 改为「AOD 包族（`com.miui.aod.` / `com.xiaomi.aod.`）+ 类名后缀」
     匹配（`.doze.DozeServicePluginImpl` 等），精确类名仍是快路径；
     MAML 根只按 `.maml.ScreenElementRoot` 后缀匹配，避免误挂同名类。
   - `probeAlreadyLoaded` 额外按 `com.xiaomi.aod.*` 预探测一遍——插件
     通常在模块就绪前就已加载，只靠 loadClass 监听会漏。

2. **待发上报重试节流（`ModuleReporter`）**：`flushPendingIfReady()`
   最多 5 秒重试一次；provider 长期不可用时不再每秒刷一条告警日志。

3. **BUILD 标记升到 `7.4.1/47`**，便于确认注入的是哪一版 dex。

4. **对老设备是否兼容**：兼容，且是严格超集——
   - 类匹配：精确命中优先，行为与之前完全一致；
   - 上报：旧设备由 Application 锚点正常清空待发队列，
     `flushPendingIfReady()` 直接空转返回；`push()` 改为只在成功时清队列，
     失败会重试而不是丢弃，属于纯增强；
   - 快速验证：Xiaomi 14（HyperOS 3 / Android 16）重启后主页「已激活」，
     AOD 日志 `found clock_container -> AodContainerView`、
     `lyric parented to clock_container`、`lyric overlay attached to AOD root`。

### 2026-11-19 第十五轮（HyperOS 3 / Android 16 状态上报修复）

1. **现象**：换到 Xiaomi 14（`23127PN0CC`，Android 16 / HyperOS 3
   OS3.0.303.0）后，App 主页一直显示「未激活」，SuperLyric 卡片为
   「—」，Xposed 框架只剩「LSPosed」；但 AOD 里歌词链路其实已经在跑。
2. **排查结论（不是 LSPosed 没激活）**：
   - HyperOS 3 起 AOD 渲染搬进了 **`com.android.systemui`** 进程
     （`MIUIAod.apk` 以插件形式加载，日志里可见
     `DozeServicePluginImpl loaded by PathClassLoader[... MIUIAod.apk]`
     与 `hooked onDreamingStarted(ViewGroup)`），`com.miui.aod` 不再常驻。
     `scope.list` 已同时包含 `com.miui.aod` 与 `com.android.systemui`，
     新装即默认勾选，无需手动加作用域。
   - logcat 里能看到 `SuperLyric registered: OK`、`lyric overlay attached`、
     `report lyric -> provider ok`，说明注入与歌词投喂都正常；
     provider 里也有 `lyricTrack/lyricLine`。
   - 缺的只有 `hooksInstalled` / `moduleApi` / `moduleFramework`：
     这两条上报发生在 `onPackageReady` 早期，此时宿主 Application 尚未
     创建，被放进 `sPendingModule` / `sPendingStatus` 等
     `Instrumentation.callApplicationOnCreate` / `Application.attach`
     的锚点回调来补发；**这两个锚点在 HyperOS 3 的 SystemUI 里没有触发**
     （歌词上报走 `ActivityThread.currentApplication()` 兜底所以正常），
     待发队列于是永远滞留 → UI 判成未激活。
3. **修复（`ModuleReporter` / `LyricBridge`）**：
   - 新增 `flushPendingIfReady()`，由歌词轮询线程每秒调用一次，也插到
     `reportLyric` / `reportLyricStop` / `reportComponents` 里，不再依赖
     Application 锚点。
   - `push()` 改为返回成功与否，`flushPending()` 只在实际推送成功后才
     清空待发队列——失败下轮重试，不再丢状态。
4. **真机验证（重启后）**：provider 出现
   `hooksInstalled=true, moduleApi=102, moduleFramework=LSPosed 2.2.1,
   hookDetail=getString + parseLyric + overlay`；主页恢复「已激活 /
   SuperLyric 已接入 / 开关 开」，信息卡显示 `102 · LSPosed 2.2.1`；
   锚点组件列表也正常枚举（`clockGradientLinearLayout`、`imageSquareFrameLayout`、
   `timedateLinearLayout`、`notifyNotificationContainer`）。

### 2026-11-19 第十四轮（主页/关于页按参考图重构 + 四标签 + 仓库初始化）

1. **主页按 `docs/assets/主页.jpg` 重构（UI，真机验证）**：
   - 顶部：右上角刷新图标（`IconButton` + `MiuixIcons.Refresh`，接
     `ModuleData.refresher`），下方 34sp 粗体大标题「息屏歌词」。
   - 状态区：左侧正方形大卡（已激活绿 `#36D167` / 未激活粉
     `#FF5A52`，右下角 Canvas 绘制的「圈 + 勾」大徽标，内容为状态
     文案 + 版本号）+ 右侧两个等宽窄卡（「SuperLyric 接入」已接入/
     未接入/—、「歌词总开关」开/关）。
   - 版本行文案由「软件版本：%s」改为「版本 %s」——原文案在 14sp
     下超出左卡宽度会折成两行。
   - Hook 未注入（`hooksInstalled == false`）时插入黄色警示横幅
     （浅色 `#FFF3D6` / 深色 `#3A2D12`，随主题切换）。
   - 信息卡：系统版本 / 应用版本 / Xposed 框架 / 设备型号 / 最近投喂
     （第 5 行是本项目追加的调试信息，参考图只有 4 行）。
   - 链接卡：GitHub / 使用教程 / 相关资源，点击分别打开仓库、
     `docs/README.md`、SuperLyric 仓库。

2. **关于页按 `docs/assets/关于页.jpg` 重构（UI，真机验证）**：
   - 顶部渐变改为**静态绘制**（Canvas + `CompositingStrategy.Offscreen`
     + 底部 `BlendMode.DstIn` 渐隐），不做逐帧动画，截图效果一致且
     不额外耗电；滚动超过 389dp 后渐变整体淡出。
   - Hero：88dp 圆角渐变图标（白色圆角框 + 圆点）+ 34sp 渐变字应用名
     + `版本名(versionCode)`；滚动时按进度缩放并淡出。
   - 分组行卡：开发者（Q 头像 → GitHub 主页）、讨论（问题反馈 →
     issues）、模块（歌词外观 → `navigator.selectTab(Route.Style)`；
     检查更新 → releases）、项目（GitHub / 更新日志 / 使用文档 /
     引用与致谢）。

3. **四标签导航（结构变更）**：
   - 底栏由 3 个改为 4 个：主页 / 外观 / 设置 / 关于，图标
     Home / Tune / Settings / Info。
   - 歌词外观从主页拆出为独立「外观」页（`StylePage.kt`：总开关 +
     原有 `StyleEditorCard`），主页只保留概览。
   - `Route` 新增 `Style`，`TabRootRoutes` / `TabRoutes` / `ModuleUi`
     的标签栈与 `MiuixAppRoot` 的 `entry<Route.Style>` 同步补齐。

4. **刷新链路**：`ModuleData` 新增 `refresher: Runnable?` 与
   `refresh()`，`MainActivity` 中 `hub.setRefresher(this::refreshState)`，
   主页右上角按钮直接触发原有 `refreshState()`。

5. **真机验证（Xiaomi 2509FPN0BC / Android 17 / LSPosed 2.2.1）**：
   主页「已激活 · 版本 1.0.261119.001 / SuperLyric 已接入 / 开关 开」，
   信息卡与链接卡排版正常、滚动到底不被底栏遮挡；关于页渐变、图标、
   版本 `1.0.261119.001(261119001)`、四组行卡与滚动淡出均正常；
   外观页、设置页回归正常。构建 8.74 MB / 527 资源符号 / 2 dex，
   `adb install -r` 成功。

6. **仓库初始化**：新增根 `README.md`（简介 / 构建命令 / 文档索引 /
   目录说明）与 `.gitignore`（忽略 `dist/`、`module/build/`、`*.apk`、
   `.deps/`、`.tmp/`、`.tools/` 及 `work/` 内上游参考源码）；按
   `github.png` 步骤 `git init` → `commit` → `branch -M main` →
   `remote add origin https://github.com/qxFly/qxfly-aod.git` →
   `push -u origin main`，首提交 `662117f`（60 个文件）。

### 2026-11-18 第十三轮（滑条数值可输入 + 首页副标题删除 + SuperLyric 接入状态）

1. **滑条数值手动输入 + 恢复默认（UI，真机验证）**：
   - 私有 `SliderRow` 扩展：`defaultValue` + `toInput`/`fromInput`
     映射；右上角数值改成可点胶囊，点击弹出 `NumberInputDialog`
     （`BasicTextField` + `Decimal` 键盘 + 自动聚焦）。
   - 弹窗内容：标题、`范围 a ~ b · 默认 c`、输入框、
     「恢复默认 / 取消 / 确定」。非法输入显示红字「请输入有效数字」
     且不关窗；超范围用 `coerceIn` 夹到边界（输入 99 → 40）。
   - 覆盖全部 8 个滑条：字号（默认 20）、描边（0）、垂直位置（0.42）、
     锚点间距（12）、背景不透明度（0.45）、调色面板的色相/饱和度/明度
     （默认取打开颜色时的 HSV）。
   - 百分比滑条（背景不透明度）输入框用 0–100，`fromInput` 去 `%`
     后 ÷100，范围提示也走 `toInput`，所以显示 `0 ~ 100` 而非 `0 ~ 1`。
   - 顺手修正调色面板数值一直显示字面量 `%d`/`%.0f` 的老 bug
     （`format = { "%d" }` 忽略了 lambda 参数）。
   - 真机验证：字号 25.35 → 点数值 → 输入 99 → 确定 → 夹到 40、滑条
     与预览同步；清空后确定 → 红字提示且不关窗；恢复默认 → 20；
     背景不透明度 100 → 输入 70 → 70%；恢复默认 → 45%。

2. **删除首页大标题下的介绍小字**：`home_subtitle` 字符串与
   `HomePage` 的 `subtitle` 传参一并删除（副标题行不再占位）。

3. **运行状态新增 SuperLyric 接入状态（UI + Java）**：
   - `MainActivity` 在 `registerLyricPublisher` 里记录
     `mSuperLyricOk`/`mSuperLyricDetail`（注册成功后取
     `SuperLyric.serverApiVersion()`：>0 显示 `API n`，否则
     `旧版协议`；异常时记录「未安装或未启用」或异常类名），注册完
     主动 `refreshState()` 让卡片立即反映结果。
   - `CardsData` 末尾追加 `superLyricOk: Boolean?`（null = 未知）与
     `superLyricDetail`；首页在状态大卡与「最近投喂」之间插入
     `SuperLyricCard`（圆点 + 标题 + 说明 · 详情，绿/红/灰三态）。
   - 新增字符串 `home_sl_*`。真机验证显示「SuperLyric 已接入 ·
     完整 LRC 歌词来源已连接 · 旧版协议」。

### 2026-11-18 第十二轮（运行状态改版 + 移除阴影 + 组件上方抖动修复）

1. **运行状态改版（UI，真机验证）**：
   - 去掉「投喂行号」「LRC 长度」两张数字小卡（对用户无用）；
     `CardsData` 删除 `lyricIndex`/`lyricChars`，新增 `feedTime`，
     `MainActivity.refreshStateInner` 改为填充曲目/歌词/时间。
   - 新布局：整宽**渐变状态大卡**（状态圆点 + Hook 状态 + 已注入说明 +
     `API · 框架`，已连接绿渐变 / 未连接红渐变 / 读取中主题灰）+
     **最近投喂卡**（曲目 + 当前投喂歌词 + 「更新于 …」，空态一行提示）。
     旧 `StatCard` 删除；新增字符串 `home_feed_*`。
   - 同时修正外观预览：未开描边时背景板不显示（预览缺 `bgModifier`），
     现在与息屏一致。

2. **移除字体阴影（功能删除）**：
   - UI：外观卡删除「阴影」滑条；`StyleDraft`/`StylePreview` 去掉
     `shadowRadius` 与预览阴影。
   - 数据：`LyricStyle.shadowRadius`、`StateKeys.STYLE_SHADOW_RADIUS`
     （含 `STYLE_KEYS`）删除；`AodOverlayController.applyStyle` 不再
     `setShadowLayer`（改为清空一层，旧配置遗留值被忽略）。
   - 说明：黑底息屏上该光晕几乎不可见，属「没用」的功能，直接去掉。

3. **「组件上方」整屏闪烁/上下抖动修复（bug，真机复现+验证）**：
   - **根因**：`insertIntoFlowAt` 的幂等判断用**绝对下标**，而歌词插入
     到锚点块之前会把锚点块下标 +1，于是每秒 kicker 都判定「不在位」→
     重插（above 插到锚点后 / 下一轮又插回前面），日志可见每秒交替
     `inserted ... index 1 (above)` / `index 0`，并伴随
     `restored expanded container heights` → `expanded lyric budget`
     反复恢复/扩张祖先高度 → 整屏上下抖动、闪烁。组件下方时上方没有
     内容被推移，所以只有「上方」模式可见。
   - **修复**：
     - `insertIntoFlowAt` 改为**相对锚点兄弟节点**判断（above = 歌词紧邻
       锚点块之前；below = 紧随其后），插入前先移除再算下标；
     - tree 模式（插入组件树）下 kicker 不再 `reinflate`（remove+add 会
       每秒重建槽位），也不再 `bringToFront`；
     - `expandFlowSpace` 增加「同视图树 + 同 delta 已扩张」短路，避免
       每次 restore→re-pin 两趟布局。
   - 真机验证：锚点=时间日期组件、放置=组件上方、插入组件树，
     连续截图照片/时钟位置完全一致，logcat 不再每秒重插；
     「组件下方」与暂停清除回归正常。




> **为什么 targetApiVersion 是 101 而不是 102**：`module.prop` 里的
> `targetApiVersion` 是"本模块要求框架至少实现到这个版本"的声明。
> 框架版本低于它时**拒绝加载，且常常无任何日志**。曾设 102，
> 在只支持 101 的 LSPosed 上模块静默不加载——logcat 里连 `module loaded`
> 都没有。对齐 SuperLyric（同为 101）后可加载。

---

## 原理

新版 AOD 已经内置歌词渲染，入口是：

```
MusicController.onMetadataChanged
  -> MusicControlScreenElement.onClientMetadataUpdate
       -> metadata.getString("android.media.metadata.LYRIC")     ← 唯一数据口
       -> MusicLyricParser.parseLyric(lrc)
       -> MAML 变量 @<name>.lyric_current / lyric_before / ...   ← 主题渲染
```

所以本模块只做一件事：**把 SuperLyric 的歌词塞进那个 getString 调用**。

### 两个 Hook

| Hook | 作用 | 为什么必要 |
|---|---|---|
| `MediaMetadata#getString` | LYRIC key 时返回我们拼的 LRC | 主通路 |
| `MusicLyricParser#parseLyric` | 参数为 null/空 时补投 LRC | 竞态保护：`updateLyric(null)` 会调 `resetLyric()` 清空变量，且 `updateLyricVar` 只在进度 tick 里跑。若 metadata 先到而 SuperLyric 还没发布，第一次就空了，之后不再重试。 |

### LRC 拼装硬约束（来自反编译 MusicLyricParser）

- 行分隔符必须是 **`\r\n`**（用 `\n` 整首歌只会解析出一行）
- `[mm:ss.cc]` 两位数格式
- 时间 < 18000000 ms，否则该行被丢弃
- `decorate()` 会对每行执行 `Html.fromHtml(line + "<br/>")`，
  所以 `<` 和 `&` 必须转义（`LrcBuilder.sanitize` 已处理）
- 0 行可解析 → `parseLyric` 返回 null → AOD 清空歌词变量

---

## 构建

本工程**不用 gradle**，手动走 aapt2 / javac / d8 / apksigner。
工具链已落在工作区：

| 工具 | 位置 |
|---|---|
| aapt2 / zipalign / aidl | `.deps/android-sdk/bt/android-37.0/` |
| d8 / apksigner | 同上 `lib/`，用 `java -cp` 调 |
| android.jar (API 36) | `.deps/android-sdk/p36/android-36/android.jar` |
| javac 21 | `.deps/jdk21/jdk-21.0.13+9/bin/javac.exe` |
| SuperLyricApi 4.0 | `.deps/superlyricapi-4.0/classes.jar`（jitpack） |
| libxposed api 102 | `.deps/libxposed/102/classes.jar` |
| **kotlin-stdlib 2.2.10** | `.deps/libs/kotlin-stdlib-2.2.10.jar` |
| androidx annotation 1.11.0 | `.deps/libs/annotation-jvm-1.11.0.jar` |
| **HiddenApiBypass 6.2** | `.deps/libs/hiddenapibypass-6.2-classes.jar`（LSPosed 官方，AAR 需解包 classes.jar） |

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
# 产出：dist\qxfly-aod-<versionName>.apk
```

> **安装期约束（曾导致"安装失败"）**：targetSdk ≥ 30 时，`resources.arsc`
> 必须 **STORED（不压缩）且 4 字节对齐**，否则 Android 11+ 拒装
> （`INSTALL_FAILED_INVALID_APK`）。aapt2 link 不会自动做这件事，
> zipalign 只对齐未压缩条目 —— 所以打包步骤用 `stage_apk.py` 显式以
> STORED 方式写 arsc，再 zipalign、再签名。改动打包流程时
> 务必重跑 `work/install_audit.py` 验收。
>
> 另外两个曾踩过的坑：zipalign 的输入必须是 `staged.apk`（对 `base.apk`
> 对齐会把没有 dex 的空壳签进最终包）；d8 `--min-api 26` 产出 dex 版本
> `038`，属正常。

> 关键坑：`SuperLyricApi 4.0` 是 Java 21 字节码（major 65），`javac 17` 读不了，
> 必须用 JDK 21 编译。工作区路径带空格（`My Project`），脚本里用 8.3 短路径
> （`D:\Code\MYPROJ~1\XIPING~1`）绕开引号问题。
>
> **运行时依赖必须进 dex**（曾导致"本进程注册失败: Failed resolution of:
> Lkotlin/jvm/internal/Intrinsics;" 且 Hook 侧静默失效）：SuperLyricApi 的
> POM 声明 `kotlin-stdlib 2.2.10`（compile）+ `androidx.annotation 1.11.0`
> （runtime）。Kotlin 每个带参数的公开方法都会调
> `Intrinsics.checkNotNullParameter`，stdlib 缺失时 Hook 进程的
> `SuperLyric.receiver()` 类初始化直接抛 NoClassDefFoundError，
> `LyricBridge.install()` 在 register() 处中断、Hook 全部不装。
> 依赖清单以 jitpack 的 POM 为准，不要凭感觉带。
>
> **hidden API 豁免**：`HiddenApi.exempt()` 先走 LSPosed `HiddenApiBypass`
> （直接遍历 ART 成员表，不依赖元数据检查），失败再退回 meta-reflection。
> 注意 `Class.getDeclaredMethod` 经 `Method.invoke` 调用时**只接受两个参数**
> （方法名 + 参数类型数组），receiver 单独传 —— 曾经把类名混进参数表，
> 每台设备都报 `Wrong number of arguments; expected 2, got 3`。

---

## 安装与验证

```bash
adb install AodLyric-1.2.0.apk
# LSPosed 里启用模块，作用域勾选 com.miui.aod
# 重启 com.miui.aod（或重启手机）

adb logcat -s AodLyric
```

## 前三张卡片一直是空的？按这个顺序排查

> **2026-11-10 状态：已解决。** 1.6.0 在用户设备上实测于
> `com.android.systemui`、`com.miui.aod`、`com.miui.aod:keyguardeditor`
> 三个进程全部加载成功，SuperLyric 注册 OK，Provider 通道 OK，
> SystemUI 进程已有 `report lyric -> provider ok`（歌词数据流入渲染进程）。
> 以下排查流程保留供回归。

**卡片一（模块状态）空 = Hook 进程的上报没到**。1.2.0 修掉了两个确定性原因：

1. `onPackageReady` 时宿主 Application 还没创建，最早两条上报拿不到 Context
   被静默丢弃 → 1.2.0 改为挂 `Instrumentation.callApplicationOnCreate` /
   `Application.attach` 捕获宿主 Context，捕获后**补发**挂起的 module/status 上报。
2. 上报失败原本无声无息 → 现在会打
   `report xxx -> provider FAILED: <异常类名>: <消息>`，logcat 直接给出原因。

排查顺序：

```bash
# 1. 确认 Hook 进程到底有没有被加载（这是最根本的一步）
adb shell am force-stop com.miui.aod      # 或重启手机
adb logcat -s AodLyric
# 然后随便开个音乐 App 放歌
# 预期顺序：
#   module loaded: process=com.miui.aod ... api=102
#   package ready: com.miui.aod
#   context anchor: ...
#   report module(flushed) -> provider ok
#   hooked MediaMetadata#getString / MusicLyricParser#parseLyric
#   SuperLyric register result: OK
```

| logcat 现象 | 结论 |
|---|---|
| **完全没有** `AodLyric` 输出 | 模块没被 LSPosed 加载：查 LSPosed 里模块是否启用、作用域是否勾了 `com.miui.aod`、LSPosed 是否支持 libxposed API 102（管理器 → 日志 里会有原因） |
| 有 `package ready` 但后面报 `provider FAILED` | 通道问题：见下面"MIUI 强杀" |
| `report module(flushed) -> provider ok` | 通道通了，卡片一必有数据 |

**MIUI 强杀问题（卡片持续为空的常见原因）**：Hook 进程往 Provider 推数据时，
如果模块 App 进程被 MIUI 强杀了，系统拉不起来 → 所有上报失败。给本 App：

- 自启动权限：允许
- 省电策略：无限制
- 后台最近任务里加锁

**卡片二（主题兼容性）一直在等待是正常的**——探测 Hook 挂在
`MusicControlScreenElement` 的构造函数上，只有**息屏亮屏显示音乐控件**时才会触发。
必须：正在播歌 + 点亮息屏 + 当前息屏样式里含音乐控件。如果卡片四有歌词滚动但
卡片二一直等待，说明你的息屏样式根本没有 MusicControl 元素——那歌词本来也
渲染不出来，只能换带歌词的息屏样式。

### 1.8.0 探测 v2：类加载器问题

1.7.x 的探测 Hook 通过 App 的 `PathClassLoader` 找 `MusicControlScreenElement`
并挂构造函数，但实测在有的设备上**从不触发**：AOD 渲染器把主题引擎从
**插件类加载器**里加载，PathClassLoader 里那份类根本不是渲染用的那份。

1.8.0 改为挂 `ClassLoader#loadClass(String, boolean)` 全进程监视：任何类
加载器加载 `MusicControlScreenElement` 时，就从**那个**加载器把构造函数
挂上。同时挂 `ScreenElementRoot.addElement` 记录主题注册的每一个 MAML
元素——如果点亮息屏后 logcat 里 `MAML element:` 列表里没有 music 相关
名字，就能确定**这个主题根本没有音乐控件**，与探测 Hook 无关。

## 1.7.0 数据流反转（重要架构变更）

实测发现设备端 SuperLyric 是 **3.x 系**（`serverApiVersion=0`，legacy 通道）。
legacy 传输的**全量歌词**只授予 manifest 声明 `superlyricapi_version_code ≥ 35`
的客户端：

- **主模块 App 进程**（声明 40）→ 收到**全量歌词** ✅
- **com.miui.aod / SystemUI 进程**（无此 meta）→ 只收到**当前行**，永远拿不到
  完整 LRC → 无从注入 ❌

所以 1.7.0 反转数据流：

```
App 进程（V4/legacy-full）── 收全量歌词 ──> 写入 Provider
                                                │
SystemUI / aod 进程（Hook）── 每 2s 拉取 Provider ──> 注入 MediaMetadata
```

App 的 UI 进程不再只是"展示"，而是**歌词主源**；Hook 进程的
`currentLrc()` 优先用自己 stream 收到的完整文档，拿不到就回落 Provider。
Provider 快照同时**持久化到文件**——MIUI 杀掉 App 进程后状态不再清零
（1.6.0 的卡片"未安装/失败"假象正是进程重启导致内存快照丢失）。

另注意：LSPosed 会向作用域模块投递 `pkg=android`（framework 资源包）的
回调，包名过滤时需要留意。

## 发布端排查（当前卡点：SuperLyric 不发歌词）

1.9.0 加了**端到端渲染测试**：App 里点「注入测试歌词到息屏」，
一条固定 LRC 写进 Provider，Hook 进程 ≤2s 拉取并注入。

- **息屏出现测试文字** → 链路 + 主题全通。之后没歌词 = SuperLyric 发布端问题。
- **息屏没出现** → Hook 进程没拿到（看 logcat `provider LRC polling`）
  或主题不渲染（看 `MAML element:` / `theme probe:`）。

若测试通过而真歌词不来，按序查发布端：

1. **LSPosed 管理器 → 日志**，播放椒盐音乐时过滤 `SaltProvider` 或
   `superlyric` —— SuperLyric 的歌词提取日志（成功/失败）都在那里。
2. 确认歌曲在椒盐内部**有歌词**（椒盐能显示歌词的歌）。
3. 椒盐版本与 SuperLyric 支持的版本是否匹配（旧 SuperLyric 对新椒盐可能提取失败）。
4. 用另一个支持的 App（如网易云）做对照，隔离是椒盐特有还是通用问题。

## 6.0.0 最终结论：息屏刷新只能靠 doze pulse

真机实测（DEV-2455.1.0.1 / HyperOS）逐一验证了所有 View 层刷新手段，
**只有一条有效**：

| 手段 | 版本 | 结果 |
|---|---|---|
| `invalidate()` + 1s 心跳 | 3.6.0 | ❌ 面板不刷新 |
| `ViewRootImpl.scheduleTraversals()` | 3.7.0 | ❌ 反射成功，无效 |
| MAML `requestFrameRateByCommand(30f)` | 3.8.0 | ❌ 抓到的根不是 AOD 的 |
| 多根捕获 + 视图树 harvest | 3.9.0 | ❌ AodView 里没有 MamlView |
| 持续动画帧（ObjectAnimator） | 4.0.0 | ❌ `pulse=true` 仍冻结 |
| 结构重建 + `Surface.setFrameRate(60)` | 4.1.0 | ❌ 两者都成功，无效 |
| 独立 SurfaceView 层 | 5.0.0 | ❌ `loop started` 仍冻结 |
| MAML `requestUpdate()` + 时钟变量 | 5.1.0 | ❌ `frameDrive=true` 无效 |
| AOD 根视图自身动画 | 5.2.0 | ❌ |
| 小米 touch 特性 mode 19/11 | 5.3.0 | ❌ `keepScreenOn=true` 无效 |
| **`com.android.systemui.doze.pulse` 广播** | **5.4.0** | ✅ **有效** |

### 为什么是 pulse

`DozeTriggers` 注册了这个广播：

```java
if ("com.android.systemui.doze.pulse".equals(intent.getAction())) {
    dozeTriggers.requestPulse(0);        // → DozeMachine.requestPulse
}
```

pulse 会驱动一次 **doze 状态转换**，状态转换才让面板重新合成。这正是用户
观察到「触摸指纹/移动手机时歌词会刷新」的原因——那些动作本身就会引发 pulse。

**关键教训**：MIUI 息屏面板的刷新门控不在 View 层，也不在触控特性服务里，
而在 doze 状态机。任何 View 级别的重绘请求都会被缓存层吃掉。

6.0.0 只保留 pulse（歌词变化时触发，2s 节流），其余全部移除以节省功耗。

## 6.1.0 面板冻结真因：`DOZE_SUSPEND`（推翻「只能靠 pulse」）

6.0.0 的结论只对了一半：pulse 能触发一次重新合成，但**面板大多数时间是
`DOZE_SUSPEND`，此时合成器根本不再送帧**。pulse 广播又被 AOSP 的
`DozeTriggers.requestPulse` 守卫（`mPulsePending` / `mAllowPulseTriggers` /
`canPulse()`）丢掉，于是「几秒后就不动」。

### 取证链

| 观察 | 命令 | 结果 |
|---|---|---|
| 显示状态 | `dumpsys display \| Select-String "Display State="` | 稳定为 `DOZE_SUSPEND`（4） |
| pulse 是否到达状态机 | `logcat \| Select-String DozeTriggers` | 每分钟 `Received pulse intent`，但状态机无 `doze state ->` 迁移 |
| 面板是否出新帧 | `dumpsys gfxinfo com.android.systemui \| Select-String "Total frames rendered"` | 冻结期 10s **0 帧** |
| 窗口缓冲区是否更新 | `screencap` 截图 | **内容一直在变** ← 假象来源 |

`screencap` 读的是 SurfaceFlinger 的缓冲区，所以看起来「歌词在刷新」，
但 `DOZE_SUSPEND` 下缓冲区的新内容永远不送往物理屏。

### 修复：把 `DOZE_SUSPEND` 改写成 `DOZE`

AOSP 自带给「不支持 doze-suspend 的屏幕」用的适配器
`DozeSuspendScreenStatePreventingAdapter`，做法就是把 4 改写成 3。我们照做，
且只在歌词播放期间生效：

```
hook android.service.dreams.DreamService.setDozeScreenState(int)
  ├─ 捕获 doze 服务实例（MiuiDozeService，继承 DreamService，无覆写 → 唯一拦截点）
  ├─ 歌词播放中：state 4(DOZE_SUSPEND) → 3(DOZE)，记录「欠恢复」
  └─ 播放停止：按 DozeMachine.mState 判断是否该恢复 4
```

补充说明：

- hook `MiuiDozeService.onCreate` 提前拿到服务实例，不必等分钟边界。
- hook `DozeMachine.<init>` 捕获状态机，读 `mState` 字段决定是否恢复：
  我们改写后机器认为「已经设过 4」不会再请求，单纯清标记会永久停在 `DOZE`。
- `sSelfApply` 标记区分「我们自己的调用」与「机器的请求」，避免周期性重申
  `DOZE` 时污染「欠恢复」簿记。
- 小米触控特性 `mode 19 (keep-screen-on)` / `mode 11 (aod-animate)`
  实测**不改变电源状态**，仅保留作辅助。

### 真机实测（Xiaomi 14 / Android 16 / HyperOS 3）

| 场景 | Display State | 10 秒帧数 |
|---|---|---|
| 播放 + 息屏 | `DOZE`（跨分钟边界不掉） | **+1150** |
| 暂停 + 息屏 | `DOZE_SUSPEND` | **0** |

日志特征：

```
AodLyric: doze service captured: com.android.keyguard.doze.MiuiDozeService
AodLyric: panel hold: DOZE_SUSPEND -> DOZE
AodLyric: panel state -> DOZE
AodLyric: overlay idle (no lyric)
AodLyric: panel state -> DOZE_SUSPEND
```

**另一个易错点**：真正运行的 doze 服务是 SystemUI 的
`com.android.keyguard.doze.MiuiDozeService`（用 SystemUI 内置 AOSP
`com.android.systemui.doze.DozeMachine`）；AOD 插件里的
`com.miui.aod.doze.DozeMachine` **从未被加载**，hook 它没有任何效果。

## 3.0.0 自绘歌词视图（路线 C，任意息屏样式可用）

探测证实默认/多数息屏样式的 MAML 元素清单里**没有 MusicControl 元素**
（实测元素列表只有 `s, w, noticeSpace, notice_icon0-3`），原生歌词通道
在此类样式上没有消费者。3.0.0 实现路线 C——**自绘歌词视图**：

```
DozeServicePluginImpl#onDreamingStarted(ViewGroup)   ← AOD 启动，拿到根 ViewGroup
  └─ attach(): 添加白字粗体+阴影的 TextView（AOD 层级最顶层）
DozeServicePluginImpl#onDreamingStopped()            ← AOD 结束，自动移除
```

- **锚点**：插件的 doze 生命周期方法，通过 1.8.0 的
  `ClassLoader#loadClass` 全进程监视捕获（插件类加载器无关）
- **数据**：App 进程的 SuperLyric stream → Provider → Hook 进程 1s 轮询
  `LYRIC_LINE`（当前行，权威实时值）→ TextView
- **兼容性**：视图活在 AOD 自己的层级里，随息屏生灭，任意样式可用；
  文本静态更新不破坏 AOD 低功耗渲染

原有 MediaMetadata 注入通道保留（对含 MusicControl 的主题依然生效），
两路并存：主题支持时走原生渲染，不支持时自绘兜底。

## 最终结论与实证（2026-11-12）

探测 v2 在真机上拿到了**决定性证据**：

```
MusicControlScreenElement loaded by PathClassLoader[...MIUIAod.apk...]
element ctor hooked (dynamic loader)
MAML element: s
MAML element: w
MAML element: noticeSpace
MAML element: notice_icon0 ... notice_icon3
```

解读：

1. **类加载器问题已解决**——探测从 MIUIAod 插件加载器正确挂上了构造函数；
2. **主题元素观察器工作正常**——能列出息屏样式注册的每一个 MAML 元素；
3. **当前息屏样式的元素清单里没有 music 控件**——只有时间(s/w)和通知
   (noticeSpace/notice_icon*)元素。MusicControlScreenElement 类虽然被
   插件**加载**了（AOD 插件会注册所有元素类型），但**从未被实例化**——
   因为样式里没有用到它。

**这是架构性边界，不是 bug**：注入链路（Provider → Hook → MediaMetadata →
MAML 变量）全部就绪且 armed，但没有 MusicControl 元素就没有消费者。

### 出路

| 路线 | 说明 |
|---|---|
| **A. 换带音乐控件的息屏主题**（推荐先试） | 主题商店搜"歌词息屏/音乐息屏"类主题。装上后点亮息屏，本模块的探测会立即给出 `theme probe: enableLyric=...` 判定——模块现成可用，零改动 |
| B. 修改主题 MAML | 给已下载主题的 XML 手工加 `<MusicControl name="..." enableLyric="true">` 元素块（需了解 MAML 语法，工作量中等） |
| C. 自绘歌词视图 | 复刻旧模块做法：在 AOD 窗口注入自绘 TextView，任意样式可用、外观可控（工作量大，需逆向 AOD 窗口层级） |

**主题检验方法**（本模块内置）：装任意候选主题 → 点亮息屏 → 看 logcat：
出现 `theme probe: enableLyric=true -> lyrics CAN render` 即完全支持。

模块加载成功后，息屏要出歌词还差两个运行时条件：

1. **播放中 + 点亮息屏**：歌词变量由进度 tick 驱动，只在播放态刷新。
2. **当前息屏样式含 MusicControl 元素**：探测 Hook 触发时会在 logcat 打
   `theme probe: enableLyric=... name=... -> lyrics CAN/WILL NOT render`。
   - `CAN render`：链路全通，若界面仍无歌词，检查该样式的歌词图层是否可见。
   - `WILL NOT render`：该样式没开 `enableLyric`，换带歌词的息屏样式。

设备端 SuperLyric 是 3.x 系（`serverApiVersion=0`，legacy 通道），全量歌词
依赖本模块声明的 `superlyricapi_version_code`（40 ≥ 35），已满足。

## 状态与歌词直接看 App（不用翻日志）

打开模块自己的"App"（AOD Lyric (Dev)），四块信息实时刷新（ContentObserver 驱动）：

| 卡片 | 内容 | 数据来源 |
|---|---|---|
| **模块状态** | 框架 API / Hook 是否装上 / Hook 进程的 SuperLyric 注册结果 | Hook 进程 → ContentProvider |
| **主题兼容性** | `enableLyric` / `name` / `needUpdateLyric` + 结论文字 | 主题探测 Hook → ContentProvider |
| **Hook 投喂** | 最近喂给 AOD 的曲目、行、行号、LRC 长度、时间 | 注入路径 → ContentProvider |
| **实时歌词** | 当前句（大字）+ 上/下一句 + 行号/进度/播放状态 | **本 App 进程自己注册 SuperLyric** |

**前三个卡片反映"Hook 侧真相"**——只有 com.miui.aod 真的跑到 Hook 才会有数据；
第四个卡片反映"SuperLyric 侧真相"——和 AOD 无关，独立验证歌词源是否在发布。

跨进程通道是模块自带的 ContentProvider（`cn.aodlyric.xiaowine/state`）：
Hook 进程 `call(update)` 推送，UI 注册 ContentObserver 拉取。Hook 进程没有
Context，通过 `ActivityThread.currentApplication()` 反射取；取不到就跳过本次
上报，不影响注入。歌词推送做了 200ms 节流。

**注意**：Hook 进程往 Provider 推数据时，如果模块 App 进程已被杀，系统会把它
拉起来——息屏状态下会有一次进程启动，属于预期行为。

若第四个卡片显示 `本进程注册: SERVICE_UNAVAILABLE` 或 `REJECTED`：检查
manifest 里 `superlyricapi` 三个 meta-data 是否在（AccessPolicy 凭它给
V4 权限），以及 SuperLyric 模块本身是否在运行。

> **注意**：AOD 自己的 `MusicControlScreenE` 日志（`raw lyric: ...` 那条）默认不输出。
> `MamlLog.d` 受系统属性 `log.tag.MAML_LOG_LEVEL` 门控，默认 WARN。要看 AOD 侧日志：
>
> ```bash
> adb shell setprop log.tag.MAML_LOG_LEVEL DEBUG
> adb logcat -s MusicControlScreenE
> ```

预期日志顺序：

```
AodLyric: module loaded: process=com.miui.aod ... api=102
AodLyric: package ready: com.miui.aod
AodLyric: hooked MediaMetadata#getString
AodLyric: hooked MusicLyricParser#parseLyric
AodLyric: hooked com.miui.maml.elements.MusicControlScreenElement#<init> (theme probe)
AodLyric: SuperLyric register result: OK
AodLyric: onLyric: <songId> lines=NN lrc=NNNN chars
AodLyric: theme probe: enableLyric=true name=music needUpdateLyric=true -> lyrics CAN render
AodLyric: parseLyric: injecting NNNN chars
```

## 怎么确认主题开了 enableLyric

模块里内置了**主题兼容性探测**：Hook 了 `MusicControlScreenElement` 的构造函数，
在 AOD 真正加载主题实例后，直接反射读它自己解析出来的字段：

```
AodLyric: theme probe: enableLyric=true name=music needUpdateLyric=true  -> lyrics CAN render
AodLyric: theme probe: enableLyric=false name=null needUpdateLyric=false -> lyrics WILL NOT render: theme lacks [MusicControl name=... enableLyric=true]
```

判断依据（来自反编译，字段是宿主自己从主题 XML 里 `getAttribute` 解析的）：

| 字段 | 归属 | 含义 |
|---|---|---|
| `mEnableLyric` | MusicControlScreenElement | `Boolean.parseBoolean(element.getAttribute("enableLyric"))` |
| `mName` / `mHasName` | ScreenElement（父类） | MAML 元素的 `name` 属性 |
| `mNeedUpdateLyric` | MusicControlScreenElement | `mEnableLyric && mHasName`，真正的总开关 |

**注意探测只在息屏实际加载主题后才有输出**——锁屏/息屏没显示音乐控件时，构造函数不会被调。
所以要边播歌边点亮息屏看。

### 判读表

| 现象 | 结论 |
|---|---|
| `theme probe ... CAN render` 且 `parseLyric: injecting` 有输出 | 链路全通，界面无歌词才需要再看主题渲染 |
| `theme probe ... WILL NOT render` | **主题不支持**，换带歌词的息屏样式，模块无法绕过 |
| 完全没有 `theme probe` 输出 | 音乐控件元素根本没被实例化（息屏没开音乐显示，或当前样式不含 MusicControl） |
| `SuperLyric register result` 不是 `OK` | `SERVICE_UNAVAILABLE` → SuperLyric 没装/没启用；其他见 `AccessPolicy` |

### 不装模块的静态检查（可选，需 root）

模块探测是最可靠的方式。若想不装模块先看一眼主题源码，息屏主题不在 APK 里
（APK 内无 `.maml` 资产），在设备的主题包里：

```bash
# 常见位置（HyperOS 可能不同）
adb shell su -c "grep -rl 'enableLyric' /data/system/theme/ /sdcard/MIUI/theme/ 2>/dev/null"
```

但主题文件常被压缩/重命名，静态 grep 可能搜不到 —— **以模块探测结果为准**。

---

## 已知限制

1. **主题必须支持歌词**：MAML 变量只在 `<MusicControl name="..." enableLyric="true">` 时创建。
   当前主题若没开 `enableLyric`，日志会看到 `raw lyric:` 有内容但界面无歌词 —— 这是主题问题，
   模块无法绕过。
2. **字体/描边/渐变等自定义外观不可用**：那是旧模块自己画视图的思路；
   新版走 MAML 主题的排版，模块改不了。
3. **SuperLyric 覆盖面**：它支持 14 个音乐 App（比 Lyric-Getter 少），不在列表里的 App 无歌词。
4. **多用户 / 分身**：未处理，只在主用户验证。
5. **锁屏（:keyguardeditor 进程）**：scope 只含 `com.miui.aod` 主进程，
   锁屏编辑器进程里的歌词渲染是另一条链路，未覆盖。

---

## 文件结构

```
build.ps1                              ← 唯一构建入口（版本号取自 manifest）
dist/                                  ← 构建产物 qxfly-aod-<versionName>.apk
docs/                                  ← 全部文档与界面参考图
├── README.md                          ← 本文件
├── BISECT.md                          ← 历史故障排查/复盘
├── COMPOSE_SETUP.md                   ← 工具链（.deps）搭建说明
├── LSPosed-API102适配指南.md
├── 项目分析报告.md
└── assets/                            ← 主页.jpg / 关于页.jpg（界面参考）
module/
├── debug.keystore                     ← 签名密钥（不要删）
├── stage_apk.py                       ← 打包（res.arsc 强制 STORED）
├── build/                             ← 中间产物（可删）
└── app/src/main/
    ├── AndroidManifest.xml            ← 版本号唯一来源
    ├── java/cn/qxfly/aod/
    │   ├── AodLyricModule.java        ← libxposed 入口
    │   ├── LyricBridge.java           ← Hook 安装 + 热重载
    │   ├── AodOverlayController.java  ← 息屏歌词视图/组件树定位
    │   ├── AnchorIds.java             ← 锚点 id 折叠规则（纯逻辑，有测试）
    │   ├── ComponentCollector.java    ← 组件树枚举（锚点下拉数据）
    │   ├── MamlBridge.java            ← MAML 反射 + 面板刷新杠杆
    │   ├── LyricStyle.java            ← 外观配置（字号/颜色/背景…）
    │   ├── LyricDisplay.java          ← 一次渲染的完整模型（行/翻译/下一句/进度）
    │   ├── SuperLyricSource.java      ← SuperLyric 接收端
    │   ├── LegacyLyricReceiver.java   ← 3.x 原始 AIDL 接收（单行载荷也能用）
    │   ├── MediaSessionSource.java    ← 系统 MediaSession：进度 / 时长 / 播放状态
    │   ├── ProviderLrcSource.java     ← Provider LRC 来源
    │   ├── AodStateProvider.java      ← cn.qxfly.aod.state
    │   ├── LrcBuilder.java            ← Lyric → LRC
    │   ├── LyricTextView.java         ← 自绘歌词视图
    │   ├── StateKeys.java             ← Provider 字段名
    │   ├── AodApplication.java        ← 进程入口：注册常驻歌词发布
    │   ├── LyricPublisher.java        ← 进程级 SuperLyric 注册（唯一全文来源）
    │   ├── KeepAliveService.java      ← 可选前台服务（默认关闭）
    │   ├── MainActivity.java          ← App 入口 + 数据中枢
    │   └── …                          ← AodLog（含环形缓冲）/ Reporter / 字体 / 主题探测
    ├── kotlin/cn/qxfly/aod/ui/
    │   ├── MiuixAppRoot.kt            ← Compose 根 + 底栏导航
    │   ├── base/                      ← MiuixPage / GlassBar
    │   ├── core/                      ← AppSettings / ModuleData / Theme
    │   └── pages/                     ← HomePage / StylePage / SettingsPage / StyleEditor / AboutPage / DiagnosticsPage
    └── resources/META-INF/xposed/
        ├── module.prop                ← 版本号由 build.ps1 自动同步
        ├── scope.list                 ← com.miui.aod
        └── java_init.list
module/test/java/cn/qxfly/aod/          ← 纯 JVM 测试（AllTests.main）
work/                                  ← 工具链/打包辅助脚本（python/ps1）
    └── run-tests.ps1                  ← 编译并运行上面的测试（无需 JUnit）
.deps/                                 ← JDK21、Kotlin 2.4、SDK build-tools、AAR 资源
```
