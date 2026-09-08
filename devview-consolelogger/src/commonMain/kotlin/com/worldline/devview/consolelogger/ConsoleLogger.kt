package com.worldline.devview.consolelogger

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import com.worldline.devview.consolelogger.model.ConsoleLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Central sink for captured console log lines.
 *
 * Unlike [com.worldline.devview.analytics.AnalyticsLogger][analytics logger], which appends
 * hand-placed events directly to its backing list, [ConsoleLogger.log] writes into a
 * bounded, drop-oldest [Channel] instead. Native console capture (logcat on Android, a
 * stdout/stderr redirect on iOS) runs off the main thread and can emit far faster than the
 * UI can usefully render, so the channel both makes [log] safe to call from those capture
 * threads and lets the single collector in [collectInto] drain everything currently buffered
 * in one uninterrupted burst — Compose coalesces the resulting snapshot writes into a single
 * recomposition rather than one per line.
 *
 * The channel's capacity — and the retained history size — is configured once via
 * [configure], called by `Console.initModule()` before the collector starts.
 *
 * @see ConsoleLog
 * @see LocalConsoleLogs
 */
public object ConsoleLogger {
    /** Capacity used before [configure] has run, e.g. if [log] is called very early. */
    internal const val DEFAULT_MAX_ENTRIES: Int = 1_000

    private val consoleLogs = mutableStateListOf<ConsoleLog>()

    private var maxEntries: Int = DEFAULT_MAX_ENTRIES
    private var channel: Channel<ConsoleLog> = newChannel(capacity = DEFAULT_MAX_ENTRIES)

    /**
     * Appends a new console log line.
     *
     * Safe to call from any thread, including the background thread(s) driving native
     * console capture. If the channel is full, the oldest buffered-but-not-yet-drained
     * entry is dropped in favor of this one.
     *
     * @param log The console log entry to append.
     */
    public fun log(log: ConsoleLog) {
        channel.trySend(element = log)
    }

    /**
     * Clears all stored console logs.
     *
     * This method is intended for internal use by the DevView framework.
     */
    internal fun clear() {
        consoleLogs.clear()
    }

    /**
     * (Re)configures the retained history size and the drop-oldest channel capacity.
     *
     * Must be called before [collectInto] starts collecting; calling it afterwards has no
     * effect on the already-running collector.
     */
    internal fun configure(maxEntries: Int) {
        require(value = maxEntries > 0) { "maxEntries must be > 0, was $maxEntries" }
        this.maxEntries = maxEntries
        channel = newChannel(capacity = maxEntries)
    }

    /**
     * Drains [log] entries as they arrive, evicting from the head once [maxEntries] is
     * exceeded. Suspends until cancelled.
     */
    internal suspend fun collectInto() {
        for (first in channel) {
            appendBounded(log = first)
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                appendBounded(log = next)
            }
        }
    }

    private fun appendBounded(log: ConsoleLog) {
        if (consoleLogs.size >= maxEntries) {
            consoleLogs.removeAt(index = 0)
        }
        consoleLogs.add(element = log)
    }

    /**
     * Returns a snapshot state list of all retained console logs, oldest first.
     *
     * This property provides reactive access to the logs, meaning any Compose UI observing
     * this property will automatically recompose when logs are added or removed.
     */
    public val logs: SnapshotStateList<ConsoleLog>
        get() = consoleLogs

    /**
     * Indicates whether any console logs have been recorded.
     */
    public val hasLogs: Boolean
        get() = consoleLogs.isNotEmpty()

    private fun newChannel(capacity: Int): Channel<ConsoleLog> =
        Channel(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

/**
 * CompositionLocal for providing console logs to Compose UI components.
 *
 * The DevView host wraps module content with
 * `CompositionLocalProvider(LocalConsoleLogs provides ConsoleLogger.logs)` before rendering
 * module destinations.
 *
 * @throws IllegalStateException if accessed without being provided in the Compose tree.
 *
 * @see ConsoleLogger
 */
public val LocalConsoleLogs: ProvidableCompositionLocal<List<ConsoleLog>> =
    staticCompositionLocalOf {
        error(message = "No console logs provided.")
    }
