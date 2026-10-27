package dev.dsh.pocket.data

import kotlin.random.Random

/**
 * What it is doing right now in a running turn, in a few playful words: 叫它's half sheet and the home page's chat show
 * them while a turn runs, instead of a bare "在想…". [now] reads the turn from its transcript rows; a [Phrasebook] picks
 * the words: never the same twice in a row, and none again in a turn until its pool is spent. The words describe what
 * it does (the step and the app), never what it types or reads. Pure, tested on the JVM.
 */
object Progress {
    enum class Step {
        SENT, QUEUED, THINKING, REPLYING, LOOKING, SCREENSHOT, TAPPING, LONG_PRESS, TYPING, SCROLLING, OPENING, BACK, HOME, KEY,
        WAITING, DRAWING, BATCH, READING, PHONE, WEB_SEARCH, WEB_READ, NOTE_WRITE, NOTE_READ, MEMORY, FILES, DIARY, TOOLS, JEV,
        PERMISSION, ASKING, STOPPING, COMPACTING, RETRYING, BUSY, STILL,
    }

    /**
     * The step now. [key] names this step (a new tool call is a new step even of the same kind; a message on its way and
     * the start of its turn share one, so the words stay when the message shows up), [target] the package a launch opens.
     */
    data class Now(val step: Step, val key: String, val target: String? = null)

    private const val START = "start"

    /**
     * [rows] are the session's transcript, [running] whether its turn runs, [queued] whether that turn waits for the
     * screen, [sent] whether the owner's message is still on its way (not in [rows] yet). Null when nothing runs.
     */
    fun now(rows: List<TranscriptRow>, running: Boolean, queued: Boolean = false, sent: Boolean = false): Now? {
        if (sent) return Now(Step.SENT, START)
        if (!running) return null
        if (queued) return Now(Step.QUEUED, "queued")
        val start = rows.indexOfLast { it.role == "user" }
        val last = rows.drop(start + 1).lastOrNull { it.role == "tool" || it.role == "assistant" || it.role == "notice" }
            ?: return Now(Step.SENT, START)
        return when (last.role) {
            "tool" -> if (last.streaming) toolStep(last) else Now(Step.THINKING, "${last.key}:after")
            "assistant" -> when {
                !last.streaming -> Now(Step.THINKING, "${last.key}:after")
                last.text.isNotBlank() -> Now(Step.REPLYING, last.key)
                else -> Now(Step.THINKING, last.key)
            }
            // Runtime records: compaction starting (Transcript's EVENT_LABELS), a model request retried, or anything else.
            else -> Now(when {
                last.title.startsWith("正在整理上下文") -> Step.COMPACTING
                "重试" in last.title -> Step.RETRYING
                else -> Step.THINKING
            }, last.key)
        }
    }

    private fun toolStep(row: TranscriptRow): Now {
        val name = toolName(row)
        val args = toolArguments(row)
        val step = when (name) {
            "phone_ui_tree" -> Step.LOOKING
            "phone_screenshot" -> Step.SCREENSHOT
            "phone_tap" -> Step.TAPPING
            "phone_long_press" -> Step.LONG_PRESS
            "phone_type_text" -> Step.TYPING
            "phone_swipe", "phone_scroll" -> Step.SCROLLING
            "phone_launch_app" -> Step.OPENING
            "phone_key" -> when (args?.text("key")) { "BACK" -> Step.BACK; "HOME" -> Step.HOME; else -> Step.KEY }
            "phone_wait" -> Step.WAITING
            "phone_gesture" -> Step.DRAWING
            "phone_batch" -> Step.BATCH
            "phone_read_page", "phone_read_chat" -> Step.READING
            "phone_status", "phone_apps", "phone_start" -> Step.PHONE
            "phone_grant_permission" -> Step.PERMISSION
            "phone_request_intervention" -> Step.ASKING
            "phone_pause", "phone_stop" -> Step.STOPPING
            "web_search" -> Step.WEB_SEARCH
            "web_fetch" -> Step.WEB_READ
            "cowork_notes", "schedule_list" -> Step.NOTE_READ
            "cowork_diary" -> Step.DIARY
            "cowork_tools" -> Step.TOOLS
            "cowork_jev_focus" -> Step.JEV
            else -> when {
                name.startsWith("schedule_") -> Step.NOTE_WRITE
                name.startsWith("cowork_files") -> Step.FILES
                "memory" in name || "mneme" in name -> Step.MEMORY
                else -> Step.BUSY
            }
        }
        val target = if (step == Step.OPENING) args?.text("packageName")?.trim()?.ifEmpty { null } else null
        return Now(step, row.key, target)
    }

