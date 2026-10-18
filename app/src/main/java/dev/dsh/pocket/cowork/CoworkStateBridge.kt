package dev.dsh.pocket.cowork

import dev.dsh.pocket.island.*
import org.json.JSONObject

/** Backend samples are ordered independently of task revision; receipt time is never freshness. */
class CoworkStateBridge(private val store:IslandStore) {
    private var instance=""
    private var revision=-1L
    private var environmentSequence=-1L
    private var observedAt=-1L
    private val retired=mutableSetOf<String>()
    fun apply(value:JSONObject,now:Long):Boolean {
        val next=value.optString("instanceId")
        val rev=value.optLong("revision",-1)
        val sequence=value.optLong("environmentSequence",-1)
        val observed=value.optLong("environmentObservedAt",-1)
        val id=value.optString("taskId")
        val executionId=value.optString("executionId")
        if(next.isBlank()||next in retired||rev<0||sequence<0||observed<0||observed>now||id.length>256||
            (id.isNotBlank()&&executionId.isBlank()))return false
        if(next==instance&&(rev<revision||sequence<=environmentSequence||observed<observedAt))return false
        if(next!=instance){
            // elapsedRealtime has the same epoch in broker and APK; an older instance cannot supersede a newer sample.
            if(observed<observedAt)return false
            if(instance.isNotEmpty())retired.add(instance)
            store.clearTask();instance=next;revision=-1;environmentSequence=-1
        }
        revision=rev;environmentSequence=sequence;observedAt=observed
        store.setConnected(true)
        if(id.isBlank())store.clearTask() else {
            val execution=IslandExecution(next,executionId)
            val state=when(value.optString("state")){
                "waiting_user"->if(value.has("pending"))IslandTaskState.APPROVAL else IslandTaskState.WAITING
                "pausing"->IslandTaskState.PAUSING
                "stopping"->IslandTaskState.STOPPING
                "paused"->IslandTaskState.PAUSED
                "completed"->IslandTaskState.COMPLETED
                "stopped"->IslandTaskState.STOPPED
                "error"->IslandTaskState.ERROR
                "running"->IslandTaskState.RUNNING
                else->IslandTaskState.WAITING
            }
            val update=IslandTaskUpdate(id,rev,state,execution)
            val current=store.snapshots.value.task
            if(current?.taskId!=id||current.execution!=execution)store.selectTask(update) else store.updateTask(update)
            value.optJSONObject("activity")?.let { activity ->
                val rows=activity.optJSONArray("events")
                val events=(0 until (rows?.length()?:0)).mapNotNull { rows?.optJSONObject(it) }.map {
                    IslandEvent("act:"+it.optString("id"),IslandEventKind.PROGRESS,it.optString("text"),privacySafe=true)
                }.filter { it.id.length>4 }
                store.submitActivity(id,activity.optLong("seq",-1),activity.optString("app"),activity.optString("text"),events)
            }
        }
        val safe=value.opt("locked") is Boolean&&!value.getBoolean("locked")
        store.updateEnvironment(IslandEnvironment(if(safe)value.optString("mainPackage").takeIf{it.isNotBlank()}else null,
            if(safe&&value.opt("mainIsGame") is Boolean)value.getBoolean("mainIsGame")else null,observed))
        return true
    }
    fun disconnected(){store.setConnected(false);store.updateEnvironment(IslandEnvironment())}
}
