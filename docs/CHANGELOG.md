# 更新日志（历史记录）

> 本文件由旧版 README 原样转存，按时间倒序记录 DSH Pocket / Continuo 各版本的变更与当时的实机验证结果。其中的绝对路径、设备序列号、APK 哈希和“当前”字样都属于写作当时的状态，不代表现状。项目的整体说明见根目录 [README](../README.md)。

---

# 副屏不再卡在受保护的页面上（2026-10-31）

- **来由**：10-30 12:00 巡视时它在知乎点进一个带 `FLAG_SECURE`（防截屏）的页面，看门狗按 `SECURE_WINDOW` 暂停了这一轮。之后 31 小时里，每一轮的第一个动作（读结构、HOME、开应用）都被拒、整轮暂停；10-31 11:46 它发通知让你关掉那个窗口，19:46 Broker 重启重建副屏才好。
- **为什么出不来**：被暂停的回合结束时副屏不收起，回合之间盖桌面的逻辑只在正常结束后生效；HOME 和开应用之前也要过同一道检查；你接管时的输入也查它，实时画面里它是黑的；就算用桌面盖住，被盖住的窗口还在窗口层级里，检查照样拒绝。
- **改了什么**（Broker）：碰到受保护窗口这一轮照旧暂停；之后副屏没有回合占用、也没人接管时（碰到过之后回合之间每 5 秒查一次，每一轮占用副屏之前都查一次），Broker 从窗口管理器的元数据里找出持有受保护窗口的工作资料任务，把它移出副屏（`removeTask`），再让 AI 桌面盖上。只做任务管理，不读、不截、不输入；个人资料的任务、别的屏幕上的任务和 AI 桌面不会动。新类 `ProtectedTaskSweep`，`SecureWindowSnapshot.secureTasks`，`ProfileWorkspace.clearProtected`。
- **插件**：`SECURE_WINDOW` 有了提示：这一轮到此为止，回合结束后 Broker 会关掉那个页面、回到桌面，下一轮照常，不用通知主人，以后别再点进去。
- **部署**：APK 和插件；装 APK 后要在 DSH 空闲时重启 Broker（重启也会重建副屏）。10-31 23:10 已部署为插件 v41 + APK。
- **实测**（工作资料里的测试应用，“Enable protected fixture” 给窗口加 `FLAG_SECURE`）：Broker 按 `SECURE_WINDOW` 暂停；结束这次任务后桌面先盖上，被盖住的受保护窗口仍在窗口层级里，几秒内 Broker 记下 `removed work-profile task …`，副屏只剩桌面；结束后马上开新一轮，新一轮读到的是测试应用正常的首页，没有被暂停。

---

# 性格、能量与主动性（2026-10-31）

