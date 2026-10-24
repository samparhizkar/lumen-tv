package com.airplay.receiver

import android.os.Bundle
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
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        model = SettingsModel(
                            deviceName = prefs.deviceName,
                            autoStart = prefs.autoStart,
                            requirePin = prefs.requirePin,
                            ip = localIp(),
                            version = BuildConfigName,
                        ),
                        onNameChange = { prefs.deviceName = it },
                        onAutoStart = { prefs.autoStart = it },
                        onRequirePin = { prefs.requirePin = it },
                        onBack = { screen = Screen.HOME },
                    )
                }
            }
        }
    }

    private enum class Screen { HOME, SETTINGS }

    companion object {
        const val BuildConfigName = "0.2.0"

        /** First non-loopback IPv4, for display. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }
}
