package io.github.mds08011.stow

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Builds and sends Groq transcription requests.
 *
 * [buildRequest] is shared with [RecordingService] so the recording path and the
 * re-transcribe path cannot drift apart — a diagnostic that does not send exactly what
 * the real path sends is worthless for comparison.
 */
object AudioTranscriber {

    const val MODEL_TURBO = "whisper-large-v3-turbo"
    const val MODEL_LARGE = "whisper-large-v3"

    private const val TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

    fun buildRequest(file: File, apiKey: String, jargon: String, model: String): Request {
        val prompt = jargon.trim()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "verbose_json")
            .apply {
                // Only the user's own vocabulary; the Whisper prompt budget is ~224 tokens.
                if (prompt.isNotEmpty()) {
                    addFormDataPart("prompt", prompt)
                }
            }
            .build()

        return Request.Builder()
            .url(TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    /**
     * The one client every transcription request goes through, including the recording
     * path in [RecordingService]. OkHttp's defaults are 10 seconds each for connect, read
     * and write, and a transcription outlasts all three: Groq answers nothing until it has
     * decoded the whole file, which takes longer than 10 seconds once a note runs past
     * twenty minutes or so, and a weak uplink can stall a write for that long on a note of
     * any length. The recording path used a bare `OkHttpClient()` until v2.8.4, so a long
     * note timed out, was re-uploaded whole by the automatic retry, and timed out again.
     */
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // The wait for Groq's answer after the upload. Non-turbo is materially slower.
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Per stalled write, not for the whole upload.
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    internal const val CONNECT_TIMEOUT_SECONDS = 30L
    internal const val READ_TIMEOUT_SECONDS = 300L
    internal const val WRITE_TIMEOUT_SECONDS = 120L

    /**
     * One-shot transcription used by the re-transcribe action. The recording path does its
     * own send because it is entangled with history, notifications and retry.
     */
    fun transcribe(
        file: File,
        apiKey: String,
        jargon: String,
        model: String,
        onSuccess: (TranscriptionResponse.Parsed) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!file.exists()) {
            onError("Saved audio is no longer available")
            return
        }
        if (apiKey.isBlank()) {
            onError("API key is missing")
            return
        }

        client.newCall(buildRequest(file, apiKey, jargon, model)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Transcription failed: ${e.message ?: "network error"}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onError("API error: ${response.code}")
                    return
                }
                val parsed = TranscriptionResponse.parse(body)
                if (parsed == null) {
                    onError("Could not read the transcription response")
                    return
                }
                onSuccess(parsed)
            }
        })
    }
}
