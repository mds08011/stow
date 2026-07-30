package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of [TranscriptionHistory] that can silently lose user data: JSON
 * serialisation, the legacy plain-text migration, and search matching.
 */
class TranscriptionHistoryTest {

    private fun entry(
        id: String = "id-1",
        timestampMillis: Long = 1_700_000_000_000L,
        durationSeconds: Int? = 42,
        rawText: String = "raw text",
        polishedText: String? = "polished text",
        routeLabel: String? = null,
        routeSampleRate: Int? = null
    ) = TranscriptionHistory.Entry(
        id, timestampMillis, durationSeconds, rawText, polishedText, routeLabel, routeSampleRate
    )

    // region JSON round-trip

    @Test
    fun `round trip preserves all fields`() {
        val original = listOf(
            entry(id = "a", rawText = "first raw", polishedText = "first polished"),
            entry(id = "b", timestampMillis = 1L, durationSeconds = 7, rawText = "second", polishedText = null)
        )

        val restored = TranscriptionHistory.parseJson(TranscriptionHistory.serializeEntries(original))

        assertEquals(original, restored)
    }

    @Test
    fun `round trip preserves null duration and null polished text`() {
        val original = listOf(entry(durationSeconds = null, polishedText = null))

        val restored = TranscriptionHistory.parseJson(TranscriptionHistory.serializeEntries(original))

        assertEquals(1, restored.size)
        assertNull(restored[0].durationSeconds)
        assertNull(restored[0].polishedText)
        assertEquals("raw text", restored[0].rawText)
    }

    @Test
    fun `round trip preserves multi-line text`() {
        val multiline = "line one\nline two\n\nline four"
        val restored = TranscriptionHistory.parseJson(
            TranscriptionHistory.serializeEntries(listOf(entry(rawText = multiline, polishedText = null)))
        )

        assertEquals(multiline, restored[0].rawText)
    }

    @Test
    fun `blank polished text is normalised to null`() {
        val restored = TranscriptionHistory.parseJson(
            """[{"id":"a","timestampMillis":1,"durationSeconds":null,"rawText":"x","polishedText":"   "}]"""
        )

        assertNull(restored[0].polishedText)
    }

    @Test
    fun `empty and blank json parse to empty list`() {
        assertTrue(TranscriptionHistory.parseJson("").isEmpty())
        assertTrue(TranscriptionHistory.parseJson("   ").isEmpty())
        assertTrue(TranscriptionHistory.parseJson("[]").isEmpty())
    }

    @Test
    fun `round trip preserves the recorded audio route`() {
        val original = listOf(
            entry(id = "a", routeLabel = "Phone mic", routeSampleRate = 16000),
            entry(id = "b", routeLabel = "Bluetooth (SCO) · Pixel Buds", routeSampleRate = 8000)
        )

        val restored = TranscriptionHistory.parseJson(TranscriptionHistory.serializeEntries(original))

        assertEquals(original, restored)
    }

    @Test
    fun `entries written before route capture parse with null route`() {
        // Exactly the shape older installs already have on disk.
        val legacyJson = """
            [{"id":"a","timestampMillis":1,"durationSeconds":30,
              "rawText":"old note","polishedText":null}]
        """.trimIndent()

        val restored = TranscriptionHistory.parseJson(legacyJson)

        assertEquals(1, restored.size)
        assertNull(restored[0].routeLabel)
        assertNull(restored[0].routeSampleRate)
        assertNull(restored[0].formattedRoute())
        assertEquals("old note", restored[0].rawText)
    }

    @Test
    fun `blank or non-positive route values normalise to null`() {
        val restored = TranscriptionHistory.parseJson(
            """[{"id":"a","timestampMillis":1,"durationSeconds":null,"rawText":"x",
                 "polishedText":null,"routeLabel":"  ","routeSampleRate":0}]"""
        )

        assertNull(restored[0].routeLabel)
        assertNull(restored[0].routeSampleRate)
    }

    @Test
    fun `formatted route renders label and rate`() {
        assertEquals(
            "Phone mic · 16 kHz",
            entry(routeLabel = "Phone mic", routeSampleRate = 16000).formattedRoute()
        )
        assertEquals("Phone mic", entry(routeLabel = "Phone mic").formattedRoute())
        assertEquals("Unknown mic · 16 kHz", entry(routeSampleRate = 16000).formattedRoute())
        assertNull(entry().formattedRoute())
    }

