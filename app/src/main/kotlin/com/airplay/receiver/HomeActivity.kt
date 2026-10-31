package com.airplay.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.airplay.receiver.service.AirPlayService
import com.airplay.receiver.ui.AirPlayTheme
import com.airplay.receiver.ui.HomeScreen
import com.airplay.receiver.ui.SettingsModel
import com.airplay.receiver.ui.SettingsScreen
import java.net.Inet4Address
import java.net.NetworkInterface

/** Launcher + status/settings UI. The receiver runs in the service. */
class HomeActivity : ComponentActivity() {

    /**
     * Whether Beam may display over other apps. Without it Android 14 blocks the
     * background service from opening the mirror screen, so a connected sender
     * would stream to nothing. Re-checked on every resume (the user grants it in
     * system settings and comes back).
     */
    private var canOpenOverOtherApps by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        val prefs = Prefs(this)

        setContent {
            AirPlayTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val status by ReceiverState.status.collectAsState()

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        status = status,
                        needsOverlayPermission = !canOpenOverOtherApps,
                        onAllowOverlay = ::openOverlaySettings,
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> {
                        // Built once per visit: localIp() walks every network
                        // interface and must not run on each recomposition.
                        val model = remember {
                            SettingsModel(
                                deviceName = prefs.deviceName,
                                autoStart = prefs.autoStart,
                                requirePin = prefs.requirePin,
                                accurateColors = prefs.accurateColors,
                                ip = localIp(),
                                version = versionName(),
                            )
                        }
                        SettingsScreen(
                            model = model,
                            onNameChange = { prefs.deviceName = it },
                            onAutoStart = { prefs.autoStart = it },
                            onRequirePin = { prefs.requirePin = it },
                            onAccurateColors = { prefs.accurateColors = it },
                            onBack = { screen = Screen.HOME },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canOpenOverOtherApps = Settings.canDrawOverlays(this)
    }

    /** Opens the system "Display over other apps" toggle for Beam. */
    private fun openOverlaySettings() {
        val pkg = Uri.parse("package:$packageName")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private enum class Screen { HOME, SETTINGS }

    companion object {
        /** First non-loopback IPv4, for display. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }
}
