package com.example.stow

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Structured local transcription history.
 * Stored as JSON under app-specific Documents; migrates the legacy plain-text log once.
 */
object TranscriptionHistory {

    private const val HISTORY_FILE_NAME = "stow_history.json"
    private const val LEGACY_LOG_FILE_NAME = "Stow_Log.txt"

    data class Entry(
        val id: String,
        val timestampMillis: Long,
        val durationSeconds: Int?,
        val rawText: String,
        val polishedText: String?
    ) {
        fun displayText(): String {
            return polishedText?.takeIf { it.isNotBlank() } ?: rawText
        }

        fun preview(maxLen: Int = 80): String {
            val text = displayText().replace('\n', ' ').trim()
            return if (text.length <= maxLen) text else text.take(maxLen).trimEnd() + "…"
        }

        fun formattedTimestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(timestampMillis))
        }

        fun formattedDuration(): String? {
            val secs = durationSeconds ?: return null
            if (secs < 0) return null
            val m = secs / 60
            val s = secs % 60
            return if (m > 0) "${m}m ${s}s" else "${s}s"
        }

        fun toExportBlock(): String {
            val sb = StringBuilder()
            sb.append("--- ").append(formattedTimestamp())
            formattedDuration()?.let { sb.append(" · ").append(it) }
            if (!polishedText.isNullOrBlank()) {
                sb.append(" (polished)")
            }
            sb.append(" ---\n")
            sb.append(displayText().trim()).append("\n")
            if (!polishedText.isNullOrBlank() && polishedText != rawText) {
                sb.append("\n[Raw]\n").append(rawText.trim()).append("\n")
            }
            return sb.toString()
        }
    }

    fun add(
        context: Context,
        rawText: String,
        polishedText: String? = null,
        durationSeconds: Int? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ): Entry? {
        val raw = rawText.trim()
        if (raw.isBlank()) return null
        val polished = polishedText?.trim()?.takeIf { it.isNotEmpty() }
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            timestampMillis = timestampMillis,
            durationSeconds = durationSeconds,
            rawText = raw,
            polishedText = polished
        )
        val entries = loadEntries(context).toMutableList()
        entries.add(0, entry)
        saveEntries(context, entries)
        return entry
    }

    fun update(context: Context, entry: Entry): Boolean {
        val entries = loadEntries(context).toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) return false
        entries[index] = entry
        saveEntries(context, entries)
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        val entries = loadEntries(context).toMutableList()
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            saveEntries(context, entries)
        }
        return removed
    }

    fun getAll(context: Context): List<Entry> = loadEntries(context)

    fun getById(context: Context, id: String): Entry? =
        loadEntries(context).firstOrNull { it.id == id }

    fun search(context: Context, query: String): List<Entry> {
        val q = query.trim()
        val all = loadEntries(context)
        if (q.isEmpty()) return all
        val lower = q.lowercase(Locale.getDefault())
        return all.filter { entry ->
            entry.rawText.lowercase(Locale.getDefault()).contains(lower) ||
                (entry.polishedText?.lowercase(Locale.getDefault())?.contains(lower) == true) ||
                entry.formattedTimestamp().contains(q)
        }
    }

    fun exportAll(context: Context): String {
        val entries = loadEntries(context)
        if (entries.isEmpty()) return "No recording history found."
        return entries.joinToString("\n") { it.toExportBlock() }
    }

    fun exportOne(entry: Entry): String = entry.toExportBlock()

    /** @deprecated Prefer [add] with structured fields. Kept for callers during migration. */
    fun append(context: Context, text: String, polished: Boolean = false) {
        if (polished) {
            // Legacy polished-only lines: store as polished with same raw for completeness.
            add(context, rawText = text, polishedText = text, durationSeconds = null)
        } else {
            add(context, rawText = text, polishedText = null, durationSeconds = null)
        }
    }

    fun readAll(context: Context): String = exportAll(context)

    private fun historyFile(context: Context): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir != null && !documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        return File(documentsDir, HISTORY_FILE_NAME)
    }

    private fun legacyLogFile(context: Context): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(documentsDir, LEGACY_LOG_FILE_NAME)
    }

    @Synchronized
    private fun loadEntries(context: Context): List<Entry> {
        return try {
            val file = historyFile(context)
            if (file.exists()) {
                parseJson(file.readText())
            } else {
                val migrated = migrateLegacyLog(context)
                if (migrated.isNotEmpty()) {
                    saveEntries(context, migrated)
                }
                migrated
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    private fun saveEntries(context: Context, entries: List<Entry>) {
        try {
            val array = JSONArray()
            for (entry in entries) {
                array.put(entryToJson(entry))
            }
            historyFile(context).writeText(array.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun entryToJson(entry: Entry): JSONObject {
        return JSONObject().apply {
            put("id", entry.id)
            put("timestampMillis", entry.timestampMillis)
            if (entry.durationSeconds != null) {
                put("durationSeconds", entry.durationSeconds)
            } else {
                put("durationSeconds", JSONObject.NULL)
            }
            put("rawText", entry.rawText)
            if (entry.polishedText != null) {
                put("polishedText", entry.polishedText)
            } else {
                put("polishedText", JSONObject.NULL)
            }
        }
    }

    private fun parseJson(json: String): List<Entry> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        val list = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val duration = if (obj.isNull("durationSeconds")) null else obj.optInt("durationSeconds")
            val polished = if (obj.isNull("polishedText")) null else obj.optString("polishedText", null)
            list.add(
                Entry(
                    id = obj.getString("id"),
                    timestampMillis = obj.getLong("timestampMillis"),
                    durationSeconds = duration,
                    rawText = obj.getString("rawText"),
                    polishedText = polished?.takeIf { it.isNotBlank() }
                )
            )
        }
        return list
    }

    private fun migrateLegacyLog(context: Context): List<Entry> {
        val logFile = legacyLogFile(context)
        if (!logFile.exists()) return emptyList()
        return try {
            val content = logFile.readText()
            if (content.isBlank()) return emptyList()

            val headerRegex = Regex("""^---\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})(?:\s*\((polished)\))?\s*---\s*$""")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val entries = mutableListOf<Entry>()
            var currentTs = System.currentTimeMillis()
            var currentPolished = false
            val body = StringBuilder()

            fun flush() {
                val text = body.toString().trim()
                if (text.isNotEmpty()) {
                    entries.add(
                        Entry(
                            id = UUID.randomUUID().toString(),
                            timestampMillis = currentTs,
                            durationSeconds = null,
                            rawText = text,
                            polishedText = if (currentPolished) text else null
                        )
                    )
                }
                body.clear()
            }

            for (line in content.lines()) {
                val match = headerRegex.matchEntire(line.trim())
                if (match != null) {
                    flush()
                    currentTs = try {
                        dateFormat.parse(match.groupValues[1])?.time ?: System.currentTimeMillis()
                    } catch (_: Exception) {
                        System.currentTimeMillis()
                    }
                    currentPolished = match.groupValues.getOrNull(2) == "polished"
                } else {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(line)
                }
            }
            flush()

            // Newest first to match new storage order
            entries.sortedByDescending { it.timestampMillis }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
