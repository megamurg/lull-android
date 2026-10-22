package com.lull.bedjetkit

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The `BedJetLink` extensions (Link.kt) and `DemoLink` behaviour that the Swift suite exercises only indirectly.
 * Not a port of a Swift file; each test names the Swift logic it pins down.
 */
class LinkTest {
    private fun data(hex: String): ByteArray = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: ByteArray?): String? = bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    private fun v2Status(mode: Mode, programStep: Int = 0, ledEnabled: Boolean = true, muted: Boolean = false) = DeviceStatus(
        remainingSeconds = 600, actualTemperature = 50, targetTemperature = 60, mode = mode, fanStep = 9,
        maxRuntimeMinutes = 720, minTemperature = 44, maxTemperature = 80, ambientTemperature = 50, lastShutdownReason = 0,
        extra = DeviceStatus.Extra(ledEnabled = ledEnabled, beepsMuted = muted, programStep = programStep, notification = 0),
    )

    // Errors

    @Test
    fun errorMessagesMatchTheSwiftDescriptions() {
        assertEquals("Couldn't reach your BedJet. Check that it's plugged in and nearby.", BedJetException.NotConnected.message)
        assertEquals("Another phone is using your BedJet right now. Try again in a moment.", BedJetException.InUseElsewhere.message)
        assertEquals("Your BedJet didn't respond. Move closer and try again.", BedJetException.Timeout.message)
        assertEquals("Your BedJet sent something unexpected. Try again.", BedJetException.BadResponse.message)
        assertEquals("The schedule didn't save correctly. Try again.", BedJetException.VerifyFailed.message)
        assertEquals(
            "Your BedJet's clock wasn't set, so it didn't start the schedule. Try again.",
            BedJetException.ClockNotSet.message,
        )
        assertEquals(
            "Your BedJet won't run a phase this long. Shorten the Heat phases or use Gentle heat.",
            BedJetException.ProgramTooLong.message,
        )
        assertEquals("why", BedJetException.InvalidProgram("why").message)
        assertEquals(BedJetException.InvalidProgram("why"), BedJetException.InvalidProgram("why"))
    }

    // V2 remote

    @Test
    fun v2OffWhileASequenceWaitsPressesCoolTwiceASecondApart() = runBlocking<Unit> {
        val link = RecordingV2Link()
        link.lastStatus = v2Status(Mode.STANDBY, programStep = 2)
        link.setMode(Mode.STANDBY)
        assertEquals(listOf("58 02 01 03 a1", "58 02 01 03 a1"), link.sent.map { hex(it) })
        val gapMs = (link.sentAtNanos[1] - link.sentAtNanos[0]) / 1_000_000
        assertTrue(gapMs >= 990, "presses were ${gapMs}ms apart")
    }

    @Test
    fun v2OffWhenNothingIsRunningSendsNothing() = runBlocking<Unit> {
        val link = RecordingV2Link()
        link.lastStatus = v2Status(Mode.STANDBY, programStep = 0)
        link.setMode(Mode.STANDBY)
        link.lastStatus = null
        link.setMode(Mode.STANDBY)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun v2ModesPressTheButtonOfTheModeTheUnitIsIn() = runBlocking<Unit> {
        val link = RecordingV2Link()
        link.lastStatus = v2Status(Mode.COOL)
        link.setMode(Mode.STANDBY)
        link.lastStatus = null
        link.setMode(Mode.HEAT)
        assertEquals(listOf("58 02 01 03 a1", "58 02 01 02 a2"), link.sent.map { hex(it) })
    }

    @Test
    fun v2SceneCommandsNeedAStatus() = runBlocking<Unit> {
        val link = RecordingV2Link()
        assertFailsWith<BedJetException.NotConnected> { link.setTemperature(60) }
        assertFailsWith<BedJetException.NotConnected> { link.setFan(5) }
        assertFailsWith<BedJetException.NotConnected> { link.setRuntime(30) }
        assertTrue(link.sent.isEmpty())
        link.lastStatus = v2Status(Mode.HEAT)
        link.setTemperature(70)
        assertEquals(hex(CodecV2.setTemperature(70, v2Status(Mode.HEAT))), hex(link.sent.single()))
    }

    @Test
    fun v2SettingsKeepTheOtherSettingFromTheStatus() = runBlocking<Unit> {
        val link = RecordingV2Link()
        link.lastStatus = v2Status(Mode.STANDBY, ledEnabled = false, muted = true)
        link.setLed(true)
        link.setMuted(false)
        link.acknowledgeNotification()
        link.syncClock(LocalTime.of(1, 2))
        assertEquals(
            listOf(hex(CodecV2.setSettings(ledOn = true, muted = true)), hex(CodecV2.setSettings(ledOn = false, muted = false))),
            link.sent.map { hex(it) },
        )
    }

    @Test
    fun v2DeviceInfo() = runBlocking<Unit> {
        val link = RecordingV2Link()
        assertEquals("53 (BedJet V2)", link.firmwareVersion())
        assertEquals(emptyList(), link.storedPrograms())
        assertFailsWith<BedJetException.BadResponse> { RecordingV2Link(info = byteArrayOf(2)).firmwareVersion() }
    }

    // V2 sequences

    @Test
    fun v2SequenceWritesAreSpacedApart() = runBlocking<Unit> {
        val link = RecordingV2Link()
        val began = System.nanoTime()
        link.store(StoredProgram("One", listOf(ProgramStep(Mode.COOL, fanStep = 3, temperature = 44, hours = 1, minutes = 0))), slot = 0)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(3, link.sent.size)
        for (i in 1 until link.sent.size) {
            val gapMs = (link.sentAtNanos[i] - link.sentAtNanos[i - 1]) / 1_000_000
            assertTrue(gapMs >= 190, "writes $i were ${gapMs}ms apart")
        }
        // The Swift also waits after the last write, so starting right away still leaves the unit its moment.
        assertTrue(tookMs >= 3 * 190, "store took ${tookMs}ms")
    }

    @Test
    fun v2StepsThatEndAtAClockTimeBecomeDurationsFromNow() = runBlocking<Unit> {
        val link = RecordingV2Link()
        val steps = listOf(
            ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0),
            ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 6, minutes = 30, endsAtClockTime = true),
            ProgramStep(Mode.HEAT, fanStep = 4, temperature = 50, hours = 7, minutes = 0, endsAtClockTime = true),
        )
        link.store(StoredProgram("Night", steps), slot = 0, now = LocalTime.of(22, 30))
        // Bytes 7 and 8 of a step write are its hours and minutes.
        val durations = link.sent.subList(1, 4).map { Pair(it[7].toInt(), it[8].toInt()) }
        // 22:30 + 1 h = 23:30; 23:30 until 06:30 is 7 h; 06:30 until 07:00 is 30 min.
        assertEquals(listOf(Pair(1, 0), Pair(7, 0), Pair(0, 30)), durations)
    }

    @Test
    fun v2StartGivesNewerUnitsTheTimeOfDay() = runBlocking<Unit> {
        val link = RecordingV2Link()
        link.startProgram(slot = 0, now = LocalTime.of(23, 5, 9))
        assertEquals(hex(data("58 04 0d 17 05 09 71")), hex(link.sent.single()))
    }

    @Test
    fun v2OlderFormatUnitsGetShortStepsAndABareStart() = runBlocking<Unit> {
        // Firmware 52 with the newer-format bit set still uses the older format; the bit only counts from 53.
        val link = RecordingV2Link(info = byteArrayOf(2, 52, 0, 0, 0, 0, 0, 1))
        link.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, fanStep = 12, temperature = 44, hours = 6, minutes = 0))), slot = 0)
        link.startProgram(slot = 0, now = LocalTime.of(23, 5, 9))
        assertEquals(hex(data("58 09 05 80 06 03 0d 06 00 00 28 d5")), hex(link.sent[1]))
        assertEquals(hex(data("58 01 0d 99")), hex(link.sent.last()))
    }

    @Test
    fun v2FirmwareTooOldForSequencesIsRefused() = runBlocking<Unit> {
        val link = RecordingV2Link(info = byteArrayOf(2, 47))
        val error = assertFailsWith<BedJetException.InvalidProgram> {
            link.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, fanStep = 3, temperature = 44, hours = 1, minutes = 0))), slot = 0)
        }
        assertEquals(
            "This BedJet V2 needs a firmware update before it can run schedules. Update it in the official BedJet app.",
            error.message,
        )
        assertTrue(link.sent.isEmpty())
    }

    // Checks before storing

    @Test
    fun programsMustHaveStepsAndFitTheUnit() = runBlocking<Unit> {
        val step = ProgramStep(Mode.COOL, fanStep = 3, temperature = 44, hours = 0, minutes = 10)
        assertEquals(
            BedJetException.InvalidProgram("Add at least one phase first."),
            assertFailsWith<BedJetException> { DemoLink().store(StoredProgram("Empty", emptyList()), slot = 0) },
        )
        assertEquals(
            BedJetException.InvalidProgram("A BedJet schedule can hold up to 32 phases."),
            assertFailsWith<BedJetException> { DemoLink().store(StoredProgram("Long", List(33) { step }), slot = 0) },
        )
        val v2 = RecordingV2Link()
        assertEquals(
            BedJetException.InvalidProgram("A BedJet schedule can hold up to 31 phases."),
            assertFailsWith<BedJetException> { v2.store(StoredProgram("Long", List(32) { step }), slot = 0) },
        )
        // The heat cut-off is checked before the modes, as in the Swift.
        val hotDry = listOf(
            ProgramStep(Mode.DRY, fanStep = 3, temperature = 60, hours = 1, minutes = 0),
            ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 3, minutes = 0),
        )
        assertEquals(BedJetException.ProgramTooLong, assertFailsWith<BedJetException> { v2.store(StoredProgram("Hot", hotDry), slot = 0) })
        assertTrue(v2.sent.isEmpty())
    }

    // BedJet 3

    @Test
    fun v3StartSetsTheClockBeforeStarting() = runBlocking<Unit> {
        val link = RecordingV3Link()
        link.startProgram(slot = 1, now = LocalTime.of(22, 45))
        assertEquals(listOf(hex(data("08 16 2d")), hex(data("01 81"))), link.sent.map { hex(it) })
        assertEquals(Pair(22, 45), link.demo.clock)
    }

    @Test
    fun v3StoreWritesFragmentsThenTheNameAndReadsItBack() = runBlocking<Unit> {
        val link = RecordingV3Link()
        val steps = List(9) { ProgramStep(Mode.COOL, fanStep = it, temperature = 40, hours = 0, minutes = 30) }
        link.store(StoredProgram("Nine", steps), slot = 2)
        assertEquals(listOf(15, 16, 6), link.sent.map { it[1].toInt() })
        assertEquals(listOf(15, 16), link.queries)
        assertEquals(StoredProgram("Nine", steps), link.demo.slots[2])
    }

    @Test
    fun v3StoreFailsWhenTheUnitKeptSomethingElse() = runBlocking<Unit> {
        // The unit loses the last step's minutes.
        val link = RecordingV3Link(corrupt = { bytes -> bytes.copyOf().also { if (it[0].toInt() == 0x40 && it.size == 43) it[7] = 0 } })
        val steps = listOf(ProgramStep(Mode.COOL, fanStep = 5, temperature = 44, hours = 2, minutes = 15))
        assertFailsWith<BedJetException.VerifyFailed> { link.store(StoredProgram("Night", steps), slot = 0) }
    }

    @Test
    fun v3BadFragmentReplyIsABadResponse() = runBlocking<Unit> {
        val link = object : BedJetLink by DemoLink() {
            override suspend fun query(request: Int): ByteArray = byteArrayOf(99)
        }
        assertFailsWith<BedJetException.BadResponse> { link.programSteps(0) }
    }

    @Test
    fun v3CommandsAreTheCodecBytes() = runBlocking<Unit> {
        val link = RecordingV3Link()
        link.setMode(Mode.WAIT)
        link.setMode(Mode.DRY)
        link.setTemperature(300)
        link.setFan(25)
        link.setRuntime(90)
        link.setLed(false)
        link.setMuted(true)
        link.acknowledgeNotification()
        assertEquals(
            listOf("01 05", "03 ff", "07 13", "02 01 1e", "01 47", "01 48", "01 52"),
            link.sent.map { hex(it) },
        )
        assertEquals("BEDJET-DEMO", link.deviceName())
        assertEquals("V1.20 (demo)", link.firmwareVersion())
    }

    // DemoLink

    @Test
    fun demoConnectsOnItsFirstTick() {
        val link = DemoLink()
        val states = mutableListOf<LinkState>()
        link.onStateChange = { states.add(it) }
        assertNull(link.lastStatus)
        link.start()
        assertEquals(LinkState.CONNECTING, link.state)
        assertNull(link.lastStatus)
        link.start()
        link.tick()
        assertEquals(LinkState.READY, link.state)
        assertNotNull(link.lastStatus)
        link.stop()
        assertEquals(listOf(LinkState.CONNECTING, LinkState.READY, LinkState.SEARCHING), states)
        assertNull(link.lastStatus)
    }

    @Test
    fun demoWithAScopeTicksEverySecond() = runBlocking<Unit> {
        val link = DemoLink(scope = this)
        try {
            link.start()
            delay(1_100)
            assertEquals(LinkState.READY, link.state)
        } finally {
            // Cancels the tick loop, which would otherwise keep runBlocking waiting forever.
            link.stop()
        }
        assertEquals(LinkState.SEARCHING, link.state)
    }

    @Test
    fun demoSkipsAheadThroughARunningProgram() = runBlocking<Unit> {
        var now = 1_000_000_000L
        val link = DemoLink(now = { now })
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        val program = StoredProgram(
            "Night",
            listOf(
                ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0),
                ProgramStep(Mode.HEAT, fanStep = 4, temperature = 50, hours = 0, minutes = 30),
            ),
        )
        link.store(program, slot = 0)
        link.startProgram(slot = 0, now = LocalTime.of(22, 30))
        assertEquals(1, latest?.extra?.programStep)
        link.skipAhead(minutes = 70)
        assertEquals(Mode.HEAT, latest?.mode)
        assertEquals(2, latest?.extra?.programStep)
        assertEquals(20 * 60, latest?.remainingSeconds)
        assertEquals(50, latest?.targetTemperature)
        // The step's end passes, and the program finishes on the next tick.
        now += 20 * 60_000L
        link.tick()
        assertEquals(Mode.STANDBY, latest?.mode)
        assertEquals(0, latest?.extra?.programStep)
    }

    @Test
    fun demoNeedsItsClockSetToStartAProgram() = runBlocking<Unit> {
        val link = DemoLink(slots = listOf(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 1, 0))), null, null))
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.send(Codec.startProgram(0))
        assertEquals(Codec.Notification.PROGRAM_NEEDS_CLOCK, latest?.extra?.notification)
        assertEquals(Mode.STANDBY, latest?.mode)
        link.send(Codec.deleteProgram(0))
        assertNull(link.slots[0])
    }

    @Test
    fun demoManualHeatFollowsTheHeatTable() = runBlocking<Unit> {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.setMode(Mode.HEAT)
        link.setFan(19)
        link.setTemperature(80)
        // 80 half-degrees at full fan is cut off after an hour.
        assertEquals(60, latest?.maxRuntimeMinutes)
        assertEquals(60 * 60, latest?.remainingSeconds)
        repeat(3) { link.tick() }
        assertEquals(60 * 60 - 3, latest?.remainingSeconds)
        // The air moves a half-degree a second toward the target, from 46.
        assertEquals(49, latest?.actualTemperature)
    }
}

/** A BedJet 3 (a [DemoLink]) that records what it is sent and asked, and can change commands on the way in. */
internal class RecordingV3Link(
    val demo: DemoLink = DemoLink(),
    private val corrupt: (ByteArray) -> ByteArray = { it },
) : BedJetLink by demo {
    val sent: MutableList<ByteArray> = mutableListOf()
    val queries: MutableList<Int> = mutableListOf()

    override suspend fun send(command: ByteArray) {
        sent.add(command)
        demo.send(corrupt(command))
    }

    override suspend fun query(request: Int): ByteArray {
        queries.add(request)
        return demo.query(request)
    }
}
