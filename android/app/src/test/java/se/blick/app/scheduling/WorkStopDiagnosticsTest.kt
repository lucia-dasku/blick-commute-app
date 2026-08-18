package se.blick.app.scheduling

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkStopDiagnosticsTest {
    @Test
    fun `quota and deliberate cancellation remain distinct`() {
        assertEquals(WorkStopCategory.QUOTA, classifyWorkStopReason(WorkInfo.STOP_REASON_QUOTA).category)
        assertEquals(
            ClassifiedWorkStopReason(
                WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
                "FOREGROUND_SERVICE_TIMEOUT",
                WorkStopCategory.TIME_LIMIT,
            ),
            classifyWorkStopReason(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT),
        )
        assertEquals(
            WorkStopCategory.DELIBERATE_APP_CANCELLATION,
            classifyWorkStopReason(WorkInfo.STOP_REASON_CANCELLED_BY_APP).category,
        )
    }

    @Test
    fun `constraints and system interruptions are classified without guessing`() {
        assertEquals(
            WorkStopCategory.CONSTRAINT_INTERRUPTION,
            classifyWorkStopReason(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY).category,
        )
        assertEquals(
            WorkStopCategory.SYSTEM_INTERRUPTION,
            classifyWorkStopReason(WorkInfo.STOP_REASON_DEVICE_STATE).category,
        )
        assertEquals(WorkStopCategory.UNKNOWN, classifyWorkStopReason(999).category)
    }

    @Test
    fun `older Android fallback reports unavailable rather than a made-up reason`() {
        var readCalled = false
        val rawReason = stopReasonForSdk(30) {
            readCalled = true
            WorkInfo.STOP_REASON_QUOTA
        }
        assertEquals(null, rawReason)
        assertFalse(readCalled)
        val reason = classifyWorkStopReason(null)
        assertEquals(WorkStopCategory.UNAVAILABLE, reason.category)
        assertEquals("UNAVAILABLE_BELOW_API_31", reason.name)
    }

    @Test
    fun `diagnostic strings contain identifiers and platform metadata only`() {
        val message = formatWorkInfoDiagnostic(
            routineId = "routine-internal-id",
            workerId = "worker-id",
            state = "ENQUEUED",
            stopReason = WorkInfo.STOP_REASON_QUOTA,
            sdkInt = 36,
        )
        assertTrue(message.contains("routineId=routine-internal-id"))
        assertTrue(message.contains("stopReasonName=QUOTA"))
        assertFalse(message.contains("station", ignoreCase = true))
        assertFalse(message.contains("destination", ignoreCase = true))
        assertFalse(message.contains("purchase", ignoreCase = true))
    }

    @Test
    fun `worker outcomes distinguish normal end pause disable and cancellation`() {
        val outcomes = listOf(
            ActiveWindowRunOutcome.WINDOW_COMPLETED,
            ActiveWindowRunOutcome.ROUTINE_PAUSED,
            ActiveWindowRunOutcome.ROUTINE_DISABLED,
            ActiveWindowRunOutcome.WORK_CANCELLED,
        ).map { outcome ->
            formatActiveWindowOutcomeDiagnostic("routine-id", "worker-id", outcome, 36)
        }
        assertTrue(outcomes[0].contains("WINDOW_COMPLETED"))
        assertTrue(outcomes[1].contains("ROUTINE_PAUSED"))
        assertTrue(outcomes[2].contains("ROUTINE_DISABLED"))
        assertTrue(outcomes[3].contains("WORK_CANCELLED"))
    }
}