- **来由**：你要它有确定的性格，基于荣格八维和 MBTI；不太克制；有主动性，而不是每天被便条拉起来几分钟；会像朋友一样主动找你。设计和与设计的差异见 [personality-design.md](personality-design.md)，运行时是什么样见 [cowork-internals.md](cowork-internals.md#性格能量与主动性)。
- **插件**：新模块 `psyche.mjs`、`psyche-store.mjs`、`psyche-recorder.mjs`、`reach.mjs`、`own-time.mjs`；系统提示词多一段 `deployment:character`，每轮第一步多一条 `[STATE]`；新工具 `cowork_reach_owner`；自己的时间 `[OWN TIME]` 和 `[OWNER SILENT]`；人设允许回怼、主动找聊过天的人；自己留的便条每天 5 张改 10 张；`BrokerClient.labelsOf`（只读）；`coworkUnattended` 多 7 个方法。
- **App**：设置“它”页的性格、能量心境、主动找我、你的联系方式、夜里别找我、别找我、自己的时间；性格页和联系方式页；聊天栏显示它主动找你的话；叫它半屏的“别找我”；新装引导的性格一步；便条页的性格卡；设了性格后巡视便条并进自己的时间；看过的回执。
- **部署**：插件和 APK 一起装（旧插件不认 App 调的新方法，旧 App 不显示它主动找你的话）。Broker 的 Java 没改：装 APK 后正在跑的 Broker 进程照旧，不用重启。

---

# 新工具 phone_read_chat（2026-10-27）：往上读聊天里更早的消息

- **来由**：模型自己提的建议。`phone_read_page` 只会往下翻，适合文章和动态；聊天窗口最新的消息在最下面，要看之前说了什么，只能一屏一屏地 `phone_scroll`。
- **单独一个工具**：读聊天用 `phone_read_chat`（参数和结果与 `phone_read_page` 相同）。用法只写在两个工具的说明里，系统提示词、巡视清单、通知唤起的说明都没加。
  - `phone_read_page` 往下读：文章、动态、搜索结果、评论区、会话列表、回复和通知列表。
  - `phone_read_chat` 往上读：QQ、微信等的私聊和群聊窗口、其他应用的私信、客服和助手聊天。
  - 说明里写了：什么时候往上读（未读比一屏多、群聊中途插话、对方提到之前的事、通知合并了几条）、`maxPages` 怎么定、`reachedEnd` 之后怎么再往前、回复前先回到最新消息；直播间这种自己一直在滚的聊天区不用它。
  - `phone_read_page` 的代码和行为没变。
- **去重**：往下读的规则（上一屏有的行这屏不要）会删掉每屏都有的昵称、时间和“好的”，所以聊天改为按位置拼。
  - **滑法**：手指拖四分之三个列表高，抬手前停 150 毫秒，不让列表甩出去。手机上实测：拖 700 像素，列表移动 669 像素。
  - **拼接**：纯 Java 类 `ChatText` 量出两屏之间移动的距离，只取上一屏里新露出来的行。
  - **难点**：
    - 一串一样的“好的”：取最接近手指距离的那个；
    - 重叠只剩一条消息的一角；
    - 重叠里有一行变了；
    - 可滚动节点里有不动的标题栏和输入栏；
    - 读到最顶时列表移动得比手指少。
  - **测试**：`ChatTextTest` 11 条，用模拟长群聊逐字还原。另跑过 12.8 万次大范围模拟，约 0.04% 没有逐字还原，全部是“只有一两种回复、读到最顶”的聊天（一串完全一样的消息会多或少一条）。
- **测试结果**：JVM 453 个测试、插件 432 个测试全部通过。
- **部署（10-27 07:29）**：
  - 插件换了 `src/broker.mjs`、`src/restricted.mjs`，用 `v38/replace-v38.sh` 校验哈希后替换；旧文件备份在 `/data/local/dsh-native/cowork-plugin-backup-20261027-read-chat`。
  - 主 APK edf5935a；旧的备份在 `/data/local/tmp/cowork-notify-deploy/app-20261027-readchat/prev-app.apk`。
  - 回滚：DSH 停着时运行 `v38/rollback-v38.sh`，再装回旧 APK，重启执行器。
  - 执行器重启了两次。第一次停掉执行器后，装 APK 期间 Continuo 的自动重连又把它拉了起来，用的还是旧 APK，`start` 看到它在跑就没再启动；07:34 再停再启后才是新 APK。以后装 APK 后要看执行器进程的 `CLASSPATH` 是不是新包。
- **手机上的验证**：
  - 工作资料“设置”：往上读 2 屏，读到顶；之后的画面就是顶部那一屏；路上看到的长行都在、没有重复，顶部和底部两屏的顺序都对。
  - QQ 一个没有未读的聊天（只报结构，不看内容）：
    - 最大的可滚动节点就是消息列表（y=213 到 2263，标题栏和输入栏在外面）；
    - 往上读 3 屏，用时 3.5 秒，48 行；
    - 出现两次的长行只有一条，它在同一屏里本来就出现两次；
    - 开始那一屏和读完那一屏的行顺序都对，最新一条在最后。
  - 一屏放得下的聊天（QQ 会话列表也是）不会被标成可滚动，`phone_read_chat` 这时报 `SCROLL_UNSUPPORTED`，不结束这一轮。
  - 测完按返回键时，QQ 和应用宝正在后台自己更新，应用宝的窗口抢了焦点，返回键被系统拒绝，执行器按平台错误暂停了测试任务；和新工具无关，已用一个空任务把状态恢复成“完成”。
  - 测试任务不发心跳超过 15 秒也会被停，原因同样记成 `CONTROL_CHANNEL_OFFLINE`（不只是控制端掉线）。
  - 微信没用过，没有可看的聊天。
- **还没做的**：没有提交；AI 自己还没用过 `phone_read_chat`。

---

# 执行器（2026-10-27 凌晨）：重建副屏后马上接管，之后再也读不到界面

- 现象：10-26 18:00 到 23:59，AI 每次读副屏都是“没有可访问的窗口”。QQ、设置、AI 桌面都一样，元素点不了，中文打不进去（拿不到元素编号，只能走按键输入）。截图看起来一直是同一个画面。其实副屏上的应用一直在正常切换；15:18 之前 6 天、987 次读取都正常。
- 原因：UiAutomation 的窗口列表缓存在 Broker 进程里，没有过期时间，只有窗口变化事件能清掉；而系统要等这个连接读过一个节点（`mUsesAccessibilityCache`）才开始给它发这类事件（照手机上的 framework/services 反编译核对过）。“重建副屏”之后你马上接管，全屏画面每隔一会儿查一次输入框（`userField`），这是新执行器第一次读窗口，而那时副屏上还没有任何应用。空列表就这样被缓存住了：之后永远读不到窗口，也就永远不读节点，系统也就永远不发清缓存的事件，一直到下次重建。17:55 和 23:36 两次重建后都是这样。
- 修复：`ProfileStructure.windows` 读到副屏的窗口列表为空时，先 `clearCache()` 再读一次；`BrokerSourceTest` 加了一条源码检查。JVM 441 个测试通过。
- 没覆盖到的一种情况（没见过）：第一次读取时副屏上只有被 `collect` 按类型跳过的窗口（不是应用窗口也不是系统窗口），也会缓存一份不空但过时的列表，同样卡住。
- 实机对照（10-27 00:14–00:16，同一套步骤：执行器刚启动、副屏还空着时接管并开全屏画面 10 秒，交还后用测试任务打开工作资料的“设置”读界面）：
  - 旧 APK：两次都是“没有可访问的窗口”；
  - 新 APK：两次都读出 22 个元素，带元素编号输入“蓝牙”成功。
- 00:15 已装主 APK 并重启执行器，插件没动。之后经执行器截图正常（“设置”和 AI 桌面两张不同），AI 桌面也能读出元素；保活跟到新 APK（`READY version=2`），QQ、微信四个收信进程 PID 没变、仍标着。旧 APK 备份在 `/data/local/tmp/cowork-notify-deploy/app-20261027-tree/prev-app.apk`。AI 自己的一轮还没在新执行器上跑过。

# App（2026-10-26 晚）：聊天栏切回来不再重新加载，打开就在最底下

- 首页四栏是横向翻页，翻走的那一栏会被销毁：以前聊天记录的订阅跟着停，切回来从头加载（先空着、显示“正在翻聊天记录…”）。现在订阅由首页发起，之后一直保持（`followOwner`），切栏、进设置再回来都直接显示；重连后重新订阅时旧记录留到新快照来。
- 聊天列表改成从底部往上排：打开就在最新一条，往前自动补的几页加在上面，不会再把视图停在顶上。
- JVM 440 个测试通过。10-26 18:22 只装了主 APK（执行器没重启）；保活半分钟内换到新 APK，`READY version=2`，QQ、微信四个收信进程照旧标着（PID 和 15:59 时一样，17:55 执行器重启、副屏重建后它们都还活着）。旧 APK 备份在 `/data/local/tmp/cowork-notify-deploy/app-20261026-chat/prev-app.apk`。真机上的聊天栏效果还没看。

# App（2026-10-26 晚）：保活的应用可以自己选，设置页重新排了位置

- 保活不再只认 QQ、微信：“设置 → 它的手机 → 保持哪些应用”从它手机上装的应用里选（最多 8 个，默认 QQ、微信）。每个应用只保持主进程和推送进程（QQ 的 `:MSF`、名字带 push 的）。名单存在手机上 root 所有的 `packages` 文件里，keeper 启动时读一次，名单一变 `keeper.sh start` 就重启它。
- 设置页重排：
  - 名字卡（原“名字和头像”）里加了它用的模型（原在“它的手机”）。
  - “它的手机”只剩连接、重建副屏和收消息。
  - 原“放进你的手机”那几项（通知、悬浮窗、盖住状态栏、快捷开关、系统助手）挪到“这台手机 → 权限与入口”；有权限没开时，设置首页这一行会标黄点。
  - “连接”和“运行环境”合成“本机 DSH”，打开时自动查一次状态。
  - 通知页标题改成和入口一样的“通知叫醒”；Jev 密钥挪到开关下面；没设密钥时开关和设置首页都会说。“不送去判断的应用”从两边手机装的应用里勾选（为此清单加了启动器应用的 `<queries>`），列表里没有的仍可手动输入包名。
  - 所有开关行整行都能点（共用 `ToggleRow`）。
- JVM 440 个测试通过。10-26 17:34 只装了主 APK（执行器没重启）：保活的监视进程半分钟内发现 APK 变了，自己换成新代码，`keeper.log` 第一行 `READY version=2 … packages=com.tencent.mobileqq,com.tencent.mm`，QQ、微信四个收信进程重新标上。手机上的保活脚本要等 App 下次连上它的手机才换成新版（新版才会在名单变化时重启 keeper）。新设置页还没在真机上看过。旧 APK 备份在 `/data/local/tmp/cowork-notify-deploy/app-20261026-settings/prev-app.apk`。

# App（2026-10-26 后续）：聊天通知保活整合主工程

- 已验证的独立 root keeper 源码纳入主 APK，生命周期脚本由 Gradle assets 打包；Cowork 连接时自动安装 KernelSU 开机入口，迁移旧 standalone jar 版本，不重启 Broker 或重建副屏。
- 从当前安装 APK 动态加载，路径/哈希变化时仅重启 keeper。保留 disabled，监视进程唯一，显式停用/卸载入口；确认主用户 App 卸载后自动停止、移除开机入口。
- 范围仍是工作资料中已运行的 QQ/微信四个收信相关进程，不拉起死亡应用，其他应用保持懒启动。新增 19 项 JUnit 策略测试和 3 项源码合同并登记主测试脚本。集成构建/设备结果见运行时说明及本次交接，不把尚未执行的整机重启或实际锁屏收信算作通过。
- 设置 → 它的手机 加了“QQ、微信保持在线”开关：显示哪个应用正在被保护、哪个没在运行；关掉就是 `keeper.sh disable`，打开是删掉 `disabled` 再 `start`，状态只存在手机上那个 `disabled` 文件里。安装失败不再让 Cowork 连不上，原因显示在这一行，点一下重试（`ChatKeeperStatusTest` 10 个测试，JVM 共 433 个通过）。

# App（2026-10-26 下午）：副屏懒启动、60 Hz、一键重建

- 副屏重建后不再把旧副屏上停放过的应用一个个拉起来（10-23 加的 `ParkedApps`，删了）。10-26 13:23 重启副屏后它一次拉起 11 个工作资料应用，常驻 3–4 GB，手机可用内存只剩 2 GB、交换区用了 6 GB，整机都慢。现在新副屏是空的，应用等用到时再启动。代价：重建后没被打开过的聊天应用可能被系统清掉，打开之前收不到它的通知。
- 副屏改成 60 Hz（以前 120 Hz）。本想空闲 1 Hz、忙时 60 Hz，但这台的 Android 16 只能在建副屏时定刷新率；实测空闲时 AI 桌面 10 秒画了 0 帧，空闲本来就不耗。看画面的视频流也跟着是 60 帧。
- “设置 → 它的手机”加了“重建副屏”：确认后 App 以 root 停掉再启动执行器（不强杀），换一块新副屏，控制端自己重连。
- 工作资料的那个应用（资料拥有者、副屏上的 AI 桌面）从“Cowork 隔离实验”改名“它的手机”，设置页标题和开头那句说明也改了。
- 改了 Broker 和 App（在主 APK 里）以及 isolation APK。JVM 401 个测试通过（少的 5 个是删掉的 `ParkedAppsTest`）。10-26 14:00 部署：两个 APK 都装上（“它的手机”仍是工作资料的资料拥有者），Broker 14:00:49 用新 APK 起来，副屏重建为 60 Hz，没有自己拉起任何应用；强制停止了上次被拉起的 9 个工作资料应用（QQ、微信留着），可用内存 2.9 → 3.7 GB，交换区占用约 6 → 3.8 GB。旧 APK 备份在 `/data/local/tmp/cowork-notify-deploy/app-20261026-lazy/`（`prev-app.apk`、`prev-isolation.apk`）。“重建副屏”按钮还没在手机上点过。

# 插件 v37 + App（2026-10-26）：一个会话，压缩放宽

- 它只活在一个会话里：你们俩的聊天、巡视、到点的便条、所有应用的通知都进叫它的那个会话，它记得自己在哪做过什么。以前巡视有自己的会话，每个应用的通知也各有一个；那些会话留着历史，不再有新回合，里面还有效的便条在 App 连上时搬进叫它的会话。
- App 告诉插件现在叫它用的是哪个会话（新的 `coworkUnattended/setOwnerSession`：连上时、清空对话时）。
- 聊天页只显示你们俩说的话和它的回复；巡视、通知、到点便条那几段在事件页。你在它巡视时插话，它先回你，这一轮从你说话起算你的。最近几页都是它自己的事时，聊天页自动往前多读几页。
- 通知：它自己忙时直接插进那一轮，处理完接着做原来的事；你们在聊时等这一轮结束；巡视中途来了通知不换模型。
- Jev 关注说明：没人看着的一轮只能改叫醒它的通知所属的应用（巡视、便条：工作资料的应用），按这一轮的消息判断，不再按会话。
- 留便条去掉了“在哪”；巡视便条贴在叫它的会话里；状态卡上叫它会话的出错写“刚才那件事没做完”（那里现在也跑巡视）。
- 上下文压缩：窗口小于 50 万 token 时用到一半、更大的用到四分之一才压（百万窗口 26.2 万，以前 8 万），每轮最多一次（模型服务报超长时的强制压缩除外），压到四分之一以下；保留最近 4 屏（以前 2 屏），摘要最长 3.2 万 token（以前 1.6 万），摘要时原样保留最近 8% 的窗口（以前 3.2 万）。
- 插件 430 个离线测试、JVM 406 个测试通过。10-26 12:43 部署插件 v37（v36 是撤回的那版，从没部署过，所以跳过这个号）：DSH 停着时换了 11 个文件、加了 `src/standing.mjs`，按 App 当时用的会话写好 `owner-session.json`（`cowork-owner-20261025-232318`）；12:44 装 APK（版本号没变，仍是 0.8.1），重启 Broker 和控制端。打开 App 后巡视便条从旧的巡视会话搬进了叫它的会话，时间规则不变。手机上 12 个插件文件和仓库逐字节一致。备份 `/data/local/dsh-native/cowork-plugin-backup-20261026-one-session`，回滚 `sh /data/local/tmp/cowork-notify-deploy/v37/rollback-v37.sh`（DSH 停着时），再装回同目录的 `prev.apk`。真实的一轮通知、巡视和压缩还没看到。

# Continuo 0.8.1（2026-10-25）：叫它更顺，能当系统的助手

- 快捷开关、状态卡的叫它 / 写便条改成直接在当前画面上开一个窗口（`CallOverlay`），不再启动 Activity：ColorOS 给快捷开关启动的 Activity 加的“从开关处放大卡片”动画没了；半屏自己从底部滑入滑出。
- 能当系统的数字助理（`assist/Assistant.kt`）：在系统设置里选 Continuo 后，系统的助手手势叫它；不读屏幕内容，锁屏不开。
- 叫它的半屏能清空对话：换到新会话、旧会话归档，没到点的便条搬过去。
- “设置 → 它的手机 → 它用的模型”多了“你叫它时”（插件 v29：`unattended-models.json` 多了 `owner`），不动 DSH 的默认模型。
- 长按电源键叫它：ColorOS 把长按电源键写死给了小布（看框架代码确认），于是关掉系统的“长按唤醒小布”，用一个只读监听电源键的 root 小守护进程在长按时打开叫它（`runtime/powerkey/`，开机自启）。
- 收键盘有动画了：键盘开着时关掉半屏，或者接管时收起键盘，键盘会滑下去，不再原地停一下再瞬间消失。
- 巡视不再因为压缩提醒整轮出错（插件 v30）：10-25 15:00 的巡视在 DSH 提醒压缩上下文的那一步直接调了 `compress`，而按需加载要到下一步才列出它，模型服务判成无效工具调用，整轮出错，状态卡一直显示“出了点问题”。上下文的 4 个工具改回常驻。
- 日记改成一天一篇（插件 v33 + App）：模型平时正常说话，提示词不再要它把最后一句写成日记；每晚 23:00 由一个单独的请求按当天的活动记录写一篇（三五百字，不写流水账），模型可以用 `cowork_diary` 翻自己的日记。首页第一栏改成聊天记录（叫它的对话），日记单独一栏。
- 上下文压缩换成插件自己的（插件 v31）：去掉 billion-context-dsh，Cowork 预设挂自己的压缩组。超过 8 万 token 先把旧屏幕换成一行（不调模型），还不够才用专门的中文提示词摘要最早的部分；模型不再有上下文工具和压缩提醒，系统提示也短了一大段。Code 模式回到 DSH 默认的自动摘要。
- 只发变化（APK + 插件 v32）：输入后的屏幕变化不大时，只发相对上一份完整界面的增、改、删；元素编号跨屏不变、任务内不复用。实测在浏览器里等待一下，返回从 55 个元素变成一行。
- 状态卡出错时写清哪件事没做完（“巡视没做完”“你交代的事没做完”，正文是在哪个应用里出错），30 分钟后自己回到空闲；以前是“出了点问题 · 打开 Continuo 看看”，一直挂到下一个任务。
- 动画：接管 / 交还的全屏画面淡入淡出、底栏和按钮交叉过渡、边框淡出、顶部提示；页面切换按层级左右滑；首页标签滑块、日记和便条的列表、“现在”卡片的文字、新装引导的步骤、设置里展开的项、头像换表情时都不再硬切。
- 首页加“事件”栏（插件 v35 + App）：巡视、被通知叫醒、到点的便条在应用会话里跑的每一轮一张卡（时间、类型和应用、最后的回复、打开过的应用、告诉你的话），点开看那一轮的经过；数据是插件的活动记录，经新的 `coworkUnattended/events` 读，不含通知原文。首页四栏（聊天、事件、便条、日记）在页面任意位置都能左右滑，跟手，滑块跟着走。
- 叫它发送不再卡一下：以前发送期间输入框被禁用，键盘收起、半屏跟着跳；现在输入框保持可用，你的话马上显示成“你说”。它干活时，半屏和首页聊天栏显示一行带梗的进度字（`data/Progress.kt`：按这一轮在想、写回复、看屏幕、点、打字、打开哪个应用、搜网页、排队等状态各一池说法，一轮里不重复），不再一直“在想…”。

# Continuo 0.8.0（2026-10-25）：它有了名字和头像，没有聊天框，也没有任务岛

- 首页是今天的日记：顶上“现在”（头像、它在干什么、小画面），下面“日记 / 便条”两页，底部“留便条 / 叫它”。没有抽屉、模式切换和任务列表；Code 模式和全部会话收进“设置 → 开发者”。
- 新装引导：检查环境、起名、选头像、它能自己做什么、权限、打个招呼。名字经插件写进提示词（`identity.json`），只在主人和它之间用。
- 头像：8 个 Q 版贴纸角色 × 9 种状态的矢量贴纸（`assets/avatars/*.av`），带动画和特效；通知用头部特写。转矢量时去掉了线条边上的白毛边和外圈灰边。
- 状态卡取代任务岛：常驻通知标题是它在干什么，按钮叫它 / 写便条 / 看画面（干活时停止）；要你接手时弹“找你”通知。全屏看画面分只看和接手两种，接手有明确的“交还”；Cowork 控制中心拆掉。v27 的接管打字不再换层（原地切焦点）保留。
- 叫它：浮在当前应用上的半屏，默认打字、按住说话，固定会话 `cowork-owner`；入口还有快捷开关、长按图标、分享菜单“交给它”。
- 留便条：必须选时间，App 经插件 `createNote` 直接存进 dsh-schedule（以前由模型代记），回执“✓ 贴上了 → ✓✓ 收到”，改和撤下也有回执；巡视一键开启。
- 日记：插件在每轮结束时记下最后一句回复（`diary.jsonl`），提示词要它写成第一人称；点开看原话，长按批一句。

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
