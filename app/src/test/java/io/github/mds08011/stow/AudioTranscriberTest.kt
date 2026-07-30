package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recording path and the re-transcribe path must build identical requests apart from
 * the model, otherwise a re-transcription is not a fair comparison against the original.
 */
class AudioTranscriberTest {

    private val file = File("audio_1700000000000.m4a")

    private fun fieldNames(model: String, jargon: String): List<String> {
        val request = AudioTranscriber.buildRequest(file, "test-key", jargon, model)
        val body = request.body as okhttp3.MultipartBody
        return (0 until body.size).map { i ->
            val disposition = body.part(i).headers?.get("Content-Disposition").orEmpty()
            Regex("""name="([^"]+)"""").find(disposition)?.groupValues?.get(1).orEmpty()
        }
    }

    @Test
    fun `request targets the Groq transcription endpoint with the key`() {
        val request = AudioTranscriber.buildRequest(file, "test-key", "", AudioTranscriber.MODEL_TURBO)

        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("POST", request.method)
    }

    @Test
    fun `every request carries the decoding controls`() {
        val names = fieldNames(AudioTranscriber.MODEL_TURBO, "")

        assertTrue(names.contains("file"))
        assertTrue(names.contains("model"))
        assertTrue(names.contains("language"))
        assertTrue(names.contains("temperature"))
        // Without this the app has no signal that a transcription is a hallucination.
        assertTrue(names.contains("response_format"))
    }

    @Test
    fun `jargon is sent only when present`() {
        assertTrue(fieldNames(AudioTranscriber.MODEL_TURBO, "MGD, clarifier").contains("prompt"))
        assertTrue(!fieldNames(AudioTranscriber.MODEL_TURBO, "   ").contains("prompt"))
    }

    @Test
    fun `both models produce the same request shape`() {
        assertEquals(
            fieldNames(AudioTranscriber.MODEL_TURBO, "MGD"),
            fieldNames(AudioTranscriber.MODEL_LARGE, "MGD")
        )
    }

    @Test
    fun `model identifiers are the expected Groq names`() {
        assertEquals("whisper-large-v3-turbo", AudioTranscriber.MODEL_TURBO)
        assertEquals("whisper-large-v3", AudioTranscriber.MODEL_LARGE)
    }
}
