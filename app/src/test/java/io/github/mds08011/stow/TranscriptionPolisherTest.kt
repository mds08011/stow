package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed polish must say what went wrong and leave the raw transcript alone. The case
 * that prompted this: Groq deprecated its Llama chat models in June 2026 and stopped
 * serving them that August, so the polish call started returning a 400 the app rendered
 * as a status code and a JSON blob.
 */
class TranscriptionPolisherTest {

    private val model = "openai/gpt-oss-20b"

    private fun describe(status: Int, body: String?, model: String = this.model) =
        TranscriptionPolisher.describePolishError(status, body, model)

    /** Groq's actual response for a retired model. */
    private val decommissioned = """
        {"error":{"message":"The model `llama-3.1-8b-instant` has been decommissioned and is no longer supported. Please refer to https://console.groq.com/docs/deprecations for a recommendation on which model to use instead.","type":"invalid_request_error","code":"model_decommissioned"}}
    """.trimIndent()

    @Test
    fun `a decommissioned model names the model and points at the setting`() {
        val message = describe(400, decommissioned, "llama-3.1-8b-instant")

        assertTrue(message.contains("llama-3.1-8b-instant"))
        assertTrue(message.contains("decommissioned"))
        assertTrue(message.contains("Settings → Polish model"))
        // The whole point of the fallback: nothing was lost.
        assertTrue(message.contains("raw transcription is unaffected"))
    }

    @Test
    fun `an unknown model id is treated the same way`() {
        val body = """{"error":{"message":"The model `nope-1` does not exist","code":"model_not_found"}}"""

        assertTrue(describe(404, body, "nope-1").contains("Settings → Polish model"))
        assertTrue(describe(400, body, "nope-1").contains("Settings → Polish model"))
    }

    @Test
    fun `a typo in the model field is reported against what was actually sent`() {
        // The user's own value, not the shipped default, is what they need to see.
        assertTrue(describe(404, "{}", "openai/gpt-oss-20B").contains("openai/gpt-oss-20B"))
    }

    @Test
    fun `key, rate limit and server errors explain themselves`() {
        assertTrue(describe(401, "{}").contains("key was rejected"))
        assertTrue(describe(403, "{}").contains("key was rejected"))
        assertTrue(describe(429, "{}").contains("rate limit", ignoreCase = true))
        assertTrue(describe(500, "{}").contains("server error"))
        assertTrue(describe(503, "{}").contains("server error"))
    }

    @Test
    fun `an unrecognised failure surfaces the server's own message`() {
        val body = """{"error":{"message":"context_length_exceeded","type":"invalid_request_error"}}"""
        val message = describe(400, body)

        assertTrue(message.contains("400"))
        assertTrue(message.contains("context_length_exceeded"))
    }

    @Test
    fun `a non-JSON body is shown rather than hidden`() {
        assertTrue(describe(418, "teapot").contains("teapot"))
    }

    @Test
    fun `an empty body still produces a readable message`() {
        assertFalse(describe(400, null).isBlank())
        assertFalse(describe(400, "").isBlank())
        assertFalse(describe(400, "{}").isBlank())
        // No dangling newline from an absent body.
        assertEquals("Polish failed (error 400).", describe(400, "{}"))
    }

    @Test
    fun `reasoning controls are sent only to models that understand them`() {
        // gpt-oss takes reasoning_effort / include_reasoning; other Groq chat models reject
        // them. The model id is user-editable, so this has to be decided per request.
        assertTrue(TranscriptionPolisher.isReasoningModel("openai/gpt-oss-20b"))
        assertTrue(TranscriptionPolisher.isReasoningModel("openai/gpt-oss-120b"))
        assertTrue(TranscriptionPolisher.isReasoningModel(" openai/gpt-oss-20b "))

        assertFalse(TranscriptionPolisher.isReasoningModel("llama-3.1-8b-instant"))
        assertFalse(TranscriptionPolisher.isReasoningModel("qwen/qwen3.6-27b"))
        assertFalse(TranscriptionPolisher.isReasoningModel(""))
    }

