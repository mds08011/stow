package io.github.mds08011.stow

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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The newest start command seen. See [stopIfIdle]. */
    private var lastStartId = 0
    /**
     * Notes that landed while a newer recording was under way, and so were never shown.
     * Counted into the next result's "earlier ones are in History" message.
     */
    private var landedUnseen = 0
    /** Guards the hand-off between "is an upload running" and "what does it send next". */
    private val chainLock = Any()
    private val wakeLockGuard = Any()
    private var uploadWakeLock: android.os.PowerManager.WakeLock? = null
    /** Microphone and sample rate per recording, held from stop until its note is saved. */
    private val routes = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Int?>>()
    // Shared with the re-transcribe path for its timeouts. See AudioTranscriber.client.
    private val client get() = AudioTranscriber.client
    @Volatile
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
        
        const val BROADCAST_STATE = "io.github.mds08011.stow.STATE_UPDATE"
        const val EXTRA_STATE = "EXTRA_STATE"
        const val EXTRA_TEXT = "EXTRA_TEXT"
        const val EXTRA_USAGE = "EXTRA_USAGE"
        const val EXTRA_DURATION = "EXTRA_DURATION"
        /** Id of the history entry the service already saved for this transcription. */
        const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"
        /** The microphone actually routed to, resolved after start() rather than guessed. */
        const val EXTRA_ROUTE_LABEL = "EXTRA_ROUTE_LABEL"
        const val EXTRA_ROUTE_SAMPLE_RATE = "EXTRA_ROUTE_SAMPLE_RATE"

        const val PREFS_NAME = "StowPrefs"
        /** Set when a transcription was saved but no UI has shown it yet. */
        const val PREF_PENDING_RESULT_ID = "pending_result_id"
        /** MainActivity keeps this current so the service knows whether to notify. */
        const val PREF_UI_VISIBLE = "ui_visible"
        /**
         * The single failed upload that versions before 2.8.4 remembered. Read once by
         * [UploadQueue.load], which folds it into the queue, and never written again.
         */
        const val PREF_FAILED_AUDIO_PATH = "failed_audio_path"
        const val PREF_FAILED_AUDIO_DURATION = "failed_audio_duration"
        /** On STATE_SUCCESS: how many notes this run of retries transcribed, when over one. */
        const val EXTRA_BATCH_COUNT = "EXTRA_BATCH_COUNT"
        /** Epoch millis before which a retry would just burn another rate-limit slot. */
        const val PREF_FAILED_RETRY_AT = "failed_retry_at"

        /**
         * Set the moment recording stops, before the first upload attempt. Survives the
         * process being killed mid-upload, which is the one window where audio exists on
         * disk but nothing in the app points at it. Cleared on success, and on a clean
         * failure, when the recording joins [UploadQueue] instead.
         */
        const val PREF_PENDING_AUDIO_PATH = "pending_audio_path"
        const val PREF_PENDING_AUDIO_DURATION = "pending_audio_duration"
        const val PREF_PENDING_AUDIO_AT = "pending_audio_at"

        // --- Live state -----------------------------------------------------------
        //
        // What the service is doing right now, for the two things that cannot rely on
        // having heard every broadcast: MainActivity, whose receiver is unregistered
        // whenever the screen is off or another app is in front, and the Quick Settings
        // tile, which has no binding to this service.
        //
        // Held in memory on purpose. The activity, the tile and the service share one
        // process, so if that process dies this resets to idle with it. The preference
        // flag this replaces (`is_recording`) survived the process and could say
        // "recording" forever after a crash.

        const val LIVE_IDLE = "idle"
        const val LIVE_RECORDING = "recording"
        const val LIVE_PAUSED = "paused"
        const val LIVE_UPLOADING = "uploading"

        @Volatile
        var liveState: String = LIVE_IDLE
            private set
        @Volatile
        private var liveAccumulatedMillis = 0L
        @Volatile
        private var liveSegmentStartElapsed = 0L
        @Volatile
        var liveRouteLabel: String? = null
            private set
        @Volatile
        var liveRouteSampleRate: Int? = null
            private set

        /**
         * The last error broadcast, kept until a screen has shown it. An upload that fails
         * while Stow is in the background broadcasts into an unregistered receiver.
         * MainActivity reads this on its way back and clears it.
         */
        @Volatile
        var liveLastError: String? = null

        /**
         * True while an upload, or a run of them, is in flight. Separate from [liveState]
         * because a new recording can be started while the last one is still uploading,
         * and then the state reads "recording".
         */
        @Volatile
        var uploadInFlight = false
            private set

        /** True from start to stop, paused or not. */
        fun isCapturing(): Boolean = liveState == LIVE_RECORDING || liveState == LIVE_PAUSED

        /** Recorded time so far with paused stretches excluded. This is what the timer shows. */
        fun liveActiveMillis(
            nowElapsed: Long = android.os.SystemClock.elapsedRealtime()
        ): Long = activeMillis(liveState, liveAccumulatedMillis, liveSegmentStartElapsed, nowElapsed)

        /** Pure form of [liveActiveMillis], separate so it can be unit tested. */
        internal fun activeMillis(
            state: String,
            accumulatedMillis: Long,
            segmentStartElapsed: Long,
            nowElapsed: Long
        ): Long = when (state) {
            LIVE_RECORDING -> accumulatedMillis + (nowElapsed - segmentStartElapsed).coerceAtLeast(0L)
            LIVE_PAUSED -> accumulatedMillis
            else -> 0L
        }

        /** Groq's free tier rejects larger uploads; stay clear of the 25 MB ceiling. */
        private const val MAX_UPLOAD_BYTES = 24L * 1024 * 1024
        const val AUDIO_FILE_PREFIX = "audio_"
        const val AUDIO_FILE_SUFFIX = ".m4a"
        private const val RETRY_DELAY_MILLIS = 2000L
        /** Longer than AudioTranscriber's 5-minute read timeout; renewed per upload. */
        private const val UPLOAD_WAKELOCK_TIMEOUT_MILLIS = 6L * 60 * 1000

        /** Longest wait worth counting down before telling the user to come back later. */
        private const val MAX_RETRY_WAIT_SECONDS = 900L
        private const val MIN_RETRY_WAIT_SECONDS = 3L
        /** Fallbacks when the server says a retry is worth attempting but not when. */
        private const val DEFAULT_RATE_LIMIT_WAIT_SECONDS = 12L
        private const val DEFAULT_SERVER_ERROR_WAIT_SECONDS = 6L

        /** Matches Groq's prose form: "try again in 7.66s" or "try again in 2m59.56s". */
        private val RETRY_PROSE = Regex("""try again in (?:(\d+)m)?([\d.]+)s""", RegexOption.IGNORE_CASE)

        /**
         * How long until a retry could actually succeed.
         *
         * Groq answers this two ways and neither is reliable alone: a `Retry-After`
         * header, and prose in the error body. Take whichever is larger, then round up —
         * retrying a moment early just burns another slot. Ported from Stow Web, which
         * hit this first; see docs/parity.md.
         *
         * Returns 0 when a retry is pointless (4xx that is not 429).
         */
        fun retryAfterSeconds(headerValue: String?, body: String?, status: Int): Long {
            val retryable = status == 429 || status >= 500
            if (!retryable) return 0

            var wait = headerValue?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

            RETRY_PROSE.find(body.orEmpty())?.let { m ->
                val minutes = m.groupValues[1].toLongOrNull() ?: 0L
                val seconds = m.groupValues[2].toDoubleOrNull() ?: 0.0
                wait = maxOf(wait, minutes * 60 + seconds)
            }

            if (wait <= 0.0) {
                wait = if (status == 429) {
                    DEFAULT_RATE_LIMIT_WAIT_SECONDS.toDouble()
                } else {
                    DEFAULT_SERVER_ERROR_WAIT_SECONDS.toDouble()
                }
            }
            return Math.ceil(wait).toLong()
                .coerceAtLeast(MIN_RETRY_WAIT_SECONDS)
                .coerceAtMost(MAX_RETRY_WAIT_SECONDS)
        }

        /** Turns a non-2xx into something worth reading, rather than a status plus JSON. */
        fun describeApiError(status: Int, body: String?): String = when {
            status == 401 || status == 403 ->
                "Your Groq API key was rejected. Check it in Settings."
            status == 413 ->
                "Recording is too large for Groq to accept (limit ~25 MB)."
            status == 429 ->
                "Groq rate limit reached. The audio was kept — retry when the timer runs out."
            status >= 500 ->
                "Groq had a server error ($status). The audio was kept; try again shortly."
            else ->
                "API error $status" + (body?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty())
        }

        /** Requested capture rate. Reported as-is; MediaRecorder may not honour it. */
        const val CAPTURE_SAMPLE_RATE = 16000
        /** getRoutedDevice() is briefly null right after start(); retry once. */
        private const val ROUTE_RETRY_DELAY_MILLIS = 150L
        private const val TAG = "StowAudio"

        /** Raw verbose_json bodies, kept for troubleshooting a bad transcription. */
        const val RESPONSES_DIR = "responses"
        private const val MAX_STORED_RESPONSES = 20

        /**
         * Recent recordings are kept so a bad transcription can be re-run or shared.
         * A missing file is normal, never an error: they are pruned after a few notes.
         */
        const val MAX_RETAINED_AUDIO = 5

        /** Folder under filesDir that recordings are written to. See [audioDir]. */
        const val AUDIO_DIR_NAME = "audio"

        /**
         * Where recordings are written.
         *
         * App-private internal storage, which Android never clears on its own. Until
         * v2.8.4 this was externalCacheDir, which the system may empty whenever the phone
         * runs short of space, and a recording that has not been transcribed yet is the
         * one file Stow cannot afford to lose that way.
         *
         * filesDir is covered by Android's automatic backup where the cache was not, so
         * res/xml/backup_rules.xml and data_extraction_rules.xml exclude this folder.
         */
        fun audioDir(context: Context): File =
            File(context.filesDir, AUDIO_DIR_NAME).apply { mkdirs() }

        /**
         * Every recording on the device: the current folder, plus the cache folder that
         * versions before 2.8.4 recorded into, whose files history entries still point at.
         */
        fun audioFiles(context: Context): List<File> = try {
            listOfNotNull(audioDir(context), context.externalCacheDir).flatMap { dir ->
                dir.listFiles { f ->
                    f.name.startsWith(AUDIO_FILE_PREFIX) && f.name.endsWith(AUDIO_FILE_SUFFIX)
                }?.toList().orEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        /**
         * Paths of recordings that have not been transcribed yet: every upload waiting
         * for a retry and the one that was in flight. Nothing that tidies up may delete these.
         */
        fun unsentAudioPaths(prefs: SharedPreferences): Set<String> =
            UploadQueue.load(prefs).map { it.path }.toSet() +
                setOfNotNull(prefs.getString(PREF_PENDING_AUDIO_PATH, null))
        private const val AUDIO_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

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
        /** Follow-up once the routed device is known, so STATE_RECORDING stays instant. */
        const val STATE_ROUTE = "STATE_ROUTE"
    }

    /** Recordings still to send in the current run of retries, after the one in flight. */
    private val retryRun = java.util.concurrent.ConcurrentLinkedDeque<UploadQueue.Item>()
    /** Notes transcribed so far in the current run. */
    @Volatile
    private var runSuccesses = 0

    @Volatile
    private var apiKey: String = ""
    @Volatile
    private var jargon: String = ""
    @Volatile
    private var deferClipboard: Boolean = false

    @Volatile
    private var isPaused = false
    /** Recording time excluding paused stretches, so usage and duration stay honest. */
    private var accumulatedActiveMillis = 0L
    private var segmentStartMillis = 0L

    /** The microphone this recording actually used; stored on the history entry. */
    private var routeLabel: String? = null
    private var routeSampleRate: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
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
            // A stop, pause or resume that found nothing to act on, for instance from a
            // screen whose state was stale. Do not leave the service started for it.
            when (intent.action) {
                ACTION_STOP, ACTION_PAUSE, ACTION_RESUME -> stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    /** Copies this recording's clock into the companion so other components can read it. */
    private fun publishLive(state: String) {
        liveAccumulatedMillis = accumulatedActiveMillis
        liveSegmentStartElapsed = segmentStartMillis
        liveState = state
    }

    /** Runs [block] on the main thread: now if already there, otherwise posted. */
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Works the live state out from what the service is actually doing. Recording wins
     * over uploading, because the two can overlap and the recording is what the screen
     * and the tile have to show. Main thread only, like everything else that writes
     * [liveState], so a network thread cannot overwrite a recording that just started.
     */
    private fun settleLiveState() {
        liveState = when {
            isRecording && isPaused -> LIVE_PAUSED
            isRecording -> LIVE_RECORDING
            uploadInFlight -> LIVE_UPLOADING
            else -> LIVE_IDLE
        }
    }

    /**
     * Stops the service only when it is neither recording nor uploading. Main thread only.
     *
     * stopSelf(startId), not stopSelf(): if a newer start command is already on its way
     * (Start tapped as an upload ends), Android then keeps the service alive for it. A
     * bare stopSelf() would destroy the service straight after that command had started
     * the recorder.
     */
    private fun stopIfIdle() {
        if (isRecording || uploadInFlight) return
        stopForeground(true)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        // Only reachable mid-recording if the system destroys the service without killing
        // the process. Release the recorder so the tile and the activity are not left
        // showing a recording that nothing is making.
        if (isRecording) {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mediaRecorder = null
            isRecording = false
            isPaused = false
            settleLiveState()
        }
        releaseUploadWakeLock()
        super.onDestroy()
    }

    private fun acquireUploadWakeLock() {
        synchronized(wakeLockGuard) {
            try {
                val wl = uploadWakeLock
                    ?: (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Stow:Upload")
                        .apply { setReferenceCounted(false) }
                        .also { uploadWakeLock = it }
                wl.acquire(UPLOAD_WAKELOCK_TIMEOUT_MILLIS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseUploadWakeLock() {
        synchronized(wakeLockGuard) {
            try {
                uploadWakeLock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
            audioDir(this),
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
            setAudioSamplingRate(CAPTURE_SAMPLE_RATE)
            setAudioEncodingBitRate(32000)
            setOutputFile(audioFile?.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                isPaused = false
                accumulatedActiveMillis = 0L
                segmentStartMillis = android.os.SystemClock.elapsedRealtime()
                startTimeMillis = segmentStartMillis
                routeLabel = null
                routeSampleRate = null
                liveRouteLabel = null
                liveRouteSampleRate = null
                publishLive(LIVE_RECORDING)
                broadcastState(STATE_RECORDING)
                resolveRoutedDevice(retry = true)
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    release()
                } catch (_: Exception) {
                }
                mediaRecorder = null
                audioFile?.takeIf { it.exists() && it.length() == 0L }?.delete()
                audioFile = null
                broadcastState(STATE_ERROR, "Failed to start recording")
                // An upload may still be running, and it needs the service. Its
                // notification was replaced by the recording one a moment ago.
                restoreUploadingNotification()
                stopIfIdle()
            }
        }
    }

    /**
     * Asks MediaRecorder which input device it is actually using, rather than inferring it
     * from what happens to be connected. The previous indicator enumerated available input
     * devices and so reported "Bluetooth Mic" whenever a headset was merely paired, while
     * the built-in mic did the recording — see docs/audio-investigation-2026-07.md.
     */
    private fun resolveRoutedDevice(retry: Boolean) {
        val device = try {
            mediaRecorder?.routedDevice
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (device == null) {
            if (retry && isRecording) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { if (isRecording) resolveRoutedDevice(retry = false) },
                    ROUTE_RETRY_DELAY_MILLIS
                )
            } else if (isRecording) {
                // Never guess. An unknown route is reported as unknown.
                routeLabel = null
                routeSampleRate = CAPTURE_SAMPLE_RATE
                broadcastRoute()
            }
            return
        }

        routeLabel = describeDevice(device)
        routeSampleRate = CAPTURE_SAMPLE_RATE

        val deviceRates = try {
            device.sampleRates?.joinToString(",")?.ifEmpty { "unspecified" } ?: "unspecified"
        } catch (e: Exception) {
            "unavailable"
        }
        android.util.Log.i(
            TAG,
            "routed input: type=${device.type} label=$routeLabel " +
                "requestedRate=$CAPTURE_SAMPLE_RATE deviceRates=$deviceRates"
        )

        broadcastRoute()
    }

    private fun describeDevice(device: android.media.AudioDeviceInfo): String {
        val base = when (device.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            else -> "Other (type ${device.type})"
        }
        // The built-in mic's product name is just the phone model, which adds nothing.
        val product = try {
            device.productName?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
        return if (!product.isNullOrEmpty() &&
            device.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
        ) {
            "$base · $product"
        } else {
            base
        }
    }

    private fun broadcastRoute() {
        liveRouteLabel = routeLabel
        liveRouteSampleRate = routeSampleRate
        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, STATE_ROUTE)
            routeLabel?.let { putExtra(EXTRA_ROUTE_LABEL, it) }
            routeSampleRate?.let { putExtra(EXTRA_ROUTE_SAMPLE_RATE, it) }
        }
        sendBroadcast(intent)
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
            publishLive(LIVE_PAUSED)
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
            publishLive(LIVE_RECORDING)
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
            isPaused = false

            val file = audioFile
            if (file == null) {
                settleLiveState()
                stopIfIdle()
                return
            }
            routes[file.absolutePath] = routeLabel to routeSampleRate

            // One upload at a time. If the last note is still on its way, this one joins
            // the queue behind it and the same run sends it. Starting a second upload
            // beside the first is what used to let one of them stop the service under
            // the other.
            val joined = synchronized(chainLock) {
                if (uploadInFlight) {
                    val item = UploadQueue.Item(
                        file.absolutePath,
                        durationSeconds,
                        System.currentTimeMillis()
                    )
                    UploadQueue.update(prefs()) { UploadQueue.addOrMoveToBack(it, item) }
                    retryRun.add(item)
                    true
                } else {
                    uploadInFlight = true
                    retryRun.clear()
                    runSuccesses = 0
                    false
                }
            }
            settleLiveState()
            broadcastState(STATE_LOADING)

            if (!joined) {
                // Checkpoint before the upload, not after it fails. A recording that
                // joined the queue is already on record there.
                try {
                    markRecordingPending(file, durationSeconds)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                sendAudioToGroq(file, durationSeconds)
            }
            restoreUploadingNotification()
        } catch (e: Exception) {
            // Reached only when the recorder itself failed to stop, before any upload
            // was claimed, so there is no run of this call's to unwind.
            e.printStackTrace()
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            audioFile?.takeIf { it.exists() && it.length() == 0L }?.delete()
            audioFile = null
            isRecording = false
            isPaused = false
            settleLiveState()
            broadcastState(STATE_ERROR, "Failed to stop recording")
            restoreUploadingNotification()
            stopIfIdle()
        }
    }

    /** Puts the uploading notification back when an upload is all that is going on. */
    private fun restoreUploadingNotification() {
        if (isRecording || !uploadInFlight) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(RECORDING_NOTIFICATION_ID, buildUploadingNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildUploadingNotification(): Notification =
        NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle("Stow")
            .setContentText("Uploading and Transcribing...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    /**
     * Remembers a failed upload so the audio can be re-sent instead of thrown away. It
     * joins the queue, or moves to its back if it was already waiting. Clears the pending
     * marker for the same file: a clean failure is a known state, and a recording must
     * not be offered as both a retry and an unfinished recording.
     */
    private fun recordUploadFailure(file: File, durationSeconds: Int, retryAfterSeconds: Long = 0) {
        if (!file.exists()) return
        val prefs = prefs()
        val item = UploadQueue.Item(file.absolutePath, durationSeconds, System.currentTimeMillis())
        UploadQueue.update(prefs) { UploadQueue.addOrMoveToBack(it, item) }
        clearRecordingPending(file)
        val edit = prefs.edit()
        if (retryAfterSeconds > 0) {
            edit.putLong(PREF_FAILED_RETRY_AT, System.currentTimeMillis() + retryAfterSeconds * 1000)
        } else {
            edit.remove(PREF_FAILED_RETRY_AT)
        }
        edit.apply()
    }

    /** A recording that was transcribed leaves the queue. Groq answered, so no wait applies. */
    private fun clearUploadFailure(file: File) {
        val prefs = prefs()
        UploadQueue.update(prefs) { UploadQueue.remove(it, file.absolutePath) }
        prefs.edit().remove(PREF_FAILED_RETRY_AT).apply()
    }

    /**
     * One failed upload: keep the audio, say why, and stop. A failure ends the whole run,
     * because whatever stopped this recording (no signal, a rate limit, a rejected key)
     * would stop the ones behind it too. They stay in the queue.
     */
    private fun failUpload(
        file: File,
        durationSeconds: Int,
        message: String,
        retryAfterSeconds: Long = 0
    ) {
        // On the calling thread, at once: the audio is on record and the run is over.
        recordUploadFailure(file, durationSeconds, retryAfterSeconds)
        val landed = runSuccesses
        synchronized(chainLock) {
            retryRun.clear()
            uploadInFlight = false
        }
        releaseUploadWakeLock()
        // On the main thread, where recordings start and stop, so that "is a recording
        // under way" cannot change between asking and acting on the answer.
        onMain {
            val earlierCount = landed + landedUnseen
            landedUnseen = 0
            val earlier = when (earlierCount) {
                0 -> ""
                1 -> "1 earlier note was transcribed and saved to History.\n"
                else -> "$earlierCount earlier notes were transcribed and saved to History.\n"
            }
            settleLiveState()
            if (isRecording || uploadInFlight) {
                // A newer recording, or its upload, has the screen. It is not
                // interrupted. MainActivity shows the reason with the next result or
                // on its next return, and the Retry button will be there.
                liveLastError = earlier + message
            } else {
                broadcastState(STATE_ERROR, earlier + message)
            }
            stopIfIdle()
        }
    }

    /**
     * Checkpoints a recording the instant it stops, before any upload is attempted.
     * If the process is killed mid-upload — the one case a failure callback cannot cover —
     * this is what lets the next launch offer the audio back instead of stranding it.
     */
    @Suppress("ApplySharedPref")
    private fun markRecordingPending(file: File, durationSeconds: Int) {
        // There is one checkpoint. If it still names an earlier recording, that one was
        // cut off mid-upload and never claimed, and overwriting the pointer would orphan
        // its audio. It joins the queue first.
        val prefs = prefs()
        val earlier = prefs.getString(PREF_PENDING_AUDIO_PATH, null)
        if (earlier != null && earlier != file.absolutePath && File(earlier).exists()) {
            val item = UploadQueue.Item(
                earlier,
                prefs.getInt(PREF_PENDING_AUDIO_DURATION, 0),
                prefs.getLong(PREF_PENDING_AUDIO_AT, 0L)
            )
            UploadQueue.update(prefs) { UploadQueue.addOrMoveToBack(it, item) }
        }
        prefs.edit()
            .putString(PREF_PENDING_AUDIO_PATH, file.absolutePath)
            .putInt(PREF_PENDING_AUDIO_DURATION, durationSeconds)
            .putLong(PREF_PENDING_AUDIO_AT, System.currentTimeMillis())
            .commit()   // synchronous: the process may not survive to flush an apply()
    }

    /** Only for [file]: a retry of an older recording must not clear a newer checkpoint. */
    private fun clearRecordingPending(file: File) {
        if (prefs().getString(PREF_PENDING_AUDIO_PATH, null) != file.absolutePath) return
        prefs().edit()
            .remove(PREF_PENDING_AUDIO_PATH)
            .remove(PREF_PENDING_AUDIO_DURATION)
            .remove(PREF_PENDING_AUDIO_AT)
            .apply()
    }

    /**
     * Keeps the raw API response beside the note so a bad transcription can be analysed
     * later. Stored as a file rather than on the history entry, which is read on every
     * history operation and must stay small.
     */
    private fun storeRawResponse(entryId: String, body: String) {
        try {
            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), RESPONSES_DIR)
            if (!dir.exists() && !dir.mkdirs()) return
            File(dir, "$entryId.json").writeText(body)

            val stored = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
            if (stored.size > MAX_STORED_RESPONSES) {
                stored.sortedBy { it.lastModified() }
                    .take(stored.size - MAX_STORED_RESPONSES)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            // Diagnostics are never worth failing a transcription over.
            e.printStackTrace()
        }
    }

    /**
     * Retains the most recent few recordings instead of deleting on success, so a bad
     * transcription can be re-run or shared rather than being unreproducible. Anything
     * beyond the cap, or older than the retention window, is dropped — as is anything the
     * pending retry still needs, which is always kept.
     */
    private fun cleanupAudioCache(keepPath: String?) {
        try {
            val files = audioFiles(this)
            if (files.isEmpty()) return

            val cutoff = System.currentTimeMillis() - AUDIO_RETENTION_MILLIS
            val keep = files
                .sortedByDescending { it.lastModified() }
                .take(MAX_RETAINED_AUDIO)
                .filter { it.lastModified() >= cutoff }
                .map { it.absolutePath }
                .toMutableSet()

            keepPath?.let { keep.add(it) }
            keep.addAll(unsentAudioPaths(prefs()))

            files.forEach { if (it.absolutePath !in keep) it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Sends everything in the queue, front first, one upload at a time. Each note is
     * saved to history as it lands. Only the last one is handed to the screen.
     */
    private fun retryUpload() {
        // Already sending. A second run over the same files would upload the one in
        // flight twice. The service is in the foreground already, so returning is safe.
        if (uploadInFlight) return

        createNotificationChannel()
        // Before anything that might stop the service: one started with
        // startForegroundService has to enter the foreground before it may stop.
        // dataSync, not microphone, because nothing is being recorded on this path. If a
        // recording is running, the service is in the foreground for that already.
        if (!isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    RECORDING_NOTIFICATION_ID,
                    buildUploadingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(RECORDING_NOTIFICATION_ID, buildUploadingNotification())
            }
        }

        val prefs = prefs()
        val present = UploadQueue.update(prefs) { queue -> queue.filter { File(it.path).exists() } }
        if (present.isEmpty()) {
            prefs.edit().remove(PREF_FAILED_RETRY_AT).apply()
            if (!isRecording) broadcastState(STATE_ERROR, "Saved audio is no longer available")
            stopIfIdle()
            return
        }

        // A recording over the size limit can never be sent as it is, so it does not get
        // to hold up the ones that can. If nothing else is waiting it goes first anyway,
        // and sendAudioToGroq reports why.
        val sendable = present.filter { File(it.path).length() <= MAX_UPLOAD_BYTES }
        val run = if (sendable.isEmpty()) present.take(1) else sendable
        val first = run.first()
        synchronized(chainLock) {
            uploadInFlight = true
            retryRun.clear()
            retryRun.addAll(run.drop(1))
            runSuccesses = 0
        }
        // Whatever an earlier failure said, this run is about to answer it afresh.
        liveLastError = null
        settleLiveState()
        if (!isRecording) broadcastState(STATE_LOADING)
        sendAudioToGroq(File(first.path), first.durationSeconds)
    }

    private fun sendAudioToGroq(file: File, durationSeconds: Int, attempt: Int = 0) {
        acquireUploadWakeLock()

        if (!isNetworkAvailable()) {
            failUpload(file, durationSeconds, "Error: No active internet connection found.")
            return
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            val megabytes = file.length().toDouble() / (1024 * 1024)
            failUpload(
                file,
                durationSeconds,
                String.format(
                    Locale.getDefault(),
                    "Recording too large to upload (%.1f MB — limit ~25 MB). The audio was kept; try splitting long recordings.",
                    megabytes
                )
            )
            return
        }

        // Shared with the re-transcribe path so the two cannot drift apart. Building it
        // can throw, for instance on a key with a character a header cannot carry, and
        // an exception here would otherwise leave the run marked as in flight for good.
        val request = try {
            AudioTranscriber.buildRequest(
                file = file,
                apiKey = apiKey,
                jargon = jargon,
                model = AudioTranscriber.MODEL_TURBO
            )
        } catch (e: Exception) {
            failUpload(file, durationSeconds, "Error: could not build the request. Check the API key in Settings.")
            return
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // One automatic retry: field connectivity drops out for a moment far more
                // often than it is genuinely unavailable. Do not stopSelf here — the service
                // has to stay alive to make the second attempt.
                if (attempt == 0) {
                    mainHandler.postDelayed({
                        sendAudioToGroq(file, durationSeconds, attempt = 1)
                    }, RETRY_DELAY_MILLIS)
                    return
                }
                failUpload(file, durationSeconds, "Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                // Once this method has been entered OkHttp never calls onFailure, so an
                // exception escaping from it would leave the service running with nothing
                // to end it. Every step below either finishes or fails the upload.
                val responseBody = try {
                    response.body?.string()
                } catch (e: Exception) {
                    failUpload(
                        file,
                        durationSeconds,
                        "Error: the connection dropped while reading Groq's answer."
                    )
                    return
                }

                if (!response.isSuccessful || responseBody == null) {
                    // Keep the audio: a 429 or a transient 5xx is worth retrying by hand,
                    // but not before the server says a retry could succeed.
                    val wait = retryAfterSeconds(
                        response.header("retry-after"),
                        responseBody,
                        response.code
                    )
                    failUpload(file, durationSeconds, describeApiError(response.code, responseBody), wait)
                    return
                }

                // Save history here, not in the UI: the activity may already be stopped
                // (screen off, another app) and would never receive the broadcast, which
                // used to lose the transcription outright. MainActivity updates this same
                // entry with polished/edited text later.
                val parsed: TranscriptionResponse.Parsed
                val entry: TranscriptionHistory.Entry?
                try {
                    parsed = TranscriptionResponse.parse(responseBody) ?: run {
                        failUpload(file, durationSeconds, "Error parsing response")
                        return
                    }
                    val route = routes[file.absolutePath]
                    entry = TranscriptionHistory.add(
                        context = this@RecordingService,
                        rawText = parsed.text,
                        polishedText = null,
                        durationSeconds = durationSeconds,
                        routeLabel = route?.first,
                        routeSampleRate = route?.second,
                        avgLogprob = parsed.avgLogprob,
                        maxNoSpeechProb = parsed.maxNoSpeechProb,
                        maxCompressionRatio = parsed.maxCompressionRatio,
                        audioPath = file.absolutePath
                    )
                } catch (e: Exception) {
                    // Nothing was saved, so the recording goes back in the queue.
                    failUpload(file, durationSeconds, "Error parsing response")
                    return
                }
                val text = parsed.text

                // The note is saved. From here nothing may send this recording back to
                // the queue: a retry would upload it again and save it a second time.
                var newTotalUsage = -1
                try {
                    routes.remove(file.absolutePath)
                    clearUploadFailure(file)
                    clearRecordingPending(file)
                    // Keep this recording explicitly: it is the one the note points at.
                    cleanupAudioCache(keepPath = file.absolutePath)
                    if (entry != null) {
                        prefs().edit().putString(PREF_PENDING_RESULT_ID, entry.id).apply()
                        storeRawResponse(entry.id, responseBody)
                        android.util.Log.i(
                            TAG,
                            "decode stats: avgLogprob=${parsed.avgLogprob} " +
                                "maxNoSpeech=${parsed.maxNoSpeechProb} " +
                                "maxCompression=${parsed.maxCompressionRatio} " +
                                "warning=${entry.qualityWarning()}"
                        )
                    }
                    newTotalUsage = updateUsage(durationSeconds)
                    // Ensure stow_history.json is flushed atomically before the service
                    // stops itself and Android can reclaim the idle process.
                    TranscriptionHistory.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                runSuccesses++

                // More waiting in the same run: move straight on. The screen hears about
                // the last note only. Taking the next one and declaring the run over
                // happen under one lock, so a recording that stops at this moment either
                // joins this run or starts its own.
                val next = synchronized(chainLock) {
                    var candidate = retryRun.poll()
                    while (candidate != null && !File(candidate.path).exists()) {
                        candidate = retryRun.poll()
                    }
                    if (candidate == null) uploadInFlight = false
                    candidate
                }
                if (next != null) {
                    sendAudioToGroq(File(next.path), next.durationSeconds)
                    return
                }
                releaseUploadWakeLock()

                // The run is over, and uploadInFlight is already false. The rest happens
                // on the main thread, for the same reason as in failUpload.
                val landed = runSuccesses
                onMain {
                    settleLiveState()
                    if (isRecording || uploadInFlight) {
                        // A newer recording, or its upload, has the screen. This note is
                        // in History. It is counted into the next result's message, and
                        // the notification says so now.
                        landedUnseen += landed
                        if (entry != null) showResultReadyNotification(text)
                    } else {
                        val batch = landed + landedUnseen
                        landedUnseen = 0
                        try {
                            if (!deferClipboard) {
                                copyToClipboard(text)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        broadcastState(
                            STATE_SUCCESS,
                            text,
                            newTotalUsage,
                            durationSeconds,
                            entry?.id,
                            batchCount = batch
                        )

                        // Clipboard writes are blocked for background apps on Android 10+,
                        // so a notification is the only way to hand the result back when
                        // the UI is gone.
                        if (entry != null && !prefs().getBoolean(PREF_UI_VISIBLE, false)) {
                            showResultReadyNotification(text)
                        }
                    }
                    stopIfIdle()
                }
            }
        })
    }

    private fun broadcastState(
        state: String,
        text: String? = null,
        usage: Int = -1,
        durationSeconds: Int = -1,
        entryId: String? = null,
        batchCount: Int = 0
    ) {
        // An error or a success changes what the service is doing, so the live state is
        // worked out again here, before the broadcast, and not at each exit.
        if (state == STATE_ERROR) {
            liveLastError = text
            settleLiveState()
        } else if (state == STATE_SUCCESS) {
            settleLiveState()
        }
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
            if (batchCount > 1) {
                putExtra(EXTRA_BATCH_COUNT, batchCount)
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
        // Capability-based rather than transport-based so VPN-only active networks
        // (Tailscale, WireGuard, corporate VPNs) are not misreported as offline.
        return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
