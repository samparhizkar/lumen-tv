package com.airplay.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

class SettingsModel(
    var deviceName: String,
    var autoStart: Boolean,
    var requirePin: Boolean,
    val ip: String?,
    val version: String,
)

@Composable
fun SettingsScreen(
    model: SettingsModel,
    onNameChange: (String) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onRequirePin: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Bg)
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text)
        Spacer(Modifier.height(32.dp))

        Section("Identity")
        var name by remember { mutableStateOf(model.deviceName) }
        SettingRow("Device name", "Shown in the AirPlay menu") {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.BgElevated)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 220.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it; onNameChange(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = Brand.Text, fontSize = 18.sp),
                    cursorBrush = SolidColor(Brand.Accent),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Section("Behavior")
        var auto by remember { mutableStateOf(model.autoStart) }
        SettingRow("Start at boot", "Be discoverable as soon as the TV turns on") {
            Toggle(auto) { auto = !auto; onAutoStart(auto) }
        }
        var pin by remember { mutableStateOf(model.requirePin) }
        SettingRow("Require PIN", "Ask for an on-screen code before a device can connect") {
            Toggle(pin) { pin = !pin; onRequirePin(pin) }
        }

        Spacer(Modifier.height(28.dp))
        Section("About")
        InfoRow("Status", model.ip?.let { "On network · $it" } ?: "Starting…")
        InfoRow("Video", "HEVC up to 4K60 · H.264 up to 1080p60")
        InfoRow("Audio", "ALAC · AAC-LC · AAC-ELD")
        InfoRow("Version", model.version)

        Spacer(Modifier.height(36.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Brand.Accent)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, color = Brand.Text)
            Text(subtitle, fontSize = 14.sp, color = Brand.TextDim)
        }
        control()
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(title, fontSize = 17.sp, color = Brand.TextDim, modifier = Modifier.width(160.dp))
        Text(value, fontSize = 17.sp, color = Brand.Text)
    }
}

@Composable
private fun Toggle(on: Boolean, onToggle: () -> Unit) {
    Button(onClick = onToggle) { Text(if (on) "On" else "Off") }
}
