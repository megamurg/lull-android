package com.lull.bedjetkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Fixtures marked "captured" are packets the official iOS app exchanged with a BedJet V2, as published in
 * github.com/roycamp/bedjet-protocol. The rest follow the formats in the official Android app.
 * Port of `CodecV2Tests.swift`.
 */
class CodecV2Test {
    private fun data(hex: String): ByteArray = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** Bytes as hex, so a failing comparison shows which byte differs. */
    private fun hex(bytes: ByteArray?): String? = bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.prefix(n: Int): ByteArray = copyOfRange(0, minOf(n, size))

    private fun status(mode: Mode, fanStep: Int, target: Int, seconds: Int, muted: Boolean = true): DeviceStatus =
        DeviceStatus(
            remainingSeconds = seconds, actualTemperature = 50, targetTemperature = target, mode = mode, fanStep = fanStep,
            maxRuntimeMinutes = 720, minTemperature = 44, maxTemperature = 80, ambientTemperature = 50, lastShutdownReason = 0,
            extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = muted, programStep = 0, notification = 0),
        )

    @Test
    fun commandsMatchCapturedBytes() {
        assertEquals(hex(data("58 01 0b 9b")), hex(CodecV2.request(CodecV2.Query.INFO)))
        assertEquals(hex(data("58 03 06 02 10 8c")), hex(CodecV2.request(CodecV2.Query.DEVICE_NAME)))
        assertEquals(hex(data("58 02 01 02 a2")), hex(CodecV2.setMode(Mode.HEAT, current = Mode.STANDBY)))
        assertEquals(hex(data("58 02 01 01 a3")), hex(CodecV2.setMode(Mode.TURBO, current = Mode.COOL)))
        assertEquals(hex(data("58 02 11 01 93")), hex(CodecV2.setSettings(ledOn = true, muted = true)))
        assertEquals(hex(data("58 02 11 02 92")), hex(CodecV2.setSettings(ledOn = false, muted = false)))
    }

    @Test
    fun modesAreButtonsThatToggle() {
        assertEquals(hex(data("58 02 01 03 a1")), hex(CodecV2.setMode(Mode.STANDBY, current = Mode.COOL)))
        assertNull(CodecV2.setMode(Mode.COOL, current = Mode.COOL))
        assertNull(CodecV2.setMode(Mode.STANDBY, current = Mode.STANDBY))
        assertNull(CodecV2.setMode(Mode.DRY, current = Mode.STANDBY))
    }

    @Test
    fun sceneMatchesCapturedTimerCommand() {
        val heat = status(Mode.HEAT, fanStep = 16, target = 76, seconds = 3 * 60)
        assertEquals(hex(data("58 07 0e 02 11 cc 00 14 00 9f")), hex(CodecV2.setRuntime(minutes = 20, status = heat)))
    }

    @Test
    fun sceneKeepsTimerWhenChangingFan() {
        val cool = status(Mode.COOL, fanStep = 14, target = 54, seconds = 9 * 3600 + 59 * 60 + 55)
        assertEquals(hex(data("58 07 0e 03 0c b6 09 3b 37 52")), hex(CodecV2.setFan(step = 11, status = cool)))
    }

    @Test
    fun parsesCapturedOffStatus() {
        val off = assertNotNull(CodecV2.parseStatus(data("59 0b 02 a7 0c 00 00 41 00 00 00 00 00 a5")))
        assertEquals(Mode.STANDBY, off.mode)
        assertEquals(0x27, off.actualTemperature)
        assertEquals(0x41, off.targetTemperature)
        assertEquals(0, off.remainingSeconds)
        assertEquals(false, off.extra?.ledEnabled)
        assertEquals(false, off.extra?.beepsMuted)
        assertEquals(0, off.extra?.programStep)
        assertFalse(off.isRunning)
    }

    @Test
    fun parsesRunningStatus() {
        val cool = assertNotNull(CodecV2.parseStatus(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26")))
        assertEquals(Mode.COOL, cool.mode)
        assertEquals(60, cool.fanPercent)
        assertEquals(9 * 3600 + 59 * 60 + 52, cool.remainingSeconds)
        assertEquals(0x36, cool.targetTemperature)
        assertEquals(true, cool.extra?.beepsMuted)
        assertEquals(true, cool.extra?.ledEnabled)
        assertEquals(1, cool.extra?.programStep)
        assertEquals(3, cool.lastShutdownReason)
        assertEquals(720, cool.maxRuntimeMinutes)
    }

    @Test
    fun reassemblesPacketsAcrossNotifications() {
        val buffer = CodecV2.StreamBuffer()
        buffer.append(data("00 ff 59 0b 02 34 6c 9e f4"))
        assertTrue(buffer.takePackets().isEmpty())
        buffer.append(data("b6 80 00 08 00 03 26 59 12 05 02 42 65 64"))
        val packets = buffer.takePackets()
        assertEquals(listOf(hex(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))), packets.map { hex(it) })
        buffer.append(data("72 6f 6f 6d 20 20 20 20 20 20 20 20 20 a5"))
        val name = buffer.takePackets().firstOrNull()
        assertEquals(
            "Bedroom",
            name?.let { CodecV2.reply(CodecV2.Query.DEVICE_NAME, it) }?.let { CodecV2.parseName(it) },
        )
        assertEquals(0, buffer.size)
    }

    @Test
    fun dropsPacketsWithBadChecksums() {
        val buffer = CodecV2.StreamBuffer()
        buffer.append(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 27"))
        assertTrue(buffer.takePackets().isEmpty())
        assertTrue(buffer.size < 14, "expected fewer than 14 bytes left, got ${buffer.size}")
    }

    @Test
    fun parsesInfo() {
        val packet = data("59 0c 01 02 35 00 00 00 00 00 01 00 00 00 61")
        val info = assertNotNull(CodecV2.reply(CodecV2.Query.INFO, packet)?.let { CodecV2.parseInfo(it) })
        assertEquals(CodecV2.Info(protocolVersion = 2, firmware = 53, extendedSequences = true), info)
        assertNull(CodecV2.reply(CodecV2.Query.DEVICE_NAME, packet))
    }

    @Test
    fun writesSequenceSteps() {
        val heat = ProgramStep(mode = Mode.HEAT, fanStep = 7, temperature = 68, hours = 0, minutes = 30)
        assertEquals(
            hex(data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4")),
            hex(CodecV2.writeStep(heat, index = 1, minutes = 30, extended = true)),
        )
        val cool = ProgramStep(mode = Mode.COOL, fanStep = 12, temperature = 44, hours = 6, minutes = 0)
        assertEquals(
            hex(data("58 09 05 80 06 03 0d 06 00 00 28 d5")),
            hex(CodecV2.writeStep(cool, index = 0, minutes = 360, extended = false)),
        )
        assertFailsWith<BedJetException.InvalidProgram> {
            CodecV2.writeStep(
                ProgramStep(mode = Mode.DRY, fanStep = 5, temperature = 60, hours = 1, minutes = 0),
                index = 0, minutes = 60, extended = true,
            )
        }
    }

    @Test
    fun runsSequence() {
        // DateComponents(hour: 23, minute: 5, second: 9) in the Swift test.
        assertEquals(hex(data("58 04 0d 17 05 09 71")), hex(CodecV2.runSequence(hour = 23, minute = 5, second = 9, extended = true)))
        assertEquals(hex(data("58 01 0d 99")), hex(CodecV2.runSequence(hour = 23, minute = 5, second = 9, extended = false)))
    }

    @Test
    fun storingAndStartingSendsTheOfficialAppsSequence() = runBlocking<Unit> {
        val link = RecordingV2Link()
        val program = StoredProgram(
            name = "Tonight",
            steps = listOf(
                ProgramStep(mode = Mode.TURBO, fanStep = 19, temperature = 86, hours = 0, minutes = 10),
                ProgramStep(mode = Mode.HEAT, fanStep = 7, temperature = 68, hours = 0, minutes = 30),
                ProgramStep(mode = Mode.COOL, fanStep = 12, temperature = 44, hours = 6, minutes = 0),
            ),
        )
        link.store(program, slot = 2)
        link.startProgram(slot = 2)
        assertEquals(6, link.sent.size)
        assertEquals(hex(data("58 01 0c 9a")), hex(link.sent[0]))
        assertEquals(hex(data("58 0a 05 80 07 01 14 00 0a 00 50 00 a2")), hex(link.sent[1]))
        assertEquals(hex(data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4")), hex(link.sent[2]))
        assertEquals(hex(data("58 0a 05 82 07 03 0d 06 00 00 28 00 d1")), hex(link.sent[3]))
        assertEquals(hex(data("58 0d 05 03 0a")), hex(link.sent[4].prefix(5)))
        assertEquals(hex(data("58 04 0d")), hex(link.sent[5].prefix(3)))
    }

    @Test
    fun refusesModesTheV2Lacks() = runBlocking<Unit> {
        val link = RecordingV2Link()
        val program = StoredProgram(
            name = "Cocoon",
            steps = listOf(ProgramStep(mode = Mode.EXTENDED_HEAT, fanStep = 5, temperature = 60, hours = 2, minutes = 0)),
        )
        val error = assertFailsWith<BedJetException>("stored a mode the V2 doesn't have") {
            link.store(program, slot = 0)
        }
        assertEquals(BedJetException.InvalidProgram(CodecV2.UNSUPPORTED_MODES), error)
        assertTrue(link.sent.isEmpty())
    }

    // Not in the Swift suite: the framing edge cases `takePackets` documents.

    @Test
    fun streamBufferTreatsImplausibleLengthsAsData() {
        val buffer = CodecV2.StreamBuffer()
        // 0x59 followed by a length over 64, then 0x59 followed by a length of 0 (a packet of 3 or fewer bytes):
        // neither starts a packet, so the real one after them is still found.
        buffer.append(data("59 7f 59 00") + data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))
        assertEquals(listOf(hex(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))), buffer.takePackets().map { hex(it) })
        assertEquals(0, buffer.size)
    }

    @Test
    fun streamBufferKeepsALonePrefixAndDropsNoise() {
        val buffer = CodecV2.StreamBuffer()
        buffer.append(data("00 01 02 59"))
        assertTrue(buffer.takePackets().isEmpty())
        assertEquals(1, buffer.size)
        buffer.append(data("0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))
        assertEquals(1, buffer.takePackets().size)
        assertEquals(0, buffer.size)
        buffer.append(data("00 01 02"))
        assertTrue(buffer.takePackets().isEmpty())
        assertEquals(0, buffer.size)
        buffer.append(data("59"))
        buffer.clear()
        assertEquals(0, buffer.size)
    }
}

/** Answers like a V2 on firmware 53 and records every command, with the time it was sent. */
internal class RecordingV2Link(
    /** The reply payload to `CodecV2.Query.INFO`. */
    private val info: ByteArray = byteArrayOf(2, 53, 0, 0, 0, 0, 0, 1, 0, 0, 0),
) : BedJetLink {
    override var state: LinkState = LinkState.READY
    override val generation: Generation get() = Generation.V2
    override var lastStatus: DeviceStatus? = null
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null
    val sent: MutableList<ByteArray> = mutableListOf()
    val sentAtNanos: MutableList<Long> = mutableListOf()

    override fun start() {}
    override fun stop() {}
    override suspend fun send(command: ByteArray) {
        sent.add(command)
        sentAtNanos.add(System.nanoTime())
    }

    override suspend fun query(request: Int): ByteArray {
        if (request != CodecV2.Query.INFO) throw BedJetException.BadResponse
        return info
    }
}
