package io.github.mds08011.stow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groq reports a rate-limit wait two ways and neither is reliable alone: a `Retry-After`
 * header, and prose in the error body. Ported from Stow Web — see docs/parity.md.
 */
class RetryAfterTest {

    private fun wait(header: String? = null, body: String? = null, status: Int = 429) =
        RecordingService.retryAfterSeconds(header, body, status)

    @Test
    fun `reads the retry-after header`() {
        assertEquals(30L, wait(header = "30"))
    }

    @Test
    fun `reads the prose form from the body`() {
        assertEquals(8L, wait(body = "Rate limit reached. Please try again in 7.66s."))
    }

    @Test
    fun `reads minutes and seconds from the prose form`() {
        // "2m59.56s" -> 179.56 -> ceil 180
        assertEquals(180L, wait(body = "Please try again in 2m59.56s."))
    }

    @Test
    fun `takes whichever source is larger`() {
        // Header says 5s, body says ~180s. Trusting the header alone would retry far too early.
        assertEquals(180L, wait(header = "5", body = "try again in 2m59.56s"))
        assertEquals(200L, wait(header = "200", body = "try again in 10.0s"))
    }

    @Test
    fun `rounds up so a retry never fires a moment early`() {
        assertEquals(8L, wait(body = "try again in 7.01s"))
        assertEquals(31L, wait(header = "30.2"))
    }

    @Test
    fun `falls back when the server says nothing useful`() {
        assertEquals(12L, wait(status = 429))
        assertEquals(6L, wait(status = 503))
    }

    @Test
    fun `garbage headers do not throw or produce nonsense`() {
        assertTrue(wait(header = "not-a-number") > 0)
        assertTrue(wait(header = "-5") > 0)
        assertTrue(wait(header = "") > 0)
    }

    @Test
    fun `never waits less than the floor`() {
        assertEquals(3L, wait(header = "1"))
        assertEquals(3L, wait(body = "try again in 0.2s"))
    }

    @Test
    fun `caps the wait rather than counting down forever`() {
        assertEquals(900L, wait(header = "99999"))
        assertEquals(900L, wait(body = "try again in 60m0.0s"))
    }

    @Test
    fun `non-retryable statuses report no wait`() {
        // A bad key or an oversized file will fail again immediately; offering a
        // countdown would imply waiting helps.
        assertEquals(0L, wait(status = 401, header = "30"))
        assertEquals(0L, wait(status = 413, body = "try again in 10s"))
        assertEquals(0L, wait(status = 400))
    }

    @Test
    fun `server errors are retryable`() {
        assertTrue(wait(status = 500) > 0)
        assertTrue(wait(status = 502, header = "20") == 20L)
    }

    @Test
    fun `error text explains the status instead of dumping json`() {
        assertTrue(RecordingService.describeApiError(401, "{}").contains("key was rejected"))
        assertTrue(RecordingService.describeApiError(413, "{}").contains("too large"))
        assertTrue(RecordingService.describeApiError(429, "{}").contains("rate limit", true))
        assertTrue(RecordingService.describeApiError(503, "{}").contains("server error"))
        // Anything unrecognised still shows the raw body rather than hiding it.
        assertTrue(RecordingService.describeApiError(418, "teapot").contains("teapot"))
    }
}
