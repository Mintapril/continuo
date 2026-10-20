package dev.dsh.pocket.cowork

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import dev.dsh.pocket.island.*
import dev.dsh.pocket.cowork.broker.BrokerAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

data class CoworkApp(val packageName:String,val label:String,val approved:Boolean)
data class CoworkPreview(val bitmap:Bitmap,val taskId:String,val revision:Long,val capturedAt:Long,val execution:IslandExecution)
/** The owned display's input-focused field while the user controls it: metadata from the broker, never its text. */
data class RemoteField(val password:Boolean,val inputType:Int,val multiLine:Boolean,val bounds:IntArray?) {
    override fun equals(other:Any?)=other is RemoteField&&password==other.password&&inputType==other.inputType&&multiLine==other.multiLine&&bounds.contentEquals(other.bounds)
    override fun hashCode()=listOf(password,inputType,multiLine,bounds?.contentHashCode()).hashCode()
    companion object {
        fun parse(meta:JSONObject):RemoteField? {
            if(!meta.optBoolean("editable"))return null
            val box=meta.optJSONArray("bounds")?.takeIf{it.length()==4}?.let{b->IntArray(4){b.optInt(it)}}
            return RemoteField(meta.optBoolean("password"),meta.optInt("inputType"),meta.optBoolean("multiLine"),box)
        }
    }
}
data class CoworkControlTarget(val taskId:String,val revision:Long,val execution:IslandExecution) {
    companion object {
        fun from(value:JSONObject)=CoworkControlTarget(value.optString("taskId"),value.optLong("revision",-1),
            IslandExecution(value.optString("instanceId"),value.optString("executionId")))
    }
}

