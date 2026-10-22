package com.lull.bedjetkit

import kotlin.math.max
import kotlin.math.min

/**
 * Byte-level encoding for the BedJet V2, which speaks a serial protocol through a Microchip BLE UART bridge.
 *
 * Every packet is `prefix, length, type, payload…, checksum`. The phone sends prefix 0x58 and the unit
 * answers with 0x59; `length` counts the type and payload, and the checksum makes every byte sum to 0xff.
 * Worked out from the official BedJet Android app; see docs/BEDJET_V2_PROTOCOL.md.
 */
object CodecV2 {
    internal const val COMMAND_PREFIX = 0x58
    internal const val REPLY_PREFIX = 0x59

    /** Requests understood by `BedJetLink.query` on a V2. */
    object Query {
        /** Protocol and firmware versions, and which sequence format the unit takes. */
        const val INFO = 0x0b

        /** The unit's name, a 16-byte parameter. */
        const val DEVICE_NAME = 0x02
    }

    /** Oldest firmware that runs sequences; the official app hides them below it. */
    const val SEQUENCE_FIRMWARE = 48

    /** The unit reports the running step in five bits, so a sequence can't be longer than this. */
    const val STEPS_PER_SEQUENCE = 31

    /** Heat targets the unit accepts, in half-degrees Celsius. */
    val HEAT_RANGE = 44..80

    // Framing

    /** Payload values are unsigned bytes, 0...255. */
    internal fun frame(type: Int, payload: List<Int> = emptyList()): ByteArray {
        val bytes = listOf(COMMAND_PREFIX, payload.size + 1, type) + payload
        return (bytes + checksum(bytes)).map { it.toByte() }.toByteArray()
    }

    /** Swift's `~bytes.reduce(0, &+)` over `UInt8`s: the complement of the byte sum, modulo 256. */
    internal fun checksum(bytes: Iterable<Int>): Int = bytes.fold(0) { sum, byte -> (sum + byte) and 0xff }.inv() and 0xff

    /**
     * Splits the unit's byte stream into packets. Notifications can end partway through a packet,
     * so whatever is incomplete stays in the buffer for the next one; bytes that can't start a packet are dropped.
     *
     * The Swift original is `takePackets(from buffer: inout Data)`; Kotlin has no `inout`, so the buffer is this class.
     */
    class StreamBuffer {
        private var buffer = ByteArray(0)

        /** Bytes waiting for the rest of their packet. */
        val size: Int get() = buffer.size

        fun append(chunk: ByteArray) {
            buffer += chunk
        }

        fun clear() {
            buffer = ByteArray(0)
        }

        /** Returns the complete packets in the buffer and keeps the unconsumed tail. */
        fun takePackets(): List<ByteArray> {
            val bytes = buffer
            val packets = mutableListOf<ByteArray>()
            var start = 0
            var rest = bytes.size
            while (true) {
                val prefix = indexOfReplyPrefix(bytes, start)
                if (prefix < 0) break
                if (prefix + 1 >= bytes.size) {
                    rest = prefix
                    break
                }
                val length = bytes.ubyte(prefix + 1) + 3
                // Real packets are short, so a long length means this 0x59 was data rather than a prefix.
                if (!(length > 3 && length <= 64)) {
                    start = prefix + 1
                    continue
                }
                if (prefix + length > bytes.size) {
                    rest = prefix
                    break
                }
                val packet = bytes.copyOfRange(prefix, prefix + length)
                val body = (0 until packet.size - 1).map { packet.ubyte(it) }
                if (checksum(body) == packet.ubyte(packet.size - 1)) {
                    packets.add(packet)
                    start = prefix + length
                } else {
                    start = prefix + 1
                }
            }
            buffer = bytes.copyOfRange(rest, bytes.size)
            return packets
        }

        private fun indexOfReplyPrefix(bytes: ByteArray, from: Int): Int {
            for (i in from until bytes.size) if (bytes.ubyte(i) == REPLY_PREFIX) return i
            return -1
        }
    }

    // Commands

