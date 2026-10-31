package com.airplay.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val NearBlack = listOf(0, 4, 8, 12, 16, 20, 24, 32, 40, 48)
private val NearWhite = listOf(200, 210, 220, 230, 235, 240, 245, 250, 255)

/**
 * Full-screen black/white level pattern drawn by the app itself, i.e. on the
 * TV's graphics plane rather than its video plane. On a correctly set up TV
 * every step is visible. Comparing it with the same pattern mirrored from a
 * Mac tells "the TV/HDMI chain clips" apart from "the video path clips".
 */
@Composable
fun PictureTest() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Caption("Near black: every box should be visible, only 0 is pure black")
        Steps(NearBlack, labelColor = Color(0xFF555555))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Brush.horizontalGradient(listOf(Color.Black, Color.White))),
        )
        Steps(NearWhite, labelColor = Color(0xFF333333))
        Caption("Near white: every box should differ, only 255 is pure white  ·  BACK to close")
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 16.sp, color = Color(0xFF8A8A8A))
}

@Composable
private fun Steps(values: List<Int>, labelColor: Color) {
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        for (v in values) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color(v, v, v)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text("$v", fontSize = 15.sp, color = labelColor, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
