package com.lull.app.model

import com.lull.bedjetkit.BedJetException
import com.lull.bedjetkit.BedJetLink
import com.lull.bedjetkit.Codec
import com.lull.bedjetkit.ConnectionTiming
import com.lull.bedjetkit.DemoLink
import com.lull.bedjetkit.Generation
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.Mode
import com.lull.bedjetkit.ProgramStep
import com.lull.bedjetkit.StoredProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives `AppModel` and its two sides against simulated BedJets. Everything runs on the `runBlocking` thread: the
 * model's coroutines are children of the test, and `DemoLink` (built without a scope) reports from inside `send()`
 * and `tick()`. Models and links share one clock that the test moves; the model's waits (settling, polling) are
 * real delays.
 */
class AppModelTest {
    /** Stands in for kotlinx.serialization: "encodes" an object by keeping it and handing back a key. */
    private class FakeJson : JsonCodec {
        val objects = mutableMapOf<String, Any>()
        private var next = 0

        fun put(value: Any): String = "#${next++}".also { objects[it] = value }

        override fun encodeSaved(saved: Saved): String = put(saved)
        override fun decodeSaved(text: String): Saved? = objects[text] as? Saved
        override fun encodeSide(side: SavedSide): String = put(side)
        override fun decodeSide(text: String): SavedSide? = objects[text] as? SavedSide
        override fun encodeHistory(history: SavedHistory): String = put(history)
        override fun decodeHistory(text: String): SavedHistory? = objects[text] as? SavedHistory
    }

    /** The picker's scan, fed by the test. */
    private class FakeFinder : UnitFinder {
        override val found = MutableStateFlow<List<FoundUnit>>(emptyList())
        var isStarted = false
        override fun start() { isStarted = true }
        override fun stop() { isStarted = false }
    }

    /**
     * Stands in for `BleLink`: bound like it, with the address and name kept in the side's storage under the same
     * keys, and connected to [unit], the simulated BedJet at the bound address. Unbound, it never starts.
     */
    private class FakeBleLink(private val storage: Storage, val unit: DemoLink) : BedJetLink by unit, HardwareLink {
        override var canYield: () -> Boolean = { true }
        override val lastConnection: ConnectionTiming? = null
        override val address: String? get() = storage.getString("BedJetKit.peripheral")
        override fun bind(address: String?, name: String?) {
            storage.putString("BedJetKit.peripheral", address)
            storage.putString("BedJetKit.deviceName", name)
        }
        override fun forget() = bind(null, null)
        override fun start() {
            if (address != null) unit.start()
        }
    }

    /**
     * Both sides of the bed. In demo mode each side gets a fresh `DemoLink` ("BEDJET-DEMO-L" with [slots], "-R" empty),
     * as in the app. Otherwise each gets a [FakeBleLink] onto the BedJet in [units] at the side's bound address,
     * which stays the same simulated unit for as long as the test runs, like a real BedJet.
     */
    private class Harness(
        scope: CoroutineScope,
        slots: List<StoredProgram?> = listOf(null, null, null),
        demo: Boolean = true,
        val storage: InMemoryStorage = InMemoryStorage(),
        val json: FakeJson = FakeJson(),
        start: Long = START,
        val finder: FakeFinder = FakeFinder(),
        val units: MutableMap<String, DemoLink> = mutableMapOf(),
    ) {
        var now = start
        /** Every link made, with its side, oldest first. */
        val links = mutableListOf<Pair<Int, BedJetLink>>()
        val model = AppModel(
            storage = storage,
            scope = scope,
            linkFactory = { isDemo: Boolean, index: Int ->
                val name = if (index == 0) "BEDJET-DEMO-L" else "BEDJET-DEMO-R"
                val link: BedJetLink = if (isDemo) {
                    DemoLink(slots = if (index == 0) slots else listOf(null, null, null), scope = null, now = { now }, name = name)
                } else {
                    val side = PrefixedStorage(storage, "side$index.")
                    val address = side.getString("BedJetKit.peripheral")
                    // An unbound link has no BedJet; this one is never started.
                    val unit = address?.let { units.getOrPut(it) { DemoLink(scope = null, now = { now }, name = it) } } ?: DemoLink()
                    FakeBleLink(side, unit)
                }
                links.add(index to link)
                link
            },
            finderFactory = { finder },
            options = LaunchOptions(forceDemo = demo),
            json = json,
            clock = { now },
        )
        val left: SideModel get() = model.sides[0]
        val right: SideModel get() = model.sides[1]

        /** The simulated BedJet a side's current link talks to. */
        fun unit(index: Int): DemoLink = when (val link = links.last { it.first == index }.second) {
            is FakeBleLink -> link.unit
            else -> link as DemoLink
        }

        fun savedFile(name: String): Any? = storage.readFile(name)?.let { json.objects[it] }
    }