    /** The words of each step; `{app}` lines are used when the app is known (the one on its screen, or the one it opens). */
    val POOLS: Map<Step, List<String>> = mapOf(
        Step.SENT to listOf("收到！这就支棱起来", "已读，马上回（这次是真的）", "接单了，骑手正在赶来", "好嘞，客官稍等", "收到收到，三秒进入状态",
            "听见了，正在穿鞋出门", "得令！", "消息已签收，开始干活", "Roger that，马上处理", "放心交给我", "已接收，正在加载干劲", "这活我接了"),
        Step.QUEUED to listOf("手上还有一单，马上来", "前面那件事收个尾，就到你", "排队取号中，你是 VIP", "副屏被占着，等它腾地方", "正在从上一件事里抽身",
            "先把锅里的菜出锅", "稍等，正在叫号", "插队申请已提交", "马上到，堵在上一件事里了", "在路上了，别催别催"),
        Step.THINKING to listOf("CPU 已烧开，正在思考", "脑细胞加班中", "让我想想…嗯…", "正在头脑风暴", "思考人生，顺便想想你的事", "大脑高速运转，风扇已起飞",
            "在悟，快悟了", "这题我会，让我组织一下", "正在调动全部智商", "琢磨琢磨下一步", "盘算中，别打扰我的灵感", "托腮思考.jpg", "沉思三秒钟",
            "思路正在加载，进度 99%", "我寻思这事得这么办", "灵感正在路上，可能堵车了"),
        Step.REPLYING to listOf("正在疯狂打字回你", "组织语言中，这次争取说人话", "回复正在出炉", "措辞中，力求言简意赅", "斟酌一下语气", "小作文写作中",
            "键盘敲出火星了", "打字中…不是已读不回", "回你的话正在排版", "憋大招中，马上说完"),
        Step.LOOKING to listOf("让我康康", "瞄一眼屏幕", "看看现在是啥情况", "睁大眼睛看界面", "扫描屏幕中，滴——", "观察一下局势", "先看清楚再动手",
            "目光如炬地盯着屏幕", "看看到哪一步了", "侦察兵出动", "看看 {app} 在搞什么", "在 {app} 里东张西望", "研究一下 {app} 的界面"),
        Step.SCREENSHOT to listOf("咔嚓，截个图", "拍照留念，顺便看清楚", "截图取证中", "放大镜已就位", "定格这一帧仔细瞧", "截屏看看，眼见为实",
            "来张高清大图", "截图研究一下细节", "给 {app} 拍张证件照"),
        Step.TAPPING to listOf("戳一下", "精准点击，手稳得很", "就是这个按钮，点它", "轻轻一点，深藏功与名", "手指已出击", "点点点", "啪，按下去了",
            "一指禅发动", "点它！", "确认过眼神，是要点的按钮", "在 {app} 里点点点", "在 {app} 里戳一下"),
        Step.LONG_PRESS to listOf("长按不放，看它有啥反应", "按住…再按住…", "长按召唤隐藏菜单", "手指按住，绝不松手", "长按蓄力中", "按住三秒，看它服不服"),
        Step.TYPING to listOf("噼里啪啦打字中", "键盘侠上线", "一字一句认真敲", "手速拉满", "打字中，错别字退散", "正在码字", "输入法冒烟了",
            "在输入框里挥毫泼墨", "敲字敲到飞起", "字斟句酌地打", "在 {app} 里打字", "在 {app} 里码字"),
        Step.SCROLLING to listOf("往下翻翻", "滑呀滑", "手指在屏幕上滑冰", "翻页找找看", "滚动中，别晕", "上下求索", "刷一刷", "一路滑下去找",
            "翻箱倒柜找东西", "划走，下一屏", "在 {app} 里刷刷刷", "在 {app} 里翻来翻去"),
        Step.OPENING to listOf("打开一个应用", "换个地方干活", "启动！", "切个应用", "打开 {app}，冲", "召唤 {app}", "传送到 {app}", "去 {app} 串个门",
            "{app}，启动！", "推开 {app} 的大门"),
        Step.BACK to listOf("返回上一页", "退！退！退！", "撤回一步", "按返回，倒车请注意", "原路返回", "此路不通，掉头"),
        Step.HOME to listOf("回到桌面", "一键回城", "先回桌面冷静一下", "Home 键，回家"),
        Step.KEY to listOf("按个键", "按键伺候", "物理外挂：按一下", "咔哒，按下"),
        Step.WAITING to listOf("等它加载，转圈圈中", "页面加载中，喝口水", "网速：我尽力了", "等界面刷出来", "耐心等待，好饭不怕晚", "等一下，它还在转",
            "让子弹飞一会儿", "加载条：正在努力", "等 {app} 慢慢醒过来"),
        Step.DRAWING to listOf("灵魂画手上线", "提笔作画", "手指当画笔", "一笔一画，认真描", "画画中，毕加索附体", "在屏幕上比划比划"),
        Step.BATCH to listOf("连招发动", "一套组合拳", "几步一起做，效率拉满", "行云流水连续操作", "一气呵成", "操作连击中"),
        Step.READING to listOf("逐字阅读中", "认真读页面上的字", "看看这页写了啥", "阅读理解开始", "一目十行扫一遍", "细细品读", "读读 {app} 上写了什么"),
        Step.PHONE to listOf("检查一下手机状态", "翻翻装了哪些应用", "给手机做个体检", "热身一下，准备开工", "清点一下装备", "确认手机还活着"),
        Step.WEB_SEARCH to listOf("上网冲浪中", "搜一搜，涨知识", "问问搜索引擎", "在互联网的海洋里捞针", "查资料中，比你想的认真", "搜索中，知识就是力量",
            "全网搜罗", "翻翻网上怎么说"),
        Step.WEB_READ to listOf("打开网页细看", "读文章中，别催", "网页加载，阅读理解", "啃一啃这篇网页", "看看人家怎么写的", "浏览网页，绝不摸鱼"),
        Step.NOTE_WRITE to listOf("记小本本上", "贴张便条，免得忘", "好记性不如烂便条", "写便条中", "记下来，到点提醒", "整理便条"),
        Step.NOTE_READ to listOf("翻翻便条", "看看还有啥没办", "检查待办清单", "翻小本本", "看看贴了哪些便条"),
        Step.MEMORY to listOf("翻翻记忆", "调取记忆中", "回忆一下以前", "搜索脑内硬盘", "记性好不好，就看这一下", "从记忆宫殿里找找", "记下这件事，下次就会了"),
        Step.FILES to listOf("翻文件中", "打开文件夹看看", "读写文件", "整理文档", "在文件堆里刨"),
        Step.DIARY to listOf("翻翻日记本", "回顾一下前几天", "看看以前的自己写了啥", "读日记，有点害羞"),
        Step.TOOLS to listOf("从工具箱里掏家伙", "装备升级中", "拿出趁手的工具", "工具已就位"),
        Step.JEV to listOf("调整通知雷达", "设置一下哪些消息值得叫我", "校准通知过滤器", "给通知把把关"),
        Step.PERMISSION to listOf("处理一下权限弹窗", "给应用授个权", "搞定权限这一关", "通行证办理中"),
        Step.ASKING to listOf("这步需要你出手", "请求场外援助", "摇人中：需要你", "卡住了，等你来救场"),
        Step.STOPPING to listOf("收手中", "停下来喘口气", "踩刹车", "收拾收拾停一下"),
        Step.COMPACTING to listOf("整理一下前面的聊天", "清理脑内缓存", "把前情提要压缩一下", "记性太满，腾点地方", "整理思路中", "大扫除：上下文版"),
        Step.RETRYING to listOf("刚才没成，再来一次", "失败是成功之母，重来", "网络抽风了，重试", "再试一遍，这次一定", "不服，再来", "重新连线中"),
        Step.BUSY to listOf("在忙，马上好", "办事中", "处理中，请稍候", "正在搞定", "干活中，勿扰", "认真营业中"),
        // One step lasting a while: after its own pool, these say it is still going.
        Step.STILL to listOf("还在弄，没卡住", "别急，慢工出细活", "马上就好，真的", "这步有点磨人", "还没完，再坚持一下", "进度条走得慢，但在走",
            "稍等，正在努力中", "快了快了", "没睡着，在干活", "多给我几秒", "有点难，但我能行", "坚持就是胜利"),
    )

