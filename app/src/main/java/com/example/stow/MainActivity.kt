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
import android.text.util.Linkify
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
    private lateinit var tvTranscription: TextView

    private lateinit var btnSettings: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var btnJargon: ImageButton
    private lateinit var tvMicIndicator: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var tvUsage: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnPolish: Button

    private var isRecording = false
    private var lastRawTranscription: String = ""
    private var isPolishing = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.BROADCAST_STATE) {
                val state = intent.getStringExtra(RecordingService.EXTRA_STATE)
                val text = intent.getStringExtra(RecordingService.EXTRA_TEXT)

                when (state) {
                    RecordingService.STATE_RECORDING -> {
                        isRecording = true
                        btnRecord.text = "Stop Recording"
                        tvTranscription.text = "Recording..."
                        tvTranscription.scrollTo(0, 0)
                        btnPolish.isEnabled = false
                        lastRawTranscription = ""
                        
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        var isBluetooth = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val activeConfigs = audioManager.activeRecordingConfigurations
                            isBluetooth = activeConfigs.any { config ->
                                val type = config.audioDevice.type
                                type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            }
                        }
                        if (!isBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                            isBluetooth = devices.any { device ->
                                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            }
                        }
                        
                        if (isBluetooth) {
                            tvMicIndicator.text = "Bluetooth Mic"
                        } else {
                            tvMicIndicator.text = "Internal Mic"
                        }

                        chronometer.base = SystemClock.elapsedRealtime()
                        chronometer.start()
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_LOADING -> {
                        isRecording = false
                        btnRecord.text = "Start Recording"
                        tvTranscription.text = "Uploading and Transcribing..."
                        tvTranscription.scrollTo(0, 0)
                        btnPolish.isEnabled = false
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_SUCCESS -> {
                        val transcription = text.orEmpty()
                        lastRawTranscription = transcription
                        tvTranscription.text = transcription
                        tvTranscription.scrollTo(0, 0)
                        
                        val usage = intent.getIntExtra(RecordingService.EXTRA_USAGE, -1)
                        if (usage != -1) {
                            updateUsageText(usage)
                        }

                        if (isAutoPolishEnabled() && transcription.isNotBlank()) {
                            startPolish(transcription, autoTriggered = true)
                        } else {
                            btnPolish.isEnabled = transcription.isNotBlank()
                            Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                    RecordingService.STATE_ERROR -> {
                        isRecording = false
                        btnRecord.text = "Start Recording"
                        tvTranscription.text = text ?: "An error occurred"
                        tvTranscription.scrollTo(0, 0)
                        btnPolish.isEnabled = false
                        lastRawTranscription = ""
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
        tvTranscription = findViewById(R.id.tvTranscription)
        tvTranscription.movementMethod = android.text.method.ScrollingMovementMethod()
        btnSettings = findViewById(R.id.btnSettings)
        btnInfo = findViewById(R.id.btnInfo)
        btnJargon = findViewById(R.id.btnJargon)
        tvMicIndicator = findViewById(R.id.tvMicIndicator)
        chronometer = findViewById(R.id.chronometer)
        tvUsage = findViewById(R.id.tvUsage)
        tvVersion = findViewById(R.id.tvVersion)
        btnHistory = findViewById(R.id.btnHistory)
        btnPolish = findViewById(R.id.btnPolish)

        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        loadInitialUsage()

        if (getApiKey().isNullOrEmpty()) {
            showApiKeyDialog()
        }

        btnSettings.setOnClickListener {
            showApiKeyDialog()
        }

        btnInfo.setOnClickListener {
            showInfoDialog()
        }
        
        btnJargon.setOnClickListener {
            showJargonDialog()
        }
        
        btnHistory.setOnClickListener {
            checkStoragePermissionAndShowHistory()
        }

        btnPolish.setOnClickListener {
            val raw = lastRawTranscription.ifBlank {
                tvTranscription.text?.toString().orEmpty()
            }
            if (raw.isBlank() || raw == "Transcription will appear here..." ||
                raw == "Recording..." || raw == "Uploading and Transcribing..." ||
                raw == "Polishing..."
            ) {
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
            } catch (e: Exception) {}
        }
    }

    private fun checkPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startRecording()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 201) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showHistoryDialog()
            } else {
                Toast.makeText(this, "Storage permission required to view history", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_API_KEY, getApiKey())
            putExtra(RecordingService.EXTRA_JARGON, getJargon())
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

    private fun getApiKey(): String? {
        return sharedPreferences.getString("api_key", "")
    }

    private fun getJargon(): String? {
        return sharedPreferences.getString("api_jargon", "")
    }

    private fun isAutoPolishEnabled(): Boolean {
        return sharedPreferences.getBoolean(PREF_AUTO_POLISH, false)
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
        val previousText = tvTranscription.text?.toString().orEmpty()
        tvTranscription.text = "Polishing..."
        tvTranscription.scrollTo(0, 0)

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
                    tvTranscription.text = if (previousText == "Polishing...") rawText else previousText.ifBlank { rawText }
                    tvTranscription.scrollTo(0, 0)
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    if (autoTriggered) {
                        // Raw transcript was already copied by RecordingService
                        Toast.makeText(
                            this@MainActivity,
                            "Using raw transcription (copied to clipboard)",
                            Toast.LENGTH_SHORT
                        ).show()
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
            text = "Polished (editable)"
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

        val scrollView = ScrollView(this).apply {
            addView(container)
        }

        AlertDialog.Builder(this)
            .setTitle("Polish result")
            .setView(scrollView)
            .setPositiveButton("Copy polished") { _, _ ->
                val finalText = polishedInput.text.toString().trim()
                if (finalText.isEmpty()) {
                    Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                copyToClipboard(finalText)
                tvTranscription.text = finalText
                tvTranscription.scrollTo(0, 0)
                lastRawTranscription = rawText
                btnPolish.isEnabled = true
                Toast.makeText(this, "Polished text copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Use raw") { _, _ ->
                copyToClipboard(rawText)
                tvTranscription.text = rawText
                tvTranscription.scrollTo(0, 0)
                lastRawTranscription = rawText
                btnPolish.isEnabled = true
                Toast.makeText(this, "Raw text copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close") { _, _ ->
                tvTranscription.text = polishedText
                tvTranscription.scrollTo(0, 0)
                lastRawTranscription = rawText
                btnPolish.isEnabled = true
            }
            .setCancelable(true)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

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
            text = "When enabled, Stow runs a second Groq pass to clean fillers and grammar. You can still polish manually with the Polish button."
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

    private fun checkStoragePermissionAndShowHistory() {
        showHistoryDialog()
    }

    private fun showHistoryDialog() {
        var content = ""
        try {
            val documentsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            val logFile = java.io.File(documentsDir, "Stow_Log.txt")
            if (logFile.exists()) {
                content = logFile.readText()
            } else {
                content = "No recording history found."
            }
        } catch (e: Exception) {
            content = "Error reading history: ${e.message}"
        }
        
        val scrollView = android.widget.ScrollView(this)
        val textView = TextView(this).apply {
            text = content
            setPadding(50, 40, 50, 40)
            textSize = 14f
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("Transcription History")
            .setView(scrollView)
            .setPositiveButton("Close", null)
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

    private var downloadReceiver: android.content.BroadcastReceiver? = null

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
                        } catch (e: NumberFormatException) {
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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

        val downloadManager = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val downloadId = downloadManager.enqueue(request)

        downloadReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName)
                    try {
                        unregisterReceiver(this)
                    } catch (e: Exception) {}
                    downloadReceiver = null
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE))
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

            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
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
