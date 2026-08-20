package io.github.mds08011.stow

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
     * @param model the chat model id to call. User-editable in Settings so a Groq
     *   deprecation is a settings change rather than an app update; defaults to [MODEL].
     * @param enforceLengthGuard reject output that is wildly shorter or longer than the input.
     *   Only meaningful for light-cleanup presets — a preset that restructures the text
     *   (Task capture, say) legitimately changes length and must not set this.
     */
    fun polish(
        rawText: String,
        apiKey: String,
        systemPrompt: String,
        model: String = MODEL,
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
        if (model.isBlank()) {
            onError("No polish model set. Check Settings → Polish model.")
            return
        }

        val userMessage = buildString {
            append("Clean the transcription between the markers.\n\n")
            append(PolishPresets.TRANSCRIPT_START).append('\n')
            append(rawText).append('\n')
            append(PolishPresets.TRANSCRIPT_END)
        }

        // Roughly two tokens of headroom per token of input — enough for any preset that
        // restructures, while still capping a runaway generation on the free tier. The
        // floor is generous because a reasoning model spends part of the budget thinking
        // before it writes anything; see MIN_MAX_TOKENS.
        val maxTokens = maxOf(MIN_MAX_TOKENS, (rawText.length / 3) * 2)

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            if (isReasoningModel(model)) {
                // Polish wants clean text, not the model's thinking. Groq returns reasoning
                // in a separate field so it never lands in the note, but it is still
                // generated and still spends the token budget — so ask for as little of it
                // as possible and don't ship it back over a field connection.
                put("reasoning_effort", "low")
                put("include_reasoning", false)
            }
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
                    onError(describePolishError(response.code, responseBody, model))
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    // A 200 can still carry an error envelope; treat it as a failure rather
                    // than falling through to "no content".
                    if (json.has("error")) {
                        onError(describePolishError(response.code, responseBody, model))
                        return
                    }
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        onError("Polish returned no content — keeping raw.")
                        return
                    }
                    val choice = choices.getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    val polished = message?.optString("content").orEmpty().trim()
                    if (polished.isEmpty()) {
                        onError("Polish returned empty text — keeping raw.")
                        return
                    }
                    // A generation that stopped on the token cap is truncated mid-sentence.
                    // It looks like ordinary output, so nothing downstream would catch it.
                    if (choice.optString("finish_reason") == "length") {
                        onError(
                            "Polish output was cut off at the token limit, so it is " +
                                "incomplete — keeping raw."
                        )
                        return
                    }
                    // "Light cleanup only" is otherwise enforced purely by a prompt a small
                    // model can drift from; this makes it a property of the app.
                    if (enforceLengthGuard && rawText.length > LENGTH_GUARD_MIN_CHARS) {
                        val ratio = polished.length.toDouble() / rawText.length
                        if (ratio < LENGTH_GUARD_MIN_RATIO || ratio > LENGTH_GUARD_MAX_RATIO) {
                            onError("Polish changed the text too much — keeping raw.")
                            return
                        }
                    }
                    onSuccess(polished)
                } catch (e: Exception) {
                    onError("Could not read the polish response — keeping raw.")
                }
            }
        })
    }

    companion object {
        /**
         * Default polish model — what the Settings field is prefilled with, and what an
         * empty field falls back to. Shared with Stow Web as `MODELS.polish`; see
         * docs/parity.md before changing it.
         *
         * Was `llama-3.1-8b-instant` until v2.8. Groq deprecated its Llama chat models on
         * 2026-06-17 and stopped serving them that August, which is also why the model is
         * now user-editable: the next deprecation should be a settings change, not a
         * release. Whisper transcription is unaffected and keeps its own constants in
         * [AudioTranscriber].
         */
        const val MODEL = "openai/gpt-oss-20b"

        /**
         * Turns a failed polish into something worth reading, rather than a status code and
         * a JSON blob. Mirrors `RecordingService.describeApiError` for the transcription
         * side; kept separate because the advice differs — a polish failure always leaves
         * the raw transcript intact, and a rejected model points at a setting the user owns.
         */
        fun describePolishError(status: Int, body: String?, model: String): String {
            val serverMessage = errorMessage(body)
            val rejectedModel = status == 404 ||
                body?.contains("model_decommissioned", ignoreCase = true) == true ||
                body?.contains("model_not_found", ignoreCase = true) == true ||
                serverMessage?.contains("decommissioned", ignoreCase = true) == true ||
                serverMessage?.contains("does not exist", ignoreCase = true) == true

            return when {
                rejectedModel ->
                    "Groq will not serve the polish model \"$model\".\n\n" +
                        (serverMessage ?: "The model was rejected.") +
                        "\n\nPick a current model in Settings → Polish model " +
                        "(console.groq.com/docs/models lists what is live). " +
                        "The raw transcription is unaffected."
                status == 401 || status == 403 ->
                    "Your Groq API key was rejected. Check it in Settings."
                status == 429 ->
                    "Groq rate limit reached, so the note was not polished. Try again shortly."
                status >= 500 ->
                    "Groq had a server error ($status), so the note was not polished."
                else ->
                    "Polish failed (error $status)." +
                        (serverMessage ?: informativeBody(body))
                            ?.let { "\n$it" }.orEmpty()
            }
        }

        /**
         * The raw body, but only when it would tell the user something. An empty envelope
         * appended to the message is the status-plus-JSON-blob noise v2.7 set out to remove.
         */
        private fun informativeBody(body: String?): String? =
            body?.trim()?.takeIf { it.isNotEmpty() && it != "{}" && it != "[]" }

        /** Pulls `error.message` out of Groq's error envelope; null if it isn't one. */
        private fun errorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return try {
                JSONObject(body).optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Reasoning controls are gpt-oss-specific. Other Groq chat models reject the
         * parameters outright, and the polish model is user-editable, so they are only
         * sent when the selected model is one that understands them.
         */
        fun isReasoningModel(model: String): Boolean =
            model.trim().startsWith("openai/gpt-oss", ignoreCase = true)

        /**
         * Floor for `max_tokens`. Was 256, which was ample for an 8B Llama that started
         * writing immediately. A reasoning model emits thinking tokens first and they come
         * out of the same budget, so a short note could spend the whole cap before writing
         * a word — arriving as a truncated or empty polish rather than an error.
         */
        private const val MIN_MAX_TOKENS = 1024

        /** Below this length, normal filler removal swings the ratio too much to judge. */
        private const val LENGTH_GUARD_MIN_CHARS = 40
        private const val LENGTH_GUARD_MIN_RATIO = 0.4
        private const val LENGTH_GUARD_MAX_RATIO = 1.5
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
