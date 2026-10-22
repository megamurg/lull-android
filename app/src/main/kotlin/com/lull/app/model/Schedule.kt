package com.lull.app.model

import com.lull.bedjetkit.Limits
import com.lull.bedjetkit.Mode
import com.lull.bedjetkit.ProgramStep
import com.lull.bedjetkit.StoredProgram
import kotlinx.serialization.Serializable
import java.util.UUID

/** A fresh identifier, formatted like Swift's `UUID().uuidString`. */
fun randomId(): String = UUID.randomUUID().toString().uppercase()

/** One stretch of the night: what the BedJet does and for how long. */
@Serializable
data class Phase(
    val id: String = randomId(),
    val mode: Mode,
    /** Half-degrees Celsius, the unit the BedJet stores. */
    val temperature: Int,
    val fanPercent: Int,
    val minutes: Int,
) {
    /** Position on the night curve: -1 is the strongest cooling, +1 the strongest heat. */
    val level: Double
        get() = when (mode) {
            Mode.COOL -> -(0.25 + 0.75 * fanPercent.toDouble() / 100)
            Mode.DRY -> -0.15
            Mode.STANDBY, Mode.WAIT -> 0.0
            Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO -> 0.2 + 0.8 * (temperature - 38).toDouble() / 48
        }

    /**
     * Longest this phase may run: the mode's own ceiling, and for Heat the BedJet's safety cut-off,
     * which comes sooner the hotter and faster the air.
     */
    val maxMinutes: Int
        get() = minOf(mode.maxMinutes, ProgramStep.longestMinutes(mode, temperature, fanPercent / 5 - 1))

    val step: ProgramStep
        get() = ProgramStep(
            mode = mode,
            fanStep = fanPercent / 5 - 1,
            temperature = temperature,
            hours = minutes / 60,
            minutes = minutes % 60,
        )

    companion object {
        /** The Swift `Phase(_ mode:, temperature:, fan:, minutes:)`: temperature defaults to the mode's own. */
        fun of(mode: Mode, temperature: Int? = null, fan: Int = 40, minutes: Int): Phase =
            Phase(mode = mode, temperature = temperature ?: mode.defaultTemperature, fanPercent = fan, minutes = minutes)
    }
}

@Serializable
data class Schedule(
    val id: String = randomId(),
    val name: String,
    val phases: List<Phase>,
    /** Minutes after midnight; used to label the timeline. A schedule runs from whenever it is started. */
    val bedtime: Int = 22 * 60 + 30,
) {
    val totalMinutes: Int get() = phases.sumOf { it.minutes }
    val program: StoredProgram get() = StoredProgram(name = name, steps = phases.map { it.step })

    fun startMinute(of: Phase): Int = bedtime + phases.takeWhile { it.id != of.id }.sumOf { it.minutes }

    companion object {
        fun importing(program: StoredProgram, bedtime: Int = 22 * 60 + 30): Schedule {
            var clock = bedtime
            val phases = program.steps.map { step ->
                var minutes = step.hours * 60 + step.minutes
                // Steps that end at a time of day become durations, measured from the usual bedtime.
                if (step.endsAtClockTime) minutes = ((minutes - clock) % 1440 + 1440) % 1440
                clock += minutes
                Phase.of(step.mode, temperature = step.temperature, fan = (step.fanStep + 1) * 5, minutes = maxOf(minutes, 1))
            }
            return Schedule(name = program.name.ifEmpty { "From BedJet" }, phases = phases, bedtime = bedtime)
        }

        val templates: List<Schedule> = listOf(
            Schedule(
                name = "Cool Drift",
                phases = listOf(
                    Phase.of(Mode.COOL, fan = 60, minutes = 45),
                    Phase.of(Mode.COOL, fan = 30, minutes = 300),
                    Phase.of(Mode.COOL, fan = 15, minutes = 90),
                    Phase.of(Mode.HEAT, temperature = 54, fan = 30, minutes = 30),
                ),
            ),
            Schedule(
                name = "Warm Welcome",
                phases = listOf(
                    Phase.of(Mode.TURBO, fan = 100, minutes = 10),
                    Phase.of(Mode.HEAT, temperature = 68, fan = 40, minutes = 30),
                    Phase.of(Mode.COOL, fan = 20, minutes = 360),
                    Phase.of(Mode.HEAT, temperature = 58, fan = 30, minutes = 40),
                ),
            ),
            Schedule(
                name = "Hot Sleeper",
                phases = listOf(
                    Phase.of(Mode.COOL, fan = 100, minutes = 60),
                    Phase.of(Mode.COOL, fan = 70, minutes = 240),
                    Phase.of(Mode.COOL, fan = 40, minutes = 150),
                ),
            ),
            Schedule(
                name = "Winter Cocoon",
                phases = listOf(
                    Phase.of(Mode.HEAT, temperature = 72, fan = 50, minutes = 30),
                    Phase.of(Mode.EXTENDED_HEAT, temperature = 62, fan = 30, minutes = 210),
                    Phase.of(Mode.EXTENDED_HEAT, temperature = 58, fan = 20, minutes = 240),
                ),
            ),
        )
    }
}

/** Modes a phase can use, in the order the editor offers them. */
val Mode.Companion.phaseModes: List<Mode>
    get() = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT)

val Mode.Companion.remoteModes: List<Mode>
    get() = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY)

val Mode.title: String
    get() = when (this) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heat"
        Mode.TURBO -> "Turbo"
        Mode.EXTENDED_HEAT -> "Gentle heat"
        Mode.COOL -> "Cool"
        Mode.DRY -> "Dry"
        Mode.WAIT -> "Pause"
    }

val Mode.activity: String
    get() = when (this) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heating"
        Mode.TURBO -> "Turbo heating"
        Mode.EXTENDED_HEAT -> "Gently heating"
        Mode.COOL -> "Cooling"
        Mode.DRY -> "Drying"
        Mode.WAIT -> "Paused"
    }

val Mode.defaultTemperature: Int
    get() = when (this) {
        Mode.HEAT -> 66
        Mode.EXTENDED_HEAT -> 58
        Mode.COOL -> 44
        Mode.DRY -> 60
        else -> temperatureRange.first
    }

/** Longest a single phase may run. Turbo and Heat limits follow the unit's own safety timers. */
val Mode.maxMinutes: Int
    get() = when (this) {
        Mode.TURBO -> 10
        Mode.HEAT -> 240
        else -> Limits.MAX_STEP_MINUTES
    }
