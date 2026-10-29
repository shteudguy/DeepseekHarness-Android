package io.github.jackwener.dsh.feature.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import io.github.jackwener.dsh.core.designsystem.LocalReducedMotion
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.designsystem.Whale
import io.github.jackwener.dsh.core.session.Conversation
import io.github.jackwener.dsh.core.session.lastTps
import io.github.jackwener.dsh.core.session.liveTps
import io.github.jackwener.dsh.core.session.SessionLive
import io.github.jackwener.dsh.feature.trajectory.TrajectoryView
import kotlinx.coroutines.launch

/**
 * The single home screen: the conversation, with the dsh sidebar as a modal drawer and a
 * 对话 / 轨迹 toggle for the same session.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, onSettings: (String?) -> Unit, onAutomation: () -> Unit) {
    val c = Dsh.colors
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val status by vm.rowStatus.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = c.scrim,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp), drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                drawerContainerColor = c.sidebar, drawerTonalElevation = 0.dp, windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
                SessionDrawer(
                    sessions, status, currentId,
                    onNew = { vm.newSession(null); scope.launch { drawer.close() } },
                    onOpen = { vm.open(it); scope.launch { drawer.close() } },
                    onAutomation = { scope.launch { drawer.close() }; onAutomation() },
                    onSettings = { section -> scope.launch { drawer.close() }; onSettings(section) },
                    onClose = { scope.launch { drawer.close() } },
                    onRename = vm::rename, onPin = vm::pin, onArchive = vm::archive, onDelete = vm::delete,
                )
            }
        },
    ) {
        ConversationPane(vm, onMenu = { scope.launch { drawer.open() } }, onSettings = onSettings, onAutomation = onAutomation)
    }
}

@Composable
private fun ConversationPane(vm: HomeViewModel, onMenu: () -> Unit, onSettings: (String?) -> Unit, onAutomation: () -> Unit) {
    val c = Dsh.colors
    val live by vm.live.collectAsStateWithLifecycle()
    val conv by vm.conversation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val prefill by vm.prefill.collectAsStateWithLifecycle()
    val translations by vm.translations.collectAsStateWithLifecycle()
    val uiLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val composer = rememberComposerState(live.sessionId)
    LaunchedEffect(prefill, live.sessionId) {
        if (live.sessionId == vm.id) prefill?.let { composer.text = it; vm.prefill.value = null }
    }
    val empty = conv.isEmpty && !live.running
    BackHandler(view == SessionView.Trajectory) { vm.view.value = SessionView.Chat }
    // While the run waits on the user (approval / question / permission / takeover card) nothing is
    // happening, so stop the "working" animations: the UI settles to idle, which also lets
    // accessibility services and UI automation read the card's buttons.
    val waitingOnUser = live.approval != null || live.question != null || live.permission != null || live.takeover != null
    CompositionLocalProvider(LocalReducedMotion provides (LocalReducedMotion.current || waitingOnUser)) {
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.dsw_panel_left_outline, onMenu, size = 40.dp, iconSize = 18.dp, tint = c.labelPrimary, contentDescription = stringResource(R.string.open_sidebar))
            Text(if (empty) "" else live.title.ifBlank { conv.turns.firstOrNull()?.user?.text?.lineSequence()?.firstOrNull() ?: stringResource(R.string.new_session) },
                Modifier.weight(1f).padding(horizontal = 4.dp), style = DshType.s14m, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 对话 / 轨迹: one icon toggle instead of a segmented control, so the title keeps its room at 360 dp.
            if (!empty) {
                val traj = view == SessionView.Trajectory
                IconBtn(if (traj) R.drawable.dsw_flat_list_outline else R.drawable.dsw_inspect_outline,
                    { vm.view.value = if (traj) SessionView.Chat else SessionView.Trajectory }, size = 40.dp, iconSize = 18.dp,
                    tint = if (traj) c.labelPrimary else c.labelSecondary,
                    contentDescription = stringResource(if (traj) R.string.view_chat else R.string.view_trajectory))
            }
            // Nothing to leave on the empty home, so no 新会话 there.
            if (!empty) IconBtn(R.drawable.dsw_new_chat_outline, { vm.newSession(null) }, size = 40.dp, iconSize = 18.dp, tint = c.labelPrimary, contentDescription = stringResource(R.string.new_session))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(Triple(view, empty, live.sessionId), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pane") { (v, isEmpty, _) ->
                when {
                    v == SessionView.Trajectory -> TrajectoryView(vm)
                    isEmpty -> EmptyHero(vm, composer, settings, live, conv, onAutomation)
                    // One list state per session (and per crossfading pane), so positions never leak across sessions.
                    else -> CompositionLocalProvider(
                        LocalReasoningTranslation provides ReasoningTranslationState(translations) { key, text -> vm.translateReasoning(key, text, uiLanguage) },
                    ) {
                        ChatStream(conv, live, settings.workDetails, rememberLazyListState(), onInspect = vm::inspect, onRetry = vm::retry, onOpenSettings = { onSettings(it) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (!empty && view == SessionView.Chat) BottomDock(vm, composer, settings, live, conv, onAutomation)
        else if (view == SessionView.Trajectory) {
            // Trajectory has no composer: keep the run stoppable from here.
            if (live.running) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), contentAlignment = Alignment.Center) {
                io.github.jackwener.dsh.core.designsystem.DButton(stringResource(R.string.stop_generating), { vm.stop() },
                    variant = io.github.jackwener.dsh.core.designsystem.BtnVariant.Outline, icon = R.drawable.dsw_stop_fill)
            } else Spacer(Modifier.navigationBarsPadding())
        }
    }
    }
}

/** dsh EmptyHero: centered whale + 探索未至之境 + 预览版 pill, composer below. No suggestion chips. */
@Composable
private fun EmptyHero(vm: HomeViewModel, composer: ComposerState, settings: Settings, live: SessionLive, conv: Conversation, onAutomation: () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = 8.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Whale(width = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.hero_title), style = DshType.xl24.copy(fontSize = DshType.xl24.fontSize * 1.08f, fontWeight = androidx.compose.ui.text.font.FontWeight(500)),
                color = c.labelPrimary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        ComposerSlot(vm, composer, settings, live, conv, hero = true, onAutomation = onAutomation)
    }
}

