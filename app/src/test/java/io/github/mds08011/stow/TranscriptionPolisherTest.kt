package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what the app does with a completion once it comes back: leaked transport markers
 * are removed, the length guard judges the real content, and the presentation wrapper is
 * applied last. The HTTP call itself is not exercised — this is the decision it feeds.
 */
class TranscriptionPolisherTest {

    private val start = PolishPresets.TRANSCRIPT_START
    private val end = PolishPresets.TRANSCRIPT_END

    private fun resolve(
        content: String,
        rawText: String = "some raw text",
        enforceLengthGuard: Boolean = false,
        wrapOutput: Boolean = false
    ) = TranscriptionPolisher.resolveOutput(content, rawText, enforceLengthGuard, wrapOutput)

    private fun successText(outcome: TranscriptionPolisher.Outcome): String {
        assertTrue("expected success, got $outcome", outcome is TranscriptionPolisher.Outcome.Success)
        return (outcome as TranscriptionPolisher.Outcome.Success).text
    }

    @Test
    fun `leaked markers are stripped from the result`() {
        assertEquals("Polished text.", successText(resolve("$start\nPolished text.\n$end")))
    }

    @Test
    fun `output with no markers is passed through unchanged`() {
        assertEquals("Polished text.", successText(resolve("  Polished text.  ")))
    }

    @Test
    fun `a response of nothing but markers is rejected rather than returned empty`() {
        val outcome = resolve("$start\n$end")

        assertEquals(
            TranscriptionPolisher.Outcome.Failure("Polish returned empty text"),
            outcome
        )
    }

    @Test
    fun `the length guard judges the stripped text, not the markers`() {
        // Identical content, so the real ratio is 1.0. The echoed markers add 28 characters
        // to a 45-character note — a 1.62 ratio, over the 1.5 ceiling — so measuring before
        // stripping would reject a polish that changed nothing at all.
        val raw = GUARDED_LENGTH_TEXT
        val polished = GUARDED_LENGTH_TEXT

        val outcome = resolve("$start\n$polished\n$end", rawText = raw, enforceLengthGuard = true)

        assertEquals(polished, successText(outcome))
    }

    @Test
    fun `the length guard still rejects genuinely runaway output`() {
        val raw = "This is the raw dictated text, about so long."
        val outcome = resolve(raw.repeat(4), rawText = raw, enforceLengthGuard = true)

        assertEquals(
            TranscriptionPolisher.Outcome.Failure("Polish changed the text too much — keeping raw"),
            outcome
        )
    }

    @Test
    fun `the guard is skipped when the preset does not ask for it`() {
        val raw = "This is the raw dictated text, about so long."

        // Task capture legitimately grows; without the flag the same output is accepted.
        assertTrue(resolve(raw.repeat(4), rawText = raw, enforceLengthGuard = false)
            is TranscriptionPolisher.Outcome.Success)
    }

    @Test
    fun `wrapping is applied when the preset asks for it`() {
        val text = successText(resolve("Polished text.", wrapOutput = true))

        assertEquals(
            "${PolishPresets.OUTPUT_WRAP_START}\nPolished text.\n${PolishPresets.OUTPUT_WRAP_END}",
            text
        )
    }

    @Test
    fun `wrapping is never applied when the preset does not ask for it`() {
        val text = successText(resolve("$start\nPolished text.\n$end", wrapOutput = false))

        assertEquals("Polished text.", text)
        assertTrue(!text.contains(PolishPresets.OUTPUT_WRAP_START))
    }

    @Test
    fun `a leaked marker is stripped rather than wrapped`() {
        // The failure this guards against: wrapping first would preserve the echoed
        // marker inside the tags, where the strip step can no longer reach it.
        val text = successText(resolve("$start\nPolished text.\n$end", wrapOutput = true))

        assertEquals(
            "${PolishPresets.OUTPUT_WRAP_START}\nPolished text.\n${PolishPresets.OUTPUT_WRAP_END}",
            text
        )
        assertTrue(!text.contains(start))
        assertTrue(!text.contains(end))
    }

    @Test
    fun `wrapping happens after the guard, so the tags never affect the ratio`() {
        // Deliberately sized to matter: 45 chars is over the 40-char guard exemption, and
        // the 27 characters of wrapper would put it at 1.6 — past the 1.5 ceiling — if the
        // guard ran after wrapping. Unwrapped it is exactly 1.0.
        val raw = GUARDED_LENGTH_TEXT
        val outcome = resolve(raw, rawText = raw, enforceLengthGuard = true, wrapOutput = true)

        assertEquals(
            "${PolishPresets.OUTPUT_WRAP_START}\n$raw\n${PolishPresets.OUTPUT_WRAP_END}",
            successText(outcome)
        )
    }

    @Test
    fun `the guarded fixture really is long enough to be guarded`() {
        // If a future edit shortens this text below the exemption, the two ordering tests
        // above would still pass while silently testing nothing.
        assertTrue(GUARDED_LENGTH_TEXT.length > 40)

        val wrappedRatio =
            PolishPresets.applyOutputWrapper(GUARDED_LENGTH_TEXT).length.toDouble() /
                GUARDED_LENGTH_TEXT.length
        assertTrue("wrapper must exceed the 1.5 ceiling to prove ordering", wrappedRatio > 1.5)
    }

    private companion object {
        /** 45 characters: past the guard's short-note exemption, short enough that the
         *  presentation wrapper alone would trip the 1.5 ratio ceiling. */
        const val GUARDED_LENGTH_TEXT = "This is the raw dictated text, about so long."
    }
}
