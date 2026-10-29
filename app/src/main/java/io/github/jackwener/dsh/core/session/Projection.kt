package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// ---- shared JSON helpers ------------------------------------------------------------------------

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray
internal fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

/** Concatenated `text` blocks of a dsh content array. */
fun textOf(content: JsonElement?): String = (content as? JsonArray)?.mapNotNull {
    val o = it as? JsonObject ?: return@mapNotNull null
    if (o.s("type") == "text") o.s("text") else null
}?.joinToString("\n")?.trim() ?: ((content as? JsonPrimitive)?.contentOrNull ?: "")

private fun reasoningOf(content: JsonElement?): String = (content as? JsonArray)?.mapNotNull {
    val o = it as? JsonObject ?: return@mapNotNull null
    if (o.s("type") == "reasoning") o.s("text") else null
}?.joinToString("\n")?.trim() ?: ""

private fun countOf(content: JsonElement?, type: String) = (content as? JsonArray)?.count { (it as? JsonObject)?.s("type") == type } ?: 0

fun parseArgs(arguments: String?): JsonObject? = arguments?.let { runCatching { RpcJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

// ---- token usage --------------------------------------------------------------------------------

/**
 * Token usage. dsh's counts are disjoint ([uncached] input, [cacheRead], [cacheWrite]); [input] is
 * the whole prompt (dsh token-meter: input + cacheRead + cacheWrite), which is what context
 * pressure and the per-turn ↑ mean.
 */
data class Usage(val uncached: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val reasoning: Long = 0, val cacheWrite: Long = 0) {
    val input get() = uncached + cacheRead + cacheWrite
    val total get() = input + output
    operator fun plus(o: Usage) = Usage(uncached + o.uncached, output + o.output, cacheRead + o.cacheRead, reasoning + o.reasoning, cacheWrite + o.cacheWrite)
    val isEmpty get() = input == 0L && output == 0L

    companion object {
        fun of(u: JsonObject?): Usage? {
            u ?: return null
            fun n(vararg k: String) = k.firstNotNullOfOrNull { u.l(it) } ?: 0L
            return Usage(n("inputTokens", "input"), n("outputTokens", "output"), n("cacheReadTokens", "cacheRead"), n("reasoningTokens"), n("cacheWriteTokens", "cacheWrite"))
        }
    }
}

/**
 * Data key under which SessionStore keeps a non-`append` dsh `surfaceOp` (e.g. the tool-result
 * pruner's `replace`). Such events rewrite what the model sees later; they are not new results.
 */
const val SURFACE_OP_KEY = "_surfaceOp"

internal fun LoggedEvent.isReplacement() = data[SURFACE_OP_KEY] != null

/**
 * Prompts the app itself sends on the user's behalf (e.g. the continuation after an engine crash)
 * start with this marker; dsh records them as user messages, but they are not shown as the user's.
 */
const val APP_PROMPT_PREFIX = "[DSH] "

/** `kind` of the `app/notice` that turns a turn's offline failure into a wait for the network (SessionStore.waitForNetwork). */
const val NETWORK_WAIT = "network_wait"

/** `kind` of the `app/notice` that an engine crash interrupted the turn and it will be continued (SessionStore.onEngineLost). */
const val ENGINE_RECOVERING = "engine_recovering"

/**
 * Notices announcing an automatic continuation of the turn they sit in. When the continuation
 * prompt ([APP_PROMPT_PREFIX]) arrives, its turn is folded into the interrupted one, so the user
 * sees one task whose status is the continuation's, and the notice is marked [Block.Notice.resumed].
 */
val CONTINUATION_NOTICES = setOf(NETWORK_WAIT, ENGINE_RECOVERING)

// ---- conversation projection --------------------------------------------------------------------

enum class ToolState { Running, Done, Error, Rejected, Stopped }

sealed interface Block {
    val key: String
    data class Reasoning(override val key: String, val text: String) : Block
    data class Text(override val key: String, val text: String, val interrupted: Boolean) : Block
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val arguments: String,
        val args: JsonObject?,
        val state: ToolState,
        val result: String?,
        val error: String?,
        val meta: JsonElement?,
        val startTime: Long,
        val endTime: Long?,
    ) : Block
    data class Compaction(override val key: String, val live: Boolean, val count: Int, val tokens: Long, val summary: String, val failed: Boolean) : Block
    /** [kind]: a [CONTINUATION_NOTICES] kind or null; [resumed]: its continuation has started (shown in the past tense). */
    data class Notice(override val key: String, val text: String, val kind: String? = null, val resumed: Boolean = false) : Block
}

data class UserMsg(val seq: Long, val time: Long, val text: String, val images: Int, val files: Int)

enum class TurnStatus { Running, Completed, Failed, Stopped, MaxTokens }

/** kind: llm | engine | engine_lost | no_credentials (legacy no_key) | budget. [retryText] re-sends a prompt that never reached the engine. */
data class TurnError(val kind: String, val message: String, val code: String?, val retryText: String?)

data class Turn(
    val key: String,
    val number: Int?,
    val user: UserMsg?,
    val blocks: List<Block>,
    val startTime: Long,
    val endTime: Long?,
    val status: TurnStatus,
    val error: TurnError?,
    val usage: Usage,
) {
    /** The closing assistant reply: the final text block, shown outside the process fold. */
    val reply: Block.Text? get() = (blocks.lastOrNull() as? Block.Text)?.takeIf { status != TurnStatus.Running }
    val process: List<Block> get() = if (reply != null) blocks.dropLast(1) else blocks
}

data class TodoItem(val content: String, val status: String)

data class Conversation(
    val turns: List<Turn> = emptyList(),
    val todos: List<TodoItem>? = null,
    /** Prompt size of the latest request (context pressure). */
    val contextTokens: Long = 0,
    val usage: Usage = Usage(),
) {
    val isEmpty get() = turns.isEmpty()
    fun tool(callId: String): Block.Tool? = turns.asSequence().flatMap { it.blocks }.filterIsInstance<Block.Tool>().firstOrNull { it.callId == callId }
}

object Projector {
    private class TurnB(val key: String, var number: Int?, var start: Long) {
        var user: UserMsg? = null
        val blocks = mutableListOf<Block>()
        var end: Long? = null
        var status = TurnStatus.Running
        var error: TurnError? = null
        var usage = Usage()
        fun build() = Turn(key, number, user, blocks.toList(), start, end, status, error, usage)
    }

    fun conversation(events: List<LoggedEvent>, running: Boolean): Conversation {
        val turns = mutableListOf<TurnB>()
        var todos: List<TodoItem>? = null
        var context = 0L
        var total = Usage()
        fun open(): TurnB? = turns.lastOrNull()?.takeIf { it.end == null }
        fun close(t: TurnB, time: Long, status: TurnStatus) {
            t.end = time; t.status = status
            if (status != TurnStatus.Completed) t.blocks.replaceAll { b ->
                if (b is Block.Tool && b.state == ToolState.Running) b.copy(state = ToolState.Stopped, endTime = time) else b
            }
        }
        fun current(time: Long, seq: Long): TurnB = open() ?: TurnB("t$seq", null, time).also { turns += it }
        // The turn whose last notice announced an automatic continuation that has not started yet.
        var awaiting: TurnB? = null
        // The continuation prompt was logged before its turn/start: that turn/start continues this one.
        var resumeInto: TurnB? = null
        fun resume(prev: TurnB, number: Int?) {
            prev.number = number ?: prev.number; prev.end = null; prev.status = TurnStatus.Running; prev.error = null
            prev.blocks.replaceAll { b -> if (b is Block.Notice && b.kind in CONTINUATION_NOTICES) b.copy(resumed = true) else b }
        }

        for (e in events) {
            val d = e.data
            when (e.type) {
                "turn/start" -> {
                    val into = resumeInto
                    resumeInto = null
                    if (into != null && turns.lastOrNull() === into) { resume(into, d.i("turn")); continue }
                    open()?.let { close(it, e.time, TurnStatus.Stopped) }
                    // Todo state is session-level in dsh (last write wins), so it carries across turns.
                    turns += TurnB("t${e.seq}", d.i("turn"), e.time)
                }
                "user/message" -> {
                    if (d.o("source")?.s("kind") != "user" || e.isReplacement()) continue
                    val content = d["content"]
                    val prev = awaiting
                    awaiting = null
                    if (textOf(content).startsWith(APP_PROMPT_PREFIX)) {
                        // The continuation's turn becomes the rest of the interrupted one: one task, one status.
                        val t = open()
                        if (prev != null && t != null && t !== prev && t.user == null && t.blocks.isEmpty() && turns.lastOrNull() === t) {
                            turns.removeAt(turns.lastIndex)
                            resume(prev, t.number)
                        } else if (prev != null && turns.lastOrNull() === prev) resumeInto = prev
                        continue
                    }
                    val msg = UserMsg(e.seq, e.time, textOf(content), countOf(content, "image"), countOf(content, "file"))
                    val t = open()
                    if (t != null && t.user == null && t.blocks.isEmpty()) t.user = msg
                    else turns += TurnB("t${e.seq}", null, e.time).also { it.user = msg }
                }
                "assistant/message" -> {
                    val t = current(e.time, e.seq)
                    val content = d.o("message")?.get("content")
                    reasoningOf(content).takeIf { it.isNotBlank() }?.let { t.blocks += Block.Reasoning("r${e.seq}", it) }
                    textOf(content).takeIf { it.isNotBlank() }?.let { t.blocks += Block.Text("x${e.seq}", it, d.b("interrupted") == true) }
                    Usage.of(d.o("usage"))?.let { u -> t.usage += u; total += u; if (u.input > 0) context = u.input }
                }
                "tool/call" -> {
                    val t = current(e.time, e.seq)
                    val callId = d.s("callId") ?: "c${e.seq}"
                    val args = d.s("arguments") ?: ""
                    t.blocks += Block.Tool("k$callId", callId, d.s("name") ?: "?", args, parseArgs(args), ToolState.Running, null, null, null, e.time, null)
                }
                "tool/result" -> {
                    if (e.isReplacement()) continue
                    val msg = d.o("message")
                    val callId = msg?.s("toolCallId") ?: continue
                    val t = turns.lastOrNull { tb -> tb.blocks.any { it is Block.Tool && it.callId == callId } } ?: continue
                    val err = d.o("error")
                    val isError = msg.b("isError") == true || err != null
                    val code = err?.s("code") ?: ""
                    val rejected = listOf("REJECT", "DENIED", "NOT_AUTHORIZED").any { code.contains(it, ignoreCase = true) }
                    val text = textOf(msg["content"])
                    t.blocks.replaceAll { b ->
                        if (b is Block.Tool && b.callId == callId) b.copy(
                            state = when { rejected -> ToolState.Rejected; isError -> ToolState.Error; else -> ToolState.Done },
                            result = text,
                            error = if (isError) (err?.s("reason")?.takeIf { it.isNotBlank() } ?: text.lineSequence().firstOrNull { it.isNotBlank() }) else null,
                            meta = d["meta"],
                            endTime = e.time,
                        ) else b
                    }
                }
                "todo/write" -> todos = d.a("todos")?.mapNotNull { (it as? JsonObject)?.let { o -> TodoItem(o.s("content") ?: "", o.s("status") ?: "pending") } }
                "compaction/start" -> current(e.time, e.seq).blocks += Block.Compaction("m${d.s("compactionId") ?: e.seq}", true, 0, 0, "", false)
                "compaction/summary" -> {
                    val key = "m${d.s("compactionId")}"
                    turns.forEach { t -> t.blocks.replaceAll { b ->
                        if (b is Block.Compaction && b.key == key) b.copy(count = d.a("shadowedSeqs")?.size ?: 0, tokens = d.l("shadowedTokenCount") ?: 0, summary = textOf(d["summary"])) else b
                    } }
                }
                "compaction/end" -> {
                    val key = "m${d.s("compactionId")}"
                    turns.forEach { t -> t.blocks.replaceAll { b -> if (b is Block.Compaction && b.key == key) b.copy(live = false, failed = d.s("error") != null) else b } }
                }
                "turn/end" -> {
                    val t = open() ?: continue
                    // Only the open turn's own end closes it (an end for another turn number is stale).
                    if (t.number != null && d.i("turn") != null && d.i("turn") != t.number) continue
                    val reason = d.o("reason")
                    when (reason?.s("kind")) {
                        "completed" -> close(t, e.time, TurnStatus.Completed)
                        "error" -> {
                            val err = reason.o("error")
                            t.error = TurnError("llm", err?.s("message") ?: err?.s("kind") ?: "error", err?.s("code") ?: err?.s("kind"), null)
                            close(t, e.time, TurnStatus.Failed)
                        }
                        "max-tokens" -> close(t, e.time, TurnStatus.MaxTokens)
                        else -> close(t, e.time, TurnStatus.Stopped)
                    }
                }
                "app/error" -> {
                    val err = TurnError(d.s("kind") ?: "engine", d.s("message") ?: "", d.s("code"), d.s("retryText"))
                    val t = open() ?: TurnB("t${e.seq}", null, e.time).also { tb ->
                        turns += tb
                        err.retryText?.let { tb.user = UserMsg(e.seq, e.time, it, 0, 0) }
                    }
                    t.error = err
                    close(t, e.time, TurnStatus.Failed)
                }
                "app/stopped" -> open()?.let { close(it, e.time, TurnStatus.Stopped) }
                "app/notice" -> {
                    // The failure it follows is not final: the turn stays open until the continuation (or a stop).
                    val kind = d.s("kind")
                    if (kind == NETWORK_WAIT) turns.lastOrNull()?.takeIf { it.status == TurnStatus.Failed }?.let { it.end = null; it.status = TurnStatus.Running; it.error = null }
                    val t = current(e.time, e.seq)
                    t.blocks += Block.Notice("n${e.seq}", d.s("text") ?: "", kind)
                    if (kind in CONTINUATION_NOTICES) awaiting = t
                }
            }
        }
        if (!running) open()?.let { t -> close(t, events.lastOrNull()?.time ?: t.start, TurnStatus.Stopped) }
        return Conversation(turns.map { it.build() }, todos, context, total)
    }

    // ---- trajectory projection (port of dsh ui-trajectory, phone subset) -----------------------

    fun trajectory(events: List<LoggedEvent>, children: List<List<LoggedEvent>>, running: Boolean): Trajectory {
        val turns = mutableListOf<TrajTurnB>()
        val between = TrajTurnB(null, 0)
        fun cur(): TrajTurnB = turns.lastOrNull()?.takeIf { it.end == null } ?: between
        val stepStart = HashMap<Pair<Int, Int>, Long>()
        val toolIndex = HashMap<String, Int>() // callId -> record index in its turn
        val toolTurn = HashMap<String, TrajTurnB>()
        var lastTime = events.firstOrNull()?.time ?: 0L
        var cumulative = Usage()
        for (e in events) {
            val d = e.data
            when (e.type) {
                "turn/start" -> turns += TrajTurnB(d.i("turn"), e.time)
                "turn/end" -> cur().let { t ->
                    if (t !== between && t.number != null && d.i("turn") != null && d.i("turn") != t.number) return@let
                    if (t !== between) t.end = e.time
                    val kind = d.o("reason")?.s("kind")
                    if (kind == "error") t.records += TrajRecord("e${e.seq}", RecordKind.Error, e.seq, d.o("reason")?.o("error")?.s("message") ?: "error",
                        e.time, e.time, null, RecordStatus.Failed, null, null, d.toString(), null, d)
                }
                "step/start" -> stepStart[(d.i("turn") ?: 0) to (d.i("step") ?: 0)] = e.time
                "user/message" -> {
                    val user = d.o("source")?.s("kind") == "user"
                    val text = textOf(d["content"])
                    cur().records += TrajRecord("u${e.seq}", if (user) RecordKind.User else RecordKind.Context, e.seq,
                        text.ifBlank { d.o("source")?.s("kind") ?: "" }, e.time, e.time, null, RecordStatus.Ok, null, null, text, null, d,
                        step = null, source = d.o("source")?.s("kind"))
                }
                "assistant/message" -> {
                    val content = d.o("message")?.get("content")
                    val start = stepStart[(d.i("turn") ?: 0) to (d.i("step") ?: 0)] ?: lastTime
                    val usage = Usage.of(d.o("usage"))
                    usage?.let { cumulative += it }
                    val text = textOf(content)
                    val calls = (content as? JsonArray)?.mapNotNull { (it as? JsonObject)?.takeIf { o -> o.s("type") == "tool-call" }?.s("name") } ?: emptyList()
                    cur().records += TrajRecord("a${e.seq}", RecordKind.Assistant, e.seq, text.ifBlank { calls.joinToString(", ") }, start, e.time, usage,
                        if (d.b("interrupted") == true) RecordStatus.Failed else RecordStatus.Ok, null, null, text, reasoningOf(content).ifBlank { null }, d,
                        step = d.i("step"), tools = calls, firstToken = d.l("firstTokenTime"), cumulative = cumulative)
                }
                "tool/call" -> {
                    val callId = d.s("callId") ?: "c${e.seq}"
                    val t = cur()
                    toolIndex[callId] = t.records.size; toolTurn[callId] = t
                    t.records += TrajRecord("k$callId", RecordKind.Tool, e.seq, d.s("name") ?: "?", e.time, null, null, RecordStatus.Pending, callId,
                        d.s("arguments"), null, null, d, step = d.i("step"), toolName = d.s("name"))
                }
                "tool/result" -> {
                    if (e.isReplacement()) continue
                    val msg = d.o("message")
                    val callId = msg?.s("toolCallId") ?: continue
                    val t = toolTurn[callId] ?: continue
                    val idx = toolIndex[callId] ?: continue
                    val failed = msg.b("isError") == true || d.o("error") != null
                    t.records[idx] = t.records[idx].copy(end = e.time, status = if (failed) RecordStatus.Failed else RecordStatus.Ok,
                        output = textOf(msg["content"]).ifBlank { d.o("error")?.s("reason") ?: "" }, resultRaw = d)
                }
                "compaction/start" -> cur().records += TrajRecord("m${d.s("compactionId") ?: e.seq}", RecordKind.Compaction, e.seq, "", e.time, null, null,
                    RecordStatus.Pending, null, null, null, null, d)
                "compaction/summary", "compaction/end" -> {
                    val id = "m${d.s("compactionId")}"
                    (turns + between).forEach { t -> t.records.replaceAll { r ->
                        if (r.id != id) r
                        else if (e.type == "compaction/summary") r.copy(title = "${d.a("shadowedSeqs")?.size ?: 0}", output = textOf(d["summary"]),
                            usage = Usage.of(d.o("usage")), resultRaw = d)
                        else r.copy(end = e.time, status = if (d.s("error") != null) RecordStatus.Failed else RecordStatus.Ok)
                    } }
                }
                "app/error" -> cur().records += TrajRecord("e${e.seq}", RecordKind.Error, e.seq, d.s("message") ?: "", e.time, e.time, null,
                    RecordStatus.Failed, null, null, d.s("message"), null, d)
            }
            lastTime = e.time
        }
        // Subagent tool calls nest under the turn that was open when they ran.
        val allTurns = (listOf(between) + turns)
        for (child in children) {
            val childCalls = HashMap<String, TrajRecord>()
            for (e in child) {
                val d = e.data
                if (e.type == "tool/call") {
                    val callId = d.s("callId") ?: continue
                    childCalls[callId] = TrajRecord("s$callId", RecordKind.Subtool, e.seq, d.s("name") ?: "?", e.time, null, null, RecordStatus.Pending,
                        callId, d.s("arguments"), null, null, d, toolName = d.s("name"))
                } else if (e.type == "tool/result" && !e.isReplacement()) {
                    val msg = d.o("message") ?: continue
                    val id = msg.s("toolCallId") ?: continue
                    childCalls[id] = childCalls[id]?.copy(end = e.time, output = textOf(msg["content"]),
                        status = if (msg.b("isError") == true) RecordStatus.Failed else RecordStatus.Ok, resultRaw = d) ?: continue
                }
            }
            childCalls.values.forEach { r ->
                val t = allTurns.lastOrNull { it.start <= r.start } ?: between
                t.records += r
            }
        }
        val built = allTurns.filter { it.records.isNotEmpty() }.map { t ->
            val recs = t.records.sortedWith(compareBy({ it.start }, { it.seq }))
            TrajTurn(if (t.number == null) "between" else "turn${t.number}_${t.start}", t.number, recs, t.start,
                t.end ?: recs.maxOfOrNull { it.end ?: it.start }, recs.mapNotNull { it.usage }.fold(Usage()) { a, b -> a + b })
        }.sortedBy { it.start }
        val start = events.firstOrNull()?.time ?: 0L
        val end = if (running) System.currentTimeMillis() else (events.lastOrNull()?.time ?: start)
        return Trajectory(built, start, end, cumulative)
    }

    private class TrajTurnB(val number: Int?, val start: Long) {
        val records = mutableListOf<TrajRecord>()
        var end: Long? = null
    }
}

enum class RecordKind { User, Context, Assistant, Tool, Subtool, Compaction, Error }
enum class RecordStatus { Ok, Failed, Pending }

data class TrajRecord(
    val id: String,
    val kind: RecordKind,
    val seq: Long,
    val title: String,
    val start: Long,
    val end: Long?,
    val usage: Usage?,
    val status: RecordStatus,
    val callId: String?,
    val input: String?,
    val output: String?,
    val reasoning: String?,
    val raw: JsonObject,
    val resultRaw: JsonObject? = null,
    val step: Int? = null,
    val tools: List<String> = emptyList(),
    val toolName: String? = null,
    val source: String? = null,
    val firstToken: Long? = null,
    val cumulative: Usage? = null,
) {
    val duration: Long? get() = end?.let { it - start }
}

data class TrajTurn(val key: String, val number: Int?, val records: List<TrajRecord>, val start: Long, val end: Long?, val usage: Usage)

data class Trajectory(val turns: List<TrajTurn>, val start: Long, val end: Long, val usage: Usage) {
    val records: List<TrajRecord> get() = turns.flatMap { it.records }
}

/**
 * Live output rate of the running turn, in estimated tokens per second, for the composer chip.
 *
 * dsh only reports exact counts when a model call settles, so this estimates from the streamed text
 * (≈3.5 characters per token in mixed Latin/Cyrillic text) over the time the turn has been running.
 * Null while idle or too early to be meaningful.
 */
fun Conversation.liveTps(now: Long): Double? {
    val turn = turns.lastOrNull()?.takeIf { it.status == TurnStatus.Running } ?: return null
    val seconds = (now - turn.startTime) / 1000.0
    if (seconds < 2.0) return null
    var chars = 0
    for (block in turn.blocks) if (block is Block.Text) chars += block.text.length
    if (chars < 12) return null
    return chars / 3.5 / seconds
}
