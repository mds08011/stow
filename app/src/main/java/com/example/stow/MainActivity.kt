package com.example.stow

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
    private lateinit var tvResultLabel: TextView
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
    private lateinit var btnCopyResult: Button

    private var isRecording = false
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
                        setStatusText("Recording...")
                        btnPolish.isEnabled = false
                        lastRawTranscription = ""
                        lastDurationSeconds = null
                        lastHistoryEntryId = null
                        resultShowsPolished = false
                        showRecordingUi()

                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        var isBluetooth = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val activeConfigs = audioManager.activeRecordingConfigurations
                            isBluetooth = activeConfigs.any { config ->
                                val type = config.audioDevice.type
                                type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                    type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            }
                        }
                        if (!isBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                            isBluetooth = devices.any { device ->
                                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            }
                        }

                        tvMicIndicator.text = if (isBluetooth) "Bluetooth Mic" else "Internal Mic"

                        chronometer.base = SystemClock.elapsedRealtime()
                        chronometer.start()
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_LOADING -> {
                        isRecording = false
                        btnRecord.text = "Start Recording"
                        setStatusText("Uploading and Transcribing...")
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

                        val usage = intent.getIntExtra(RecordingService.EXTRA_USAGE, -1)
                        if (usage != -1) {
                            updateUsageText(usage)
                        }

                        if (isAutoPolishEnabled() && transcription.isNotBlank()) {
                            setStatusText("Polishing...")
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
                        btnRecord.text = "Start Recording"
                        showingResult = false
                        showRecordingUi()
                        setStatusText(text ?: "An error occurred")
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
        tvResultLabel = findViewById(R.id.tvResultLabel)
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
        btnCopyResult = findViewById(R.id.btnCopyResult)

        updatePresetButton()
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        loadInitialUsage()
        showRecordingUi()
        setStatusText("Transcription will appear here...")

        if (getApiKey().isNullOrEmpty()) {
            showApiKeyDialog()
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

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                // Leaving the editable result: save any edits, clear the field, start fresh.
                if (showingResult) {
                    prepareForNewRecording()
                }
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
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

    private fun isUsableTranscription(text: String): Boolean {
        if (text.isBlank()) return false
        return text != "Transcription will appear here..." &&
            text != "Recording..." &&
            text != "Uploading and Transcribing..." &&
            !text.startsWith(POLISHING_STATUS_PREFIX) &&
            text != "An error occurred"
    }

    private fun currentEditableText(): String = etTranscription.text?.toString().orEmpty().trim()

    private fun setStatusText(text: String) {
        etTranscription.setText(text)
        etTranscription.setSelection(0)
        etTranscription.scrollTo(0, 0)
    }

    private fun showRecordingUi() {
        showingResult = false
        tvResultLabel.visibility = View.GONE
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
        tvResultLabel.visibility = View.VISIBLE
        resultActionRow.visibility = View.VISIBLE
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
            val entry = TranscriptionHistory.add(
                context = this,
                rawText = rawText,
                polishedText = polishedText,
                durationSeconds = lastDurationSeconds
            )
            lastHistoryEntryId = entry?.id
        }

        // Copy immediately so the text is ready to paste; bottom Copy updates after edits.
        if (autoCopy && displayText.isNotBlank()) {
            copyToClipboard(displayText)
        }
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
        tvResultLabel.visibility = View.GONE
        resultActionRow.visibility = View.GONE
        setTranscriptionEditable(false)
        setStatusText("Transcription will appear here...")
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
        val previousText = etTranscription.text?.toString().orEmpty()
        if (!showingResult) {
            setStatusText("Polishing with ${preset.name}...")
        }

        polisher.polish(
            rawText = rawText,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            onSuccess = { polished ->
                runOnUiThread {
                    isPolishing = false
                    btnPolish.isEnabled = true
                    showPolishResultDialog(rawText, polished, preset.name)
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
                        if (!showingResult) {
                            setStatusText(
                                if (previousText.startsWith(POLISHING_STATUS_PREFIX)) rawText
                                else previousText.ifBlank { rawText }
                            )
                        }
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
            .setPositiveButton("Use polished") { _, _ ->
                val finalText = polishedInput.text.toString().trim().ifBlank { polishedText }
                openResultAfterPolish(rawText, displayText = finalText, polishedForHistory = finalText, chosePolished = true)
            }
            .setNeutralButton("Use raw") { _, _ ->
                // Keep polished in history even if the user displays raw.
                val polishedFinal = polishedInput.text.toString().trim().ifBlank { polishedText }
                openResultAfterPolish(rawText, displayText = rawText, polishedForHistory = polishedFinal, chosePolished = false)
            }
            .setNegativeButton("Close") { _, _ ->
                // Default to polished on the editable result screen.
                val finalText = polishedInput.text.toString().trim().ifBlank { polishedText }
                openResultAfterPolish(rawText, displayText = finalText, polishedForHistory = finalText, chosePolished = true)
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
                "${entry.formattedTimestamp()}$duration$badge\n${entry.preview(90)}"
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

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString().orEmpty())
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
            text = "When enabled, Stow runs a second free-tier-friendly Groq pass using the selected polish preset. Choosing raw or polished copies the text immediately and opens the editable result screen. Disable this if you prefer the raw transcript only."
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

                        try {
                            if (version.toDouble() > currentVersion.toDouble() && downloadUrl.isNotEmpty()) {
                                runOnUiThread {
                                    showUpdateDialog(version, downloadUrl)
                                }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "App is up to date", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (_: NumberFormatException) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Error parsing version", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
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
        /** Status messages during polish start with this; used to detect placeholder text. */
        private const val POLISHING_STATUS_PREFIX = "Polishing"
    }
}
