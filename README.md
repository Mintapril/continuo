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
 │  │  创建并持有虚拟副屏 · UID 定向输入 · UID 过滤截图   │   │
 │  │  UiAutomation 读界面结构 · 接管 · 看门狗 · 实时流   │   │
 │  └──────┬────────────────────────────────────────────┘   │
 │         ▼                                                 │
 │  工作资料（userId 10）里的应用 ──► AI 独占副屏              │
 │                                                           │
 │  个人主屏：用户自己使用，不在 AI 操作范围内                  │
 └───────────────────────────────────────────────────────────┘
```

1. **DSH 的 `cowork-phone` 预设**（`cowork-plugin/`）：模型只能看到一组固定的手机工具、有限的文档读写和基础联网，没有 Shell、代码执行、包管理、MCP 和子代理。限制同时落在工具注册表和执行守卫上，不只是提示词。
2. **Broker**（`app/.../cowork/broker/`）：以 root 通过 `app_process` 运行的原生服务，监听 `127.0.0.1:3091`。它是所有安全边界的最终执行者：创建副屏、校验窗口与 UID、注入输入、截图、读界面结构、管理接管与暂停。
3. **Continuo App**：显式启用控制服务，提供任务岛、实时画面和接管界面。控制令牌与模型令牌分开，模型没有审批、恢复或接管的能力。
4. **工作资料**（`isolation/`）：由用户经系统确认创建，作为 AI 应用实例与存储的隔离层。Broker 只认已验证的资料（userId、serial、父用户、拥有者全部核对），不认应用包名或窗口标题。

### 隔离与安全模型

- **独占虚拟副屏**：Broker 创建一块 `OWN_FOCUS`、不抢主屏焦点、独立显示组、始终解锁的虚拟显示，并常驻保留。每轮任务首次调用工具时占用它，轮次结束只清空内容。主屏切换应用不会让它暂停；主屏的变化只在安全无法确认时才会触发暂停。
- **UID 定向输入**：每个触摸或按键事件在 InputDispatcher 层绑定到“副屏 + 目标应用 UID”，没有无定向回退，不用全局按键、共享剪贴板或主屏输入法。
- **UID 过滤截图与界面结构**：截图按 UID 过滤，不抓安全层；UiAutomation 只读副屏上属于工作资料的窗口，密码框内容为 `[REDACTED]`，安全窗口不导出。
- **没有应用白名单**：范围由“工作资料内可启动的应用”决定，而不是逐个包名放行。
- **结构优先**：模型调用 `phone_screenshot` 前必须先读 `phone_ui_tree`，否则被拒（`TREE_REQUIRED`）。插件与 Broker 各自独立强制。截图只用于结构不完整（WebView、自绘、游戏）的场景。
- **观察绑定与单次消费**：每次动作带短期 `observationId`，屏幕变化、超时或用过一次即失效；元素点击绑定界面树观察，截图不会让元素 ID 失效。
- **拒绝不等于结束**：参数、元素、转场类的拒绝（如 `NODE_STALE`、`SCREEN_SETTLING`）只返回下一步建议；只有越界类失败（外来任务占用副屏、身份校验失败、`LOCKED`、`APP_CONFLICT` 等）才暂停任务。
- **高影响操作交给用户**：付款、发消息、授权、删除等不自动提交；审批绑定动作、会话与屏幕版本，模型不能给自己审批。
- **文本输入限制**：仅支持键盘可表达的 ASCII 可打印字符，中文与 emoji 直接返回 `TEXT_UNSUPPORTED`，不会悄悄改走共享剪贴板。
- **不留痕**：工作资料里的启动器入口由 Broker 停用（可恢复），模型启动的应用不进最近任务，看门狗每 3 秒清理遗留任务记录。
- **鉴权**：Broker 只监听回环地址；Agent / Controller 使用请求绑定的 HMAC 双向挑战应答，令牌不经过 URL、日志或命令行。

> 工作资料不是虚拟机，也不是防恶意应用的完整沙箱；root 链路仍是共享的。屏幕、网页、消息中的文字一律视为待观察数据，不是指令。

### 用户体验

- **任务岛**：类灵动岛的悬浮胶囊，待机时是屏幕顶部的一颗黑色胶囊；任务运行时向两侧展开，左侧显示“正在使用 <应用>”，右侧显示最近的关键动作（打开应用、输入文字、请求接管等，由 Broker 原生生成，不调用模型）。点按展开，右侧是副屏实时预览。
- **实时画面与接管**：副屏画面经 GPU 直送硬件 H.264 编码器，通过 Broker 的 `/v1/stream` 推给任务岛和 Cowork 页面。全屏预览里可直接“接管操作”，触摸直通副屏，完成后“交还给 AI”；模型也可以主动请求接管。接管期间模型的观察与操作工具返回 `USER_CONTROL`，DSH 回合保持存活。
- **副屏虚拟导航**：副屏没有系统导航栏，由 Broker 提供 BACK / HOME（工作资料里的“AI 桌面”）/ RECENTS 三个虚拟键。
- **自动连接**：打开应用即自动启动控制服务并自愈重连；发布任务时如控制器心跳过期，Broker 会以 root 唤醒它。

### 模型可用的手机工具

共 17 个，模型可见名把点号换成下划线（如 `phone_tap`），线协议名保持 `phone.tap`：

| 类别 | 工具 |
|---|---|
| 状态 | `phone.status`、`phone.apps` |
| 观察 | `phone.ui_tree`、`phone.screenshot`、`phone.read_page`（自动翻页读长文） |
| 手势 | `phone.tap`、`phone.long_press`、`phone.swipe`、`phone.scroll` |
| 输入 | `phone.type_text`、`phone.key`（`BACK`/`ENTER`/`HOME`/`RECENTS`） |
| 应用与时序 | `phone.start`（可选）、`phone.launch_app`、`phone.wait` |
| 控制 | `phone.pause`、`phone.stop`、`phone.request_intervention` |

另有 `cowork_files_read/write/list`（固定 `/workspace/cowork`，只能新建文件）、`web_search`、`web_fetch`（仅公网 HTTP(S)），以及在记忆插件已安装时放行的 `memory_*` 与上下文压缩工具。完整契约见 [PROTOCOL.md](cowork-plugin/PROTOCOL.md) 与 [COWORK-CONTRACT.md](COWORK-CONTRACT.md)。

## 其他功能

- **Code 模式**：连接 DSH 的 `standard` 预设，是完整的手机端编码智能体对话——流式回复、可折叠的思考与工具调用、模型与推理强度选择、工作区与权限、提问面板、后台任务、任务列表（筛选、搜索、置顶、归档）。应用只有 Cowork 与 Code 两种任务模式，没有基础聊天。
- **记忆**：设置 → 记忆，读写手机侧 dsh-mneme 记忆库（概览、自动化开关、注入预算、条目增删改与归档、注入预览、存储回收）。Cowork 与 Code 共用同一个库。
- **运行环境**：设置 → 运行环境，启动、重启、停止、重建 DSH 的 Ubuntu chroot（调用固定动作的 `pocket-control.sh`，需要 root 授权）。
- **品牌与界面**：宣纸石青配色，开口 C + 输入光标的标志；深浅色主题；系统无衬线为界面字体，JetBrains Mono 为代码字体。

## 仓库结构

| 目录 / 文件 | 内容 |
|---|---|
| `app/` | Continuo 主应用：`ui/` 界面、`data/` DSH 接口与会话数据、`cowork/` 控制连接与 `broker/` 原生 Broker、`island/` 任务岛 |
| `isolation/` | “Cowork 隔离实验”：工作资料创建与资料拥有者应用（minSdk 33） |
| `fixture/` | 无网络、无权限的测试夹具应用，用于验证点击、长按、输入、滚动与安全窗口 |
| `platform-stubs/` | Android 16 隐藏 API 的编译期桩，仅供 Broker 编译，不打包 |
| `cowork-plugin/` | DSH `cowork-phone` 预设插件（Node.js ≥ 22）：严格工具目录、协议适配、受限文件与联网工具、任务生命周期 |
| `runtime/` | 手机端运行时脚本：`pocket-control.sh`、`cowork-service.sh`，以及插件安装器、备份与预检工具 |
| `deploy-*.py`、`cowork_auth.py` | 经 ADB 的部署脚本与 HMAC 认证客户端（令牌运行时生成，不写入仓库） |
| `cowork-*-test.*`、`probe-*.py` | 设备验收与探测脚本 |
| `docs/` | 设计文档与[更新日志](docs/CHANGELOG.md) |

## 构建与测试

环境：JDK 21、Gradle 8.13（AGP 8.13.2、Kotlin 2.2.21）、Android SDK 36。仓库不含 `local.properties`，需自行指向 SDK。

```powershell
.\test-unit.ps1            # 构建 debug APK，编译并运行 JVM 测试
.\test-unit.ps1 -SkipBuild # 仅运行测试
```

`test-unit.ps1` 默认工具链放在工作区上级目录的 `tools\android-build\`（JDK、SDK、Gradle），并用 JUnitCore 直接运行测试，以绕开 Gradle 测试 worker 在 Unicode 路径下的启动问题。换环境时请先改脚本里的路径。`app/` 约有 200 个 JVM 单元测试，覆盖协议契约、动作闸门、接管状态机、任务生命周期、任务岛策略等。

插件的离线测试：

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
4. `deploy-cowork-plugin.py` 把 `cowork-plugin/` 作为附加的 bundle 装入 DSH（带完整私有备份，且要求 DSH 空闲）。
5. 授予悬浮窗与通知权限；Broker 由 `runtime/cowork-service.sh` 经 root 启动，之后应用会自动连接与唤醒。

部署脚本中写死了测试设备序列号和本机 ADB 路径，换设备需要修改。

## 已知限制

- 依赖 root、手机本机 DSH 与手动创建的工作资料，仅在一加 Ace 3V 上验证。
- 工作资料不是完整沙箱：应用跨资料或跨显示的跳转只会被检测并暂停，不是系统级阻断；root 链路共享。
- 文本输入仅支持 ASCII；无障碍节点只读副屏结构，不用于输入。
- ColorOS 在用户从最近任务划掉应用时会强杀进程，控制器会断开，Broker 会在下次任务请求时重新唤醒；建议在最近任务里锁定应用。
- 副屏画面与结构不保证覆盖所有应用：WebView、游戏、自绘界面常缺少结构节点，只能依赖截图。
- 第三方真实应用的兼容性只做过抽样验证；付款、登录等流程应由用户接管。

## 文档

- [Cowork 与任务岛实施设计](docs/cowork-design.md)
- [主屏自由使用与后台并行：设计与验收](docs/cowork-concurrent-design.md)
- [创建隔离工作资料](docs/isolation-profile-setup.md)
- [Broker 协议 v1](cowork-plugin/PROTOCOL.md) · [插件说明](cowork-plugin/README.md) · [集成约定](COWORK-CONTRACT.md)
- [更新日志](docs/CHANGELOG.md)

> 设计文档里引用的 `test-evidence/` 实机证据（截图、会话记录等）含个人数据，未随仓库提交。
