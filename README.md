# Continuo（通奏）

**让 AI 在手机的独立副屏上操作应用，你继续正常使用主屏。**

Continuo 是一个原生 Android 客户端（Kotlin + Jetpack Compose），连接手机本机运行的 DSH 智能体服务。它的核心是 **Cowork 模式**：在 Android 工作资料（work profile）里开一块 AI 独占的虚拟副屏，AI 只在这块屏幕上读界面、点击、滑动、输入；个人主屏、你的应用和你的数据不在它的操作范围内。你可以随时在任务岛里看实时画面、暂停，或一键接管。

> **项目状态：** 这是一个面向特定设备（一加 Ace 3V，Android 16，ColorOS，KernelSU root）的个人项目，不是开箱即用的通用应用。它依赖 root、手机本机的 DSH 运行环境和一个手动创建的工作资料，部署步骤与设备强相关。第三方真实应用的兼容性只验证过一部分。详见[已知限制](#已知限制)。

- 包名 `dev.dsh.pocket`（沿用旧名 DSH Pocket 以保留数据与授权），当前版本 `0.7.0`（versionCode 9），`minSdk 28` / `targetSdk 36`。
- 许可证：尚未指定。仅内置字体 JetBrains Mono 遵循 SIL OFL（见 `app/src/main/assets/licenses/`）。

## Cowork：独立副屏

### 它解决什么问题

让 AI 代你操作手机，通常意味着它和你抢同一块屏幕：你一碰手机，任务就被打断；它一执行，你就没法用手机。Continuo 的做法是把 AI 的操作面彻底搬离主屏：

| | 常见做法 | Continuo Cowork |
|---|---|---|
| AI 操作的屏幕 | 主屏 | 独占的虚拟副屏 |
| 用户同时使用主屏 | 会打断任务 | 主屏任意切换，不影响任务 |
| AI 能碰到的应用 | 全部，或靠白名单 | 工作资料里的应用（与个人应用数据隔离） |
| 观察与干预 | 事后查看 | 任务岛实时画面，可随时暂停、接管、交还 |

### 工作原理

```
 ┌────────────────────────── 手机 ──────────────────────────┐
 │                                                          │
 │  Continuo App（个人空间）             DSH 服务            │
 │  ├ 对话 / 设置 / 记忆                 Node + Ubuntu chroot │
 │  ├ CoworkControllerService  ◄──HMAC──►  127.0.0.1:3080    │
 │  └ 任务岛（悬浮窗）                    └ cowork-phone 插件 │
 │         ▲   控制 / 状态 / 实时画面            │ 工具调用    │
 │         │                                    ▼            │
 │  ┌──────┴────────────────────────────────────────────┐   │
 │  │ Broker（root，app_process 启动，127.0.0.1:3091）   │   │
 │  │  常驻虚拟副屏 · UID 定向输入 · UID 过滤截图         │   │
 │  │  UiAutomation 读界面结构 · 接管 · 看门狗 · 实时流   │   │
 │  └──────┬────────────────────────────────────────────┘   │
 │         ▼                                                 │
 │  工作资料（userId 10）里的应用 ──► AI 独占副屏              │
 │                                                           │
 │  个人主屏：用户自己使用，不在 AI 操作范围内                  │
 └───────────────────────────────────────────────────────────┘
```

1. **DSH 的 `cowork-phone` 预设**（`cowork-plugin/`，入口 `src/restricted.mjs`）：模型只能看到一组固定的手机工具、有限的文档读写和基础联网，没有 Shell、代码执行、包管理、MCP 和子代理。限制同时落在工具注册表和执行守卫上，不只是提示词。插件还持有每轮任务的原生“租约”（`src/task-lifecycle.mjs`），见[一轮任务如何结束](#一轮任务如何结束turn-aborted-是什么)。
2. **Broker**（`app/.../cowork/broker/`）：以 root 通过 `app_process` 运行的原生服务，监听 `127.0.0.1:3091`。它是所有安全边界的最终执行者：持有副屏、校验窗口与 UID、注入输入、截图、读界面结构、管理接管与暂停。
3. **Continuo App**：控制服务（`CoworkControllerService`）、任务岛、实时画面和接管界面。控制令牌与模型令牌分开，模型没有审批、恢复或接管的能力。
4. **工作资料**（`isolation/`）：由用户经系统确认创建，作为 AI 应用实例与存储的隔离层。Broker 只认已验证的资料（userId、serial、父用户、拥有者及其签名全部核对），不认应用包名或窗口标题。

### 副屏的生命周期

- **常驻**：Broker 启动时创建一块虚拟显示（`DSH-Cowork-Profile`，宽 720、高按主屏比例取 1280–2000，密度按主屏换算，请求 120 Hz），带 `OWN_FOCUS`、`STEAL_TOP_FOCUS_DISABLED`、独立显示组与 `ALWAYS_UNLOCKED` 等标记，之后一直保留；丢失（如 system_server 重启）时由看门狗重建。
- **占用与释放**：每轮任务的第一个手机工具调用自动占用副屏（不需要 `phone_start`）；轮次结束只释放占用和撤销观察，**副屏上的应用和画面保留**到下一轮。只有模型调用 `phone_stop` 才清空副屏上的工作资料任务。
- **电源**：独立显示组意味着副屏有自己的电源组和自己的息屏超时，你在主屏上的操作不算它的活动，主屏亮着也不能让它保持唤醒。任务持有副屏期间，Broker 每 2 秒检查一次，必要时用 `wakeUpWithDisplayId` 唤醒副屏电源组并上报活动；无人占用时允许它休眠，下一轮占用时再唤醒。调用只指定副屏的显示 ID，设计上不点亮主屏（尚待真机确认，见[已知限制](#已知限制)）。（缺了这一步时，模型思考或等待超过息屏超时，副屏就会睡着：上面的应用全部停止，启动超时、画面永远“转场中”，最后整轮被暂停。）

### 一轮任务如何结束（“Turn aborted” 是什么）

插件在每轮任务的第一步向 Broker 申请一个原生租约（`/v1/task` 的 `begin`），之后每 0.5 秒轮询一次。当 Broker 发出 `pause` / `stop` 命令时，插件会取消这一轮 DSH 回合，会话里就出现 **Turn aborted**（`reason.kind` 为 `user`，即使不是你按的停止）。因此“反复 turn aborted”几乎总是 Broker 一侧决定暂停了任务。

- **不会结束任务的拒绝**：参数、元素与转场类，如 `NODE_STALE`、`STALE_OBSERVATION`、`SCREEN_SETTLING`、`INPUT_REJECTED`、`LAUNCH_TIMEOUT`、`TREE_REQUIRED`、`TEXT_UNSUPPORTED`。模型拿到错误码和下一步建议（`phone_wait` 后再读结构、请求接管等）继续做。窗口身份或安全探测超时（`SECURITY_UNAVAILABLE: Cannot verify…`）也按转场处理。分类集中在 `RefusalPolicy.java`。
- **会立即暂停任务的情况**：越界或身份类失败，如 AI 启动的应用跑出副屏（`APP_CONFLICT`）、资料身份或副屏身份变化、`SECURE_WINDOW`、`LOCKED`、控制器失联超过 15 秒（`CONTROLLER_OFFLINE`）、用户暂停或停止。
- **转场超时**：副屏连续 20 秒不可用（一直在转场、没有可验证的工作资料前台、外来任务清不掉）时，看门狗以 `SCREEN_UNRESPONSIVE` 暂停。这 20 秒按“当前占用者的一段连续转场”计时，换轮次、关闭工作区或任一次检查通过都会重新计时。
- **排查**：每次原生暂停或停止都会在 root 私有的 `/data/local/dsh-native/cowork/broker.log` 里留一行 `task pause requested: <原因码>`（只写码，不写界面内容）。该日志在每次 Broker 启动时被覆盖。

### 隔离与安全模型

- **独占虚拟副屏**：副屏不抢主屏焦点，主屏切换应用、看视频、玩游戏都不会让任务暂停；Broker 只校验副屏自身（窗口 UID、任务归属、身份），不看主屏在做什么。
- **UID 定向输入**：每个触摸或按键事件在 InputDispatcher 层绑定到“副屏 + 目标应用 UID”，没有无定向回退，不用全局按键、共享剪贴板或主屏输入法（副屏输入法策略为隐藏）。
- **UID 过滤截图与界面结构**：截图按 UID 过滤，不抓安全层；UiAutomation 只读副屏上属于工作资料的窗口，密码框内容为 `[REDACTED]`，带 `FLAG_SECURE` 的窗口不导出并要求用户接管。
- **没有应用白名单**：范围由“工作资料内可启动的应用”决定，而不是逐个包名放行。
- **结构优先**：模型调用 `phone_screenshot` 前必须先读 `phone_ui_tree`，否则被拒（`TREE_REQUIRED`）；结构不完整（`complete:false`）时可以直接截图。插件与 Broker 各自独立强制。
- **观察绑定与单次消费**：每次动作带短期 `observationId`（60 秒内有效），屏幕变化、超时或用过一次即失效；元素点击绑定界面树观察，中间截一张图不会让元素 ID 失效。
- **动作自带结果画面**：点击、长按、滑动、滚动、输入、`BACK`/`ENTER`/`HOME` 和 `phone_wait` 执行后，Broker 等画面稳定（最早 300 毫秒后读，两次读到相同内容即视为稳定，最多读 6 次），再把一份完整的 `phone_ui_tree` 观察放进回复的 `observation`（新的 `observationId` 与元素 ID），模型直接接着用，不必再调一次 `phone_ui_tree`。回复里的 `changed:false` 表示与模型上次读到的画面相比没有可见变化，`settled:false` 表示读取时画面仍在变化；读取失败时只给出 `observationError` 和提示，动作本身仍报告为已执行，避免模型误以为没点上而重复操作。`phone_launch_app`（冷启动通常接着 `phone_wait`）、`phone_read_page` 和 `RECENTS` 不附带画面。
- **单观察多动作（`phone_batch`）**：同一屏上的几个元素操作（填几个输入框、勾几个选项，最后一步可以是一次点击、`ENTER` 或 `BACK`）一次调用完成，2–5 步，只接受元素 ID，界面树必须完整。每一步执行前，Broker 都会核验三件事：窗口集合和前台 UID 仍是观察时的样子；上一步稳定后，它所操作元素以外的带文字内容没有变化；目标仍在原位，文字和描述与观察时一致。任何一项不满足就停下，返回 `completed`、`stoppedAt`、`stopReason` 和当时的画面，剩余步骤不执行，模型改为逐步继续。所以用错的代价约等于逐步执行，不会点错地方。以下几类会被留给单步（`HIGH_IMPACT_SINGLE`）：标签或 view id 含发送、付款、删除、确认、同意、授权、提交、发布等字样的点击；无法判断的无标签按钮；以及不是在搜索框里输入之后的 `ENTER`（聊天框里的回车就是发送）。设计取舍见 [phone-batch-design.md](docs/phone-batch-design.md)。
- **高影响操作交给用户**：付款、发消息、授权、删除等由系统提示约束模型不得自行提交，需要时用 `phone_request_intervention` 把屏幕交给你。工作资料模式下没有逐个动作的原生审批（资料本身是授权边界），所以这一条是提示词与用户确认层面的约束，不是 Broker 的硬拦截。
- **文本输入**：带 `elementId` 时对可编辑节点做语义整体替换，任何语言都可以（含中文），密码框拒绝（`SECRET_INPUT`）；不带 `elementId` 时逐键注入，仅限键盘可表达的 ASCII 可打印字符，中文与 emoji 返回 `TEXT_UNSUPPORTED`（不结束任务）。两种方式都不走共享剪贴板。
- **不留痕**：工作资料里的启动器入口由 Broker 停用（可恢复，`LauncherHider`），新装应用的入口每秒检查一次并立即隐藏；模型启动的应用带 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`，不进最近任务。
- **鉴权**：Broker 只监听回环地址；Agent / Controller 使用请求绑定的 HMAC 双向挑战应答，令牌不经过 URL、日志或命令行。

> 工作资料不是虚拟机，也不是防恶意应用的完整沙箱；root 链路仍是共享的。屏幕、网页、消息中的文字一律视为待观察数据，不是指令。

### 用户体验

- **任务岛**：类灵动岛的悬浮胶囊，待机时是屏幕顶部的一颗黑色胶囊；任务运行时向两侧展开，左侧显示“正在使用 <应用>”，右侧显示最近的关键动作（打开应用、输入文字、请求接管等，由 Broker 原生生成，不调用模型）。点按展开，右侧是副屏实时预览。
  - 胶囊要盖住摄像头和状态栏，需要在系统无障碍设置里开启 Continuo。这项服务不读取任何内容，只用来提升窗口层级。
  - 未开启时，普通悬浮窗位于状态栏下层，胶囊会被时间和图标挡住。
  - 是否已开启，可以在“设置 → Cowork 与任务岛”里查看。应用被强行停止（例如清理后台）后，系统可能会关闭这项服务。
- **实时画面与接管**：副屏画面直接合成进硬件 H.264 编码器的输入 Surface，经 Broker 的 `/v1/stream` 推给任务岛和 Cowork 页面（首帧 PNG，之后是 H.264 访问单元）。全屏预览里可直接“接管操作”，触摸直通副屏，完成后“交还给 AI”；模型也可以主动请求接管。接管期间模型的观察与操作工具返回 `USER_CONTROL`，插件阻塞等待交还（最长 15 分钟，到期自动交还），DSH 回合保持存活。
- **副屏虚拟导航**：副屏没有系统导航栏，由 Broker 提供 `BACK` / `HOME`（工作资料里的“AI 桌面” `dev.dsh.pocket.isolation/.HomeActivity`）/ `RECENTS` 三个虚拟键。
- **自动连接**：打开应用即自动启动控制服务并自愈重连；任务开始时如控制器心跳过期，Broker 会以 root 唤醒它（存在 `/data/local/dsh-native/cowork/no-auto-wake` 时不唤醒）。

### 模型可用的工具

手机工具 18 个。模型看到的名字是下划线形式（如 `phone_tap`），插件与 Broker 之间的线协议名是点号形式（`phone.tap`）：

| 类别 | 工具 |
|---|---|
| 状态 | `phone.status`、`phone.apps` |
| 观察 | `phone.ui_tree`、`phone.screenshot`、`phone.read_page`（自动下翻 1–12 页，读长文） |
| 手势 | `phone.tap`、`phone.long_press`、`phone.swipe`、`phone.scroll` |
| 输入 | `phone.type_text`、`phone.key`（`BACK`/`ENTER`/`HOME`/`RECENTS`）、`phone.batch`（同一观察上的 2–5 步元素操作） |
| 应用与时序 | `phone.start`（可选）、`phone.launch_app`（最多等 8 秒上屏，超时返回 `LAUNCH_TIMEOUT` 但保留已启动的应用）、`phone.wait`（≤10 秒） |
| 控制 | `phone.pause`、`phone.stop`、`phone.request_intervention` |

另有 `cowork_files_read/write/list`（固定 `/workspace/cowork`，只能新建文件）、`web_search`、`web_fetch`（仅公网 HTTP(S)），共 23 个。宿主装有对应插件时，还会放行上下文管理工具（`compress`、`decompress`、`search_context`、`acp_status`）和记忆工具（`memory_search/get/list/save/update/archive`），但只认宿主的全局定义，带文件参数的调用一律拒绝。

## 其他功能

- **Code 模式**：连接 DSH 的 `standard` 预设，是完整的手机端编码智能体对话——流式回复、可折叠的思考与工具调用、模型与推理强度选择、工作区与权限、提问面板、后台任务、任务列表（筛选、搜索、置顶、归档）。应用只有 Cowork 与 Code 两种任务模式，没有基础聊天。
- **设置**：中心页分为 连接与模式、Cowork、外观与反馈、记忆、运行环境、关于。
- **记忆**：读写手机侧 dsh-mneme 记忆库（概览、自动化开关、注入预算、条目增删改与归档、注入预览、存储回收），数据走 DSH 自身的 `/api/dsh-mneme/` 路由。Cowork 与 Code 共用同一个库。
- **运行环境**：启动、重启、停止、重建 DSH 的 Ubuntu chroot（调用固定动作的 `runtime/pocket-control.sh`，需要 KernelSU 授权；重建前若仍有进程留在 chroot 中会被拒绝）。
- **品牌与界面**：宣纸石青配色，开口 C + 输入光标的标志；深浅色主题；系统无衬线为界面字体，JetBrains Mono 为代码字体。

## 仓库结构

| 目录 / 文件 | 内容 |
|---|---|
| `app/` | Continuo 主应用：`ui/` 界面、`data/` DSH 接口与会话数据、`cowork/` 控制连接、`island/` 任务岛 |
| `app/.../cowork/broker/` | 原生 Broker。`BrokerMain` 是 HTTP 入口与看门狗，`ProfileWorkspace` 是工作资料副屏驱动，`ProfileStructure` 读界面结构，`TaskLifecycle` / `Takeover` / `RefusalPolicy` 等是可在 JVM 上测试的纯逻辑。`AndroidWorkspace` 是早期共享用户驱动，`*ProbeMain` 是探测入口，都已不在主流程中 |
| `isolation/` | “Cowork 隔离实验”：工作资料创建、资料拥有者与“AI 桌面”（minSdk 33，版本 `0.4-experiment`） |
| `fixture/` | 无网络、无权限的测试夹具应用，用于验证点击、长按、输入、滚动与安全窗口 |
| `platform-stubs/` | Android 16 隐藏 API 的编译期桩，仅供 Broker 编译，不打包 |
| `cowork-plugin/` | DSH `cowork-phone` 预设插件（Node.js ≥ 22）。生产入口是 `src/restricted.mjs`（`package.json` 的 `exports`），`src/index.mjs`、`bridge.mjs`、`files.mjs`、`policy.mjs` 是早期实现，不在发布文件列表中 |
| `runtime/` | 手机端运行时脚本：`pocket-control.sh`、`cowork-service.sh`，以及插件安装器、备份与预检工具 |
| `scripts/` | 经 ADB 的运维脚本，全部绑定测试设备：`deploy/` 部署（`deploy-cowork.py`、`deploy-cowork-plugin.py`）、`acceptance/` 夹具与运行时验收、`probes/` 真机探测（结构优先读屏、实时画面帧率），`cowork_auth.py` 是它们共用的 HMAC 认证客户端（令牌运行时读取，不写入仓库） |
| `docs/` | 设计文档与[更新日志](docs/CHANGELOG.md) |

另外，`cowork-plugin/t-tmp.mjs`（一份早期测试的临时副本）和 `cowork-plugin/` 下一个名为 `{console.log([...m.KEEPS_OBSERVATION].join('` 的空文件（误输入的命令留下的）也被提交进了版本库，与功能无关。

## 构建与测试

环境：JDK 21、Gradle 8.13（AGP 8.13.2、Kotlin 2.2.21）、Android SDK 36。仓库不含 `local.properties`，需自行指向 SDK。

```powershell
.\test-unit.ps1            # 构建 debug APK，编译并运行 JVM 测试
.\test-unit.ps1 -SkipBuild # 仅运行测试
```

- `test-unit.ps1` 默认工具链放在仓库上两级目录的 `tools\android-build\`（JDK、SDK、Gradle），换环境时请先改脚本里的路径。它用 JUnitCore 直接运行测试（绕开 Gradle 测试 worker 在 Unicode 路径下的启动问题），测试类是脚本里显式列出的，新增测试类要加进列表。
- 请在仓库根目录运行：`BrokerSourceTest` 按当前目录查找 Broker 源码。
- 当前 209 个 JVM 单元测试，覆盖协议契约、动作闸门、拒绝分类与转场计时、接管状态机、任务生命周期、任务岛策略、会话记录解析等。Broker 依赖 Android 框架，无法在 JVM 上加载，所以可测的逻辑都拆在纯类里。
- 如果 Kotlin 编译守护进程无法创建临时文件（受限环境里会出现 `AccessDeniedException …kotlin-compiler-…alive`），可把 `TEMP`/`TMP` 指向仓库内被忽略的 `.kctmp\`，并给 Gradle 加 `-Pkotlin.compiler.execution.strategy=in-process`。

插件的离线测试（当前 269 个）：

```powershell
cd cowork-plugin
node --test test/strict-*.test.mjs
```

其中 `strict-integration` 依赖从已安装的 DSH 中提取的运行时副本（`cowork-plugin/research/`）。该目录是调研转储，未随仓库提交，因此这部分集成测试在干净检出上无法直接运行。

## 部署概览

没有一键安装。大致流程（细节与风险见各脚本头部说明与 [docs](docs/)）：

1. 手机已 root（KernelSU），手机本机运行 DSH（`127.0.0.1:3080`，Ubuntu chroot）。
2. 在手机上由用户经系统确认创建工作资料，资料拥有者为 `dev.dsh.pocket.isolation`，见 [创建说明](docs/isolation-profile-setup.md)。应用不会自动创建或删除资料。
3. `adb install -r` 安装 Continuo；不卸载、不清数据。
4. `scripts/deploy/deploy-cowork-plugin.py` 把 `cowork-plugin/` 作为附加的 bundle 装入 DSH（带完整私有备份，且要求 DSH 空闲）。注意：该脚本的文件清单仍含已删除的 `aux-summary.mjs`、`summary-transport.mjs`，并读取未入库的预检证据 `test-evidence/cowork/preflight-v3.json`，目前 `--check` 会拒绝执行，需要先更新清单与固定哈希。
5. 授予悬浮窗与通知权限；Broker 由 `runtime/cowork-service.sh start` 经 root 启动（从已安装 APK 加载），之后应用会自动连接与唤醒。

**更新 Broker 代码**：Broker 进程在启动时加载 APK，`adb install -r` 之后正在运行的 Broker 仍是旧代码，需要在 DSH 空闲时用 `cowork-service.sh stop` / `start` 重启它（会重建常驻副屏）。可以对比 `broker.pid` 进程的启动时间与 APK 的安装时间来确认。

`scripts/` 中的脚本写死了测试设备序列号和本机 ADB / Node 路径，换设备需要修改；用到 `test-evidence/` 的脚本会把结果写到该本地目录（已被 .gitignore 排除）。`scripts/acceptance/` 里的两个 `.mjs` 还依赖工作区同级的 `dsh-android/repair-model-rpc.mjs`，不在本仓库内。

## 已知限制

- 依赖 root、手机本机 DSH 与手动创建的工作资料，仅在一加 Ace 3V 上验证。
- 工作资料不是完整沙箱：应用跨资料或跨显示的跳转只会被检测并暂停，不是系统级阻断；root 链路共享。
- 应用跳转到不在工作资料里的系统页面时，副屏上没有可验证的工作资料前台，Broker 只能当作转场处理，20 秒后暂停；这类页面需要用户自己处理。系统权限弹窗通常拒绝注入的点击（`INPUT_REJECTED`），应交给用户接管。
- 副屏电源组保活（见[副屏的生命周期](#副屏的生命周期)）是 2026-10-18 新增的修复，尚待真机验收；任务持有副屏期间即使主屏熄灭，系统也可能把设备视为“交互中”（`isInteractive` 为真）。
- 不带 `elementId` 的文本输入仅支持 ASCII；无障碍节点只读副屏结构，语义输入只用于带 `elementId` 的整体替换。
- ColorOS 在用户从最近任务划掉应用时会强杀进程，控制器会断开，Broker 会在下次任务请求时重新唤醒；建议在最近任务里锁定应用。
- 副屏画面与结构不保证覆盖所有应用：WebView、游戏、自绘界面常缺少结构节点，只能依赖截图。
- 第三方真实应用的兼容性只做过抽样验证；付款、登录等流程应由用户接管。

## 文档

- [Cowork 与任务岛实施设计](docs/cowork-design.md)
- [主屏自由使用与后台并行：设计与验收](docs/cowork-concurrent-design.md)
- [创建隔离工作资料](docs/isolation-profile-setup.md)
- [Broker 协议 v1](cowork-plugin/PROTOCOL.md) · [插件说明](cowork-plugin/README.md) · [集成约定](COWORK-CONTRACT.md)
- [更新日志](docs/CHANGELOG.md)

> 以上文档写于不同阶段，部分细节已落后于代码，以代码为准：插件说明仍写“未部署、16 个手机工具”，集成约定写启动等待 10 秒、轮次结束清空副屏内容（代码分别是 8 秒、保留内容）。设计文档里引用的 `test-evidence/` 实机证据（截图、会话记录等）含个人数据，未随仓库提交。
