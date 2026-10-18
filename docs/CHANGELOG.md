# 更新日志（历史记录）

> 本文件由旧版 README 原样转存，按时间倒序记录 DSH Pocket / Continuo 各版本的变更与当时的实机验证结果。其中的绝对路径、设备序列号、APK 哈希和“当前”字样都属于写作当时的状态，不代表现状。项目的整体说明见根目录 [README](../README.md)。

---

# Continuo（通奏）— 原 DSH Pocket：品牌与界面重构

- 应用改名 **Continuo（通奏）**，包名/applicationId 仍为 `dev.dsh.pocket`（保留数据与授权）；设计依据 `工作区\continuo\continuo\Continuo 品牌与图标设计指导.md`。
- 视觉：「宣纸石青」配色（纸 #ECE9E1 / 墨 #22252A / 石青 #2E6A8E，深色 #1B1D21 / #5B9BC0），去掉赭红与火花；唯一强调色石青 = “AI 正在执行”与主要操作。
- 品牌元素：开口 C + 输入光标。`ContinuoCursor`（`ui/Components.kt`）取代转圈与火花：执行中按 1200 ms 闪烁（透明度不低于 0.15）、规划中呼吸、等待确认为稳定的藤黄；系统动画缩放为 0 时静止。`ContinuoMark` 绘制 C+光标。
- 字体：界面用系统无衬线（不再内置 Anthropic/Inter/Lora/Poppins/Source Serif）；代码用 JetBrains Mono；衬线仅用于品牌时刻（首页标题、关于页字标）。
- 启动图标为自适应图标（含主题单色层），启动屏为 C 描边 + 光标闪烁动画（Android 12+ 框架启动屏，950 ms）；状态栏/通知图标为单色 C。字标 `drawable/continuo_wordmark.xml`。
- 标志已按 R2「合环」更新（`工作区\continuo\CHANGES.md`）：C 圆心 (47,50)，光标 6×40、比例 1:6.7、圆角约 17%，启动动画描边弧 `M62.43 31.62 A24 24 0 1 0 62.43 68.38`、光标缩放基点 (72.5,70)。
- 记忆（设置 → 记忆）：直接读写手机侧 dsh-mneme 记忆库——概览（条数/归档数/检索方式/上次整理/版本）、模式（standard/light）、自动化开关（自动注入、自动总结、自动整理、热记忆、实体抽取、文档记忆、质量过滤、BM25、rerank）、注入预算（单条字数、文档条数）、记忆库列表（类型筛选、关键词搜索、新建、编辑、改重要性、归档/恢复、删除）、注入预览与存储回收（先 dry-run 再确认）。数据经 DSH 自身的 `/api/dsh-mneme/` 路由读取，复用已登录的 DSH 会话 cookie，不额外开端口或令牌；插件未安装时页面直接提示。
- 旧名称 “DSH Pocket” 仅保留在下方历史版本标题与 DSH 侧脚本名（`pocket-control.sh` 等）中。
# DSH Pocket 0.7.0（设置重排 / 执行器自动连接 / 任务岛上移）

