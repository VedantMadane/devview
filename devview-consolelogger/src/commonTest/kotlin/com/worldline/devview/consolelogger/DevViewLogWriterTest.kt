package com.worldline.devview.consolelogger

import co.touchlab.kermit.Severity
import com.worldline.devview.consolelogger.model.LogLevel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class DevViewLogWriterTest {

    @AfterTest
    fun tearDown() {
        ConsoleLogger.clear()
        ConsoleLogger.configure(maxEntries = ConsoleLogger.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun `every Severity maps to its LogLevel`() = runTest {
        ConsoleLogger.configure(maxEntries = 10)
        val job = launch { ConsoleLogger.collectInto() }

        val expected = mapOf(
            Severity.Verbose to LogLevel.VERBOSE,
            Severity.Debug to LogLevel.DEBUG,
            Severity.Info to LogLevel.INFO,
            Severity.Warn to LogLevel.WARNING,
            Severity.Error to LogLevel.ERROR,
            Severity.Assert to LogLevel.ASSERT
        )

        expected.forEach { (severity, _) ->
            DevViewLogWriter.log(severity = severity, message = "message", tag = "MyTag", throwable = null)
        }
        advanceUntilIdle()

        ConsoleLogger.logs.map { it.level } shouldBe expected.values.toList()

        job.cancelAndJoin()
    }

    @Test
    fun `message without a throwable is forwarded unchanged`() = runTest {
        ConsoleLogger.configure(maxEntries = 10)
        val job = launch { ConsoleLogger.collectInto() }

        DevViewLogWriter.log(severity = Severity.Info, message = "plain message", tag = "Tag", throwable = null)
        advanceUntilIdle()

        val log = ConsoleLogger.logs.single()
        log.message shouldBe "plain message"
        log.tag shouldBe "Tag"
        log.message.shouldNotContain("\n")

        job.cancelAndJoin()
    }

    @Test
    fun `throwable stack trace is appended to the message`() = runTest {
        ConsoleLogger.configure(maxEntries = 10)
        val job = launch { ConsoleLogger.collectInto() }
        val throwable = IllegalStateException("boom")

        DevViewLogWriter.log(severity = Severity.Error, message = "failed", tag = "Tag", throwable = throwable)
        advanceUntilIdle()

        val log = ConsoleLogger.logs.single()
        log.message.shouldContain("failed")
        log.message.shouldContain("IllegalStateException")
        log.message.shouldContain("boom")

        job.cancelAndJoin()
    }
}