    /** Test methods must return void for JUnit 4, hence the explicit `Unit`. */
    private fun test(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        body()
        // The model's timers (settling, the slot refresh after connecting) would otherwise keep the test waiting.
        coroutineContext[Job]?.cancelChildren()
        Unit
    }

    @Test
    fun firstLaunchStartsFromTheTemplates() = test {
        val h = Harness(this)
        assertEquals(Schedule.templates, h.model.schedules.value)
        assertTrue(h.model.demoMode.value)
        assertEquals(2, h.model.sides.size)
        assertEquals(0, h.model.selectedSide.value)
        assertSame(h.left, h.model.selected.value)
        for ((index, side) in h.model.sides.withIndex()) {
            assertEquals(index, side.index)
            assertEquals(Schedule.templates[0].id, side.tonightId.value)
            assertEquals("Cool Drift", side.tonight?.name)
            assertEquals(LinkState.CONNECTING, side.linkState.value)
            // Demo mode comes with a simulated BedJet on each side.
            assertTrue(side.isSetUp)
        }
        assertEquals(listOf("Left", "Right"), h.model.sides.map { it.label.value })
        assertEquals(listOf("BEDJET-DEMO-L", "BEDJET-DEMO-R"), h.model.sides.map { it.unitName.value })
        h.unit(0).tick()
        assertEquals(LinkState.READY, h.left.linkState.value)
        assertTrue(h.left.isConnected)
        assertNotNull(h.left.status.value)
        assertEquals(LinkState.CONNECTING, h.right.linkState.value)
        assertEquals("v3", h.storage.getString("side0.generation"))
        assertNull(h.storage.getString("generation"))
    }

    @Test
    fun startingTonightStoresTheProgramAndRunsIt() = test {
        val h = Harness(this)
        val side = h.left
        h.unit(0).tick()
        side.startTonight()

        val coolDrift = Schedule.templates[0]
        assertNull(side.errorMessage.value)
        assertEquals(StoredProgram("Cool Drift", coolDrift.program.steps), h.unit(0).slots[0])
        assertEquals(0, side.slot.value)
        assertEquals(ActiveRun(coolDrift, START), side.run.value)
        assertEquals(1, side.status.value?.extra?.programStep)
        assertNull(side.runningElsewhereStep)
        assertFalse(side.isBusy.value)
        assertEquals(listOf<StoredProgram?>(StoredProgram("Cool Drift", coolDrift.program.steps), null, null), side.deviceSlots.value)
        val started = side.history.value.single()
        assertEquals(HistoryEntry.Event.ScheduleStarted(name = "Cool Drift", phases = coolDrift.phases), started.event)
        assertEquals(START, started.date)
        assertEquals(45, started.room)

        // Saved in the side's own file, so a relaunch picks the night back up.
        val saved = assertIs<SavedSide>(h.savedFile("side0.side.json"))
        assertEquals(ActiveRun(coolDrift, START), saved.run)
        assertEquals(0, saved.slot)
        assertEquals(coolDrift.id, saved.tonightId)
        val relaunched = Harness(this, storage = h.storage, json = h.json, start = START + 60_000)
        assertEquals(ActiveRun(coolDrift, START), relaunched.left.run.value)
        assertEquals(0, relaunched.left.slot.value)
        assertEquals(1, relaunched.left.history.value.size)
        assertNull(relaunched.right.run.value)
    }

    @Test
    fun endingTheNightIsRecordedAsYours() = test {
        val h = Harness(this)
        val side = h.left
        h.unit(0).tick()
        side.startTonight()
        h.now += 60 * MINUTE
        side.endNight()

        assertNull(side.run.value)
        assertEquals(Mode.STANDBY, side.status.value?.mode)
        val ended = side.history.value.last()
        assertEquals(HistoryEntry.Event.ScheduleEnded(name = "Cool Drift", ending = HistoryEntry.Ending.ByYou), ended.event)
        assertEquals(START + 60 * MINUTE, ended.date)
        assertEquals(2, side.history.value.size)
    }

