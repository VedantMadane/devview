package com.worldline.devview.consolelogger.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import kotlin.time.Clock

/**
 * Preview parameter provider for lists of [ConsoleLog] in composable previews.
 *
 * Generates a sample list of console logs containing one entry for each [LogLevel]. This is
 * useful for previewing list-based UI components like the console screen with representative
 * data across every level.
 *
 * @see ConsoleLog
 * @see LogLevel
 * @see com.worldline.devview.consolelogger.ConsoleScreen
 */
internal class ConsoleLogListPreviewParameterProvider : PreviewParameterProvider<List<ConsoleLog>> {
    /**
     * Provides a sequence containing a single list of sample [ConsoleLog] instances, with
     * one log entry for each [LogLevel] variant.
     */
    override val values: Sequence<List<ConsoleLog>>
        get() = sequenceOf(
            element = LogLevel.entries.mapIndexed { index, level ->
                ConsoleLog(
                    level = level,
                    tag = "SampleTag",
                    message = "Sample $level message #$index",
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
            }
        )
}
