package com.google.mediapipe.examples.handlandmarker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Kiosk palette: a dark translucent material over the live camera, in the style of iOS and HyperOS. */
object KioskColors {
    val Glass = Color(0xC71C1C1E)
    val GlassEdge = Color(0x24FFFFFF)
    val TextSecondary = Color(0x99FFFFFF)
    val Track = Color(0x2EFFFFFF)
    val Confirmed = Color(0xFF30D158)
    val Shadow = Color(0x47000000)
    val JointGlow = Color(0x47FFFFFF)
}

private val GLYPH_SLOT = 140.dp
private val GLYPH_SIZE = 128.sp
private val TWO_CHAR_GLYPH_SIZE = 96.sp   // "10" must fit the same slot
private const val DIMMED_ALPHA = .38f

/** Bottom card: the recognized sign, a short status line and the live confidence bar. */
@Composable
fun ResultCard(state: RecognitionUiState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KioskColors.Glass)
            .border(1.dp, KioskColors.GlassEdge, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SignGlyph(state, Modifier.size(GLYPH_SLOT))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = caption(state),
                color = KioskColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                maxLines = 1,
            )
            Text(
                text = message(state),
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            ConfidenceBar(state.confidence, isConfirmed = state.isHandVisible && state.isSignCurrent)
        }
    }
}

private fun caption(state: RecognitionUiState): String = when {
    state.sign != null -> state.sign.kind.caption
    state.isHandVisible -> "LEYENDO TU MANO"
    else -> "LENGUA DE SEÑAS COLOMBIANA"
}

private fun message(state: RecognitionUiState): String = when {
    state.sign == null && state.isHandVisible -> "Forma una letra o un número"
    state.sign == null -> "Muestra tu mano y haz una letra o un número"
    state.isSignCurrent -> "Reconocida"
    state.isHandVisible -> "Sigue intentando"
    else -> "Última seña reconocida"
}

@Composable
private fun SignGlyph(state: RecognitionUiState, modifier: Modifier) {
    val alpha by animateFloatAsState(if (state.sign == null || state.isSignCurrent) 1f else DIMMED_ALPHA, tween(150), label = "glyphAlpha")
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = state.sign,
            transitionSpec = { (fadeIn(tween(120)) + scaleIn(tween(140), initialScale = .9f)) togetherWith fadeOut(tween(80)) },
            contentAlignment = Alignment.Center,
            label = "sign",
        ) { sign ->
            if (sign == null) {
                IdleMark(Modifier.size(GLYPH_SLOT * .5f))
            } else {
                Text(
                    text = sign.text,
                    color = Color.White.copy(alpha = alpha),
                    fontSize = if (sign.text.length > 1) TWO_CHAR_GLYPH_SIZE else GLYPH_SIZE,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Small version of the focus corners: "put your hand in the square". */
@Composable
private fun IdleMark(modifier: Modifier) {
    Canvas(modifier) {
        val arm = size.minDimension * .3f
        val stroke = 3.dp.toPx()
        val color = Color.White.copy(alpha = .55f)
        val w = size.width
        val h = size.height
        listOf(
            Offset(0f, arm) to Offset(0f, 0f), Offset(0f, 0f) to Offset(arm, 0f),
            Offset(w - arm, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, arm),
            Offset(w, h - arm) to Offset(w, h), Offset(w, h) to Offset(w - arm, h),
            Offset(arm, h) to Offset(0f, h), Offset(0f, h) to Offset(0f, h - arm),
        ).forEach { (from, to) -> drawLine(color, from, to, stroke, StrokeCap.Round) }
    }
}

@Composable
private fun ConfidenceBar(confidence: Float, isConfirmed: Boolean) {
    val fill by animateFloatAsState(confidence.coerceIn(0f, 1f), tween(90, easing = LinearEasing), label = "confidence")
    val color by animateColorAsState(if (isConfirmed) KioskColors.Confirmed else Color.White, tween(120), label = "confidenceColor")
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(KioskColors.Track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .clip(CircleShape)
                .background(color)
        )
    }
}