    @Test
    fun aScheduleThatStopsEarlyIsReported() = test {
        val h = Harness(this)
        val side = h.left
        h.unit(0).tick()
        side.startTonight()
        // Someone turns the BedJet off at the unit 20 seconds in, past the 15 s the start needs to settle.
        h.now += 20_000
        h.unit(0).send(Codec.setMode(Mode.STANDBY)!!)

        assertNull(side.run.value)
        assertEquals(
            "Your BedJet turned off 7 hr 44 min before tonight's schedule ended (BedJet reason 0).",
            side.errorMessage.value,
        )
        assertNull(h.right.errorMessage.value)
        val ended = side.history.value.last()
        assertEquals(HistoryEntry.Event.ScheduleEnded(name = "Cool Drift", ending = HistoryEntry.Ending.Early(464)), ended.event)
        assertFalse(ended.noticedLater)
        side.clearError()
        assertNull(side.errorMessage.value)
    }

    @Test
    fun fullSlotsAskWhichToReuseAndKeepWhatWasOnThem() = test {
        val step = { minutes: Int -> ProgramStep(mode = Mode.COOL, fanStep = 9, temperature = 44, hours = 0, minutes = minutes) }
        val slots = listOf(
            StoredProgram("Partner", listOf(step(30))),
            StoredProgram("Nap", listOf(step(20), step(25))),
            StoredProgram("Guest", listOf(step(50))),
        )
        val h = Harness(this, slots = slots)
        val side = h.left
        h.unit(0).tick()
        side.startTonight()

        assertTrue(side.needsSlotChoice.value)
        assertNull(side.run.value)
        assertNull(side.slot.value)
        assertEquals(slots, side.deviceSlots.value)
        // Everything on the BedJet is copied into the shared schedules before a slot can be reused.
        assertEquals(
            Schedule.templates.map { it.name } + listOf("Partner", "Nap", "Guest"),
            h.model.schedules.value.map { it.name },
        )
        assertEquals(listOf(20, 25), h.model.schedules.value.first { it.name == "Nap" }.phases.map { it.minutes })
        assertEquals(h.model.schedules.value, assertIs<Saved>(h.savedFile("lull.json")).schedules)

        side.dismissSlotChoice()
        assertFalse(side.needsSlotChoice.value)
        side.startTonight(replacingSlot = 1)
        assertEquals(1, side.slot.value)
        assertEquals("Cool Drift", h.unit(0).slots[1]?.name)
        assertEquals("Partner", h.unit(0).slots[0]?.name)
        assertNotNull(side.run.value)
        // Already copied, so not imported twice.
        assertEquals(7, h.model.schedules.value.size)
    }

    @Test
    fun remoteChangesSettleIntoHistory() = test {
        val storage = InMemoryStorage()
        val json = FakeJson()
        // History already knows the BedJet was off, so the first report is not news.
        storage.writeFile(
            "side0.history-demo.json",
            json.put(SavedHistory(entries = emptyList(), lastSeen = Seen(Mode.STANDBY, 60, 40, 0, 0, at = START - DAY))),
        )
        val h = Harness(this, storage = storage, json = json).also { it.unit(0).tick() }
        val side = h.left
        assertTrue(side.history.value.isEmpty(), "the BedJet is as it was left")
        h.now += 60_000

        side.setMode(Mode.HEAT)
        // Heat started from the Remote runs for as long as the BedJet allows.
        assertEquals(240 * 60, side.status.value?.remainingSeconds)
        assertNull(side.errorMessage.value)
        assertTrue(side.history.value.isEmpty(), "nothing is recorded until the setting has held for 3 s")

        delay(3_300)
        val air = side.history.value.single()
        assertEquals(HistoryEntry.Event.Air(mode = Mode.HEAT, temperature = 60, fanPercent = 40), air.event)
        assertFalse(air.noticedLater)
        // The refresh after connecting has run meanwhile.
        assertEquals("BEDJET-DEMO-L", side.deviceName.value)
        assertEquals("V1.20 (demo)", side.firmware.value)

        h.now += 10 * MINUTE
        // Waits out the unit's report of a running mode (600 ms + 20 × 200 ms), which never comes for Off;
        // the change settles meanwhile.
        side.setMode(Mode.STANDBY)
        delay(100)
        assertEquals(
            listOf(
                HistoryEntry.Event.Air(mode = Mode.HEAT, temperature = 60, fanPercent = 40),
                HistoryEntry.Event.Off(timerRanOut = false),
            ),
            side.history.value.map { it.event },
        )
        val saved = assertIs<SavedHistory>(h.savedFile("side0.history-demo.json"))
        assertEquals(2, saved.entries.size)
        assertEquals(Mode.STANDBY, saved.lastSeen?.mode)
        assertNull(h.savedFile("side0.history.json"), "demo history never mixes with real history")
        assertTrue(h.right.history.value.isEmpty(), "the other side's BedJet did nothing")
    }

