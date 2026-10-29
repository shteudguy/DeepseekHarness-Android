package io.github.jackwener.dsh.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.provider.ModelChoice
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.resolveChoice
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("settings")

/** The agent's own virtual display needs Shizuku and is experimental: off until asked for. */
private val DEFAULT_DISABLED = setOf(ToolGroup.VScreen)

enum class ThemeMode { System, Light, Dark }

/** dsh "工作步骤展示": how much process detail completed turns keep open. */
enum class WorkDetails { Compact, Standard, Detailed }

/** dsh "繁忙时的发送行为". */
enum class BusySend { Queue, Steer }

/** Reasoning effort ids accepted by the DeepSeek adapter (`packages/llm/llm-deepseek/src/model-info.ts`). */
enum class Effort(val id: String, val label: String) {
    Off("off", "Off"), Low("low", "Low"), High("high", "High"), Max("max", "Max");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: High }
}

data class Settings(
    /** Selected route: "" = DeepSeek (key or account), else a third-party provider's route id ([providers]). */
    val provider: String = "",
    val model: String = DEFAULT_MODEL,
    val effort: Effort = Effort.High,
    /** Device tool groups the user switched off (everything else is offered to the agent). */
    val disabledGroups: Set<ToolGroup> = emptySet(),
    /** Apps whose notifications DSH must not read (default: none — every app is readable). */
    val notificationBlocked: Set<String> = emptySet(),
    val floatingBall: Boolean = false,
    val dailyTokenLimit: Long = 0,
    val sessionTokenLimit: Long = 0,
    val theme: ThemeMode = ThemeMode.System,
    val workDetails: WorkDetails = WorkDetails.Standard,
    val busySend: BusySend = BusySend.Steer,
    val defaultPreset: PermissionPreset = PermissionPreset.Full,
    /** 允许其他 App 调用: whether the exported RUN_TASK entry accepts tasks from other apps. Off by default. */
    val allowExternalApps: Boolean = false,
    /** Third-party OpenAI-compatible providers (keys live in SecretStore, never here). */
    val providers: List<ProviderConfig> = emptyList(),
) {
    val enabledGroups: Set<ToolGroup> get() = ToolGroup.entries.toSet() - disabledGroups
    /** The effective selection (falls back to DeepSeek when the provider/model is gone or disabled). */
    val choice: ModelChoice get() = resolveChoice(provider, model, providers)

    companion object { const val DEFAULT_MODEL = "deepseek-flash" }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val providerList = ListSerializer(ProviderConfig.serializer())

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val provider = stringPreferencesKey("provider")
        val providers = stringPreferencesKey("providers_json")
        val model = stringPreferencesKey("model")
        val effort = stringPreferencesKey("effort")
        val disabledGroups = stringSetPreferencesKey("disabled_groups")
        val notifBlocked = stringSetPreferencesKey("notif_blocked")
        val ball = booleanPreferencesKey("floating_ball")
        val dailyTokens = longPreferencesKey("daily_tokens")
        val sessionTokens = longPreferencesKey("session_tokens")
        val theme = stringPreferencesKey("theme")
        val workDetails = stringPreferencesKey("work_details")
        val busySend = stringPreferencesKey("busy_send")
        val preset = stringPreferencesKey("default_preset")
        val externalApps = booleanPreferencesKey("allow_external_apps")
    }

    val settings: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        provider = this[K.provider] ?: "",
        providers = this[K.providers]?.let { runCatching { json.decodeFromString(providerList, it) }.getOrNull() } ?: emptyList(),
        model = this[K.model] ?: Settings.DEFAULT_MODEL,
        effort = Effort.of(this[K.effort]),
        disabledGroups = this[K.disabledGroups]?.mapNotNull(ToolGroup::of)?.toSet() ?: DEFAULT_DISABLED,
        notificationBlocked = this[K.notifBlocked] ?: emptySet(),
        floatingBall = this[K.ball] ?: false,
        dailyTokenLimit = this[K.dailyTokens] ?: 0,
        sessionTokenLimit = this[K.sessionTokens] ?: 0,
        theme = this[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.System,
        workDetails = this[K.workDetails]?.let { v -> WorkDetails.entries.firstOrNull { it.name == v } } ?: WorkDetails.Standard,
        busySend = this[K.busySend]?.let { v -> BusySend.entries.firstOrNull { it.name == v } } ?: BusySend.Queue,
        defaultPreset = PermissionPreset.of(this[K.preset]),
        allowExternalApps = this[K.externalApps] ?: false,
    )

    /** [provider] "" = DeepSeek. */
    suspend fun setModel(provider: String, model: String) = context.store.edit { it[K.provider] = provider; it[K.model] = model }
    suspend fun saveProvider(p: ProviderConfig) = context.store.edit {
        val cur = it[K.providers]?.let { s -> runCatching { json.decodeFromString(providerList, s) }.getOrNull() } ?: emptyList()
        val next = if (cur.any { c -> c.id == p.id }) cur.map { c -> if (c.id == p.id) p else c } else cur + p
        it[K.providers] = json.encodeToString(providerList, next)
    }
    suspend fun deleteProvider(id: String) = context.store.edit {
        val cur = it[K.providers]?.let { s -> runCatching { json.decodeFromString(providerList, s) }.getOrNull() } ?: emptyList()
        it[K.providers] = json.encodeToString(providerList, cur.filter { c -> c.id != id })
        if (it[K.provider] == id) { it[K.provider] = ""; it[K.model] = Settings.DEFAULT_MODEL }
    }
    suspend fun setEffort(effort: Effort) = context.store.edit { it[K.effort] = effort.id }
    suspend fun setGroupEnabled(group: ToolGroup, enabled: Boolean) = context.store.edit {
        val cur = it[K.disabledGroups] ?: DEFAULT_DISABLED.map { g -> g.id }.toSet()
        it[K.disabledGroups] = if (enabled) cur - group.id else cur + group.id
    }
    suspend fun setNotificationBlocked(apps: Set<String>) = context.store.edit { it[K.notifBlocked] = apps }
    suspend fun setFloatingBall(on: Boolean) = context.store.edit { it[K.ball] = on }
    suspend fun setLimits(daily: Long, session: Long) = context.store.edit {
        it[K.dailyTokens] = daily; it[K.sessionTokens] = session
    }
    suspend fun setTheme(mode: ThemeMode) = context.store.edit { it[K.theme] = mode.name }
    suspend fun setWorkDetails(v: WorkDetails) = context.store.edit { it[K.workDetails] = v.name }
    suspend fun setBusySend(v: BusySend) = context.store.edit { it[K.busySend] = v.name }
    suspend fun setDefaultPreset(p: PermissionPreset) = context.store.edit { it[K.preset] = p.id }
    suspend fun setAllowExternalApps(on: Boolean) = context.store.edit { it[K.externalApps] = on }
}
