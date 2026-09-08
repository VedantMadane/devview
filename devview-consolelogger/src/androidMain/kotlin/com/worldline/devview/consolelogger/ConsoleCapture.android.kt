package com.worldline.devview.consolelogger

import co.touchlab.kermit.Logger
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Tails `logcat` scoped to this process's PID.
 *
 * Reading your own app's logcat needs no permission and works on an untethered, released
 * build. Some OEM ROMs block `logcat` for third-party apps entirely — in that case the
 * process fails to start or its output stream ends immediately, and this function simply
 * returns without native capture; [ConsoleLogger.log] and [DevViewLogWriter] still work.
 */
@Suppress("InjectDispatcher") // platform actual: no DI framework to inject a test dispatcher into
internal actual suspend fun tailNativeConsole(): Unit = withContext(context = Dispatchers.IO) {
    val process = try {
        ProcessBuilder("logcat", "-v", "brief", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true)
            .start()
    } catch (exception: IOException) {
        Logger.w(throwable = exception, tag = "DevViewConsole") {
            "logcat process failed to start; native console capture disabled for this session"
        }
        return@withContext
    }

    try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                currentCoroutineContext().ensureActive()
                ConsoleLogger.log(log = parseLogcatLine(line = line))
            }
        }
    } finally {
        // ponytail: the blocking readLine() above only observes cancellation once a line
        // arrives or the process exits; destroy() unblocks it immediately on cancellation.
        process.destroy()
    }
}
