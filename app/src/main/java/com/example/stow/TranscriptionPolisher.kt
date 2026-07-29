package com.example.stow

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional post-transcription polish via Groq chat completions.
 *
 * The behaviour is defined entirely by the caller-supplied system prompt, which comes from
 * the selected [PolishPresets.Preset] (built via [PolishPresets.buildSystemPrompt] so the
 * Jargon Dictionary is applied). The raw transcript is sent as the user message.
 */
class TranscriptionPolisher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * @param systemPrompt the selected preset's prompt, already jargon-substituted.
     * @param enforceLengthGuard reject output that is wildly shorter or longer than the input.
     *   Only meaningful for light-cleanup presets — a preset that restructures the text
     *   (Task capture, say) legitimately changes length and must not set this.
     */
    fun polish(
        rawText: String,
        apiKey: String,
        systemPrompt: String,
        enforceLengthGuard: Boolean = false,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (rawText.isBlank()) {
            onError("Nothing to polish")
            return
        }
        if (apiKey.isBlank()) {
            onError("API key is missing")
            return
        }
        if (systemPrompt.isBlank()) {
            onError("Polish preset has no prompt text")
            return
        }

        val userMessage = buildString {
            append("Clean the transcription between the markers.\n\n")
            append(PolishPresets.TRANSCRIPT_START).append('\n')
            append(rawText).append('\n')
            append(PolishPresets.TRANSCRIPT_END)
        }

        // Roughly two tokens of headroom per token of input — enough for any preset that
        // restructures, while still capping a runaway generation on the free tier.
        val maxTokens = maxOf(256, (rawText.length / 3) * 2)

        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                }
            )
        }

        val request = Request.Builder()
            .url(CHAT_COMPLETIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Polish failed: ${e.message ?: "network error"}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    onError("Polish API error: ${response.code}\n${responseBody ?: ""}")
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() == 0) {
                        onError("Polish returned no content")
                        return
                    }
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    val polished = message.getString("content").trim()
                    if (polished.isEmpty()) {
                        onError("Polish returned empty text")
                        return
                    }
                    // "Light cleanup only" is otherwise enforced purely by a prompt an 8B
                    // model can drift from; this makes it a property of the app.
                    if (enforceLengthGuard && rawText.length > LENGTH_GUARD_MIN_CHARS) {
                        val ratio = polished.length.toDouble() / rawText.length
                        if (ratio < LENGTH_GUARD_MIN_RATIO || ratio > LENGTH_GUARD_MAX_RATIO) {
                            onError("Polish changed the text too much — keeping raw")
                            return
                        }
                    }
                    onSuccess(polished)
                } catch (e: Exception) {
                    onError("Error parsing polish response")
                }
            }
        })
    }

    companion object {
        const val MODEL = "llama-3.1-8b-instant"

        /** Below this length, normal filler removal swings the ratio too much to judge. */
        private const val LENGTH_GUARD_MIN_CHARS = 40
        private const val LENGTH_GUARD_MIN_RATIO = 0.4
        private const val LENGTH_GUARD_MAX_RATIO = 1.5
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
