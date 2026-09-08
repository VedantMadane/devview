package com.worldline.devview.consolelogger

/**
 * Captures the platform's native console output and forwards each line to
 * [ConsoleLogger.log] until the calling coroutine is cancelled.
 *
 * - **Android**: tails `logcat` scoped to this process (`--pid=<self>`), so it needs no
 *   permission and works on an untethered, released build.
 * - **iOS**: redirects `stdout`/`stderr` through a pipe read in-process, so it also works
 *   without a debugger attached. It cannot see `NSLog`/`os_log`/Swift `Logger` output on a
 *   device with no debugger attached — see the module's documentation for the full capture
 *   matrix and the [DevViewLogWriter] mitigation.
 */
internal expect suspend fun tailNativeConsole()