    @Test
    fun `the shipped default is a model Groq still serves`() {
        // Guards against the Llama ids coming back in a merge. Whisper is unaffected by the
        // June 2026 chat deprecations and keeps its own constants.
        assertEquals("openai/gpt-oss-20b", TranscriptionPolisher.MODEL)
        assertFalse(TranscriptionPolisher.MODEL.contains("llama"))
    }

    // --- Token budget ----------------------------------------------------------------
    //
    // The failure these cover, seen in the field on v2.8: "Polish returned empty text".
    // gpt-oss spends part of the completion budget thinking before it writes, so a note
    // whose budget was only just large enough came back with nothing in it.

    @Test
    fun `a reasoning model gets an allowance a non-reasoning model does not`() {
        val chars = 6000
        val reasoning = TranscriptionPolisher.maxTokensFor(chars, "openai/gpt-oss-20b")
        val plain = TranscriptionPolisher.maxTokensFor(chars, "llama-3.3-70b-versatile")

        assertEquals(4000, plain)
        assertTrue("a reasoning model needs the larger budget", reasoning > plain)
    }

    @Test
    fun `the allowance reaches mid-length notes, not just ones on the floor`() {
        // The v2.8 fix was to raise the floor, which only binds below ~1,500 characters.
        // A note past that took its budget from the estimate and got no allowance at all.
        val onTheFloor = TranscriptionPolisher.maxTokensFor(300, model)
        val pastTheFloor = TranscriptionPolisher.maxTokensFor(3000, model)

        assertTrue(onTheFloor > 1024)
        assertTrue("the estimate alone would have been 2000", pastTheFloor > 2000 + 1000)
    }

    @Test
    fun `the budget still grows with the transcript`() {
        val short = TranscriptionPolisher.maxTokensFor(2000, model)
        val long = TranscriptionPolisher.maxTokensFor(20000, model)

        assertTrue(long > short)
        // Still a cap, not an open budget: a runaway generation is what it exists to stop.
        assertTrue(long < 20000)
    }

    // --- Stopping on the token cap ---------------------------------------------------

    @Test
    fun `no content at all is reported as the budget, not as a bad model`() {
        val message = TranscriptionPolisher.describeTokenCap(noContent = true)

        assertTrue(message.contains("thinking"))
        assertTrue(message.contains("keeping raw"))
        // "empty text" is what v2.8 said here, and it pointed at the wrong thing.
        assertFalse(message.contains("empty text"))
    }

    @Test
    fun `truncated output is reported as truncation`() {
        val message = TranscriptionPolisher.describeTokenCap(noContent = false)

        assertTrue(message.contains("cut off"))
        assertTrue(message.contains("keeping raw"))
    }

    // --- Rate-limit headers -----------------------------------------------------------

    @Test
    fun `the rate limit line reads as a budget`() {
        assertEquals(
            "1200 of 6000 tokens left · retry after 7.5s",
            TranscriptionPolisher.formatRateLimit("6000", "1200", "7.5")
        )
    }

    @Test
    fun `partial headers still produce a line, and absent ones produce none`() {
        assertEquals("1200 tokens left", TranscriptionPolisher.formatRateLimit(null, "1200", null))
        assertEquals("retry after 3s", TranscriptionPolisher.formatRateLimit(null, null, "3"))
        assertEquals(null, TranscriptionPolisher.formatRateLimit(null, null, null))
        assertEquals(null, TranscriptionPolisher.formatRateLimit("6000", "  ", ""))
    }

    @Test
    fun `a rate limited polish shows what is left rather than just try again`() {
        val message = TranscriptionPolisher.describePolishError(
            429,
            null,
            model,
            TranscriptionPolisher.formatRateLimit("6000", "0", "12")
        )

        assertTrue(message.contains("rate limit"))
        assertTrue(message.contains("0 of 6000 tokens left"))
        assertTrue(message.contains("retry after 12s"))
    }

    @Test
    fun `a failure with no headers is unchanged`() {
        assertFalse(describe(429, null).contains("tokens left"))
    }
}
