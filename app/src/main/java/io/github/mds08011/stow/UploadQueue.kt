package io.github.mds08011.stow

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recordings that still need a successful upload, oldest first.
 *
 * Until v2.8.4 there was one slot (`failed_audio_path`), so a second failure took the
 * pointer away from the first: its audio stayed on disk with nothing in the app leading
 * to it, and was pruned a few notes later. Two notes dictated back to back in a dead zone
 * meant one lost note. Every failure now joins this list and stays until it is
 * transcribed.
 *
 * Stored as a JSON array in SharedPreferences, like the rest of Stow's state. The list
 * operations are pure functions so they can be unit tested.
 */
object UploadQueue {

    data class Item(val path: String, val durationSeconds: Int, val addedAtMillis: Long)

    const val PREF_QUEUE = "upload_queue"

    fun parse(json: String?): List<Item> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val path = obj.optString("path", "")
                if (path.isEmpty()) {
                    null
                } else {
                    Item(path, obj.optInt("duration", 0), obj.optLong("addedAt", 0L))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serialize(items: List<Item>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("path", item.path)
                    .put("duration", item.durationSeconds)
                    .put("addedAt", item.addedAtMillis)
            )
        }
        return array.toString()
    }

    /**
     * Puts [item] at the back. A recording already in the list moves to the back, which
     * is what happens when a retry fails: the others get their turn first next time, so
     * one recording that can never succeed does not block the rest.
     */
    fun addOrMoveToBack(items: List<Item>, item: Item): List<Item> =
        items.filter { it.path != item.path } + item

    fun remove(items: List<Item>, path: String): List<Item> =
        items.filter { it.path != path }

    /**
     * The list with a pre-2.8.4 single failure folded in at the front, so an upgrade
     * does not drop the retry that was waiting.
     */
    fun withLegacy(items: List<Item>, legacyPath: String?, legacyDuration: Int): List<Item> {
        if (legacyPath.isNullOrEmpty() || items.any { it.path == legacyPath }) return items
        return listOf(Item(legacyPath, legacyDuration, 0L)) + items
    }

    fun load(prefs: SharedPreferences): List<Item> = withLegacy(
        parse(prefs.getString(PREF_QUEUE, null)),
        prefs.getString(RecordingService.PREF_FAILED_AUDIO_PATH, null),
        prefs.getInt(RecordingService.PREF_FAILED_AUDIO_DURATION, 0)
    )

    /** Writes the list and retires the legacy keys, which [load] has already folded in. */
    fun save(prefs: SharedPreferences, items: List<Item>) {
        prefs.edit()
            .putString(PREF_QUEUE, serialize(items))
            .remove(RecordingService.PREF_FAILED_AUDIO_PATH)
            .remove(RecordingService.PREF_FAILED_AUDIO_DURATION)
            .apply()
    }
}
