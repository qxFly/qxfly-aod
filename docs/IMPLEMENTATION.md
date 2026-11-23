# 功能实现索引（IMPLEMENTATION.md）

本文件记录**每个功能的关键实现方法与实现路径**，供后续查阅与排障。

## 文档约定（更新制，务必遵守）

1. **旧实现不删除**。同一功能换了实现方式时，保留旧条目，在「演进记录」里**追加**新条目。
2. 每条演进记录写清：`日期 / 版本` → `做法` → `结果或废弃原因`（有实测数据就附上）。
3. 状态标记：`✅ 现行` / `⛔ 已废弃` / `🧪 待复核`。
4. 「当前实现」一节只描述**现在生效的那一套**，并给出 `文件路径` + `类#方法`。
   - **不写行号**（行号会漂移）；用类名 + 方法名定位。
5. 新增功能时：先在「功能索引」加一行，再补一节；不要只改代码不留文档。
6. 相关文档分工：
   - `docs/README.md`：面向使用者的说明 + 按轮次的更新日志（现象、取证、结论）
   - **本文件**：面向开发者的实现索引（怎么做的、代码在哪、为什么换过做法）
   - `docs/BISECT.md`：历史故障排查复盘

---

## 功能索引

| # | 功能 | 主要文件 | 当前实现入口 |
|---|---|---|---|
| A | 歌词数据来源与合并 | `LyricBridge` / `SuperLyricSource` / `LegacyLyricReceiver` / `ProviderLrcSource` | `LyricBridge#buildDisplay` |
| B | 跨进程状态通道与低延迟推送 | `AodStateProvider` / `StateKeys` / `LyricBridge` | `LyricBridge#pollOnce` + `#kickPoll` |
| C | 歌名/歌手交叉校验 | `TrackInfo` | `TrackInfo#resolve` |
| D | 播放进度与外推 | `MediaSessionSource` | `MediaSessionSource#extrapolate` |
| E | 歌词渲染与放置（3 种模式） | `AodOverlayController` / `LyricTextView` | `AodOverlayController#attach` / `#insertIntoFlow` / `#applyShadowPlacement` |
| F | 歌词宽度约束 | `AodOverlayController` | `AodOverlayController#applyFullWidth` |
| G | 内容高度约束 | `AodOverlayController` / `LyricTextView` | `#expandFlowSpace` / `#maybeExpandBudget` / `LyricTextView#onMeasure` |
| H | 息屏面板刷新（核心） | `ThemeProbeV2` / `AodOverlayController` | `ThemeProbeV2#hookDreamDozeState` + `#setPanelAwake` |
| I | 触摸与双击亮屏 | `AodOverlayController` / `ThemeProbeV2` | `#installTouchWatch` / `#watchDoubleTap` / `ThemeProbeV2#doubleTapWake` |
| J | 暂停/空闲功耗管理 | `ThemeProbeV2` / `AodOverlayController` | `#restoreSuspend` / `#parkPanelSuspend` / `#confirmPanelSuspended` |
| K | 样式系统与热更新 | `LyricStyle` / `StateKeys` / `StyleEditor.kt` / `ProviderLrcSource` | `ProviderLrcSource#styleStamp` |
| L | 歌词背景（色/透明度/圆角） | `LyricTextView` / `LyricStyle` / `StyleEditor.kt` | `LyricTextView#setLyricBackground` |
| M | 自定义字体 | `CustomFontLoader` / `AodStateProvider` / `MainActivity` | `CustomFontLoader#load` + `StateKeys#STYLE_FONT_STAMP` |
| N | 组件枚举与锚点定位 | `ComponentCollector` / `AnchorIds` / `AodOverlayController` | `ComponentCollector#collectComponents` / `#applyAnchorOffset` |
| O | 诊断与日志回传 | `AodLog` / `ModuleReporter` / `AppReporter` / `AodStateProvider` | `StateKeys#METHOD_DIAG_REQUEST` |
| P | 机型/系统兼容 | `ThemeProbeV2` / `AodLyricModule` | `ThemeProbeV2#matchesTarget` |
| Q | 构建与测试 | `build.ps1` / `work/run-tests.ps1` / `module/test` | `AllTests#main` |

---

## A. 歌词数据来源与合并

### 当前实现 ✅

三路来源，按「本地优先、Provider 兜底」合并：

| 来源 | 类 | 说明 |
|---|---|---|
| SuperLyric v4 流 | `SuperLyricSource` | 进程内回调，延迟最低；`onLyric(LyricView)` / `onStop(Stop)` |
| SuperLyric legacy 广播 | `LegacyLyricReceiver` | `onLyric(publisher, SuperLyricData)`，带 `translation` |
| App 进程快照 | `ProviderLrcSource#refresh` | 读 `content://cn.qxfly.aod.state/state`，权威进度/时长/状态 |

