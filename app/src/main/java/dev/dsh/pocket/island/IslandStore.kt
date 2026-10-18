package dev.dsh.pocket.island

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * In-process, memory-only bridge. No model call, screen capture, polling of agent steps or persistence.
 * State/revision changes publish immediately; native key-action activity only changes when the broker records one.
 * taskId must identify one execution (do not reuse an id after backend revision numbering restarts).
 */
class IslandStore(
    private val scope: CoroutineScope,
    private val actionTimeoutMillis: Long = 15_000,
) {
    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow(IslandSnapshot())
    val snapshots: StateFlow<IslandSnapshot> = mutableSnapshots.asStateFlow()
    private var actionJob: Job? = null
    private var highestActivitySequence = -1L
    private var newestEnvironmentObservation = -1L

    init {
        require(actionTimeoutMillis > 0)
    }

    /** Explicit local task selection only. Stream messages must use updateTask instead. */
    fun selectTask(update: IslandTaskUpdate): Boolean = synchronized(lock) {
        if (mutableSnapshots.value.task?.let { it.taskId == update.taskId && it.execution == update.execution } == true) return@synchronized updateTask(update)
        cancelPending()
        highestActivitySequence = -1
        mutableSnapshots.value = mutableSnapshots.value.copy(
            task = update, activity = IslandActivity(), recentEvents = listOf(stateEvent(update)),
            pendingAction = null, actionError = false,
        )
        true
    }

    /** Cannot select a task, roll back a revision or change state at the same revision. */
    fun updateTask(update: IslandTaskUpdate): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        val task = current.task ?: return@synchronized false
        if (update.taskId != task.taskId || update.execution != task.execution || update.revision <= task.revision) return@synchronized false
        val pending = current.pendingAction
        val preserveSafetyAction = pending != null && pending.execution != null &&
            pending.execution == update.execution && !update.state.terminal &&
            (pending.action == IslandAction.STOP || (pending.action == IslandAction.PAUSE && update.state != IslandTaskState.PAUSED))
        cancelPending(cancelAction = !preserveSafetyAction)
        mutableSnapshots.value = current.copy(
            task = update,
            recentEvents = if (task.state != update.state) appendEvents(current.recentEvents, listOf(stateEvent(update)))
                else current.recentEvents,
            pendingAction = if (preserveSafetyAction) pending else null, actionError = false,
        )
        true
    }

    fun setConnected(connected: Boolean) = synchronized(lock) {
        if (!connected) cancelPending()
        mutableSnapshots.value = mutableSnapshots.value.copy(
            backendConnected = connected,
            pendingAction = if (connected) mutableSnapshots.value.pendingAction else null,
        )
    }

    /** Keeps preferences but drops all task content on disconnect/logout/deselection. */
    fun clearTask() = synchronized(lock) {
        cancelPending()
        highestActivitySequence = -1
        mutableSnapshots.value = mutableSnapshots.value.copy(
            task = null, activity = IslandActivity(), recentEvents = emptyList(), pendingAction = null, actionError = false,
        )
    }

    fun configure(preferences: IslandPreferences) = synchronized(lock) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            preferences = preferences.copy(excludedPackages = preferences.excludedPackages.toSet()),
        )
    }

    fun updateEnvironment(environment: IslandEnvironment): Boolean = synchronized(lock) {
        // Unknown invalidation is immediate; delayed old observations must not re-enable an overlay.
        if (environment.foregroundPackage != null &&
            environment.observedAtElapsedMillis <= newestEnvironmentObservation) return@synchronized false
        newestEnvironmentObservation = maxOf(newestEnvironmentObservation, environment.observedAtElapsedMillis)
        mutableSnapshots.value = mutableSnapshots.value.copy(environment = environment)
        true
    }

    /**
     * Native key-action feed. Applied only when [sequence] is newer; events are keyed by id, so repeated
     * samples never duplicate. Texts are fixed broker strings / app labels and are redacted defensively.
     */
    fun submitActivity(taskId: String, sequence: Long, app: String, text: String, events: List<IslandEvent>): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        val task = current.task ?: return@synchronized false
        if (task.taskId != taskId || sequence < 0 || sequence <= highestActivitySequence) return@synchronized false
        highestActivitySequence = sequence
        val safe = events.takeLast(MAX_EVENTS).filter { it.id.isNotBlank() && it.id.length <= 128 }
            .map { it.copy(text = IslandPrivacy.safeLabel(it.text, 60), privacySafe = true) }
        mutableSnapshots.value = current.copy(
            activity = IslandActivity(sequence, IslandPrivacy.safeLabel(app, 40), IslandPrivacy.safeLabel(text, 60)),
            recentEvents = appendEvents(current.recentEvents, safe),
        )
        true
    }

    /**
     * Each click carries the displayed task/revision, so an old notification cannot stop a new task.
     * The handler must revalidate the revision with the backend and route only to the authenticated
     * local app/backend. No approval/resume action exists. Success is not an optimistic state change.
     */
    fun requestAction(taskId: String, revision: Long, action: IslandAction,
        handler: (suspend (IslandActionRequest) -> Unit)?): Boolean =
        requestAction(taskId, revision, action, handler, null)

    fun requestAction(
        taskId: String,
        revision: Long,
        action: IslandAction,
        handler: (suspend (IslandActionRequest) -> Unit)?,
        execution: IslandExecution?,
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        val task = current.task ?: return@synchronized false
        val safety = action == IslandAction.PAUSE || action == IslandAction.STOP
        val sameExecution = execution != null && execution == task.execution
        if (!current.backendConnected || task.taskId != taskId || execution != task.execution ||
            (task.revision != revision && !(safety && sameExecution && revision in 0..task.revision)) ||
            !IslandPolicy.allows(task, action) || current.pendingAction != null) return@synchronized false
        if (handler == null) {
            mutableSnapshots.value = current.copy(actionError = true)
            return@synchronized false
        }
        val request = IslandActionRequest(UUID.randomUUID().toString(), taskId, revision, action, execution)
        mutableSnapshots.value = current.copy(pendingAction = request, actionError = false)
        actionJob = scope.launch {
            try {
                withTimeout(actionTimeoutMillis) { handler(request) }
                if (action != IslandAction.OPEN_TASK) {
                    // Acknowledgement must arrive through updateTask; do not invent paused/stopped state.
                    delay(actionTimeoutMillis)
                    finishAction(request, failed = true)
                } else finishAction(request, failed = false)
            } catch (cancelled: CancellationException) {
                // State advance cancels this job and already clears pendingAction. A timeout doesn't.
                finishAction(request, failed = true)
                throw cancelled
            } catch (_: Exception) {
                finishAction(request, failed = true) // Never display/log backend secrets or raw errors.
            }
        }
        true
    }

    private fun finishAction(request: IslandActionRequest, failed: Boolean) = synchronized(lock) {
        val current = mutableSnapshots.value
        if (current.pendingAction?.requestId == request.requestId) {
            mutableSnapshots.value = current.copy(pendingAction = null, actionError = failed)
        }
    }

    private fun cancelPending(cancelAction: Boolean = true) {
        if (cancelAction) {
            actionJob?.cancel()
            actionJob = null
        }
    }

    private fun stateEvent(update: IslandTaskUpdate) = IslandEvent(
        id = "state:${update.revision}:${update.state.name}",
        kind = when (update.state) {
            IslandTaskState.RUNNING -> IslandEventKind.STARTED
            IslandTaskState.WAITING, IslandTaskState.PAUSING, IslandTaskState.STOPPING -> IslandEventKind.WAITING
            IslandTaskState.APPROVAL -> IslandEventKind.APPROVAL
            IslandTaskState.PAUSED -> IslandEventKind.PAUSED
            IslandTaskState.COMPLETED -> IslandEventKind.COMPLETED
            IslandTaskState.STOPPED -> IslandEventKind.STOPPED
            IslandTaskState.ERROR -> IslandEventKind.ERROR
        },
    )

    private fun appendEvents(old: List<IslandEvent>, incoming: List<IslandEvent>): List<IslandEvent> {
        val byId = LinkedHashMap<String, IslandEvent>()
        (old + incoming).forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
        return byId.values.toList().takeLast(MAX_EVENTS)
    }

    companion object { const val MAX_EVENTS = 6 }
}