    @Test
    fun controlsWaitForTheBedJetThenSayItCouldNotBeReached() = test {
        val h = Harness(this)
        // The simulated BedJet never finishes connecting without a tick. Eight seconds pass in well under one.
        val pump = launch {
            while (true) {
                delay(10)
                h.now += 500
            }
        }
        h.left.setMode(Mode.HEAT)
        pump.cancel()
        assertEquals(BedJetException.NotConnected.message, h.left.errorMessage.value)
        assertFalse(h.left.isBusy.value)
        assertTrue(h.left.canControl)
        assertFalse(h.left.isConnected)
    }

    @Test
    fun aRunThatEndedWhileClosedIsRecordedAsFinished() = test {
        val storage = InMemoryStorage()
        val json = FakeJson()
        val coolDrift = Schedule.templates[0]
        val startedAt = START - 10 * 60 * MINUTE
        storage.writeFile("lull.json", json.put(Saved(Schedule.templates)))
        storage.writeFile("side1.side.json", json.put(SavedSide(Schedule.templates[1].id, ActiveRun(coolDrift, startedAt), 2)))
        val h = Harness(this, storage = storage, json = json)
        val side = h.right

        assertNull(side.run.value)
        assertEquals(2, side.slot.value)
        assertEquals("Warm Welcome", side.tonight?.name)
        val finished = side.history.value.single()
        assertEquals(HistoryEntry.Event.ScheduleEnded(name = "Cool Drift", ending = HistoryEntry.Ending.Finished), finished.event)
        assertEquals(startedAt + 465 * MINUTE, finished.date)
        assertTrue(h.left.history.value.isEmpty())
        assertEquals("Cool Drift", h.left.tonight?.name)
    }

    @Test
    fun demoModeKeepsItsOwnHistory() = test {
        val storage = InMemoryStorage()
        val json = FakeJson()
        val real = HistoryEntry(date = START - 3_600_000, event = HistoryEntry.Event.Off(timerRanOut = true))
        val pretend = HistoryEntry(date = START - 7_200_000, event = HistoryEntry.Event.Air(Mode.COOL, 44, 40))
        storage.writeFile("side0.history.json", json.put(SavedHistory(listOf(real))))
        storage.writeFile("side0.history-demo.json", json.put(SavedHistory(listOf(pretend))))
        val h = Harness(this, demo = false, storage = storage, json = json)

        assertEquals(listOf(real), h.left.history.value)
        // Without a BedJet assigned, a real side isn't set up; a simulated one always is.
        assertFalse(h.left.isSetUp)
        h.model.setDemoMode(true)
        assertEquals(listOf(pretend), h.left.history.value)
        assertTrue(h.left.isSetUp)
        assertEquals(true, storage.getBoolean("demoMode"))
        assertEquals(4, h.links.size, "turning on demo mode reconnects both sides")
        assertIs<DemoLink>(h.links.last().second)
        h.model.setDemoMode(false)
        assertEquals(listOf(real), h.left.history.value)
        assertTrue(h.right.history.value.isEmpty())
        assertFalse(h.left.isSetUp)
    }

    @Test
    fun historyKeepsTwoMonths() = test {
        val storage = InMemoryStorage()
        val json = FakeJson()
        val old = HistoryEntry(date = START - 61 * DAY, event = HistoryEntry.Event.Off(timerRanOut = true))
        val recent = HistoryEntry(date = START - 59 * DAY, event = HistoryEntry.Event.Off(timerRanOut = true))
        storage.writeFile("side0.history-demo.json", json.put(SavedHistory(listOf(old, recent))))
        val h = Harness(this, storage = storage, json = json)
        h.unit(0).tick()
        h.left.endNight()
        assertEquals(listOf(recent.id), h.left.history.value.dropLast(1).map { it.id })
        assertEquals(HistoryEntry.Event.ScheduleEnded(null, HistoryEntry.Ending.ByYou), h.left.history.value.last().event)
    }

