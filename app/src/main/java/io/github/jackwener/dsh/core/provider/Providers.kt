package io.github.jackwener.dsh.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Third-party model providers (OpenAI-compatible Chat Completions endpoints), served in the engine
 * by the vendored `@snnh/dsh-llm-service-lite` row. See docs/ENGINE-CONTRACT.md §1.1.
 *
 * Presets: base URLs follow magpie's provider table (github.com/yetone/magpie,
 * internal/provider/presets.go, MIT), restricted to each vendor's OpenAI-compatible endpoint.
 * Default model ids are only a starting point; "获取模型列表" asks the endpoint's `/models`.
 * Adding a preset is one line.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    /** The custom preset leaves the key optional (e.g. a self-hosted gateway without auth). */
    val keyOptional: Boolean = false,
    /** The user edits the address (custom endpoint; HTTPS only). */
    val editableUrl: Boolean = false,
    val keysUrl: String? = null,
)

val PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-5"), keysUrl = "https://platform.openai.com/api-keys"),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", listOf("claude-sonnet-5", "claude-opus-5"), keysUrl = "https://console.anthropic.com/settings/keys"),
    ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-3.5-flash"), keysUrl = "https://aistudio.google.com/apikey"),
    ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", listOf("grok-4"), keysUrl = "https://console.x.ai"),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("openrouter/auto"), keysUrl = "https://openrouter.ai/keys"),
    ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", listOf("kimi-k3"), keysUrl = "https://platform.moonshot.cn/console/api-keys"),
    ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-5.3"), keysUrl = "https://open.bigmodel.cn/usercenter/apikeys"),
    ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen3.8-max"), keysUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key"),
    ProviderPreset("ark", "火山方舟 / 豆包", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-seed-2.1-pro"), keysUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey"),
    ProviderPreset("minimax", "MiniMax", "https://api.minimaxi.com/v1", listOf("MiniMax-M3.1"), keysUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key"),
    ProviderPreset("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", keysUrl = "https://cloud.siliconflow.cn/account/ak"),
    ProviderPreset("stepfun", "阶跃星辰 StepFun", "https://api.stepfun.com/v1", listOf("step-5"), keysUrl = "https://platform.stepfun.com/interface-key"),
    ProviderPreset(CUSTOM, "自定义（OpenAI 兼容）", "", keyOptional = true, editableUrl = true),
)

const val CUSTOM = "custom"

fun presetOf(id: String?): ProviderPreset? = PRESETS.firstOrNull { it.id == id }

/** One configured provider = one engine route (`id`), all its models on Chat Completions. */
@Serializable
data class ProviderConfig(
    /** Route id: the preset id, `-2`, `-3`… for repeats. Never a DeepSeek route name. */
    val id: String,
    val preset: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val enabled: Boolean = true,
    /** A key is stored for it in SecretStore (the value itself never leaves Keystore-encrypted storage except as env). */
    val hasKey: Boolean = false,
) {
    /** `apiKeyEnv` the engine resolves per request; set in the `:engine` environment only. */
    val keyEnv: String get() = keyEnvOf(id)
    val secretName: String get() = "provider-$id"
}

fun keyEnvOf(routeId: String) = "DSH_ANDROID_KEY_" + routeId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** A fresh route id for [preset] that no existing provider (or DeepSeek) owns. */
fun newRouteId(preset: String, existing: Collection<String>): String {
    val base = preset.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { CUSTOM }.let { if (it.startsWith("deepseek")) "x-$it" else it }
    if (base !in existing) return base
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in existing }
}

/** Fixed facts for every third-party model (no per-model matrices; see MAGPIE-RESEARCH / ARCHITECTURE). */
object ThirdParty {
    /** Advertised context window. Conservative: most current models have ≥128K. */
    const val CONTEXT_WINDOW = 131_072L
    /** Request output cap (`max_tokens`): vendors' own defaults are sometimes tiny (1–4K), truncating tool calls. */
    const val MAX_TOKENS = 16_384
    /**
     * Compaction headroom for these models. dsh's default (64K) on a 128K window would compact at
     * 128K − 16K − 64K = 48K; with 16K it compacts at ~96K (75 %), still well inside the window.
     */
    const val COMPACTION_HEADROOM = 16_384
}

/** What the composer / engine route resolves to. `provider == null` is DeepSeek. */
data class ModelChoice(val provider: ProviderConfig?, val model: String) {
    val isDeepSeek get() = provider == null
    val contextWindow get() = if (provider == null) DEEPSEEK_CONTEXT else ThirdParty.CONTEXT_WINDOW
    companion object { const val DEEPSEEK_CONTEXT = 1_000_000L }
}

val DEEPSEEK_MODELS = listOf("deepseek-flash" to "DeepSeek-V4.1-Flash", "deepseek-v4-pro" to "DeepSeek-V4-Pro")
const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"

/**
 * Resolve the saved selection: a third-party (provider, model) only while that provider exists, is
 * enabled and still lists the model; otherwise DeepSeek (the saved DeepSeek model, or flash).
 */
