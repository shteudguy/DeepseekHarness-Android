package io.github.jackwener.dsh.feature.conversation

import androidx.compose.runtime.compositionLocalOf

/**
 * Machine translation of reasoning rows: [values] holds translated text keyed by block key, [request]
 * asks the engine's cot-translation plugin for one. Provided where the chat is composed, so the rows
 * themselves do not have to thread a callback through every layer.
 */
class ReasoningTranslationState(
    val values: Map<String, String> = emptyMap(),
    val request: (key: String, text: String) -> Unit = { _, _ -> },
)

val LocalReasoningTranslation = compositionLocalOf { ReasoningTranslationState() }
