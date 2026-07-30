package io.github.mds08011.stow

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private val polisher = TranscriptionPolisher()

    private lateinit var btnRecord: Button
    private lateinit var etTranscription: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResultLabel: TextView
    private lateinit var resultHeaderRow: LinearLayout
    private lateinit var btnToggleVariant: Button
    private lateinit var resultActionRow: LinearLayout

    private lateinit var btnSettings: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var btnJargon: ImageButton
    private lateinit var tvMicIndicator: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var tvUsage: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnPolish: Button
    private lateinit var btnPolishPreset: Button
    private lateinit var btnPauseResume: Button
    private lateinit var btnRetryUpload: Button
    private lateinit var btnCopyResult: Button

    private var isRecording = false
    private var isPaused = false
    /** Route reported by the recorder for the current/most recent take. */
    private var lastRouteLabel: String? = null
    private var lastRouteSampleRate: Int? = null
    private var lastRawTranscription: String = ""
    private var lastDurationSeconds: Int? = null
    private var lastHistoryEntryId: String? = null
    /** True when the editable result field is showing the polished (or user-chosen polished) text. */
    private var resultShowsPolished = false
    private var isPolishing = false
    private var showingResult = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.BROADCAST_STATE) {
                val state = intent.getStringExtra(RecordingService.EXTRA_STATE)
                val text = intent.getStringExtra(RecordingService.EXTRA_TEXT)

                when (state) {
                    RecordingService.STATE_RECORDING -> {
                        isRecording = true
                        showingResult = false
                        btnRecord.text = "Stop Recording"
                        setStatus("Recording...")
                        etTranscription.setText("")
                        isPaused = false
                        btnRetryUpload.visibility = View.GONE
                        updatePauseButton()
                        btnPolish.isEnabled = false
                        lastRawTranscription = ""
                        lastDurationSeconds = null
                        lastHistoryEntryId = null
                        resultShowsPolished = false
                        showRecordingUi()

                        // The route is resolved from the recorder itself and arrives in a
                        // follow-up STATE_ROUTE broadcast; never inferred from what happens
                        // to be connected.
                        tvMicIndicator.text = "Mic: checking…"

                        chronometer.base = SystemClock.elapsedRealtime()
                        chronometer.start()
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_ROUTE -> {
                        lastRouteLabel = intent.getStringExtra(RecordingService.EXTRA_ROUTE_LABEL)
                        lastRouteSampleRate = intent
                            .getIntExtra(RecordingService.EXTRA_ROUTE_SAMPLE_RATE, -1)
                            .takeIf { it > 0 }
                        updateMicIndicator()
                    }
                    RecordingService.STATE_PAUSED -> {
                        isPaused = true
                        updatePauseButton()
                        setStatus("Recording paused")
                        // Freeze the display at the elapsed active time.
                        chronometer.stop()
                        val elapsed = intent.getIntExtra(RecordingService.EXTRA_DURATION, 0)
                        chronometer.base = SystemClock.elapsedRealtime() - elapsed * 1000L
                    }
                    RecordingService.STATE_RESUMED -> {
                        isPaused = false
                        updatePauseButton()
                        setStatus("Recording...")
                        val elapsed = intent.getIntExtra(RecordingService.EXTRA_DURATION, 0)
                        chronometer.base = SystemClock.elapsedRealtime() - elapsed * 1000L
                        chronometer.start()
                    }
                    RecordingService.STATE_LOADING -> {
                        isRecording = false
                        isPaused = false
                        updatePauseButton()
                        btnRecord.text = "Start Recording"
                        setStatus("Uploading and Transcribing...")
                        btnPolish.isEnabled = false
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_SUCCESS -> {
                        val transcription = text.orEmpty()
                        lastRawTranscription = transcription
                        val duration = intent.getIntExtra(RecordingService.EXTRA_DURATION, -1)
                        lastDurationSeconds = if (duration >= 0) duration else null

                        // The service already saved this note; adopt its entry rather than
                        // creating a second one, and mark the hand-off as claimed.
                        lastHistoryEntryId = intent.getStringExtra(RecordingService.EXTRA_ENTRY_ID)
                        clearPendingResult()
                        btnRetryUpload.visibility = View.GONE

                        val usage = intent.getIntExtra(RecordingService.EXTRA_USAGE, -1)
                        if (usage != -1) {
                            updateUsageText(usage)
                        }

                        if (isAutoPolishEnabled() && transcription.isNotBlank()) {
                            startPolish(transcription, autoTriggered = true)
                        } else {
                            showEditableResult(
                                displayText = transcription,
                                rawText = transcription,
                                polishedText = null,
                                saveHistory = true
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "Transcription copied — edit if needed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    RecordingService.STATE_ERROR -> {
                        isRecording = false
                        isPaused = false
                        updatePauseButton()
                        btnRecord.text = "Start Recording"
                        showingResult = false
                        showRecordingUi()
                        setStatus(text ?: "An error occurred")
                        refreshRetryButton()
                        btnPolish.isEnabled = false
                        lastRawTranscription = ""
                        lastDurationSeconds = null
                        lastHistoryEntryId = null
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("StowPrefs", Context.MODE_PRIVATE)

        btnRecord = findViewById(R.id.btnRecord)
        etTranscription = findViewById(R.id.etTranscription)
        tvStatus = findViewById(R.id.tvStatus)
        tvResultLabel = findViewById(R.id.tvResultLabel)
        resultHeaderRow = findViewById(R.id.resultHeaderRow)
        btnToggleVariant = findViewById(R.id.btnToggleVariant)
        resultActionRow = findViewById(R.id.resultActionRow)
        btnSettings = findViewById(R.id.btnSettings)
        btnInfo = findViewById(R.id.btnInfo)
        btnJargon = findViewById(R.id.btnJargon)
        tvMicIndicator = findViewById(R.id.tvMicIndicator)
        chronometer = findViewById(R.id.chronometer)
        tvUsage = findViewById(R.id.tvUsage)
        tvVersion = findViewById(R.id.tvVersion)
        btnHistory = findViewById(R.id.btnHistory)
        btnPolish = findViewById(R.id.btnPolish)
        btnPolishPreset = findViewById(R.id.btnPolishPreset)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnRetryUpload = findViewById(R.id.btnRetryUpload)
        btnCopyResult = findViewById(R.id.btnCopyResult)

        updatePresetButton()
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        loadInitialUsage()
        showRecordingUi()
        setStatus("")

        if (getApiKey().isNullOrEmpty()) {
            showApiKeyDialog()
        } else {
            // Only once the app is actually usable — stacking two dialogs on first launch
            // would bury the API key prompt.
            maybeAskBatteryOptimization()
        }

        btnSettings.setOnClickListener { showApiKeyDialog() }
        btnInfo.setOnClickListener { showInfoDialog() }
        btnJargon.setOnClickListener { showJargonDialog() }
        btnHistory.setOnClickListener { showHistoryBrowser() }

        btnCopyResult.setOnClickListener {
            val text = currentEditableText()
            if (text.isBlank()) {
                Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard(text)
            persistEditedResult(text)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnPolish.setOnClickListener {
            val raw = lastRawTranscription.ifBlank { currentEditableText() }
            if (!isUsableTranscription(raw)) {
                Toast.makeText(this, "No transcription to polish", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startPolish(raw, autoTriggered = false)
        }

        btnPolishPreset.setOnClickListener { showPresetChooserDialog() }

        btnToggleVariant.setOnClickListener { toggleResultVariant() }

        btnPauseResume.setOnClickListener {
            val action = if (isPaused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
            startService(Intent(this, RecordingService::class.java).apply { this.action = action })
        }

        btnRetryUpload.setOnClickListener {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RETRY_UPLOAD
                putExtra(RecordingService.EXTRA_API_KEY, getApiKey())
                putExtra(RecordingService.EXTRA_JARGON, getJargon())
                putExtra(RecordingService.EXTRA_DEFER_CLIPBOARD, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            btnRetryUpload.visibility = View.GONE
            setStatus("Retrying upload...")
        }

        restoreInstanceState(savedInstanceState)

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                // startRecording() clears the result screen — doing it here instead would
                // wipe the user's text even when the permission request is then denied.
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The EditText's own text is restored by the framework (it has an id); what would
        // otherwise be lost on rotation is which mode the screen is in.
        outState.putBoolean(STATE_SHOWING_RESULT, showingResult)
        outState.putBoolean(STATE_SHOWS_POLISHED, resultShowsPolished)
        outState.putString(STATE_RAW_TRANSCRIPTION, lastRawTranscription)
        outState.putString(STATE_ENTRY_ID, lastHistoryEntryId)
        outState.putString(STATE_STATUS, tvStatus.text?.toString().orEmpty())
        outState.putString(STATE_ROUTE_LABEL, lastRouteLabel)
        lastDurationSeconds?.let { outState.putInt(STATE_DURATION, it) }
        lastRouteSampleRate?.let { outState.putInt(STATE_ROUTE_RATE, it) }
    }

    /** Re-enters the result screen after a rotation without re-copying or re-saving. */
    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return

        lastRawTranscription = state.getString(STATE_RAW_TRANSCRIPTION).orEmpty()
        lastHistoryEntryId = state.getString(STATE_ENTRY_ID)
        lastDurationSeconds = if (state.containsKey(STATE_DURATION)) {
            state.getInt(STATE_DURATION)
        } else {
            null
        }
        resultShowsPolished = state.getBoolean(STATE_SHOWS_POLISHED, false)
        lastRouteLabel = state.getString(STATE_ROUTE_LABEL)
        lastRouteSampleRate = if (state.containsKey(STATE_ROUTE_RATE)) {
            state.getInt(STATE_ROUTE_RATE)
        } else {
            null
        }
        if (lastRouteLabel != null || lastRouteSampleRate != null) {
            updateMicIndicator()
        }

        if (state.getBoolean(STATE_SHOWING_RESULT, false)) {
            showResultUi()
            btnPolish.isEnabled = lastRawTranscription.isNotBlank()
            updateVariantToggle()
        }
        // After showResultUi, which clears the status line.
        setStatus(state.getString(STATE_STATUS).orEmpty())
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        sharedPreferences.edit().putBoolean(RecordingService.PREF_UI_VISIBLE, true).apply()
        consumePendingResult()
        refreshRetryButton()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
        sharedPreferences.edit().putBoolean(RecordingService.PREF_UI_VISIBLE, false).apply()
    }

    /**
     * Picks up a transcription that finished while this activity was stopped. The service
     * saves every result to history immediately, so nothing is lost when the broadcast has
     * no live receiver — this is where the user finally sees it.
     */
    private fun consumePendingResult() {
        val pendingId = sharedPreferences.getString(RecordingService.PREF_PENDING_RESULT_ID, null)
            ?: return
        clearPendingResult()
        if (isRecording) return

        val entry = TranscriptionHistory.getById(this, pendingId) ?: return

        lastHistoryEntryId = entry.id
        lastRawTranscription = entry.rawText
        lastDurationSeconds = entry.durationSeconds

        // Honour auto-polish for a result the user never got to see.
        if (isAutoPolishEnabled() && entry.polishedText.isNullOrBlank() && entry.rawText.isNotBlank()) {
            startPolish(entry.rawText, autoTriggered = true)
            return
        }

        showEditableResult(
            displayText = entry.displayText(),
            rawText = entry.rawText,
            polishedText = entry.polishedText,
            saveHistory = false,
            showingPolished = !entry.polishedText.isNullOrBlank()
        )
        Toast.makeText(this, "Transcription ready — copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearPendingResult() {
        sharedPreferences.edit().remove(RecordingService.PREF_PENDING_RESULT_ID).apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(RecordingService.RESULT_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audio && notification
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 200)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startRecording()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        // Leaving the editable result: save any edits, clear the field, start fresh. Done
        // here (not on the button tap) so a denied permission never costs the user their text.
        if (showingResult) {
            prepareForNewRecording()
        }
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_API_KEY, getApiKey())
            putExtra(RecordingService.EXTRA_JARGON, getJargon())
            // Defer clipboard until the user finishes on the result screen (or polish choice).
            putExtra(RecordingService.EXTRA_DEFER_CLIPBOARD, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun getApiKey(): String? = sharedPreferences.getString("api_key", "")

    private fun getJargon(): String? = sharedPreferences.getString("api_jargon", "")

    private fun isAutoPolishEnabled(): Boolean =
        sharedPreferences.getBoolean(PREF_AUTO_POLISH, false)

    /**
     * Status messages used to be written into the transcript field, which forced this to
     * blacklist their exact wording. They now live in their own view, so the field only ever
     * holds transcript text and a blank check is enough.
     */
    private fun isUsableTranscription(text: String): Boolean = text.isNotBlank()

    private fun currentEditableText(): String = etTranscription.text?.toString().orEmpty().trim()

    private fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Shows the microphone the recorder actually used. Reports "unknown" rather than
     * guessing — the previous indicator inferred Bluetooth from merely-connected devices
     * and so claimed a headset mic while the phone mic recorded.
     */
    private fun updateMicIndicator() {
        val label = lastRouteLabel?.takeIf { it.isNotBlank() }
        val rate = lastRouteSampleRate?.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
        tvMicIndicator.text = when {
            label != null && rate != null -> "Mic: $label · $rate"
            label != null -> "Mic: $label"
            rate != null -> "Mic: unknown · $rate"
            else -> "Mic: unknown"
        }
    }

    private fun updatePauseButton() {
        btnPauseResume.visibility = if (isRecording) View.VISIBLE else View.GONE
        btnPauseResume.text = if (isPaused) "Resume" else "Pause"
    }

    /** Offers the retry only while a failed upload's audio is still on disk. */
    private fun refreshRetryButton() {
        val path = sharedPreferences.getString(RecordingService.PREF_FAILED_AUDIO_PATH, null)
        val available = path != null && java.io.File(path).exists()
        if (!available && path != null) {
            sharedPreferences.edit()
                .remove(RecordingService.PREF_FAILED_AUDIO_PATH)
                .remove(RecordingService.PREF_FAILED_AUDIO_DURATION)
                .apply()
        }
        btnRetryUpload.visibility = if (available && !isRecording) View.VISIBLE else View.GONE
    }

    /**
     * Asked once per install. Aggressive OEM battery savers are the single most common reason
     * background recording dies, and the README's manual workaround assumed the user would go
     * looking for it.
     */
    private fun maybeAskBatteryOptimization() {
        if (sharedPreferences.getBoolean(PREF_ASKED_BATTERY_OPT, false)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            sharedPreferences.edit().putBoolean(PREF_ASKED_BATTERY_OPT, true).apply()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Keep recording reliable")
            .setMessage(
                "Android's battery saver can kill Stow while it records in the background — " +
                    "the recording stops without warning when your screen is off or you switch apps.\n\n" +
                    "Allowing Stow to run unrestricted prevents that. Stow only uses power while " +
                    "you are actually recording."
            )
            .setPositiveButton("Allow") { _, _ ->
                sharedPreferences.edit().putBoolean(PREF_ASKED_BATTERY_OPT, true).apply()
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Open Settings → Apps → Stow → Battery", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                sharedPreferences.edit().putBoolean(PREF_ASKED_BATTERY_OPT, true).apply()
            }
            .show()
    }

    private fun showRecordingUi() {
        showingResult = false
        resultHeaderRow.visibility = View.GONE
        resultActionRow.visibility = View.GONE
        btnRecord.visibility = View.VISIBLE
        if (!isRecording) {
            btnRecord.text = "Start Recording"
        }
        chronometer.visibility = View.VISIBLE
        setTranscriptionEditable(false)
    }

    private fun showResultUi() {
        showingResult = true
        // Name the variant on screen — without this there is no way to tell raw from
        // polished once the text is in the editable field.
        tvResultLabel.text = if (resultShowsPolished) {
            "Result — polished (editable)"
        } else {
            "Result — raw (editable)"
        }
        resultHeaderRow.visibility = View.VISIBLE
        resultActionRow.visibility = View.VISIBLE
        setStatus("")
        // Keep Start at the same position as the main recording screen so the next take is one tap.
        btnRecord.visibility = View.VISIBLE
        btnRecord.text = "Start Recording"
        chronometer.visibility = View.GONE
        setTranscriptionEditable(true)
    }

    private fun setTranscriptionEditable(editable: Boolean) {
        etTranscription.isFocusable = editable
        etTranscription.isFocusableInTouchMode = editable
        etTranscription.isCursorVisible = editable
        etTranscription.isLongClickable = true
        if (!editable) {
            etTranscription.clearFocus()
        }
    }

    private fun showEditableResult(
        displayText: String,
        rawText: String,
        polishedText: String?,
        saveHistory: Boolean,
        showingPolished: Boolean = polishedText != null && displayText == polishedText,
        autoCopy: Boolean = true
    ) {
        lastRawTranscription = rawText
        resultShowsPolished = showingPolished
        showResultUi()
        etTranscription.setText(displayText)
        etTranscription.setSelection(etTranscription.text?.length ?: 0)
        btnPolish.isEnabled = rawText.isNotBlank()

        if (saveHistory && rawText.isNotBlank()) {
            // The service saves the entry as soon as the transcription lands, so normally
            // there is one to update. Only add when there genuinely isn't (e.g. polishing
            // text that never came from a recording).
            val existingId = lastHistoryEntryId
            val existing = existingId?.let { TranscriptionHistory.getById(this, it) }
            if (existing != null) {
                TranscriptionHistory.update(
                    this,
                    existing.copy(
                        rawText = rawText,
                        polishedText = polishedText ?: existing.polishedText
                    )
                )
            } else {
                val entry = TranscriptionHistory.add(
                    context = this,
                    rawText = rawText,
                    polishedText = polishedText,
                    durationSeconds = lastDurationSeconds
                )
                lastHistoryEntryId = entry?.id
            }
        }

        // Copy immediately so the text is ready to paste; bottom Copy updates after edits.
        if (autoCopy && displayText.isNotBlank()) {
            copyToClipboard(displayText)
        }

        // After history is settled — the toggle depends on the entry having both versions.
        updateVariantToggle()
        showQualityWarningIfAny()
    }

    /**
     * Whisper hallucinates confidently on poor audio, so bad output reads as fine. The
     * decode statistics are the only signal that something went wrong; surface them, but
     * never block or auto-retry.
     */
    private fun showQualityWarningIfAny() {
        val warning = lastHistoryEntryId
            ?.let { TranscriptionHistory.getById(this, it) }
            ?.qualityWarning()
            ?: return
        setStatus("Transcription may be unreliable — $warning")
    }

    /**
     * Shows the toggle only when the current note actually has both versions, and labels it
     * with the variant it would switch *to*.
     */
    private fun updateVariantToggle() {
        val entry = lastHistoryEntryId?.let { TranscriptionHistory.getById(this, it) }
        val hasBoth = entry != null &&
            entry.rawText.isNotBlank() &&
            !entry.polishedText.isNullOrBlank() &&
            entry.polishedText != entry.rawText
        btnToggleVariant.visibility = if (showingResult && hasBoth) View.VISIBLE else View.GONE
        btnToggleVariant.text = if (resultShowsPolished) "Show raw" else "Show polished"
    }

    /** Swaps the result field between raw and polished, keeping edits to each side. */
    private fun toggleResultVariant() {
        val id = lastHistoryEntryId ?: return

        // Save whatever the user typed into the version they are leaving.
        val current = currentEditableText()
        if (current.isNotBlank()) {
            persistEditedResult(current)
        }

        val entry = TranscriptionHistory.getById(this, id) ?: return
        val showPolished = !resultShowsPolished
        val target = if (showPolished) entry.polishedText.orEmpty() else entry.rawText
        if (target.isBlank()) return

        resultShowsPolished = showPolished
        showResultUi()
        etTranscription.setText(target)
        etTranscription.setSelection(target.length)
        copyToClipboard(target)
        updateVariantToggle()
        Toast.makeText(
            this,
            if (showPolished) "Polished copied" else "Raw copied",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun persistEditedResult(editedText: String) {
        val id = lastHistoryEntryId ?: return
        val existing = TranscriptionHistory.getById(this, id) ?: return
        val updated = if (resultShowsPolished) {
            existing.copy(polishedText = editedText)
        } else {
            existing.copy(rawText = editedText)
        }
        if (updated != existing) {
            TranscriptionHistory.update(this, updated)
        }
    }

    /** Persist edits if needed and clear result state before a brand-new recording. */
    private fun prepareForNewRecording() {
        val edited = currentEditableText()
        if (isUsableTranscription(edited)) {
            persistEditedResult(edited)
        }
        lastRawTranscription = ""
        lastDurationSeconds = null
        lastHistoryEntryId = null
        resultShowsPolished = false
        btnPolish.isEnabled = false
        showingResult = false
        resultHeaderRow.visibility = View.GONE
        resultActionRow.visibility = View.GONE
        setTranscriptionEditable(false)
        etTranscription.setText("")
        setStatus("")
    }

    private fun startPolish(rawText: String, autoTriggered: Boolean) {
        if (isPolishing) {
            Toast.makeText(this, "Polish already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Set your Groq API key in Settings first", Toast.LENGTH_SHORT).show()
            showApiKeyDialog()
            return
        }

        // Both manual and auto-polish use the currently selected preset; auto-polish has no
        // UI moment of its own, so "last used" is the only sensible source.
        val preset = PolishPresets.getSelected(this)
        val systemPrompt = PolishPresets.buildSystemPrompt(preset, getJargon().orEmpty())

        isPolishing = true
        btnPolish.isEnabled = false
        setStatus("Polishing with ${preset.name}...")

        polisher.polish(
            rawText = rawText,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            // Only Clean prose promises to stay close to the original length. Task capture
            // restructures into headings and checkboxes and legitimately grows.
            enforceLengthGuard = preset.id == PolishPresets.ID_CLEAN_PROSE,
            onSuccess = { polished ->
                runOnUiThread {
                    isPolishing = false
                    btnPolish.isEnabled = true
                    if (autoTriggered) {
                        // Auto-polish means "always polished" — confirming it on every note
                        // is the friction the setting exists to remove. The result screen's
                        // Show raw toggle covers the times it gets it wrong.
                        openResultAfterPolish(
                            rawText,
                            displayText = polished,
                            polishedForHistory = polished,
                            chosePolished = true
                        )
                    } else {
                        showPolishResultDialog(rawText, polished, preset.name)
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    isPolishing = false
                    btnPolish.isEnabled = rawText.isNotBlank()
                    if (autoTriggered) {
                        showEditableResult(
                            displayText = rawText,
                            rawText = rawText,
                            polishedText = null,
                            saveHistory = true
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "Polish failed — raw transcription copied",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // The transcript field was never overwritten by status text, so
                        // there is nothing to restore — just clear the spinner message.
                        setStatus("")
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun showPolishResultDialog(rawText: String, polishedText: String, presetName: String) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (8 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val rawLabel = TextView(this).apply {
            text = "Raw transcription"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val rawView = TextView(this).apply {
            text = rawText
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, gap)
        }

        val polishedLabel = TextView(this).apply {
            text = "Polished — $presetName (preview)"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, gap, 0, 0)
        }
        val polishedInput = EditText(this).apply {
            setText(polishedText)
            textSize = 16f
            minLines = 4
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(gap, gap, gap, gap)
        }

        container.addView(rawLabel)
        container.addView(rawView)
        container.addView(polishedLabel)
        container.addView(polishedInput)

        val scrollView = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Polish result")
            .setView(scrollView)
            // Two buttons, both honest. There used to be a third ("Close") that read as
            // cancel but silently applied the polished text.
            .setPositiveButton("Use polished") { _, _ ->
                val finalText = polishedInput.text.toString().trim().ifBlank { polishedText }
                openResultAfterPolish(rawText, displayText = finalText, polishedForHistory = finalText, chosePolished = true)
            }
            .setNegativeButton("Use raw") { _, _ ->
                // Keep polished in history even if the user displays raw.
                val polishedFinal = polishedInput.text.toString().trim().ifBlank { polishedText }
                openResultAfterPolish(rawText, displayText = rawText, polishedForHistory = polishedFinal, chosePolished = false)
            }
            .setCancelable(true)
            .setOnCancelListener {
                openResultAfterPolish(rawText, displayText = polishedText, polishedForHistory = polishedText, chosePolished = true)
            }
            .show()
    }

    private fun openResultAfterPolish(
        rawText: String,
        displayText: String,
        polishedForHistory: String,
        chosePolished: Boolean
    ) {
        // If we already saved a history entry for this session, update it; otherwise add.
        val existingId = lastHistoryEntryId
        if (existingId != null) {
            val existing = TranscriptionHistory.getById(this, existingId)
            if (existing != null) {
                TranscriptionHistory.update(
                    this,
                    existing.copy(
                        polishedText = polishedForHistory,
                        rawText = rawText
                    )
                )
                resultShowsPolished = chosePolished
                showResultUi()
                etTranscription.setText(displayText)
                etTranscription.setSelection(etTranscription.text?.length ?: 0)
                lastRawTranscription = rawText
                btnPolish.isEnabled = true
                if (displayText.isNotBlank()) {
                    copyToClipboard(displayText)
                }
                updateVariantToggle()
                showQualityWarningIfAny()
                Toast.makeText(
                    this,
                    if (chosePolished) "Polished text copied — edit if needed"
                    else "Raw text copied — edit if needed",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        showEditableResult(
            displayText = displayText,
            rawText = rawText,
            polishedText = polishedForHistory,
            saveHistory = true,
            showingPolished = chosePolished,
            autoCopy = true
        )
        Toast.makeText(
            this,
            if (chosePolished) "Polished text copied — edit if needed"
            else "Raw text copied — edit if needed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun shareText(text: String, title: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(sendIntent, title))
    }

    // region History browser

    private fun showHistoryBrowser() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val searchInput = EditText(this).apply {
            hint = "Search notes…"
            setSingleLine(true)
            textSize = 16f
        }
        container.addView(searchInput)

        val emptyView = TextView(this).apply {
            text = "No notes yet. Record something to build history."
            textSize = 14f
            setPadding(0, pad, 0, pad)
            visibility = View.GONE
        }
        container.addView(emptyView)

        val listView = ListView(this).apply {
            dividerHeight = 1
        }
        val listParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (360 * density).toInt()
        )
        container.addView(listView, listParams)

        var currentEntries = TranscriptionHistory.getAll(this)

        fun labelsFor(entries: List<TranscriptionHistory.Entry>): List<String> {
            return entries.map { entry ->
                val duration = entry.formattedDuration()?.let { " · $it" }.orEmpty()
                val badge = if (!entry.polishedText.isNullOrBlank()) " · polished" else ""
                val warning = entry.qualityWarning()?.let { " · ⚠ $it" }.orEmpty()
                "${entry.formattedTimestamp()}$duration$badge$warning\n${entry.preview(90)}"
            }
        }

        fun refreshList(query: String) {
            currentEntries = TranscriptionHistory.search(this, query)
            if (currentEntries.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                emptyView.text = if (query.isBlank()) {
                    "No notes yet. Record something to build history."
                } else {
                    "No notes match \"$query\"."
                }
                listView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                listView.visibility = View.VISIBLE
                listView.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    labelsFor(currentEntries)
                )
            }
        }

        refreshList("")

        // Debounced so a fast typist does not re-filter the whole history on every keystroke.
        val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var pendingSearch: Runnable? = null
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingSearch?.let { searchHandler.removeCallbacks(it) }
                val query = s?.toString().orEmpty()
                val runnable = Runnable { refreshList(query) }
                pendingSearch = runnable
                searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MILLIS)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Transcription History")
            .setView(container)
            .setPositiveButton("Close", null)
            .setNeutralButton("Export all") { _, _ ->
                val export = TranscriptionHistory.exportAll(this)
                if (export == "No recording history found." || export.isBlank()) {
                    Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
                } else {
                    shareText(export, "Export history")
                }
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in currentEntries.indices) {
                dialog.dismiss()
                showHistoryDetail(currentEntries[position])
            }
        }

        dialog.show()
    }

    private fun showHistoryDetail(entry: TranscriptionHistory.Entry) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (8 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val meta = TextView(this).apply {
            val duration = entry.formattedDuration()?.let { " · $it" }.orEmpty()
            text = "${entry.formattedTimestamp()}$duration"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        }
        container.addView(meta)

        // Which microphone produced this note — the answer to "why is this transcript bad?"
        entry.formattedRoute()?.let { route ->
            container.addView(
                TextView(this).apply {
                    text = route
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                }
            )
        }

        entry.qualityWarning()?.let { warning ->
            container.addView(
                TextView(this).apply {
                    text = "⚠ Transcription may be unreliable — $warning"
                    textSize = 12f
                    setPadding(0, gap / 2, 0, 0)
                }
            )
        }

        val body = EditText(this).apply {
            setText(entry.displayText())
            textSize = 16f
            minLines = 6
            maxLines = 16
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(gap, gap, gap, gap)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        container.addView(body)

        if (!entry.polishedText.isNullOrBlank() && entry.polishedText != entry.rawText) {
            val rawLabel = TextView(this).apply {
                text = "Original raw"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, gap, 0, 0)
            }
            val rawView = TextView(this).apply {
                text = entry.rawText
                textSize = 13f
                setTextIsSelectable(true)
            }
            container.addView(rawLabel)
            container.addView(rawView)
        }

        val scrollView = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Note detail")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val text = body.text.toString().trim()
                if (text.isBlank()) {
                    Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                } else {
                    copyToClipboard(text)
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            .create()

        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "More…") { _, _ ->
            // Overridden below after show so we can keep the dialog open / chain menus.
        }

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val text = body.text.toString().trim()
            showHistoryDetailActions(entry, text, dialog)
        }
    }

    private fun showHistoryDetailActions(
        entry: TranscriptionHistory.Entry,
        currentText: String,
        parentDialog: AlertDialog
    ) {
        val options = arrayOf(
            "Share",
            "Export note",
            "Re-polish",
            "Delete",
            "Save edits"
        )
        AlertDialog.Builder(this)
            .setTitle("Actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (currentText.isBlank()) {
                            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
                        } else {
                            shareText(currentText, "Share note")
                        }
                    }
                    1 -> {
                        shareText(TranscriptionHistory.exportOne(entry), "Export note")
                    }
                    2 -> {
                        parentDialog.dismiss()
                        rePolishHistoryEntry(entry)
                    }
                    3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete note?")
                            .setMessage("This permanently removes the note from local history.")
                            .setPositiveButton("Delete") { _, _ ->
                                TranscriptionHistory.delete(this, entry.id)
                                if (lastHistoryEntryId == entry.id) {
                                    lastHistoryEntryId = null
                                }
                                parentDialog.dismiss()
                                Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                                showHistoryBrowser()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    4 -> {
                        val updated = if (entry.polishedText != null) {
                            entry.copy(polishedText = currentText)
                        } else {
                            entry.copy(rawText = currentText)
                        }
                        TranscriptionHistory.update(this, updated)
                        Toast.makeText(this, "Edits saved", Toast.LENGTH_SHORT).show()
                        parentDialog.dismiss()
                        showHistoryDetail(updated)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rePolishHistoryEntry(entry: TranscriptionHistory.Entry) {
        val apiKey = getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Set your Groq API key in Settings first", Toast.LENGTH_SHORT).show()
            showApiKeyDialog()
            return
        }
        if (isPolishing) {
            Toast.makeText(this, "Polish already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val preset = PolishPresets.getSelected(this)
        isPolishing = true
        Toast.makeText(this, "Re-polishing with ${preset.name}…", Toast.LENGTH_SHORT).show()

        polisher.polish(
            rawText = entry.rawText,
            apiKey = apiKey,
            systemPrompt = PolishPresets.buildSystemPrompt(preset, getJargon().orEmpty()),
            enforceLengthGuard = preset.id == PolishPresets.ID_CLEAN_PROSE,
            onSuccess = { polished ->
                runOnUiThread {
                    isPolishing = false
                    val updated = entry.copy(polishedText = polished)
                    TranscriptionHistory.update(this@MainActivity, updated)
                    Toast.makeText(this@MainActivity, "Note re-polished", Toast.LENGTH_SHORT).show()
                    showHistoryDetail(updated)
                }
            },
            onError = { error ->
                runOnUiThread {
                    isPolishing = false
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    showHistoryDetail(entry)
                }
            }
        )
    }

    // endregion

    // region Polish presets

    private fun updatePresetButton() {
        btnPolishPreset.text = "${PolishPresets.getSelected(this).name} ▾"
    }

    /** Quick selector: pick the preset the next polish will use. */
    private fun showPresetChooserDialog() {
        val presets = PolishPresets.getAll(this)
        val selectedId = PolishPresets.getSelected(this).id
        val names = presets.map { it.name }.toTypedArray()
        val checked = presets.indexOfFirst { it.id == selectedId }

        AlertDialog.Builder(this)
            .setTitle("Polish preset")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                PolishPresets.setSelected(this, presets[which].id)
                updatePresetButton()
                dialog.dismiss()
                Toast.makeText(this, "Using \"${presets[which].name}\"", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Manage…") { _, _ -> showPresetManagerDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresetManagerDialog() {
        val presets = PolishPresets.getAll(this)
        val labels = presets.map { preset ->
            if (preset.isBuiltIn) "${preset.name}  ·  built-in" else preset.name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Polish presets")
            .setItems(labels) { _, which -> showPresetEditDialog(presets[which]) }
            .setNeutralButton("Add new") { _, _ -> showPresetEditDialog(null) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** @param existing null to create a new preset. */
    private fun showPresetEditDialog(existing: PolishPresets.Preset?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (8 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val nameLabel = TextView(this).apply {
            text = "Name"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name.orEmpty())
            hint = "Preset name"
            setSingleLine(true)
            textSize = 16f
        }

        val promptLabel = TextView(this).apply {
            text = "System prompt"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, gap, 0, 0)
        }
        val promptInput = EditText(this).apply {
            setText(existing?.prompt.orEmpty())
            hint = "Instructions sent as the system message"
            textSize = 14f
            minLines = 6
            maxLines = 16
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(gap, gap, gap, gap)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val hint = TextView(this).apply {
            text = "The transcript is sent separately as the user message. Jargon Dictionary " +
                "terms are appended automatically, or inserted at " +
                "${PolishPresets.JARGON_PLACEHOLDER} if you include it."
            textSize = 12f
            setPadding(0, gap, 0, 0)
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(promptLabel)
        container.addView(promptInput)
        container.addView(hint)

        val scrollView = ScrollView(this).apply { addView(container) }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New preset" else "Edit preset")
            .setView(scrollView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> showPresetManagerDialog() }

        // Built-ins can be restored to their shipped text; custom presets can be removed.
        if (existing != null) {
            if (existing.isBuiltIn) {
                builder.setNeutralButton("Reset") { _, _ -> confirmResetPreset(existing) }
            } else {
                builder.setNeutralButton("Delete") { _, _ -> confirmDeletePreset(existing) }
            }
        }

        val dialog = builder.create()
        dialog.show()

        // Override after show() so validation failures keep the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val prompt = promptInput.text.toString().trim()
            if (name.isEmpty() || prompt.isEmpty()) {
                Toast.makeText(this, "Name and prompt are both required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (existing == null) {
                val created = PolishPresets.add(this, name, prompt)
                if (created == null) {
                    Toast.makeText(this, "Could not save preset", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                PolishPresets.setSelected(this, created.id)
                Toast.makeText(this, "Preset saved and selected", Toast.LENGTH_SHORT).show()
            } else {
                PolishPresets.update(this, existing.copy(name = name, prompt = prompt))
                Toast.makeText(this, "Preset saved", Toast.LENGTH_SHORT).show()
            }
            updatePresetButton()
            dialog.dismiss()
            showPresetManagerDialog()
        }
    }

    private fun confirmResetPreset(preset: PolishPresets.Preset) {
        AlertDialog.Builder(this)
            .setTitle("Reset \"${preset.name}\"?")
            .setMessage("Restores the built-in name and prompt text. Your edits to this preset are lost.")
            .setPositiveButton("Reset") { _, _ ->
                PolishPresets.resetToDefault(this, preset.id)
                updatePresetButton()
                Toast.makeText(this, "Preset reset", Toast.LENGTH_SHORT).show()
                showPresetManagerDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showPresetManagerDialog() }
            .show()
    }

    private fun confirmDeletePreset(preset: PolishPresets.Preset) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${preset.name}\"?")
            .setMessage("This permanently removes the preset.")
            .setPositiveButton("Delete") { _, _ ->
                PolishPresets.delete(this, preset.id)
                updatePresetButton()
                Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
                showPresetManagerDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showPresetManagerDialog() }
            .show()
    }

    // endregion

    private fun showApiKeyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")
        builder.setMessage("Please enter your Groq API key to use dictation.")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val apiKeyInput = EditText(this)
        apiKeyInput.setText(getApiKey())
        apiKeyInput.hint = "Groq API key"
        layout.addView(apiKeyInput)

        val autoPolishCheckbox = CheckBox(this).apply {
            text = "Auto-polish after transcription"
            isChecked = isAutoPolishEnabled()
            setPadding(0, 24, 0, 0)
        }
        layout.addView(autoPolishCheckbox)

        val autoPolishHint = TextView(this).apply {
            text = "When enabled, Stow runs a second free-tier-friendly Groq pass using the selected polish preset and goes straight to the editable result with the polished text already copied — no confirmation step. Use Show raw on the result screen if a polish goes wrong. Disable this to go straight to the raw transcript instead."
            textSize = 12f
            setPadding(8, 8, 8, 0)
        }
        layout.addView(autoPolishHint)

        val presetsButton = Button(this).apply {
            text = "Polish presets…"
            setPadding(0, 24, 0, 0)
            setOnClickListener { showPresetManagerDialog() }
        }
        layout.addView(presetsButton)

        builder.setView(layout)

        builder.setPositiveButton("Save") { dialog, _ ->
            val key = apiKeyInput.text.toString().trim()
            sharedPreferences.edit()
                .putString("api_key", key)
                .putBoolean(PREF_AUTO_POLISH, autoPolishCheckbox.isChecked)
                .apply()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.setCancelable(false)
        builder.show()
    }

    private fun showJargonDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Custom Vocabulary / Jargon")
        builder.setMessage("Enter comma-separated terms (e.g., CAD, HVAC, structural load)")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val jargonInput = EditText(this)
        jargonInput.setText(getJargon())
        layout.addView(jargonInput)

        builder.setView(layout)

        builder.setPositiveButton("Save") { dialog, _ ->
            val jargonText = jargonInput.text.toString().trim()
            sharedPreferences.edit()
                .putString("api_jargon", jargonText)
                .apply()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun showInfoDialog() {
        val message = TextView(this).apply {
            text = "Stow is an open-source background dictation app.\n\n" +
                "After each transcription, choose raw or polished — Stow copies your choice and opens an editable result screen. Use Copy to refresh the clipboard after edits, or Start at the top for the next recording.\n\n" +
                "History stores timestamp, duration, raw, and polished text. Search, open a note, re-polish, export, or delete — all offline on device.\n\n" +
                "View GitHub Repository:\nhttps://github.com/mds08011/stow\n\n" +
                "View Changelog & Updates:\nhttps://github.com/mds08011/stow/releases"
            setPadding(50, 40, 50, 40)
            textSize = 16f
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }

        AlertDialog.Builder(this)
            .setTitle("Stow App - v${BuildConfig.VERSION_NAME}")
            .setView(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Check for Updates") { _, _ ->
                checkForUpdates()
            }
            .show()
    }

    private fun loadInitialUsage() {
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = sharedPreferences.getString("LastRecordedDate", "")

        var currentTotal = sharedPreferences.getInt("DailyUsageSeconds", 0)
        if (todayDate != lastDate) {
            currentTotal = 0
        }

        updateUsageText(currentTotal)
    }

    private fun updateUsageText(usageSeconds: Int) {
        val minutes = usageSeconds / 60
        val percent = (usageSeconds.toFloat() / 28800f * 100f).toInt()
        tvUsage.text = "Today's Usage: ${minutes}m ($percent% of 8h Groq Free limit)"
    }

    private var downloadReceiver: BroadcastReceiver? = null

    private fun checkForUpdates() {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/mds08011/stow/releases/latest")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonObject = org.json.JSONObject(responseBody)
                        val tagName = jsonObject.getString("tag_name")
                        val assets = jsonObject.getJSONArray("assets")
                        var downloadUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }

                        val version = tagName.removePrefix("v")
                        val currentVersion = BuildConfig.VERSION_NAME

                        val comparison = compareVersions(version, currentVersion)
                        if (comparison == null) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Error parsing version", Toast.LENGTH_SHORT).show()
                            }
                        } else if (comparison > 0 && downloadUrl.isNotEmpty()) {
                            runOnUiThread {
                                showUpdateDialog(version, downloadUrl)
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "App is up to date", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    /**
     * Compares dotted version strings component-wise: negative, zero or positive like
     * [Comparator], or null when either side is not numeric.
     *
     * Replaces a toDouble() comparison that read "2.10" as 2.1 (so it would have gone
     * backwards from 2.4) and threw outright on three-part tags such as "2.4.1".
     */
    private fun compareVersions(a: String, b: String): Int? {
        val left = a.trim().removePrefix("v").split(".")
        val right = b.trim().removePrefix("v").split(".")
        val parts = maxOf(left.size, right.size)
        for (i in 0 until parts) {
            val l = (left.getOrNull(i) ?: "0").toIntOrNull() ?: return null
            val r = (right.getOrNull(i) ?: "0").toIntOrNull() ?: return null
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun showUpdateDialog(version: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("Update Available: v$version. Do you want to download and install?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstallUpdate(downloadUrl, "stow-v$version.apk")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndInstallUpdate(url: String, fileName: String) {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            .setTitle("Stow Update")
            .setDescription("Downloading update $fileName")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val downloadId = downloadManager.enqueue(request)

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName)
                    try {
                        unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    downloadReceiver = null
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                downloadReceiver,
                IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
    }

    private fun installApk(fileName: String) {
        try {
            val file = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) return

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to install update: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    companion object {
        private const val PREF_AUTO_POLISH = "auto_polish"
        private const val PREF_ASKED_BATTERY_OPT = "asked_battery_opt"
        private const val SEARCH_DEBOUNCE_MILLIS = 250L

        private const val STATE_SHOWING_RESULT = "showingResult"
        private const val STATE_SHOWS_POLISHED = "resultShowsPolished"
        private const val STATE_RAW_TRANSCRIPTION = "lastRawTranscription"
        private const val STATE_DURATION = "lastDurationSeconds"
        private const val STATE_ENTRY_ID = "lastHistoryEntryId"
        private const val STATE_STATUS = "statusText"
        private const val STATE_ROUTE_LABEL = "lastRouteLabel"
        private const val STATE_ROUTE_RATE = "lastRouteSampleRate"
    }
}
