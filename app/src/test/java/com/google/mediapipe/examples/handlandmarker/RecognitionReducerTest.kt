package com.google.mediapipe.examples.handlandmarker

import com.google.mediapipe.examples.handlandmarker.RecognitionReducer.LINGER_MS
import com.google.mediapipe.examples.handlandmarker.RecognitionReducer.MIN_HOLD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionReducerTest {

    private fun reduce(state: RecognitionUiState, reading: FrameReading?, nowMs: Long) =
        RecognitionReducer.reduce(state, reading, nowMs)

    private val showingA = reduce(RecognitionUiState(), FrameReading.Confirmed("A", .97f), 1_000)

    @Test
    fun confirmedSignIsShownAsCurrent() {
        assertEquals(DisplaySign("A", SignKind.LETTER), showingA.sign)
        assertTrue(showingA.isSignCurrent)
        assertTrue(showingA.isHandVisible)
        assertEquals(.97f, showingA.confidence, 0f)
    }

    @Test
    fun settlingKeepsTheSignAndShowsLiveConfidence() {
        val state = reduce(showingA, FrameReading.Settling(.4f), 1_050)
        assertEquals("A", state.sign?.text)
        assertTrue(state.isSignCurrent)
        assertEquals(.4f, state.confidence, 0f)
    }

    @Test
    fun rejectionDimsTheSignAndEmptiesConfidence() {
        val state = reduce(showingA, FrameReading.Rejected, 1_050)
        assertEquals("A", state.sign?.text)
        assertFalse(state.isSignCurrent)
        assertEquals(0f, state.confidence, 0f)
    }

    @Test
    fun newSignWaitsForTheMinimumHold() {
        val tooSoon = reduce(showingA, FrameReading.Confirmed("B", .9f), 1_000 + MIN_HOLD_MS - 1)
        assertEquals("A", tooSoon.sign?.text)

        val later = reduce(tooSoon, FrameReading.Confirmed("B", .9f), 1_000 + MIN_HOLD_MS)
        assertEquals("B", later.sign?.text)
        assertEquals(1_000 + MIN_HOLD_MS, later.signShownAtMs)
    }

    @Test
    fun dimmedSignIsReplacedImmediately() {
        val dimmed = reduce(showingA, FrameReading.Rejected, 1_010)
        val state = reduce(dimmed, FrameReading.Confirmed("B", .9f), 1_020)
        assertEquals("B", state.sign?.text)
        assertTrue(state.isSignCurrent)
    }

    @Test
    fun reconfirmingTheSameSignKeepsItsShownTime() {
        val state = reduce(showingA, FrameReading.Confirmed("A", .95f), 1_400)
        assertEquals(1_000, state.signShownAtMs)
    }

    @Test
    fun signLingersAfterTheHandLeavesThenClears() {
        val justLeft = reduce(showingA, null, 1_000 + LINGER_MS - 1)
        assertFalse(justLeft.isHandVisible)
        assertFalse(justLeft.isSignCurrent)
        assertEquals("A", justLeft.sign?.text)
        assertEquals(0f, justLeft.confidence, 0f)

        assertNull(reduce(justLeft, null, 1_000 + LINGER_MS).sign)
    }

    @Test
    fun confidenceIsShownAsAWholePercentageBelowCertainty() {
        assertEquals(97, RecognitionUiState(confidence = .9712f).confidencePercent)
        assertEquals(0, RecognitionUiState(confidence = 0f).confidencePercent)
        assertEquals(99, RecognitionUiState(confidence = .9999f).confidencePercent)
        assertEquals(99, RecognitionUiState(confidence = 1.2f).confidencePercent)
        assertEquals(0, RecognitionUiState(confidence = -.1f).confidencePercent)
    }

    @Test
    fun displayMapsModelLabelsForVisitors() {
        assertEquals(DisplaySign("Ñ", SignKind.LETTER), DisplaySign.of("NN"))
        assertEquals(DisplaySign("10", SignKind.NUMBER), DisplaySign.of("10"))
        assertEquals(DisplaySign("I", SignKind.LETTER), DisplaySign.of("I"))
        assertEquals(DisplaySign("1", SignKind.NUMBER), DisplaySign.of("1"))
    }
}
