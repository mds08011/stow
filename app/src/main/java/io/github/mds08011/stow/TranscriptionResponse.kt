package io.github.mds08011.stow

import org.json.JSONObject

/**
 * Parses Groq's `verbose_json` transcription response.
 *
 * Beyond the transcript itself, `verbose_json` returns per-segment decoding statistics.
 * These are the only objective signal the app has for distinguishing a clean transcription
 * from a fluent hallucination — Whisper produces confident-sounding nonsense on low-SNR
 * audio, and nothing in the plain text gives that away.
 * See docs/audio-investigation-2026-07.md.
 */
object TranscriptionResponse {

    data class Parsed(
        val text: String,
        /** Mean of the segments' `avg_logprob`. More negative means less confident. */
        val avgLogprob: Double?,
        /** Worst segment `no_speech_prob`. High means the model heard mostly silence or noise. */
        val maxNoSpeechProb: Double?,
        /** Worst segment `compression_ratio`. High means repetitive, looping output. */
        val maxCompressionRatio: Double?
    )

    /**
     * Returns null only when there is no usable transcript. Missing or malformed statistics
     * degrade to null metrics rather than losing the transcription — a diagnostic must never
     * cost the user their note.
     */
    fun parse(body: String): Parsed? {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }

        val text = json.optString("text", "")
        if (text.isEmpty()) return null

        val segments = json.optJSONArray("segments")
            ?: return Parsed(text, null, null, null)

        val logprobs = ArrayList<Double>(segments.length())
        var maxNoSpeech = Double.NaN
        var maxCompression = Double.NaN

        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue

            val logprob = segment.optDouble("avg_logprob", Double.NaN)
            if (!logprob.isNaN()) logprobs.add(logprob)

            val noSpeech = segment.optDouble("no_speech_prob", Double.NaN)
            if (!noSpeech.isNaN() && (maxNoSpeech.isNaN() || noSpeech > maxNoSpeech)) {
                maxNoSpeech = noSpeech
            }

            val compression = segment.optDouble("compression_ratio", Double.NaN)
            if (!compression.isNaN() && (maxCompression.isNaN() || compression > maxCompression)) {
                maxCompression = compression
            }
        }

        return Parsed(
            text = text,
            avgLogprob = if (logprobs.isEmpty()) null else logprobs.average(),
            maxNoSpeechProb = if (maxNoSpeech.isNaN()) null else maxNoSpeech,
            maxCompressionRatio = if (maxCompression.isNaN()) null else maxCompression
        )
    }
}