    @Test
    fun settingsAreRemembered() = test {
        val h = Harness(this)
        h.model.setUseFahrenheit(true)
        assertTrue(h.model.useFahrenheit.value)
        assertEquals(true, h.storage.getBoolean("useFahrenheit"))
        assertEquals("72°", h.model.degrees(44))
        // Shared by both sides.
        assertEquals("72°", h.right.degrees(44))
        h.model.setUseFahrenheit(false)
        assertEquals("22°", h.model.degrees(44))
        assertEquals("22°", h.left.degrees(44))

        // Each side remembers the kind of BedJet it has.
        h.storage.putString("side1.generation", "v2")
        val v2 = Harness(this, storage = h.storage, json = h.json)
        assertEquals(Generation.V2, v2.right.generation.value)
        assertEquals(listOf(Mode.COOL, Mode.HEAT, Mode.TURBO), v2.right.remoteModes)
        assertEquals(listOf(Mode.COOL, Mode.HEAT, Mode.TURBO, Mode.WAIT), v2.right.phaseModes)
        assertFalse(v2.right.adjustsTemperature(Mode.COOL))
        assertEquals(Generation.V3, v2.left.generation.value)
        assertTrue(v2.left.adjustsTemperature(Mode.COOL))
    }

    @Test
    fun schedulesAreSavedAndDeleted() = test {
        val h = Harness(this)
        val mine = Schedule(name = "Mine", phases = listOf(Phase.of(Mode.COOL, minutes = 60)))
        h.model.save(mine)
        assertEquals("Mine", h.model.schedules.value.last().name)
        h.model.save(mine.copy(name = "Renamed"))
        assertEquals(5, h.model.schedules.value.size)
        assertEquals("Renamed", h.model.schedules.value.last().name)

        h.left.setTonight(mine.id)
        h.right.setTonight(mine.id)
        assertEquals("Renamed", h.left.tonight?.name)
        assertEquals("Renamed", h.right.tonight?.name)
        h.model.delete(mine)
        // Deleting tonight's schedule falls back to the first, on every side that had it.
        assertEquals(Schedule.templates[0].id, h.left.tonightId.value)
        assertEquals(Schedule.templates[0].id, h.right.tonightId.value)
        val saved = assertIs<Saved>(h.savedFile("lull.json"))
        assertEquals(Schedule.templates, saved.schedules)
        // The library alone: tonight's schedule is each side's.
        assertNull(saved.tonightId)
        assertEquals(Schedule.templates[0].id, assertIs<SavedSide>(h.savedFile("side1.side.json")).tonightId)

        // Another side's choice survives a delete that isn't its schedule.
        h.right.setTonight(Schedule.templates[2].id)
        h.model.delete(Schedule.templates[3])
        assertEquals(Schedule.templates[2].id, h.right.tonightId.value)
    }

    // Two sides

    @Test
    fun twoDemoSidesAreIndependent() = test {
        val h = Harness(this)
        h.unit(0).tick()
        h.unit(1).tick()
        h.left.startTonight()

        assertNotNull(h.left.run.value)
        assertNull(h.right.run.value)
        assertEquals(0, h.left.slot.value)
        assertNull(h.right.slot.value)
        assertEquals("Cool Drift", h.unit(0).slots[0]?.name)
        assertEquals(listOf<StoredProgram?>(null, null, null), h.unit(1).slots)
        assertEquals(1, h.left.status.value?.extra?.programStep)
        assertEquals(0, h.right.status.value?.extra?.programStep)
        assertEquals(1, h.left.history.value.size)
        assertTrue(h.right.history.value.isEmpty())

        // The Remote on the other side changes only its own BedJet.
        h.right.setLed(false)
        assertEquals(false, h.right.status.value?.extra?.ledEnabled)
        assertEquals(true, h.left.status.value?.extra?.ledEnabled)
        h.left.endNight()
        assertNull(h.left.run.value)
        assertEquals(Mode.STANDBY, h.left.status.value?.mode)
    }

    @Test
    fun selectingASideIsRemembered() = test {
        val h = Harness(this)
        h.model.selectSide(1)
        assertEquals(1, h.model.selectedSide.value)
        assertSame(h.right, h.model.selected.value)
        assertEquals(1, h.storage.getInt("selectedSide"))
        h.model.selectSide(2)
        assertEquals(1, h.model.selectedSide.value, "there are only two sides")

        val relaunched = Harness(this, storage = h.storage, json = h.json)
        assertEquals(1, relaunched.model.selectedSide.value)
        assertSame(relaunched.right, relaunched.model.selected.value)
        relaunched.model.selectSide(0)
        assertSame(relaunched.left, relaunched.model.selected.value)
        assertEquals(0, h.storage.getInt("selectedSide"))
    }

