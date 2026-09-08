package com.worldline.devview.consolelogger

import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import kotlin.time.Clock

// Matches a single `logcat -v brief` line, e.g. `D/MyTag( 1234): message text`.
// Group 1 is the one-letter priority, group 2 the tag, group 3 the message. Continuation
// lines of a multi-line message don't match and fall back to LogLevel.UNKNOWN below.
private val LOGCAT_BRIEF_LINE = Regex(pattern = """^([VDIWEF])/(.+?)\(\s*\d+\): (.*)$""")

/**
 * Parses a single line of `logcat -v brief` output into a [ConsoleLog].
 *
 * Lines that don't match the expected `<priority>/<tag>( <pid>): <message>` shape — such as
 * continuation lines of a multi-line message — become a [LogLevel.UNKNOWN] entry carrying
 * the raw line as its message, so nothing captured is silently dropped.
 */
internal fun parseLogcatLine(line: String): ConsoleLog {
    val match = LOGCAT_BRIEF_LINE.matchEntire(input = line)
        ?: return ConsoleLog(
            level = LogLevel.UNKNOWN,
            tag = "",
            message = line,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )

    val (priority, tag, message) = match.destructured
    return ConsoleLog(
        level = priority.toLogLevel(),
        tag = tag,
        message = message,
        timestamp = Clock.System.now().toEpochMilliseconds()
    )
}

private fun String.toLogLevel(): LogLevel = when (this) {
    "V" -> LogLevel.VERBOSE
    "D" -> LogLevel.DEBUG
    "I" -> LogLevel.INFO
    "W" -> LogLevel.WARNING
    "E" -> LogLevel.ERROR
    "F" -> LogLevel.ASSERT
    else -> LogLevel.UNKNOWN
}
