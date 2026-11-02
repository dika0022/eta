package io.github.mangi.eta.agent.cron

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Small, dependency-free five-field cron parser.
 *
 * Supported syntax: *, ?, lists, ranges and steps (for example star-step-15, 1-5, 1,15,30-45-step-5).
 * Field order: minute hour day-of-month month day-of-week. Sunday may be 0 or 7.
 * Names (JAN/MON), seconds and year fields are intentionally not accepted.
 */
internal class CronExpression private constructor(
    private val minute: Field,
    private val hour: Field,
    private val dayOfMonth: Field,
    private val month: Field,
    private val dayOfWeek: Field,
) {
    fun nextAfter(afterEpochMillis: Long, zoneId: String): Long {
        val zone = try {
            ZoneId.of(zoneId)
        } catch (error: Exception) {
            throw IllegalArgumentException("Zona waktu tidak dikenal: $zoneId", error)
        }
        val after = Instant.ofEpochMilli(afterEpochMillis)
        var date = after.atZone(zone).toLocalDate()

        // Eight years covers a complete leap-year cycle with room for sparse expressions such as 29 Feb.
        repeat(MAX_SEARCH_DAYS) {
            if (month.matches(date.monthValue) && matchesDay(date)) {
                for (hourValue in hour.sortedValues) {
                    for (minuteValue in minute.sortedValues) {
                        val localDateTime = date.atTime(hourValue, minuteValue)
                        val candidate = localDateTime.atZone(zone)
                        // A local time in a DST gap is shifted by ZonedDateTime; skip rather than fire at
                        // an unintended wall-clock time. Ambiguous times resolve to the earlier offset.
                        if (candidate.toLocalDateTime() == localDateTime && candidate.toInstant().isAfter(after)) {
                            return candidate.toInstant().toEpochMilli()
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        throw IllegalStateException("Tidak ditemukan waktu pelaksanaan yang cocok dalam delapan tahun")
    }

    private fun matchesDay(date: LocalDate): Boolean {
        val dom = dayOfMonth.matches(date.dayOfMonth)
        val dow = dayOfWeek.matches(date.dayOfWeek.value % 7)
        return when {
            dayOfMonth.wildcard && dayOfWeek.wildcard -> true
            dayOfMonth.wildcard -> dow
            dayOfWeek.wildcard -> dom
            else -> dom || dow // Standard cron OR semantics when both day fields are restricted.
        }
    }

    private data class Field(
        val values: Set<Int>,
        val wildcard: Boolean,
    ) {
        val sortedValues: List<Int> = values.sorted()
        fun matches(value: Int): Boolean = value in values
    }

    companion object {
        private const val MAX_SEARCH_DAYS = 366 * 8

        fun parse(expression: String): CronExpression {
            val fields = expression.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            require(fields.size == 5) {
                "Ekspresi cron harus memiliki tepat lima kolom: menit jam tanggal bulan hari-dalam-minggu"
            }
            return CronExpression(
                minute = parseField(fields[0], 0, 59, allowQuestion = false),
                hour = parseField(fields[1], 0, 23, allowQuestion = false),
                dayOfMonth = parseField(fields[2], 1, 31, allowQuestion = true),
                month = parseField(fields[3], 1, 12, allowQuestion = false),
                dayOfWeek = parseField(fields[4], 0, 7, allowQuestion = true, normalizeSunday = true),
            )
        }

        private fun parseField(
            source: String,
            minimum: Int,
            maximum: Int,
            allowQuestion: Boolean,
            normalizeSunday: Boolean = false,
        ): Field {
            val raw = source.uppercase(Locale.ROOT)
            require(!raw.contains('?') || (allowQuestion && raw == "?")) {
                "'?' hanya boleh digunakan sendiri pada kolom tanggal atau hari-dalam-minggu"
            }
            val wildcard = raw == "*" || raw == "?"
            val values = sortedSetOf<Int>()

            raw.split(',').forEach { item ->
                require(item.isNotBlank()) { "Bagian daftar cron tidak boleh kosong" }
                val components = item.split('/')
                require(components.size <= 2) { "Langkah cron tidak valid: $item" }
                val base = components[0]
                val step = components.getOrNull(1)?.toIntOrNull() ?: 1
                require(step > 0) { "Langkah cron harus lebih besar dari nol: $item" }
                require(components.getOrNull(1) == null || components[1].toIntOrNull() != null) {
                    "Langkah cron tidak valid: $item"
                }

                val start: Int
                val end: Int
                when {
                    base == "*" || base == "?" -> {
                        require(base != "?" || allowQuestion) { "'?' hanya boleh digunakan pada kolom hari" }
                        start = minimum
                        end = maximum
                    }
                    '-' in base -> {
                        val range = base.split('-')
                        require(range.size == 2) { "Rentang cron tidak valid: $item" }
                        start = range[0].toIntOrNull() ?: throw IllegalArgumentException("Nilai cron tidak valid: $item")
                        end = range[1].toIntOrNull() ?: throw IllegalArgumentException("Nilai cron tidak valid: $item")
                        require(start in minimum..maximum && end in minimum..maximum && start <= end) {
                            "Rentang cron berada di luar batas: $item"
                        }
                    }
                    else -> {
                        start = base.toIntOrNull() ?: throw IllegalArgumentException("Nilai cron tidak valid: $item")
                        require(start in minimum..maximum) { "Nilai cron berada di luar batas: $item" }
                        end = if (components.size == 2) maximum else start
                    }
                }

                var value = start
                while (value <= end) {
                    values += if (normalizeSunday && value == 7) 0 else value
                    value += step
                }
            }
            require(values.isNotEmpty()) { "Kolom cron tidak boleh kosong" }
            return Field(values, wildcard)
        }
    }
}
