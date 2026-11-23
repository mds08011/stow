package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timer a returning screen shows is rebuilt from the service's clock, not from
 * broadcasts it may have missed while the phone was locked.
 */
class LiveStateTest {

    @Test
    fun `recording counts the banked time plus the running segment`() {
        val millis = RecordingService.activeMillis(
            state = RecordingService.LIVE_RECORDING,
            accumulatedMillis = 60_000,
            segmentStartElapsed = 1_000_000,
            nowElapsed = 1_030_000
        )

        assertEquals(90_000, millis)
    }

    @Test
    fun `paused shows only the banked time, however long the pause`() {
        val millis = RecordingService.activeMillis(
            state = RecordingService.LIVE_PAUSED,
            accumulatedMillis = 60_000,
            segmentStartElapsed = 1_000_000,
            nowElapsed = 9_000_000
        )

        assertEquals(60_000, millis)
    }

    @Test
    fun `nothing is counted once the recording has stopped`() {
        for (state in listOf(RecordingService.LIVE_UPLOADING, RecordingService.LIVE_IDLE)) {
            assertEquals(0, RecordingService.activeMillis(state, 60_000, 1_000_000, 1_030_000))
        }
    }

    @Test
    fun `a clock that reads earlier than the segment start never goes negative`() {
        val millis = RecordingService.activeMillis(
            state = RecordingService.LIVE_RECORDING,
            accumulatedMillis = 5_000,
            segmentStartElapsed = 1_000_000,
            nowElapsed = 999_000
        )

        assertEquals(5_000, millis)
    }
}
