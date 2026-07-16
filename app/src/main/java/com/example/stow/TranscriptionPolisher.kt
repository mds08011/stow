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
 * Light cleanup only: fillers, spelling, grammar, punctuation, and minimal rephrasing.
 * Keeps length and meaning close to the original; preserves jargon terms.
 */
class TranscriptionPolisher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    fun polish(
        rawText: String,
        apiKey: String,
        jargon: String,
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

        val systemPrompt = buildSystemPrompt(jargon, rawText)
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.2)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Clean the raw transcription.")
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
                    onSuccess(polished)
                } catch (e: Exception) {
                    onError("Error parsing polish response")
                }
            }
        })
    }

    /**
     * Builds the polishing system prompt. Keep in sync with docs/polishing-prompt.md.
     */
    private fun buildSystemPrompt(jargon: String, rawTranscription: String): String {
        val jargonList = if (jargon.isNotBlank()) jargon.trim() else "(none)"
        return """
            |You are a careful transcription cleaner for professional voice notes (civil engineering, construction, project notes).
            |
            |Your job is light cleanup only. Follow these rules strictly and in order:
            |
            |1. Remove filler words and vocalizations: um, uh, er, ah, like, you know, kind of, sort of, basically, actually (when used as filler), yeah, so, well (when used as filler), and similar. Remove them completely.
            |
            |2. Fix spelling mistakes, grammar errors, and add correct capitalization and punctuation.
            |
            |3. Apply very light rephrasing only when it significantly improves readability (e.g. fixing broken word order or incomplete sentences). Keep the original meaning, structure, and the speaker’s natural voice.
            |
            |4. Do NOT add any new information, explanations, summaries, or content that was not spoken.
            |5. Do NOT expand short notes into longer text or turn them into essays.
            |6. Do NOT rewrite for style or make the language more formal/professional beyond the light fixes above.
            |7. Preserve technical terms, project names, company names, and proper nouns exactly. Use the provided Jargon List when available.
            |8. Keep the cleaned output similar in length to the original transcription.
            |
            |Output ONLY the cleaned plain text. No explanations, no quotes, no additional commentary.
            |
            |Jargon List (preserve these exactly):
            |$jargonList
            |
            |Raw transcription:
            |$rawTranscription
        """.trimMargin()
    }

    companion object {
        const val MODEL = "llama-3.1-8b-instant"
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
