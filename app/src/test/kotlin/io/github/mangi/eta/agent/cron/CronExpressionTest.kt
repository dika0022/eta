package io.github.mangi.eta.agent.cron

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CronExpressionTest {
    @Test
    fun `supports minute steps and ranges`() {
        val expression = CronExpression.parse("*/15 9-10 * * *")
        val after = Instant.parse("2026-11-01T09:01:00Z").toEpochMilli()
        val next = expression.nextAfter(after, "UTC")
        assertEquals(Instant.parse("2026-11-01T09:15:00Z").toEpochMilli(), next)
    }

    @Test
    fun `supports weekday schedules`() {
        val expression = CronExpression.parse("0 9 * * 1-5")
        val after = Instant.parse("2026-10-30T10:00:00Z").toEpochMilli() // Friday
        val next = expression.nextAfter(after, "UTC")
        assertEquals(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), next) // Monday
    }

    @Test
    fun `uses standard OR semantics for restricted day fields`() {
        val expression = CronExpression.parse("0 9 1 * 1")
        val after = Instant.parse("2026-11-01T09:01:00Z").toEpochMilli() // Sunday, not day 1 any more
        val next = expression.nextAfter(after, "UTC")
        assertEquals(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), next) // Monday
    }

    @Test
    fun `accepts Sunday as seven`() {
        val expression = CronExpression.parse("0 8 * * 7")
        val after = Instant.parse("2026-11-01T07:59:00Z").toEpochMilli()
        val next = expression.nextAfter(after, "UTC")
        assertEquals(Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun `skips nonexistent local time during daylight saving transition`() {
        val expression = CronExpression.parse("30 2 * * *")
        val after = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()
        val next = expression.nextAfter(after, "America/New_York")
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `rejects unsupported or malformed expressions`() {
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("0 0 * *") }
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("0 0 ?,1 * *") }
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("60 * * * *") }
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("0 0 * JAN MON") }
    }
}