合并逻辑：`LyricBridge#buildDisplay(ProviderLrcSource)`
- 本地有行 → 用本地行/译文/标题/歌手/下一句
- 本地为空 → 用 Provider 的 `displayLine()` 等
- 进度/时长/播放态始终取 Provider + `MediaSessionSource`

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/LyricBridge.java        # buildDisplay / pollOnce
module/app/src/main/java/cn/qxfly/aod/SuperLyricSource.java   # v4 流
module/app/src/main/java/cn/qxfly/aod/LegacyLyricReceiver.java# legacy 广播
module/app/src/main/java/cn/qxfly/aod/ProviderLrcSource.java  # Provider 快照
module/app/src/main/java/cn/qxfly/aod/LrcBuilder.java         # Lyric -> LRC 文本（时间戳硬约束）
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 2026-11-12 前 | 只 hook `MediaMetadata#getString` + `MusicLyricParser#parseLyric` 拿歌词 | ⛔ 依赖主题 `enableLyric`，多数样式没有 MusicControl 元素，拿不到消费者 |
| 1.7.0 | **数据流反转**：App 进程接 SuperLyric → Provider → Hook 进程读 | ✅ 与主题解耦 |
| 3.x | 增加 legacy 广播通道（带译文） | ✅ 覆盖旧版 SuperLyric |
| 4.x | 增加 v4 流（`LyricView`），本地优先 | ✅ 延迟最低，App 被冻结时仍可用 |
| 2026-11-20 | 进度/歌名改读系统 `MediaSession` | ✅ 修掉「下一句/进度空白」 |

---

## B. 跨进程状态通道与低延迟推送

### 当前实现 ✅

- 通道：`AodStateProvider`（authority `cn.qxfly.aod.state`），`call(method, arg, extras)`
  - `update`：按 `extras` 里的 `type`（`module`/`status`/`lyric`/`style`/`components`/`diag`）分派合并
  - `get`：返回整份快照 Bundle
  - `diagRequest` / `clear`
- 写入后 `notifyChanged()` → `ContentResolver#notifyChange`
- Hook 侧：`LyricBridge#ensureProviderObserver` 注册 ContentObserver
  - `onChange` → `sProviderDirty = true` **并** `kickPoll()`
  - `kickPoll()`：120ms 合并窗口 → 把 `pollOnce(true)` 投给同一个单线程执行器（跳过自适应节流）
- 1s 心跳仍在：进度条刷新、看门狗、媒体会话采样

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/AodStateProvider.java   # call / apply / notifyChanged / openFile(字体)
module/app/src/main/java/cn/qxfly/aod/StateKeys.java          # 全部键名与 METHOD_*
module/app/src/main/java/cn/qxfly/aod/LyricBridge.java        # pollOnce / kickPoll / ensureProviderObserver
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 两个宿主进程各自 **每秒一次 `content call`** | ⛔ 息屏空白时也在烧 binder |
| 7.5.0 | 自适应轮询（1s tick，脏标记 + 心跳 5s/10s/30s）+ ContentObserver 置脏 | ⛔ 推送只置脏，真正查询要等下一个 tick → **实测 0–1000ms 随机延迟**（用户反馈「歌词慢 1s」） |
| 2026-11-22（8.1.5） | 观察者收到推送即 `kickPoll()`（120ms 合并）→ 立即 `pollOnce(true)` | ✅ 实测 `legacy lyric:` → `now playing:` **150–190ms** |

---

## C. 歌名/歌手交叉校验

### 当前实现 ✅

`TrackInfo#resolve(payloadTitle, payloadArtist, sessionTitle, sessionArtist, screenLine)`：

- 会话 `ARTIST` 里找不到载荷 `artist` → 载荷元数据属于**旧歌**，改用会话值
- 此时若会话 `TITLE` 只是屏幕上的歌词行（椒盐把歌词行写进 TITLE）→ **不作曲名**，标题行降级为只显示歌手
- 无矛盾 → 载荷优先，会话只补空

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/TrackInfo.java
module/test/java/cn/qxfly/aod/TrackInfoTest.java   # 9 组场景
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 直接信任 SuperLyric 载荷的 `track`/`artist` | ⛔ 切歌后残留旧曲（「羽·泉 - 奔跑」） |
| 8.1.1 前 | 改读会话 `METADATA_KEY_TITLE` | ⛔ 椒盐把**当前歌词行**写进 TITLE，2 秒一变 |
| 2026-11-21（8.1.2） | 两源交叉校验（本条） | ✅ 切歌即时跟上，宁可少显示不显示错歌 |

---

## D. 播放进度与外推

### 当前实现 ✅

`MediaSessionSource#extrapolate(base, updatedAt, speed, now)`：按 `lastPositionUpdateTime` + `speed` 外推，
**上限 `POSITION_MAX_EXTRAPOLATION_MS = 30_000`**：超过 30s 的外推一律截断。

