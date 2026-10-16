package com.gameagent.app.data

import android.content.Context
import androidx.core.content.edit
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppSettings] in a private SharedPreferences file.
 *
 * No secret is stored here - the AI key lives in [SecureKeyStore], encrypted with an Android
 * Keystore key - and the file is excluded from backup by `android:allowBackup="false"`.
 */
class SettingsRepository(
    context: Context,
    private val events: DiagnosticEventSink,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutableSettings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    private fun load(): AppSettings = AppSettingsCodec.decode { key -> preferences.all[key] }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(mutableSettings.value).sanitised()
        persist(next)
        mutableSettings.value = next
        events.debug(EventCategory.SYSTEM, "settings updated")
        return next
    }

    fun replace(next: AppSettings): AppSettings = update { next }

    private fun persist(settings: AppSettings) {
        preferences.edit {
            AppSettingsCodec.encode(settings).forEach { (key, value) ->
                when (value) {
                    is Long -> putLong(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }
    }

    companion object {
        const val PREFS_NAME = "gameagent.settings"
    }
}
