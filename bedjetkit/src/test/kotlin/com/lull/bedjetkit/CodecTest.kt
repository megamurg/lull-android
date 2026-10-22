package com.lull.bedjetkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Byte fixtures below were captured from a BedJet 3 running firmware V1.20. Port of `CodecTests.swift`. */
class CodecTest {
    private fun data(hex: String): ByteArray = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** Bytes as hex, so a failing comparison shows which byte differs. */
    private fun hex(bytes: ByteArray?): String? = bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.dropFirst(n: Int): ByteArray = copyOfRange(n, size)

    @Test
    fun parsesStatusCapturedFromDevice() {
        val packet = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53")
        val status = assertNotNull(Codec.parseStatus(packet))
        assertEquals(Mode.STANDBY, status.mode)
        assertEquals(46, status.actualTemperature)
        assertEquals(79, status.targetTemperature)
        assertEquals(100, status.fanPercent)
        assertEquals(45, status.ambientTemperature)
        assertEquals(0, status.extra?.programStep)
        assertEquals(true, status.extra?.ledEnabled)
        assertEquals(true, status.extra?.beepsMuted)
        assertEquals(0, status.lastShutdownReason)
        assertEquals(Codec.Notification.CLEAN_FILTER, status.extra?.notification)
    }

    @Test
    fun firstChunkAloneStillParses() {
        val head = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12")
        assertTrue(Codec.isStatusStart(head))
        assertNull(assertNotNull(Codec.parseStatus(head)).extra)
    }

    @Test
    fun parsesProgramCapturedFromDevice() {
        val reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val fragment = assertNotNull(Codec.parseFragment(reply.dropFirst(2), index = 0))
        assertEquals(4, fragment.total)
        assertEquals(
            listOf(
                ProgramStep(mode = Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0),
                ProgramStep(mode = Mode.COOL, fanStep = 13, temperature = 49, hours = 3, minutes = 0),
                ProgramStep(mode = Mode.COOL, fanStep = 9, temperature = 46, hours = 1, minutes = 15),
                ProgramStep(mode = Mode.HEAT, fanStep = 4, temperature = 47, hours = 1, minutes = 0),
            ),
            fragment.steps,
        )
    }

    @Test
    fun writingReproducesTheBytesTheDeviceReturned() {
        val reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val steps = Codec.parseFragment(reply.dropFirst(2), index = 0)!!.steps
        val commands = Codec.writeProgram(slot = 0, steps = steps)
        assertEquals(1, commands.size)
        assertEquals(hex(byteArrayOf(0x40, 0x07)), hex(commands[0].copyOfRange(0, 2)))
        assertEquals(hex(reply.dropFirst(2)), hex(commands[0].dropFirst(2)))
    }

    @Test
    fun parsesNamesCapturedFromDevice() {
        val reply = data("04 05 63 68 6f 74 65 20 6d 6f 74 65 7a 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 73 6c 65 65 65 65 70 00 00 00 00 00 00 00 00 00")
        assertEquals(listOf("chote motez", null, "sleeeep"), Codec.parseNames(reply.dropFirst(2)))
    }

    @Test
    fun longProgramsSplitIntoFragments() {
        val steps = (0 until 19).map { ProgramStep(mode = Mode.COOL, fanStep = it, temperature = 40, hours = 0, minutes = 30) }
        val commands = Codec.writeProgram(slot = 2, steps = steps)
        assertEquals(listOf(15, 16, 17), commands.map { it[1].toInt() and 0xff })
        assertTrue(commands.all { it.size == 43 && it[2].toInt() == 19 })
        assertEquals(steps.drop(16), Codec.parseFragment(commands[2].dropFirst(2), index = 2)?.steps)
    }

    @Test
    fun clockTimeFlagSharesTheHoursByte() {
        val step = ProgramStep(mode = Mode.COOL, fanStep = 14, temperature = 36, hours = 6, minutes = 30, endsAtClockTime = true)
        val command = Codec.writeProgram(slot = 0, steps = listOf(step))[0]
        assertEquals(hex(data("04 0e 24 86 1e")), hex(command.copyOfRange(3, 8)))
        assertEquals(listOf(step), Codec.parseFragment(command.dropFirst(2), index = 0)?.steps)
    }

    @Test
    fun nameCommandIsPaddedAndTrimmed() {
        val command = Codec.nameProgram(slot = 1, name = "A very long schedule name ✨")
        assertEquals(19, command.size)
        assertEquals(hex(data("40 05 10")), hex(command.copyOfRange(0, 3)))
        assertEquals("A very long sch", Codec.parseText(command.dropFirst(3)))
    }

