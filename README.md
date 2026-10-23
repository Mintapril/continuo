# Continuo（通奏）

**让 AI 在手机的独立副屏上操作应用，你继续正常使用主屏。**

Continuo 是一个原生 Android 客户端（Kotlin + Jetpack Compose），连接手机本机运行的 DSH 智能体服务（`@deepseek-ai/dsh-*`）。核心是 **Cowork 模式**：在 Android 工作资料（work profile）里开一块 AI 独占的虚拟副屏。AI 只在这块屏幕上读界面、点击、滑动、输入；个人主屏、你的应用和数据不在它的操作范围内。你可以随时在任务岛里看实时画面、暂停，或一键接管。

> **项目状态**：面向特定设备（一加 Ace 3V，Android 16，ColorOS，KernelSU root）的个人项目，不是开箱即用的通用应用。依赖 root、手机本机的 DSH 运行环境和一个手动创建的工作资料。见[局限](#局限)。

包名 `dev.dsh.pocket` · 版本 `0.7.0`（versionCode 9）· `minSdk 28` / `targetSdk 36` · [MIT](LICENSE)

## 为什么

让 AI 代你操作手机，通常意味着它和你抢同一块屏幕：你一碰手机，任务就被打断；它一执行，你就没法用手机。

| | 常见做法 | Continuo Cowork |
|---|---|---|
| AI 操作的屏幕 | 主屏 | 独占的虚拟副屏 |
| 用户同时使用主屏 | 会打断任务 | 随意使用，不影响任务 |
| AI 能碰到的应用 | 全部，或靠白名单 | 工作资料里的应用（与个人数据隔离） |
| 观察与干预 | 事后查看 | 实时画面，随时暂停、接管、交还 |

## 技术路径

```
 ┌────────────────────────── 手机 ──────────────────────────┐
 │  Continuo App（个人空间）             DSH 服务            │
 │  ├ 对话 / 设置 / 记忆                 Node + Ubuntu chroot │
 │  ├ CoworkControllerService  ◄──HMAC──►  127.0.0.1:3080    │
 │  └ 任务岛（悬浮窗）                    └ cowork-phone 插件 │
 │         ▲   控制 / 状态 / 实时画面            │ 工具调用    │
 │         │                                    ▼            │
 │  ┌──────┴────────────────────────────────────────────┐   │
 │  │ Broker（root，app_process，127.0.0.1:3091）        │   │
 │  │  常驻虚拟副屏 · UID 定向输入 · UID 过滤截图         │   │
 │  │  UiAutomation 读界面结构 · 接管 · 看门狗 · 实时流   │   │
 │  └──────┬────────────────────────────────────────────┘   │
 │         ▼                                                 │
 │  工作资料（userId 10）里的应用 ──► AI 独占副屏              │
 │  个人主屏：用户自己使用，不在 AI 操作范围内                  │
 └───────────────────────────────────────────────────────────┘
```

| 组件 | 作用 |
|---|---|
| **DSH `cowork-phone` 插件**（`cowork-plugin/`） | 给模型一组固定的手机工具、受限的文件读写和基础联网，没有 Shell、代码执行、MCP 和子代理。限制落在工具注册表和执行守卫上，不只是提示词。插件负责把模型参数转成 Broker 协议，并持有每轮任务的原生租约。 |
| **Broker**（`app/.../cowork/broker/`） | 以 root 通过 `app_process` 从已安装的 APK 启动的原生服务，是所有安全边界的最终执行者：持有副屏、校验窗口与 UID、注入输入、截图、读界面结构、管理暂停与接管。 |
| **Continuo App** | 控制服务、任务岛、实时画面与接管界面，以及 Code 模式、记忆与运行环境管理。控制令牌与模型令牌分开，模型没有审批、恢复或接管的能力。 |
| **工作资料**（`isolation/`） | 由用户经系统确认创建，作为 AI 应用实例与存储的隔离层。Broker 只认已验证的资料（userId、serial、父用户、拥有者及签名），不认包名或窗口标题。 |

关键做法：

- **虚拟副屏**：常驻、带独立焦点和显示组的虚拟显示，不抢主屏焦点，主屏怎么用都不会打断任务。
- **UID 定向输入**：每个触摸、按键事件绑定"副屏 + 目标应用 UID"，没有无定向回退，不用全局按键、共享剪贴板或主屏输入法。
- **UID 过滤截图与结构**：截图按 UID 过滤且不抓安全层；UiAutomation 只读副屏上工作资料的窗口，密码框内容被遮蔽。
- **结构优先，观察绑定**：模型先读紧凑的界面树并按元素 ID 操作，每个动作的回复自带稳定后的新画面；动作必须引用 60 秒内、单次有效的 `observationId`，屏幕变了就失效。截图只在结构不够用时使用。
- **界面不落主屏**：保留副屏锚点、关闭工作资料应用的后台弹窗资格，加定时兜底迁移。一轮结束后 AI 桌面盖住副屏，应用退到后台，照常发通知。
- **实时画面**：副屏直接合成进硬件 H.264 编码器，推给任务岛与全屏预览。

实现细节（副屏生命周期、任务暂停原因、弹窗闸门、手势时序、Android 16 隐藏 API 桩等）见 [Cowork 实现细节](docs/cowork-internals.md)。

## 功能

**Cowork（AI 操作手机）**：22 个手机工具，外加受限文件读写与网页工具，共 27 个。

| 类别 | 工具 |
|---|---|
| 观察 | `phone_ui_tree`、`phone_screenshot`（可局部放大）、`phone_read_page`（自动下翻读长文） |
| 操作 | `phone_tap`、`phone_long_press`、`phone_swipe`、`phone_scroll`、`phone_type_text`、`phone_key`、`phone_batch`（同屏 2–5 步元素操作）、`phone_gesture`（任意轨迹与两指手势） |
| 应用与控制 | `phone_status`、`phone_apps`、`phone_start`、`phone_launch_app`、`phone_wait`、`phone_pause`、`phone_stop`、`phone_request_intervention`、`phone_notify_owner`、`phone_grant_permission`、`phone_uninstall_app` |

- **任意轨迹手势**：用 SVG 路径描述笔画（含贝塞尔曲线与圆弧）、两指捏合与旋转，可用于画画、签名、拖拽。
- **局部放大截图**：裁出一块区域放大最多 8 倍，四边标屏幕坐标刻度，便于精确定位。
- **任务岛**：类灵动岛的悬浮胶囊，显示当前应用与最近动作，展开看实时画面；可随时暂停、接管、交还。
- **接管时打字**：副屏不弹输入法；接管时点中输入框会弹出本机键盘，文字经接管通道发到副屏。
- **自动化任务**：基于 DSH 内置的 `dsh-schedule`。模型用 `schedule_*` 安排一次性、间隔、每天、每周或 cron 任务，到期发回原会话执行；提醒唤起的回合不能增删改任务。App 的"自动化"页可查看和修改。
- **通知触发**：Broker（root）读取并合并通知，锁屏时也不会被系统冻结；判断模型 Jev（TypeSafe，经 OpenRouter）认为有意义才唤起 Cowork，送进该应用的固定会话（如"QQ · 自动运营"，在任务列表的“自动运营”筛选里）。应用在副屏前台时不发通知，由 Broker 在模型思考时看着副屏补上。在设置 → 通知触发里开关、排除应用、填密钥，详见[实现细节](docs/cowork-internals.md#通知触发)。
- **AI 经营副屏账号**：工作资料里的通讯、社交账号是专门给 AI 用的，回消息、发动态、加好友、装卸应用、开权限都由它自行决定（开权限和卸载由 Broker 以 root 完成，因为系统弹窗不接受注入点击）。只有付款、账号安全和清空聊天记录要你决定。需要你时，你派的任务会请你接管；通知或定时唤起的回合则给你发一条通知后结束。这部分主要靠提示词约束，接管与通知的分工由插件强制。

**其他**：

- **Code 模式**：连接 DSH 的 `standard` 预设，是完整的手机端编码智能体对话（流式回复、思考与工具调用、后台任务、任务列表）。
- **运行中发消息**：会话运行时新消息直接插进当前这一轮；按停止后，排队的最早一条文字消息自动补发（带附件的需重发）。
- **折叠过程**：一轮结束后，思考、工具调用和中间回复折叠成一项，只留你的消息、报错和结论。
- **记忆**：读写手机侧 dsh-mneme 记忆库，Cowork 与 Code 共用。
- **运行环境**：启动、重启、停止、重建 DSH 的 Ubuntu chroot（需要 KernelSU 授权）。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `app/` | Continuo 主应用：`ui/` 界面、`data/` DSH 接口、`cowork/` 控制连接、`island/` 任务岛、`notify/` 通知触发 |
| `app/.../cowork/broker/` | 原生 Broker。入口 `BrokerMain`，副屏驱动 `ProfileWorkspace`；`TaskLifecycle`、`RefusalPolicy`、`StrokePath`、`ScreenZoom` 等是可在 JVM 上测试的纯逻辑 |
| `isolation/` | 工作资料创建、资料拥有者与"AI 桌面" |
| `fixture/` | 无网络、无权限的测试夹具应用 |
| `platform-stubs/` | Android 16 隐藏 API 的编译期桩 |
| `cowork-plugin/` | DSH `cowork-phone` 插件（Node.js ≥ 22），入口 `src/restricted.mjs`；`src/notify.mjs` 是同一 bundle 里的通知入口插件 |
| `runtime/` | 手机端运行时脚本：`cowork-service.sh`、`pocket-control.sh`，插件安装与预检工具 |
| `scripts/` | 经 ADB 的部署、验收与探测脚本（绑定测试设备） |
| `docs/` | 设计文档与更新日志 |

不在主流程中的旧代码：Broker 的 `AndroidWorkspace` 与 `*ProbeMain`，插件的 `src/index.mjs`、`bridge.mjs`、`files.mjs`、`policy.mjs`。另有两个误提交的无关文件：`cowork-plugin/t-tmp.mjs`，以及 `cowork-plugin/` 下一个以 `{console.log(` 开头的空文件。

## 构建与测试

环境：JDK 21、Gradle 8.13（AGP 8.13.2、Kotlin 2.2.21）、Android SDK 36。仓库不含 `local.properties`。

```powershell
.\test-unit.ps1            # 构建 debug APK，编译并运行 JVM 测试
.\test-unit.ps1 -SkipBuild # 仅运行测试；请在仓库根目录运行
```

```powershell
cd cowork-plugin
node --test test/strict-*.test.mjs   # 插件离线测试
```

- `test-unit.ps1` 默认从仓库上两级的 `tools\android-build\` 取工具链，换环境需改路径；测试类在脚本里显式列出，新增测试类要加进去。
- 受限环境里 Kotlin 编译守护进程可能报 `AccessDeniedException …alive`：把 `TEMP`/`TMP` 指向仓库内被忽略的 `.kctmp\`，并给 Gradle 加 `-Pkotlin.compiler.execution.strategy=in-process`。
- 插件的 `strict-integration` 测试依赖未入库的 DSH 运行时副本（`cowork-plugin/research/`），干净检出上无法完整运行。

## 部署

没有一键安装，以下步骤与设备强相关：

1. 手机已 root（KernelSU），本机运行 DSH 0.2.1-rc.2（Ubuntu chroot，`127.0.0.1:3080`；插件声明的版本须与之一致，见[升级说明](docs/cowork-internals.md#dsh-版本与升级)）。
2. 由用户经系统确认创建工作资料，资料拥有者为 `dev.dsh.pocket.isolation`，见[创建说明](docs/isolation-profile-setup.md)。
3. `adb install -r` 安装 Continuo，授予悬浮窗与通知权限。
4. 把 `cowork-plugin/` 装进 DSH 的插件目录，替换前先备份并核对哈希，DSH 停止时替换。
5. 用 root 运行 `runtime/cowork-service.sh start` 启动 Broker，之后应用会自动连接与唤醒。

更新时注意：

- **Broker 代码**：`adb install -r` 之后，正在运行的 Broker 仍是旧代码，需要在 DSH 空闲时用 `cowork-service.sh stop` / `start` 重启，并核对其 `CLASSPATH` 与 `pm path dev.dsh.pocket` 一致。
- **插件与 APK 要一起更新**：二者的协议须匹配，否则新参数会被另一侧拒绝。
- **`scripts/` 已过期且绑定设备**：写死了设备序列号和本机路径；`scripts/deploy/deploy-cowork-plugin.py` 的文件清单需先更新才能用。

## 局限

- 依赖 root、手机本机 DSH 与手动创建的工作资料，只在一加 Ace 3V 上验证过。
- 工作资料不是虚拟机，也不是防恶意应用的完整沙箱；root 链路共享。跨资料、跨显示的跳转只会被检测并处理，不是系统级阻断。
- 应用跳到工作资料以外的系统页面时，Broker 当作转场，20 秒后暂停。系统弹窗通常拒绝注入点击：权限和卸载有专门工具，其他弹窗需要你接管。
- WebView、游戏、自绘界面常缺少结构节点，只能依赖截图；第三方应用只做过抽样验证。
- 不带元素 ID 的文本输入仅支持 ASCII；带元素 ID 时可输入任意语言。
- 少数系统级启动（通知、闹钟等）仍可能让工作资料界面短暂出现在主屏，由兜底迁移回副屏。
- ColorOS 从最近任务划掉应用时会强杀进程；建议在最近任务里锁定 Continuo。
- 副屏电源组保活尚待真机长时间验收。
- 接管打字是代理输入：拼音组合中的文字上屏后才发送，密码框只能输入键盘字符，不支持光标定位和选字。
- 自动化任务依赖 DSH 常驻。到期时副屏被占用、上一轮在等你或执行器唤不醒，这次就失败且不重试。时间按 DSH 进程的时区。
- 通知触发依赖 Broker 和 DSH 常驻。开启后所有未排除应用的通知都会发给 OpenRouter/TypeSafe，通过的还会进入模型服务，**建议排除银行、验证码类应用**。其他限制见[实现细节](docs/cowork-internals.md#已知限制)。

## 文档

- [Cowork 实现细节](docs/cowork-internals.md)
- [Cowork 与任务岛实施设计](docs/cowork-design.md) · [主屏自由使用与后台并行](docs/cowork-concurrent-design.md) · [phone_batch 设计](docs/phone-batch-design.md)
- [创建隔离工作资料](docs/isolation-profile-setup.md)
- [Broker 协议 v1](cowork-plugin/PROTOCOL.md) · [插件说明](cowork-plugin/README.md) · [集成约定](COWORK-CONTRACT.md)
- [更新日志](docs/CHANGELOG.md)

部分设计文档写于早期，细节以代码为准，例如插件说明里的工具数、集成约定里的启动等待时间。设计文档引用的实机证据（`test-evidence/`）含个人数据，未随仓库提交。

## 许可证

[MIT](LICENSE)。内置字体 JetBrains Mono 遵循 SIL OFL 1.1（见 `app/src/main/assets/licenses/`）。
