package app.gamenative.service

import app.gamenative.service.download.GameDownloadService

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification tests for [GameDownloadService.isTransientFailure]: only transient
 * errors are auto-retried; permanent and unknown errors fail fast.
 */
class GameDownloadServiceRetryTest {

    @Test
    fun transient_errors_are_retried() {
        val transient = listOf(
            "download: chunk fetch timed out",
            "operation timed out",
            "connection reset by peer",
            "connection refused",
            "broken pipe",
            "unexpected EOF",
            "dns error: failed to lookup address",
            "network is unreachable",
            "non-200 HTTP status (503)",
            "non-200 HTTP status (429)",
            "status 502",
            "download: no CDN servers available",
            "no process-pool verdict for 60s",
            "service temporarily unavailable",
        )
        transient.forEach { msg ->
            assertTrue("expected transient: $msg", GameDownloadService.isTransientFailure(msg))
        }
    }

    @Test
    fun permanent_errors_are_not_retried() {
        val permanent = listOf(
            "download: manifest fetch failed for depot 219741: non-200 HTTP status (404)",
            "non-200 HTTP status (401)",
            "non-200 HTTP status (403)",
            "download: depot key unavailable for depot 219741",
            "no manifest gid for branch public",
            "Manifest contains unsafe path: ../evil",
            "no space left on device",
            "disk full",
            "download: filename decryption failed for depot 219741",
            "download: manifest parse failed for depot 219741",
            "Cancelled by user",
        )
        permanent.forEach { msg ->
            assertFalse("expected permanent: $msg", GameDownloadService.isTransientFailure(msg))
        }
    }

    @Test
    fun unknown_and_null_errors_fail_fast() {
        assertFalse(GameDownloadService.isTransientFailure(null))
        assertFalse(GameDownloadService.isTransientFailure(""))
        assertFalse(GameDownloadService.isTransientFailure("download: something unexpected happened"))
    }

    @Test
    fun permanent_marker_wins_over_transient_substring() {
        // A 404 wrapped in network-ish wording must still fail fast.
        assertFalse(
            GameDownloadService.isTransientFailure("network request failed: non-200 HTTP status (404)"),
        )
    }
}
