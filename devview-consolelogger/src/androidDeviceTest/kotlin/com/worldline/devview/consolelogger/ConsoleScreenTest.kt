package com.worldline.devview.consolelogger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import com.worldline.devview.consolelogger.theme.LocalLogColorScheme
import com.worldline.devview.consolelogger.theme.LogColorScheme
import com.worldline.devview.consolelogger.theme.rememberLogColorScheme
import com.worldline.devview.test.waitUntilTagCount
import io.kotest.matchers.shouldBe
import org.junit.Test

class ConsoleScreenTest {

    @Test
    fun consoleScreen_shows_empty_state_when_no_logs() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalConsoleLogs provides emptyList(),
                LocalLogColorScheme provides LogColorScheme.Light
            ) {
                ConsoleScreen()
            }
        }

        waitUntilTagCount(tag = "console_empty_state", expectedCount = 1)
    }

    @Test
    fun consoleScreen_filters_by_query_and_clear_restores_results() = runComposeUiTest {
        val logs = listOf(
            log(tag = "network", message = "request sent"),
            log(tag = "ui", message = "button clicked")
        )

        setContent {
            CompositionLocalProvider(
                LocalConsoleLogs provides logs,
                LocalLogColorScheme provides LogColorScheme.Light
            ) {
                ConsoleScreen()
            }
        }

        waitUntilTagCount(tag = "console_log_item_0", expectedCount = 1)
        waitUntilTagCount(tag = "console_log_item_1", expectedCount = 1)

        onNodeWithTag(testTag = "console_filter_field").performTextInput("network")

        waitUntilTagCount(tag = "console_log_item_0", expectedCount = 1)
        waitUntilTagCount(tag = "console_log_item_1", expectedCount = 0)

        onNodeWithTag(testTag = "clear_filter_button").performClick()

        waitUntilTagCount(tag = "console_log_item_1", expectedCount = 1)
    }

    @Test
    fun consoleScreen_can_filter_by_level_chip() = runComposeUiTest {
        val logs = listOf(
            log(tag = "a", message = "info line", level = LogLevel.INFO),
            log(tag = "b", message = "error line", level = LogLevel.ERROR)
        )

        setContent {
            CompositionLocalProvider(
                LocalConsoleLogs provides logs,
                LocalLogColorScheme provides LogColorScheme.Light
            ) {
                ConsoleScreen()
            }
        }

        onNodeWithTag(testTag = "console_level_chip_ERROR").performClick()

        waitUntilTagCount(tag = "console_log_item_0", expectedCount = 1)
        waitUntilTagCount(tag = "console_log_item_1", expectedCount = 0)
    }

    @Test
    fun rememberLogColorScheme_honours_explicit_LocalLogColorScheme() = runComposeUiTest {
        var resolved: LogColorScheme? = null

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLogColorScheme provides LogColorScheme.Light) {
                    resolved = rememberLogColorScheme()
                }
            }
        }
        waitForIdle()

        resolved shouldBe LogColorScheme.Light
    }

    @Test
    fun rememberLogColorScheme_falls_back_to_dark_when_none_provided_and_theme_is_dark() = runComposeUiTest {
        var resolved: LogColorScheme? = null

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                resolved = rememberLogColorScheme()
            }
        }
        waitForIdle()

        resolved shouldBe LogColorScheme.Dark
    }

    private var logIdCounter = 0L

    private fun log(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        timestamp: Long = logIdCounter++
    ): ConsoleLog = ConsoleLog(level = level, tag = tag, message = message, timestamp = timestamp)
}
