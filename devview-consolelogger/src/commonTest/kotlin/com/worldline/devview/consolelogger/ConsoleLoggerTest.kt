package com.worldline.devview.consolelogger

import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ConsoleLoggerTest {

    @AfterTest
    fun tearDown() {
        ConsoleLogger.clear()
        ConsoleLogger.configure(maxEntries = ConsoleLogger.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun `log entries are drained into logs in order`() = runTest {
        ConsoleLogger.configure(maxEntries = 10)
        val job = launch { ConsoleLogger.collectInto() }

        ConsoleLogger.log(log = testLog(message = "first"))
        ConsoleLogger.log(log = testLog(message = "second"))
        advanceUntilIdle()

        ConsoleLogger.logs.map { it.message } shouldContainExactly listOf("first", "second")
        ConsoleLogger.hasLogs shouldBe true

        job.cancelAndJoin()
    }

    @Test
    fun `oldest entries are evicted past maxEntries`() = runTest {
        ConsoleLogger.configure(maxEntries = 2)
        val job = launch { ConsoleLogger.collectInto() }

        ConsoleLogger.log(log = testLog(message = "first"))
        ConsoleLogger.log(log = testLog(message = "second"))
        ConsoleLogger.log(log = testLog(message = "third"))
        advanceUntilIdle()

        ConsoleLogger.logs shouldHaveSize 2
        ConsoleLogger.logs.map { it.message } shouldContainExactly listOf("second", "third")

        job.cancelAndJoin()
    }

    @Test
    fun `clear removes all logged entries`() = runTest {
        ConsoleLogger.configure(maxEntries = 10)
        val job = launch { ConsoleLogger.collectInto() }

        ConsoleLogger.log(log = testLog(message = "only"))
        advanceUntilIdle()

        ConsoleLogger.clear()

        ConsoleLogger.hasLogs shouldBe false
        ConsoleLogger.logs shouldHaveSize 0

        job.cancelAndJoin()
    }

    @Test
    fun `configure rejects non-positive maxEntries`() {
        shouldThrow<IllegalArgumentException> {
            ConsoleLogger.configure(maxEntries = 0)
        }
    }

    private fun testLog(message: String): ConsoleLog = ConsoleLog(
        level = LogLevel.INFO,
        tag = "Test",
        message = message,
        timestamp = 1_700_000_000_000
    )
}
