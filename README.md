# Continuo（通奏）

**让 AI 在手机的独立副屏上操作应用，你继续正常使用主屏。**

Continuo 是一个原生 Android 客户端（Kotlin + Jetpack Compose），连接手机本机运行的 DSH 智能体（`@deepseek-ai/dsh-*`）。核心是 **Cowork 模式**：在 Android 工作资料里开一块 AI 独占的虚拟副屏，AI 只在这块屏上读界面、点击、输入，碰不到你的主屏、应用和数据。AI 有你起的名字和头像，下拉通知栏就能看它在干什么、叫它、给它留便条。

> **项目状态**：只面向一加 Ace 3V（Android 16，ColorOS，KernelSU root）的个人项目，不是开箱即用的应用，依赖 root、手机本机的 DSH 和手动创建的工作资料。见[局限](#局限)。

包名 `dev.dsh.pocket` · 版本 `0.8.1`（versionCode 11）· `minSdk 28` / `targetSdk 36` · [MIT](LICENSE)

## 为什么

让 AI 代你操作手机，通常意味着它和你抢同一块屏幕：你一碰手机，任务就被打断；它一执行，你就没法用手机。

| | 常见做法 | Continuo Cowork |
|---|---|---|
| AI 操作的屏幕 | 主屏 | 独占的虚拟副屏 |
| 你同时用主屏 | 会打断任务 | 随意用，不影响任务 |
| AI 能碰到的应用 | 全部，或靠白名单 | 工作资料里的应用，与个人数据隔离 |
| 观察与干预 | 事后查看 | 实时画面，随时暂停、接管、交还 |

## 架构

```
 ┌────────────────────────── 手机 ──────────────────────────┐
 │  Continuo App（个人空间）             DSH 服务            │
 │  ├ 首页 / 设置 / 记忆                 Node + Ubuntu chroot │
 │  ├ CoworkControllerService  ◄──HMAC──►  127.0.0.1:3080    │
 │  └ 状态卡 / 叫它 / 看画面              └ cowork-phone 插件 │
 │         ▲   控制 / 状态 / 实时画面            │ 工具调用    │
 │         │                                    ▼            │
 │  ┌──────┴────────────────────────────────────────────┐   │
 │  │ Broker（root，app_process，127.0.0.1:3091）        │   │
 │  │  常驻虚拟副屏 · UID 定向输入 · UID 过滤截图         │   │
 │  │  UiAutomation 读界面结构 · 接管 · 看门狗 · 实时流   │   │
 │  └──────┬────────────────────────────────────────────┘   │
 │         ▼                                                 │
 │  工作资料（userId 10）里的应用 ──► AI 独占副屏              │
 │  个人主屏：你自己用，不在 AI 操作范围内                      │
 └───────────────────────────────────────────────────────────┘
```

| 组件 | 作用 |
|---|---|
| **`cowork-phone` 插件**（`cowork-plugin/`） | 给模型一组固定的手机工具、受限的文件读写和网页工具，没有 Shell、代码执行和子代理。限制落在工具注册和执行守卫上，不只靠提示词。 |
| **Broker**（`app/.../cowork/broker/`） | root 经 `app_process` 启动的原生服务，所有安全边界的最终执行者：持有副屏、注入输入、截图、读界面、管理暂停与接管。 |
| **Continuo App** | 状态卡、叫它和留便条的半屏、首页、全屏看画面与接管、设置。控制令牌与模型令牌分开，模型不能审批、恢复或接管。 |
| **工作资料**（`isolation/`） | 你经系统确认创建的隔离层。Broker 只认验证过的资料，不认包名或窗口标题。 |

关键做法：

- **虚拟副屏**：常驻、焦点独立，主屏怎么用都不打断任务。
- **按 UID 输入和截图**：每个输入事件绑定“副屏 + 应用 UID”，没有无定向回退；截图按 UID 过滤、不抓安全层，密码框内容被遮蔽。
- **结构优先**：模型按界面树里的元素操作，每个动作必须引用 60 秒内、只能用一次的观察；输入后界面变化不大时只发变化。截图只在结构不够用时用。
- **实时画面**：副屏直接送进硬件 H.264 编码器，推给小画面和全屏看画面。

细节见 [Cowork 实现细节](docs/cowork-internals.md)。

## 功能

**它**（[细节](docs/cowork-internals.md#它名字头像叫它便条聊天记录日记)）

- **名字和头像**：新装引导里起名、选头像；8 个贴纸角色，每个 9 种状态，带动画。
- **状态卡**：常驻通知写着它在干什么，按钮是叫它 / 写便条 / 看画面；要你接手时弹“找你”。
- **叫它**：浮在当前应用上的半屏，打字或按住说话，它干活时显示在做什么。入口有状态卡、快捷开关、长按图标、分享菜单、系统数字助理和长按电源键（`runtime/powerkey/`）。叫它是唯一直接派活的方式。
- **留便条**：必须选时间，原话由 App 直接存进 DSH，不经过模型。
- **首页**：聊天、事件（巡视和被通知叫醒时它做了什么）、便条、日记（每晚 11 点写一篇）四栏，左右滑切换。
- **看画面**：全屏只看；点“接手”自己操作，“交还”还给它。

**Cowork**：22 个手机工具，加上受限的文件读写和网页工具。

| 类别 | 工具 |
|---|---|
| 观察 | `phone_ui_tree`、`phone_screenshot`（可局部放大）、`phone_read_page`（自动下翻读长文） |
| 操作 | `phone_tap`、`phone_long_press`、`phone_swipe`、`phone_scroll`、`phone_type_text`、`phone_key`、`phone_batch`（同屏 2–5 步）、`phone_gesture`（任意轨迹与两指手势） |
| 应用与控制 | `phone_status`、`phone_apps`、`phone_start`、`phone_launch_app`、`phone_wait`、`phone_pause`、`phone_stop`、`phone_request_intervention`、`phone_notify_owner`、`phone_grant_permission`、`phone_uninstall_app` |

- **便条与巡视**：到点要做的事都是便条（基于 DSH 的 `dsh-schedule`）。你让它记的照做，它自己留的由它判断还做不做。巡视是一张周期便条，在单独的会话里轮流照看几个应用。
- **通知叫醒**：Broker 读通知，判断模型 Jev 认为值得才唤起那个应用的固定会话。模型可以给每个应用写关注说明，但私聊和 @ 一定算、广告一定不算。
- **自主但有边界**：像普通人一样用副屏上的账号；社交只做低侵入的，不主动私聊、加好友，敏感内容不碰。付款、账号安全、清空聊天记录要你决定。副屏同一时间只给一个回合用，你的消息优先。
- **省上下文**：工具按需加载，旧屏幕压成一行，用插件自己的上下文压缩。
- **其他**：Code 模式（设置 → 开发者）、运行中插话、记忆管理、DSH 运行环境管理。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `app/` | 主应用：`ui/` 界面、`data/` DSH 接口、`cowork/` 控制连接与接管、`island/` 任务状态、`avatar/` 头像、`notify/` 通知触发 |
| `app/.../cowork/broker/` | 原生 Broker，入口 `BrokerMain`，副屏驱动 `ProfileWorkspace`；可在 JVM 上测的纯逻辑单独成类 |
| `isolation/` | 工作资料的创建、资料拥有者与“AI 桌面” |
| `fixture/` | 无网络、无权限的测试夹具应用 |
| `platform-stubs/` | Android 16 隐藏 API 的编译期桩 |
| `cowork-plugin/` | DSH 插件（Node.js ≥ 22），入口 `src/restricted.mjs` |
| `runtime/` | 手机端运行时脚本（`cowork-service.sh`、`pocket-control.sh`、`powerkey/`） |
| `scripts/` | 经 ADB 的部署、验收与探测脚本（绑定测试设备） |
| `docs/` | 设计文档与更新日志 |

旧代码 Broker 的 `AndroidWorkspace`、`*ProbeMain` 和插件的 `src/index.mjs`、`bridge.mjs`、`files.mjs`、`policy.mjs` 不在主流程里；`cowork-plugin/t-tmp.mjs` 和一个以 `{console.log(` 开头的空文件是误提交的。

## 构建与测试

环境：JDK 21、Gradle 8.13（AGP 8.13.2、Kotlin 2.2.21）、Android SDK 36，仓库不含 `local.properties`。

```powershell
.\test-unit.ps1            # 构建 debug APK，编译并运行 JVM 测试（在仓库根目录运行）
.\test-unit.ps1 -SkipBuild # 只跑测试
```

```powershell
cd cowork-plugin
node --test test/strict-*.test.mjs   # 插件离线测试
```

- `test-unit.ps1` 从仓库上两级的 `tools\android-build\` 取工具链；测试类写死在脚本里，新增的要加进去。
- Kotlin 编译报 `AccessDeniedException …alive` 时，把 `TEMP`/`TMP` 指向仓库内的 `.kctmp\`，并给 Gradle 加 `-Pkotlin.compiler.execution.strategy=in-process`。
- 插件的 `strict-integration` 测试依赖未入库的 DSH 运行时副本（`cowork-plugin/research/`）。

## 部署

没有一键安装，步骤与设备强相关：

1. 手机已 root（KernelSU），本机运行 DSH 0.2.1-rc.2（Ubuntu chroot，`127.0.0.1:3080`）；插件声明的版本必须与之一致，见[升级说明](docs/cowork-internals.md#dsh-版本与升级)。
2. 经系统确认创建工作资料，见[创建说明](docs/isolation-profile-setup.md)。
3. `adb install -r` 装 Continuo，授予悬浮窗与通知权限。
4. DSH 停止时把 `cowork-plugin/` 装进 DSH 的插件目录（先备份、核对哈希）。
5. 用 root 运行 `runtime/cowork-service.sh start` 启动 Broker。

更新时：插件与 APK 的协议要匹配，一起更新；`adb install -r` 后在跑的 Broker 仍是旧代码，要在 DSH 空闲时重启它。`scripts/` 写死了设备序列号和本机路径，部分已过期。

## 局限

- 依赖 root、手机本机 DSH 和手动创建的工作资料，只在一加 Ace 3V 上验证过。
- 工作资料不是虚拟机，也不是防恶意应用的完整沙箱；跨资料、跨显示的跳转只会被检测和处理，不是系统级阻断。
- 系统弹窗通常不接受注入的点击：权限和卸载有专门工具，其他弹窗要你接管。
- WebView、游戏、自绘界面常缺结构节点，只能靠截图；第三方应用只做过抽样验证。
- 便条和通知叫醒依赖 DSH 和 Broker 常驻；开启通知叫醒后，未排除应用的通知会发给判断模型，**建议排除银行、验证码类应用**。
- 自主行动主要靠提示词约束，去哪些应用、做到什么程度由模型判断。
- ColorOS 从最近任务划掉应用会强杀进程，建议在最近任务里锁定 Continuo。

更多见[实现细节里的已知限制](docs/cowork-internals.md#已知限制)。

## 文档

- [Cowork 实现细节](docs/cowork-internals.md) · [更新日志](docs/CHANGELOG.md)
- [Broker 协议 v1](cowork-plugin/PROTOCOL.md) · [插件说明](cowork-plugin/README.md) · [集成约定](COWORK-CONTRACT.md)
- [创建隔离工作资料](docs/isolation-profile-setup.md)
- 早期设计：[Cowork 与任务岛](docs/cowork-design.md) · [主屏自由使用与后台并行](docs/cowork-concurrent-design.md) · [phone_batch](docs/phone-batch-design.md)

早期设计文档的细节以代码为准。文档引用的实机证据（`test-evidence/`）含个人数据，没有随仓库提交。

## 许可证

[MIT](LICENSE)。内置字体 JetBrains Mono 遵循 SIL OFL 1.1（见 `app/src/main/assets/licenses/`）。
