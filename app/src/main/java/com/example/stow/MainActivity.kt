package com.example.stow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

import android.app.AlertDialog
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.os.Environment
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var btnRecord: Button
    private lateinit var tvTranscription: TextView

    private lateinit var btnSettings: ImageButton

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("StowPrefs", Context.MODE_PRIVATE)

        btnRecord = findViewById(R.id.btnRecord)
        tvTranscription = findViewById(R.id.tvTranscription)
        btnSettings = findViewById(R.id.btnSettings)

        if (getApiKey().isNullOrEmpty()) {
            showApiKeyDialog()
        }

        btnSettings.setOnClickListener {
            showApiKeyDialog()
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

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            200
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
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
                btnRecord.text = "Stop Recording"
                tvTranscription.text = "Recording..."
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            btnRecord.text = "Start Recording"
            tvTranscription.text = "Loading..."
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            audioFile?.let {
                sendAudioToGroq(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAudioToGroq(file: File) {
        if (!isNetworkAvailable()) {
            tvTranscription.text = "Error: No active internet connection found."
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${getApiKey()}")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvTranscription.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonObject = JSONObject(responseBody)
                        val text = jsonObject.getString("text")
                        
                        // Cleanup temporary audio file
                        if (file.exists()) {
                            file.delete()
                        }
                        
                        saveToLog(text)
                        
                        runOnUiThread {
                            tvTranscription.text = text
                            copyToClipboard(text)
                            Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            tvTranscription.text = "Error parsing response"
                        }
                    }
                } else {
                    runOnUiThread {
                        tvTranscription.text = "API Error: ${response.code}\n$responseBody"
                    }
                }
            }
        })
    }

    private fun getApiKey(): String? {
        return sharedPreferences.getString("api_key", "")
    }

    private fun showApiKeyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Groq API Key")
        builder.setMessage("Please enter your Groq API key to use dictation.")

        val input = EditText(this)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        input.layoutParams = lp
        input.setText(getApiKey())
        builder.setView(input)

        builder.setPositiveButton("Save") { dialog, _ ->
            val key = input.text.toString().trim()
            sharedPreferences.edit().putString("api_key", key).apply()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.setCancelable(false)
        builder.show()
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

    private fun saveToLog(text: String) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!documentsDir.exists()) {
                documentsDir.mkdirs()
            }
            val logFile = File(documentsDir, "Stow_Log.txt")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timeStamp = dateFormat.format(Date())
            
            val entry = "--- $timeStamp ---\n$text\n\n"
            
            FileOutputStream(logFile, true).use {
                it.write(entry.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
