package com.lull.app.model

import com.lull.bedjetkit.Mode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One thing the BedJet did, kept so the morning after can be checked against how the night felt. */
@Serializable
data class HistoryEntry(
    val id: String = randomId(),
    /** Epoch milliseconds. */
    val date: Long,
    val event: Event,
    /** What the BedJet measured in the room at the time, in half-degrees Celsius. */
    val room: Int? = null,
    /**
     * Lull lets go of the BedJet when it leaves the screen, so a change made meanwhile, on the BedJet's
     * own remote or another phone, is only seen when Lull is opened again and is dated then.
     */
    val noticedLater: Boolean = false,
) {
    @Serializable
    sealed class Event {
        /** The air the BedJet settled on, whichever phone or remote chose it. */
        @Serializable
        @SerialName("air")
        data class Air(val mode: Mode, val temperature: Int, val fanPercent: Int) : Event()

        @Serializable
        @SerialName("off")
        data class Off(val timerRanOut: Boolean) : Event()

        /** Names and phases are missing for schedules started from another phone. */
        @Serializable
        @SerialName("scheduleStarted")
        data class ScheduleStarted(val name: String?, val phases: List<Phase>?) : Event()

        @Serializable
        @SerialName("scheduleEnded")
        data class ScheduleEnded(val name: String?, val ending: Ending) : Event()
    }

    @Serializable
    sealed class Ending {
        @Serializable
        @SerialName("byYou")
        data object ByYou : Ending()

        @Serializable
        @SerialName("finished")
        data object Finished : Ending()

        @Serializable
        @SerialName("early")
        data class Early(val minutes: Int) : Ending()
    }

    companion object {
        /** A few believable nights ending last night, for screenshots and trying History without a BedJet. */
        fun samples(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<HistoryEntry> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            fun at(nightsAgo: Int, hour: Int, minute: Int, event: Event, room: Int): HistoryEntry {
                // Hours past midnight belong to the morning after the evening.
                val day = today.plusDays((if (hour < 12) 1 - nightsAgo else -nightsAgo).toLong())
                val date = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                return HistoryEntry(date = date, event = event, room = room)
            }
            val coolDrift = Schedule.templates[0]
            val warmWelcome = Schedule.templates[1]
            return listOf(
                at(3, 22, 45, Event.ScheduleStarted(name = warmWelcome.name, phases = warmWelcome.phases), room = 40),
                at(3, 7, 5, Event.ScheduleEnded(name = warmWelcome.name, ending = Ending.Finished), room = 39),

                at(2, 22, 15, Event.Air(mode = Mode.HEAT, temperature = 70, fanPercent = 50), room = 41),
                at(2, 23, 0, Event.Air(mode = Mode.COOL, temperature = 44, fanPercent = 40), room = 43),
                at(2, 2, 30, Event.Air(mode = Mode.COOL, temperature = 44, fanPercent = 20), room = 42),
                at(2, 5, 45, Event.Air(mode = Mode.HEAT, temperature = 58, fanPercent = 30), room = 40),
                at(2, 6, 30, Event.Off(timerRanOut = true), room = 41),

                at(1, 22, 30, Event.ScheduleStarted(name = coolDrift.name, phases = coolDrift.phases), room = 46),
                at(1, 1, 50, Event.Air(mode = Mode.COOL, temperature = 44, fanPercent = 70), room = 47),
                at(1, 7, 15, Event.ScheduleEnded(name = coolDrift.name, ending = Ending.Finished), room = 44),
            )
        }
    }
}

