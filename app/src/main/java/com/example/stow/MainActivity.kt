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
    private lateinit var btnCopyResult: Button
    private lateinit var btnShareResult: Button
    private lateinit var btnDiscardResult: Button

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
                            Toast.makeText(this@MainActivity, "Transcription ready", Toast.LENGTH_SHORT).show()
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
        btnCopyResult = findViewById(R.id.btnCopyResult)
        btnShareResult = findViewById(R.id.btnShareResult)
        btnDiscardResult = findViewById(R.id.btnDiscardResult)

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

        btnShareResult.setOnClickListener {
            val text = currentEditableText()
            if (text.isBlank()) {
                Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            persistEditedResult(text)
            shareText(text, "Share transcription")
        }

        btnDiscardResult.setOnClickListener {
            discardResultAndReturnToRecording()
        }

        btnPolish.setOnClickListener {
            val raw = lastRawTranscription.ifBlank { currentEditableText() }
            if (!isUsableTranscription(raw)) {
                Toast.makeText(this, "No transcription to polish", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startPolish(raw, autoTriggered = false)
        }

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
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
            text != "Polishing..." &&
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
        chronometer.visibility = View.VISIBLE
        setTranscriptionEditable(false)
    }

    private fun showResultUi() {
        showingResult = true
        tvResultLabel.visibility = View.VISIBLE
        resultActionRow.visibility = View.VISIBLE
        btnRecord.visibility = View.GONE
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
        showingPolished: Boolean = polishedText != null && displayText == polishedText
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

        // Do not auto-copy; user chooses Copy on the result screen.
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

    private fun discardResultAndReturnToRecording() {
        val edited = currentEditableText()
        if (isUsableTranscription(edited)) {
            persistEditedResult(edited)
        }
        lastRawTranscription = ""
        lastDurationSeconds = null
        lastHistoryEntryId = null
        resultShowsPolished = false
        btnPolish.isEnabled = false
        showRecordingUi()
        setStatusText("Transcription will appear here...")
        Toast.makeText(this, "Ready for a new recording", Toast.LENGTH_SHORT).show()
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

        isPolishing = true
        btnPolish.isEnabled = false
        val previousText = etTranscription.text?.toString().orEmpty()
        if (!showingResult) {
            setStatusText("Polishing...")
        }

        polisher.polish(
            rawText = rawText,
            apiKey = apiKey,
            jargon = getJargon().orEmpty(),
            onSuccess = { polished ->
                runOnUiThread {
                    isPolishing = false
                    btnPolish.isEnabled = true
                    showPolishResultDialog(rawText, polished)
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
                            "Polish failed — showing raw transcription",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        if (!showingResult) {
                            setStatusText(
                                if (previousText == "Polishing...") rawText
                                else previousText.ifBlank { rawText }
                            )
                        }
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun showPolishResultDialog(rawText: String, polishedText: String) {
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
            text = "Polished (preview)"
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
                Toast.makeText(
                    this,
                    if (chosePolished) "Polished text ready — edit, copy, or share"
                    else "Raw text ready — edit, copy, or share",
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
            showingPolished = chosePolished
        )
        Toast.makeText(
            this,
            if (chosePolished) "Polished text ready — edit, copy, or share" else "Raw text ready — edit, copy, or share",
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

        isPolishing = true
        Toast.makeText(this, "Re-polishing…", Toast.LENGTH_SHORT).show()

        polisher.polish(
            rawText = entry.rawText,
            apiKey = apiKey,
            jargon = getJargon().orEmpty(),
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
            text = "When enabled, Stow runs a second free-tier-friendly Groq pass for light cleanup only (fillers, spelling, grammar, punctuation; minimal rephrasing). After polish (or raw transcription), you land on an editable result screen to correct, copy, or share. Disable this if you prefer the raw transcript only."
            textSize = 12f
            setPadding(8, 8, 8, 0)
        }
        layout.addView(autoPolishHint)

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
                "After each transcription you get an editable result screen: fix typos, then Copy, Share, or start a New recording.\n\n" +
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
    }
}