/** Fixed local controller channel. Neither secret nor lifecycle command enters model context. */
object CoworkConnection {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(2,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).callTimeout(7,TimeUnit.SECONDS).build()
    private var token:String?=null
    private var poll:Job?=null
    private val bridge=CoworkStateBridge(IslandRuntime.store)
    private val mutable=MutableStateFlow(JSONObject())
    val status=mutable.asStateFlow()
    private val errors=MutableStateFlow("")
    val error=errors.asStateFlow()
    private val appRows=MutableStateFlow<List<CoworkApp>>(emptyList())
    val apps=appRows.asStateFlow()
    private val frames=MutableStateFlow<CoworkPreview?>(null)
    val preview=frames.asStateFlow()
    private val link=MutableStateFlow(CoworkLinkState.DISCONNECTED)
    val connection=link.asStateFlow()
    private val connectLock=Mutex()
    private val liveBitmap=MutableStateFlow<Bitmap?>(null)
    /** Still poster (PNG) of the owned display shown until the first video frame; the video itself is decoded to a Surface by [LiveVideo]. Null when no viewer is attached or no workspace exists. */
    val live=liveBitmap.asStateFlow()
    private var liveJob:Job?=null
    private var liveUsers=0
    @Volatile private var inputChannel:Channel<String>?=null
    private val focusedField=MutableStateFlow<RemoteField?>(null)
    /** Input field focused on the owned display during a takeover (null: none, or no live view attached). */
    val field=focusedField.asStateFlow()
    /** True while the island shows the takeover full screen: that surface owns the keyboard, not CoworkActivity. */
    val islandFullScreen=MutableStateFlow(false)
    /** Reference counted: the Activity and the island share one stream (the broker allows a single viewer). */
    fun acquireLive(){
        liveUsers++
        if(liveJob?.isActive!=true)liveJob=scope.launch{
            while(isActive){
                if(link.value==CoworkLinkState.CONNECTED&&mutable.value.optInt("displayId")>0&&mutable.value.optBoolean("workspaceActive")){
                    try{stream()}catch(e:CancellationException){throw e}catch(_:Exception){}
                    liveBitmap.value=null
                }
                delay(1000)
            }
        }
    }
    fun releaseLive(){
        if(liveUsers>0)liveUsers--
        if(liveUsers==0){liveJob?.cancel();liveJob=null;liveBitmap.value=null;inputChannel=null}
    }
    /** Human input travels on the authenticated stream socket; the broker only accepts it while takeover is active. */
    fun sendTouch(action:String,x:Float,y:Float){inputChannel?.trySend(JSONObject().put("kind","touch").put("action",action).put("x",x.toDouble()).put("y",y.toDouble()).toString())}
    fun sendKey(key:String){inputChannel?.trySend(JSONObject().put("kind","key").put("key",key).toString())}
    fun sendText(text:String){inputChannel?.trySend(JSONObject().put("kind","text").put("text",text).toString())}
    suspend fun takeover(start:Boolean){
        val instance=mutable.value.optString("instanceId");check(instance.isNotBlank()){"尚未连接控制服务"}
        apply(request("/v1/takeover",JSONObject().put("action",if(start)"start" else "end").put("instanceId",instance)))
    }
    private suspend fun stream()=withContext(Dispatchers.IO){
        val secret=checkNotNull(token){"尚未连接控制服务"}
        val path="/v1/stream"
        val digest=BrokerAuth.digest("GET",path,byteArrayOf())
        val nonce=java.util.UUID.randomUUID().toString().replace("-","")+java.util.UUID.randomUUID().toString().replace("-","")
        val challenge=JSONObject().put("role","controller").put("clientNonce",nonce).put("digest",digest)
        val server=client.newCall(Request.Builder().url("http://127.0.0.1:3091/v1/auth").post(challenge.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            check(response.isSuccessful){"执行器认证失败"}
            JSONObject(String(checkNotNull(response.body).byteStream().use{it.readNBytes(4097)},Charsets.UTF_8))
        }
        val serverNonce=server.getString("serverNonce")
        val fields="controller\n$nonce\n$serverNonce\n$digest"
        check(BrokerAuth.equal(server.getString("proof"),BrokerAuth.mac(secret,"server\n$fields"))){"执行器身份不可信"}
        val socket=Socket()
        val closer=checkNotNull(coroutineContext[Job]).invokeOnCompletion{runCatching{socket.close()}}
        try {
            socket.connect(InetSocketAddress("127.0.0.1",3091),2000);socket.tcpNoDelay=true;socket.soTimeout=5000
            socket.getOutputStream().apply{
                write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Cowork-HMAC controller:$nonce:$serverNonce:${BrokerAuth.mac(secret,"client\n$fields")}\r\n\r\n").toByteArray(Charsets.US_ASCII));flush()
            }
            val input=DataInputStream(java.io.BufferedInputStream(socket.getInputStream(),65536))
            val head=StringBuilder()
            while(!head.endsWith("\r\n\r\n")){val b=input.read();check(b>=0&&head.length<4096){"实时画面连接中断"};head.append(b.toChar())}
            val lines=head.toString().split("\r\n")
            check(lines[0]=="HTTP/1.1 200"){"实时画面不可用"}
            val proof=lines.firstOrNull{it.startsWith("X-Cowork-Proof:")}?.substringAfter(":")?.trim().orEmpty()
            check(BrokerAuth.equal(proof,BrokerAuth.mac(secret,"response\n$fields\n200\n${BrokerAuth.sha256(byteArrayOf())}"))){"执行器响应认证失败"}
            val channel=Channel<String>(256);inputChannel=channel;LiveVideo.requestKeyFrame={channel.trySend("{\"kind\":\"sync\"}")}
            val output=socket.getOutputStream()
            coroutineScope {
                val writer=launch{
                    for(line in channel){output.write((line+"\n").toByteArray(Charsets.UTF_8));output.flush()}
                }
                try {
                    while(isActive){
                        val length=input.readInt()
                        if(length==0)continue
                        check(length in 2..4*1024*1024){"画面帧无效"}
                        val bytes=ByteArray(length);input.readFully(bytes)
                        when(bytes[0].toInt()){
                            1->{val meta=JSONObject(String(bytes,1,length-1,Charsets.UTF_8));LiveVideo.onInfo(LiveVideo.Info(meta.getInt("w"),meta.getInt("h"),meta.optInt("fps",60)))}
                            2->LiveVideo.onConfig(bytes.copyOfRange(1,length))
                            3,4->if(length>9)LiveVideo.onUnit(bytes[0].toInt()==3,java.nio.ByteBuffer.wrap(bytes,1,8).long,bytes,9,length-9)
                            5->BitmapFactory.decodeByteArray(bytes,1,length-1)?.let{liveBitmap.value=it}
                            6->focusedField.value=RemoteField.parse(JSONObject(String(bytes,1,length-1,Charsets.UTF_8)))
                        }
                    }
                } finally {LiveVideo.requestKeyFrame={};LiveVideo.reset();inputChannel=null;focusedField.value=null;channel.close();writer.cancel()}
            }        } finally {closer.dispose();runCatching{socket.close()}}
    }
    internal fun controllerUnavailable(message:String) {
        errors.value=message;link.value=CoworkLinkState.FAILED
    }
    internal fun controllerStopped() {
        poll?.cancel();poll=null;token=null;frames.value=null;liveBitmap.value=null;appRows.value=emptyList();mutable.value=JSONObject()
        IslandRuntime.setActionHandler(null);bridge.disconnected();link.value=CoworkLinkState.DISCONNECTED
    }
    internal suspend fun connect(context:Context) {
        check(connectLock.tryLock()){"正在连接，请稍候"}
        try {
            // Repeated clicks must not reset consent or interrupt a healthy controller.
            if(link.value==CoworkLinkState.CONNECTED)return
            val app=context.applicationContext
            connectWithFeedback({link.value=it},{
                token=null;frames.value=null;appRows.value=emptyList();mutable.value=JSONObject();bridge.disconnected()
            }) {
                poll?.cancelAndJoin();poll=null;token=null;frames.value=null;appRows.value=emptyList()
                mutable.value=JSONObject();bridge.disconnected();errors.value=""
                // Both commands are constants; no package, user text or model arguments are interpolated.
                withContext(Dispatchers.IO){rootCommand("/system/bin/sh /data/local/dsh-native/cowork-service.sh start",1024)}
                link.value=CoworkLinkState.AUTHENTICATING
                token=withContext(Dispatchers.IO){
                    val text=rootCommand("/system/bin/sh /data/local/dsh-native/cowork-service.sh controller-token",1024)
                    check(text.matches(Regex("[A-Za-z0-9_-]{40,256}"))){"控制凭证不可用，请检查部署"};text
                }
                link.value=CoworkLinkState.CONNECTING
                var ready:JSONObject?=null
                repeat(12){if(ready==null){try{ready=request("/v1/state")}catch(e:CancellationException){throw e}catch(_:Exception){delay(250)}}}
                val initial=checkNotNull(ready){"执行器未就绪，请检查 KernelSU 授权与本机部署"}
            }
            errors.value=""
            IslandRuntime.setActionHandler { action ->
                val target=CoworkControlTarget(action.taskId,action.revision,checkNotNull(action.execution){"任务身份不可用"})
                checkTarget(target)
                if(action.action==IslandAction.OPEN_TASK)app.startActivity(Intent(app,CoworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else control(if(action.action==IslandAction.PAUSE)"pause" else "stop",target)
            }
            poll=scope.launch {
                while(isActive){
                    try{
                        check(apply(request("/v1/state"))){"执行器状态无效或已过期"}
                        errors.value="";link.value=CoworkLinkState.CONNECTED
                    }
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){errors.value="控制服务连接中断，后台操作将自动暂停";frames.value=null;mutable.value=JSONObject();bridge.disconnected();link.value=CoworkLinkState.LOST}
                    delay(1000)
                }
            }
            // Optional app enumeration must not misreport the authenticated connection as failed.
            try{refreshApps()}catch(e:CancellationException){throw e}catch(_:Exception){error("执行器已连接，但应用列表加载失败；可在下方刷新")}
        } catch(e:CancellationException){throw e}
        catch(e:Exception){errors.value=e.message?:"连接未完成";throw e}
        finally {connectLock.unlock()}
    }
    private fun rootCommand(command:String,maxBytes:Int):String {
        val process=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();error("KernelSU 授权超时，请在 KernelSU 中检查授权")}
        val bytes=process.inputStream.use{it.readNBytes(maxBytes+1)}
        check(process.exitValue()==0&&bytes.size<=maxBytes){"无法启动本机执行器；请检查 Continuo 的 KernelSU 授权"}
        return String(bytes,Charsets.UTF_8).trim()
    }
    private suspend fun request(path:String,body:JSONObject?=null,limit:Int=262144):JSONObject=withContext(Dispatchers.IO){
        val secret=checkNotNull(token){"尚未连接控制服务"}
        val method=if(body==null)"GET" else "POST"
        val requestBytes=body?.toString()?.toByteArray(Charsets.UTF_8)?:byteArrayOf()
        val digest=BrokerAuth.digest(method,path,requestBytes)
        val nonce=java.util.UUID.randomUUID().toString().replace("-","")+java.util.UUID.randomUUID().toString().replace("-","")
        val challenge=JSONObject().put("role","controller").put("clientNonce",nonce).put("digest",digest)
        val deadline=SystemClock.elapsedRealtime()+7000
        val challengeRequest=Request.Builder().url("http://127.0.0.1:3091/v1/auth").post(challenge.toString().toRequestBody("application/json".toMediaType())).build()
        val server=client.newCall(challengeRequest).execute().use { response ->
            check(response.isSuccessful){"执行器认证失败"}
            val bytes=checkNotNull(response.body).byteStream().use{it.readNBytes(4097)};check(bytes.size<=4096)
            JSONObject(String(bytes,Charsets.UTF_8))
        }
        val serverNonce=server.getString("serverNonce")
        check(server.length()==2&&serverNonce.matches(Regex("[0-9a-f]{64}"))){"执行器认证失败"}
        val fields="controller\n$nonce\n$serverNonce\n$digest"
        check(BrokerAuth.equal(server.getString("proof"),BrokerAuth.mac(secret,"server\n$fields"))){"执行器身份不可信"}
        val builder=Request.Builder().url("http://127.0.0.1:3091$path").header("Authorization","Cowork-HMAC controller:$nonce:$serverNonce:${BrokerAuth.mac(secret,"client\n$fields")}")
        if(body!=null)builder.post(requestBytes.toRequestBody("application/json".toMediaType()))
        val remaining=deadline-SystemClock.elapsedRealtime();check(remaining>0){"执行器认证超时"}
        val call=client.newCall(builder.build());call.timeout().timeout(remaining,TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val bytes=checkNotNull(response.body).byteStream().use{it.readNBytes(limit+1)}
            check(bytes.size<=limit){"控制响应超过上限"}
            val expected=BrokerAuth.mac(secret,"response\n$fields\n${response.code}\n${BrokerAuth.sha256(bytes)}")
            check(BrokerAuth.equal(response.header("X-Cowork-Proof").orEmpty(),expected)){"执行器响应认证失败"}
            val value=JSONObject(String(bytes,Charsets.UTF_8))
            check(response.isSuccessful&&value.optBoolean("ok",true)){"控制服务拒绝请求：${value.optJSONObject("error")?.optString("code")?:response.code}"}
            value
        }
    }
    private fun checkTarget(target:CoworkControlTarget){
        val current=CoworkControlTarget.from(mutable.value)
        check(target.taskId.isNotBlank()&&target.execution.instanceId.isNotBlank()&&target.execution.executionId.isNotBlank()&&
            target.taskId==current.taskId&&target.execution==current.execution){"任务已切换，请重新检查"}
    }
    suspend fun control(action:String,target:CoworkControlTarget,approvalId:String?=null){
        checkTarget(target)
        val body=JSONObject().put("action",action).put("sessionId",target.taskId).put("revision",target.revision)
            .put("instanceId",target.execution.instanceId).put("executionId",target.execution.executionId)
        approvalId?.let{body.put("approvalId",it)}
        apply(request("/v1/control",body))
    }
    suspend fun refreshApps(){
        val rows=request("/v1/apps").getJSONArray("apps")
        appRows.value=(0 until rows.length()).map{rows.getJSONObject(it)}.map{CoworkApp(it.getString("packageName"),it.getString("label"),it.optBoolean("approved"))}.sortedBy{it.label}
    }
    suspend fun refreshPreview(){
        frames.value=null
        val frame=request("/v1/preview",limit=12*1024*1024)
        val bitmap=withContext(Dispatchers.Default){
            check(frame.getString("mimeType")=="image/png"&&frame.getInt("width")==720&&frame.getInt("height") in 1280..2000)
            val bytes=Base64.decode(frame.getString("base64"),Base64.DEFAULT);check(bytes.size<=8*1024*1024)
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            check(options.outWidth==frame.getInt("width")&&options.outHeight==frame.getInt("height")){"副屏图像尺寸不匹配"}
            checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        }
        val current=mutable.value
        val execution=IslandExecution(frame.getString("instanceId"),frame.getString("executionId"))
        if(current.optString("taskId")==frame.optString("taskId")&&current.optLong("revision")==frame.optLong("revision")&&current.optInt("displayId")>0&&current.optBoolean("workspaceActive")&&CoworkControlTarget.from(current).execution==execution)
            frames.value=CoworkPreview(bitmap,frame.getString("taskId"),frame.getLong("revision"),frame.getLong("capturedAt"),execution)
    }
    private fun apply(value:JSONObject):Boolean {
        if(!bridge.apply(value,SystemClock.elapsedRealtime()))return false
        mutable.value=value
        frames.value?.let{if(it.taskId!=value.optString("taskId")||it.execution!=CoworkControlTarget.from(value).execution||it.revision!=value.optLong("revision")||value.optInt("displayId")<=0||!value.optBoolean("workspaceActive")||value.optBoolean("locked"))frames.value=null}
        return true
    }
}
