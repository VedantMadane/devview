package com.worldline.devview.consolelogger

import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._IOLBF
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.read
import platform.posix.setvbuf
import platform.posix.stdout
import platform.posix.write

private const val READ_BUFFER_SIZE = 4096

/**
 * Redirects `stdout` and `stderr` through in-process pipes so Kotlin `println`, Swift
 * `print`, and C `printf` output reaches [ConsoleLogger] even when no debugger is
 * attached. Bytes are teed back to the original file descriptor so Xcode's console still
 * shows everything when the device is tethered.
 *
 * This cannot see `NSLog`/`os_log`/Swift `Logger` output on a device with no debugger
 * attached — those bypass the file descriptor entirely. See [DevViewLogWriter] to close
 * that gap for logging routed through Kermit.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun tailNativeConsole(): Unit = coroutineScope {
    launch { redirectStream(fd = STDOUT_FILENO, level = LogLevel.INFO) }
    launch { redirectStream(fd = STDERR_FILENO, level = LogLevel.ERROR) }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun redirectStream(fd: Int, level: LogLevel): Unit = withContext(Dispatchers.IO) {
    val saved = dup(fd)
    val (readFd, writeFd) = memScoped {
        val fds = allocArray<IntVar>(2)
        pipe(fds)
        fds[0] to fds[1]
    }

    dup2(writeFd, fd)
    close(writeFd)

    // Non-blocking so a stalled reader (a full pipe buffer) drops writes instead of
    // hanging every subsequent print in the app that owns this file descriptor. Dropping
    // log lines is the correct trade for a dev tool.
    fcntl(fd, F_SETFL, O_NONBLOCK)

    if (fd == STDOUT_FILENO) {
        // A pipe is not a tty, so libc switches stdout to 4KB full buffering and lines
        // would arrive in bursts. Line-buffer instead so each println flushes promptly.
        setvbuf(stdout, null, _IOLBF, 0.convert())
    }

    val buffer = ByteArray(size = READ_BUFFER_SIZE)
    var pending = ""
    try {
        while (true) {
            currentCoroutineContext().ensureActive()

            val bytesRead = buffer.usePinned { pinned ->
                read(readFd, pinned.addressOf(index = 0), buffer.size.convert())
            }
            if (bytesRead <= 0) break

            buffer.usePinned { pinned ->
                write(saved, pinned.addressOf(index = 0), bytesRead.convert())
            }

            pending += buffer.decodeToString(endIndex = bytesRead.toInt())
            val lines = pending.split("\n")
            pending = lines.last()
            lines.dropLast(n = 1).forEach { line ->
                if (line.isNotEmpty()) {
                    ConsoleLogger.log(
                        log = ConsoleLog(
                            level = level,
                            tag = "",
                            message = line,
                            timestamp = Clock.System.now().toEpochMilliseconds()
                        )
                    )
                }
            }
        }
    } finally {
        close(readFd)
        dup2(saved, fd)
        close(saved)
    }
}