    fun request(query: Int): ByteArray =
        if (query == Query.INFO) frame(0x0b) else frame(0x06, listOf(query, 16))

    /** Modes are buttons: pressing one switches to that mode, and pressing the one the unit is in turns it off. */
    fun setMode(mode: Mode, current: Mode): ByteArray? {
        if (mode == current) return null
        return button(if (mode == Mode.STANDBY) current else mode)?.let { frame(0x01, listOf(it)) }
    }

    fun setTemperature(halfDegrees: Int, status: DeviceStatus): ByteArray =
        scene(status, temperature = halfDegrees)

    fun setFan(step: Int, status: DeviceStatus): ByteArray =
        scene(status, fanStep = step)

    fun setRuntime(minutes: Int, status: DeviceStatus): ByteArray =
        scene(status, seconds = minutes * 60)

    fun setSettings(ledOn: Boolean, muted: Boolean): ByteArray =
        frame(0x11, listOf((if (muted) 0x01 else 0) or (if (ledOn) 0 else 0x02)))

    /** Temperature, fan and timer are set together, so whatever isn't changing is repeated from the status. */
    internal fun scene(
        status: DeviceStatus,
        temperature: Int? = null,
        fanStep: Int? = null,
        seconds: Int? = null,
    ): ByteArray {
        val remaining = max(seconds ?: status.remainingSeconds, 0)
        val target = clampToByte(min(max(temperature ?: status.targetTemperature, 0), 0x7f))
        val muted = status.extra?.beepsMuted == true
        return frame(
            0x0e,
            listOf(
                statusCode(status.mode),
                min(max(fanStep ?: status.fanStep, 0), 19) + 1,
                target or (if (muted) 0x80 else 0),
                min(remaining / 3600, 12),
                remaining / 60 % 60,
                remaining % 60,
            ),
        )
    }

    // Sequences

    /** Empties the unit's sequence before new steps are written. A fresh array on every read. */
    val clearSequence: ByteArray get() = frame(0x0c)

    /**
     * One step, written to the parameter for its position. Units that take the newer format have one more byte.
     *
     * @throws BedJetException.InvalidProgram for a mode the V2 doesn't have.
     */
    fun writeStep(step: ProgramStep, index: Int, minutes: Int, extended: Boolean): ByteArray {
        val mode = stepCode(step.mode) ?: throw BedJetException.InvalidProgram(UNSUPPORTED_MODES)
        val target: Int = when (step.mode) {
            Mode.HEAT -> min(max(step.temperature, HEAT_RANGE.first), HEAT_RANGE.last)
            // The official app stores these, and the unit ignores them.
            Mode.COOL -> 40
            else -> 80
        }
        val payload = mutableListOf(
            0x80 + index, if (extended) 7 else 6,
            mode, min(max(step.fanStep, 0), 19) + 1, minutes / 60, minutes % 60, 0, target,
        )
        if (extended) payload.add(0)
        return frame(0x05, payload)
    }

    /** Ten bytes the official app stores alongside a sequence to recognise it later. */
    fun tagSequence(tag: ByteArray): ByteArray =
        frame(0x05, listOf(0x03, 0x0a) + tag.take(10).map { it.toInt() and 0xff } + List(max(0, 10 - tag.size)) { 0 })

    /**
     * Starts the stored sequence. Units that take the newer format are given the time of day with it;
     * for the others the time is ignored.
     */
    fun runSequence(hour: Int, minute: Int, second: Int, extended: Boolean): ByteArray {
        if (!extended) return frame(0x0d)
        return frame(0x0d, listOf(hour, minute, second))
    }

    const val UNSUPPORTED_MODES = "A BedJet V2 doesn't have Gentle heat or Dry. Change those phases to Heat or Cool."

    // Replies

    data class Info(
        val protocolVersion: Int,
        val firmware: Int,
        /** Steps are written in the longer format and the run command carries the time of day. */
        val extendedSequences: Boolean,
    ) {
        val runsSequences: Boolean get() = firmware >= SEQUENCE_FIRMWARE
    }