会话选择：`#refreshControllers` + `#score(PlaybackState, MediaMetadata)` 打分挑最可信的会话。

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/MediaSessionSource.java
module/test/java/cn/qxfly/aod/MediaSessionSourceTest.java   # 外推边界
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 只用播放器上报的 position | ⛔ 上报稀疏，进度卡顿 |
| 8.1.1 | 按时钟无限外推 | ⛔ 播放器偶发「state=PLAYING 但时间戳冻结」，几分钟后顶到 `4:50/4:50` 钉死 |
| 2026-11-21（8.1.2） | 外推加 30s 时效上限 | ✅ 会话恢复刷新时自动纠正 |

---

## E. 歌词渲染与放置（3 种模式）

### 当前实现 ✅

自绘 `LyricTextView`（继承 TextView，自绘描边 + 背景），挂进 AOD 自己的视图树，随息屏生灭。

| 模式 | 入口 | 适用 |
|---|---|---|
| 容器/锚点放置 | `AodOverlayController#attach` → `#resolveClockContainer` → `#applyAnchorOffset` / `#applyOffset` | 常规 AOD：挂进时钟容器，随防烧屏漂移移动 |
| 组件树流插入 | `#insertIntoFlow`（`styleTreeInsert=1`） | 让原生布局引擎排位（位置=树位置） |
| shadow 全屏模式 | `#applyShadowPlacement` | 全屏 AOD（与锁屏一致）：插件内容树被整体淡出，改挂 keyguard `AodView#aod_root_view` |

关键辅助：
- `#relaxClipping` / `#relaxSubtreeClipping`：关闭裁剪
- `#reinflate` / `#ensureFront`：重挂与置顶（树模式下禁用，避免跳动）
- `#startKicker`：1s 自适应心跳（内容刚变时每秒重绘，稳定后每 3 tick 走重路径）
- `#startPulse` / `#stopPulse`：无限 alpha 动画逼出逐帧合成（仅播放时）

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java
module/app/src/main/java/cn/qxfly/aod/LyricTextView.java
module/app/src/main/java/cn/qxfly/aod/MamlBridge.java        # forceTraversal / driveFramerate / startRootMotion
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 走 MAML 主题原生歌词通道（MusicControl 元素） | ⛔ 多数样式没有该元素，无消费者 |
| 3.0.0 | **路线 C：自绘 TextView**，挂 `DozeServicePluginImpl#onDreamingStarted(ViewGroup)` 拿到的根 | ✅ 任意样式可用 |
| 2026-11-16（第七轮） | 全屏 AOD 适配：检测祖先链 alpha<0.05 → shadow 模式 | ✅ 修「视图活着但不显示」 |
| 2026-11-17（第九/十一轮） | 背景整体化、组件上方抖动修复 | ✅ |

---

## F. 歌词宽度约束

### 当前实现 ✅（2026-11-23，真机已验证）

`AodOverlayController#applyFullWidth(TextView)`：

1. `lp.width = 屏幕宽像素`（`displayMetrics.widthPixels`，若 AOD 根更宽则取根宽）
   - 正数 width 按 `EXACTLY` 测量 → **父容器再窄也夹不住**
2. `translationX = -(父容器 getLocationOnScreen()[0] + 父容器 paddingLeft)`
   - 让歌词左边缘对齐**屏幕**左边缘，而不是主题内容列左边缘
3. 三条放置路径都调用（`attach` / `insertIntoFlow` / `applyShadowPlacement`），
   并在 kicker 每 tick 重申（MIUI 防烧屏漂移 + 主题重建 LayoutParams）

配合 `#relaxClipping` 让溢出可见；对齐方式仍生效（居中=屏幕居中）。

**实测证据**（Xiaomi `2509FPN0BC` / Android 17 / HyperOS 4，主屏 1200x2608）：

```
overlay geometry: xy=0,1233 size=1200x351   (34sp)
overlay geometry: xy=0,1207 size=1200x815   (44sp 四行)
主题内容列快照:  clockGradientLinearLayout|[339,685 521x193]   ← 只有 521px @ x=339
```

- 视图 `x=0`、`width=1200` = 整屏；单行文字实测宽 **1009–1039px**（旧 521px 列约只能装 5 个字）
- `full-width apply failed` **0 次**；切歌 / 改字号 / 防烧屏漂移（`clock_container` ty −211→−237）全程 `size=1200` 不变

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java   # applyFullWidth
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 3.0.0 起 | `lp.width = MATCH_PARENT` | ⛔ 被主题内容列宽限制（实测 **521px / 屏宽 1200px**），长句提前换行 |
| 2026-11-23 | 显式屏宽 + `translationX` 补偿（本条） | ✅ 真机 `xy=0 / size=1200xH`，单行文字 1009–1039px；三条放置路径均接入，kicker 每秒重申 |

