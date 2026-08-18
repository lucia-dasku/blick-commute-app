package se.blick.app.scheduling

import android.annotation.SuppressLint
import android.os.Build
import androidx.work.WorkInfo

internal enum class WorkStopCategory {
    NOT_STOPPED,
    DELIBERATE_APP_CANCELLATION,
    CONSTRAINT_INTERRUPTION,
    QUOTA,
    TIME_LIMIT,
    USER_STOP,
    SYSTEM_INTERRUPTION,
    UNKNOWN,
    UNAVAILABLE,
}

internal data class ClassifiedWorkStopReason(
    val code: Int?,
    val name: String,
    val category: WorkStopCategory,
)

internal fun classifyWorkStopReason(reason: Int?): ClassifiedWorkStopReason = when (reason) {
    null -> ClassifiedWorkStopReason(null, "UNAVAILABLE_BELOW_API_31", WorkStopCategory.UNAVAILABLE)
    WorkInfo.STOP_REASON_NOT_STOPPED ->
        ClassifiedWorkStopReason(reason, "NOT_STOPPED", WorkStopCategory.NOT_STOPPED)
    WorkInfo.STOP_REASON_CANCELLED_BY_APP ->
        ClassifiedWorkStopReason(reason, "CANCELLED_BY_APP", WorkStopCategory.DELIBERATE_APP_CANCELLATION)
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW ->
        ClassifiedWorkStopReason(reason, "CONSTRAINT_BATTERY_NOT_LOW", WorkStopCategory.CONSTRAINT_INTERRUPTION)
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING ->
        ClassifiedWorkStopReason(reason, "CONSTRAINT_CHARGING", WorkStopCategory.CONSTRAINT_INTERRUPTION)
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY ->
        ClassifiedWorkStopReason(reason, "CONSTRAINT_CONNECTIVITY", WorkStopCategory.CONSTRAINT_INTERRUPTION)
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE ->
        ClassifiedWorkStopReason(reason, "CONSTRAINT_DEVICE_IDLE", WorkStopCategory.CONSTRAINT_INTERRUPTION)
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
        ClassifiedWorkStopReason(reason, "CONSTRAINT_STORAGE_NOT_LOW", WorkStopCategory.CONSTRAINT_INTERRUPTION)
    WorkInfo.STOP_REASON_QUOTA -> ClassifiedWorkStopReason(reason, "QUOTA", WorkStopCategory.QUOTA)
    WorkInfo.STOP_REASON_TIMEOUT -> ClassifiedWorkStopReason(reason, "TIMEOUT", WorkStopCategory.TIME_LIMIT)
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ->
        ClassifiedWorkStopReason(reason, "FOREGROUND_SERVICE_TIMEOUT", WorkStopCategory.TIME_LIMIT)
    WorkInfo.STOP_REASON_USER -> ClassifiedWorkStopReason(reason, "USER", WorkStopCategory.USER_STOP)
    WorkInfo.STOP_REASON_PREEMPT ->
        ClassifiedWorkStopReason(reason, "PREEMPT", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_DEVICE_STATE ->
        ClassifiedWorkStopReason(reason, "DEVICE_STATE", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION ->
        ClassifiedWorkStopReason(reason, "BACKGROUND_RESTRICTION", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_APP_STANDBY ->
        ClassifiedWorkStopReason(reason, "APP_STANDBY", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING ->
        ClassifiedWorkStopReason(reason, "SYSTEM_PROCESSING", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
        ClassifiedWorkStopReason(reason, "ESTIMATED_APP_LAUNCH_TIME_CHANGED", WorkStopCategory.SYSTEM_INTERRUPTION)
    WorkInfo.STOP_REASON_UNKNOWN -> ClassifiedWorkStopReason(reason, "UNKNOWN", WorkStopCategory.UNKNOWN)
    else -> ClassifiedWorkStopReason(reason, "UNRECOGNIZED", WorkStopCategory.UNKNOWN)
}

@SuppressLint("NewApi")
internal fun workInfoStopReasonOrNull(
    workInfo: WorkInfo,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int? = stopReasonForSdk(sdkInt) { workInfo.stopReason }

internal fun stopReasonForSdk(sdkInt: Int, readReason: () -> Int): Int? =
    if (sdkInt >= Build.VERSION_CODES.S) readReason() else null

internal fun formatWorkInfoDiagnostic(
    routineId: String,
    workerId: String,
    state: String,
    stopReason: Int?,
    sdkInt: Int,
): String {
    val classified = classifyWorkStopReason(stopReason)
    return "action=work_state routineId=$routineId workerId=$workerId state=$state " +
        "stopReasonCode=${classified.code ?: "unavailable"} stopReasonName=${classified.name} " +
        "stopCategory=${classified.category.name} sdk=$sdkInt"
}

internal enum class ActiveWindowRunOutcome {
    WINDOW_COMPLETED,
    ROUTINE_DELETED,
    ROUTINE_DISABLED,
    ROUTINE_PAUSED,
    HARD_RUNTIME_CAP,
    NOTIFICATIONS_UNAVAILABLE,
    UNEXPECTED_FAILURE,
    WORK_CANCELLED,
}

enum class RoutineCancellationReason {
    EXPLICIT_APP_CANCELLATION,
    USER_DISABLED,
    ROUTINE_DELETED,
}

internal fun formatActiveWindowOutcomeDiagnostic(
    routineId: String,
    workerId: String,
    outcome: ActiveWindowRunOutcome,
    sdkInt: Int,
): String = "action=worker_finished routineId=$routineId workerId=$workerId " +
    "outcome=${outcome.name} sdk=$sdkInt"
