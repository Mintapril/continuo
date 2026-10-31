package dev.dsh.pocket.cowork

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import dev.dsh.pocket.MainActivity
import dev.dsh.pocket.R
import dev.dsh.pocket.avatar.Stickers
import dev.dsh.pocket.data.IdentityStore
import dev.dsh.pocket.island.IslandAction
import dev.dsh.pocket.island.IslandExecution
import dev.dsh.pocket.island.IslandRuntime
import dev.dsh.pocket.notify.NotifyCenter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.Calendar

/**
 * The AI's presence on this phone, independent of any Activity: keeps the controller channel to the broker, shows the
 * status card (the ongoing notification: what it is doing, with 叫它 / 写便条 / 看画面, and 停止 while it works), tells
 * the owner when it needs them (找你), and owns the full-screen viewer and takeover ([TakeoverScreen]).
 * No boot receiver, sticky restart, new credentials in Intents, or automatic permission grant.
 */
class CoworkControllerService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var connecting:Job?=null
    private var admitted=false
    private lateinit var takeover:TakeoverScreen
    override fun onCreate() {
        super.onCreate()
        IslandRuntime.restore(applicationContext)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"它的状态",NotificationManager.IMPORTANCE_LOW).apply {
            description="它在做什么；从这里叫它、写便条、看它的手机"
            setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        // Something the owner must see even with Continuo in the background: the AI asks them to take its screen, a run
        // nobody watched needs them, or a task was stopped for safety.
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,"找你",NotificationManager.IMPORTANCE_HIGH).apply {
            description="它需要你接手、需要你决定，或者被安全检查停下时提醒"
            setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        try {
            val first=card()
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,first,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID,first)
            admitted=true
        }catch(_:RuntimeException){CoworkConnection.controllerUnavailable("无法启动后台控制服务，请保持应用前台并重试");stopSelf();return}
        takeover=TakeoverScreen(this,scope){IdentityStore.current(this).shown}
        val identity=IdentityStore.flow(this)
        scope.launch {
            var last=""
            // The minute ticks too: the idle face follows the time of day (asleep at night, eating at lunch).
            val minutes=kotlinx.coroutines.flow.flow{while(true){emit(System.currentTimeMillis()/60_000);delay(60_000L-System.currentTimeMillis()%60_000L)}}
            combine(CoworkConnection.connection,CoworkConnection.status,identity,takeover.accepted,minutes){_,_,_,_,_->Unit}.collect {
                val status=CoworkConnection.status.value
                val built=StatusCard.of(identity.value.shown,CoworkConnection.connection.value,status,takeover.accepted.value,hour(),minute(),ErrorClock.ageMs(this@CoworkControllerService,status))
                val key="$built|${target()}|${identity.value.avatar}"
                if(key!=last){
                    last=key
                    val face=face(identity.value.avatar,built.mood)
                    try{manager.notify(ID,card(built,face))}catch(_:SecurityException){}
                }
            }
        }
        // The viewer and takeover follow the broker: whoever starts a takeover (this phone, or the owner accepting the
        // model's request), the full screen shows while the owner is in control and goes when control returns.
        scope.launch { CoworkConnection.status.collect { takeover.sync(it) } }
        scope.launch { CoworkConnection.connection.collect { takeover.sync(CoworkConnection.status.value) } }
        scope.launch { CoworkConnection.live.collect { takeover.frame(it) } }
        scope.launch { CoworkConnection.field.collect { takeover.onFieldChanged() } }
        scope.launch {
            var lastKey=""
            CoworkConnection.status.collect { state ->
                val reason=state.optString("pauseReason")
                val phase=state.optString("state")
                val asked=state.optBoolean("userControl")&&state.optString("interventionBy")=="model"
                val needs=asked||(reason.isNotBlank()&&!reason.startsWith("USER_")&&phase in setOf("pausing","paused","stopping","waiting_user"))
                val shown=if(asked)"INTERVENTION_REQUIRED" else reason
                val key=if(needs)"${state.optString("taskId")}|${state.optString("interventionId")}|${if(asked)"" else state.optString("revision")}|$shown" else ""
                if(!needs){
                    if(lastKey.isNotEmpty())try{manager.cancel(ALERT_ID)}catch(_:SecurityException){}
                    lastKey=""
                }else if(key!=lastKey){
                    lastKey=key
                    val face=face(IdentityStore.current(this@CoworkControllerService).avatar,StatusCard.Mood.CALL)
                    try{manager.notify(ALERT_ID,alert(shown,state.optString("interventionReason"),face))}catch(_:SecurityException){}
                }
            }
        }
        // Notes the AI left when a run nobody watched needed you (phone_notify_owner): one notification each, never
        // replaced or cancelled by the alert above; the last one shown is remembered across restarts.
        scope.launch {
            val prefs=getSharedPreferences("preferences",0)
            CoworkConnection.status.collect { state ->
                val fresh=OwnerNoticeFeed.fresh(state,prefs.getString("notice_instance","").orEmpty(),prefs.getLong("notice_seq",0))
                if(fresh.isEmpty())return@collect
                val instance=state.optString("instanceId")
                val face=face(IdentityStore.current(this@CoworkControllerService).avatar,StatusCard.Mood.CALL)
                for(n in fresh)try{manager.notify("notice-$instance-${n.seq}",NOTICE_ID,notice(n,face))}catch(_:SecurityException){}
                prefs.edit().putString("notice_instance",instance).putLong("notice_seq",fresh.last().seq).apply()
            }
        }
        // The broker reads the notifications; this keeps its settings in step with the ones edited here.
        NotifyCenter.init(this)
    }
    /** Its head in [mood], for a notification's large icon (drawn off the main thread; null if it cannot be read). */
    private suspend fun face(avatar:String,mood:StatusCard.Mood):android.graphics.Bitmap?=withContext(Dispatchers.Default){
        runCatching{Stickers.render(this@CoworkControllerService,avatar,mood.key,(64*resources.displayMetrics.density).toInt(),head=true,background=0xFFE8ECF2.toInt())}.getOrNull()
    }
    private fun hour()=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun minute()=Calendar.getInstance().get(Calendar.MINUTE)
    private fun name()=IdentityStore.current(this).shown

    private fun activity(code:Int,intent:Intent)=PendingIntent.getActivity(this,code,intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun open(code:Int,what:String?=null)=activity(code,Intent(this,MainActivity::class.java).apply{if(what!=null)putExtra("open",what)})
    /** 叫它 / 写便条 float over whatever app is in front, without bringing Continuo forward: through [CoworkShadeActivity]
     *  too, which shows the sheet as a window ([dev.dsh.pocket.CallOverlay]) over what is on screen. */
    private fun sheet(code:Int,note:Boolean)=shade(code,if(note)CoworkShadeActivity.NOTE else CoworkShadeActivity.CALL)
    /** Through [CoworkShadeActivity], so the notification shade closes before the full screen appears. */
    private fun shade(code:Int,action:String)=activity(code,Intent(this,CoworkShadeActivity::class.java).setAction(action))
    private fun button(label:String,intent:PendingIntent)=Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.island_notification),label,intent).build()

    private fun target():String{
        val s=CoworkConnection.status.value
        return "${s.optString("taskId")}|${s.optLong("revision",-1)}|${s.optString("instanceId")}|${s.optString("executionId")}"
    }

    private fun card(built:StatusCard.Card=StatusCard.of(name(),CoworkConnection.connection.value,CoworkConnection.status.value,false,hour(),minute(),ErrorClock.ageMs(this,CoworkConnection.status.value)),face:android.graphics.Bitmap?=null):Notification {
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.island_notification)
            .setContentTitle(built.title).setContentText(built.text).setContentIntent(open(73))
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.island_notification).setContentTitle(name()).setContentText("解锁后查看").build())
        if(face!=null)builder.setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(face))
        val linked=CoworkConnection.connection.value==CoworkLinkState.CONNECTED&&CoworkConnection.status.value.optInt("displayId")>0
        when {
            built.asking -> { builder.addAction(button("去接手",shade(80,CoworkShadeActivity.ACCEPT))); builder.addAction(button("看画面",shade(81,CoworkShadeActivity.WATCH))) }
            built.ownerControl -> { builder.addAction(button("看画面",shade(81,CoworkShadeActivity.WATCH))); builder.addAction(button("交还",service(82,HAND_BACK))) }
            else -> {
                builder.addAction(button("叫它",sheet(77,false)))
                if(built.working)stopIntent()?.let{builder.addAction(button("看画面",shade(81,CoworkShadeActivity.WATCH)));builder.addAction(button("停止",it))}
                else{builder.addAction(button("写便条",sheet(78,true)));if(linked)builder.addAction(button("看画面",shade(81,CoworkShadeActivity.WATCH)))}
            }
        }
        return builder.build()
    }
    private fun service(code:Int,action:String)=PendingIntent.getService(this,code,Intent(this,CoworkControllerService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    /** Each click carries the task it was shown for, so an old card cannot stop a newer task. */
    private fun stopIntent():PendingIntent?{
        val s=CoworkConnection.status.value
        val task=s.optString("taskId").takeIf{it.isNotBlank()}?:return null
        val intent=Intent(this,CoworkControllerService::class.java).setAction(STOP)
            .setData(android.net.Uri.Builder().scheme("dsh-card").authority("stop").appendPath(task).appendPath(s.optString("instanceId")).appendPath(s.optString("executionId")).appendPath(s.optLong("revision",-1).toString()).build())
            .putExtra(EXTRA_TASK,task).putExtra(EXTRA_REVISION,s.optLong("revision",-1)).putExtra(EXTRA_INSTANCE,s.optString("instanceId")).putExtra(EXTRA_EXECUTION,s.optString("executionId"))
        return PendingIntent.getService(this,83,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun notice(n:OwnerNoticeFeed.Notice,face:android.graphics.Bitmap?):Notification =
        Notification.Builder(this,ALERT_CHANNEL).setSmallIcon(R.drawable.island_notification).apply{if(face!=null)setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(face))}
            .setContentTitle("${name()}找你").setContentText(n.text).setStyle(Notification.BigTextStyle().bigText(n.text))
            .setWhen(n.at).setShowWhen(true).setContentIntent(open(75,"chat")).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .addAction(button("回它",sheet(79,false)))
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    private fun alert(reason:String,detail:String="",face:android.graphics.Bitmap?=null):Notification {
        val asking=reason=="INTERVENTION_REQUIRED"
        val title=if(asking)"${name()}找你" else "${name()}停下了"
        val text=when(reason){
            "INTERVENTION_REQUIRED"->"需要你接手它的手机${if(detail.isBlank())"" else "：$detail"}。弄好后点“交还”。"
            "CONTROL_CHANNEL_OFFLINE"->"和它的手机断开了，这件事停了。连上后再叫它。"
            "APP_DENIED","APP_CONFLICT","SECURITY_UNAVAILABLE"->"安全检查没通过（$reason），它先停下了。打开 Continuo 看看。"
            else->"它先停下了（$reason）。打开 Continuo 看看。"
        }
        val builder=Notification.Builder(this,ALERT_CHANNEL).setSmallIcon(R.drawable.island_notification)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(if(asking)shade(84,CoworkShadeActivity.ACCEPT) else open(74)).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if(face!=null)builder.setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(face))
        if(asking)builder.addAction(button("去接手",shade(85,CoworkShadeActivity.ACCEPT)))
        return builder.build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!admitted)return START_NOT_STICKY
        when(intent?.action){
            CONNECT->{kick=true;supervise()}
            WATCH->if(!linked())offline() else if(!takeover.watch())unavailable()
            ACCEPT->if(!linked())offline() else if(!takeover.accept())unavailable()
            HAND_BACK->scope.launch{runCatching{CoworkConnection.takeover(false)}}
            STOP->{
                val task=intent.getStringExtra(EXTRA_TASK).orEmpty()
                if(task.isNotBlank())IslandRuntime.dispatch(task,intent.getLongExtra(EXTRA_REVISION,-1),IslandAction.STOP,
                    IslandExecution(intent.getStringExtra(EXTRA_INSTANCE).orEmpty(),intent.getStringExtra(EXTRA_EXECUTION).orEmpty()))
            }
            else->if(connecting==null)stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun linked()=CoworkConnection.connection.value==CoworkLinkState.CONNECTED&&CoworkConnection.status.value.optInt("displayId")>0
    private fun offline()=android.widget.Toast.makeText(this,"还没连上${name()}的手机，稍后再看",android.widget.Toast.LENGTH_SHORT).show()
    /** No overlay permission and no accessibility layer: send the owner to the system page that grants it. */
    private fun unavailable(){
        android.widget.Toast.makeText(this,"要先允许 Continuo 显示在其他应用上层，才能看它的手机",android.widget.Toast.LENGTH_LONG).show()
        runCatching{startActivity(IslandRuntime.overlaySettingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    }
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);if(::takeover.isInitialized)takeover.reopen()}
    @Volatile private var kick=false
    /** Self-healing connection: connects at once, reconnects after a lost link, backs off on failure and gives up
     *  after [MAX_FAILURES] consecutive failures (the next app launch or task request starts it again). */
    private fun supervise(){
        if(connecting?.isActive==true)return
        connecting=scope.launch {
            var failures=0
            var lostSince=0L
            while(isActive){
                val link=CoworkConnection.connection.value
                when {
                    link==CoworkLinkState.CONNECTED->{failures=0;lostSince=0L;kick=false;delay(2000);continue}
                    link.connecting->{delay(1000);continue}
                    link==CoworkLinkState.LOST&&!kick->{
                        // The poll loop recovers transient hiccups by itself; reconnect only when it stays down.
                        val now=System.currentTimeMillis()
                        if(lostSince==0L)lostSince=now
                        if(now-lostSince<6000){delay(1000);continue}
                    }
                }
                kick=false;lostSince=0L
                try{CoworkConnection.connect(applicationContext);failures=0}
                catch(e:CancellationException){throw e}
                catch(_:Exception){
                    failures++
                    if(failures>=MAX_FAILURES){stopSelf();return@launch}
                    delay(3000L*failures)
                }
            }
        }
    }
    override fun onDestroy(){
        if(::takeover.isInitialized)takeover.close(animated=false)
        scope.cancel();CoworkConnection.controllerStopped();super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="cowork-controller"
        private const val ID=7131
        private const val ALERT_ID=7132
        private const val NOTICE_ID=7133
        private const val ALERT_CHANNEL="cowork-alert"
        private const val CONNECT="dev.dsh.pocket.cowork.CONNECT"
        internal const val WATCH="dev.dsh.pocket.cowork.WATCH"
        internal const val ACCEPT="dev.dsh.pocket.cowork.ACCEPT"
        private const val HAND_BACK="dev.dsh.pocket.cowork.HAND_BACK"
        private const val STOP="dev.dsh.pocket.cowork.STOP"
        private const val EXTRA_TASK="taskId"
        private const val EXTRA_REVISION="revision"
        private const val EXTRA_INSTANCE="instanceId"
        private const val EXTRA_EXECUTION="executionId"
        private const val MAX_FAILURES=6
        /** User preference "自动连接执行器" (default on). Stored with the other app preferences. */
        fun autoConnect(context:Context):Boolean=context.applicationContext.getSharedPreferences("preferences",0).getBoolean("cowork_auto",true)
        fun setAutoConnect(context:Context,enabled:Boolean){
            context.applicationContext.getSharedPreferences("preferences",0).edit().putBoolean("cowork_auto",enabled).apply()
            if(enabled)ensureRunning(context) else runCatching{disconnectIdle(context)}
        }
        /** Called from visible Activities (launch/resume): starts the self-healing controller unless the user switched it off
         *  or it is already running. Never throws; a refusal is reported through the connection state. */
        fun ensureRunning(context:Context){
            if(!autoConnect(context))return
            if(CoworkConnection.connection.value==CoworkLinkState.CONNECTED)return
            try{startUserInitiated(context)}catch(_:RuntimeException){}
        }
        /** Returns immediately; the service owns the connection attempts. */
        fun startUserInitiated(context:Context){
            try{context.startForegroundService(Intent(context,CoworkControllerService::class.java).setAction(CONNECT))}
            catch(e:RuntimeException){CoworkConnection.controllerUnavailable("Android 拒绝后台控制服务启动，请在前台重试");throw e}
        }
        /** 看画面 / 去接手 from a visible screen of this app. The controller must already run (it does while connected). */
        fun show(context:Context,action:String){
            runCatching{context.startService(Intent(context,CoworkControllerService::class.java).setAction(action))}
        }
        /** Caller must first verify no active task/display; does not terminate the root broker. */
        fun disconnectIdle(context:Context){
            val state=CoworkConnection.status.value
            check(CoworkConnection.connection.value==CoworkLinkState.CONNECTED&&!state.optBoolean("workspaceActive")&&state.optString("state") in setOf("idle","completed","stopped","paused","error")){"请先停止当前任务并等待执行器确认"}
            context.stopService(Intent(context,CoworkControllerService::class.java))
        }
    }
}