---

## G. 内容高度约束

### 当前实现 ✅

四层配合，尽量解除各主题的高度限制：

| 机制 | 位置 | 作用 |
|---|---|---|
| 自然高度测量 | `LyricTextView#onMeasure` | 父给固定高度时，用 `UNSPECIFIED` 重测并上报自然高度 |
| 祖先预算扩高 | `AodOverlayController#expandFlowSpace` | 把歌词占位高度加到祖先链上每个**固定/match** 容器（主题列是固定高） |
| 增量再扩 | `#maybeExpandBudget` | 文本变长/换行增多时再次扩高 |
| 解除裁剪 | `#relaxClipping` / `#relaxSubtreeClipping` | 歌词→根、以及整棵子树 `clipChildren=false` |

另：TextView **不设** `maxLines` / `ellipsize` / `singleLine`，可无限换行。
离开流时 `#restoreExpandedHeights` 还原主题原始高度。

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 只 `invalidate()` 重绘 | ⛔ 大字号被容器裁切（「最大字号被裁」） |
| 2026-11-16 | 祖先链关闭裁剪 + 自然高度测量 | ✅ 溢出画进 AOD 空白区 |
| 2026-11-17 | 预算扩高（`expandFlowSpace`） | ✅ 修「下方时间日期/通知被挤压」 |
| 2026-11-17 | 增量再扩（`maybeExpandBudget`） | ✅ 修长句换行后再次被裁 |
| 2026-11-23 | 整屏宽后复核大字号 | ✅ 44sp **三行/四行**均完整：自然高度 351→623→815px，`expanded lyric budget` 连扩 5 次（363→512→655→827→1017px），文字 bbox 完整落在背景框内（上留 ~66px / 下留 ~56px），无裁切 |

---

## H. 息屏面板刷新（核心，历史最长）

### 当前实现 ✅

**把 `DOZE_SUSPEND(4)` 改写成 `DOZE(3)`**，只在歌词播放期间生效。

- Hook 点：`android.service.dreams.DreamService#setDozeScreenState(int)`
  - `MiuiDozeService` 继承 `DreamService` 且**未覆写** → 这是唯一拦截点
- 捕获服务实例：hook `com.android.keyguard.doze.MiuiDozeService#onCreate`（`#noteDreamService`）
- 捕获状态机：hook `com.android.systemui.doze.DozeMachine` 构造器，读 `mState`
- 开关：`ThemeProbeV2#setPanelAwake(boolean, boolean force)`
  - 由 `AodOverlayController#setPanelHold` 驱动（播放/暂停/空闲/detach/kicker 每 15 tick 重申）
- `sSelfApply` 标记：区分「我们自己的调用」与「机器的请求」，避免污染簿记

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/ThemeProbeV2.java          # hookDreamDozeState / setPanelAwake / applyDozeState
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java  # setPanelHold / requestPulse
```

### 演进记录（全部保留，勿删）

| 版本 | 做法 | 结果 |
|---|---|---|
| 3.6.0 | `invalidate()` + 1s 心跳 | ⛔ 面板不刷新 |
| 3.7.0 | `ViewRootImpl#scheduleTraversals()` 反射 | ⛔ 反射成功但无效 |
| 3.8.0 | MAML `requestFrameRateByCommand(30f)` | ⛔ 抓到的根不是 AOD 的 |
| 3.9.0 | 多根捕获 + 视图树 harvest | ⛔ AodView 里没有 MamlView |
| 4.0.0 | 持续 `ObjectAnimator` 帧 | ⛔ `pulse=true` 仍冻结 |
| 4.1.0 | 结构重建 + `Surface#setFrameRate(60)` | ⛔ 两者都成功，无效 |
| 5.0.0 | 独立 SurfaceView 层 | ⛔ `loop started` 仍冻结 |
| 5.1.0 | MAML `requestUpdate()` + 时钟变量 | ⛔ `frameDrive=true` 无效 |
| 5.2.0 | AOD 根视图自身动画 | ⛔ |
| 5.3.0 | 小米 touch 特性 mode 19/11 | ⛔ 不改电源状态，且**吞触摸**（见 I） |
| 5.4.0 / 6.0.0 | `com.android.systemui.doze.pulse` 广播 | ⚠️ 当时唯一有效，但只在 pulse 瞬间刷新；后证明 pulse 会被 AOSP `DozeTriggers` 守卫丢弃 |
| 2026-11-22（6.1.0 / 8.1.3） | **hook `DreamService#setDozeScreenState`，播放期间 4→3** | ✅ 真机 `Display State=DOZE` 跨分钟不掉，10s **+1134 帧**；暂停回 4 后 **0 帧** |