    @Test
    fun `export includes the mic line only when known`() {
        val withRoute = entry(
            polishedText = null,
            routeLabel = "Bluetooth (SCO)",
            routeSampleRate = 8000
        ).toExportBlock()
        assertTrue(withRoute.contains("[Mic] Bluetooth (SCO) · 8 kHz"))

        assertFalse(entry(polishedText = null).toExportBlock().contains("[Mic]"))
    }

    // endregion

    // region Legacy migration

    @Test
    fun `legacy log migrates entries newest first`() {
        val log = """
            --- 2024-01-01 09:00:00 ---
            oldest note
            --- 2024-03-15 14:30:00 (polished) ---
            newest note
            --- 2024-02-10 11:15:00 ---
            middle note
        """.trimIndent()

        val entries = TranscriptionHistory.parseLegacyLog(log)

        assertEquals(3, entries.size)
        assertEquals(listOf("newest note", "middle note", "oldest note"), entries.map { it.rawText })
        assertTrue(entries[0].timestampMillis > entries[1].timestampMillis)
        assertTrue(entries[1].timestampMillis > entries[2].timestampMillis)
    }

    @Test
    fun `legacy polished marker sets polished text`() {
        val entries = TranscriptionHistory.parseLegacyLog(
            "--- 2024-03-15 14:30:00 (polished) ---\ncleaned up note"
        )

        assertEquals(1, entries.size)
        assertEquals("cleaned up note", entries[0].polishedText)
        assertEquals("cleaned up note", entries[0].rawText)
    }

    @Test
    fun `legacy entry without marker has no polished text`() {
        val entries = TranscriptionHistory.parseLegacyLog("--- 2024-03-15 14:30:00 ---\nplain note")

        assertNull(entries[0].polishedText)
    }

    @Test
    fun `legacy multi-line bodies are kept intact`() {
        val entries = TranscriptionHistory.parseLegacyLog(
            "--- 2024-03-15 14:30:00 ---\nfirst line\nsecond line"
        )

        assertEquals("first line\nsecond line", entries[0].rawText)
    }

    @Test
    fun `blank legacy log migrates to nothing`() {
        assertTrue(TranscriptionHistory.parseLegacyLog("").isEmpty())
        assertTrue(TranscriptionHistory.parseLegacyLog("   \n  ").isEmpty())
    }

    // endregion

    // region Search

    @Test
    fun `search is case insensitive across raw and polished`() {
        val entries = listOf(
            entry(id = "a", rawText = "Clarifier weir needs cleaning", polishedText = null),
            entry(id = "b", rawText = "unrelated", polishedText = "Influent CHANNEL blocked"),
            entry(id = "c", rawText = "nothing relevant", polishedText = null)
        )

        assertEquals(listOf("a"), TranscriptionHistory.filterEntries(entries, "CLARIFIER").map { it.id })
        assertEquals(listOf("b"), TranscriptionHistory.filterEntries(entries, "channel").map { it.id })
    }

    @Test
    fun `empty query returns everything`() {
        val entries = listOf(entry(id = "a"), entry(id = "b"))

        assertEquals(entries, TranscriptionHistory.filterEntries(entries, ""))
        assertEquals(entries, TranscriptionHistory.filterEntries(entries, "   "))
    }

    @Test
    fun `search with no matches returns empty`() {
        val entries = listOf(entry(rawText = "valve", polishedText = null))

        assertTrue(TranscriptionHistory.filterEntries(entries, "zzz").isEmpty())
    }

    // endregion

    // region Entry helpers

    @Test
    fun `display text prefers polished when present`() {
        assertEquals("polished text", entry().displayText())
        assertEquals("raw text", entry(polishedText = null).displayText())
        assertEquals("raw text", entry(polishedText = "  ").displayText())
    }

    @Test
    fun `preview collapses newlines and truncates`() {
        val long = entry(polishedText = null, rawText = "a".repeat(200))
        assertEquals(81, long.preview(80).length) // 80 chars + the ellipsis character

        val wrapped = entry(polishedText = null, rawText = "one\ntwo")
        assertEquals("one two", wrapped.preview(80))
    }

    @Test
    fun `formatted duration renders minutes and seconds`() {
        assertEquals("45s", entry(durationSeconds = 45).formattedDuration())
        assertEquals("2m 5s", entry(durationSeconds = 125).formattedDuration())
        assertNull(entry(durationSeconds = null).formattedDuration())
        assertNull(entry(durationSeconds = -1).formattedDuration())
    }

    // endregion
}