    @Test
    fun sidesCanBeRenamed() = test {
        val h = Harness(this)
        h.right.setLabel(" Window side of the bed")
        assertEquals("Window side", h.right.label.value, "at most 12 characters, without the spaces around them")
        assertEquals("Window side", h.storage.getString("side1.label"))
        assertEquals("Left", h.left.label.value)
        val relaunched = Harness(this, storage = h.storage, json = h.json)
        assertEquals("Window side", relaunched.right.label.value)
        relaunched.right.setLabel("  ")
        assertEquals("Right", relaunched.right.label.value)
        assertNull(h.storage.getString("side1.label"))
    }

    @Test
    fun assigningABedJetBindsTheSideAndConnects() = test {
        val h = Harness(this, demo = false)
        assertFalse(h.left.isSetUp)
        assertNull(h.left.unitName.value)
        assertEquals(LinkState.SEARCHING, h.left.linkState.value)
        h.model.startFinding()
        assertTrue(h.finder.isStarted)

        h.left.assign(FoundUnit(address = A, name = "BEDJET-3B04", rssi = -60))
        assertFalse(h.finder.isStarted, "picking a BedJet stops the finder")
        assertFalse(h.model.isFinding.value)
        assertEquals(A, h.storage.getString("side0.BedJetKit.peripheral"))
        assertEquals("BEDJET-3B04", h.storage.getString("side0.BedJetKit.deviceName"))
        assertEquals("BEDJET-3B04", h.left.unitName.value)
        assertEquals(A, h.left.address)
        assertTrue(h.left.isSetUp)
        assertEquals(LinkState.CONNECTING, h.left.linkState.value)
        h.units.getValue(A).tick()
        assertEquals(LinkState.READY, h.left.linkState.value)
        h.left.startTonight()
        assertEquals(0, h.left.slot.value)

        // Given to the other side, the BedJet moves: this side is left without one.
        h.right.assign(FoundUnit(address = A, name = "BEDJET-3B04", rssi = -60))
        assertNull(h.left.address)
        assertNull(h.left.unitName.value)
        assertFalse(h.left.isSetUp)
        assertNull(h.left.slot.value, "what Lull kept in the BedJet's slot is that BedJet's")
        assertEquals(LinkState.SEARCHING, h.left.linkState.value)
        assertEquals(A, h.right.address)
        assertEquals("BEDJET-3B04", h.right.unitName.value)
        assertNull(h.storage.getString("side0.BedJetKit.peripheral"))
        // The run and history stay with the side they happened on.
        assertNotNull(h.left.run.value)
        assertTrue(h.right.history.value.isEmpty())

        h.right.forgetDevice()
        assertNull(h.right.unitName.value)
        assertNull(h.storage.getString("side1.BedJetKit.deviceName"))
    }

    @Test
    fun swappingSidesSwapsOnlyTheBedJets() = test {
        val h = Harness(this, demo = false)
        h.left.assign(FoundUnit(address = A, name = "BEDJET-A", rssi = -50))
        h.right.assign(FoundUnit(address = B, name = "BEDJET-B", rssi = -70))
        h.units.getValue(A).tick()
        h.left.startTonight()
        h.right.setTonight(Schedule.templates[1].id)
        h.right.setLabel("Window")
        val run = assertNotNull(h.left.run.value)
        val linksBefore = h.links.size

        h.model.swapSides()

        assertEquals(B, h.left.address)
        assertEquals("BEDJET-B", h.left.unitName.value)
        assertEquals(A, h.right.address)
        assertEquals("BEDJET-A", h.right.unitName.value)
        assertEquals(B, h.storage.getString("side0.BedJetKit.peripheral"))
        assertEquals("BEDJET-A", h.storage.getString("side1.BedJetKit.deviceName"))
        assertEquals(linksBefore + 2, h.links.size, "both sides reconnect")
        assertSame(h.units.getValue(B), h.unit(0))
        assertSame(h.units.getValue(A), h.unit(1))
        // Everything else stays with its side.
        assertEquals(listOf("Left", "Window"), h.model.sides.map { it.label.value })
        assertEquals(run, h.left.run.value)
        assertEquals(0, h.left.slot.value)
        assertEquals(Schedule.templates[0].id, h.left.tonightId.value)
        assertEquals(Schedule.templates[1].id, h.right.tonightId.value)
        assertEquals(1, h.left.history.value.size)
        assertTrue(h.right.history.value.isEmpty())
    }