    /** The Swift test calls `BLELink.handoffUUID(forDeviceNamed:)`; the derivation lives in [Handoff] here. */
    @Test
    fun handOverRequestsAreSpecificToOneBedJet() {
        val mine = Handoff.uuid("BEDJET-3B04")
        assertEquals(Handoff.uuid("BEDJET-3B04"), mine)
        assertNotEquals(Handoff.uuid("BEDJET-3B05"), mine)
        // `mine.data.count == 16` in the Swift: a full 128-bit UUID, 32 hex digits.
        assertEquals(32, mine.toString().replace("-", "").length)
    }

    @Test
    fun runtimeCommandSplitsHoursAndMinutes() {
        assertEquals(hex(byteArrayOf(0x02, 2, 30)), hex(Codec.setRuntime(minutes = 150)))
    }

    @Test
    fun runtimeChangesTheShutOffTimeAndStopsAtTheModeLimit() = runBlocking<Unit> {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.setMode(Mode.HEAT)
        assertEquals(60 * 60, latest?.remainingSeconds)
        link.send(Codec.setRuntime(minutes = 150))
        assertEquals(150 * 60, latest?.remainingSeconds)
        link.send(Codec.setRuntime(minutes = 600))
        assertEquals(240 * 60, latest?.remainingSeconds)
    }

    @Test
    fun storeAndReadBackThroughSimulatedDevice() = runBlocking<Unit> {
        val link = DemoLink(
            slots = listOf(
                StoredProgram(name = "old", steps = listOf(ProgramStep(mode = Mode.DRY, fanStep = 1, temperature = 60, hours = 1, minutes = 0))),
                null,
                null,
            ),
        )
        val steps = (0 until 11).map {
            ProgramStep(mode = if (it % 2 == 0) Mode.COOL else Mode.HEAT, fanStep = it, temperature = 50, hours = 0, minutes = 20 + it)
        }
        link.store(StoredProgram(name = "Deep Sleep", steps = steps), slot = 1)
        val stored = link.storedPrograms()
        assertEquals("old", stored[0]?.name)
        assertEquals(StoredProgram(name = "Deep Sleep", steps = steps), stored[1])
        assertNull(stored[2])
    }

    @Test
    fun startingAProgramSyncsTheClockAndRunsTheFirstStep() = runBlocking<Unit> {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.store(
            StoredProgram(name = "Night", steps = listOf(ProgramStep(mode = Mode.COOL, fanStep = 5, temperature = 44, hours = 2, minutes = 0))),
            slot = 0,
        )
        link.startProgram(slot = 0)
        assertNotNull(link.clock)
        assertEquals(Mode.COOL, latest?.mode)
        assertEquals(5, latest?.fanStep)
        assertEquals(1, latest?.extra?.programStep)
    }

    /** Values follow the heat table in the official BedJet app (v1.0.6), which keeps the unit's safety cut-off. */
    @Test
    fun heatIsCutOffSoonerWhenHotterAndFaster() {
        assertEquals(60, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 80, fanStep = 19))
        assertEquals(120, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 75, fanStep = 19))
        assertEquals(240, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 80, fanStep = 0))
        assertEquals(60, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 81, fanStep = 0))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 66, fanStep = 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(mode = Mode.HEAT, temperature = 50, fanStep = 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(mode = Mode.COOL, temperature = 80, fanStep = 19))
        assertFalse(ProgramStep(mode = Mode.HEAT, fanStep = 19, temperature = 80, hours = 2, minutes = 0).fitsUnitLimits)
        assertTrue(ProgramStep(mode = Mode.HEAT, fanStep = 19, temperature = 80, hours = 1, minutes = 0).fitsUnitLimits)
        assertTrue(ProgramStep(mode = Mode.HEAT, fanStep = 19, temperature = 80, hours = 6, minutes = 0, endsAtClockTime = true).fitsUnitLimits)
    }

    @Test
    fun programsPastTheHeatCutOffAreNotSaved() = runBlocking<Unit> {
        val link = DemoLink()
        val program = StoredProgram(name = "too hot", steps = listOf(ProgramStep(mode = Mode.HEAT, fanStep = 19, temperature = 80, hours = 3, minutes = 0)))
        val error = assertFailsWith<BedJetException>("stored a step the unit would cut off") {
            link.store(program, slot = 0)
        }
        assertEquals(BedJetException.ProgramTooLong, error)
        assertNull(link.slots[0])
    }

    @Test
    fun unitRefusesAProgramItCannotFinishUntilAcknowledged() = runBlocking<Unit> {
        val tooHot = ProgramStep(mode = Mode.HEAT, fanStep = 19, temperature = 80, hours = 3, minutes = 0)
        val link = DemoLink(slots = listOf(StoredProgram(name = "too hot", steps = listOf(tooHot)), null, null))
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.startProgram(slot = 0)
        assertEquals(Mode.STANDBY, latest?.mode)
        assertEquals(0, latest?.extra?.programStep)
        assertEquals(Codec.Notification.PROGRAM_TOO_LONG, latest?.extra?.notification)
        link.send(Codec.acknowledgeNotification)
        assertEquals(0, latest?.extra?.notification)
    }
}
