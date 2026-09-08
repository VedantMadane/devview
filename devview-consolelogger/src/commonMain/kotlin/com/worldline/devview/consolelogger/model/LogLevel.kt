package com.worldline.devview.consolelogger.model

import androidx.compose.runtime.Stable

/**
 * Severity levels for a captured [ConsoleLog] entry.
 *
 * These mirror Android's `Log`/logcat priority levels, with [ASSERT] mapped from logcat's
 * `F` ("What a Terrible Failure") priority and [UNKNOWN] reserved for lines the capture
 * layer could not classify (e.g. continuation lines of a multi-line message).
 *
 * @see ConsoleLog
 * @see com.worldline.devview.consolelogger.theme.LogColorScheme
 */
@Stable
public enum class LogLevel {
    /** Verbose diagnostic output; the lowest-priority, highest-volume level. */
    VERBOSE,

    /** Debug-only diagnostic output. */
    DEBUG,

    /** General informational messages. */
    INFO,

    /** Potentially harmful situations that do not stop execution. */
    WARNING,

    /** Error events that likely indicate a failure. */
    ERROR,

    /** Serious failures that should never happen ("What a Terrible Failure"). */
    ASSERT,

    /** A log line the capture layer could not classify into one of the levels above. */
    UNKNOWN
}
