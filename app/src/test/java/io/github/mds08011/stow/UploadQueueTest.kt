package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** More than one recording can wait for signal, and none of them loses its place. */
class UploadQueueTest {

    private val first = UploadQueue.Item("/audio/audio_1.m4a", 60, 1_000)
    private val second = UploadQueue.Item("/audio/audio_2.m4a", 90, 2_000)

    @Test
    fun `a second failure keeps the first`() {
        val queue = UploadQueue.addOrMoveToBack(UploadQueue.addOrMoveToBack(emptyList(), first), second)

        assertEquals(listOf(first, second), queue)
    }

    @Test
    fun `a recording that fails again moves to the back and is not duplicated`() {
        val queue = UploadQueue.addOrMoveToBack(listOf(first, second), first)

        assertEquals(listOf(second, first), queue)
    }

    @Test
    fun `a transcribed recording leaves and the rest stay in order`() {
        assertEquals(listOf(second), UploadQueue.remove(listOf(first, second), first.path))
    }

    @Test
    fun `the list survives being written and read back`() {
        val queue = listOf(first, second)

        assertEquals(queue, UploadQueue.parse(UploadQueue.serialize(queue)))
    }

    @Test
    fun `missing or damaged storage reads as an empty list`() {
        assertTrue(UploadQueue.parse(null).isEmpty())
        assertTrue(UploadQueue.parse("").isEmpty())
        assertTrue(UploadQueue.parse("{ not a list").isEmpty())
        assertTrue(UploadQueue.parse("""[{"duration":5}]""").isEmpty())
    }

    @Test
    fun `a retry left by an older version goes to the front`() {
        val queue = UploadQueue.withLegacy(listOf(second), first.path, first.durationSeconds)

        assertEquals(listOf(first.path, second.path), queue.map { it.path })
        assertEquals(60, queue.first().durationSeconds)
    }

    @Test
    fun `the old retry is not added twice or when there is none`() {
        assertEquals(listOf(first), UploadQueue.withLegacy(listOf(first), first.path, 60))
        assertEquals(listOf(second), UploadQueue.withLegacy(listOf(second), null, 0))
        assertEquals(listOf(second), UploadQueue.withLegacy(listOf(second), "", 0))
    }
}
