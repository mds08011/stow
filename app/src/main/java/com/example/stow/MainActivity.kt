package com.example.stow

import android.Manifest
import android.content.BroadcastReceiver
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
import android.widget.Chronometer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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

    private var isRecording = false

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
                        chronometer.stop()
                        chronometer.base = SystemClock.elapsedRealtime()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    RecordingService.STATE_SUCCESS -> {
                        tvTranscription.text = text
                        
                        val usage = intent.getIntExtra(RecordingService.EXTRA_USAGE, -1)
                        if (usage != -1) {
                            updateUsageText(usage)
                        }

                        Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    RecordingService.STATE_ERROR -> {
                        isRecording = false
                        btnRecord.text = "Start Recording"
                        tvTranscription.text = text ?: "An error occurred"
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
        btnSettings = findViewById(R.id.btnSettings)
        btnInfo = findViewById(R.id.btnInfo)
        btnJargon = findViewById(R.id.btnJargon)
        tvMicIndicator = findViewById(R.id.tvMicIndicator)
        chronometer = findViewById(R.id.chronometer)
        tvUsage = findViewById(R.id.tvUsage)
        tvVersion = findViewById(R.id.tvVersion)
        btnHistory = findViewById(R.id.btnHistory)

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
            showHistoryDialog()
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
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startRecording()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
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

    private fun showApiKeyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")
        builder.setMessage("Please enter your Groq API key to use dictation.")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val apiKeyInput = EditText(this)
        apiKeyInput.setText(getApiKey())
        layout.addView(apiKeyInput)

        builder.setView(layout)

        builder.setPositiveButton("Save") { dialog, _ ->
            val key = apiKeyInput.text.toString().trim()
            sharedPreferences.edit()
                .putString("api_key", key)
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
            .show()
    }

    private fun showHistoryDialog() {
        val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        val logFile = java.io.File(documentsDir, "Stow_Log.txt")
        val content = if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: Exception) {
                "Error reading history."
            }
        } else {
            "No history found."
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
}
