package com.example.stow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val client = OkHttpClient()
    private var isRecording = false
    private var startTimeMillis = 0L

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_RETRY_UPLOAD = "ACTION_RETRY_UPLOAD"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val EXTRA_JARGON = "EXTRA_JARGON"
        /** When true, skip clipboard so the UI can polish first, then copy the final text. */
        const val EXTRA_DEFER_CLIPBOARD = "EXTRA_DEFER_CLIPBOARD"
        
        const val BROADCAST_STATE = "com.example.stow.STATE_UPDATE"
        const val EXTRA_STATE = "EXTRA_STATE"
        const val EXTRA_TEXT = "EXTRA_TEXT"
        const val EXTRA_USAGE = "EXTRA_USAGE"
        const val EXTRA_DURATION = "EXTRA_DURATION"
        /** Id of the history entry the service already saved for this transcription. */
        const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"

        const val PREFS_NAME = "StowPrefs"
        /** Set when a transcription was saved but no UI has shown it yet. */
        const val PREF_PENDING_RESULT_ID = "pending_result_id"
        /** MainActivity keeps this current so the service knows whether to notify. */
        const val PREF_UI_VISIBLE = "ui_visible"
        /** Set when an upload failed and the audio is still on disk, ready to retry. */
        const val PREF_FAILED_AUDIO_PATH = "failed_audio_path"
        const val PREF_FAILED_AUDIO_DURATION = "failed_audio_duration"
        /** Read by the Quick Settings tile, which has no binding to this service. */
        const val PREF_IS_RECORDING = "is_recording"

        /** Groq's free tier rejects larger uploads; stay clear of the 25 MB ceiling. */
        private const val MAX_UPLOAD_BYTES = 24L * 1024 * 1024
        private const val AUDIO_FILE_PREFIX = "audio_"
        private const val AUDIO_FILE_SUFFIX = ".m4a"
        private const val RETRY_DELAY_MILLIS = 2000L

        private const val RECORDING_NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2
        private const val RECORDING_CHANNEL_ID = "StowChannel"
        private const val RESULT_CHANNEL_ID = "StowResultChannel"
        
        const val STATE_RECORDING = "ACTION_RECORDING_STARTED"
        const val STATE_LOADING = "ACTION_RECORDING_STOPPED"
        const val STATE_SUCCESS = "ACTION_RECORDING_SUCCESS"
        const val STATE_ERROR = "ACTION_RECORDING_ERROR"
        const val STATE_STOPPED = "STATE_STOPPED"
        const val STATE_PAUSED = "STATE_PAUSED"
        const val STATE_RESUMED = "STATE_RESUMED"
    }

    private var apiKey: String = ""
    private var jargon: String = ""
    private var deferClipboard: Boolean = false

    private var isPaused = false
    /** Recording time excluding paused stretches, so usage and duration stay honest. */
    private var accumulatedActiveMillis = 0L
    private var segmentStartMillis = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                    jargon = intent.getStringExtra(EXTRA_JARGON) ?: ""
                    deferClipboard = intent.getBooleanExtra(EXTRA_DEFER_CLIPBOARD, false)
                    startRecording()
                }
                ACTION_STOP -> stopRecording()
                ACTION_PAUSE -> pauseRecording()
                ACTION_RESUME -> resumeRecording()
                ACTION_RETRY_UPLOAD -> {
                    apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                    jargon = intent.getStringExtra(EXTRA_JARGON) ?: ""
                    deferClipboard = intent.getBooleanExtra(EXTRA_DEFER_CLIPBOARD, false)
                    retryUpload()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return
        
        createNotificationChannel()
        
        val notification = buildRecordingNotification(paused = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(RECORDING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(RECORDING_NOTIFICATION_ID, notification)
        }

        // A new take supersedes any unclaimed previous result.
        prefs().edit().remove(PREF_PENDING_RESULT_ID).apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(RESULT_NOTIFICATION_ID)

        // Unique name so a failed upload's audio survives the next recording (see retryUpload).
        audioFile = File(
            externalCacheDir,
            "$AUDIO_FILE_PREFIX${System.currentTimeMillis()}$AUDIO_FILE_SUFFIX"
        )

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Whisper resamples to 16 kHz mono anyway, so matching it costs no accuracy and
            // cuts the upload to roughly 14 MB/hour — the difference between a note landing
            // and a note timing out on a weak site connection.
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(audioFile?.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                setRecordingFlag(true)
                isPaused = false
                accumulatedActiveMillis = 0L
                segmentStartMillis = android.os.SystemClock.elapsedRealtime()
                startTimeMillis = segmentStartMillis
                broadcastState(STATE_RECORDING)
            } catch (e: Exception) {
                e.printStackTrace()
                setRecordingFlag(false)
                broadcastState(STATE_ERROR, "Failed to start recording")
                stopSelf()
            }
        }
    }

    private fun buildRecordingNotification(paused: Boolean): Notification {
        val stopPendingIntent = servicePendingIntent(ACTION_STOP, requestCode = 0)
        val builder = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle("Stow")
            .setContentText(if (paused) "Recording paused" else "Recording in progress...")
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (paused) {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    servicePendingIntent(ACTION_RESUME, requestCode = 2)
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    servicePendingIntent(ACTION_PAUSE, requestCode = 1)
                )
            }
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Recording", stopPendingIntent)
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            mediaRecorder?.pause()
            isPaused = true
            accumulatedActiveMillis += android.os.SystemClock.elapsedRealtime() - segmentStartMillis
            updateRecordingNotification(paused = true)
            broadcastState(STATE_PAUSED, durationSeconds = activeSeconds())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            mediaRecorder?.resume()
            isPaused = false
            segmentStartMillis = android.os.SystemClock.elapsedRealtime()
            updateRecordingNotification(paused = false)
            broadcastState(STATE_RESUMED, durationSeconds = activeSeconds())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateRecordingNotification(paused: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RECORDING_NOTIFICATION_ID, buildRecordingNotification(paused))
    }

    /** Elapsed recording time with paused stretches excluded. */
    private fun activeSeconds(): Int {
        val live = if (isPaused) 0L else android.os.SystemClock.elapsedRealtime() - segmentStartMillis
        return ((accumulatedActiveMillis + live) / 1000).toInt()
    }

    private fun stopRecording() {
        if (!isRecording) return
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            val durationSeconds = activeSeconds()
            mediaRecorder = null
            isRecording = false
            setRecordingFlag(false)
            isPaused = false

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(RECORDING_NOTIFICATION_ID, buildUploadingNotification())

            broadcastState(STATE_LOADING)

            audioFile?.let {
                sendAudioToGroq(it, durationSeconds)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            setRecordingFlag(false)
            broadcastState(STATE_ERROR, "Failed to stop recording")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun buildUploadingNotification(): Notification =
        NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle("Stow")
            .setContentText("Uploading and Transcribing...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    /** Remembers a failed upload so the audio can be re-sent instead of thrown away. */
    private fun recordUploadFailure(file: File, durationSeconds: Int) {
        if (!file.exists()) return
        prefs().edit()
            .putString(PREF_FAILED_AUDIO_PATH, file.absolutePath)
            .putInt(PREF_FAILED_AUDIO_DURATION, durationSeconds)
            .apply()
    }

    private fun clearUploadFailure() {
        prefs().edit()
            .remove(PREF_FAILED_AUDIO_PATH)
            .remove(PREF_FAILED_AUDIO_DURATION)
            .apply()
    }

    /** Drops stale recordings from the cache, keeping any that a retry still needs. */
    private fun cleanupAudioCache(keepPath: String?) {
        try {
            externalCacheDir?.listFiles()?.forEach { candidate ->
                if (candidate.name.startsWith(AUDIO_FILE_PREFIX) &&
                    candidate.name.endsWith(AUDIO_FILE_SUFFIX) &&
                    candidate.absolutePath != keepPath
                ) {
                    candidate.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun retryUpload() {
        val path = prefs().getString(PREF_FAILED_AUDIO_PATH, null)
        val duration = prefs().getInt(PREF_FAILED_AUDIO_DURATION, 0)
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            clearUploadFailure()
            broadcastState(STATE_ERROR, "Saved audio is no longer available")
            stopSelf()
            return
        }

        createNotificationChannel()
        // dataSync, not microphone: nothing is being recorded on this path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RECORDING_NOTIFICATION_ID,
                buildUploadingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(RECORDING_NOTIFICATION_ID, buildUploadingNotification())
        }
        broadcastState(STATE_LOADING)
        sendAudioToGroq(file, duration)
    }

    private fun sendAudioToGroq(file: File, durationSeconds: Int, attempt: Int = 0) {
        if (!isNetworkAvailable()) {
            recordUploadFailure(file, durationSeconds)
            broadcastState(STATE_ERROR, "Error: No active internet connection found.")
            stopForeground(true)
            stopSelf()
            return
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            val megabytes = file.length().toDouble() / (1024 * 1024)
            broadcastState(
                STATE_ERROR,
                String.format(
                    Locale.getDefault(),
                    "Recording too large to upload (%.1f MB — limit ~25 MB). The audio was kept; try splitting long recordings.",
                    megabytes
                )
            )
            recordUploadFailure(file, durationSeconds)
            stopForeground(true)
            stopSelf()
            return
        }

        // Only the user's own vocabulary is sent. The Whisper prompt field is capped around
        // 224 tokens, so a generic hardcoded seed used to crowd out the terms that actually
        // matter for this user's work.
        val prompt = jargon.trim()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")
            .apply {
                if (prompt.isNotEmpty()) {
                    addFormDataPart("prompt", prompt)
                }
            }
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // One automatic retry: field connectivity drops out for a moment far more
                // often than it is genuinely unavailable. Do not stopSelf here — the service
                // has to stay alive to make the second attempt.
                if (attempt == 0) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        sendAudioToGroq(file, durationSeconds, attempt = 1)
                    }, RETRY_DELAY_MILLIS)
                    return
                }
                recordUploadFailure(file, durationSeconds)
                broadcastState(STATE_ERROR, "Error: ${e.message}")
                stopForeground(true)
                stopSelf()
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonObject = JSONObject(responseBody)
                        val text = jsonObject.getString("text")
                        
                        clearUploadFailure()
                        cleanupAudioCache(keepPath = null)

                        // Save history here, not in the UI: the activity may already be stopped
                        // (screen off, another app) and would never receive the broadcast, which
                        // used to lose the transcription outright. MainActivity updates this same
                        // entry with polished/edited text later.
                        val entry = TranscriptionHistory.add(
                            context = this@RecordingService,
                            rawText = text,
                            polishedText = null,
                            durationSeconds = durationSeconds
                        )
                        if (entry != null) {
                            prefs().edit().putString(PREF_PENDING_RESULT_ID, entry.id).apply()
                        }

                        if (!deferClipboard) {
                            copyToClipboard(text)
                        }

                        val newTotalUsage = updateUsage(durationSeconds)

                        broadcastState(STATE_SUCCESS, text, newTotalUsage, durationSeconds, entry?.id)

                        // Clipboard writes are blocked for background apps on Android 10+, so a
                        // notification is the only way to hand the result back when the UI is gone.
                        if (entry != null && !prefs().getBoolean(PREF_UI_VISIBLE, false)) {
                            showResultReadyNotification(text)
                        }
                    } catch (e: Exception) {
                        recordUploadFailure(file, durationSeconds)
                        broadcastState(STATE_ERROR, "Error parsing response")
                    }
                } else {
                    // Keep the audio: a 429 or a transient 5xx is worth retrying by hand.
                    recordUploadFailure(file, durationSeconds)
                    broadcastState(STATE_ERROR, "API Error: ${response.code}\n$responseBody")
                }
                stopForeground(true)
                stopSelf()
            }
        })
    }

    private fun broadcastState(
        state: String,
        text: String? = null,
        usage: Int = -1,
        durationSeconds: Int = -1,
        entryId: String? = null
    ) {
        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            if (text != null) {
                putExtra(EXTRA_TEXT, text)
            }
            if (usage != -1) {
                putExtra(EXTRA_USAGE, usage)
            }
            if (durationSeconds >= 0) {
                putExtra(EXTRA_DURATION, durationSeconds)
            }
            if (entryId != null) {
                putExtra(EXTRA_ENTRY_ID, entryId)
            }
        }
        sendBroadcast(intent)
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Committed synchronously so the tile never reads a stale value right after a tap. */
    @Suppress("ApplySharedPref")
    private fun setRecordingFlag(recording: Boolean) {
        prefs().edit().putBoolean(PREF_IS_RECORDING, recording).commit()
    }

    /** Tappable hand-off when the transcription finished with no UI on screen. */
    private fun showResultReadyNotification(text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val preview = text.replace('\n', ' ').trim().let {
            if (it.length <= 80) it else it.take(80).trimEnd() + "…"
        }

        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("Transcription ready")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    RECORDING_CHANNEL_ID,
                    "Stow Recording Channel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            // Default importance: this one has to be noticeable, since it fires only when
            // the app is not on screen and is the user's only route back to the result.
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Stow Transcription Ready",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun updateUsage(durationSeconds: Int): Int {
        val prefs = getSharedPreferences("StowPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString("LastRecordedDate", "")

        var currentTotal = prefs.getInt("DailyUsageSeconds", 0)

        if (todayDate != lastDate) {
            currentTotal = 0
        }

        currentTotal += durationSeconds

        prefs.edit()
            .putString("LastRecordedDate", todayDate)
            .putInt("DailyUsageSeconds", currentTotal)
            .apply()
            
        return currentTotal
    }
}
