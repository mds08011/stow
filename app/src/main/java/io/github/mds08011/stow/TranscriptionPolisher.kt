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

        val maxTokens = maxTokensFor(rawText.length, model)

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
                val rateLimit = formatRateLimit(
                    limit = response.header("x-ratelimit-limit-tokens"),
                    remaining = response.header("x-ratelimit-remaining-tokens"),
                    retryAfter = response.header("retry-after")
                )
                // Logged on every response, successes included, alongside what was asked
                // for. Groq returns these headers always, and read together they answer
                // the question the budget below turns on: whether the per-minute
                // allowance is charged for max_tokens or only for tokens actually
                // generated. That decides how large the reasoning allowance can safely
                // get, and it is answerable from ordinary field use rather than a test.
                android.util.Log.i(
                    TAG,
                    "polish: model=$model status=${response.code} " +
                        "chars=${rawText.length} maxTokens=$maxTokens " +
                        "rateLimit=${rateLimit ?: "absent"}"
                )
                if (!response.isSuccessful || responseBody == null) {
                    onError(describePolishError(response.code, responseBody, model, rateLimit))
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    // A 200 can still carry an error envelope; treat it as a failure rather
                    // than falling through to "no content".
                    if (json.has("error")) {
                        onError(describePolishError(response.code, responseBody, model, rateLimit))
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
                    // The token cap is tested before emptiness, and the order is the whole
                    // point. A reasoning model can spend the entire budget thinking and
                    // return finish_reason=length with no content at all — which is the
                    // exact failure the cap check was added for in v2.8, and which the
                    // emptiness check in front of it then swallowed. Reported as "returned
                    // empty text" it reads as a misbehaving model; it is a budget too small
                    // to reach an answer, and the two want different fixes.
                    if (choice.optString("finish_reason") == "length") {
                        onError(describeTokenCap(polished.isEmpty()))
                        return
                    }
                    if (polished.isEmpty()) {
                        onError("Polish returned empty text — keeping raw.")
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
        fun describePolishError(
            status: Int,
            body: String?,
            model: String,
            rateLimit: String? = null
        ): String {
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
                    "Groq rate limit reached, so the note was not polished. Try again shortly." +
                        rateLimit?.let { "\n\n$it" }.orEmpty()
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
         * The completion budget for one polish request.
         *
         * The output estimate is unchanged: roughly two tokens of headroom per token of
         * input, which covers a preset that restructures while still capping a runaway
         * generation. What v2.8 missed is that on a reasoning model the budget is not
         * spent on output alone — gpt-oss emits its thinking first and from the same
         * allowance, so a note whose estimate was only just large enough arrived
         * truncated, or empty when the thinking consumed all of it.
         *
         * Raising [MIN_MAX_TOKENS] did not fix that, because the floor only binds below
         * about 1,500 characters and the failure runs well past it. The thinking cannot
         * be switched off either: `reasoning_effort` is already `"low"`, and gpt-oss does
         * not accept `"none"` (only Qwen does). So it is budgeted for instead.
         *
         * The allowance is flat rather than proportional because that is the shape of the
         * cost — reading the preset's rules once before writing, which does not grow with
         * the transcript.
         */
        fun maxTokensFor(rawTextLength: Int, model: String): Int {
            val output = maxOf(MIN_MAX_TOKENS, (rawTextLength / 3) * 2)
            return if (isReasoningModel(model)) output + REASONING_HEADROOM_TOKENS else output
        }

        /**
         * Why a generation that stopped on the token cap has no usable result.
         *
         * Split in two because the two cases point at different things. Truncated output
         * means the transcript outgrew its budget; nothing at all means the model never
         * got past thinking, which is a reasoning-model failure and the reason
         * [REASONING_HEADROOM_TOKENS] exists.
         */
        fun describeTokenCap(noContent: Boolean): String = if (noContent) {
            "Polish used its whole token budget thinking and never wrote an answer — " +
                "keeping raw."
        } else {
            "Polish output was cut off at the token limit, so it is incomplete — " +
                "keeping raw."
        }

        /**
         * Groq's rate-limit headers condensed to one line, or null when none were sent.
         *
         * Groq returns these on every response, not only a 429, which is what makes them
         * worth reading: the gap between what a request asks for and what the budget is
         * charged is the open question behind [maxTokensFor]. Shown to the user only on a
         * 429, where "try again shortly" is otherwise the whole answer.
         */
        fun formatRateLimit(limit: String?, remaining: String?, retryAfter: String?): String? {
            val parts = buildList {
                remaining?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add(if (limit.isNullOrBlank()) "$it tokens left" else "$it of $limit tokens left")
                }
                retryAfter?.trim()?.takeIf { it.isNotEmpty() }?.let { add("retry after ${it}s") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /**
         * Reasoning controls are gpt-oss-specific. Other Groq chat models reject the
         * parameters outright, and the polish model is user-editable, so they are only
         * sent when the selected model is one that understands them.
         */
        fun isReasoningModel(model: String): Boolean =
            model.trim().startsWith("openai/gpt-oss", ignoreCase = true)

        /**
         * Floor under the *output* half of the budget, for a note too short to estimate
         * from. Was 256 until v2.8, which raised it to 1024 to cover a reasoning model's
         * thinking. That is now [REASONING_HEADROOM_TOKENS]' job — added on top rather
         * than folded into the floor, so it also reaches the mid-length notes where the
         * estimate, not the floor, decides the budget.
         */
        private const val MIN_MAX_TOKENS = 1024

        /**
         * Flat allowance added to a reasoning model's budget for the tokens it spends
         * thinking before it writes. See [maxTokensFor] for why it is flat, and why the
         * floor alone was not enough.
         */
        private const val REASONING_HEADROOM_TOKENS = 2048

        private const val TAG = "TranscriptionPolisher"

        /** Below this length, normal filler removal swings the ratio too much to judge. */
        private const val LENGTH_GUARD_MIN_CHARS = 40
        private const val LENGTH_GUARD_MIN_RATIO = 0.4
        private const val LENGTH_GUARD_MAX_RATIO = 1.5
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