**关键教训**：刷新门控不在 View 层、也不在触控服务，而在**显示电源状态**。
`DOZE_SUSPEND` 下合成器不再送帧，但 `screencap` 仍读到新缓冲区 —— 这是「看起来在刷新」的假象来源。

---

## I. 触摸与双击亮屏

### 当前实现 ✅

- 模块**自己识别双击**：`AodOverlayController#installTouchWatch` hook AOD 容器的
  `dispatchTouchEvent`（`ViewGroup` 声明），`#watchDoubleTap` 判定「两次短按抬手、间隔<300ms、位移小」
- 命中后 `ThemeProbeV2#doubleTapWake` → `#wakeNow` → `PowerManager#wakeUp(uptime, "com.android.systemui:NODOZE")`
- **尊重用户设置**：`#systemDoubleTapWakeEnabled` 读 `Settings.System["gesture_wakeup"]`，为 0 则忽略
- pulse 周期内 wakeUp 会被吞 → `#armWakeRetry` 兜底重试（pulse 排空后补发）
- **不碰** `Utils.setTouchMode`（见演进记录）
- 诊断：保持 DOZE 期间收到 ACTION_DOWN 会打 `AOD touch down while holding DOZE`（1s 节流）

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java  # installTouchWatch / watchDoubleTap / systemDoubleTapWakeEnabled
module/app/src/main/java/cn/qxfly/aod/ThemeProbeV2.java          # doubleTapWake / wakeNow / armWakeRetry / hookRequestWakeUp
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 依赖 MIUI 自己的双击路径 | ⛔ `DozeHost.onTouch` 三重门：`mSupportGestureWakeup && mOpenDoubleTapGoToSleep && needKeepScreenOnAtFirst()`，第三项随样式恒 false |
| 2026-11-15（第四轮） | 模块自识别双击 + 立即 `wakeUp` + 重试兜底 | ✅ 实测双击到亮屏 3–4ms（曾误做成「等 pulse 结束」→ 延迟 9s） |
| 2026-11-15（第五轮） | 补读 `gesture_wakeup`，尊重系统开关 | ✅ 用户关掉双击后模块不再亮屏 |
| 2026-11-22（8.1.3） | 为刷新接通 `Utils.setTouchMode(0,19,1)` / `(0,11,1)`（`getDeclaredMethod`+`setAccessible`） | ⛔ **播放中触摸全失效**：反编译证实直板机上 MIUI 从不把 mode 19 置 1（`DozeHost.lambda$startEnterAnim$10`、`DozeTriggers.lambda$transitionTo$0` 都是置 0），我们把 IC 切进它不用的状态 |
| 2026-11-22（8.1.4） | **删除全部 `setTouchMode` 调用** | ✅ 触摸恢复；推送版之所以正常，是因为当时 `getMethod` 拿不到 private 方法、调用一直静默失败 |
| 2026-11-22（8.1.5） | 保持 DOZE 期间**不再发 pulse** | ✅ 消除「歌词更新后 1–2s 内双击无效」（pulse 周期吞 wakeUp） |

---

## J. 暂停/空闲功耗管理

### 当前实现 ✅

释放保持后由模块**自己负责收尾**（机器认为已挂起，不会再请求）：

```
setPanelHold(false) → ThemeProbeV2#restoreSuspend
  → 宽限 PANEL_RELEASE_GRACE_MS=1200ms（让"变暗的暂停行"在 DOZE 下先上屏）
  → #parkPanelSuspend(PULSE_WAIT_BUDGET_MS=6000)
       ├ UNINITIALIZED/INITIALIZED/DOZE_REQUEST_PULSE/DOZE_PULSING → 每 500ms 重试
       ├ FINISH（已唤醒）→ 放弃，交还系统
       └ 其余 doze 状态 → applyDozeState(4)
  → 恢复播放：#cancelPark()（否则宽限期内的 park 会打在新一轮保持上）
```

兜底自愈 `ThemeProbeV2#confirmPanelSuspended()`（kicker 暂停每 5 tick / 空闲每 15 tick 调用，内部 10s 节流）：
- 稳定态 `DOZE_AOD` / `DOZE_AOD_PAUSED` / `DOZE_PULSE_DONE` 且未持有面板 → 补写 4
- **pulse 状态持续 > `STUCK_PULSE_MS=5000`** 视为卡住 → 同样补写 4

帧泵：`sPaused` 为真时 `#stopPulse()` + `#stopRootMotion()`，kicker 不再 `driveFramerate()`。

