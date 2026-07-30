package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parser must never cost the user their transcription. Malformed or partial statistics
 * degrade to null metrics; only a genuinely unusable body returns null.
 */
class TranscriptionResponseTest {

    private val verboseJson = """
        {
          "task": "transcribe",
          "language": "english",
          "duration": 8.4,
          "text": "Check the clarifier weir on Monday.",
          "segments": [
            {"id":0,"start":0.0,"end":4.0,"text":"Check the clarifier weir",
             "avg_logprob":-0.20,"compression_ratio":1.1,"no_speech_prob":0.01},
            {"id":1,"start":4.0,"end":8.4,"text":"on Monday.",
             "avg_logprob":-0.40,"compression_ratio":1.5,"no_speech_prob":0.05}
          ]
        }
    """.trimIndent()

    @Test
    fun `aggregates segment statistics`() {
        val parsed = TranscriptionResponse.parse(verboseJson)

        assertNotNull(parsed)
        assertEquals("Check the clarifier weir on Monday.", parsed!!.text)
        assertEquals(-0.30, parsed.avgLogprob!!, 1e-9)   // mean of -0.20 and -0.40
        assertEquals(0.05, parsed.maxNoSpeechProb!!, 1e-9) // max, not last
        assertEquals(1.5, parsed.maxCompressionRatio!!, 1e-9)
    }

    @Test
    fun `response without segments still yields the transcript`() {
        val parsed = TranscriptionResponse.parse("""{"text":"plain transcript"}""")

        assertNotNull(parsed)
        assertEquals("plain transcript", parsed!!.text)
        assertNull(parsed.avgLogprob)
        assertNull(parsed.maxNoSpeechProb)
        assertNull(parsed.maxCompressionRatio)
    }

    @Test
    fun `empty segment array yields the transcript with null metrics`() {
        val parsed = TranscriptionResponse.parse("""{"text":"hello","segments":[]}""")

        assertEquals("hello", parsed!!.text)
        assertNull(parsed.avgLogprob)
    }

    @Test
    fun `segments missing individual statistics are skipped not zeroed`() {
        val parsed = TranscriptionResponse.parse(
            """
            {"text":"x","segments":[
              {"avg_logprob":-0.5},
              {"no_speech_prob":0.9},
              {}
            ]}
            """.trimIndent()
        )

        assertEquals(-0.5, parsed!!.avgLogprob!!, 1e-9)
        assertEquals(0.9, parsed.maxNoSpeechProb!!, 1e-9)
        assertNull(parsed.maxCompressionRatio)
    }

    @Test
    fun `unusable bodies return null`() {
        assertNull(TranscriptionResponse.parse("not json at all"))
        assertNull(TranscriptionResponse.parse("{}"))
        assertNull(TranscriptionResponse.parse("""{"text":""}"""))
    }

    @Test
    fun `a hallucinating response trips the quality thresholds`() {
        // Repetition loop on near-silence: the shape of the Jul 2026 bad dictations.
        val parsed = TranscriptionResponse.parse(
            """
            {"text":"And then add that in. And then add that in.","segments":[
              {"avg_logprob":-1.4,"compression_ratio":3.1,"no_speech_prob":0.75}
            ]}
            """.trimIndent()
        )!!

        val entry = TranscriptionHistory.Entry(
            id = "x",
            timestampMillis = 1L,
            durationSeconds = 60,
            rawText = parsed.text,
            polishedText = null,
            avgLogprob = parsed.avgLogprob,
            maxNoSpeechProb = parsed.maxNoSpeechProb,
            maxCompressionRatio = parsed.maxCompressionRatio
        )

        assertEquals("repetitive output", entry.qualityWarning())
    }
}