    @Test
    fun demoSidesSwapTheirSimulatedBedJets() = test {
        val h = Harness(this)
        val left = h.unit(0)
        h.right.setLabel("Window")
        h.model.swapSides()
        assertEquals(listOf("BEDJET-DEMO-R", "BEDJET-DEMO-L"), h.model.sides.map { it.unitName.value })
        assertEquals(listOf("Left", "Window"), h.model.sides.map { it.label.value })
        assertEquals(2, h.links.size, "a simulated BedJet is its link, so the sides trade links")
        left.tick()
        assertEquals(LinkState.READY, h.right.linkState.value)
        assertEquals(LinkState.CONNECTING, h.left.linkState.value)
    }

    @Test
    fun startBothSidesStartsEachSidesTonight() = test {
        val h = Harness(this)
        h.unit(0).tick()
        h.unit(1).tick()
        h.right.setTonight(Schedule.templates[1].id)
        h.model.startBothSides()

        assertEquals(ActiveRun(Schedule.templates[0], START), h.left.run.value)
        assertEquals(ActiveRun(Schedule.templates[1], START), h.right.run.value)
        assertEquals("Cool Drift", h.unit(0).slots[0]?.name)
        assertEquals("Warm Welcome", h.unit(1).slots[0]?.name)
        assertNull(h.left.errorMessage.value)
        assertNull(h.right.errorMessage.value)
    }

    @Test
    fun startBothSidesCarriesOnPastASideThatCannotStart() = test {
        val step = ProgramStep(mode = Mode.COOL, fanStep = 9, temperature = 44, hours = 0, minutes = 30)
        val full = List(3) { StoredProgram("Kept $it", listOf(step.copy(minutes = 30 + it))) }
        val h = Harness(this, slots = full)
        h.unit(0).tick()
        h.unit(1).tick()
        h.model.startBothSides()

        // Side 0 asks which slot to reuse; side 1 starts anyway.
        assertTrue(h.left.needsSlotChoice.value)
        assertNull(h.left.run.value)
        assertFalse(h.right.needsSlotChoice.value)
        assertEquals(ActiveRun(Schedule.templates[0], START), h.right.run.value)
    }

    @Test
    fun theOneBedJetBecomesSide0s() = test {
        val storage = InMemoryStorage()
        val json = FakeJson()
        val mine = Schedule(name = "Mine", phases = listOf(Phase.of(Mode.COOL, minutes = 60)))
        val run = ActiveRun(Schedule.templates[0], START - 60 * MINUTE)
        val real = HistoryEntry(date = START - 3_600_000, event = HistoryEntry.Event.Off(timerRanOut = true))
        val pretend = HistoryEntry(date = START - 7_200_000, event = HistoryEntry.Event.Air(Mode.COOL, 44, 40))
        // What Lull kept before there were two sides.
        storage.putString("BedJetKit.peripheral", A)
        storage.putString("BedJetKit.deviceName", "BEDJET-3B04")
        storage.putString("generation", "v2")
        storage.writeFile("lull.json", json.put(Saved(Schedule.templates + mine, Schedule.templates[1].id, run, 2)))
        storage.writeFile("history.json", json.put(SavedHistory(listOf(real))))
        storage.writeFile("history-demo.json", json.put(SavedHistory(listOf(pretend))))
        val h = Harness(this, demo = false, storage = storage, json = json)

        assertEquals(A, h.left.address)
        assertEquals("BEDJET-3B04", h.left.unitName.value)
        assertTrue(h.left.isSetUp)
        assertEquals(Generation.V2, h.left.generation.value)
        assertEquals("Warm Welcome", h.left.tonight?.name)
        assertEquals(run, h.left.run.value)
        assertEquals(2, h.left.slot.value)
        assertEquals(listOf(real), h.left.history.value)
        // The schedules are both sides'.
        assertEquals(Schedule.templates + mine, h.model.schedules.value)
        // The other side starts afresh.
        assertNull(h.right.address)
        assertFalse(h.right.isSetUp)
        assertEquals("Cool Drift", h.right.tonight?.name)
        assertNull(h.right.run.value)
        assertNull(h.right.slot.value)
        assertTrue(h.right.history.value.isEmpty())
        assertEquals(Generation.V3, h.right.generation.value)
        h.model.setDemoMode(true)
        assertEquals(listOf(pretend), h.left.history.value)
        h.model.setDemoMode(false)

        // The old keys and files stay where they were, harmlessly.
        assertEquals(A, storage.getString("BedJetKit.peripheral"))
        assertNotNull(storage.readFile("history.json"))
        // Moved once: a BedJet forgotten afterwards doesn't come back from them.
        h.left.forgetDevice()
        val relaunched = Harness(this, demo = false, storage = storage, json = json)
        assertNull(relaunched.left.address)
        assertFalse(relaunched.left.isSetUp)
        assertEquals("Warm Welcome", relaunched.left.tonight?.name)
    }

