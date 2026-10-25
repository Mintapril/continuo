# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

Continuo（包名 `dev.dsh.pocket`）：一个只面向一加 Ace 3V（Android 16、ColorOS、KernelSU root）的个人 Android 客户端，连接手机本机运行的 DSH 智能体（`127.0.0.1:3080`）。核心是 **Cowork 模式**：AI 在 Android 工作资料（userId 10）里一块独占的虚拟副屏上操作应用，用户继续用主屏。README 是概览，[docs/cowork-internals.md](docs/cowork-internals.md) 是实现细节，[cowork-plugin/PROTOCOL.md](cowork-plugin/PROTOCOL.md) 是插件与 Broker 之间的线协议。改 Cowork 行为前先读这三份。

中文为主的仓库：提交信息、README、docs、用户可见文案都是中文；代码注释多为英文。

## 构建与测试

没有 Gradle wrapper。工具链在仓库上两级的 `tools\android-build\`（JDK 21、Gradle 8.13、SDK 36），`test-unit.ps1` 从那里取路径；`local.properties` 不入库。

```powershell
.\test-unit.ps1              # 构建 debug APK + 编译并运行全部 JVM 测试（仓库根目录运行）
.\test-unit.ps1 -SkipBuild   # 只跑 JVM 测试
```

- JVM 测试不经 Gradle 的 test task 运行（本机 Unicode 路径会让 Gradle worker 启动失败），而是 `test-unit.ps1` 用 `JUnitCore` 直接跑一份**硬编码的测试类列表**。新增测试类必须加进脚本，否则不会被运行。
- 只跑一个测试类：复制脚本里的 `java -cp ... org.junit.runner.JUnitCore` 调用，只保留目标类名；JUnit 4 没有按方法过滤。
- 如果 `:app:compileDebugKotlin` 报 `AccessDeniedException …kotlin-compiler-in-shocket-*.alive`：把 `TEMP`/`TMP` 指向仓库内的 `.kctmp\`，并给 Gradle 加 `-Pkotlin.compiler.execution.strategy=in-process`，先手动 `gradle.bat --no-daemon :app:assembleDebug :app:compileDebugUnitTestKotlin`，再 `-SkipBuild`。
- `runtime/powerkey/`：长按电源键叫它的 root 守护进程（只读监听 `pmic_pwrkey`，关掉 `secure speech_assist_power_wakeup` 让小布让位），装在手机 `/data/adb/continuo-powerkey/`，由 `/data/adb/service.d/continuo-powerkey.sh` 开机启动。ColorOS 国行的长按电源键在框架里写死启动小布，不走系统助手角色。
- `isolation` 模块有自己的 JUnit 测试（`ProvisioningAttemptTest`），不在 `test-unit.ps1` 的列表里。
- 仪器测试 `app/src/androidTest/LocalContractTest` 要连真机并且 DSH 在跑，是部署核对，不是常规测试。

插件（Node ≥ 22）：

```powershell
cd cowork-plugin
node --test test/strict-*.test.mjs          # 全部离线测试
node --test test/strict-broker.test.mjs     # 单个文件；--test-name-pattern 可按用例名过滤
```

- `strict-integration`、`strict-production-activation` 和 `test/installed-seams.mjs` 依赖未入库的 DSH 运行时副本 `cowork-plugin/research/`，干净检出上会失败，这是已知的。
- `strict-auth-python` 会调用 Python 跑 `scripts/cowork_auth.py`；Windows 上默认用 DSH 运行时自带的 python（`COWORK_TEST_PYTHON` 可覆盖）。PATH 上的 `python` 是 WindowsApps 占位，什么都不做。

## 跨语言一致性由测试守住

插件（JS）与 Broker（Java）之间的常量和协议必须两边同时改，相关测试会读对方的源码：

- `cowork-plugin/test/strict-native-errors.test.mjs` 把 `broker.mjs` 的 `ERROR_CODES` 与 `app/.../cowork/broker/*.java` 里实际抛出的错误码对账。新增错误码要两边都加。
- `strict-broker.test.mjs` 核对 `ZOOM`（`broker.mjs`）与 `ScreenZoom.java` 的放大限制一致。
- `app/src/test/.../BrokerSourceTest.kt` 对 Broker 的 Java **源码文本**做断言（例如 `Set.of` 不能有重复元素，不能注册 root 进程无法注册的包监听，手势每笔的检查点）。Broker 类在 JVM 上加载不了，所以只能这样测。改 `ProfileWorkspace.stroke`/`strokeCheck`、`ProfileStructure.focusedField`/`appendFocused` 这类方法时先看这个测试在匹配什么。
- 模型可见的工具名是下划线形式（`phone_tap`），插件内部和线协议是点号形式（`phone.tap`），由 `modelName()` 转换；`ALLOWED_TOOLS` 是模型可见名。
- 插件与 APK 的协议必须匹配：一边加了参数另一边会拒绝。改 `broker.mjs` 的参数/结果 schema 时同步改 `ToolContract.java`。
- 通知入口（`notify.mjs` ↔ App 的 `notify/NotifyPolicy.kt`）：`strict-notify.test.mjs` 的 `VECTOR` 与 `NotifyPolicyTest` 逐字节对账同一个请求体和 MAC；改字段或签名格式要两边一起改（线协议见 PROTOCOL.md「Notification ingress」）。屏幕新内容的条目多一个 `origin: "screen"`（`SCREEN_BODY`）。
- App 的 `data/Companion.kt` 与 `data/UnattendedModels.kt`/`data/JevFocus.kt` 调的 `coworkUnattended/*` 方法名和请求体（`identity`、`setIdentity`、`diary`、`events`、`createNote` 的 `{sessionId, title, prompt, change}`，`change` 与 dsh-schedule 的 update 同形；`setModel` 的 kind 是 `patrol`/`notify`/`owner`）由 `unattended.mjs` 提供，**没有跨语言测试**：两边要一起改；`strict-integration.test.mjs` 钉住了网关能发现的方法列表。叫它的会话 id（`cowork-owner`，清空后 `cowork-owner-<yyyyMMdd-HHmmss>`）由 App 的 `Companion.isOwnerSession`/`nextOwnerSession` 和插件 `restricted.mjs` 的 `isOwnerSession` 各认一遍，改格式要两边一起改。
- 插件 `package.json` 的 `@deepseek-ai/dsh-*` peer 版本必须等于手机上运行的 DSH 版本：DSH 0.2.1 起版本不符的组件会被直接跳过（Cowork 预设和通知入口一起消失）。`strict-integration.test.mjs` 拿 `research/installed/dsh` 的版本核对；升级方法见 docs/cowork-internals.md「DSH 版本与升级」。
- 会话 ID 要过 Broker 的 `[a-zA-Z0-9_-]{1,100}`（`TaskLifecycle.begin`、`ActionGate.start`），否则这一轮在第一步前就被取消。`strict-notify.test.mjs` 从这两个 Java 文件读出规则核对 `sessionIdOf`。
- 观察结果里可选的 `appeared`（Broker 的 `ScreenWatch`）由 `broker.mjs` 的 `TREE_RESULT` 校验；旧插件会拒收带它的结果，所以插件和 APK 要一起部署。
- 输入后的屏幕可以是相对基准（最近一份完整界面）的变化：Broker 的 `TreeDiff`（纯 Java，`TreeDiffTest`）给元素配跨屏不变、任务内不复用的编号，`ProfileStructure.snapshot(..., full)` 输出 `nodes` 或 `base/added/changed/removed/unchanged`；插件 `broker.mjs` 的 `SCREEN_RESULT` 和 `#screen` 把变化合并到会话的基准上再核对编号，`context-plan.mjs` 压缩时保留基准。改字段要两边一起改（PROTOCOL.md “Change instead of a full tree”）；旧插件不认变化，先部署插件再装 APK。
- 便条（`[SELF NOTE]`）、巡视（`[PATROL]`）和巡视附带的被拦通知（`[JEV HELD]`）的文本格式：`strict-notes.test.mjs` 读 `ScheduleFormatTest.kt` 里的 `selfNote`/`keptNote`/`selfNoteBatch`/`patrolBatch`/`patrolHeld` 五行逐字节对账（App 的 `ScheduleFormat.delivery` 按它解析，不认识的段会让整条消息退回原文显示；便条的两种开场白、巡视清单 `patrolChecklist` 或 `jev-focus.mjs` 的 `heldSection` 措辞一改，用到它的那几行就要跟着重新生成）；`strict-integration.test.mjs` 读 `research/installed` 里 dsh-schedule 的源码，钉住它的提醒格式（`reframeReminder` 靠这个格式拆出每一条）。改抬头或字段名要两边一起改；升级 DSH 时先跑这条测试。
- 打包清单：`package.json` 的 `files` 要包含每个被打包模块 import 的本地文件（`strict-integration` 会检查），新增 `src/*.mjs` 时记得加，否则 DSH 加载插件时才报错。

## 架构

四个运行时组件，全部在手机上：

```
Continuo App（个人用户 0，Kotlin/Compose）
  ├─ data/DshApi.kt ── cookie 会话 + RPC 信封 + WebSocket remote.mux ──► DSH :3080（Node，Ubuntu chroot）
  ├─ cowork/CoworkConnection.kt ── HMAC 挑战应答（controller 角色）──► Broker :3091
  ├─ 状态卡（常驻通知）、叫它 / 记便条半屏（CallOverlay 悬浮窗口或 CallActivity）、全屏看画面与接管（TakeoverScreen）、系统助手（assist/）
  └─ island/ 任务状态（IslandStore）+ 接管画面的无障碍层级服务
                                                        DSH 里的 cowork-plugin（agent 角色）──► Broker :3091
Broker（root，app_process，从已安装 APK 的 dev.dsh.pocket.cowork.broker.BrokerMain 启动）
  └─ 持有虚拟副屏，UID 定向注入输入，UID 过滤截图，UiAutomation 读树，接管、看门狗、H.264 实时流
工作资料（userId 10）里的应用，资料拥有者是 isolation 模块（dev.dsh.pocket.isolation）
```

### Gradle 模块

- `app`：主应用 + Broker。Broker 源码在 `app/src/main/java/dev/dsh/pocket/cowork/broker/`，和 App 打进同一个 APK，但以 root 经 `app_process` 单独运行，**不是** App 进程的一部分。它通过 `platform-stubs` 的 `compileOnly` 桩访问 Android 16 隐藏 API（`android.view.InputWindowHandle`、`android.window.WindowInfosListener`），桩不打包。
- `isolation`：工作资料的资料拥有者与副屏上的"AI 桌面"（`HomeActivity`）。`debug` 也关闭了 `isDebuggable`。
- `fixture`：无网络、无权限的测试夹具应用，验收脚本只对它操作；`-PfixtureId=` 可换包名。
- `platform-stubs`：纯 Java 库，从 `local.properties` 读 SDK 路径。

### Broker 里的关键分层

- `BrokerMain`：HTTP 端点（agent 角色用 `/v1/tool` 调工具、`/v1/task` 申请并轮询每轮租约；controller 角色用 `/v1/control`、`/v1/state`、`/v1/apps`、`/v1/takeover`、`/v1/stream` 等）、任务租约、看门狗。锁纪律写在类注释里：`this` 只保护短暂的控制面状态，绝不跨工作区操作持有；工具调用走 `toolLock`。
- `ProfileWorkspace`：副屏驱动（创建/保持虚拟显示、注入、截图、手势笔画、保活）。`AndroidWorkspace` 是早期共享用户版本，已不在主流程。
- `ProfileStructure`：UiAutomation 读树、语义输入、焦点字段元数据。
- 可在 JVM 上单测的纯逻辑：`ToolContract`（参数校验）、`RefusalPolicy`（哪些拒绝不结束任务）、`TaskLifecycle`、`BrokerAuth`（HMAC）、`StrokePath`、`ScreenZoom`、`BatchPolicy`、`StrayTaskSweep`、`LauncherEntrySweep`、`PopupPermissionPolicy`、`NotificationGrantPolicy`（工作资料新装应用自动允许通知，由 `NotificationGrant` 执行）、`QuietNotificationPolicy`（工作资料应用的通知通道压到最低、不在主屏提醒，由 `QuietNotificationGate` 执行）、`QuietAudioPolicy`（工作资料第三方应用的 `PLAY_AUDIO` 设为忽略，副屏上的视频不从手机外放，由 `QuietAudioGate` 执行）、`ParkedApps`（副屏重建后恢复停在后面的应用）、`TreeDiff`（跨屏不变的元素编号和输入后屏幕的变化）、`ScreenWatch`（模型思考时副屏上自己冒出来、它没看到的文字：随下一次观察报 `appeared`，一轮结束时剩下的直接并进通知触发的批次）、`ActivityLog` 等。往这些类里放逻辑，Broker 的 Android 依赖部分尽量薄。
- `NotifyBridge`：通知触发的读取端。灭屏后 ColorOS 会冻结 Continuo 并扣住它的通知回调，所以由 root 的 Broker 注册系统级通知监听（`registerAsSystemService`，所有用户）。合并、发送、唤醒锁和记录复用 App 包里可在 JVM 上测的 `notify/NotifyRelay`；设置来自 Continuo（`POST /v1/notify-settings`），存在 `BASE/notify-settings.json`。`wakeController` 和 `keepControllerRunning` 会解冻被冻结的 Continuo，好让一轮能拿到控制端心跳开始。
- `*ProbeMain` 是探测入口，不在主流程。

### App 里的关键分层

- `data/`：`DshApi`（唯一允许的目标是 `http://127.0.0.1:3080`，`init` 里 `require`），`Transcript`（会话快照归约为 `TranscriptRow`），`Modes`（只有 `COWORK`=`cowork-phone` 与 `CODE`=`standard` 两种预设，没有普通聊天模式），`RuntimeController`（经 `su` 调 `pocket-control.sh` 的固定动作），`MemoryCenter`/`ScheduleCenter`（通过 DSH 自己的插件路由读写 dsh-mneme 记忆与 dsh-schedule 任务），`CredentialVault`。
- `cowork/`：`CoworkConnection`（单例，controller 通道、实时流、接管输入），`CoworkControllerService`（前台服务，自愈重连），`CoworkStateBridge`（Broker 状态样本按 instance/revision 去旧，喂给 `IslandStore`），`StatusCard`（状态卡的标题、正文、按钮和头像表情，JVM 测试），`TakeoverScreen`（控制服务持有的全屏窗口：只看 / 接手两种模式、交还、应用列表、本机键盘代理输入），`CoworkShadeActivity`（通知按钮打开全屏前先收起通知栏的中转），`LiveVideo`、`RemoteKeyboard`。没有任务岛，也没有 Cowork 控制中心。
- `data/Modes.kt` 的 `SessionFilter`：应用固定会话（`isAppSession`，ID 以 `cowork-app-` 开头）只在“设置 → 开发者”会话列表的“自动运营”筛选下显示。
- 开发者会话列表、对话页和叫它半屏的“等副屏”：`data/Modes.kt` 的 `waitsForScreen` 加 `IslandSnapshot.screenHolder`（Broker 当前在跑、暂停中或停止中的任务属于别的会话）推断，没有单独的状态通道。
- 没有“自动化任务”这个概念，DSH 的 dsh-schedule 在 App 里一律叫便条（主页的“便条”页 `ui/NotesPane.kt`）。`data/ScheduleCenter.kt` 的 `ScheduleFormat.delivery`：把 `source.kind == "schedule"` 的消息拆成便条和巡视，会话页据此显示“便条”或“巡视”（插件改写之前的旧消息也按便条显示）。主人新写的便条（“留便条”半屏、巡视卡）由 App 经插件 `coworkUnattended/createNote` 直接存，不经模型，原话照存、不带标记，时间必须选（`data/Companion.kt`）。
- `data/Presentation.kt` 的 `foldedEntries`：一轮结束后把思考、工具调用、中间回复折叠成一项，只留用户消息、报错和最后的结论；正在进行的一轮不折叠。
- `notify/`：通知触发，没有按应用的规则。通知由 Broker 读取（`NotifyBridge`），App 里没有通知监听。`NotifyCenter`（单例：开关、排除列表、等待时间，推给 Broker，最近记录从 Broker 状态读）、`NotifyRelay`（Broker 用的批次、发送、唤醒锁与记录，JVM 测试）、`NotifyPolicy`（可在 JVM 上测的过滤、去抖合并（等待时间可在设置页选，默认 5 秒）、v2 线格式）。设置页 `ui/NotifySettings.kt`（含模型写的 Jev 关注说明列表，`data/JevFocus.kt` 解析，只能清除）。`data/QueuedInput`：停止后把最早一条排队消息重新 steer 回去（DSH 停止后不会为已排队的消息自动开新一轮）。
- `island/`：`IslandRuntime`（`IslandStore` 与暂停/停止的派发；名字是任务岛时代留下的）、`IslandAccessibilityService`（只用来把全屏接管窗口抬到状态栏上层，不读内容）、`IslandLayerKeeper`、`MarqueeGlow.kt` 的 `EdgeGlow`（接管时的彩色边框）。
- `data/Identity.kt`：它的名字和头像（`IdentityStore`，存在 App；名字经 `coworkUnattended/setIdentity` 交给插件写进提示词），以及叫它当前用哪个会话（`OwnerConversation`：先是 `cowork-owner`，每次“清空对话”换一个新的，旧的归档；`PocketViewModel.clearOwner` 先把旧会话里没到点的便条在新会话建好再撤下旧的）。`data/Companion.kt`：叫它的会话 id、日记分页、主人便条的请求和回执文字、搬便条的请求（JVM 测试）。`avatar/Stickers.kt`：把 `assets/avatars/<角色>.av`（gzip 的矢量贴纸，8 个角色 × 9 种状态）画成位图，App 和通知共用。
- `ui/`：Compose 界面，没有聊天框、抽屉和任务列表：首页 `Today`（现在卡片；聊天记录 `ChatPane`（叫它的对话，`data/ChatLog.kt` 整理，ViewModel 单独订阅 `followOwner`，不动 `selected`：选中会话就会进开发者对话页）、事件 `EventsPane`（巡视、通知唤起、到点便条在应用会话里跑的回合，`coworkUnattended/events`）、便条 `NotesPane`、日记 `DiaryPane` 四栏，`HorizontalPager` 跟手左右滑；留便条 / 叫它）、新装引导 `Onboarding`、设置（它 / 它的手机 / 这台手机 / 开发者），Code 模式和全部会话在 `Developer.kt`（设置 → 开发者）。叫它 / 记便条半屏（`ui/CallScreens.kt`，自己滑入滑出；发送时输入框不能禁用或设只读，否则键盘收起、半屏跳动；它干活时的进度字由 `data/Progress.kt` 按这一轮的记录挑，`ProgressTest` 守着每句话不重复）有两个宿主，都在根包：`CallOverlay`（App 进程里直接加的悬浮窗口，没有 Activity；快捷开关、状态卡按钮、系统助手用它，因为 ColorOS 会给快捷开关启动的 Activity 加放大卡片的动画；要麦克风权限或回答 DSH 的提问时转给 CallActivity）和 `CallActivity`（导出：分享菜单两个入口 `ShareCall`/`ShareNote`、启动器快捷方式、`ACTION_ASSIST`；分享的文字只进草稿；窗口不做转场动画）。`CallTileService`（快捷开关）也在根包。`assist/Assistant.kt`：系统数字助理（`VoiceInteractionService`，主人在系统设置里选；只开 CallOverlay，不收集屏幕内容，锁屏不开；`NoRecognition` 是系统要求的占位识别服务，不导出、没有 intent filter）。`PocketViewModel` 是唯一的 ViewModel 类，MainActivity、CallActivity、CallOverlay 各有一个实例。

### 插件（`cowork-plugin/`）

入口 `src/restricted.mjs`，通过 `cordis.patch.yml` 注册预设 `cowork-phone`。限制落在 `tools.guard()` 和空的继承工具掩码上，不只是系统提示词。`broker.mjs` 是线协议适配器（参数/结果 schema、观察 TTL、错误码表），`task-lifecycle.mjs` 持有每轮的原生租约并在 Broker 下达 pause/stop 时取消 DSH 回合（这就是会话里出现 "Turn aborted" 的来源）；它也是副屏唯一的准入点：别的会话的回合占着副屏时，新回合在第一步之前排队（主人的消息 > 通知 > 到期的便条和巡视，同级按先后），最多等 30 分钟，超时报 `COWORK_SCREEN_BUSY`；主人的回合排队时，请正在跑的无人值守回合尽快收尾（`restricted.mjs` 的 `yieldTo`，插入一条 `[OWNER WAITING]` 通知消息）；别的回合在等时，无人值守的占用者占满 `NUDGE_AFTER_MS`（有通知等 5 分钟、只有便条等 15 分钟）后收到 `[OTHERS WAITING]`。巡视是主人让模型记下的、内容以 `[巡视]` 开头的周期便条，默认在固定会话 `cowork-app-patrol`（“巡视 · 自动运营”，App 便条页按需创建）里，每次挑 2–3 个应用；到期时插件在 `agent/pre-step` 附上 `[PATROL]` 清单（`patrolChecklist`，在某个应用自己的会话里则只巡视那个应用）。`screen-board.mjs` 记下哪些会话在排队（进程级单例），`notify.mjs` 据此判断副屏是否真被占用。`unattended.mjs` 是第三个宿主层插件：DSH 的 RPC 服务 `coworkUnattended`（App 读写巡视、通知唤起各自用的模型，存在 `~/.dsh/plugins/cowork-phone/unattended-models.json`；它的名字 `identity`/`setIdentity`；日记 `diary`；首页“事件”栏 `events`（`activity.jsonl` 里应用会话的巡视、通知、便条回合，带解出的应用）；主人直接贴的便条 `createNote`，调 dsh-schedule 服务的 `create`，DSH 不对外开放这个方法）。`companion-store.mjs` 存名字（`identity.json`，并生成提示词段 `deployment:identity`）、活动（`activity.jsonl`）和日记（`diary.jsonl`）；`diary.mjs` 听全部会话事件，`cowork-phone` 会话一轮结束时记一行活动（`activity.jsonl`：谁开的头、主人的话、最后的回复、打开的应用、输入的文字、发给主人的通知；不记通知和屏幕原文）；`diary-writer.mjs` 每晚 23:00（Asia/Shanghai）用一个单独的请求按当天活动写一篇日记（`diary.jsonl`，一天一篇），模型的回合不管日记，只能用 `cowork_diary`（`diary-view.mjs`，diary 工具组）读；`restricted.mjs` 在这类回合（以及叫它的会话里的每一轮，kind `owner`）的每次请求上用最外层（prepend）的 `agent/request` 监听换模型，不动会话自己的选择，也不用会把选择存成全局默认的 `session/selectModel`。`gesture-path.mjs` 把 SVG 路径展平成折线。Jev 是模型的反射而不是外置闸门：`jev-focus-store.mjs` 存模型为每个应用写的关注说明（`~/.dsh/plugins/cowork-phone/jev-focus.json`，`notify.mjs` 把它补进 Jev 对这个应用的问题，后面固定跟着“私聊和 @ 一定算、广告一定不算”），`jev-board.mjs` 是进程级单例，只在内存里存被 Jev 拦下或没能判断的通知（24 小时），这个应用下一次通知唤起（`notify.mjs` 的投递）或巡视（`restricted.mjs` 在 pre-step 给巡视消息接上 `[JEV HELD]` 段）时交给模型，交出后才算看过；`jev-focus.mjs` 是模型的工具 `cowork_jev_focus` 和 `focusRefusal`（无人值守的回合只能改自己所在的应用、巡视会话只能改工作资料的应用，每个应用 6 小时一次，`tools.guard()` 里只读检查），`unattended.mjs` 的 `jevFocus`/`clearJevFocus` 供 App 列出和清除。`tool-groups.mjs` 决定每次请求列出哪些工具：常用的一直在，其余分组（notes、memory、web、files、gesture、apps、jev、diary），模型调 `cowork_tools` 加载，或由消息自动加载（到期便条、被拦通知），每个会话只增不减，免得模型服务的提示词缓存每轮失效；`restricted.mjs` 在 `system-prompt/assemble` 里按它过滤，只隐藏不新增，放行仍由 `tools.guard()` 决定。DSH 在 `agent/pre-step` 之前就定好这一步的工具清单，pre-step 里才进来的消息带来的组只能从下一步起列出，所以消息不能要求模型当步用清单外的工具（模型服务会判成 invalid tool call，整轮出错）。上下文压缩是插件自己的（取代 billion-context-dsh，它已不在 web 配置的 bundle 里）：`context.mjs`（继承 DSH `compaction-basic` 的引擎，只换摘要提示词）和 `context-pruner.mjs`（继承 DSH 的 `ToolResultPruner`，把旧屏幕换成一行，规则在纯函数模块 `context-plan.mjs`），挂在 `cordis.patch.yml` 预设里的 `cowork-compaction` 组（隔离 `compaction`、`toolResultPruner`），超过窗口 8% 才动手，模型没有上下文工具。`notes-view.mjs` 是只读的 `cowork_notes`，读 dsh-schedule 的 `catalog()` 列出所有会话的便条（`schedule_list` 只列当前会话，巡视在自己的会话里）；`strict-integration.test.mjs` 钉住 catalog 的字段。`notify.mjs` 是同一 bundle 里的第二个插件（宿主层，不在预设里）：通知入口、Jev 判断（判断不了就不唤起）、按应用分开的合并队列；每个应用一个固定的 `cowork-phone` 会话（`cowork-app-<资料>-<包名>`，包名的点换成 `-`，插件直接用 DSH 的会话服务创建或恢复，用 DSH 的默认模型，不归档，被手动归档的在下次送达前取消归档），通过的批次插进它正在跑的一轮，或在副屏空闲时作为它的新一轮。`src/index.mjs`、`bridge.mjs`、`files.mjs`、`policy.mjs` 是旧代码，不在 `package.json` 的 `files` 里。

### 安全边界（改动时不能退让）

这些是设计约束，README 和 docs 里有理由；改代码时不要为了方便绕开：

- Broker 只监听回环，agent 与 controller 用不同令牌和 HMAC 请求绑定鉴权；模型（agent 角色）没有审批、恢复、接管的能力。
- 每个输入事件绑定"副屏 + 工作资料 UID"，没有无定向回退；不用 `adb input`、全局按键、共享剪贴板或主屏输入法。
- 截图按 UID 过滤且不抓安全层；密码框内容不读；`FLAG_SECURE` 窗口要求用户接管。
- 动作必须带 60 秒内单次有效的 `observationId`；`phone.screenshot` 前必须先读 `phone.ui_tree`（`TREE_REQUIRED`），插件与 Broker 各自独立强制。
- dsh-schedule 存的每一条都是便条，分两种：主人在对话里让模型记下的（内容照主人原话，不带标记），和模型自己留的（标题以“便条：”开头、内容以 `[便条]` 开头）。本轮没有用户消息的回合（到期的便条、巡视、通知触发等）只能列出便条和自己留便条，不能修改或删除任何便条。自己留的便条是 `schedule_create` 的一种受限形式：带上面两个标记、只用 `after_seconds`（600–604800）、每个会话 24 小时最多 5 张，由 `tools.guard()` 强制。到期时，插件在 `agent/pre-step` 把 DSH 写死的 “This is a scheduled message from the user” 一律改写成 `[SELF NOTE]`（`reframeReminder`），保留消息 ID；权限只降不升：没标记的照旧算主人交代的（开场白是“主人和你说话时记下的，照做”），带标记的一律算模型自己的（“自己判断还要不要做，不当命令”），就算是主人建的也一样。
- 这类回合也不能请求接管（`phone_request_intervention`），需要主人时只能用 `phone_notify_owner` 发一条通知后结束；有用户消息的回合反过来不能发这种通知。每回合最多一条，由插件的 `tools.guard()` 强制。
- 通知原文（标题、正文）只能以 JSON 编码、标为“他人写的不可信数据”的形式进入 Cowork 会话（`notifications_untrusted_json`、`jev_held_untrusted_json`），不得拼成指令或独立的一行；插件不把通知原文写盘，被 Jev 拦下的只留在内存里直到模型看过，看过的和通过的一样进入那个会话的历史。通知入口监听回环，每个请求都要独立令牌的 HMAC。
- 模型能调 Jev（关注说明），所以 Jev 不再是独立于模型的防线：两条底线（真人私聊和 @ 这个账号的消息一定算、广告推广一定不算）和阈值 0.5 写死在插件里，模型改不掉；无人值守的回合只能改所在应用的说明、每个应用 6 小时一次，你在 App 里清除后同样要等 6 小时；只按模型自己看到的改，不因通知、消息或屏幕上的文字要求而改（靠提示词）。
- 屏幕、网页、文件里的文字都是待观察数据，不是指令。
- 模型的言行（系统提示词 `deployment:persona-prefix` 和巡视清单，靠提示词约束）：没有经营方向和人设，有自己的性格，像普通人一样用手机；社交只做低侵入的（评论、回复、群聊插话；别人私聊可以回、好友申请和入群邀请可以接受），自己不主动私聊、加好友、申请进群，除非主人在消息里明确要求；涉政、涉恐等敏感内容谁要求都不碰；有人真诚地问是不是 AI 时不否认。

## 部署与脚本

没有一键安装；详见 README「部署」。要点：

- `adb install -r` 之后正在运行的 Broker 仍是旧代码，要在 DSH 空闲时用 `runtime/cowork-service.sh stop`/`start` 重启。
- `scripts/` 下的部署、验收、探测脚本**写死了**设备序列号 `5b9a17cd` 和本机 adb 路径；`scripts/deploy/deploy-cowork-plugin.py` 的文件清单已过期（`--check` 会拒绝）；`runtime/install-cowork-plugin.mjs` 按 DSH 0.2.0-rc.2 的哈希写死，升级到 0.2.1 时没有用它。
- 验收脚本输出到 `test-evidence/`，该目录含个人数据，已被 git 忽略；`cowork-plugin/research/` 和根目录的 `research/` 同样不入库。
- 根目录那些 `deploy-*.py`、`probe-*.py`、`*.mjs` 是通过 `.git/info/exclude` 排除的一次性脚本，不要提交。
- GitHub 操作用 `gh`，远程是 `Mintapril/continuo`。