    /** A step's lines for [app] (an app name, or null when unknown): its `{app}` lines filled in, or left out. */
    fun lines(step: Step, app: String?): List<String> {
        val pool = POOLS[step] ?: POOLS.getValue(Step.BUSY)
        val name = app?.trim()?.ifEmpty { null }
        return pool.mapNotNull { line -> if ("{app}" !in line) line else name?.let { line.replace("{app}", it) } }
    }

    /**
     * Words for one session's turns. [pick] gives the words for a step; [lasting] is true when the same step goes on and
     * the words change only to show it is alive. Until [reset] (the turn is over) no line repeats before its pool is
     * spent, and never the same line twice in a row.
     */
    class Phrasebook(private val random: Random = Random.Default) {
        private val used = HashSet<String>()
        private var last: String? = null

        fun reset() { used.clear() }

        fun pick(step: Step, app: String?, lasting: Boolean = false): String {
            val own = lines(step, app)
            val fresh = own.filter { it !in used && it != last }
            val choice = fresh.randomOrNull(random)
                ?: (if (lasting) lines(Step.STILL, null).filter { it !in used && it != last }.randomOrNull(random) else null)
                ?: run {
                    // Every line of this step was shown in this turn: start its pool over, never with the line just shown.
                    used.removeAll(own.toSet())
                    (own.filter { it != last }.ifEmpty { own }).random(random)
                }
            used += choice
            last = choice
            return choice
        }
    }
}
