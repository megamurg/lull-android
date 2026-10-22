package com.lull.bedjetkit

import kotlin.math.max
import kotlin.math.min

/**
 * Byte-level encoding for the BedJet 3 GATT protocol.
 *
 * Commands are written to characteristic 0x2004. Status arrives as notifications on 0x2000,
 * and replies to `query` requests are read from 0x2006.
 *
 * Bytes on the wire are unsigned; every value read out of a `ByteArray` goes through [ubyte].
 */
object Codec {
    object Query {
        const val DEVICE_NAME = 0x00
        const val PROGRAM_NAMES = 0x04
        const val FIRMWARE = 0x20
        fun fragment(slot: Int, index: Int): Int = 7 + slot * 4 + index
    }

    /** Codes the unit raises in byte 29 of its status. */
    object Notification {
        const val CLEAN_FILTER = 1
        const val UPDATE_AVAILABLE = 2
        const val UPDATE_FAILED = 3

        /** A program was started but the unit's clock isn't set, so it didn't run. */
        const val PROGRAM_NEEDS_CLOCK = 4

        /** A program was started with a step longer than the unit allows, so it didn't run. */
        const val PROGRAM_TOO_LONG = 5
    }

    // Commands

    fun setMode(mode: Mode): ByteArray? = mode.button?.let { bytesOf(0x01, it) }
    fun setRuntime(minutes: Int): ByteArray = bytesOf(0x02, minutes / 60, minutes % 60)
    fun setTemperature(halfDegrees: Int): ByteArray = bytesOf(0x03, clampToByte(halfDegrees))
    fun setFan(step: Int): ByteArray = bytesOf(0x07, min(max(step, 0), 19))
    fun setClock(hour: Int, minute: Int): ByteArray = bytesOf(0x08, hour, minute)
    fun setLed(on: Boolean): ByteArray = bytesOf(0x01, if (on) 0x46 else 0x47)
    fun setMuted(muted: Boolean): ByteArray = bytesOf(0x01, if (muted) 0x48 else 0x49)
    fun startProgram(slot: Int): ByteArray = bytesOf(0x01, 0x80 + slot)
    fun deleteProgram(slot: Int): ByteArray = bytesOf(0x01, 0x83 + slot)

    /** A fresh array on every read, because a `ByteArray` (unlike Swift's `Data`) can be changed by whoever holds it. */
    val acknowledgeNotification: ByteArray get() = bytesOf(0x01, 0x52)
    fun query(request: Int, tag: Int): ByteArray = bytesOf(0x41, request, tag)

    fun nameProgram(slot: Int, name: String): ByteArray {
        val bytes = ByteArray(19)
        bytes[0] = 0x40
        bytes[1] = (4 + slot).toByte()
        bytes[2] = 0x10
        for ((i, byte) in sanitize(name).encodeToByteArray().withIndex()) bytes[3 + i] = byte
        return bytes
    }

    /** One write per 8 steps; every fragment repeats the program's total step count. */
    fun writeProgram(slot: Int, steps: List<ProgramStep>): List<ByteArray> {
        val fragments = max(1, (steps.size + Limits.STEPS_PER_FRAGMENT - 1) / Limits.STEPS_PER_FRAGMENT)
        return (0 until fragments).map { index ->
            val bytes = ByteArray(43)
            bytes[0] = 0x40
            bytes[1] = Query.fragment(slot = slot, index = index).toByte()
            bytes[2] = steps.size.toByte()
            val chunk = steps.drop(index * Limits.STEPS_PER_FRAGMENT).take(Limits.STEPS_PER_FRAGMENT)
            for ((i, step) in chunk.withIndex()) {
                val base = 3 + i * 5
                bytes[base] = step.mode.raw.toByte()
                bytes[base + 1] = min(max(step.fanStep, 0), 19).toByte()
                bytes[base + 2] = clampToByte(step.temperature).toByte()
                bytes[base + 3] = ((step.hours and 0x7f) or (if (step.endsAtClockTime) 0x80 else 0)).toByte()
                bytes[base + 4] = step.minutes.toByte()
            }
            bytes
        }
    }