- 无需手动连接执行器：打开应用时自动启动控制服务（`CoworkControllerService` 自愈循环：断线 6 秒后重连，退避 3 秒×失败次数，连续失败 6 次后停止）；发布 Cowork 任务时若控制器心跳超过 15 秒，broker 自行以 root 执行 `am start-foreground-service` 唤醒控制服务（8 秒限频；存在文件 `/data/local/dsh-native/cowork/no-auto-wake` 或关闭设置中的“自动连接执行器”可禁用），插件最多等待 25 秒再给出可操作的错误提示。实机验证：`am force-stop` 后发起任务，约 1.8 秒内控制器与任务岛服务重新就绪。
- DSH 自动连接：每次启动 DSH 都会换新登录令牌，旧版只尝试一次已保存令牌，失效后要手动点连接。现在 `PocketViewModel` 有自愈监督循环：先用已保存令牌，失败则经 Root 读取当前令牌，带退避持续重试；应用回到前台立即重连；在“运行环境”手动停止后暂停自动连接，启动/重启后恢复。设置里每个目标只有一个入口，Cowork 控制中心页面不再回链设置（原先会互相无限跳转）。
- 设置页重排为中心页 + 子页：连接与模式（右侧显示 DSH 连接状态）、Cowork（右侧显示执行器状态与任务岛开关）（执行器自动连接、任务岛开关、悬浮窗/通知权限状态、游戏中隐藏、免打扰应用、控制中心入口）、外观与反馈、运行环境、关于。Cowork 控制中心页不再包含设置项，只保留状态、实时画面、接管与应用列表。
- 任务岛重做（类 iPhone 灵动岛）：待机时是一颗黑色胶囊，垂直居中于前置摄像头与状态栏时间/信号同一行（位置按系统 cutout 与 status bar insets 实测，不写死像素）。任务运行时胶囊以弹簧动画向两侧拉长：摄像头左侧显示“正在使用 <应用名>”，右侧显示最近一个关键动作（打开应用、输入文字、确认输入、请求/结束接管、暂停等）；这些文案由 broker 的 `ActivityLog` 根据真实工具调用与前台应用切换（连续 2 次采样确认）生成，只在关键动作/应用切换时更新，**不再有模型生成的摘要**（摘要功能、插件侧 summary 通道与设置开关已整体删除）。点按胶囊展开为无按钮面板：左侧最近 4 条关键动作，右侧副屏实时预览；点击预览进入全屏预览，底部“接管操作”直接接管（全屏内触摸直通副屏），接管中变为“交还给 AI”，左上“返回”回到任务岛预览态。系统状态栏窗口会吞掉其区域内的触摸，所以胶囊下方有一块透明触摸区（约 104dp 宽）。动效为画布弹簧（展开 ζ=0.62/3Hz，收起 ζ=0.78，出现/消失从摄像头缩放）；运行中为跳动音柱、等待/确认/暂停为呼吸圆点、断连为闪烁点。FLAG_SECURE 在展示副屏画面/全屏预览时开启。通知上的暂停/停止/隐藏动作保留。同时修正系统应用（如桌面）被判为“未知是否游戏”而导致任务岛退回通知的问题。
- Cowork 现在也放行宿主的记忆工具（`memory_search` / `memory_get` / `memory_list` / `memory_save` / `memory_update` / `memory_archive`，`restricted.mjs` 的 `MEMORY_TOOLS`）：只在记忆插件已安装、且工具由宿主全局定义时列出（后代作用域的同名影子会被拒绝），带 `path`/`toFile` 等文件参数的调用一律拒绝；`memory_register_document`（吃宿主路径）、`memory_runtime`（下载运行时）、`memory_delete`/`memory_forget`（销毁或隐藏数据）仍不暴露。系统提示要求动手前先 `memory_search`、结束时用 `memory_save` 记下持久偏好与决定，并把取回的记忆当作不可信数据。Cowork 与 Code 共用同一个手机侧记忆库（`~/.dsh/memory/memory.db`），app 的「设置 → 记忆」页对两种模式都生效。实测：cowork-phone 会话工具数 26 → 32，`memory_save` 写入成功、`memory_search` 能召回。
- Cowork 放行 billion-context-dsh 的上下文工具（compress / decompress / search_context / cp_status，
estricted.mjs 的 CONTEXT_TOOLS）：只放行宿主全局定义（后代作用域同名影子会被拒绝），带 	oFile/path 等写文件参数一律拒绝；其余仍是白名单。
- 模型可见的工具名不再含点号：OpenAI 兼容接口（如 deepseek-official）要求 ^[a-zA-Z0-9_-]+$，原来的 phone.tap、cowork.files.read 会报 Invalid 'tools[0].name'。现在注册给模型的名字是 phone_tap、phone_ui_tree、cowork_files_read 等（modelName() 把非法字符换成下划线）；插件与手机 Broker 之间的内部线协议名仍是 phone.tap 形式，不变。ALLOWED_TOOLS 即模型可见名，测试断言全部匹配该正则。
- 截图前必须读结构（代码级强制）：模型调用 phone.screenshot 之前必须先调用 phone.ui_tree 读取当前屏幕的 UI 元素，否则直接以 TREE_REQUIRED 拒绝并返回理由（先读 phone.ui_tree；截图仅用于树不完整、自绘内容或树里没有的信息）。插件 BrokerClient 与原生 Broker 各自独立强制（Broker 内为 	reeRead 标记）：任何输入动作、phone.wait、phone.start、请求接管/交还、关闭副屏都会使标记失效，需重新读树；一次读树之后可连续截图直到屏幕再次变化（树本身返回 complete:false 时也直接允许截图）。设备实测：未读树截图 → TREE_REQUIRED；读树后截图成功；wait/按键后截图再次被拒。
- 隔离加强（不留痕）：① 工作资料里的启动器入口由 Broker 停用（LauncherHider，只停用组件、不卸载、不清数据），桌面不再出现 Work 文件夹和这些应用；列表记在手机 `/data/local/dsh-native/cowork/hidden-launchers`，建 `/data/local/dsh-native/cowork/no-hide-launchers` 即在下个周期全部恢复；启动应用时 Broker 为该应用启用其入口并在应用运行期间保持启用（停用正在运行的启动活动会让系统强制结束它，实测会令副屏应用立刻消失），独占副屏释放或看门狗发现无副屏时再统一隐藏。② 模型启动应用带 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`，运行期间不出现在最近任务。③ Broker 看门狗每 3 秒执行 `housekeeping`：无独占副屏时移除工作资料遗留的最近任务记录，只处理已验证的工作资料用户，绝不碰个人任务。资料拥有者应用（Cowork 隔离实验）是受保护包，系统拒绝停用它（Cannot disable a protected package），所以改为从源头去掉图标：isolation 模块 0.3-experiment 的清单里 IsolationActivity 不再声明 LAUNCHER，桌面不再有它的图标（需要打开时用 m start -n dev.dsh.pocket.isolation/.IsolationActivity）。桌面（ColorOS 启动器）不会即时刷新缓存的图标，改动后需重启桌面或等它重新加载；Broker 启动后与每个 housekeeping 周期会把被别人重新启用的入口重新隐藏（漂移修复）。
- 已知限制：唤醒依赖 KernelSU 对 DSH Pocket 的授权与系统允许 root 启动前台服务；ColorOS 上划清除最近任务仍会强杀进程，但 broker 会在下次任务请求时重新唤醒。

# DSH Pocket 0.6.0（接管 / 实时画面 / 结构优先）

- 页面结构优先：`phone.ui_tree` 通过 UiAutomation 读取工作资料副屏的节点树，返回**紧凑格式** `{title, package, nodes:[{id, text?, desc?, role?, rid?, bounds:[l,t,r,b], flags?}], complete, advice}`（`flags` 字母 c 可点/e 可编辑/s 可滚动/f 聚焦/k 选中）。无文字的可点击外壳、被上层窗口遮挡的节点、重复堆叠的同一操作栏都会被去掉；大块无描述图片以 `role:"Image"` 出现（没有替代文本，需要时用截图）。模型按 `elementId` 点击/输入（`phone.tap`/`long_press`/`type_text` 的 `elementId`，与 x,y 二选一）；`elementId` 绑定的是树观察，所以 `phone_ui_tree` → `phone_screenshot` → `phone_tap(elementId)` 可以连着用。`complete:false` 表示结构不足（WebView、自绘/游戏/大面积 SurfaceView、多数控件无标签、被截断），此时截图不再需要重读树。密码框内容为 `[REDACTED]`；安全窗口与外来包窗口不导出。上限：2000 节点、深度 40、文本 300 字、读取预算 5 秒。
- 读长文与滚动反馈：`phone.read_page` 一次调用自动向下翻页（`maxPages` 1–12，默认 4），返回去重后的可见文本、`pages`、`reachedEnd`、`title`；`phone.scroll` 返回 `moved`，并说明“没动”的原因，`x`/`y` 可省略（自动取最大可滚动区域）。
- 拒绝不等于结束会话：注入前的参数/元素校验失败（`NODE_STALE`、`STALE_OBSERVATION`、`ARGUMENTS_INVALID`、`KEY_DENIED`、`INVALID_GESTURE`、`WAIT_LIMIT`、`SCROLL_UNSUPPORTED`、`INPUT_REJECTED`、`LAUNCH_TIMEOUT`、`SCREEN_SETTLING`）不会暂停或结束任务，插件同时给出下一步建议；只有越界类失败（外任务占用副屏、未批准包、身份/安全校验失败、`LOCKED`、`USER_CONTROL`、`APP_CONFLICT`）才暂停。看门狗容忍转场最多 20 秒，之后才以 `SCREEN_UNRESPONSIVE` 暂停。`phone.start`/`phone.launch_app` 最多等应用 8 秒上屏，超时返回 `LAUNCH_TIMEOUT` 但保留副屏与已启动的活动，模型用 `phone_wait`（上限 10 秒）后继续观察，而不是重开。
- 副屏虚拟导航键：副屏没有系统装饰（个人桌面与 launcher 会破坏资料隔离），所以导航由 broker 自带三键实现——`phone.key` 的 `BACK`（副屏本地返回，需观察）、`HOME`（打开资料内的「AI 桌面」`dev.dsh.pocket.isolation/.HomeActivity`，当前应用留在后台继续运行）、`RECENTS`（列出副屏上的工作资料任务及前后台状态，不改变画面）。`HOME`/`RECENTS` 按屏幕寻址而非像素，因此不需要 observationId，切回后台应用用 `phone.launch_app`（同样不需要）。`phone.launch_app` 去掉了 `MULTIPLE_TASK`，因此切回会复用原有任务而不是新建。用户接管时同样可用：Cowork 页面新增「桌面」按钮，任务岛全屏的左右边缘内滑=返回、底部上滑=桌面（与 ColorOS 手势一致）。
- 用户接管：模型调用 `phone.request_intervention`（可带 reason）或用户在 Cowork 页面/任务岛点“接管操作”，期间模型的观察/操作工具返回 `USER_CONTROL`，DSH 回合保持存活（插件阻塞等待，最长 15 分钟，到期自动交还）；用户在实时画面中触摸、发送文字、按 返回/回车/删除，完成后点“交还给 AI”，模型重新观察后继续。
- 实时画面：`GET /v1/stream`（控制器鉴权，单观看者，4 字节长度前缀帧，首帧 PNG，之后 540x960 JPEG q65，最小间隔 30ms；屏幕静止时不产生新帧，因此 30fps 是动态画面的上限）。用户输入在同一条已鉴权连接上回传，避免逐次 HTTP 往返。Cowork 页面与任务岛共享同一路流。
- 任务岛：连接控制器后自动启动；偏好持久化（SharedPreferences `island_preferences`，默认开启）；需要“显示在其他应用上层”权限；展开后显示实时画面与接管/交还。模型请求介入时发出高优先级通知。
- 已知限制：ColorOS 在用户从最近任务划掉 DSH Pocket 时会强杀进程，控制器随之断开，需重新连接并建议在最近任务中锁定。

# DSH Pocket 0.5.0（Cowork 工作资料模式）

- Cowork 现只在已验证的工作资料（userId 10）独立副屏运行；个人主屏可任意切换，不再因此暂停；无逐应用白名单，范围为该资料内可启动应用。
- 输入仅为 UID 定向触摸（点击/长按/滑动/滚动）；截图为 UID 过滤、非安全层；文本输入（0.5.1）：按键事件逐字注入焦点输入框（UID 定向，每字符复查目标），仅支持键盘可表达的可打印字符（ASCII 字母数字标点空格）；中文/emoji 返回 TEXT_UNSUPPORTED（不暂停任务）；按键仅 BACK/ENTER；无障碍节点暂不支持。工作资料内“允许未知来源安装”已由资料拥有者应用的 SideloadPolicyActivity 放开（可用 --es action restrict 恢复），未授予任何应用的单独“安装未知应用”权限。
- 详见 [docs/cowork-concurrent-design.md](cowork-concurrent-design.md)。以下为旧版 0.3.1 说明。

# DSH Pocket 0.3.1

## 0.3.1 更新

- 本机显示配置已核对：`wm size` 为 `1080x2414`，`wm density` 为 `436`，`settings get secure display_density_forced` 为 `436`。使用现有持久化设置，没有重启手机或改动运行环境；重启后未另做实测。
- 使用用户提供的 Anthropic Sans / Serif / Mono 正体与斜体，从 WOFF2 转换为 Android 可加载的 SFNT 字体；中文缺失字形由系统补充。不把专有字体标为 OFL，也不据此主张公开分发权。
- 工作区与权限改为屏幕居中的选择弹窗。工作区在新任务创建之前选择，创建后即锁定（包括已发送首条消息的会话）；UI 与 ViewModel 均阻止中途切换，原会话目录不变。
- 对话右上角“⋯”收纳工具权限与后台任务，输入卡片不再重复显示权限。权限名称和说明统一中文：只读、工作区读写、完全访问。
- 助手正文 16.5sp → 14sp，用户正文 16sp → 14sp；工具折叠行最小高度 40dp → 30dp，消息间距 12dp → 6dp，思考与工具标题压缩至 12sp。参数、输出和真实错误均保留。


用于连接手机本地 DSH 的原生 Android 客户端，使用 Kotlin 和 Jetpack Compose，不嵌入 WebView。0.3.0 按 Claude 移动应用的公开资料重做外观与交互，只保留 **Cowork** 与 **Code** 两种任务模式，没有基础聊天模式。

> 本版已通过构建和 56 项 JVM 回归测试。安装验证记录见下文。按此前约定，没有打开副屏做界面验收，没有发送模型请求，也没有重启 DSH。新界面的实机观感、键盘适配和真实提问交互仍需手动验收。手机上没有 Claude 应用，设计依据只有公开资料，因此做不到逐像素一致。

## 模式

| 模式 | DSH agent preset | 状态 |
|---|---|---|
| Code | `standard` | 可用；新任务使用 `session/create {agentPreset:"standard"}` |
| Cowork | `cowork`（预留） | 当前 DSH 只有 `standard / ptc / minimal / cordis`，所以显示“未配置”，无法创建 Cowork 任务。DSH 中加入名为 `cowork` 的模式后，无需改应用即可使用 |

- 任务列表按会话的 `projections.values.agentPreset` 归类：`cowork` 归入 Cowork，其余（含 ptc/minimal/cordis 旧会话）归入 Code，非 standard 会显示 preset 标签。
- 读取模式列表失败时，只开放 Code。

## 界面

- 风格：象牙底 `#FAF9F5`、陶土色强调 `#D97757`、暖色深色模式；正文与标题使用 Anthropic Serif，界面使用 Anthropic Sans，代码使用 Anthropic Mono。字体来源为用户指定的本地文件，火花图标为自绘。
- 首页：左侧菜单、Cowork｜Code 切换、火花与问候语，以及底部输入卡片（附件、工作区、模型 + 推理强度、权限、发送／停止）。发送时用当前模式创建任务，并带上所选模型和权限。
- 侧边栏：搜索、新任务、Cowork、Code、最近任务（置顶、运行中或待输入状态点），底部是连接状态与设置入口。
- 任务列表：筛选（全部、需要输入、运行中、已完成、已归档）、搜索、下拉刷新、工作区标签、相对时间；长按或“更多”可以重命名、置顶、归档或恢复。
- 对话：用户消息是右侧气泡，助手回复是无气泡正文；思考过程可折叠。连续的工具调用合并成一条可折叠记录，展开后才看到各次调用的参数与输出，不再每个调用单独一栏。会话记录同样按相邻记录合并。实时流在消费跟不上时等待，不再因为缓冲满而整段断开重同步；助手输出按短时间窗合并刷新。进度面板显示待办与目标；排队消息和补充回答入口；长按消息可复制。
- 模型面板按 Provider 分组，推理强度用胶囊选择；顶部和输入框里的模型胶囊始终显示 Provider，用来区分同名模型。
- 提问面板：分段进度、编号选项、自定义回答、超时后补答。
- 设置：连接、模式状态、外观（浅色、跟随系统、深色）、触感反馈、运行环境、版本与许可。运行环境页的启动、重启、停止和重建 Linux 环境仍调用固定的 `pocket-control.sh` 动作，需要 KernelSU 授权；重建前会确认，若仍有进程留在 chroot 中会被拒绝。