/** Bottom dock: [todo dock | queue dock] above the composer slot (approval ▸ permission ▸ question ▸ composer). */
@Composable
private fun BottomDock(vm: HomeViewModel, composer: ComposerState, settings: Settings, live: SessionLive, conv: Conversation, onAutomation: () -> Unit) {
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        conv.todos?.let { if (live.running) TodoDock(it) }
        QueueDock(live.queue, onEdit = { q -> vm.removeQueued(q.id); composer.text = q.text }, onDelete = { vm.removeQueued(it.id) }, onSendNow = { vm.sendQueuedNow(it.id) })
        Box(Modifier.padding(horizontal = 8.dp)) { ComposerSlot(vm, composer, settings, live, conv, hero = false, onAutomation = onAutomation) }
    }
}

/** First non-null wins: permission card, approval card, question card, composer. */
@Composable
fun ComposerSlot(vm: ConversationController, composer: ComposerState, settings: Settings, live: SessionLive, conv: Conversation, hero: Boolean,
                 onAutomation: (() -> Unit)? = null, showChips: Boolean = true, canSendEmpty: Boolean = false,
                 prepare: (String) -> String = { it }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val modelChangePending by vm.modelChangePending.collectAsStateWithLifecycle()
    // Queued messages the run never sent (stopped / failed) come back into the composer.
    LaunchedEffect(live.sessionId, live.restoredDraft) {
        val draft = live.restoredDraft ?: return@LaunchedEffect
        composer.text = listOf(draft, composer.text).filter { it.isNotBlank() }.joinToString("\n\n")
        vm.consumeRestoredDraft()
    }
    when {
        live.takeover != null -> TakeoverCard(live.takeover) { vm.answerTakeover(live.takeover.requestKey, it) }
        live.permission != null -> PermissionCard(live.permission) { vm.answerPermission(live.permission.requestKey, it) }
        live.approval != null -> ApprovalCard(live.approval) { vm.approve(live.approval, it) }
        live.question != null -> QuestionCard(live.question, onSubmit = { vm.answer(live.question, it) }, onSkip = { vm.skipQuestion(live.question) })
        else -> Composer(
            composer, live.running, live.preset, vm::setPreset, settings.choice, settings.providers, settings.effort, vm::setModel, vm::setEffort, conv.contextTokens, liveTps = conv.liveTps(System.currentTimeMillis()) ?: conv.lastTps(),
            onSend = {
                val text = prepare(composer.outgoingText(context))
                val images = composer.images.map { it.mime to it.base64 }
                composer.clear()
                vm.send(text, images, now = settings.busySend == io.github.jackwener.dsh.core.data.BusySend.Steer)
            },
            onStop = { vm.stop() },
            hero = hero,
            actions = ComposerActions(
                onNewSession = if (conv.isEmpty) null else ({ vm.newSession() }),
                onTrajectory = if (conv.isEmpty) null else ({ vm.view.value = SessionView.Trajectory }),
                onAutomation = onAutomation,
            ),
            showChips = showChips,
            canSendEmpty = canSendEmpty,
            modelChangePending = modelChangePending,
        )
    }
}
