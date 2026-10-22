package com.lull.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

object Format {
    /**
     * Formats a time of day the way the phone shows times. `java.time` can't see Android's 24-hour switch, which
     * Swift's `.shortened` style follows on iOS, so the app sets this to `android.text.format.DateFormat.getTimeFormat`
     * (see `LullApplication`). Without it, times use the locale's short style.
     */
    var platformTime: ((LocalTime) -> String)? = null

    /**
     * Formats a date as the locale's abbreviated weekday, month and day. `java.time` can't turn the skeleton
     * "EEEMMMd" into a locale's pattern, so the app sets this from `DateFormat.getBestDateTimePattern`.
     * Without it, dates read "Mon, Oct 20".
     */
    var platformNightTitle: ((LocalDate) -> String)? = null

    fun clock(minuteOfDay: Int): String {
        val minute = ((minuteOfDay % 1440) + 1440) % 1440
        return timeOfDay(LocalTime.of(minute / 60, minute % 60))
    }

    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        if (hours == 0) return "$rest min"
        return if (rest == 0) "$hours hr" else "$hours hr $rest min"
    }

    fun degrees(halfCelsius: Int, fahrenheit: Boolean): Int {
        val celsius = halfCelsius.toDouble() / 2
        return roundHalfAwayFromZero(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).toInt()
    }

    fun halfCelsius(degrees: Int, fahrenheit: Boolean): Int {
        val celsius = if (fahrenheit) (degrees - 32).toDouble() * 5 / 9 else degrees.toDouble()
        return roundHalfAwayFromZero(celsius * 2).toInt()
    }

    /** A moment as a time of day, like Swift's `formatted(date: .omitted, time: .shortened)`. */
    fun time(epochMillis: Long): String =
        timeOfDay(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime())

    /** A night's evening as "Mon, Oct 20", like Swift's `.dateTime.weekday(.abbreviated).month(.abbreviated).day()`. */
    fun nightTitle(evening: LocalDate): String =
        platformNightTitle?.invoke(evening)
            ?: evening.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))

    private fun timeOfDay(time: LocalTime): String =
        platformTime?.invoke(time)
            ?: time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
}

/** Swift's `Double.rounded()`: halves round away from zero, unlike `kotlin.math.round`, which rounds them to even. */
internal fun roundHalfAwayFromZero(value: Double): Long = (sign(value) * floor(abs(value) + 0.5)).toLong()
