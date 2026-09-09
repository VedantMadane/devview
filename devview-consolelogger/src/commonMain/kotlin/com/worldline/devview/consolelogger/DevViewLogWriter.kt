package com.worldline.devview.consolelogger

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import kotlin.time.Clock

/**
 * Optional [LogWriter] that forwards Kermit log calls to [ConsoleLogger].
 *
 * Native console capture (see [Console.captureNativeConsole]) cannot see `NSLog`/`os_log`/Swift
 * `Logger` output on an iOS device with no debugger attached. Routing your app's logging
 * through Kermit and registering this writer closes that gap on both platforms, since it
 * runs in-process and does not depend on a debugger being attached:
 *
 * ```kotlin
 * Logger.addLogWriter(DevViewLogWriter)
 * ```
 *
 * ## Caveat
 * On Android, if you *also* use Kermit's own `LogcatWriter`, entries logged through Kermit
 * will appear twice in the Console module — once via this writer, once via the native
 * logcat tail picking up what `LogcatWriter` already wrote. Use one or the other for a given
 * log call, or accept the duplication.
 *
 * @see ConsoleLogger
 */
public object DevViewLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        ConsoleLogger.log(
            log = ConsoleLog(
                level = severity.toLogLevel(),
                tag = tag,
                message = if (throwable != null) {
                    "$message\n${throwable.stackTraceToString()}"
                } else {
                    message
                },
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        )
    }
}

private fun Severity.toLogLevel(): LogLevel = when (this) {
    Severity.Verbose -> LogLevel.VERBOSE
    Severity.Debug -> LogLevel.DEBUG
    Severity.Info -> LogLevel.INFO
    Severity.Warn -> LogLevel.WARNING
    Severity.Error -> LogLevel.ERROR
    Severity.Assert -> LogLevel.ASSERT
}
