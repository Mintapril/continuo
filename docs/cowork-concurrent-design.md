# 主屏自由使用与后台 Cowork 并行：待验证设计

> **0.7.0 增补**：执行器无需手动连接（应用打开自动连接 + broker 在任务请求时 root 唤醒控制服务）；设置页重排。详见 README 0.7.0 一节。
>
> **0.6.0 增补**：新增 `ProfileStructure`（UiAutomation 节点树，按元素点击/输入）、`Takeover`（用户接管状态机，`USER_CONTROL`）、`/v1/stream` 实时画面、`/v1/takeover`、`/v1/user-input`；`request_intervention` 不再暂停/取消回合。无障碍节点现在用于工作资料副屏（只读结构，仍不回退共享输入法）。详见 README 0.6.0 一节。
>
> **状态（0.5.0，夹具验证）**：Broker 已改用 `ProfileWorkspace`：仅操作已验证的工作资料（userId=10 / serial=10 / parent=0），不再依赖“主屏不变”，也没有逐应用白名单。真机夹具验证通过：OWN_FOCUS + 无抢焦点副屏、UID 过滤截图、InputDispatcher 定向 UID 触摸（点击/滑动/滚动）；主屏切到“设置”后副屏继续执行且未暂停；停止后只剩物理显示。DSH 共享存储已改为工作资料 `/workspace/cowork`，个人 `/workspace/phone` 已移除。
>
> **未验证/有意限制**：真实第三方应用；文本仅支持键盘可表达的 ASCII 字符（逐键注入，中文/emoji 拒绝且不暂停），系统按键仅 BACK/ENTER，无障碍节点有意拒绝（不回退共享输入法）；应用跨资料/跨显示跳转仅检测并暂停，不是系统级阻断。工作资料不是防恶意应用的完整沙箱，root 链路仍共享。

## 用户要完成什么

用户正常切换主屏应用、输入、看视频或玩游戏时，后台副屏不应仅因主屏变化而暂停。DSH Pocket 不需要一直停留前台；任务岛是可关闭的状态与控制界面，不是后台执行的生存条件。同包应用并行也需要独立验证，不能靠禁止用户打开目标应用来宣称满足要求。

支付、发消息、账号授权等行为仍需授权；主屏自由使用不代表授予后台所有敏感操作权限。断电、设备重启、进程被杀、应用拒绝副屏与锁屏策略分别处理，不能承诺永不中断。

## 已知证据

实机只读命令及结果见[能力记录](<../test-evidence/cowork/concurrent-capabilities.json>)。

| 证据 | 观察 | 能说明什么 / 不能说明什么 |
|---|---|---|
| Android SDK / build type | `36` / `user` | 采用 Android 16 接口基线；不代表 OEM 与 AOSP 完全一致 |
| 最大用户数 | `4` | 支持多用户数量，不等于多个完整用户可同时显示 |
| 可见后台完整用户 | `effective=false real=false` | 不能直接把第二完整用户当作已支持的并行显示方案 |
| 全局每显示焦点配置 | `false` | 不否定可信虚拟显示自身的 `OWN_FOCUS` |
| 工作资料特性 | `android.software.managed_users=true` | 可研究前台用户的独立资料；现有资料占用、创建条件及实际行为未验证 |
| 副屏 / 虚拟设备 | 副屏活动特性为真，`virtualdevice` 服务存在 | 不等于调用者有创建虚拟设备权限，也不证明任务不会逃逸到主屏 |
| 当前实现 | 共享当前用户，以 `startActivityAsUser(...,-2)` 启动；主任务改变即暂停 | 是应用层主动限制，不是需求已被系统证明不可能 |

