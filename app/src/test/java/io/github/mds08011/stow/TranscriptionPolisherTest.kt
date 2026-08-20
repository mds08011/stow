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
}
