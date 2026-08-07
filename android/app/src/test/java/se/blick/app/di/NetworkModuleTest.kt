package se.blick.app.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.scheduling.ACTIVE_WINDOW_REFRESH_INTERVAL_MS
import se.blick.app.scheduling.DISRUPTIONS_FETCH_TIMEOUT_MS

/**
 * Plain JVM tests for [NetworkModule]'s shared `OkHttpClient` configuration -- see
 * [NetworkModule.CALL_TIMEOUT_MS]'s own doc for why a whole-call timeout exists at all and why
 * this specific value was chosen.
 */
class NetworkModuleTest {

    @Test
    fun `the shared OkHttpClient has a whole-call timeout configured`() {
        val client = NetworkModule.provideOkHttpClient()

        assertEquals(NetworkModule.CALL_TIMEOUT_MS, client.callTimeoutMillis.toLong())
    }

    @Test
    fun `the call timeout is safely below the active-window refresh interval, and looser than the disruptions fetch timeout`() {
        assertTrue(
            "expected CALL_TIMEOUT_MS (${NetworkModule.CALL_TIMEOUT_MS}) to be safely below " +
                "ACTIVE_WINDOW_REFRESH_INTERVAL_MS ($ACTIVE_WINDOW_REFRESH_INTERVAL_MS)",
            NetworkModule.CALL_TIMEOUT_MS < ACTIVE_WINDOW_REFRESH_INTERVAL_MS,
        )
        // Departures is the primary, always-fetched-first data for a tick -- its own timeout
        // must never be the tightest budget in the loop; DISRUPTIONS_FETCH_TIMEOUT_MS already
        // bounds the secondary, best-effort fetch more tightly than this.
        assertTrue(
            "expected CALL_TIMEOUT_MS (${NetworkModule.CALL_TIMEOUT_MS}) to be looser than the " +
                "secondary DISRUPTIONS_FETCH_TIMEOUT_MS ($DISRUPTIONS_FETCH_TIMEOUT_MS)",
            NetworkModule.CALL_TIMEOUT_MS > DISRUPTIONS_FETCH_TIMEOUT_MS,
        )
    }
}
