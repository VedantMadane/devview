package com.worldline.devview.consolelogger

import com.worldline.devview.consolelogger.model.LogLevel
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LogcatLineTest {

    @Test
    fun `every priority prefix maps to its LogLevel`() {
        val expected = mapOf(
            "V" to LogLevel.VERBOSE,
            "D" to LogLevel.DEBUG,
            "I" to LogLevel.INFO,
            "W" to LogLevel.WARNING,
            "E" to LogLevel.ERROR,
            "F" to LogLevel.ASSERT
        )

        expected.forEach { (priority, level) ->
            val log = parseLogcatLine(line = "$priority/MyTag( 1234): something happened")

            log.level shouldBe level
            log.tag shouldBe "MyTag"
            log.message shouldBe "something happened"
        }
    }

    @Test
    fun `tag containing spaces is preserved`() {
        val log = parseLogcatLine(line = "D/My Cool Tag( 42): hello")

        log.tag shouldBe "My Cool Tag"
        log.message shouldBe "hello"
    }

    @Test
    fun `message containing the delimiter sequence is preserved in full`() {
        val log = parseLogcatLine(line = "I/Tag( 1): result): still part of the message")

        log.message shouldBe "result): still part of the message"
    }

    @Test
    fun `unparseable line becomes UNKNOWN with the raw line as message`() {
        val line = "    at com.example.Foo.bar(Foo.kt:42)"

        val log = parseLogcatLine(line = line)

        log.level shouldBe LogLevel.UNKNOWN
        log.tag shouldBe ""
        log.message shouldBe line
    }
}
