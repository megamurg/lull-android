package com.lull.app.model

import com.lull.bedjetkit.Mode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val MINUTE = 60_000L

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun entry(date: Long, event: HistoryEntry.Event) = HistoryEntry(date = date, event = event)

    @Test
    fun nightsRunFromNoonToNoon() {
        val entries = listOf(
            entry(at(2026, 10, 20, 11, 59), HistoryEntry.Event.Off(timerRanOut = false)),
            entry(at(2026, 10, 20, 12, 0), HistoryEntry.Event.Off(timerRanOut = false)),
            entry(at(2026, 10, 21, 3, 0), HistoryEntry.Event.Off(timerRanOut = true)),
            entry(at(2026, 10, 20, 22, 0), HistoryEntry.Event.Air(Mode.COOL, 44, 40)),
        )
        val nights = Night.group(entries, zone)
        // Newest night first; within a night, oldest entry first.
        assertEquals(listOf("2026-10-20", "2026-10-19"), nights.map { it.id })
        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)), nights.map { it.evening })
        assertEquals(listOf(at(2026, 10, 20, 12, 0), at(2026, 10, 20, 22, 0), at(2026, 10, 21, 3, 0)), nights[0].entries.map { it.date })
        assertEquals(1, nights[1].entries.size)
        assertEquals(LocalDate.of(2026, 1, 4), Night.evening(at(2026, 1, 5, 11, 59), zone))
        assertEquals(LocalDate.of(2026, 1, 5), Night.evening(at(2026, 1, 5, 12, 0), zone))
        assertEquals("2026-01-04", Night.id(LocalDate.of(2026, 1, 4)))
    }

    @Test
    fun scheduleNamesNameTheNight() {
        val night = Night(
            id = "2026-10-20", evening = LocalDate.of(2026, 10, 20),
            entries = listOf(
                entry(at(2026, 10, 20, 22), HistoryEntry.Event.ScheduleStarted(name = "Cool Drift", phases = null)),
                entry(at(2026, 10, 20, 23), HistoryEntry.Event.Air(Mode.COOL, 44, 40)),
                entry(at(2026, 10, 21, 1), HistoryEntry.Event.ScheduleStarted(name = null, phases = null)),
                entry(at(2026, 10, 21, 7), HistoryEntry.Event.ScheduleEnded(name = "Cool Drift", ending = HistoryEntry.Ending.Finished)),
            ),
        )
        assertEquals(listOf("Cool Drift", "A schedule"), night.scheduleNames)
    }

    @Test
    fun phasesReplayAScheduleWithATweakAndAnOff() {
        val schedule = listOf(
            Phase.of(Mode.COOL, fan = 60, minutes = 60),
            Phase.of(Mode.COOL, fan = 30, minutes = 120),
            Phase.of(Mode.HEAT, temperature = 60, fan = 30, minutes = 60),
        )
        val start = at(2026, 10, 20, 22)
        val night = Night(
            id = "2026-10-20", evening = LocalDate.of(2026, 10, 20),
            entries = listOf(
                entry(start, HistoryEntry.Event.ScheduleStarted(name = "Test", phases = schedule)),
                // 30 minutes in, the fan is turned up; that lasts until the schedule's second phase starts at 23:00.
                entry(start + 30 * MINUTE, HistoryEntry.Event.Air(Mode.COOL, 44, 90)),
                // Turned off at 01:00, as the second phase ends, and nothing more until 02:00.
                entry(start + 180 * MINUTE, HistoryEntry.Event.Off(timerRanOut = false)),
                entry(start + 240 * MINUTE, HistoryEntry.Event.Air(Mode.HEAT, 60, 30)),
            ),
        )
        val phases = night.phases
        assertEquals(
            listOf(
                Mode.COOL to 30, // schedule phase 1 until the tweak
                Mode.COOL to 30, // the tweak, until phase 1 would have ended
                Mode.COOL to 120, // the schedule's phase 2, played until the off
                Mode.WAIT to 60, // off until the next entry
                Mode.HEAT to 30, // the last setting gets a short stretch to be visible
            ),
            phases.map { it.mode to it.minutes },
        )
        assertEquals(listOf(60, 90, 30, 40, 30), phases.map { it.fanPercent })
    }

    @Test
    fun aScheduleWithoutLaterEntriesPlaysToItsEnd() {
        val schedule = Schedule.templates[2].phases
        val night = Night(
            id = "x", evening = LocalDate.of(2026, 10, 20),
            entries = listOf(entry(at(2026, 10, 20, 22), HistoryEntry.Event.ScheduleStarted(name = "Hot Sleeper", phases = schedule))),
        )
        assertEquals(schedule.map { it.minutes }, night.phases.map { it.minutes })
    }

    @Test
    fun aScheduleThatEndsBeforeTheNextEntryIdles() {
        val schedule = listOf(Phase.of(Mode.COOL, minutes = 60))
        val start = at(2026, 10, 20, 22)
        val night = Night(
            id = "x", evening = LocalDate.of(2026, 10, 20),
            entries = listOf(
                entry(start, HistoryEntry.Event.ScheduleStarted(name = "Short", phases = schedule)),
                entry(start + 90 * MINUTE, HistoryEntry.Event.ScheduleEnded(name = "Short", ending = HistoryEntry.Ending.Finished)),
            ),
        )
        assertEquals(listOf(Mode.COOL to 60, Mode.WAIT to 30), night.phases.map { it.mode to it.minutes })
    }

    @Test
    fun samplesMakeThreeNights() {
        val now = at(2026, 10, 21, 9, 30)
        val samples = HistoryEntry.samples(now, zone)
        assertEquals(10, samples.size)
        val nights = Night.group(samples, zone)
        assertEquals(listOf("2026-10-20", "2026-10-19", "2026-10-18"), nights.map { it.id })
        assertEquals(listOf(3, 5, 2), nights.map { it.entries.size })
        assertEquals(listOf("Cool Drift"), nights[0].scheduleNames)
        assertEquals(emptyList<String>(), nights[1].scheduleNames)
        assertEquals(listOf("Warm Welcome"), nights[2].scheduleNames)
        // Hours past midnight land on the morning after the evening.
        assertEquals(at(2026, 10, 21, 7, 15), nights[0].entries.last().date)
        assertEquals(Night.samples(now, zone).map { it.date }, samples.map { it.date })
    }
}
