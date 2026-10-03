package com.google.mediapipe.examples.handlandmarker

import kotlin.math.roundToInt

/** One frame of classifier output, reduced to what the kiosk needs to decide what to show. */
sealed interface FrameReading {
    /** The last `k` frames agree on a sign. */
    data class Confirmed(val label: String, val confidence: Float) : FrameReading

    /** Frames disagree. [confidence] belongs to the top sign candidate, or is 0 when the top class is the rejection. */
    data class Settling(val confidence: Float) : FrameReading

    /** The last `k` frames were rejected. */
    data object Rejected : FrameReading
}

enum class SignKind(val caption: String) { LETTER("LETRA"), NUMBER("NÚMERO") }

/** [sameAsNumber] is the digit signed with the same handshape as this letter, if any. */
data class DisplaySign(val text: String, val kind: SignKind, val sameAsNumber: String? = null) {
    val caption: String
        get() = sameAsNumber?.let { "${kind.caption} · ${SignKind.NUMBER.caption} $it" } ?: kind.caption

    companion object {
        // The dataset spells Ñ as "NN" to avoid encoding issues; visitors should see the real letter.
        private val DISPLAY_TEXT = mapOf("NN" to "Ñ")

        // In LSC, 2 and 3 share the handshape of V and W, so the model has one class for each pair.
        private val SAME_HANDSHAPE_NUMBER = mapOf("V" to "2", "W" to "3")

        fun of(label: String) = DisplaySign(
            text = DISPLAY_TEXT[label] ?: label,
            kind = if (label.isNotEmpty() && label.all(Char::isDigit)) SignKind.NUMBER else SignKind.LETTER,
            sameAsNumber = SAME_HANDSHAPE_NUMBER[label],
        )
    }
}

/**
 * What the kiosk shows. The confirmed sign stays on screen until another one replaces it, so the
 * result never flickers through the classifier's intermediate states.
 */
data class RecognitionUiState(
    val isHandVisible: Boolean = false,
    val sign: DisplaySign? = null,
    /** False once the hand stops showing [sign]: it is still readable, just dimmed. */
    val isSignCurrent: Boolean = false,
    val confidence: Float = 0f,
    val signShownAtMs: Long = 0L,
    val lastHandAtMs: Long = 0L,
) {
    /** [confidence] as a whole percentage, capped at 99: a softmax score is never certainty. */
    val confidencePercent: Int
        get() = (confidence.coerceIn(0f, 1f) * 100).roundToInt().coerceAtMost(MAX_SHOWN_PERCENT)

    private companion object {
        const val MAX_SHOWN_PERCENT = 99
    }
}

object RecognitionReducer {
    /** After the hand leaves, the last sign stays this long so the people in line can read it. */
    const val LINGER_MS = 1_500L

    /** A new sign replaces the current one only after this long, so near ties do not flicker. */
    const val MIN_HOLD_MS = 250L

    fun reduce(state: RecognitionUiState, reading: FrameReading?, nowMs: Long): RecognitionUiState {
        if (reading == null) return withoutHand(state, nowMs)
        val seen = state.copy(isHandVisible = true, lastHandAtMs = nowMs)
        return when (reading) {
            is FrameReading.Confirmed -> confirm(seen, reading, nowMs)
            is FrameReading.Settling -> seen.copy(confidence = reading.confidence)
            FrameReading.Rejected -> seen.copy(confidence = 0f, isSignCurrent = false)
        }
    }

    private fun confirm(state: RecognitionUiState, reading: FrameReading.Confirmed, nowMs: Long): RecognitionUiState {
        val sign = DisplaySign.of(reading.label)
        val withConfidence = state.copy(confidence = reading.confidence)
        if (sign == state.sign) {
            val shownAt = if (state.isSignCurrent) state.signShownAtMs else nowMs
            return withConfidence.copy(isSignCurrent = true, signShownAtMs = shownAt)
        }
        val canReplace = state.sign == null || !state.isSignCurrent || nowMs - state.signShownAtMs >= MIN_HOLD_MS
        return if (canReplace) {
            withConfidence.copy(sign = sign, isSignCurrent = true, signShownAtMs = nowMs)
        } else {
            withConfidence
        }
    }

    private fun withoutHand(state: RecognitionUiState, nowMs: Long): RecognitionUiState {
        val expired = nowMs - state.lastHandAtMs >= LINGER_MS
        return state.copy(
            isHandVisible = false,
            confidence = 0f,
            isSignCurrent = false,
            sign = if (expired) null else state.sign,
        )
    }
}