上一版的事件、投影一致性与请求重试修正都保留：未知扩展事件不报错，真实失败仍会显示。

## 安装

- 包名 `dev.dsh.pocket`，versionCode `4`，versionName `0.3.1`。
- 连接目标：手机本机 `http://127.0.0.1:3080`。
- APK：[安装包](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/build/outputs/apk/debug/app-debug.apk>)，debug 签名。
- 更新使用 `adb -s 5b9a17cd install -r`：不卸载、不清除应用数据，也不改 DSH 或 provider 配置。

## 构建与测试

```powershell
.\test-unit.ps1            # 构建 APK、编译并运行 JVM 测试
.\test-unit.ps1 -SkipBuild # 仅运行测试
```

[测试脚本](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/test-unit.ps1>) 使用工作区里的 JDK 21、Gradle 8.13 和 Android SDK 36，通过 JUnitCore 直接运行测试，以绕开本机 Gradle 测试 worker 在 Unicode 路径下的启动问题。

本版结果：

- `:app:assembleDebug`、`:app:compileDebugUnitTestKotlin`：通过。
- Transcript / Presentation / PendingPromptStore / ProjectionState / RpcRejectedException / Modes：`OK (56 tests)`。
- 设备 `5b9a17cd` 覆盖安装 `Success`；Package Manager 显示 `versionCode=4`、`versionName=0.3.1`，firstInstallTime `2026-10-15 13:28:36` 未变，lastUpdateTime `2026-10-15 17:14:46`。
- 安装后 SurfaceFlinger 只有 `HWC display 0`，没有遗留副屏。
- APK SHA-256：`36CFEA25AFB605A3881457B43ABDB8308B45EB1B1190CF8C802FED4D165F1BD0`。

