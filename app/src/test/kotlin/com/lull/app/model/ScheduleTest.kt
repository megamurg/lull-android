package com.lull.app.model

import com.lull.bedjetkit.Limits
import com.lull.bedjetkit.Mode
import com.lull.bedjetkit.ProgramStep
import com.lull.bedjetkit.StoredProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleTest {
    @Test
    fun templatesRunTheirWholeNight() {
        assertEquals(listOf("Cool Drift", "Warm Welcome", "Hot Sleeper", "Winter Cocoon"), Schedule.templates.map { it.name })
        assertEquals(listOf(465, 440, 450, 480), Schedule.templates.map { it.totalMinutes })
        // Every template fits what the BedJet accepts, so it starts without a refusal.
        for (template in Schedule.templates) {
            assertTrue(template.program.steps.all { it.fitsUnitLimits }, template.name)
            assertTrue(template.phases.all { it.minutes <= it.maxMinutes }, template.name)
        }
    }

    @Test
    fun phasesDefaultToTheirModesTemperature() {
        assertEquals(44, Phase.of(Mode.COOL, minutes = 10).temperature)
        assertEquals(66, Phase.of(Mode.HEAT, minutes = 10).temperature)
        assertEquals(58, Phase.of(Mode.EXTENDED_HEAT, minutes = 10).temperature)
        assertEquals(60, Phase.of(Mode.DRY, minutes = 10).temperature)
        assertEquals(86, Phase.of(Mode.TURBO, minutes = 10).temperature)
        assertEquals(80, Phase.of(Mode.WAIT, minutes = 10).temperature)
        assertEquals(40, Phase.of(Mode.COOL, minutes = 10).fanPercent)
    }

    @Test
    fun phaseLevelPlacesItOnTheNightCurve() {
        assertEquals(-1.0, Phase.of(Mode.COOL, fan = 100, minutes = 1).level, 1e-9)
        assertEquals(-(0.25 + 0.75 * 0.4), Phase.of(Mode.COOL, fan = 40, minutes = 1).level, 1e-9)
        assertEquals(-0.15, Phase.of(Mode.DRY, minutes = 1).level, 1e-9)
        assertEquals(0.0, Phase.of(Mode.WAIT, minutes = 1).level, 1e-9)
        assertEquals(0.0, Phase.of(Mode.STANDBY, minutes = 1).level, 1e-9)
        assertEquals(1.0, Phase.of(Mode.TURBO, minutes = 1).level, 1e-9)
        assertEquals(0.2, Phase.of(Mode.EXTENDED_HEAT, temperature = 38, minutes = 1).level, 1e-9)
        assertEquals(0.2 + 0.8 * 28.0 / 48, Phase.of(Mode.HEAT, temperature = 66, minutes = 1).level, 1e-9)
    }

    @Test
    fun phaseMaxMinutesFollowsTheModeAndTheHeatTable() {
        assertEquals(10, Phase.of(Mode.TURBO, minutes = 1).maxMinutes)
        assertEquals(Limits.MAX_STEP_MINUTES, Phase.of(Mode.COOL, minutes = 1).maxMinutes)
        // Heat is capped at four hours by the app, and sooner by the unit when it is hot and fast.
        assertEquals(240, Phase.of(Mode.HEAT, temperature = 66, fan = 40, minutes = 1).maxMinutes)
        val hot = Phase.of(Mode.HEAT, temperature = 80, fan = 100, minutes = 1)
        assertEquals(ProgramStep.longestMinutes(Mode.HEAT, 80, 19), hot.maxMinutes)
        assertTrue(hot.maxMinutes < 240)
    }

    @Test
    fun phaseStepIsWhatTheBedJetStores() {
        assertEquals(
            ProgramStep(mode = Mode.HEAT, fanStep = 7, temperature = 68, hours = 2, minutes = 5),
            Phase.of(Mode.HEAT, temperature = 68, fan = 40, minutes = 125).step,
        )
        assertEquals(0, Phase.of(Mode.COOL, fan = 5, minutes = 1).step.fanStep)
        assertEquals(19, Phase.of(Mode.COOL, fan = 100, minutes = 1).step.fanStep)
    }

    @Test
    fun startMinuteCountsFromBedtime() {
        val schedule = Schedule.templates[1]
        assertEquals(22 * 60 + 30, schedule.startMinute(schedule.phases[0]))
        assertEquals(22 * 60 + 30 + 10 + 30, schedule.startMinute(schedule.phases[2]))
    }

    @Test
    fun importingTurnsClockTimeStepsIntoDurations() {
        val program = StoredProgram(
            name = "",
            steps = listOf(
                ProgramStep(mode = Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 30),
                // Ends at 06:00: from midnight (22:30 + 1:30) that is six hours.
                ProgramStep(mode = Mode.COOL, fanStep = 3, temperature = 44, hours = 6, minutes = 0, endsAtClockTime = true),
                // Ends at the time it already is: a zero-length phase becomes a minute.
                ProgramStep(mode = Mode.HEAT, fanStep = 5, temperature = 60, hours = 6, minutes = 0, endsAtClockTime = true),
                ProgramStep(mode = Mode.HEAT, fanStep = 5, temperature = 60, hours = 0, minutes = 20),
            ),
        )
        val schedule = Schedule.importing(program)
        assertEquals("From BedJet", schedule.name)
        assertEquals(listOf(90, 360, 1, 20), schedule.phases.map { it.minutes })
        assertEquals(listOf(50, 20, 30, 30), schedule.phases.map { it.fanPercent })
        assertEquals(22 * 60 + 30, schedule.bedtime)
        assertEquals("Kept", Schedule.importing(program.copy(name = "Kept")).name)
        // A step that ends before bedtime wraps past midnight.
        val early = Schedule.importing(
            StoredProgram("Early", listOf(ProgramStep(Mode.COOL, 9, 44, hours = 21, minutes = 30, endsAtClockTime = true))),
        )
        assertEquals(23 * 60, early.phases.single().minutes)
    }

    @Test
    fun importedProgramsRoundTripTheirSteps() {
        val template = Schedule.templates[3]
        assertEquals(template.program.steps, Schedule.importing(template.program).program.steps)
    }

    @Test
    fun modesHaveTheirTitles() {
        assertEquals(listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT), Mode.phaseModes)
        assertEquals(listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY), Mode.remoteModes)
        assertEquals("Gentle heat", Mode.EXTENDED_HEAT.title)
        assertEquals("Off", Mode.STANDBY.title)
        assertEquals("Gently heating", Mode.EXTENDED_HEAT.activity)
        assertEquals("Paused", Mode.WAIT.activity)
        assertEquals(10, Mode.TURBO.maxMinutes)
        assertEquals(240, Mode.HEAT.maxMinutes)
        assertEquals(Limits.MAX_STEP_MINUTES, Mode.DRY.maxMinutes)
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("0 min", Format.duration(0))
        assertEquals("45 min", Format.duration(45))
        assertEquals("2 hr", Format.duration(120))
        assertEquals("7 hr 45 min", Format.duration(465))
    }

    @Test
    fun degreesRoundTrip() {
        assertEquals(22, Format.degrees(44, fahrenheit = false))
        assertEquals(72, Format.degrees(44, fahrenheit = true))
        assertEquals(44, Format.halfCelsius(72, fahrenheit = true))
        assertEquals(44, Format.halfCelsius(22, fahrenheit = false))
        // Halves round away from zero, as Swift's `rounded()` does: 22.5 °C is 23, 72.5 °F (45 half-degrees) is 73.
        assertEquals(23, Format.degrees(45, fahrenheit = false))
        assertEquals(73, Format.degrees(45, fahrenheit = true))
        // A slider in Fahrenheit shows the same number again after setting what it showed.
        for (half in 38..86) {
            val shown = Format.degrees(half, fahrenheit = true)
            assertEquals(shown, Format.degrees(Format.halfCelsius(shown, fahrenheit = true), fahrenheit = true), "$half")
        }
        // Whole Celsius degrees are exact.
        for (celsius in 19..43) assertEquals(celsius, Format.degrees(Format.halfCelsius(celsius, fahrenheit = false), fahrenheit = false))
    }

    @Test
    fun clockWrapsAroundTheDay() {
        assertEquals(Format.clock(30), Format.clock(1440 + 30))
        assertEquals(Format.clock(23 * 60), Format.clock(-60))
    }

    @Test
    fun activeRunKnowsItsPhase() {
        val schedule = Schedule.templates[1]
        val run = ActiveRun(schedule = schedule, startedAt = 1_000_000L)
        assertEquals(1_000_000L + 440 * 60_000L, run.endsAt)
        assertEquals(0.0, run.progress(0L))
        assertEquals(0.5, run.progress(1_000_000L + 220 * 60_000L), 1e-9)
        assertEquals(1.0, run.progress(run.endsAt + 1))
        assertEquals(CurrentPhase(schedule.phases[0], 0, 10), run.phase(1_000_000L))
        assertEquals(CurrentPhase(schedule.phases[1], 1, 25), run.phase(1_000_000L + 15 * 60_000L + 30_000L))
        assertEquals(null, run.phase(run.endsAt))
    }
}
