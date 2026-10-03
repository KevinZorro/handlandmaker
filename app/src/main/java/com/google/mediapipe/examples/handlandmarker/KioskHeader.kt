package com.google.mediapipe.examples.handlandmarker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Top pill: the app name and a one-line description, so passers-by know what the screen does. */
@Composable
fun KioskHeader(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(KioskColors.Glass)
            .border(1.dp, KioskColors.GlassEdge, shape)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Reconocimiento de lengua de señas", color = KioskColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
