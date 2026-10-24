package com.airplay.receiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var name by remember { mutableStateOf(model.deviceName) }
    var auto by remember { mutableStateOf(model.autoStart) }
    var pin by remember { mutableStateOf(model.requirePin) }
    var editingName by remember { mutableStateOf(false) }

    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    // While editing, Back closes the editor; otherwise Back leaves Settings.
    BackHandler(enabled = editingName) { editingName = false }
    BackHandler(enabled = !editingName) { onBack() }

    ScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .padding(horizontal = 96.dp, vertical = 56.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Settings", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Brand.Text, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Set up how this TV appears and behaves on your network",
                fontSize = 17.sp,
                color = Brand.TextDim,
            )
            Spacer(Modifier.height(40.dp))

            SectionHeader("Identity")
            ActionRow(
                title = "Device name",
                subtitle = "Shown in the AirPlay menu on your Apple devices",
                value = name,
                onClick = { editingName = true },
                modifier = Modifier.focusRequester(firstRow),
            )

            Spacer(Modifier.height(36.dp))
            SectionHeader("Behavior")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow(
                    title = "Start at boot",
                    subtitle = "Be discoverable as soon as the TV turns on",
                    checked = auto,
                    onCheckedChange = { auto = it; onAutoStart(it) },
                )
                ToggleRow(
                    title = "Require PIN",
                    subtitle = "Ask for an on-screen code before a device can connect",
                    checked = pin,
                    onCheckedChange = { pin = it; onRequirePin(it) },
                )
            }

            Spacer(Modifier.height(36.dp))
            SectionHeader("About")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("Status", model.ip?.let { "On network · $it" } ?: "Starting…")
                InfoRow("Video", "HEVC up to 4K60 · H.264 up to 1080p60")
                InfoRow("Audio", "ALAC · AAC-LC · AAC-ELD")
                InfoRow("Version", model.version)
            }

            Spacer(Modifier.height(44.dp))
            PillButton(label = "Done", leading = "‹", onClick = onBack)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingName) {
        NameEditor(
            initial = name,
            onCommit = { entered ->
                val committed = entered.trim().ifBlank { name }
                name = committed
                onNameChange(committed)
                editingName = false
            },
        )
    }
}

/**
 * A focused, modal name editor. Because the text field only exists while this
 * overlay is open — and grabs focus only here — it never interferes with the
 * row-to-row D-pad traversal on the Settings list behind it.
 */
@Composable
private fun NameEditor(initial: String, onCommit: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .clip(Shapes.Card)
                .background(Brand.Surface)
                .padding(44.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("Device name", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "This is how the TV appears when you pick it from Screen Mirroring.",
                fontSize = 16.sp,
                color = Brand.TextDim,
            )
            Spacer(Modifier.height(26.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brand.Bg)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(32) },
                    singleLine = true,
                    textStyle = TextStyle(color = Brand.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(Brand.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit(draft) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
            }

            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(label = "Save", onClick = { onCommit(draft) }, accent = true)
                Text(
                    "Press BACK to cancel",
                    fontSize = 14.sp,
                    color = Brand.TextMuted,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}
