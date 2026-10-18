package dev.dsh.pocket.cowork

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.island.*
import dev.dsh.pocket.ui.PocketTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class ControlConfirmation(val action:String,val target:CoworkControlTarget,val approvalId:String?=null,val details:String="")

/** Explicit user control surface. Never exported; credentials cannot arrive in intents. */
class CoworkActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event:MotionEvent):Boolean =
        if(IslandTouchGuard.isObscured(event))true else super.dispatchTouchEvent(event)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        IslandRuntime.restore(applicationContext)
        CoworkControllerService.ensureRunning(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { PocketTheme("system") {
            val status by CoworkConnection.status.collectAsState()
            val connectionError by CoworkConnection.error.collectAsState()
            val connection by CoworkConnection.connection.collectAsState()
            val apps by CoworkConnection.apps.collectAsState()
            val preview by CoworkConnection.preview.collectAsState()
            val snapshot by IslandRuntime.store.snapshots.collectAsState()
            val availability by IslandRuntime.availability.collectAsState()
            val scope=rememberCoroutineScope()
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var confirm by remember { mutableStateOf<ControlConfirmation?>(null) }
            var appFilter by remember { mutableStateOf("") }
            val hasWorkspace=status.optInt("displayId")>0&&status.optBoolean("workspaceActive")
            val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
                if(!granted)error="通知权限未开启，通知降级入口可能不可见"
            }
            fun perform(action:suspend ()->Unit){scope.launch{busy=true;try{action();error=""}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"操作未完成"}finally{busy=false}}}
            fun confirmation(action:String,approvalId:String?=null,details:String="") = ControlConfirmation(action,CoworkControlTarget.from(status),approvalId,details)
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick={finish()}){Text("返回工作台")}
                    Text("Cowork 控制中心",style=MaterialTheme.typography.headlineMedium)
                    Text("独立副屏 · 可随时暂停和停止",style=MaterialTheme.typography.titleMedium)
                    Text("连接会启动本机执行器，需要 KernelSU 对 Continuo 的明确授权。手机操作仅限已验证的工作资料，不向模型提供 Shell。个人主屏切换不再作为暂停原因；锁屏、工作资料失效或控制通道中断仍会停止执行。")
                    Text("工作资料应用无需逐项加入白名单。真实应用的具体交互仍需确认，可从个人主屏返回这里查看。副屏不抢主屏焦点，不使用共享输入法；模型优先读取页面结构并按元素点击，仅在结构不足时才截图；系统按键仅限返回/回车，不提供主页键。",style=MaterialTheme.typography.bodySmall)
                    Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("执行器：${connection.label}",style=MaterialTheme.typography.titleMedium,
                            color=if(connection in setOf(CoworkLinkState.FAILED,CoworkLinkState.LOST))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(if(connection==CoworkLinkState.CONNECTED)"已自动连接；发布 Cowork 任务时无需任何手动操作。" else connection.hint,style=MaterialTheme.typography.bodySmall)
                        if(connection.connecting)LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(connection in setOf(CoworkLinkState.FAILED,CoworkLinkState.LOST,CoworkLinkState.DISCONNECTED))OutlinedButton(onClick={perform{CoworkControllerService.startUserInitiated(this@CoworkActivity)}},enabled=!busy){Text("重试")}
                    } }
                    Text("执行状态：${if(connection==CoworkLinkState.CONNECTED)snapshot.statusLabel else "正在自动连接执行器"}",style=MaterialTheme.typography.titleLarge)
                    if(status.optString("packageName").isNotBlank())Text("副屏应用：${status.optString("packageName")}")
                    if(error.isNotBlank()||connectionError.isNotBlank())Text(error.ifBlank{connectionError},color=MaterialTheme.colorScheme.error)
                    if(status.optBoolean("restartRequired"))Text("工作资料应用离开自有副屏或身份检查失败。请停止并检查后重新开始；不会自动恢复。",color=MaterialTheme.colorScheme.error)
                    val alert=status.optString("displayAlert")
                    if(alert.isNotBlank()){
                        val reason=when(alert){
                            "LOCKED"->"手机处于锁屏状态"
                            "SECURITY_UNAVAILABLE"->"工作资料或副屏身份检查失败"
                            "NO_DISPLAY"->"副屏不存在"
                            else->alert
                        }
                        Text("副屏重建失败（$reason）。执行器已自动重试多次仍未成功：请确认工作资料可用、手机已解锁，再点“停止”后重新开始；仍然不行请重启手机。",color=MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={val target=CoworkControlTarget.from(status);perform{CoworkConnection.control("pause",target)}},enabled=!busy&&status.optString("state")=="running"){Text("暂停")}
                        if(!status.optBoolean("taskManaged"))OutlinedButton(onClick={confirm=confirmation("resume")},enabled=!busy&&hasWorkspace&&status.optString("state")=="paused"&&!status.optBoolean("restartRequired")){Text("恢复")}
                        Button(onClick={confirm=confirmation("stop")},enabled=!busy&&(hasWorkspace||status.optString("state") in setOf("running","waiting_user","pausing","stopping"))){Text("停止")}
                    }
                    if(hasWorkspace){
                        val userControl=status.optBoolean("userControl")
                        val liveFrame by CoworkConnection.live.collectAsState()
                        DisposableEffect(Unit){CoworkConnection.acquireLive();onDispose{CoworkConnection.releaseLive()}}
                        var typed by remember { mutableStateOf("") }
                        var box by remember { mutableStateOf(IntSize.Zero) }
                        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("副屏实时画面",style=MaterialTheme.typography.titleMedium)
                            if(userControl&&status.optString("interventionBy")=="model")Text("AI 请求你介入${status.optString("interventionReason").let{if(it.isBlank())"" else "：$it"}}。请在下方画面直接操作，完成后点“交还给 AI”。",color=MaterialTheme.colorScheme.error)
                            else if(userControl)Text("你正在操作副屏，AI 已暂停操作；完成后点“交还给 AI”。")
                            else Text("只读实时画面（硬件编码视频，最高跟随副屏 120 帧/秒，静止时不耗流量）。双击任务岛预览或点“接管操作”后才能触控，AI 会等待你交还。",style=MaterialTheme.typography.bodySmall)
                            val videoInfo by LiveVideo.info.collectAsState()
                            val vw=(videoInfo?.width ?: 720).toFloat();val vh=(videoInfo?.height ?: 1608).toFloat()
                            Box(Modifier.fillMaxWidth(0.8f).aspectRatio(vw/vh)) {
                                liveFrame?.let{ Image(it.asImageBitmap(),contentDescription=null,modifier=Modifier.matchParentSize()) }
                                androidx.compose.ui.viewinterop.AndroidView(factory={ LiveTextureView(it) },modifier=Modifier.matchParentSize().onSizeChanged{box=it}.pointerInput(userControl,vw,vh){
                                    if(userControl)awaitEachGesture {
                                        val down=awaitFirstDown(requireUnconsumed=false);down.consume()
                                        fun send(action:String,x:Float,y:Float){CoworkConnection.sendTouch(action,(x/size.width*vw).coerceIn(0f,vw-1f),(y/size.height*vh).coerceIn(0f,vh-1f))}
                                        send("down",down.position.x,down.position.y)
                                        while(true){
                                            val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}
                                            if(change==null){send("up",down.position.x,down.position.y);break}
                                            change.consume()
                                            if(change.changedToUp()){send("up",change.position.x,change.position.y);break}
                                            send("move",change.position.x,change.position.y)
                                        }
                                    }
                                })
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(!userControl)Button(onClick={perform{CoworkConnection.takeover(true)}},enabled=!busy&&status.optString("state")=="running"){Text("接管操作")}
                                else Button(onClick={perform{CoworkConnection.takeover(false)}},enabled=!busy){Text("交还给 AI")}
                            }
                            if(userControl){
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={CoworkConnection.sendKey("BACK")}){Text("返回")}
                                    OutlinedButton(onClick={CoworkConnection.sendKey("HOME")}){Text("桌面")}
                                    OutlinedButton(onClick={CoworkConnection.sendKey("ENTER")}){Text("回车")}
                                    OutlinedButton(onClick={CoworkConnection.sendKey("DEL")}){Text("删除")}
                                }
                                OutlinedTextField(typed,{typed=it.take(200)},label={Text("输入文字（先点画面中的输入框）")},modifier=Modifier.fillMaxWidth())
                                OutlinedButton(onClick={if(typed.isNotEmpty()){CoworkConnection.sendText(typed);typed=""}},enabled=typed.isNotEmpty()){Text("发送到副屏")}
                                Text("密码等敏感内容由你自己输入；AI 在接管期间看不到画面也无法操作。",style=MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                    status.optJSONObject("pending")?.let { pending ->
                        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            val details=pending.optJSONObject("arguments")?.toString(2).orEmpty()
                            Text("操作等待确认",style=MaterialTheme.typography.titleMedium)
                            Text("${pending.optString("tool")} · 剩余 ${pending.optLong("remainingMs")/1000} 秒")
                            Text("请先刷新并检查副屏快照；只批准下列精确操作，不确定时拒绝。")
                            Text(details)
                            Row {
                                TextButton(onClick={val target=CoworkControlTarget.from(status);perform{CoworkConnection.control("reject",target)}},enabled=!busy){Text("拒绝")}
                                Button(onClick={confirm=confirmation("approve",pending.optString("approvalId"),"${pending.optString("tool")}\n$details")},enabled=!busy&&preview?.revision==status.optLong("revision")&&preview?.execution==CoworkControlTarget.from(status).execution&&pending.optLong("remainingMs")>0){Text("检查并确认")}
                            }
                        } }
                    }
                    HorizontalDivider()
                    Text("工作资料应用",style=MaterialTheme.typography.titleLarge)
                    Text("资料 ID：${status.optInt("profileUserId",-1)} · 不使用应用白名单。这里只列出该资料已安装且可启动的应用；个人空间同名应用不在访问范围内。应用可访问不代表发送、支付、授权或删除许可。")
                    OutlinedButton(onClick={perform{CoworkConnection.refreshApps()}},enabled=!busy&&status.has("revision")){Text("刷新应用列表")}
                    OutlinedTextField(appFilter,{appFilter=it},label={Text("搜索应用名称或包名")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    val filtered=apps.filter{appFilter.isBlank()||it.label.contains(appFilter,true)||it.packageName.contains(appFilter,true)}
                    filtered.take(20).forEach { app ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)){Text(app.label);Text(app.packageName,style=MaterialTheme.typography.bodySmall)}
                            Text("工作资料",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                    if(filtered.size>20)Text("还有 ${filtered.size-20} 个应用，请搜索缩小范围。",style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("任务岛、悬浮窗与通知权限、自动连接等选项在应用内 设置 → Cowork。",style=MaterialTheme.typography.bodySmall)
                }
            }
            confirm?.let { selected ->
                val action=selected.action
                AlertDialog(onDismissRequest={confirm=null},title={Text(when(action){"overlay"->"开启系统悬浮窗权限";"stop"->"确认停止";"resume"->"确认恢复";else->"确认此项精确操作"})},
                    text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){Text(when(action){
                        "stop"->"将请求 DSH 退出整个 Cowork 工作（包括文件、网页和模型生成），并销毁副屏。只有 DSH 确认退出后才显示已停止；不会删除应用数据或终止主屏 App。继续需发送新的明确请求。"
                        "resume"->"仅用于直接测试工作区。恢复后旧观察失效，必须重新观察；DSH 任务需发送新的明确请求。"
                        "overlay"->"将打开个人空间的 Android 系统设置，请手动允许显示在其他应用上层。未授权时只使用通知，不会自动提权授权。"
                        else->"仅批准以下精确操作，屏幕变化或原观察到期后会失效。请勿批准未查看的发送、支付、授权或删除动作。\n\n${selected.details}"
                    })}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}},confirmButton={TextButton(onClick={
                        confirm=null
                        if(action=="overlay")startActivity(IslandRuntime.overlaySettingsIntent(this@CoworkActivity))
                        else perform{
                            CoworkConnection.control(action,selected.target,selected.approvalId)
                        }
                    }){Text("确认")}})
            }
        } }
    }
}
