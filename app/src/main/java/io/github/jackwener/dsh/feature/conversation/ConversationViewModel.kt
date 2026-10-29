package io.github.jackwener.dsh.feature.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.session.ApprovalOutcome
import io.github.jackwener.dsh.core.session.Conversation
import io.github.jackwener.dsh.core.session.PendingApproval
import io.github.jackwener.dsh.core.session.PendingQuestion
import io.github.jackwener.dsh.core.session.Projector
import io.github.jackwener.dsh.core.session.RowStatus
import io.github.jackwener.dsh.core.session.SessionLive
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.core.session.Trajectory
import io.github.jackwener.dsh.core.session.TurnError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which representation of the session the top-bar toggle shows. */
enum class SessionView { Chat, Trajectory }

/**
 * One conversation: live state, its projections (conversation + trajectory, both derived from
 * the same event log), and every action the composer slot can take. Shared by the home screen
 * and the quick panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class ConversationController(
    protected val store: SessionStore,
    private val engine: EngineManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val sessionId = MutableStateFlow(store.newSessionId())
    val id: String get() = sessionId.value
    val currentId: StateFlow<String> = sessionId

    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val live: StateFlow<SessionLive> = sessionId.flatMapLatest { id -> flow { emitAll(store.state(id)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionLive(sessionId.value))

    /**
     * A model / effort change waits (the engine keeps its current route) while any run is active:
     * the route is process-wide and switching it restarts the engine. True while the settings differ
     * from the running engine's route for that reason; the composer says the change applies later.
     */
    val modelChangePending: StateFlow<Boolean> = combine(engine.route, settings, store.status) { route, st, status ->
        val want = st.choice
        val differs = route != null && when (val p = want.provider) {
            null -> !route.provider.startsWith("deepseek-") || route.model != want.model || route.effort != st.effort.id
            else -> route.provider != p.id || route.model != want.model
        }
        differs && status.values.any { it != RowStatus.Idle }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val conversation: StateFlow<Conversation> = live
        .distinctUntilChangedBy { Triple(it.sessionId, it.events.size, it.running) }
        .conflate()
        .map { Projector.conversation(it.events, it.running) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Conversation())

    val view = MutableStateFlow(SessionView.Chat)
    /** Trajectory record to scroll to and inspect (set by a tool row's 查看 pill). */
    val focusRecord = MutableStateFlow<String?>(null)

    val trajectory: StateFlow<Trajectory?> = combine(view, live.distinctUntilChangedBy { Triple(it.sessionId, it.events.size, it.running) }) { v, l -> v to l }
        .conflate()
        .flatMapLatest { (v, l) ->
            if (v != SessionView.Trajectory) flowOf<Trajectory?>(null)
            else flow<Trajectory?> { emit(Projector.trajectory(l.events, store.childEvents(l.sessionId), l.running)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { store.setViewing(this, id) }

    open fun open(id: String) {
        sessionId.value = id
        view.value = SessionView.Chat
        store.setViewing(this, id)
    }

    open fun newSession() {
        sessionId.value = store.newSessionId()
        view.value = SessionView.Chat
        store.setViewing(this, sessionId.value)
    }

    /** The session's preset is the store's own; [live] can still be the previous session's right after open/newSession. */
    fun send(text: String, images: List<Pair<String, String>> = emptyList(), now: Boolean = false) = viewModelScope.launch {
        store.send(sessionId.value, text, images, now = now)
    }

    /** The composer took [SessionLive.restoredDraft] (queued messages handed back after a stop or failure). */
    fun consumeRestoredDraft() = store.consumeRestoredDraft(sessionId.value)

    fun stop() = viewModelScope.launch { store.stop() }
    fun retry(e: TurnError) = viewModelScope.launch { store.retry(sessionId.value, e) }
    fun approve(a: PendingApproval, outcome: ApprovalOutcome) = store.answerApproval(a.requestKey, outcome)
    fun answer(q: PendingQuestion, answers: Map<String, Pair<List<String>, String?>>) = store.answerQuestion(q.requestKey, answers)
    fun skipQuestion(q: PendingQuestion) = store.answerQuestion(q.requestKey, q.questions.associate { it.id to (emptyList<String>() to null) })
    fun answerPermission(requestKey: String, granted: Boolean) = store.answerPermission(requestKey, granted)
    fun answerTakeover(requestKey: String, done: Boolean) = store.answerTakeover(requestKey, done)
    fun removeQueued(id: String) = store.removeQueued(sessionId.value, id)
    fun sendQueuedNow(id: String) = viewModelScope.launch { store.sendQueuedNow(sessionId.value, id) }
    fun setPreset(p: PermissionPreset) = viewModelScope.launch { store.setPreset(sessionId.value, p) }
    /** [provider] "" = DeepSeek. */
    fun setModel(provider: String, m: String) = viewModelScope.launch { settingsRepo.setModel(provider, m) }
    fun setEffort(e: Effort) = viewModelScope.launch { settingsRepo.setEffort(e) }

    fun inspect(callId: String) { view.value = SessionView.Trajectory; focusRecord.value = "k$callId" }

    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Machine translations of reasoning rows, keyed by block key. */
    val translations: StateFlow<Map<String, String>> = _translations

    fun translateReasoning(key: String, text: String, targetLanguage: String) = viewModelScope.launch {
        if (text.isBlank() || _translations.value.containsKey(key)) return@launch
        val translated = store.translateReasoning(sessionId.value, text, targetLanguage) ?: return@launch
        _translations.value = _translations.value + (key to translated)
    }

    override fun onCleared() { store.setViewing(this, null) }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    store: SessionStore,
    engine: EngineManager,
    settings: SettingsRepository,
) : ConversationController(store, engine, settings) {
    val sessions: StateFlow<List<SessionEntity>> = store.conversations.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rowStatus: StateFlow<Map<String, RowStatus>> = store.status

    /** Text to drop into the composer (e.g. "新建" on the automation screen). */
    val prefill = MutableStateFlow<String?>(null)

    fun newSession(prefillText: String?) { newSession(); prefill.value = prefillText }

    fun rename(id: String, title: String) = viewModelScope.launch { store.rename(id, title) }
    fun pin(id: String, pinned: Boolean) = viewModelScope.launch { store.setPinned(id, pinned) }
    fun archive(id: String, archived: Boolean) = viewModelScope.launch {
        store.setArchived(id, archived)
        if (archived && id == this@HomeViewModel.id) newSession()
    }
    fun delete(id: String) = viewModelScope.launch {
        store.delete(id)
        if (id == this@HomeViewModel.id) newSession()
    }
}

@HiltViewModel
class QuickViewModel @Inject constructor(
    store: SessionStore,
    engine: EngineManager,
    settings: SettingsRepository,
    @ApplicationContext val context: Context,
) : ConversationController(store, engine, settings)