fun resolveChoice(provider: String, model: String, providers: List<ProviderConfig>): ModelChoice {
    if (provider.isNotEmpty()) {
        val p = providers.firstOrNull { it.id == provider && it.enabled }
        if (p != null && model in p.models) return ModelChoice(p, model)
    }
    return ModelChoice(null, model.takeIf { m -> DEEPSEEK_MODELS.any { it.first == m } } ?: DEFAULT_DEEPSEEK_MODEL)
}

/**
 * The user patch layer `$DSH_HOME/profiles/android/cordis.patch.yml` (dsh applies it after every
 * bundle). The app is its only writer and rewrites it before each engine start; it carries no
 * secret, only `apiKeyEnv` names. Values are JSON (a YAML subset), so no quoting rules apply.
 *
 * @param webSearch false turns off the `web_search` tool (it needs a DeepSeek credential: the
 *   API key, or the account login only while the conversation uses the account route).
 */
fun renderProfilePatch(providers: List<ProviderConfig>, webSearch: Boolean): String {
    val active = providers.filter { it.enabled && it.models.isNotEmpty() && it.baseUrl.isNotBlank() }
    val json = Json { prettyPrint = false }
    val sb = StringBuilder()
    sb.append("# Generated by DSH-Android (设置 → 模型服务). Rewritten before every engine start; edits here are lost.\n")
    if (active.isNotEmpty()) {
        val providersJson = buildJsonObject {
            putJsonObject("providers") {
                active.forEach { p ->
                    putJsonObject(p.id) {
                        put("displayName", p.name)
                        put("interfaceType", "openai-chat-completions")
                        put("baseURL", p.baseUrl.trim().trimEnd('/'))
                        if (p.hasKey) put("apiKeyEnv", p.keyEnv)
                        put("includeUsage", true)
                        putJsonArray("models") {
                            p.models.forEach { m ->
                                addJsonObject {
                                    put("id", m)
                                    put("contextWindow", ThirdParty.CONTEXT_WINDOW)
                                    putJsonObject("defaults") { put("maxTokens", ThirdParty.MAX_TOKENS) }
                                }
                            }
                        }
                    }
                }
            }
        }
        sb.append("- id: llm-service-lite\n  config: ").append(json.encodeToString(JsonObject.serializer(), providersJson)).append('\n')
        val compaction = buildJsonObject {
            putJsonArray("modelPolicies") {
                active.forEach { p -> p.models.forEach { m -> addJsonObject { put("provider", p.id); put("model", m); put("headroomTokens", ThirdParty.COMPACTION_HEADROOM) } } }
            }
        }
        sb.append("- id: compaction-basic\n  config: ").append(json.encodeToString(JsonObject.serializer(), compaction)).append('\n')
    }
    // dsh-base's tool-web config, with search off (a patch replaces the whole config).
    if (!webSearch) sb.append("- id: tool-web\n  config: {\"search\":false,\"fetch\":true,\"searchTimeoutMs\":60000}\n")
    // Reasoning translation: dsh-base does not ship these rows on android. The translator makes the
    // provider call (anonymous endpoints, no key); the cot-translation row exposes the
    // cotTranslation/translate remote method the app calls from a reasoning row.
    sb.append(
        "- insert:\n" +
            "    - id: translator\n" +
            "      name: '@deepseek-ai/dsh-experimental-translator'\n" +
            "    - id: cot-translation\n" +
            "      name: '@deepseek-ai/dsh-experimental-client-ui-cot-translation'\n" +
            "      config:\n" +
            "        provider: bing\n" +
            "        targetLanguage: auto\n"
    )

    return sb.toString()
}

/**
 * `GET {baseUrl}/models` (OpenAI shape `{data:[{id}]}`, or a bare array / `{models:[…]}`).
 * Doubles as the connection test. Runs on the caller's thread (call from Dispatchers.IO).
 * https, or http for a user's LAN gateway (network_security_config permits cleartext).
 */
fun fetchModels(baseUrl: String, key: String?): Result<List<String>> = runCatching {
    val url = URL(baseUrl.trim().trimEnd('/') + "/models")
    val c = url.openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        if (!key.isNullOrBlank()) {
            c.setRequestProperty("Authorization", "Bearer $key")
            // Anthropic's listing reads x-api-key (its OpenAI-compatible layer takes the bearer).
            if (url.host.endsWith("anthropic.com")) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString().take(4_000_000) }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(160).replace('\n', ' ')}".trim())
        parseModelIds(body)
    } finally { c.disconnect() }
}

fun parseModelIds(body: String): List<String> {
    val root = Json.parseToJsonElement(body)
    val arr: JsonArray = when {
        root is JsonArray -> root
        root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
        root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
        else -> error("unexpected /models response")
    }
    return arr.mapNotNull { e ->
        runCatching { (e as? JsonObject)?.let { o -> (o["id"] ?: o["name"] ?: o["model"])?.jsonPrimitive?.content } ?: e.jsonPrimitive.content }.getOrNull()
    }.map { it.removePrefix("models/") }.filter { it.isNotBlank() }.distinct().sorted()
}
