package io.github.mds08011.stow

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Structured local transcription history.
 * Stored as JSON under app-specific Documents; migrates the legacy plain-text log once.
 *
 * Reads are served from an in-memory cache and writes are serialised onto a background
 * thread, so callers on the UI thread never block on file IO. Both the activity and the
 * recording service run in the same process, so a single cache is authoritative.
 */
object TranscriptionHistory {

    private const val HISTORY_FILE_NAME = "stow_history.json"
    private const val LEGACY_LOG_FILE_NAME = "Stow_Log.txt"

    @Volatile
    private var cache: List<Entry>? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stow-history-writer").apply { isDaemon = true }
    }

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
            return if (text.length <= maxLen) text else text.take(maxLen).trimEnd() + "â€¦"
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
            formattedDuration()?.let { sb.append(" Â· ").append(it) }
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

    fun search(context: Context, query: String): List<Entry> =
        filterEntries(loadEntries(context), query)

    /** Exposed for tests: the matching rule behind [search]. */
    internal fun filterEntries(entries: List<Entry>, query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        val lower = q.lowercase(Locale.getDefault())
        return entries.filter { entry ->
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
        cache?.let { return it }
        val loaded = try {
            val file = historyFile(context)
            if (file.exists()) {
                parseJson(file.readText())
            } else {
                val migrated = migrateLegacyLog(context)
                if (migrated.isNotEmpty()) {
                    cache = migrated
                    saveEntries(context, migrated)
                }
                migrated
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    private fun saveEntries(context: Context, entries: List<Entry>) {
        cache = entries
        // Resolve the file on the calling thread (it needs the Context), serialise and write
        // on the writer thread so the UI never blocks on a full rewrite.
        val file = try {
            historyFile(context)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        val snapshot = entries.toList()
        writeExecutor.execute {
            try {
                file.writeText(serializeEntries(snapshot))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Exposed for tests: the exact JSON written to disk. */
    internal fun serializeEntries(entries: List<Entry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(entryToJson(entry))
        }
        return array.toString(2)
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

    internal fun parseJson(json: String): List<Entry> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        val list = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val duration = if (obj.isNull("durationSeconds")) null else obj.optInt("durationSeconds")
            // isNull() already covers both a missing key and an explicit null, so the
            // single-argument optString is enough â€” passing a null fallback made Kotlin
            // infer Nothing? for the parameter.
            val polished = if (obj.isNull("polishedText")) null else obj.optString("polishedText")
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
            parseLegacyLog(logFile.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Exposed for tests: parses the pre-JSON plain-text log format. */
    internal fun parseLegacyLog(content: String): List<Entry> {
        return try {
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
