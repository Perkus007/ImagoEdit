package com.perkus.imagoedit.data

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import java.util.Locale

/** The system caches prefs: create an instance wherever needed */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) = prefs.edit { putBoolean("vibration", v) }

    var sounds: Boolean
        get() = prefs.getBoolean("sounds", true)
        set(v) = prefs.edit { putBoolean("sounds", v) }

    var language: String?
        get() = prefs.getString("language", null)
        set(v) = prefs.edit { putString("language", v) }

    /** Missing or renamed → [default] */
    fun <E : Enum<E>> enum(key: String, entries: List<E>, default: E = entries.first()): E = prefs.getString(key, null).let { n -> entries.firstOrNull { it.name == n } } ?: default
    fun setEnum(key: String, v: Enum<*>) = prefs.edit { putString(key, v.name) }

    fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setBool(key: String, v: Boolean) = prefs.edit { putBoolean(key, v) }

    fun int(key: String, default: Int) = prefs.getInt(key, default)
    fun setInt(key: String, v: Int) = prefs.edit { putInt(key, v) }

    fun float(key: String, default: Float) = prefs.getFloat(key, default)
    fun setFloat(key: String, v: Float) = prefs.edit { putFloat(key, v) }

    fun string(key: String): String? = prefs.getString(key, null)
    fun setString(key: String, v: String) = prefs.edit { putString(key, v) }
}

/** Each write persisted at once (apply is async) */
@Stable
class EnumOption<E : Enum<E>>(private val settings: AppSettings, private val key: String, val entries: List<E>, default: E = entries.first()) {
    private val state = mutableStateOf(settings.enum(key, entries, default))
    var value: E
        get() = state.value
        set(v) { state.value = v; settings.setEnum(key, v) }
}

@Stable
class BoolOption(private val settings: AppSettings, private val key: String, default: Boolean) {
    private val state = mutableStateOf(settings.bool(key, default))
    var value: Boolean
        get() = state.value
        set(v) { state.value = v; settings.setBool(key, v) }
}

@Stable
class IntOption(private val settings: AppSettings, private val key: String, val range: IntRange, default: Int) {
    private val state = mutableIntStateOf(settings.int(key, default).coerceIn(range))
    var value: Int
        get() = state.intValue
        set(v) { state.intValue = v.coerceIn(range); settings.setInt(key, state.intValue) }
}

@Stable
class FloatOption(private val settings: AppSettings, private val key: String, val range: ClosedFloatingPointRange<Float>, default: Float) {
    private val state = mutableFloatStateOf(settings.float(key, default).coerceIn(range))
    var value: Float
        get() = state.floatValue
        set(v) { state.floatValue = v.coerceIn(range); settings.setFloat(key, state.floatValue) }
}

/** Locale.setDefault keeps Compose text and formatting in the same language as resources */
fun Context.withAppLanguage(): Context {
    val locale = Locale.forLanguageTag(AppSettings(this).language ?: return this)
    Locale.setDefault(locale)
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
