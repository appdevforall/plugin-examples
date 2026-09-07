package com.appdevforall.contractor.plugin.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DateRange(val fromMillis: Long, val toMillis: Long) {
    init {
        require(toMillis >= fromMillis) { "toMillis must be >= fromMillis" }
    }
}

object DateRanges {

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    fun startOfNextDayMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun today(): DateRange {
        val today = LocalDate.now(zone())
        return DateRange(startOfDayMillis(today), startOfNextDayMillis(today))
    }

    fun thisWeek(): DateRange {
        val today = LocalDate.now(zone())
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val nextMonday = monday.plusDays(7)
        return DateRange(startOfDayMillis(monday), startOfDayMillis(nextMonday))
    }

    fun thisMonth(): DateRange {
        val today = LocalDate.now(zone())
        val first = today.with(TemporalAdjusters.firstDayOfMonth())
        val nextMonthFirst = first.plusMonths(1)
        return DateRange(startOfDayMillis(first), startOfDayMillis(nextMonthFirst))
    }

    fun custom(start: LocalDate, endInclusive: LocalDate): DateRange {
        return DateRange(startOfDayMillis(start), startOfNextDayMillis(endInclusive))
    }

    fun toLocalDate(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()
}
