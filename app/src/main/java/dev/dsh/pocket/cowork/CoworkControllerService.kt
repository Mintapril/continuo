package dev.dsh.pocket.cowork

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.dsh.pocket.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Explicit foreground controller lifetime, independent of Activity and optional island overlay.
 * No boot receiver, sticky restart, new credentials in Intents, or automatic permission grant.
 */
class CoworkControllerService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var connecting:Job?=null
    private var admitted=false
    override fun onCreate() {
        super.onCreate()
        dev.dsh.pocket.island.IslandRuntime.restore(applicationContext)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Cowork 后台控制",NotificationManager.IMPORTANCE_LOW).apply {
            description="保持用户已连接的执行器控制通道；与任务岛开关独立"
            setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        try {
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification("正在连接执行器"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID,notification("正在连接执行器"))
            admitted=true
        }catch(_:RuntimeException){CoworkConnection.controllerUnavailable("无法启动后台控制服务，请保持应用前台并重试");stopSelf();return}
        scope.launch {
            combine(CoworkConnection.connection,CoworkConnection.status){link,state->
                when {
                    link!=CoworkLinkState.CONNECTED->link.label
                    state.optString("state") in setOf("running","pausing","stopping","waiting_user")->"已连接 · ${state.optString("state")} · 点此暂停或停止"
                    else->"已连接待命 · 可将 Continuo 转入后台"
                }
            }.collect { label ->
                try{manager.notify(ID,notification(label))}catch(_:SecurityException){}
            }
        }
        // The island follows the controller: once connected and enabled it is armed without another click.
        scope.launch {
            var armed=false
            CoworkConnection.connection.collect { link ->
                if(link==CoworkLinkState.CONNECTED&&!armed){armed=true;dev.dsh.pocket.island.IslandRuntime.startUserInitiated(applicationContext)}
                if(link!=CoworkLinkState.CONNECTED)armed=false
            }
        }
        // A paused/stopping task the user has not caused must be visible even with Pocket in the background.
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,"Cowork 需要你处理",NotificationManager.IMPORTANCE_HIGH).apply {
            description="AI 请求介入或任务被安全暂停时提醒；不包含屏幕内容"
            setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        scope.launch {
            var lastKey=""
            CoworkConnection.status.collect { state ->
                val reason=state.optString("pauseReason")
                val phase=state.optString("state")
                val takeoverAsked=state.optBoolean("userControl")&&state.optString("interventionBy")=="model"
                val needs=takeoverAsked||(reason.isNotBlank()&&!reason.startsWith("USER_")&&phase in setOf("pausing","paused","stopping","waiting_user"))
                val shown=if(takeoverAsked)"INTERVENTION_REQUIRED" else reason
                val key=if(needs)"${state.optString("taskId")}|${state.optString("interventionId")}|${if(takeoverAsked)"" else state.optString("revision")}|$shown" else ""
                if(!needs){
                    if(lastKey.isNotEmpty())try{manager.cancel(ALERT_ID)}catch(_:SecurityException){}
                    lastKey=""
                }else if(key!=lastKey){
                    lastKey=key
                    try{manager.notify(ALERT_ID,alert(shown,state.optString("interventionReason")))}catch(_:SecurityException){}
                }
            }
        }
    }
    private fun alert(reason:String,detail:String=""):Notification {
        val open=PendingIntent.getActivity(this,74,Intent(this,CoworkActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=when(reason){
            "INTERVENTION_REQUIRED"->"AI 请求你接管副屏${if(detail.isBlank())"" else "：$detail"}。请打开 Cowork 直接操作，完成后点“交还给 AI”。"
            "CONTROL_CHANNEL_OFFLINE"->"控制通道已断开，任务已停止。请重新连接后再发布任务。"
            "APP_DENIED","APP_CONFLICT","SECURITY_UNAVAILABLE"->"任务因安全检查被暂停（$reason）。请打开 Cowork 查看。"
            else->"任务已暂停（$reason）。请打开 Cowork 查看。"
        }
        return Notification.Builder(this,ALERT_CHANNEL).setSmallIcon(R.drawable.island_notification)
            .setContentTitle("Cowork 需要你处理").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!admitted)return START_NOT_STICKY
        if(intent?.action!=CONNECT){if(connecting==null)stopSelf();return START_NOT_STICKY}
        kick=true
        supervise()
        return START_NOT_STICKY
    }
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
    private fun notification(label:String):Notification {
        val open=PendingIntent.getActivity(this,73,Intent(this,CoworkActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.island_notification)
            .setContentTitle("Cowork 后台控制").setContentText(label).setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    override fun onDestroy(){
        scope.cancel();CoworkConnection.controllerStopped();super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="cowork-controller"
        private const val ID=7131
        private const val ALERT_ID=7132
        private const val ALERT_CHANNEL="cowork-alert"
        private const val CONNECT="dev.dsh.pocket.cowork.CONNECT"
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
        /** Caller must first verify no active task/display; does not terminate the root broker. */
        fun disconnectIdle(context:Context){
            val state=CoworkConnection.status.value
            check(CoworkConnection.connection.value==CoworkLinkState.CONNECTED&&!state.optBoolean("workspaceActive")&&state.optString("state") in setOf("idle","completed","stopped","paused","error")){"请先停止当前任务并等待执行器确认"}
            context.stopService(Intent(context,CoworkControllerService::class.java))
        }
    }
}