pulse 收敛：`#setLyric` 的空闲/暂停分支用 `wasActive` / `wasPaused` **只在跃迁时**发一次 pulse。

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/ThemeProbeV2.java          # restoreSuspend / parkPanelSuspend / confirmPanelSuspended / cancelPark
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java  # setLyric / setPanelHold / startKicker / requestPulse
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 8.1.3 | 暂停时 `setPanelAwake(false)` → `machineWantsSuspend()`（只认 `DOZE_AOD_PAUSED`）才写 4 | ⛔ 暂停瞬间机器是 `DOZE_AOD` → 提前返回，不恢复 |
| 8.1.3 | 空闲/暂停分支**每次轮询**都 `requestPulse` | ⛔ 新机上变成每 4–5s 一个 pulse，屏幕反复点亮 |
| 2026-11-22（8.1.6） | pulse 收敛到跃迁 + 宽限 1.2s 自写 4 + 等 pulse 排空 + `cancelPark` | ✅ 暂停 **+2s 回 `DOZE_SUSPEND`**，30s 不回弹 |
| 2026-11-22（8.1.6） | 瞬时状态重试 + 稳定态自愈 + **卡住 pulse >5s 自愈** | ✅ 6 种补电源键压测均未再卡住（曾出现卡 `DOZE_PULSING` 亮屏 2 分钟 / 卡 14 分钟） |
| 2026-11-22（8.1.6） | 暂停时停 `sPulse` / `sRootMotion` / `driveFramerate` | ✅ 语义正确；注：暂停+挂起时仍见 ~130fps，模块 idle 时同样，判定为 MIUI 自身 AOD 动画 |

---

## K. 样式系统与热更新

### 当前实现 ✅

- 模型：`LyricStyle`（字号/颜色/字体/描边/粗细/对齐/锚点/树插入/译文/背景/歌名/下一句/进度/暂停保留）
- 键名：`StateKeys.STYLE_*`，全部列入 `StateKeys.STYLE_KEYS`
- 传输：`LyricStyle#toBundle` / `#fromBundle`（字符串化，兼容旧载荷）
- 热更新：`ProviderLrcSource#styleStamp(Bundle)` 把 `STYLE_KEYS` 拼成戳；戳变 → `AodOverlayController#setStyle`
- UI：`StyleEditor.kt` 的 `StyleDraft` ⇄ `LyricStyle`（`toDraft` / `toLyricStyle`），滑条可点数值直接输入
- 预设/导入导出：`StyleEditor.kt` 的 `Preset` / `toJson` / `draftFromJson`（`format=aodlyric-style`, `version=1`）

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/LyricStyle.java
module/app/src/main/java/cn/qxfly/aod/StateKeys.java
module/app/src/main/java/cn/qxfly/aod/ProviderLrcSource.java     # styleStamp
module/app/src/main/kotlin/cn/qxfly/aod/ui/pages/StyleEditor.kt
module/app/src/main/res/values/strings.xml
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 样式改动需重启 AOD | ⛔ 体验差 |
| 8.x | 样式戳（全键参与）→ 改完保存即重绘 | ✅ 新增开关自动纳入，无需改戳逻辑 |
| 2026-11-18（第十三轮） | 滑条数值可输入 | ✅ |
| 2026-11-22（8.1.5） | 新增 `styleBgRadius` 并入 `STYLE_KEYS` | ✅ 圆角热更新 |

---

## L. 歌词背景（颜色 / 不透明度 / 圆角）

### 当前实现 ✅

`LyricTextView#setLyricBackground(enabled, color, alpha, radiusDp)`：
- 开启时给视图加 `dp(20) / dp(12)` 内边距，保证面板在视图边界内
- `onDraw` 里取**所有换行行界的并集**外扩 padding，画**一个**圆角矩形（原文+译文共用一块面板）
- 半径：`LyricStyle#clampBgRadiusDp`（0–40dp）→ 绘制时再夹到**面板短边的一半**（避免 Skia 变形）

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/LyricTextView.java   # setLyricBackground / onDraw
module/app/src/main/java/cn/qxfly/aod/LyricStyle.java      # bgEnabled / bgColor / bgAlpha / bgRadiusDp
module/app/src/main/kotlin/cn/qxfly/aod/ui/pages/StyleEditor.kt
module/test/java/cn/qxfly/aod/LyricStyleTest.java
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 2026-11-17（第九轮） | 每行一个圆角色块 | ⛔ 多行时割裂 |
| 2026-11-17（第十一轮） | 整体化：所有行并集画一块面板 | ✅ |
| 2026-11-17 | 圆角写死 `dp(24)` | ⛔ 不可调 |
| 2026-11-22（8.1.5） | 新增 `styleBgRadius`（0–40dp，默认 24）+ 滑条 + 预览 + JSON | ✅ 注入 0（方形）/ 40（胶囊）截图验证，热更新生效 |

---

## M. 自定义字体

### 当前实现 ✅

- App 侧导入字体 → 私有目录 `files/fonts/`，`MainActivity` 写 `StateKeys.STYLE_FONT_STAMP`（文件时间戳+长度）
- Hook 侧不能直接读 App 私有目录 → 通过 `AodStateProvider#openFile`（`content://cn.qxfly.aod.state/font/<name>`）取
  - 路径遍历防护：只取 bare filename，且限定 fonts 目录
