package com.appdevforall.contractor.plugin.domain.model

import java.util.concurrent.TimeUnit

object DurationFormat {

    fun millisToSeconds(ms: Long): Long = ms / 1000

    fun secondsToHours(seconds: Long): Double = seconds / 3600.0

    fun formatHoursMinutes(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            else -> "${hours}h ${"%02d".format(minutes)}m"
        }
    }

    fun formatDecimalHours(ms: Long): String {
        val hours = ms / 3_600_000.0
        return "%.2f".format(hours)
    }
}