## 手动验收重点

1. 首页：Cowork 显示“未配置”，Code 能输入并开始任务；模型胶囊显示 Provider · 模型 · 强度。
2. 侧边栏与任务列表：旧会话都在 Code 下；筛选、搜索、置顶和归档正常。
3. 对话：工具行足够紧凑，展开后有参数和输出；长回复的衬线正文、代码块和链接显示正常。
4. 深色模式和键盘弹出时，输入卡片不被遮挡。
5. 提问面板的单选、多选、自定义回答与补答。

## 代码入口

- [应用框架、侧边栏、首页、任务列表、设置、运行环境](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/PocketApp.kt>)
- [对话与输入卡片](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/ChatScreen.kt>)
- [底部面板：模型、权限、工作区、后台任务、审批](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/Sheets.kt>)
- [提问面板](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/QuestionSheet.kt>)
- [主题、字体与组件](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/Theme.kt>)、[图标与通用组件](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/ui/Components.kt>)
- [模式与展示规则](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/data/Modes.kt>)
- [会话状态](<C:/Users/rangt/Desktop/工作区/work/dsh-android-app/app/src/main/java/dev/dsh/pocket/PocketViewModel.kt>)

- 常驻副屏：AI 副屏由 broker 在启动时创建并一直保留，不随每轮任务销毁；每轮首次工具调用自动占用（无需 phone_start），轮次结束只清空内容；任务岛在 Continuo 前台时同样显示，模型请求接管时自动展开并显示「请求接管」，用户双击预览后进入全屏接管。
