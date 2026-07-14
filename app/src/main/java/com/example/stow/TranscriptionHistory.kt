package com.example.stow

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local transcription log under app-specific Documents.
 */
object TranscriptionHistory {

    private const val LOG_FILE_NAME = "Stow_Log.txt"

    fun append(context: Context, text: String, polished: Boolean = false) {
        if (text.isBlank()) return
        try {
            val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (documentsDir != null && !documentsDir.exists()) {
                documentsDir.mkdirs()
            }
            val logFile = File(documentsDir, LOG_FILE_NAME)
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = if (polished) {
                "--- $timeStamp (polished) ---"
            } else {
                "--- $timeStamp ---"
            }
            val entry = "$header\n$text\n\n"
            FileOutputStream(logFile, true).use {
                it.write(entry.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readAll(context: Context): String {
        return try {
            val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val logFile = File(documentsDir, LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "No recording history found."
            }
        } catch (e: Exception) {
            "Error reading history: ${e.message}"
        }
    }
}
