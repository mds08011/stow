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

        private const val RECORDING_NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2
        private const val RECORDING_CHANNEL_ID = "StowChannel"
        private const val RESULT_CHANNEL_ID = "StowResultChannel"
        
        const val STATE_RECORDING = "ACTION_RECORDING_STARTED"
        const val STATE_LOADING = "ACTION_RECORDING_STOPPED"
        const val STATE_SUCCESS = "ACTION_RECORDING_SUCCESS"
        const val STATE_ERROR = "ACTION_RECORDING_ERROR"
        const val STATE_STOPPED = "STATE_STOPPED"
    }

    private var apiKey: String = ""
    private var jargon: String = ""
    private var deferClipboard: Boolean = false

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
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return
        
        createNotificationChannel()
        
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val notification: Notification = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle("Stow")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(RECORDING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(RECORDING_NOTIFICATION_ID, notification)
        }

        // A new take supersedes any unclaimed previous result.
        prefs().edit().remove(PREF_PENDING_RESULT_ID).apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(RESULT_NOTIFICATION_ID)

        audioFile = File(externalCacheDir, "audio_record.m4a")

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFile?.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                startTimeMillis = android.os.SystemClock.elapsedRealtime()
                broadcastState(STATE_RECORDING)
            } catch (e: Exception) {
                e.printStackTrace()
                broadcastState(STATE_ERROR, "Failed to start recording")
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification: Notification = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
                .setContentTitle("Stow")
                .setContentText("Uploading and Transcribing...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .build()
            notificationManager.notify(RECORDING_NOTIFICATION_ID, notification)
            
            broadcastState(STATE_LOADING)

            val durationMillis = android.os.SystemClock.elapsedRealtime() - startTimeMillis
            val durationSeconds = (durationMillis / 1000).toInt()

            audioFile?.let {
                sendAudioToGroq(it, durationSeconds)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            broadcastState(STATE_ERROR, "Failed to stop recording")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun sendAudioToGroq(file: File, durationSeconds: Int) {
        if (!isNetworkAvailable()) {
            broadcastState(STATE_ERROR, "Error: No active internet connection found.")
            stopForeground(true)
            stopSelf()
            return
        }

        var prompt = "CAD, HVAC, structural load, thermodynamic, schematic"
        if (jargon.isNotEmpty()) {
            prompt += ", $jargon"
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("prompt", prompt)
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
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
                        
                        if (file.exists()) {
                            file.delete()
                        }

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
                        broadcastState(STATE_ERROR, "Error parsing response")
                    }
                } else {
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