- `CustomFontLoader#load(Context)` 加载并缓存，`#invalidate()` 失效
- 字体戳并入样式戳 → 覆盖导入后无需重启即生效

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/CustomFontLoader.java
module/app/src/main/java/cn/qxfly/aod/AodStateProvider.java   # openFile
module/app/src/main/java/cn/qxfly/aod/MainActivity.java       # 导入 + fontStamp
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 2026-11-16（第八轮） | 支持自定义字体 | ✅ |
| 同期 | 覆盖导入后需重启才生效 | ⛔ |
| 后续 | `STYLE_FONT_STAMP` 参与样式戳 | ✅ 热生效 |

---

## N. 组件枚举与锚点定位

### 当前实现 ✅

- `ComponentCollector#collectComponents` 枚举 AOD 组件（`id|class|bounds`），上报 `componentsList`
- 粗粒度锚点：`AnchorIds`（`clock` / `image` / `timedate` / `notify`），`#canonicalCandidates` 给出候选资源名，
  `#legacyToCanonical` 把旧细粒度 id 映射过来
- 定位：`AodOverlayController#applyAnchorOffset`（找到锚点视图 → `translationY` = 锚点边缘 ± `anchorGap`），
  找不到则回退 `#applyOffset` 的百分比 `offsetRatio`
- shadow 模式下用**可见的**锁屏大时钟坐标（`#visibleClockSpan`）代替被淡出的组件锚点

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/ComponentCollector.java
module/app/src/main/java/cn/qxfly/aod/AnchorIds.java
module/app/src/main/java/cn/qxfly/aod/AodOverlayController.java   # applyAnchorOffset / applyOffset / visibleClockSpan
module/test/java/cn/qxfly/aod/AnchorIdsTest.java
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 固定百分比偏移 | ⛔ 不同样式位置不一致 |
| 后续 | 细粒度锚点（具体资源名） | ⛔ 换样式/机型即失效 |
| 2026-11-19（第十四轮） | 4 个粗粒度锚点 + 旧 id 映射 | ✅ 跨样式稳定 |
| 2026-11-16 | 全屏 AOD 用可见锁屏时钟兜底 | ✅ |

---

## O. 诊断与日志回传

### 当前实现 ✅

- `AodLog`：进程内环形日志（`logTail`），带级别与相对时间
- App 侧点「诊断」→ 写 `diagRequest` → Hook 侧 `pollOnce` 发现变化 → `ModuleReporter#reportDiagnostics`
  把 `logTail` / `logBuild` / `logProcess` 回传 Provider
- `AppReporter` / `ModuleReporter`：状态、模块信息、组件列表、播放态镜像
- 失败静默重试：`ModuleReporter#flushPendingIfReady`（SystemUI 里 Application 创建锚点不可靠）

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/AodLog.java
module/app/src/main/java/cn/qxfly/aod/ModuleReporter.java
module/app/src/main/java/cn/qxfly/aod/AppReporter.java
module/app/src/main/java/cn/qxfly/aod/Reporter.java
module/app/src/main/kotlin/cn/qxfly/aod/ui/pages/DiagnosticsPage.kt
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | 只能 `adb logcat` 看 | ⛔ 用户无法自查 |
| 后续 | Provider `logTail` + 诊断握手 | ✅ App 内可看 |
| 2026-11-19（第十五轮） | HyperOS 3 / Android 16 状态上报修复（进程/时机） | ✅ |

---

## P. 机型 / 系统兼容

### 当前实现 ✅

- `ThemeProbeV2#matchesTarget(name, canonical, tail)`：先按规范全名匹配，再按**类名尾部**匹配
  （`isAodFamily` 限定 `com.miui.aod` / `com.xiaomi.aod` 家族），兼容不同 MIUI/HyperOS 把插件挪包的情况
- `ClassLoader#loadClass` 全进程监视（`#watchLoaded`）+ `#probeAlreadyLoaded` 双保险：
  插件类加载器无关，先加载的也能抓到
- LSPosed API 兼容：`AodLyricModule#buildHook` 仅在 `api >= API_102` 时 `setId`（101 上会 `NoSuchMethodError`）
- 隐藏 API：`HiddenApi#exempt`

已验证机型：

| 设备 | 系统 | 结论 |
|---|---|---|
| Xiaomi 14（`23127PN0CC`） | Android 16 / HyperOS 3 | ✅ 播放 DOZE +1134 帧/10s，暂停 0 帧 |
| Xiaomi `2509FPN0BC`（popsicle） | **Android 17 / SDK 37 / HyperOS 4.0** | ✅ 关键类全未漂移；播放 DOZE +1946~1964 帧/8s，暂停 +2s 回 `DOZE_SUSPEND` |