当前代码位置：[显示创建和主屏守卫](<../app/src/main/java/dev/dsh/pocket/cowork/broker/AndroidWorkspace.java#L104-L151>)、[应用启动](<../app/src/main/java/dev/dsh/pocket/cowork/broker/AndroidWorkspace.java#L184-L200>)、[控制通道看门狗](<../app/src/main/java/dev/dsh/pocket/cowork/broker/BrokerMain.java#L48-L58>)。

## 不采用直接删除守卫

当前共享用户会复用应用进程、任务和数据。事件带 displayId 只保证直接输入路由，不能证明应用发起的后续 Activity、权限窗口、Intent 或账号操作也受同样限制。把 `MAIN_CHANGED` 和同包检查直接删掉，不构成隔离。

AOSP 的多显示 FAQ 也明确，同用户多显示不提供同应用的独立进程/沙箱。来源：[多显示 FAQ](<https://source.android.com/docs/core/display/multi_display/faq>)。

## 优先验证的候选

1. **独立工作资料（先征得同意）。** 为 AI 提供独立的应用实例与存储，不复制个人应用私有数据、登录凭据或聊天记录。仅启用无网络测试应用，测试通过后再由用户选择实际应用并自行登录。资料不是完整虚拟机，账号服务仍可能施加跨设备/多实例限制。
2. **固定资料身份而不是当前用户。** 启动、包查询、任务归属、节点及窗口策略都绑定明确的 userId/uid 和已拥有的 display；资料/显示身份变更立即失效。不能只把启动参数换成另一个数字。
3. **副屏独立焦点且禁止抢占主屏顶层焦点。** AOSP Android 16 定义 `VIRTUAL_DISPLAY_FLAG_OWN_FOCUS=1<<14`，可信显示局部启用；增加 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED=1<<16` 是候选而非已验证修复。需要检查 ROM 实际保留的 DisplayInfo 标记并做主屏输入验证。来源：[DisplayManager.java](<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/hardware/display/DisplayManager.java>)。
4. **不争用主屏输入法。** no-steal 显示的 IME 有平台限制；候选设置 `DISPLAY_IME_POLICY_HIDE=2`，继续使用非密码节点 `ACTION_SET_TEXT` 与显示定向的允许键，不借主屏输入法或共享剪贴板。依赖自绘输入法、无法语义输入的应用不能宣称已兼容。
5. **任务/Intent 留在隔离区域是尚未解决的独立问题。** VirtualDeviceManager / DisplayWindowPolicyController 不是通用出站边界：AOSP 的启动检查针对目标显示，且 `keepActivityOnWindowFlagsChanged(...)` 返回 false 的约定可将任务移回默认显示。不能把 VDM 或工作资料等同于虚拟机。仅在输入后发现逃逸再暂停不足以保证不打扰主屏；完整保证可能需要系统级 UID→显示约束，或另一个 Android 系统。此阶段只评估 fixture 和显式允许的兼容应用，不宣称支持任意应用。来源：[ActivityStarter.java](<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/ActivityStarter.java>)、[DisplayWindowPolicyController.java](<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/window/DisplayWindowPolicyController.java>)。
6. **后台控制服务与任务岛解耦。** 用户明确开始任务后，由原生常驻前台服务维持控制、停止入口及通知；关闭胶囊不应终止控制心跳。控制服务真正失联依然需要安全停止。OEM 省电、内存压力和用户手动停止应用必须单独测试。

## 分阶段验收

| 阶段 | 实机测试 | 通过标准 |
|---|---|---|
| 隔离资料 | 新资料只装测试应用；主屏仍在个人资料 | 无个人数据复制、无主用户切换，资料及显示身份稳定 |
| 前台切换 | 用户在多个主屏应用之间切换，副屏执行测试动作 | 不因普通主屏变化暂停；所有输入和图像仍属于副屏 |
| 同时输入 | 主屏输入测试文本，副屏语义填入另一段文本并按允许键 | 两侧文本、焦点、键盘目标不串；副屏不抢主屏焦点 |
| 同包并行 | 主屏与资料中各运行测试应用 | 进程/UID/存储独立，计数及任务不互相复用或迁移 |
| 跳转拦截 | 测试应用尝试跨包、外部链接、默认显示跳转 | 不把后台页面或确认窗口推到主屏；拒绝发生在逃逸前 |
| 生命周期 | Pocket 退后台、关闭任务岛、旋转、主屏游戏、内存压力 | 控制通道可持续且停止按钮有效；不将胶囊显示与任务存活绑定 |
| 中止与失联 | 暂停、停止、进程异常、资料禁用、锁屏 | 已定义的安全状态及时生效，不自动重放动作，不误报完成 |
| 用户应用 | 用户逐个允许并登录后验证 | 只把通过的组合列为兼容，不用 fixture 或离线测试代替 |

## 当前授权与下一步

用户已创建独立工作资料，并要求资料内取消逐应用白名单。最新元数据确认资料 userId=10 / serial=10 / parent=0、拥有者 `dev.dsh.pocket.isolation/.ProfileAdminReceiver`。资料边界与窗口 UID 适配器正在实现，不把「安装在资料内」等同于实际窗口身份。用户另要求加快进度并移除个人共享挂载；[存储迁移](<../test-evidence/cowork/work-profile-storage.json>)已完成，不更改个人文件、模型配置或已有会话。

创建工作资料会改变设备资料配置，可能出现带工作标记的应用，资料内登录和文件与个人空间分开。不覆盖或清空个人数据，不在未确认时刷机、启用开发用户模式、改全局资源或重启设备。应用不主动删除资料，但 Android 可能回滚失败或取消的本次创建；创建阶段不放重要内容。若 ColorOS 不支持所需的输入/任务隔离，应明确报告结果并改选更强隔离环境，而不是静默退回无保护的共享用户模式。
