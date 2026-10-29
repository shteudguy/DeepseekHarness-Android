package io.github.jackwener.dsh.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.account.AccountRepository
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.provider.renderProfilePatch
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

/** Neither a DeepSeek API key nor a DeepSeek account login: the engine is up, but no model route can authenticate. */
class NoCredentialsException : Exception("No DeepSeek API key and not signed in to a DeepSeek account")
class EngineStartException(message: String, val log: String?, cause: Throwable) : Exception(message, cause)
/** Android refused to start the engine's foreground service from the background (Android 12+). */
class EngineBackgroundStartException(cause: Throwable) : Exception("foreground service start not allowed from the background", cause)

/**
 * The process-wide SDK route (`initialize`): provider + model + effort ([effort] is the Effort id;
 * "" for third-party routes, which use the model family's own default and send none).
 */
data class EngineRoute(val provider: String, val model: String, val effort: String)

/** The selected third-party route could not be initialized (engine fell back to DeepSeek). */
class RouteUnavailableException(message: String) : Exception(message)

/**
 * Owns the `:engine` process from the app side: starts it on demand, wires the SDK and capability
 * channels, and shuts it down after idling. See docs/ENGINE-CONTRACT.md.
 *
 * Credentials: an API key (passed as `DEEPSEEK_API_KEY` at process start) or a DeepSeek account
 * login (held by the engine itself). Precedence mirrors dsh's `initializeDefaultModel`: the account
 * route `deepseek-account` is used only when no API key is configured; otherwise
 * `deepseek-official`. The engine starts without either, because account login runs inside it.
 *
 * Third-party providers (Settings → 模型服务): the app writes the user patch layer
 * `$DSH_HOME/profiles/android/cordis.patch.yml` (llm-service-lite providers, see
 * core/provider/Providers.kt) before each start and passes each provider key as
 * `DSH_ANDROID_KEY_<ID>`. Both are read once at process start, so a change to either (the
 * [profileFingerprint]) restarts the engine when it is idle, like a route change.
 */
