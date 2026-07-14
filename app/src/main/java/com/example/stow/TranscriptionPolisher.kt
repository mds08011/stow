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
 * Cleans fillers, grammar, and false starts while preserving meaning and jargon.
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

        val systemPrompt = buildSystemPrompt(jargon)
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
                        put("content", rawText)
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

    private fun buildSystemPrompt(jargon: String): String {
        val jargonSection = if (jargon.isNotBlank()) {
            """
            |Use these domain terms exactly when they appear or are clearly intended
            |(project names, companies, technical vocabulary):
            |$jargon
            """.trimMargin()
        } else {
            "No custom jargon list was provided."
        }

        return """
            |You polish voice-dictation transcripts for engineering notes.
            |Rules:
            |- Fix grammar, punctuation, and capitalization.
            |- Remove fillers (um, uh, er, ah, like, you know, sort of, kind of, I mean, basically, actually when empty).
            |- Remove false starts, stutters, and obvious redundancies.
            |- Preserve the speaker's meaning, facts, numbers, and intent.
            |- Do not invent content or add commentary.
            |- Prefer clear, professional prose suitable for engineering voice notes.
            |- Output only the polished text with no quotes or preface.
            |
            |$jargonSection
        """.trimMargin()
    }

    companion object {
        const val MODEL = "llama-3.1-8b-instant"
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