    /** Names are 15 ASCII bytes on the unit. */
    fun sanitize(name: String): String {
        // Swift filters Unicode scalars; filtering UTF-16 units is the same here, because every unit of a
        // character outside printable ASCII (surrogates included) falls outside 32..126 too.
        val ascii = name.filter { it.code in 32 until 127 }
        return ascii.take(Limits.NAME_LENGTH)
    }

    // Replies

    data class Fragment(val total: Int, val steps: List<ProgramStep>)

    /** Payload of a fragment reply (after the echoed request and tag bytes). */
    fun parseFragment(payload: ByteArray, index: Int): Fragment? {
        val bytes = payload
        if (bytes.isEmpty()) return null
        val total = bytes.ubyte(0)
        if (total > Limits.STEPS_PER_PROGRAM) return null
        val expected = min(Limits.STEPS_PER_FRAGMENT, max(0, total - index * Limits.STEPS_PER_FRAGMENT))
        if (bytes.size < 1 + expected * 5) return null
        val steps = mutableListOf<ProgramStep>()
        for (i in 0 until expected) {
            val base = 1 + i * 5
            val mode = Mode.fromRaw(bytes.ubyte(base)) ?: return null
            steps.add(
                ProgramStep(
                    mode = mode,
                    fanStep = bytes.ubyte(base + 1),
                    temperature = bytes.ubyte(base + 2),
                    hours = bytes.ubyte(base + 3) and 0x7f,
                    minutes = bytes.ubyte(base + 4),
                    endsAtClockTime = (bytes.ubyte(base + 3) and 0x80) != 0,
                ),
            )
        }
        return Fragment(total, steps)
    }

    /** 16-byte name slots. A slot starting with 0x01 has never been saved. */
    fun parseNames(payload: ByteArray): List<String?> {
        val bytes = payload
        return (0 until bytes.size step 16).map { start ->
            val slot = bytes.copyOfRange(start, min(start + 16, bytes.size))
            if (slot.first().toInt() == 0x01) null else decodeUntilNul(slot)
        }
    }

    fun parseText(payload: ByteArray): String = decodeUntilNul(payload)

    // Status

    /** True for a status notification, as opposed to the remainder served by a follow-up read. */
    fun isStatusStart(chunk: ByteArray): Boolean =
        chunk.size >= 20 && chunk.ubyte(1) == 0x56 && chunk.ubyte(3) == 0x01

    fun parseStatus(packet: ByteArray): DeviceStatus? {
        val b = packet
        if (b.size < 20) return null
        val mode = Mode.fromRaw(b.ubyte(9)) ?: return null
        var status = DeviceStatus(
            remainingSeconds = b.ubyte(4) * 3600 + b.ubyte(5) * 60 + b.ubyte(6),
            actualTemperature = b.ubyte(7),
            targetTemperature = b.ubyte(8),
            mode = mode,
            fanStep = b.ubyte(10),
            maxRuntimeMinutes = b.ubyte(11) * 60 + b.ubyte(12),
            minTemperature = b.ubyte(13),
            maxTemperature = b.ubyte(14),
            ambientTemperature = b.ubyte(17),
            lastShutdownReason = b.ubyte(18),
            extra = null,
        )
        if (b.size >= 30) {
            status = status.copy(
                extra = DeviceStatus.Extra(
                    ledEnabled = (b.ubyte(27) and 0x10) != 0,
                    beepsMuted = (b.ubyte(27) and 0x01) != 0,
                    programStep = b.ubyte(28),
                    notification = b.ubyte(29),
                ),
            )
        }
        return status
    }
}

// Byte helpers shared by the codecs and the demo unit.

/** The byte at [index] as an unsigned value, 0...255, like Swift's `UInt8`. */
internal fun ByteArray.ubyte(index: Int): Int = this[index].toInt() and 0xff

/**
 * Builds bytes from values that are already 0...255, as Swift's `Data([UInt8(...)])` does. Swift's `UInt8(x)`
 * traps on anything else, so callers clamp first wherever the Swift uses `UInt8(clamping:)`.
 */
internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Swift's `UInt8(clamping:)`. */
internal fun clampToByte(value: Int): Int = min(max(value, 0), 255)

/** Swift's `String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)`: malformed UTF-8 becomes U+FFFD. */
internal fun decodeUntilNul(bytes: ByteArray): String {
    val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
    return bytes.decodeToString(0, end)
}