@Singleton
class EngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val capabilities: Lazy<CapabilityHub>,
    private val sessions: Lazy<SessionStore>,
    private val account: Lazy<AccountRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "engine task crashed", e) })
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var sdk: JsonRpcPeer? = null
    private var bridge: JsonRpcPeer? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null
    var startupMillis: Long = 0; private set

    /** Incremented on every engine start; sessions must be resumed once per generation. */
    @Volatile var generation: Int = 0; private set
    /**
     * Highest generation that has been torn down (stopped, crashed or failed to start). Anything a
     * retired generation still delivers describes a process that no longer exists.
     */
    @Volatile var retiredGeneration: Int = 0; private set
    private val _route = MutableStateFlow<EngineRoute?>(null)
    /** Route of the running engine (null while stopped). A settings change that differs waits until no run is active. */
    val route: StateFlow<EngineRoute?> = _route.asStateFlow()
    private var activeRoute: EngineRoute?
        get() = _route.value
        set(v) { _route.value = v }
    /** Device time zone the running engine was started with (TZ is read once, at process start). */
    @Volatile private var startedTz: String = TimeZone.getDefault().id
    /** Whether an SDK session was opened in this generation (then a route change needs a restart). */
    @Volatile private var sessionsOpened = false
    private var death: CompletableDeferred<Unit>? = null
    /** Generation we asked to exit; its channel closing is not a crash. */
    @Volatile private var expectedExit = -1
    /** Backoff + circuit breaker for unexpected engine exits (see RestartPolicy). */
    val restarts = RestartPolicy()

    private fun wantedRoute(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean): EngineRoute {
        val choice = st.choice
        choice.provider?.let { return EngineRoute(it.id, choice.model, "") }
        return EngineRoute(if (!hasKey && account.get().signedIn) "deepseek-account" else "deepseek-official", choice.model, st.effort.id)
    }

    /** web_search needs a DeepSeek credential: the API key, or the account while on the account route. */
    private fun webSearch(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean) = hasKey || st.choice.isDeepSeek

    private fun profilePatch(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean) =
        renderProfilePatch(
            st.providers,
            webSearch(st, hasKey),
            // Durable full-text index for the past-session search tool (built on the first search).
            File(context.filesDir, "dsh-home/android/sessions.db").path,
        )

    /** Provider keys for the engine environment (env var name → key). */
    private fun providerKeys(st: io.github.jackwener.dsh.core.data.Settings): Map<String, String> =
        st.providers.filter { it.enabled && it.hasKey }.mapNotNull { p -> secrets.read(p.secretName)?.let { p.keyEnv to it } }.toMap()

    /** What a running process has baked in at start: the patch layer and the provider keys (hashed, never stored). */
    private fun profileFingerprint(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(profilePatch(st, hasKey).toByteArray())
        providerKeys(st).toSortedMap().forEach { (k, v) -> md.update("$k=$v\n".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    @Volatile private var activeFingerprint: String? = null
    /** A third-party route that failed to initialize in this generation, and why. */
    @Volatile private var failedRoute: Pair<EngineRoute, String>? = null

    /**
     * Returns a ready SDK peer for prompts, starting the engine if needed. The route is
     * process-wide (`initialize`): a changed model / effort / credential route re-initializes an
     * engine that has not opened sessions yet, or restarts an idle one, before the next prompt.
     * While any run is active (including the caller's own, when it steers a running session) the
     * old route is kept: restarting would kill those turns.
     * Throws [NoCredentialsException] (with the engine left running, so the user can sign in)
     * when there is neither an API key nor an account login.
     */
    /** The running engine's channel, without starting one (null while stopped or starting). */
    fun sdkIfReady(): JsonRpcPeer? = sdk?.takeIf { state.value == EngineState.Ready }

    suspend fun sdk(): JsonRpcPeer = mutex.withLock {
        val st = settings.current()
        val hasKey = secrets.read() != null
        val peer = sdk?.takeIf { _state.value == EngineState.Ready }?.let { peer ->
            val want = wantedRoute(st, hasKey)
            when {
                sessions.get().hasEngineRuns() -> peer
                // The device time zone changed since start: a new process picks it up (when idle, like a route change).
                TimeZone.getDefault().id != startedTz -> { Log.i(TAG, "time zone changed: restarting engine"); shutdownLocked(); null }
                // Providers / keys / web search changed: only a new process reads them.
                profileFingerprint(st, hasKey) != activeFingerprint -> { Log.i(TAG, "model providers changed: restarting engine"); shutdownLocked(); null }
                want == activeRoute -> peer
                // This process already failed that route with the same configuration: report it (below) without churning.
                want == failedRoute?.first -> peer
                !sessionsOpened -> runCatching { initialize(peer, want); peer }.getOrElse {
                    Log.w(TAG, "re-initialize failed; restarting", it); shutdownLocked(); null
                }
                else -> { Log.i(TAG, "route changed: restarting engine"); shutdownLocked(); null }
            }
        } ?: startLocked(st, hasKey)
        val want = wantedRoute(st, hasKey)
        if (want != activeRoute && !st.choice.isDeepSeek && !sessions.get().hasEngineRuns())
            throw RouteUnavailableException("${st.choice.provider?.name ?: want.provider} / ${want.model}: ${failedRoute?.second ?: "unavailable"}")
        if (st.choice.isDeepSeek && !hasKey && !account.get().signedIn) throw NoCredentialsException()
        peer
    }

    /** A ready peer for control traffic (account login, profile); never restarts a running engine. */
    suspend fun control(): JsonRpcPeer = mutex.withLock {
        sdk?.takeIf { _state.value == EngineState.Ready } ?: startLocked(settings.current(), secrets.read() != null)
    }

    /** SessionStore is about to open (create/resume) an SDK session in the current generation. */
    fun markSessionOpened() { sessionsOpened = true }

    private suspend fun initialize(peer: JsonRpcPeer, route: EngineRoute) {
        withTimeout(90_000) {
            peer.request("initialize", buildJsonObject {
                put("cwd", File(context.filesDir, "home").path)
                put("provider", route.provider)
                put("model", route.model)
                if (route.effort.isNotEmpty()) put("reasoningEffort", route.effort)
                put("approvalRequests", true)
                putJsonObject("capabilities") { put("userQuestions", true) }
            })
        }
        activeRoute = route
        Log.i(TAG, "SDK route ${route.provider}/${route.model}/${route.effort}")
    }

    private suspend fun startLocked(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean): JsonRpcPeer {
        _state.value = EngineState.Starting
        val t0 = System.currentTimeMillis()
        val gen = generation + 1
        try {
            var service = bind(gen)
            if (runCatching { service.isRunning() }.getOrDefault(false)) {
                // A previous :main died while :engine survived; Node runs once per process, so replace it.
                Log.w(TAG, "engine process already ran Node; replacing it")
                val dying = death
                connection?.let { runCatching { context.unbindService(it) } }
                connection = null
                killEngineProcess()
                withTimeoutOrNull(5_000) { dying?.await() }
                service = bind(gen)
            }
            val tBound = System.currentTimeMillis()
            val fingerprint = profileFingerprint(st, hasKey)
            withContext(Dispatchers.IO) { writeProfilePatch(profilePatch(st, hasKey)) }
            val handles = withContext(Dispatchers.IO) { service.start(environment(secrets.read(), providerKeys(st))) }
            val sdkIn = ParcelFileDescriptor.AutoCloseOutputStream(handles.pfd("sdk_in"))
            val sdkOut = ParcelFileDescriptor.AutoCloseInputStream(handles.pfd("sdk_out"))
            val bridgeFd = handles.pfd("bridge")
            val peer = JsonRpcPeer("sdk", sdkOut, sdkIn, scope,
                onRequest = { m, p -> sessions.get().onServerRequest(m, p) },
                onNotification = { m, p -> if (m.startsWith("account.")) account.get().onNotification(m, p) else sessions.get().onNotification(m, p, gen) },
                onClosed = { onEngineGone(gen) })
            val bridgePeer = JsonRpcPeer("bridge",
                ParcelFileDescriptor.AutoCloseInputStream(bridgeFd),
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(bridgeFd.fileDescriptor)),
                scope,
                onRequest = { m, p -> capabilities.get().onBridgeRequest(m, p) },
                onNotification = { m, p -> if (m == "budget/exceeded") sessions.get().onBudgetExceeded(p) else capabilities.get().onBridgeNotification(m, p) },
                onClosed = {})
            peer.start(); bridgePeer.start()
            sessionsOpened = false
            // The account decides the route when there is no key; read it before `initialize`.
            runCatching { withTimeout(90_000) { peer.request("account/state") } }
                .onSuccess { account.get().onEngineView(it) }
                .onFailure { Log.w(TAG, "account/state unavailable", it) }
            val tBooted = System.currentTimeMillis()
            val want = wantedRoute(st, hasKey)
            failedRoute = null
            try {
                initialize(peer, want)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || st.choice.isDeepSeek) throw e
                // A broken third-party route must not take the engine (account login, DeepSeek) down.
                Log.w(TAG, "third-party route ${want.provider}/${want.model} failed; falling back to DeepSeek", e)
                failedRoute = want to (e.message ?: e.javaClass.simpleName)
                initialize(peer, EngineRoute(if (!hasKey && account.get().signedIn) "deepseek-account" else "deepseek-official", io.github.jackwener.dsh.core.provider.DEFAULT_DEEPSEEK_MODEL, st.effort.id))
            }
            activeFingerprint = fingerprint
            sdk = peer; bridge = bridgePeer
            generation = gen
            capabilities.get().attach(bridgePeer)
            bridgePeer.notify("budget/update", buildJsonObject {
                if (st.sessionTokenLimit > 0) put("sessionTokenLimit", st.sessionTokenLimit)
                if (st.dailyTokenLimit > 0) put("dailyTokenLimit", st.dailyTokenLimit)
            })
            startupMillis = System.currentTimeMillis() - t0
            _state.value = EngineState.Ready
            // bind = :engine process + service up (Android); boot = Node + dsh until account/state answers.
            Log.i(TAG, "engine gen $gen ready in ${startupMillis}ms (bind ${tBound - t0}, boot ${tBooted - tBound}, initialize ${System.currentTimeMillis() - tBooted})")
            scope.launch { delay(RestartPolicy.STABLE_MS); if (generation == gen && sdk === peer) restarts.recordHealthy() }
            return peer
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            if (e is EngineBackgroundStartException) { teardown(gen); throw e }
            restarts.recordFailure()
            val detail = engineLogTail()
            teardown(gen)
            killEngineProcess()
            _state.value = EngineState.Failed(listOfNotNull(e.message ?: e.javaClass.simpleName, detail).joinToString("\n"))
            throw EngineStartException(e.message ?: e.javaClass.simpleName, detail, e)
        }
    }

    private fun engineLogTail(): String? = runCatching {
        EngineDiagnostics.logTail(context, 600)
    }.getOrNull()

    /** Called when there is no running work; stops the engine after [IDLE_MS]. */
    fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (sessions.get().hasRunning() || account.get().busy) return@launch
            stop()
        }
    }

    fun cancelIdleShutdown() { idleJob?.cancel() }

    /** Hard stop: the SDK has no cancel method, so stopping a turn means stopping the process. */
    suspend fun stop() = mutex.withLock { shutdownLocked() }

    /** Asks Node to exit and waits for the `:engine` process to die so the next start gets a fresh one. */
    private suspend fun shutdownLocked() {
        val dying = death
        expectedExit = generation
        runCatching { withTimeout(3_000) { sdk?.request("shutdown") } }
        teardown(generation)
        if (dying != null && withTimeoutOrNull(5_000) { dying.await() } == null) killEngineProcess()
    }

    /** Last resort for a wedged `:engine` process (Node can start only once per process). */
    private fun killEngineProcess() {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        am.runningAppProcesses?.filter { it.processName == context.packageName + ":engine" }?.forEach {
            Log.w(TAG, "killing engine process ${it.pid}")
            android.os.Process.killProcess(it.pid)
        }
    }

    fun notifyCapabilitiesChanged() {
        scope.launch { runCatching { bridge?.notify("capability/changed") } }
    }

    /**
     * The `:engine` process of [gen] went away unexpectedly. Three signals report it (the SDK
     * pipe's EOF, the binder death link, onServiceDisconnected) on different threads; only the
     * first counts, so one crash is one RestartPolicy failure and one onEngineLost.
     */
    private fun onEngineGone(gen: Int) {
        val peer: JsonRpcPeer
        val retryInMs: Long?
        synchronized(this) {
            peer = sdk ?: return
            if (gen != generation || gen == expectedExit) return
            Log.w(TAG, "engine gen $gen channel closed")
            teardown(gen)
            retryInMs = restarts.recordFailure()
        }
        scope.launch {
            // A binder-death signal can beat the pipe's EOF: let the dead channel's buffered tail
            // (events the process wrote before it died) reach SessionStore before the loss does.
            withTimeoutOrNull(2_000) { peer.closed.await() }
            sessions.get().onEngineLost(gen, retryInMs)
            delay(2_000) // let the system record the exit reason
            EngineDiagnostics.collectExitInfo(context)
        }
    }

    private fun teardown(retire: Int) {
        retiredGeneration = maxOf(retiredGeneration, retire)
        // Every request this generation is still serving (approval cards, device tool calls, unlock
        // waits) is void now: cancel them so nothing acts later or answers into a dead pipe (EngineCalls).
        val cause = EngineCallCancelled("engine generation $retire ended")
        sdk?.cancelRequests(cause); bridge?.cancelRequests(cause)
        sdk = null; bridge = null
        activeRoute = null
        account.get().onEngineStopped()
        capabilities.get().detach()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        context.stopService(Intent(context, EngineService::class.java))
        _state.value = EngineState.Stopped
    }

    private suspend fun bind(gen: Int): IEngineService {
        val deferred = CompletableDeferred<IEngineService>()
        val died = CompletableDeferred<Unit>().also { death = it }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit); onEngineGone(gen) }, 0)
                deferred.complete(IEngineService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { died.complete(Unit); onEngineGone(gen) }
        }
        val intent = Intent(context, EngineService::class.java)
        try {
            context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException; older
            // releases throw a plain IllegalStateException for background starts.
            death = null
            throw EngineBackgroundStartException(e)
        }
        // No BIND_AUTO_CREATE: the started (START_NOT_STICKY) service alone keeps :engine alive. An
        // auto-create binding makes ActivityManager schedule its own restart of a crashed service
        // (1 s, then ×4 per crash, ≥10 s apart); while that restart is pending every new
        // startForegroundService/bindService waits for it, which delayed AC8 recovery by 10–25 s.
        // Restarts are RestartPolicy's job, so a crash must leave nothing scheduled in the system.
        check(context.bindService(intent, conn, Context.BIND_IMPORTANT)) { "bind failed" }
        connection = conn
        return withTimeout(20_000) { deferred.await() }
    }

    /** The user patch layer dsh reads at boot (ENGINE-CONTRACT §1.1); written atomically, only when it changed. */
    private fun writeProfilePatch(text: String) {
        val dir = File(context.filesDir, "dsh-home/profiles/android").apply { mkdirs() }
        val f = File(dir, "cordis.patch.yml")
        if (f.exists() && f.readText() == text) return
        val tmp = File(dir, "cordis.patch.yml.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(f)) { "could not write ${f.path}" }
    }

    private fun environment(apiKey: String?, providerKeys: Map<String, String>) = Bundle().apply {
        startedTz = TimeZone.getDefault().id
        val files = context.filesDir
        putString("DSH_HOME", File(files, "dsh-home").path)
        putString("HOME", File(files, "home").path)
        putString("TMPDIR", File(context.cacheDir, "tmp").path)
        putString("SHELL", "/system/bin/sh")
        putString("PATH", "${File(files, "bin").path}:/system/bin:/system/xbin")
        putString("DSH_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
        apiKey?.let { putString("DEEPSEEK_API_KEY", it) }
        providerKeys.forEach { (env, key) -> putString(env, key) }
        putString("DSH_ANDROID_PACKAGE", context.packageName)
        // The engine turns this into the system prompt language hint: use the app's own locale (per-app
        // language / UI language) so the model reasons and answers in the language the UI is in, not in
        // whatever the system locale happens to be.
        val appLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        putString("DSH_ANDROID_LOCALE", appLocale.toLanguageTag())
        // Node otherwise runs in UTC (local time for the model, daily budget at local midnight).
        // DSH_ANDROID_TZ lets the bundle's preload re-apply it where AT_SECURE makes Node drop TZ.
        putString("TZ", startedTz)
        putString("DSH_ANDROID_TZ", startedTz)
        putString("NODE_OPTIONS", "--max-old-space-size=768")
        putString("DSH_TELEMETRY_DISABLED", "1")
        putString("NARB_DISABLE_NATIVE_CACHE", "1")
    }

    private fun Bundle.pfd(key: String): ParcelFileDescriptor =
        @Suppress("DEPRECATION") (getParcelable<ParcelFileDescriptor>(key) ?: error("missing $key"))

    private companion object {
        const val TAG = "EngineManager"
        const val IDLE_MS = 5 * 60_000L
    }
}
