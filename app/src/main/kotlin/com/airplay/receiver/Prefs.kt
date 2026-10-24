package com.airplay.receiver

import android.content.Context
import android.provider.Settings

/** User-facing settings, SharedPreferences-backed. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("airplay", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var deviceName: String
        get() = sp.getString(KEY_NAME, null) ?: defaultName()
        set(v) = sp.edit().putString(KEY_NAME, v.trim().ifBlank { defaultName() }).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var requirePin: Boolean
        get() = sp.getBoolean(KEY_PIN, false)
        set(v) = sp.edit().putBoolean(KEY_PIN, v).apply()

    private fun defaultName(): String {
        val sys = Settings.Global.getString(appContext.contentResolver, "device_name")
        return if (sys.isNullOrBlank() || sys == "Android TV") "Homatics AirPlay" else "$sys (AirPlay)"
    }

    companion object {
        private const val KEY_NAME = "device_name"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_PIN = "require_pin"
    }
}
