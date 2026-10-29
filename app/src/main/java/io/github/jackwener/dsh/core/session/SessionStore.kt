package io.github.jackwener.dsh.core.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.capability.ShotStore
import io.github.jackwener.dsh.core.data.EventEntity
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.SessionDao
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.UsageDao
import io.github.jackwener.dsh.core.data.UsageEntity
import io.github.jackwener.dsh.core.engine.EngineBackgroundStartException
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.engine.JsonRpcPeer
import io.github.jackwener.dsh.core.engine.TurnWakeLock
import io.github.jackwener.dsh.core.engine.EngineStartException
import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.NoCredentialsException
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ruleIdOfSession
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.Connectivity
import io.github.jackwener.dsh.core.platform.DeferredRuns
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class ApprovalOutcome { AllowOnce, AllowSession, Reject }

/**
 * Sessions: sends prompts, persists every `session.event` (plus app-local error/stop records) as
 * the single log both the conversation and trajectory views project from, keeps the app-side
 * message queue, and answers dsh's server requests (approvals, user questions) and the
 * capability layer's just-in-time permission requests.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineManager,
    private val dao: SessionDao,
    private val rules: RuleDao,
    private val usage: UsageDao,
    private val settings: SettingsRepository,
    private val capabilities: CapabilityHub,
    private val wakeLock: TurnWakeLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> android.util.Log.e(TAG, "session task crashed", e) })
    private val states = ConcurrentHashMap<String, MutableStateFlow<SessionLive>>()
    private val loadLock = Mutex()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalOutcome>>()
    private val questions = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val permissions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val takeovers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /**
     * Sessions whose task was already continued automatically once (after an engine loss or an
     * offline failure). Kept across the continuation turn and after it, so a continuation that
     * fails again can never arm another one; cleared only when the user starts a new task (a
     * non-app prompt on an idle or network-waiting session, 重试) or stops the run.
     */
    private val autoContinued = ConcurrentHashMap.newKeySet<String>()
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()
    /** sessionId -> engine generation it was last resumed/created in. */
    private val resumedIn = ConcurrentHashMap<String, Int>()
    private val logSeqs = LogSeqs()
    private val localSeq = AtomicLong(-System.currentTimeMillis())
    /** Prompt attempts per session; an `app/error` is reported once per attempt (see [appendLocal]). */
    private val attempts = ConcurrentHashMap<String, Int>()
    private val errorReported = ConcurrentHashMap<String, Int>()
    /** viewer (a conversation screen) -> the session it shows. */
    private val viewers = ConcurrentHashMap<Any, String>()

    private val _status = MutableStateFlow<Map<String, RowStatus>>(emptyMap())
    /** Live sidebar status per session (approval / question / running). */
    val status: StateFlow<Map<String, RowStatus>> = _status.asStateFlow()

    val conversations: Flow<List<SessionEntity>> = dao.observeAttended()

    init {
        // Rows still marked running belong to a previous app process (both processes died): the
        // turn is gone, so show it as interrupted with 继续 rather than silently idle.
        scope.launch {
            dao.running().forEach { row ->
                dao.setRunning(row.id, false)
                if (row.parentId == null) appendLocal(row.id, "app/error", buildJsonObject {
                    put("kind", "engine_lost"); put("message", context.getString(R.string.err_interrupted_previous))
                })
            }
        }
    }
    fun observeSession(id: String): Flow<SessionEntity?> = dao.observe(id)

    suspend fun state(sessionId: String): StateFlow<SessionLive> = flowOf(sessionId)

    private suspend fun flowOf(sessionId: String): MutableStateFlow<SessionLive> = states[sessionId] ?: loadLock.withLock {
        states[sessionId]?.let { return it }
        val entity = dao.get(sessionId)
        // Parsing a long log (tool results included) is real work: never on the caller's (often Main) thread.
        val events = dao.events(sessionId).let { rows -> withContext(Dispatchers.Default) { rows.map { it.toLogged() } } }
        val preset = entity?.preset?.let { PermissionPreset.of(it) } ?: settings.current().defaultPreset
        android.util.Log.i(TAG, "session $sessionId loaded: preset ${preset.id} (${if (entity?.preset != null) "session row" else "settings default"})")
        MutableStateFlow(SessionLive(sessionId, entity?.title ?: "", events, running = false, preset = preset, exists = entity != null))
            .also { states[sessionId] = it }
    }

    private suspend fun mutate(sessionId: String, f: (SessionLive) -> SessionLive) {
        val flow = flowOf(sessionId)
        flow.update(f)
        val s = flow.value
        val st = when {
            s.approvals.isNotEmpty() || s.permissions.isNotEmpty() || s.takeovers.isNotEmpty() -> RowStatus.Approval
            s.questions.isNotEmpty() -> RowStatus.Question
            s.running -> RowStatus.Running
            else -> RowStatus.Idle
        }
        _status.update { if (it[sessionId] == st) it else it + (sessionId to st) }
        // CPU stays awake only while some turn is actively running (not while it waits for the user, an unlock or the network).
        if (states.values.any { it.value.running && !it.value.hasPendingRequest && !it.value.waitingForUnlock && !it.value.waitingForNetwork }) wakeLock.hold() else wakeLock.release()
    }

    private fun EventEntity.toLogged() = LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    fun hasRunning() = states.values.any { it.value.running }

    /**
     * Runs the engine is executing now. A fresh prompt still waiting for the engine to start is not
     * one, but a running session that is steered (sent to again mid-turn) is: [prompt] leaves its
     * startingEngine unset, so a route change never restarts the engine under it.
     */
    fun hasEngineRuns() = states.values.any { it.value.running && !it.value.startingEngine }

    fun newSessionId(): String = UUID.randomUUID().toString()

    /**
     * Which session [viewer] (a conversation screen: home, quick panel) shows; null when it goes
     * away. A session counts as seen while any viewer shows it (clears its completed-unread dot).
     */
    fun setViewing(viewer: Any, sessionId: String?) {
        if (sessionId == null) viewers.remove(viewer) else viewers[viewer] = sessionId
        if (sessionId != null) scope.launch { dao.setUnread(sessionId, false) }
    }

    private fun isViewed(sessionId: String) = sessionId in viewers.values

    /** Subagent logs, from the live per-session state (loaded once, then kept current by the event stream). */
    suspend fun childEvents(sessionId: String): List<List<LoggedEvent>> =
        dao.children(sessionId).map { c -> flowOf(c.id).value.events }

    /** The composer took the [SessionLive.restoredDraft]. */
    fun consumeRestoredDraft(sessionId: String) = scope.launch { mutate(sessionId) { it.copy(restoredDraft = null) } }

    // ---- sending ---------------------------------------------------------------------------------

    /**
     * User send from the composer. While a run is active the message waits in the app-side queue
     * (dsh 排队发送) unless [now] is set, in which case it goes to the engine immediately and dsh
     * claims it at its next turn boundary.
     */
    suspend fun send(sessionId: String, text: String, images: List<Pair<String, String>> = emptyList(), now: Boolean = false) {
        // The session's preset is the store's (row value, or the settings default for a new id;
        // setPreset changes it): a caller's copy can belong to the previously shown session.
        val live = flowOf(sessionId).value
        if (live.running && !now && images.isEmpty()) {
            mutate(sessionId) { it.copy(queue = it.queue + QueuedMessage(UUID.randomUUID().toString(), text)) }
            return
        }
        prompt(sessionId, text, images = images, steer = now && live.running)
    }

    fun removeQueued(sessionId: String, id: String) = scope.launch { mutate(sessionId) { s -> s.copy(queue = s.queue.filterNot { it.id == id }) } }

    suspend fun sendQueuedNow(sessionId: String, id: String) {
        val q = flowOf(sessionId).value.queue.firstOrNull { it.id == id } ?: return
        mutate(sessionId) { s -> s.copy(queue = s.queue.filterNot { it.id == id }) }
        prompt(sessionId, q.text)
    }

    /**
     * Sends a prompt to the engine; creates the session row on first use. Never throws for
     * engine problems: a failure becomes an `app/error` record in the log (shown as a failed
     * turn with a retry affordance) and the session goes back to idle.
     */
    suspend fun prompt(sessionId: String, text: String, ruleId: Long? = null, images: List<Pair<String, String>> = emptyList(), steer: Boolean = false) {
        val now = System.currentTimeMillis()
        val existing = dao.get(sessionId)
        val live = flowOf(sessionId).value
        attempts.merge(sessionId, 1, Int::plus)
        val appPrompt = text.startsWith(APP_PROMPT_PREFIX)
        // Steering a running turn (also a queued message sent now) stays part of the same task.
        if (!appPrompt && (!live.running || live.waitingForNetwork)) autoContinued.remove(sessionId)
        // A prompt the app sent for the user is not re-sent verbatim by 重试 (that asks to continue).
        val retryText = text.takeUnless { appPrompt }
        if (existing == null) dao.upsert(SessionEntity(sessionId, text.lineSequence().first().take(40), now, now, ruleId, running = true, preset = live.preset.id))
        else dao.setRunning(sessionId, true)
        // Steering a running session keeps it an engine run (no startingEngine): see hasEngineRuns.
        mutate(sessionId) { it.copy(running = true, exists = true, runStartedAt = it.runStartedAt.takeIf { _ -> live.running } ?: now,
            startingEngine = it.startingEngine || !live.running, waitingForNetwork = false) }
        engine.cancelIdleShutdown()
        if (ruleIdOfSession(sessionId) != null && engine.restarts.open && engine.state.value != EngineState.Ready) {
            // Circuit breaker open: automations do not restart a crash-looping engine (Doctor shows why).
            fail(sessionId, "engine", context.getString(R.string.err_engine_crash_loop), null, retryText)
            return
        }
        try {
            val sdk = engine.sdk()
            mutate(sessionId) { it.copy(startingEngine = false) }
            engine.markSessionOpened()
            val gen = engine.generation
            if (resumedIn[sessionId] != gen && (live.events.none { it.seq >= 0 } || resume(sdk, sessionId))) resumedIn[sessionId] = gen
            // The guard needs the conversation's permission mode (ENGINE-CONTRACT: android/session/permission).
            sendPreset(sdk, sessionId, flowOf(sessionId).value.preset)
            sdk.request("session/prompt", buildJsonObject {
                put("sessionId", sessionId)
                // dsh: "steer" injects the message into the running turn; without it the prompt waits as a follow-up.
                if (steer) put("mode", "steer")
                putJsonArray("contentBlocks") {
                    addJsonObject { put("type", "text"); put("text", text) }
                    images.forEach { (mime, data) -> addJsonObject { put("type", "image"); put("mimeType", mime); put("data", data) } }
                }
            })
        } catch (e: NoCredentialsException) {
            fail(sessionId, "no_credentials", e.message ?: "", null, retryText)
        } catch (e: EngineBackgroundStartException) {
            // Android 12+ refused the engine's foreground service from the background (e.g. battery
            // optimization re-enabled): degrade to a notification that runs the task when tapped.
            val rule = ruleId ?: ruleIdOfSession(sessionId)
            fail(sessionId, "engine", context.getString(R.string.err_bg_start), null, retryText)
            val label = rule?.let { rules.get(it) }?.let { runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson).name }.getOrNull() }
                ?: dao.get(sessionId)?.title ?: text.take(40)
            DeferredRuns.post(context, sessionId, text, rule, label)
        } catch (e: EngineStartException) {
            fail(sessionId, "engine", e.message ?: "", e.log, retryText)
        } catch (e: JsonRpcException) {
            fail(sessionId, "engine", e.message ?: "", e.code.toString(), retryText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(sessionId, "engine", e.message ?: e.javaClass.simpleName, null, retryText)
        }
    }

    /**
     * Re-opens a session logged in an earlier engine generation. dsh's resume can fail transiently
     * (the dead generation's journal lease, a timeout), and a prompt without it would start a
     * context-less session, so it is retried once; an unresumed session tries again on its next
     * prompt in this generation instead of being marked resumed.
     */
    private suspend fun resume(sdk: io.github.jackwener.dsh.core.engine.JsonRpcPeer, sessionId: String): Boolean {
        repeat(2) { attempt ->
            try {
                sdk.request("session/resume", buildJsonObject { put("sessionId", sessionId) })
                return true
            } catch (e: JsonRpcException) {
                if (e.message?.contains("already open") == true) return true
                android.util.Log.w(TAG, "session/resume $sessionId failed (attempt ${attempt + 1}): ${e.message}")
                if (attempt == 0) delay(RESUME_RETRY_MS)
            }
        }
        return false
    }

    /** Fire-and-forget [prompt] in the store's own scope (callers that are about to go away, e.g. a notification trampoline). */
    fun launchPrompt(sessionId: String, text: String, ruleId: Long? = null) { scope.launch { prompt(sessionId, text, ruleId) } }

    /**
     * Re-sends after a failure (重试): retries the prompt, or asks to continue. The session shows
     * as running right away, so the button cannot fire twice while the engine restarts. The engine
     * is restarted only for an `engine` error on a live engine (it may be wedged) and only when no
     * other session is running; a lost engine (`engine_lost`) is already gone, and the prompt
     * starts a fresh one.
     */
    suspend fun retry(sessionId: String, error: TurnError) {
        if (flowOf(sessionId).value.running) return
        // The user asked for a new attempt: it may be continued automatically once again.
        autoContinued.remove(sessionId)
        mutate(sessionId) { it.copy(running = true, startingEngine = true, runStartedAt = System.currentTimeMillis()) }
        val othersRunning = states.values.any { it.value.running && it.value.sessionId != sessionId }
        if (error.kind == "engine" && engine.state.value == EngineState.Ready && !othersRunning) {
            stopping = true
            try { engine.stop() } finally { stopping = false }
            // The user tapped Stop while the engine restarted.
            if (!flowOf(sessionId).value.running) return
        }
        prompt(sessionId, error.retryText ?: context.getString(R.string.retry_continue_prompt))
    }

    private suspend fun fail(sessionId: String, kind: String, message: String, code: String?, retryText: String?) {
        dao.setRunning(sessionId, false)
        appendLocal(sessionId, "app/error", buildJsonObject {
            put("kind", kind); put("message", message)
            code?.let { put("code", it) }
            retryText?.let { put("retryText", it) }
        })
        mutate(sessionId) { it.idle() }
        if (!hasRunning()) engine.scheduleIdleShutdown()
    }

    private suspend fun appendLocal(sessionId: String, type: String, data: JsonObject) {
        flowOf(sessionId)
        if (type == "app/error") {
            // One prompt attempt reports one error: the prompt's own failure and the engine-loss
            // callback can both describe the same crash. A retry is a new attempt and shows again.
            val attempt = attempts[sessionId] ?: 0
            if (errorReported.put(sessionId, attempt) == attempt) return
        }
        val e = LoggedEvent(localSeq.decrementAndGet(), type, System.currentTimeMillis(), data)
        if (dao.get(sessionId) != null) dao.insertEvent(EventEntity(sessionId, e.seq, type, data.toString(), e.time))
        mutate(sessionId) { s -> if (s.events.any { it.seq == e.seq }) s else s.copy(events = s.events + e) }
    }

    /** Set while this store stops the engine itself; the resulting channel close is not a crash. */
    @Volatile private var stopping = false

    /** Stops every run (the SDK has no per-session cancel, so this stops the engine process). */
    suspend fun stop() = stopEngine()

    /**
     * Restarts the engine (Doctor, an API key change). Same as [stop] for the runs it kills: they
     * end as stopped, with their cards and queues cleared; the next prompt starts the new engine.
     * Every caller that kills the engine must come through here (or [stop]), never
     * EngineManager.stop() directly: a deliberate exit sends no `session.status` for the aborted turns.
     */
    suspend fun restartEngine() = stopEngine()

    private suspend fun stopEngine() {
        val running = states.values.map { it.value }.filter { it.running }.map { it.sessionId }.toSet()
        stopping = true
        try { engine.stop() } finally { stopping = false }
        cancelPending()
        running.forEach { id ->
            dao.setRunning(id, false)
            autoContinued.remove(id)
            appendLocal(id, "app/stopped", JsonObject(emptyMap()))
            mutate(id) { it.idle() }
        }
    }

    /**
     * The engine generation ended: answer every pending card (their handler coroutines were
     * cancelled by the teardown already, see EngineCalls) and clear what any session still shows.
     */
    private suspend fun cancelPending() {
        approvals.values.forEach { it.complete(ApprovalOutcome.Reject) }
        questions.values.forEach { it.completeExceptionally(JsonRpcException(-32000, "cancelled")) }
        permissions.values.forEach { it.complete(false) }
        takeovers.values.forEach { it.complete(false) }
        states.values.filter { it.value.hasPendingRequest || it.value.waitingForUnlock }.forEach { f ->
            mutate(f.value.sessionId) { it.withoutAnyPending().copy(waitingForUnlock = false) }
        }
    }

    // ---- session management ----------------------------------------------------------------------

    suspend fun rename(sessionId: String, title: String) { dao.setTitle(sessionId, title); mutate(sessionId) { it.copy(title = title) } }
    suspend fun setPinned(sessionId: String, pinned: Boolean) = dao.setPinned(sessionId, pinned)
    suspend fun setArchived(sessionId: String, archived: Boolean) = dao.setArchived(sessionId, archived)
    suspend fun setPreset(sessionId: String, preset: PermissionPreset) {
        // Preset changes are rare and user-initiated; the stack pins down any other caller.
        android.util.Log.i(TAG, "session $sessionId preset -> ${preset.id}", Throwable("setPreset caller"))
        if (dao.get(sessionId) != null) dao.setPreset(sessionId, preset.id)
        mutate(sessionId) { it.copy(preset = preset) }
        engine.sdkIfReady()?.let { sendPreset(it, sessionId, preset) }
    }

    private suspend fun sendPreset(sdk: JsonRpcPeer, sessionId: String, preset: PermissionPreset) {
        runCatching { sdk.request("android/session/permission", buildJsonObject { put("sessionId", sessionId); put("preset", preset.id) }) }
            .onFailure { android.util.Log.w(TAG, "android/session/permission failed", it) }
    }

    suspend fun delete(sessionId: String) {
        dao.children(sessionId).forEach { dao.delete(it.id); dao.deleteEvents(it.id) }
        dao.delete(sessionId); dao.deleteEvents(sessionId); states.remove(sessionId)
        ShotStore.deleteSession(context, sessionId)
        _status.update { it - sessionId }
    }

    // ---- SDK callbacks ---------------------------------------------------------------------------

    /** Maps a dsh event seq to its log seq (see [LogSeqs]); the log's newest seq is read only when a generation starts. */
    private suspend fun logSeq(sessionId: String, gen: Int, seq: Long): Long =
        logSeqs.mapped(sessionId, gen, seq) ?: logSeqs.start(sessionId, gen, seq, flowOf(sessionId).value.events.maxOfOrNull { it.seq } ?: -1L).also { mapped ->
            if (mapped != seq) android.util.Log.i(TAG, "session $sessionId gen $gen: engine seq $seq was already logged; logging from $mapped")
        }

    suspend fun onNotification(method: String, params: JsonObject, gen: Int) {
        if (method == "subagent.started") {
            val parent = params.s("parentSessionId") ?: return
            val child = params.s("childSessionId") ?: return
            if (dao.get(child) == null) {
                val now = System.currentTimeMillis()
                dao.upsert(SessionEntity(child, "", now, now, ruleIdOfSession(parent), parentId = parent))
            }
            return
        }
        if (method == "user-question.cancelled") { params.s("requestId")?.let(::cancelQuestion); return }
        val sessionId = params.s("sessionId") ?: return
        when (method) {
            "session.event" -> {
                val event = params.o("event") ?: return
                val type = event.s("type") ?: return
                val seq = logSeq(sessionId, gen, event.l("seq") ?: return)
                val time = event.l("time") ?: System.currentTimeMillis()
                if (type in UNLOGGED) return
                val op = event["surfaceOp"]
                val data = slim(type, event.o("data") ?: JsonObject(emptyMap())).let { d ->
                    // Keep a non-append surfaceOp (the pruner's `replace` of an old tool result): it is not a new result.
                    if (op == null || (op is JsonPrimitive && op.contentOrNull == "append")) d else JsonObject(d + (SURFACE_OP_KEY to op))
                }
                if (dao.get(sessionId) != null) dao.insertEvent(EventEntity(sessionId, seq, type, data.toString(), time))
                mutate(sessionId) { s -> if (s.events.any { it.seq == seq }) s else s.copy(events = s.events + LoggedEvent(seq, type, time, data)) }
                if (type == "session/title") data.s("title")?.let { rename(sessionId, it) }
                if (type == "assistant/message" || type == "assistant/attempt") recordUsage(data)
            }
            "session.status" -> {
                // A torn-down generation's status (its tail drained after the loss) is not this run's.
                if (gen <= engine.retiredGeneration) return
                val running = params.s("status") == "running"
                // A repeated idle while the session waits for the network must not end (or re-arm) the wait.
                if (!running && flowOf(sessionId).value.waitingForNetwork) return
                dao.setRunning(sessionId, running)
                mutate(sessionId) { it.copy(running = running, startingEngine = false, runStartedAt = if (running) (it.runStartedAt ?: System.currentTimeMillis()) else null) }
                if (!running) onTurnFinished(sessionId)
            }
        }
    }

    /**
     * Drops the bulky model stream from assistant events, keeping its first-token time and (for a
     * failed `assistant/attempt`, whose usage exists only in the stream) its last usage report.
     */
    private fun slim(type: String, data: JsonObject): JsonObject {
        if (type != "assistant/message" && type != "assistant/attempt") return data
        val stream = data["stream"] as? JsonArray ?: return data
        val first = stream.mapNotNull { (it as? JsonObject)?.l("time0") }.minOrNull()
        val usage = if (data["usage"] != null) null else stream.mapNotNull { r ->
            (r as? JsonObject)?.o("chunk")?.takeIf { it.s("type") == "usage" }?.o("usage")
        }.lastOrNull()
        return JsonObject(data.filterKeys { it != "stream" } +
            (if (first != null) mapOf("firstTokenTime" to JsonPrimitive(first)) else emptyMap()) +
            (if (usage != null) mapOf("usage" to usage) else emptyMap()))
    }

    suspend fun onServerRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "approval/request" -> buildJsonObject { put("outcome", approve(params)) }
        "user-question/ask" -> ask(params)
        else -> throw JsonRpcException(-32601, "unsupported $method")
    }

    private suspend fun approve(p: JsonObject): String {
        // Subagent requests carry the child session plus rootSessionId; approvals belong to the root conversation.
        val sessionId = (p["rootSessionId"] ?: p["sessionId"])?.jsonPrimitive?.contentOrNull ?: return "rejected"
        val tool = p.s("toolName") ?: return "rejected"
        val ruleId = ruleIdOfSession(sessionId)
        if (ruleId != null) {
            // Unattended: never prompt; allow only what the rule's owner approved.
            val spec = rules.get(ruleId)?.let { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }
            return if (spec != null && tool in spec.allowedTools) "allowed-once" else "rejected"
        }
        val live = flowOf(sessionId).value
        val risk = capabilities.descriptor(tool)?.risk ?: Risk.write
        android.util.Log.i(TAG, "approval $tool (risk $risk) for $sessionId (from ${p.s("sessionId")}), preset ${live.preset.id}")
        when (live.preset) {
            PermissionPreset.ReadOnly -> return "rejected"
            // Full access asks only before messaging other people (SMS, notification replies).
            PermissionPreset.Full -> if (tool !in OUTWARD_TOOLS) return "allowed-once"
            // Reading the phone's own data (location, notifications, …) never asks.
            PermissionPreset.Ask -> if (risk == Risk.sensitive || risk == Risk.read) return "allowed-once"
        }
        if (sessionGrants[sessionId]?.contains(tool) == true && risk != Risk.dangerous) return "allowed-once"
        val key = UUID.randomUUID().toString()
        val callId = p.s("callId")
        val args = callArguments(sessionId, p.s("sessionId"), callId)
        val deferred = CompletableDeferred<ApprovalOutcome>()
        approvals[key] = deferred
        val outcome = awaitCard(sessionId, PendingApproval(key, sessionId, tool, callId, p.s("reason"), args, risk, risk != Risk.dangerous), key, approvals, deferred) {
            if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, sessionId.hashCode(),
                context.getString(R.string.notif_approval_title), context.getString(R.string.notif_approval_text, tool), sessionId)
        }
        android.util.Log.i(TAG, "approval $tool answered $outcome; preset now ${flowOf(sessionId).value.preset.id}")
        return when (outcome) {
            ApprovalOutcome.AllowOnce -> "allowed-once"
            ApprovalOutcome.AllowSession -> { sessionGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(tool); "allowed-once" }
            ApprovalOutcome.Reject -> "rejected"
        }
    }

    /**
     * Shows [card] in [owner]'s composer slot (queued behind earlier cards of its kind) until
     * [deferred] is answered, or the request is cancelled because its engine generation ended. The
     * card always leaves the slot, even when the waiting coroutine is cancelled.
     */
    private suspend fun <T> awaitCard(owner: String, card: Any, key: String, registry: MutableMap<String, CompletableDeferred<T>>,
                                      deferred: CompletableDeferred<T>, announce: () -> Unit): T {
        try {
            mutate(owner) { it.withPending(card) }
            announce()
            return deferred.await()
        } finally {
            registry.remove(key)
            withContext(NonCancellable) { mutate(owner) { it.withoutPending(key) } }
        }
    }

    private suspend fun callArguments(root: String, own: String?, callId: String?): String? {
        callId ?: return null
        for (id in listOfNotNull(own, root).distinct()) {
            flowOf(id).value.events.lastOrNull { it.type == "tool/call" && it.data.s("callId") == callId }?.let { return it.data.s("arguments") }
        }
        return null
    }

    fun answerApproval(requestKey: String, outcome: ApprovalOutcome) { approvals[requestKey]?.complete(outcome) }

    private suspend fun ask(p: JsonObject): JsonElement {
        val sessionId = p.s("sessionId") ?: throw JsonRpcException(-32602, "no session")
        // A subagent's own id has no rule prefix; the conversation it belongs to (its root) decides.
        val owner = rootOf(sessionId)
        if (ruleIdOfSession(owner) != null) throw JsonRpcException(-32000, "PROVIDER_ERROR: unattended session cannot ask the user")
        val key = p.s("requestId") ?: UUID.randomUUID().toString()
        val qs = p.a("questions")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PendingQuestion.Question(
                id = o.s("id") ?: return@mapNotNull null,
                header = o.s("header"),
                text = o.s("question") ?: o.s("text") ?: "",
                detail = o.s("detail"),
                options = o.a("options")?.mapNotNull { opt ->
                    when (opt) {
                        is JsonObject -> opt.s("label")?.let { PendingQuestion.Option(it, opt.s("description")) }
                        is JsonPrimitive -> PendingQuestion.Option(opt.content, null)
                        else -> null
                    }
                } ?: emptyList(),
                multi = o.b("multiSelect") == true,
            )
        } ?: emptyList()
        val deferred = CompletableDeferred<JsonElement>()
        questions[key] = deferred
        return awaitCard(owner, PendingQuestion(key, owner, qs), key, questions, deferred) {
            if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode(),
                context.getString(R.string.notif_question_title), qs.firstOrNull()?.text ?: "", owner)
        }
    }

    private suspend fun rootOf(sessionId: String): String {
        var id = sessionId
        repeat(8) { id = dao.get(id)?.parentId ?: return id }
        return id
    }

    /** Called when the runtime cancels a pending question (`user-question.cancelled`). */
    fun cancelQuestion(requestKey: String) { questions[requestKey]?.completeExceptionally(JsonRpcException(-32000, "cancelled")) }

    /** answers: questionId -> selected labels plus an optional custom text. */
    fun answerQuestion(requestKey: String, answers: Map<String, Pair<List<String>, String?>>) {
        questions[requestKey]?.complete(buildJsonObject {
            putJsonArray("answers") {
                answers.forEach { (id, a) -> addJsonObject {
                    put("id", id)
                    put("selected", buildJsonArray { a.first.forEach { add(it) } })
                    a.second?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                } }
            }
        })
    }

    /**
     * A device tool needs an Android permission that is not granted. Shows the permission card in
     * the composer slot and suspends until the user grants (true, the call is retried) or declines.
     */
    suspend fun requestPermission(sessionId: String, toolName: String, what: String): Boolean {
        val owner = rootOf(sessionId)
        val key = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        permissions[key] = deferred
        return awaitCard(owner, PendingPermission(key, owner, toolName, permissionIdFor(what), what), key, permissions, deferred) {
            if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode(),
                context.getString(R.string.perm_card_strip), what, owner)
        }
    }

    fun answerPermission(requestKey: String, granted: Boolean) { permissions[requestKey]?.complete(granted) }

    /**
     * A screen step cannot be automated (FLAG_SECURE / black screenshot, or input the agent cannot
     * deliver): pause the call with a 请你来操作 card until the user has done it and taps 继续 (true)
     * or 停止 (false). AutoGLM-style manual takeover.
     */
    suspend fun requestTakeover(sessionId: String, toolName: String, reason: String): Boolean {
        val owner = rootOf(sessionId)
        val key = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        takeovers[key] = deferred
        return awaitCard(owner, PendingTakeover(key, owner, toolName, reason), key, takeovers, deferred) {
            Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode() + 2,
                context.getString(R.string.takeover_title), context.getString(R.string.takeover_notif), owner)
        }
    }

    fun answerTakeover(requestKey: String, done: Boolean) { takeovers[requestKey]?.complete(done) }

    /** Engine refused a step because a token/cost budget is exhausted. */
    suspend fun onBudgetExceeded(p: JsonObject) {
        val sessionId = p.s("sessionId") ?: return
        appendLocal(rootOf(sessionId), "app/notice", buildJsonObject {
            put("text", context.getString(R.string.budget_exceeded, p.s("kind") ?: "", p.s("used") ?: "", p.s("limit") ?: ""))
        })
    }

    /** A device call of [sessionId] waits for the phone to be unlocked. Ignored for a session that is no longer running. */
    suspend fun setWaitingForUnlock(sessionId: String, waiting: Boolean) = withContext(NonCancellable) {
        mutate(rootOf(sessionId)) { if (waiting && !it.running) it else it.copy(waitingForUnlock = waiting) }
    }

    private suspend fun onTurnFinished(sessionId: String) {
        if (waitForNetwork(sessionId)) return
        val live = flowOf(sessionId).value
        if (live.queue.isNotEmpty()) {
            val next = live.queue.first()
            mutate(sessionId) { it.copy(queue = it.queue.drop(1)) }
            scope.launch { prompt(sessionId, next.text) }
            return
        }
        if (states.values.none { it.value.running }) engine.scheduleIdleShutdown()
        if (dao.get(sessionId)?.parentId != null) return
        if (!isViewed(sessionId) || !AppVisibility.isForeground) dao.setUnread(sessionId, true)
        if (!AppVisibility.isForeground) {
            val conv = Projector.conversation(live.events, running = false)
            val reply = conv.turns.lastOrNull()?.reply?.text ?: return
            Notifications.post(context, Notifications.CHANNEL_TASKS, sessionId.hashCode(),
                live.title.ifBlank { context.getString(R.string.app_name) }, reply.take(300), sessionId)
        }
    }

    /**
     * The turn just ended failed with a network-type model error ([NETWORK_CODES], already retried
     * for ~25 s by dsh's llm-retry) while the phone has no validated internet, e.g. the agent itself
     * turned Wi‑Fi off. Instead of a final failure the session stays running, waiting (no wake lock)
     * for the network, and then gets one continuation prompt. One automatic continuation per turn
     * (shared with [onEngineLost], see [autoContinued]): the continuation turn failing offline again
     * is a final failure. Stop or a new prompt ends the wait. Returns whether it waits.
     */
    private suspend fun waitForNetwork(sessionId: String): Boolean {
        val flow = flowOf(sessionId)
        val end = flow.value.events.lastOrNull { it.type == "turn/start" || it.type == "turn/end" }?.takeIf { it.type == "turn/end" } ?: return false
        val reason = end.data.o("reason")
        if (reason?.s("kind") != "error" || reason.o("error")?.s("code") !in NETWORK_CODES) return false
        if (dao.get(sessionId)?.parentId != null || Connectivity.online(context) || !autoContinued.add(sessionId)) return false
        android.util.Log.w(TAG, "session $sessionId: turn failed offline; continuing when the network is back")
        // Marked running like a live turn, so an app restart meanwhile shows it as interrupted (继续).
        dao.setRunning(sessionId, true)
        mutate(sessionId) { it.copy(running = true, waitingForNetwork = true, runStartedAt = System.currentTimeMillis()) }
        appendLocal(sessionId, "app/notice", buildJsonObject { put("kind", NETWORK_WAIT); put("text", context.getString(R.string.network_wait)) })
        scope.launch {
            combine(Connectivity.onlineFlow(context), flow) { online, s -> online || !s.waitingForNetwork }.first { it }
            if (!flow.value.waitingForNetwork) return@launch
            mutate(sessionId) { it.copy(waitingForNetwork = false) }
            prompt(sessionId, CONTINUE_AFTER_NETWORK, ruleIdOfSession(sessionId))
        }
        return true
    }

    /**
     * The engine channel closed unexpectedly (AC8). dsh's `session/resume` only repairs the
     * interrupted final turn (closes dangling tool calls) and cannot continue it, so recovery is:
     * after the [RestartPolicy][io.github.jackwener.dsh.core.engine.RestartPolicy] backoff
     * ([retryInMs]) each interrupted root session is resumed (prompt() does that once per engine
     * generation) and sent one continuation prompt that tells the model what happened. A session
     * gets one automatic continuation per turn; a second loss in the same turn, or an open circuit
     * breaker ([retryInMs] == null), leaves the visible engine_lost error with its 继续 button.
     */
    suspend fun onEngineLost(gen: Int, retryInMs: Long?) {
        if (stopping) return
        android.util.Log.w(TAG, "engine gen $gen lost; retry in $retryInMs ms")
        cancelPending()
        // A session waiting for the network has no turn in the engine; its continuation starts a new one.
        val waiting = states.values.map { it.value }.filter { it.waitingForNetwork }.map { it.sessionId }.toSet()
        val running = states.values.map { it.value }.filter { it.running }.map { it.sessionId }.toSet() + dao.running().map { it.id } - waiting
        val roots = mutableListOf<String>()
        running.forEach { id ->
            dao.setRunning(id, false)
            if (dao.get(id)?.parentId == null) roots += id
            else mutate(id) { it.idle() }
        }
        roots.forEach { id ->
            if (retryInMs != null && autoContinued.add(id)) {
                // Continues the same task: its queue stays and is sent after the continuation, as usual.
                mutate(id) { it.copy(running = true, startingEngine = true, runStartedAt = System.currentTimeMillis(), waitingForUnlock = false) }
                appendLocal(id, "app/notice", buildJsonObject { put("kind", ENGINE_RECOVERING); put("text", context.getString(R.string.engine_recovering)) })
            } else {
                mutate(id) { it.idle() }
                appendLocal(id, "app/error", buildJsonObject {
                    put("kind", "engine_lost")
                    put("message", context.getString(if (retryInMs == null) R.string.err_engine_crash_loop else R.string.err_engine_lost))
                })
            }
        }
        if (retryInMs == null) return
        val resume = roots.filter { it in autoContinued }
        if (resume.isEmpty()) return
        scope.launch {
            delay(retryInMs)
            // Skip sessions the user stopped during the backoff.
            resume.filter { flowOf(it).value.running }.forEach { id -> scope.launch { prompt(id, CONTINUE_AFTER_CRASH, ruleIdOfSession(id)) } }
        }
    }

    private suspend fun recordUsage(data: JsonObject) {
        val u = Usage.of(data.o("usage")) ?: return
        val day = LocalDate.now().toString()
        val cur = usage.get(day) ?: UsageEntity(day, 0, 0, 0)
        // inputTokens is the whole prompt (cached part included), cachedTokens the cache hits within it.
        usage.upsert(cur.copy(inputTokens = cur.inputTokens + u.input, outputTokens = cur.outputTokens + u.output, cachedTokens = cur.cachedTokens + u.cacheRead))
    }

    private companion object {
        const val TAG = "SessionStore"
        const val RESUME_RETRY_MS = 1_000L
        /** Tools that send messages to other people; the only ones full access still asks about. */
        val OUTWARD_TOOLS = setOf("sms_send", "notification_reply")

        /** Model-facing continuation after an engine crash (the interrupted tool calls were closed by session/resume). */
        const val CONTINUE_AFTER_CRASH = APP_PROMPT_PREFIX + "The agent engine stopped unexpectedly and was restarted, so your previous turn was " +
            "interrupted. Every tool call above that has a successful result already took effect: do NOT call any tool to repeat or " +
            "re-apply it, even if the device state now looks different (the user may have changed it since). A call without a result " +
            "may not have run; check before redoing only that one. Then continue the remaining steps; if nothing is left, just give the final reply."

        /** Model-facing continuation once the phone is back online after a turn failed offline ([waitForNetwork]). */
        const val CONTINUE_AFTER_NETWORK = APP_PROMPT_PREFIX + "The network dropped and your previous reply could not be sent, so your " +
            "previous turn ended early; the network is back now. Every tool call shown above already ran and its effect is done. Do NOT call " +
            "any tool to repeat or re-apply them, even if the device state now looks different: the user may have changed it since " +
            "(e.g. turned the network back on). Only do steps of the task that were never attempted; if all its actions are done, just give the final reply."

        /** Model error codes of a request that could not reach the provider (dsh LlmError). */
        val NETWORK_CODES = setOf("TRANSPORT", "TIMEOUT")

        /** Log-only records the phone UI never shows; they carry the whole prompt and tool schema. */
        val UNLOGGED = setOf("request/header", "request/context", "system/message", "developer/message", "session/end-seed")

        /** Maps a PermissionMissingException description to a PermissionCatalog id. */
        fun permissionIdFor(what: String): String? {
            val w = what.lowercase()
            return when {
                "accessibility" in w -> "accessibility"
                "notification access" in w -> "notif_access"
                "all files" in w -> "files"
                "do not disturb" in w -> "dnd"
                "modify system" in w -> "write_settings"
                "shizuku" in w -> null
                listOf("contacts", "sms", "call log", "calendar").any { it in w } -> "comms"
                "location" in w -> "location"
                "bluetooth" in w -> "bluetooth"
                else -> null
            }
        }
    }
}