/** Everything recorded between one noon and the next, so 3 a.m. belongs with the evening before. */
data class Night(
    /** The evening the night began, as `yyyy-MM-dd`. */
    val id: String,
    val evening: LocalDate,
    /** Oldest first, so the night reads in the order it happened. */
    val entries: List<HistoryEntry>,
) {
    /** Schedules started this night, in order, for naming the night at a glance. */
    val scheduleNames: List<String>
        get() = entries.mapNotNull { entry ->
            val event = entry.event as? HistoryEntry.Event.ScheduleStarted ?: return@mapNotNull null
            event.name ?: "A schedule"
        }

    /**
     * The night redrawn as phases, so it can be drawn like a schedule. Each entry lasts until the next. A schedule
     * plays out its own phases, and a change made during one lasts until the schedule moves to its next phase.
     */
    val phases: List<Phase>
        get() {
            val phases = mutableListOf<Phase>()
            var schedule: PlayingSchedule? = null
            for ((index, entry) in entries.withIndex()) {
                val next = entries.getOrNull(index + 1)?.date
                val event = entry.event
                when {
                    event is HistoryEntry.Event.ScheduleStarted && event.phases != null -> {
                        val schedulePhases = event.phases
                        schedule = PlayingSchedule(entry.date, schedulePhases)
                        val end = next ?: (entry.date + schedulePhases.sumOf { it.minutes } * MINUTE)
                        phases += play(schedulePhases, startedAt = entry.date, from = entry.date, to = end)
                    }
                    event is HistoryEntry.Event.Air -> {
                        // How long the last setting lasted is unknown, so it gets a short stretch to be visible.
                        val end = next ?: (entry.date + 30 * MINUTE)
                        val tweak = { until: Long ->
                            Phase.of(event.mode, temperature = event.temperature, fan = event.fanPercent, minutes = minutes(entry.date, until))
                        }
                        val playing = schedule
                        val phaseEnds = playing?.let { phaseEnd(it.phases, startedAt = it.startedAt, after = entry.date) }
                        if (playing == null || phaseEnds == null || phaseEnds >= end) {
                            phases.add(tweak(end))
                            continue
                        }
                        phases.add(tweak(phaseEnds))
                        phases += play(playing.phases, startedAt = playing.startedAt, from = phaseEnds, to = end)
                    }
                    // .off, .scheduleStarted without phases, .scheduleEnded
                    else -> {
                        schedule = null
                        if (next != null) phases.add(Phase.of(Mode.WAIT, minutes = minutes(entry.date, next)))
                    }
                }
            }
            return phases.filter { it.minutes > 0 }
        }

    private class PlayingSchedule(val startedAt: Long, val phases: List<Phase>)

    companion object {
        private const val MINUTE = 60_000L

        fun group(entries: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Night> =
            entries.groupBy { evening(it.date, zone) }
                .map { (evening, entries) -> Night(id = id(evening), evening = evening, entries = entries.sortedBy { it.date }) }
                .sortedByDescending { it.evening }

        /** The evening a moment belongs to: the calendar day twelve hours earlier. */
        fun evening(of: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochMilli(of - 12 * 3600 * 1000L).atZone(zone).toLocalDate()

        fun id(evening: LocalDate): String =
            String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", evening.year, evening.monthValue, evening.dayOfMonth)

        /** Same as [HistoryEntry.samples]; listed here by the port plan. */
        fun samples(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<HistoryEntry> =
            HistoryEntry.samples(now, zone)

        /** The stretch of a schedule between two times, idle once the schedule is over. */
        private fun play(schedule: List<Phase>, startedAt: Long, from: Long, to: Long): List<Phase> {
            val played = mutableListOf<Phase>()
            var phaseStart = startedAt
            for (phase in schedule) {
                val phaseEnd = phaseStart + phase.minutes * MINUTE
                if (phaseEnd > from && phaseStart < to) {
                    played.add(phase.copy(minutes = minutes(maxOf(phaseStart, from), minOf(phaseEnd, to))))
                }
                phaseStart = phaseEnd
            }
            if (phaseStart < to) played.add(Phase.of(Mode.WAIT, minutes = minutes(maxOf(phaseStart, from), to)))
            return played
        }

        private fun phaseEnd(schedule: List<Phase>, startedAt: Long, after: Long): Long? {
            var phaseEnd = startedAt
            for (phase in schedule) {
                phaseEnd += phase.minutes * MINUTE
                if (phaseEnd > after) return phaseEnd
            }
            return null
        }

        private fun minutes(start: Long, end: Long): Int =
            maxOf(roundHalfAwayFromZero((end - start).toDouble() / MINUTE).toInt(), 0)
    }
}