实现路径：
```
module/app/src/main/java/cn/qxfly/aod/ThemeProbeV2.java     # matchesTarget / watchLoaded / probeAlreadyLoaded
module/app/src/main/java/cn/qxfly/aod/AodLyricModule.java   # buildHook
module/app/src/main/java/cn/qxfly/aod/HiddenApi.java
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 1.8.0 | 直接 `Class.forName` 找插件类 | ⛔ 插件类加载器不同，找不到 |
| 1.8.0 | `ClassLoader#loadClass` 全进程监视 | ✅ |
| 2026-11-19（第十六轮） | 放宽目标类匹配（尾部匹配）+ 节流待发上报 | ✅ 多设备兼容 |
| 2026-11-22 | 误把 doze 守卫挂在 `com.miui.aod.doze.DozeMachine` | ⛔ 该类**从未加载**；真正跑的是 SystemUI 的 `com.android.systemui.doze.DozeMachine` |
| 2026-11-22 | 改挂 `DreamService#setDozeScreenState` + `MiuiDozeService#onCreate` + SystemUI `DozeMachine` 构造器 | ✅ |

---

## Q. 构建与测试

### 当前实现 ✅

- **唯一构建入口**：仓库根 `build.ps1`（必须 UTF-8 with BOM）
  - 只构建：`powershell -ExecutionPolicy Bypass -File .\build.ps1`
  - 构建+安装：`-Install`
  - 改了 Hook（`module/app/src/main/java/**`）：`-Install -Reboot`
  - 9 步：aapt2 compile/link → 补 R → javac → kotlinc(Compose) → javac(MainActivity) → d8 → stage → zipalign → apksigner
- 单测：`work/run-tests.ps1` → `module/test/java/cn/qxfly/aod/AllTests#main`
  - 纯 JVM，无 JUnit（`T` 断言助手）；当前 **68 断言**
  - 覆盖：`AnchorIdsTest` / `LrcBuilderTest` / `LyricDisplayTest` / `LyricStyleTest` / `TrackInfoTest` / `MediaSessionSourceTest`

实现路径：
```
build.ps1
work/run-tests.ps1
work/collect_aar_res2.py / aarzip_compile.py / gen_extra_r.py   # 构建依赖脚本
module/stage_apk.py                                            # res.arsc 强制 STORED
module/test/java/cn/qxfly/aod/                                 # AllTests + T + 各测试
```

### 演进记录

| 日期 | 做法 | 结果 |
|---|---|---|
| 早期 | Gradle + miuix 模板 | ⛔ 依赖重、`.deps` 难维护 |
| 2026-11-19 | 收敛为唯一 `build.ps1`（手写 aapt2/javac/kotlinc/d8 流水线） | ✅ 目录清理回收约 2.8GB |
| 2026-11-21 | 引入纯 JVM 断言测试（56 条） | ✅ 可门禁 |
| 2026-11-22 | 加 `LyricStyleTest`（→68 条） | ✅ |

---

## 附录：取证命令速查

```powershell
$adb = "D:\GitSoftware\scrcpy-win64-v3.2\adb.exe"

# 显示电源状态（注意行首有缩进，别用 ^ 锚定）
& $adb shell dumpsys display | Select-String "Display State="
# 多屏设备取主屏
& $adb shell "dumpsys display | grep -A1 'Display Id=0' | grep 'Display State='"

# 面板是否真的在出新帧（关键证据）
& $adb shell dumpsys gfxinfo com.android.systemui | Select-String "Total frames rendered"

# 模块日志
& $adb logcat -d -v time | Where-Object { $_ -match "AodLyric" -and $_ -notmatch "LSPosed" }

# 状态快照（含 logTail / 样式 / 组件列表）
& $adb shell content call --uri content://cn.qxfly.aod.state/state --method get

# 注入样式（必须带 type:s:style）
& $adb shell content call --uri content://cn.qxfly.aod.state/state --method update `
  --extra type:s:style --extra styleTextSize:s:34 --extra styleBgRadius:s:0

# 截图（必须 screencap + pull，exec-out > 会破坏二进制）
& $adb shell screencap -p /sdcard/x.png; & $adb pull /sdcard/x.png <本地路径>

# 播放控制
& $adb shell cmd media_session dispatch play   # 或 pause
& $adb shell dumpsys media_session | Select-String "state=PlaybackState"
```

**排查口诀**
1. 数据不动 → 看 `lyricAt` / `now playing` 是否刷新；静音会触发 PowerKeeper `pauseAudioTracks`（环境限制）
2. 数据在动但屏幕不动 → 看 `Display State`：`DOZE_SUSPEND` 就是没送帧（`screencap` 会骗人）
3. 触摸没反应 → 先确认有没有碰 `setTouchMode`；再看是否处在 pulse 周期
4. 暂停后不省电 → 看 `panel state -> DOZE_SUSPEND` 是否出现、`gfxinfo` 是否停增