    @Test
    fun prefixedStorageKeepsEachSidesOwn() {
        val base = InMemoryStorage()
        val side = PrefixedStorage(base, "side1.")
        side.putString("BedJetKit.peripheral", A)
        side.putBoolean("flag", true)
        side.putInt("count", 3)
        side.writeFile("side.json", "{}")
        assertEquals(A, base.getString("side1.BedJetKit.peripheral"))
        assertEquals(true, base.getBoolean("side1.flag"))
        assertEquals(3, base.getInt("side1.count"))
        assertEquals("{}", base.readFile("side1.side.json"))
        assertNull(base.getString("BedJetKit.peripheral"))
        assertNull(base.readFile("side.json"))
        assertEquals(A, side.getString("BedJetKit.peripheral"))
        assertEquals(true, side.getBoolean("flag"))
        assertEquals(3, side.getInt("count"))
        assertEquals("{}", side.readFile("side.json"))
        base.putString("label", "unprefixed")
        assertNull(side.getString("label"))
        side.putString("BedJetKit.peripheral", null)
        assertNull(base.getString("side1.BedJetKit.peripheral"))
    }

    @Test
    fun thePickerListsWhatTheFinderHearsAndTheConnectedSides() = test {
        val h = Harness(this)
        val nearby = listOf(FoundUnit(A, "BEDJET-3B04", -60), FoundUnit(B, "BEDJET-3B04", -75))
        assertTrue(h.model.foundUnits.value.isEmpty())
        h.model.startFinding()
        assertTrue(h.finder.isStarted)
        assertTrue(h.model.isFinding.value)
        h.finder.found.value = nearby
        delay(20)
        assertEquals(nearby, h.model.foundUnits.value)

        // A connected BedJet can't be heard, so its side's link lists it.
        h.unit(1).tick()
        delay(20)
        val connected = FoundUnit("BEDJET-DEMO-R", "BEDJET-DEMO-R", rssi = null, connectedToSide = 1)
        assertEquals(listOf(connected) + nearby, h.model.foundUnits.value)
        h.finder.found.value = listOf(nearby[0].copy(rssi = -40))
        delay(20)
        assertEquals(listOf(connected, nearby[0].copy(rssi = -40)), h.model.foundUnits.value)

        // Leaving the screen pauses the scan, and coming back resumes it while the picker is open.
        h.model.appMovedToBackground()
        assertFalse(h.finder.isStarted)
        h.model.appBecameActive()
        assertTrue(h.finder.isStarted)
        h.model.stopFinding()
        assertFalse(h.finder.isStarted)
        assertFalse(h.model.isFinding.value)
        h.model.appBecameActive()
        assertFalse(h.finder.isStarted)

        // A simulated side just records what it was given.
        h.model.startFinding()
        h.right.assign(nearby[1])
        assertFalse(h.finder.isStarted)
        assertEquals("BEDJET-3B04", h.right.unitName.value)
        assertEquals(B, h.right.address)
        // Choosing the other simulated side's BedJet trades them, so neither side is left without one.
        h.left.assign(nearby[1])
        assertEquals(listOf("BEDJET-3B04", "BEDJET-DEMO-L"), h.model.sides.map { it.unitName.value })
        assertEquals(listOf(B, "BEDJET-DEMO-L"), h.model.sides.map { it.address })
    }

    private companion object {
        const val A = "AA:BB:CC:DD:EE:01"
        const val B = "AA:BB:CC:DD:EE:02"
        /** 2026-10-20 23:00 UTC. */
        const val START = 1_792_537_200_000L
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
    }
}
