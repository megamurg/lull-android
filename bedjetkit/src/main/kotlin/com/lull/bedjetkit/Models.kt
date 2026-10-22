package com.lull.bedjetkit

import kotlin.math.max
import kotlin.math.min

/**
 * Operating modes as the BedJet 3 reports them and stores them in program steps. `CodecV2` maps the
 * V2's own codes onto these.
 */
enum class Mode(val raw: Int) {
    STANDBY(0),
    HEAT(1),
    TURBO(2),
    EXTENDED_HEAT(3),
    COOL(4),
    DRY(5),
    WAIT(6);

    /** Button code that switches the unit into this mode right now. */
    val button: Int?
        get() = when (this) {
            STANDBY -> 0x01
            COOL -> 0x02
            HEAT -> 0x03
            TURBO -> 0x04
            DRY -> 0x05
            EXTENDED_HEAT -> 0x06
            WAIT -> null
        }

    /** Target temperature limits in half-degrees Celsius, as enforced by the official app. */
    val temperatureRange: IntRange
        get() = when (this) {
            HEAT -> 45..80
            TURBO -> 86..86
            EXTENDED_HEAT -> 38..67
            COOL -> 38..62
            DRY -> 40..80
            STANDBY, WAIT -> 80..80
        }

    val usesTemperature: Boolean get() = temperatureRange.first != temperatureRange.last
    val usesFan: Boolean get() = this != STANDBY && this != WAIT

    companion object {
        /** The mode with this raw byte, as `Mode(rawValue:)` in the Swift original; null for an unknown code. */
        fun fromRaw(raw: Int): Mode? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Which BedJet a link is talking to. Both run the same kind of program on the unit, but they speak
 * different protocols and the V2 has fewer modes.
 */
enum class Generation {
    V2,
    V3;

    fun supports(mode: Mode): Boolean = when (this) {
        V3 -> true
        V2 -> mode in listOf(Mode.STANDBY, Mode.HEAT, Mode.TURBO, Mode.COOL, Mode.WAIT)
    }

    /** The V2 only takes a target temperature for Heat. */
    fun adjustsTemperature(mode: Mode): Boolean =
        if (this == V2) mode == Mode.HEAT else mode.usesTemperature
}

/** One step of an on-device program ("biorhythm"). Five bytes on the wire. */
data class ProgramStep(
    val mode: Mode,
    /** 0...19, where the fan runs at `(fanStep + 1) * 5` percent. */
    val fanStep: Int,
    /** Target temperature in half-degrees Celsius. */
    val temperature: Int,
    val hours: Int,
    val minutes: Int,
    /** When set, hours/minutes are a wall-clock end time instead of a duration. */
    val endsAtClockTime: Boolean = false,
) {
    val longestMinutes: Int
        get() = longestMinutes(mode = mode, temperature = temperature, fanStep = fanStep)

    /** False when the unit would stop this step before it finishes. Clock-time steps end when they say. */
    val fitsUnitLimits: Boolean
        get() = endsAtClockTime || hours * 60 + minutes <= longestMinutes

    companion object {
        /**
         * Longest the unit lets a step run, in minutes. Heat is cut off sooner the hotter and faster the air,
         * following the table the official app enforces; the unit refuses or stops a step that runs past it.
         */
        fun longestMinutes(mode: Mode, temperature: Int, fanStep: Int): Int {
            if (mode != Mode.HEAT || temperature < 60) return Limits.MAX_STEP_MINUTES
            val row = heatHours[min(max(fanStep, 0), heatHours.size - 1)]
            val offset = temperature - 60
            val pair = row[min(offset shr 1, row.size - 1)]
            val hours = if ((offset and 1) == 0) pair shr 4 else pair and 0x0f
            return min(hours * 60, Limits.MAX_STEP_MINUTES)
        }

        /**
         * Heat hours allowed by fan step (rows) and target from 30 °C up in half-degree pairs (columns).
         * Each byte holds two temperatures: the even half-degree in the high nibble, the odd one in the low.
         * Values are unsigned bytes held in `Int`s.
         */
        private val heatHours: List<IntArray> = run {
            val quiet = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x66, 0x41)
            val strong = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11)
            List(5) { quiet } + listOf(
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x64, 0x44, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x44, 0x44, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x42, 0x22, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x22, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
            ) + List(6) { strong }
        }
    }
}

/** A program stored in one of the unit's three slots. */
data class StoredProgram(val name: String, val steps: List<ProgramStep>)

data class DeviceStatus(
    val remainingSeconds: Int,
    val actualTemperature: Int,
    val targetTemperature: Int,
    val mode: Mode,
    val fanStep: Int,
    val maxRuntimeMinutes: Int,
    val minTemperature: Int,
    val maxTemperature: Int,
    val ambientTemperature: Int,
    /** Why the unit last turned itself off. The codes are undocumented; reported for diagnosis. */
    val lastShutdownReason: Int,
    /** The second half of the status packet arrives separately and may lag the first. */
    val extra: Extra?,
) {
    data class Extra(
        val ledEnabled: Boolean,
        val beepsMuted: Boolean,
        /** Non-zero while an on-device program is running. */
        val programStep: Int,
        /** One of `Codec.Notification`. The unit repeats it until it is acknowledged. */
        val notification: Int,
    )

    val fanPercent: Int get() = (fanStep + 1) * 5
    val isRunning: Boolean get() = mode != Mode.STANDBY
}

enum class LinkState {
    BLUETOOTH_OFF,
    UNAUTHORIZED,
    SEARCHING,
    CONNECTING,
    READY,
    /** Handed over to another phone that asked for the BedJet. Reconnects by itself once that phone lets go. */
    YIELDED,
}

/**
 * How long the most recent connection took, measured from the moment the BedJet was asked for.
 * The Swift original keeps these as `TimeInterval` seconds; here they are milliseconds.
 */
data class ConnectionTiming(
    /** Until Bluetooth reported it was on. Zero when it already was. */
    val radioOnMs: Long,
    /** Until the radio link was up. Includes any wait for another phone to hand the BedJet over. */
    val linkUpMs: Long,
    /** Until the unit's service was found. */
    val servicesMs: Long,
    /** Until commands could be sent. */
    val usableMs: Long,
    /** False when the unit had to be found by scanning, as on a first connection. */
    val knownUnit: Boolean,
)

/** Errors a link reports. `message` is the text shown to the person, copied from the Swift `errorDescription`. */
sealed class BedJetException(message: String) : Exception(message) {
    object NotConnected : BedJetException("Couldn't reach your BedJet. Check that it's plugged in and nearby.")
    object InUseElsewhere : BedJetException("Another phone is using your BedJet right now. Try again in a moment.")
    object Timeout : BedJetException("Your BedJet didn't respond. Move closer and try again.")
    object BadResponse : BedJetException("Your BedJet sent something unexpected. Try again.")
    object VerifyFailed : BedJetException("The schedule didn't save correctly. Try again.")
    object ClockNotSet : BedJetException("Your BedJet's clock wasn't set, so it didn't start the schedule. Try again.")
    object ProgramTooLong :
        BedJetException("Your BedJet won't run a phase this long. Shorten the Heat phases or use Gentle heat.")

    data class InvalidProgram(val why: String) : BedJetException(why)
}

object Limits {
    const val SLOTS = 3
    const val STEPS_PER_PROGRAM = 32
    const val STEPS_PER_FRAGMENT = 8
    const val NAME_LENGTH = 15

    /** The unit refuses steps of 12 hours or longer. */
    const val MAX_STEP_MINUTES = 11 * 60 + 59
}