    /** The payload of the reply to `query`, or null when the packet answers something else. */
    fun reply(query: Int, packet: ByteArray): ByteArray? {
        val b = packet
        if (b.size < 4 || b.ubyte(0) != REPLY_PREFIX) return null
        if (query == Query.INFO) return if (b.ubyte(2) == 0x01) b.copyOfRange(3, b.size - 1) else null
        if (b.ubyte(2) != 0x05 || b.size < 5 || b.ubyte(3) != query) return null
        return b.copyOfRange(4, b.size - 1)
    }

    fun parseInfo(payload: ByteArray): Info? {
        val b = payload
        if (b.size < 2) return null
        val firmware = b.ubyte(1)
        val newerFormat = b.size > 7 && (b.ubyte(7) and 0x01) != 0
        return Info(protocolVersion = b.ubyte(0), firmware = firmware, extendedSequences = newerFormat && firmware >= 53)
    }

    /** A unit that was never named answers with no bytes. */
    fun parseName(payload: ByteArray): String =
        // Foundation's `.whitespaces` is the space separators (category Zs) plus tab, but not newlines.
        decodeUntilNul(payload).trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }

    fun parseStatus(packet: ByteArray): DeviceStatus? {
        val b = packet
        if (b.size < 14 || b.ubyte(0) != REPLY_PREFIX || b.ubyte(2) != 0x02) return null
        val mode = mode(statusCode = (b.ubyte(4) shr 5) and 0x07) ?: return null
        val fanStep = max((b.ubyte(4) and 0x1f) - 1, 0)
        val target = b.ubyte(7) and 0x7f
        val actual = b.ubyte(3) and 0x7f
        val hours = b.ubyte(5) shr 4
        val minutes = ((b.ubyte(5) shl 2) and 0x3c) or (b.ubyte(6) shr 6)
        val seconds = b.ubyte(6) and 0x3f
        val limits: IntRange = when (mode) {
            Mode.HEAT -> HEAT_RANGE
            Mode.TURBO -> 86..86
            Mode.COOL -> 44..44
            else -> 80..80
        }
        val longest: Int = when (mode) {
            Mode.TURBO -> 10
            Mode.HEAT -> ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = target, fanStep = fanStep)
            Mode.COOL -> 12 * 60
            else -> 0
        }
        return DeviceStatus(
            remainingSeconds = hours * 3600 + minutes * 60 + seconds,
            actualTemperature = actual,
            targetTemperature = target,
            mode = mode,
            fanStep = fanStep,
            maxRuntimeMinutes = longest,
            minTemperature = limits.first,
            maxTemperature = limits.last,
            // The V2 has no room sensor; with the air off, its air sensor reads the room.
            ambientTemperature = actual,
            lastShutdownReason = if (b.ubyte(1) > 10) b.ubyte(12) else 0,
            extra = DeviceStatus.Extra(
                ledEnabled = (b.ubyte(3) and 0x80) == 0,
                beepsMuted = (b.ubyte(8) and 0x80) != 0,
                programStep = (b.ubyte(10) shr 3) and 0x1f,
                notification = 0,
            ),
        )
    }

    // Modes

    /** The V2 reports off, Turbo, Heat and Cool as 0 to 3 and has no other modes. */
    internal fun mode(statusCode: Int): Mode? = when (statusCode) {
        0 -> Mode.STANDBY
        1 -> Mode.TURBO
        2 -> Mode.HEAT
        3 -> Mode.COOL
        else -> null
    }

    internal fun statusCode(mode: Mode): Int = when (mode) {
        Mode.TURBO -> 1
        Mode.HEAT -> 2
        Mode.COOL -> 3
        else -> 0
    }

    /** Sequence steps use the same codes, with 0 meaning wait rather than off. */
    internal fun stepCode(mode: Mode): Int? = when (mode) {
        Mode.WAIT -> 0
        Mode.TURBO, Mode.HEAT, Mode.COOL -> statusCode(mode)
        else -> null
    }

    internal fun button(mode: Mode): Int? = when (mode) {
        Mode.TURBO -> 0x01
        Mode.HEAT -> 0x02
        Mode.COOL -> 0x03
        else -> null
    }
}
