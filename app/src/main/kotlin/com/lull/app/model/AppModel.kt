package com.lull.app.model

import com.lull.bedjetkit.BedJetException
import com.lull.bedjetkit.BedJetLink
import com.lull.bedjetkit.Codec
import com.lull.bedjetkit.ConnectionTiming
import com.lull.bedjetkit.DemoLink
import com.lull.bedjetkit.DeviceStatus
import com.lull.bedjetkit.Generation
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.Mode
import com.lull.bedjetkit.StoredProgram
import com.lull.bedjetkit.acknowledgeNotification
import com.lull.bedjetkit.deviceName
import com.lull.bedjetkit.firmwareVersion
import com.lull.bedjetkit.setFan
import com.lull.bedjetkit.setLed
import com.lull.bedjetkit.setMode
import com.lull.bedjetkit.setMuted
import com.lull.bedjetkit.setRuntime
import com.lull.bedjetkit.setTemperature
import com.lull.bedjetkit.startProgram
import com.lull.bedjetkit.store
import com.lull.bedjetkit.storedPrograms
import com.lull.bedjetkit.syncClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A schedule that is running on the BedJet right now. */
@Serializable
data class ActiveRun(
    val schedule: Schedule,
    /** Epoch milliseconds. */
    val startedAt: Long,
) {
    val endsAt: Long get() = startedAt + schedule.totalMinutes * MINUTE_MS

    fun progress(at: Long): Double =
        minOf(maxOf((at - startedAt).toDouble() / maxOf(endsAt - startedAt, SECOND_MS), 0.0), 1.0)

    fun phase(at: Long): CurrentPhase? {
        var elapsed = ((at - startedAt) / MINUTE_MS).toInt()
        for ((index, phase) in schedule.phases.withIndex()) {
            if (elapsed < phase.minutes) return CurrentPhase(phase, index, phase.minutes - elapsed)
            elapsed -= phase.minutes
        }
        return null
    }
}

/** Where a running schedule is: the Swift `(phase:, index:, minutesLeft:)` tuple. */
data class CurrentPhase(val phase: Phase, val index: Int, val minutesLeft: Int)

/**
 * What the Bluetooth transport offers beyond [BedJetLink], which a simulated BedJet doesn't have.
 * `ble.BleLink` implements it; the model can't name that class because it compiles without Android.
 */
interface HardwareLink {
    /** Return false to postpone a hand-over, for example while a schedule is being saved. */
    var canYield: () -> Boolean
    /** How long the most recent connection took, for `logConnections`. */
    val lastConnection: ConnectionTiming?
    /** The address of the BedJet this link is bound to; null while it has none, when it stays `SEARCHING`. */
    val address: String?
    /**
     * Binds the link to one BedJet (null: none) and remembers it with the name it advertised, which identifies
     * it in a hand-over until the unit is connected and tells its own. Only that address is ever connected to.
     */
    fun bind(address: String?, name: String?)
    /** Forgets the bound unit, its address and its name: `bind(null, null)`. */
    fun forget()
}

/** A BedJet the picker can offer: one the finder hears, or one connected to a side (which can't be heard). */
data class FoundUnit(val address: String, val name: String, val rssi: Int?, val connectedToSide: Int? = null)

/** Scans for BedJets for the picker. Android implementation in `ble/`; tests use a fake. */
interface UnitFinder {
    /** De-duplicated by address, newest RSSI. */
    val found: StateFlow<List<FoundUnit>>
    fun start()
    fun stop()
}

/**
 * What both sides share.
 *
 * @param addSchedule Adds a schedule to the library, which [AppModel] owns: a side keeps copies of programs it
 *   finds on its BedJet.
 * @param claim A side is being given a BedJet: [AppModel] stops the picker's scan and, if the other side had that
 *   BedJet, takes it off the other side.
 */
class SharedState(
    val schedules: StateFlow<List<Schedule>>,
    val useFahrenheit: StateFlow<Boolean>,
    val demoMode: StateFlow<Boolean>,
    internal val addSchedule: (Schedule) -> Unit = {},
    internal val claim: (side: Int, unit: FoundUnit) -> Unit = { _, _ -> },
)

/**
 * One half of the bed: everything the screens show and do for one BedJet. [AppModel] makes the two sides and keeps
 * what they share, the schedule library and the settings, in [SharedState].
 *
 * Single-threaded: create it, call it and collect it on the main thread ([scope] is `MainScope()` in the app). Link
 * callbacks are expected on that same thread, which is what `BleLink` (posts to the main looper) and `DemoLink`
 * (calls back from inside `send()`/`tick()`) do.
 *
 * @param index 0 or 1.
 * @param storage This side's own view of the storage, already prefixed with `side<index>.` by [AppModel].
 */
class SideModel internal constructor(
    val index: Int,
    private val storage: Storage,
    private val scope: CoroutineScope,
    private val linkFactory: (demo: Boolean, index: Int) -> BedJetLink,
    private val options: LaunchOptions,
    private val json: JsonCodec,
    private val clock: () -> Long,
    private val shared: SharedState,
) {
    private val _linkState = MutableStateFlow(LinkState.SEARCHING)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware.asStateFlow()

    /** The kind of BedJet last connected, remembered so schedules can be edited for it while it is away. */
    private val _generation = MutableStateFlow(Generation.V3)
    val generation: StateFlow<Generation> = _generation.asStateFlow()

    /** What is saved in the BedJet's three program slots. */
    private val _deviceSlots = MutableStateFlow<List<StoredProgram?>>(emptyList())
    val deviceSlots: StateFlow<List<StoredProgram?>> = _deviceSlots.asStateFlow()

    private val _tonightId = MutableStateFlow<String?>(null)
    val tonightId: StateFlow<String?> = _tonightId.asStateFlow()

    private val _run = MutableStateFlow<ActiveRun?>(null)
    val run: StateFlow<ActiveRun?> = _run.asStateFlow()

    /** The BedJet slot this app keeps tonight's schedule in. */
    private val _slot = MutableStateFlow<Int?>(null)
    val slot: StateFlow<Int?> = _slot.asStateFlow()

    /** What the BedJet did, oldest first, so the morning after can be checked against how the night felt. */
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /** Set once Lull has looked for the BedJet for a while without reaching it, to suggest what to check. */
    private val _isSlowToFind = MutableStateFlow(false)
    val isSlowToFind: StateFlow<Boolean> = _isSlowToFind.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Set when every slot on the BedJet is taken and the user has to pick one to reuse. */
    private val _needsSlotChoice = MutableStateFlow(false)
    val needsSlotChoice: StateFlow<Boolean> = _needsSlotChoice.asStateFlow()

    /** What this side is called on the switcher: "Left" or "Right" until it is renamed. */
    private val _label = MutableStateFlow(defaultLabel)
    val label: StateFlow<String> = _label.asStateFlow()

    /** The bound BedJet's name, as it advertised it or, once connected, told it; null while this side has none. */
    private val _unitName = MutableStateFlow<String?>(null)
    val unitName: StateFlow<String?> = _unitName.asStateFlow()

    private var link: BedJetLink? = null
    /**
     * What a simulated side was assigned. A simulated BedJet has no address to bind, so the side records the unit
     * itself: at first its own simulated BedJet, whose name stands in for an address.
     */
    private var demoBinding: Binding? = null
    private var lastRefresh = DISTANT_PAST
    private var keepsLongestRuntime = true
    private var isLoaded = false
    private var searchTimer: Job? = null
    /** What the history last recorded the BedJet doing, kept across launches to notice what changed while Lull was closed. */
    private var lastSeen: Seen? = null
    /** The latest report, waiting to settle before it is compared with `lastSeen`. */
    private var observed: Seen? = null
    private var settling: Job? = null
    private var connectedAt = DISTANT_PAST
    private var lastCommandAt = DISTANT_PAST

    /**
     * Launching with `demoElapsedMinutes` 200 makes a night started in demo mode appear that far along. [AppModel] has
     * already read the setting, which is the app's rather than a side's, into [options].
     */
    private val demoElapsedMinutes: Int = options.demoElapsedMinutes

    /** Whether this side has a BedJet. The demo's simulated ones are always there. */
    val isSetUp: Boolean get() = shared.demoMode.value || _unitName.value != null

    /**
     * The bound BedJet's address, so the picker can tell which of the BedJets it lists is this side's. Changes with
     * [unitName]. A simulated BedJet's is its name.
     */
    val address: String?
        get() = when (val link = link) {
            is HardwareLink -> link.address
            else -> demoBinding?.address
        }

    /**
     * This side's BedJet while it is connected. A connected BedJet stops advertising, so the picker can't see it;
     * the link can.
     */
    internal val connectedUnit: FoundUnit?
        get() {
            if (!isConnected) return null
            val address = address ?: return null
            return FoundUnit(address = address, name = _unitName.value ?: address, rssi = null, connectedToSide = index)
        }

    val tonight: Schedule?
        get() = shared.schedules.value.let { schedules -> schedules.firstOrNull { it.id == _tonightId.value } ?: schedules.firstOrNull() }
    val isConnected: Boolean get() = _linkState.value == LinkState.READY

    /**
     * Controls stay live while connecting or while another phone has the BedJet: what is tapped
     * is sent the moment the connection is up.
     */
    val canControl: Boolean
        get() = _linkState.value != LinkState.BLUETOOTH_OFF && _linkState.value != LinkState.UNAUTHORIZED

    /** A schedule is running on the BedJet that this phone didn't start, for example from a partner's phone. */
    val runningElsewhereStep: Int?
        get() {
            if (_run.value != null) return null
            val step = _status.value?.extra?.programStep ?: return null
            return if (step > 0) step else null
        }

    val nights: List<Night> get() = Night.group(_history.value)

    init {
        _label.value = storage.getString(LABEL_KEY)?.takeIf { it.isNotBlank() } ?: defaultLabel
        _generation.value = storage.getString("generation")?.let(::generationNamed) ?: Generation.V3
        loadHistory()
        load()
        reconnect()
    }

    // region Settings

    fun setTonight(id: String?) {
        _tonightId.value = id
        save()
    }

    /** Renames this side, up to 12 characters. A blank name is "Left" or "Right" again. */
    fun setLabel(label: String) {
        val trimmed = label.trim()
        var clipped = trimmed.take(MAX_LABEL_LENGTH)
        // Never half an emoji.
        if (clipped.length < trimmed.length && clipped.last().isHighSurrogate()) clipped = clipped.dropLast(1)
        clipped = clipped.trimEnd()
        val named = clipped.isNotEmpty()
        _label.value = if (named) clipped else defaultLabel
        storage.putString(LABEL_KEY, if (named) clipped else null)
    }

    /** Demo mode was turned on or off ([SharedState.demoMode]): the other history, and a link of the other kind. */
    internal fun demoModeChanged() {
        demoBinding = null
        loadHistory()
        reconnect()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun dismissSlotChoice() {
        _needsSlotChoice.value = false
    }

    private fun setGeneration(value: Generation) {
        _generation.value = value
        storage.putString("generation", value.name.lowercase(Locale.ROOT))
    }

    private fun setRun(value: ActiveRun?) {
        _run.value = value
        save()
    }

    private fun setSlot(value: Int?) {
        _slot.value = value
        save()
    }

    // endregion

    // region Connection

    /** A fresh link to the bound BedJet, which a real link remembers itself (see [HardwareLink.bind]). */
    internal fun reconnect() {
        link?.stop()
        val link = linkFactory(shared.demoMode.value, index)
        // The simulated BedJets come assigned, one to each side.
        if (link is DemoLink && demoBinding == null) demoBinding = Binding(address = link.name, name = link.name)
        attach(link)
    }

    /** Makes [link] this side's and starts it, with nothing of the previous BedJet left on screen. */
    private fun attach(link: BedJetLink) {
        _status.value = null
        _deviceName.value = null
        _firmware.value = null
        _deviceSlots.value = emptyList()
        // A link that has been replaced may still report, for example a Bluetooth disconnect arriving
        // after demo mode was turned on; only the current one speaks for the BedJet.
        link.onStateChange = { state -> if (this.link === link) linkStateChanged(state) }
        link.onStatus = { status -> if (this.link === link) statusChanged(status) }
        // Never hand the BedJet to another phone halfway through saving a schedule.
        (link as? HardwareLink)?.canYield = { !_isBusy.value }
        this.link = link
        refreshUnitName()
        _linkState.value = link.state
        lastRefresh = DISTANT_PAST
        link.start()
        restartSearchTimer()
    }

    /** Lets go of the BedJet, before this side is given another one. */
    internal fun stop() {
        link?.stop()
    }

    /**
     * A real link remembers the name the unit advertised when it was assigned, and replaces it with the one the unit
     * tells it once connected. Without either (a V2 that never answered), the BedJet is called by its address.
     */
    private fun refreshUnitName() {
        _unitName.value = when (val link = link) {
            is HardwareLink -> link.address?.let { address -> storage.getString(NAME_KEY) ?: address }
            else -> demoBinding?.name
        }
    }

    /**
     * Binds this side to a BedJet found by the picker and (re)connects. A BedJet the other side had moves to this
     * one. What a different BedJet holds in its slots is unknown, so the slot Lull used is forgotten, as when a
     * BedJet is forgotten. A simulated side has nothing to bind or connect: choosing just records the name. (In demo
     * mode the picker lists only the two simulated BedJets, and choosing the other side's swaps them, see `AppModel`.)
     */
    fun assign(unit: FoundUnit) {
        // First, so the picker stops and the other side lets go of the BedJet before this one reaches for it.
        shared.claim(index, unit)
        val link = link
        if (link !is HardwareLink) {
            demoBinding = Binding(address = unit.address, name = unit.name)
            refreshUnitName()
            return
        }
        if (link.address == unit.address && isConnected) return
        if (link.address != unit.address) setSlot(null)
        // Stopped first, so binding it only remembers the unit rather than starting a connection. The new
        // link reads it back (see `HardwareLink.bind`) and starts with nothing of the previous unit on screen.
        link.stop()
        link.bind(unit.address, unit.name)
        reconnect()
    }

    /** The other side was given this side's BedJet, which this side forgets. (Simulated sides swap instead.) */
    internal fun release() {
        forgetDevice()
    }

    /**
     * Gives this side's BedJet to [other] and takes theirs. Only the bindings move: label, tonight's schedule, slot,
     * run and history stay with their side. Both let go before either reaches for the other's BedJet.
     *
     * A simulated BedJet has no address to bind: the link is the unit. So simulated sides trade links, with what
     * each unit holds and is doing, and the names they recorded, and connect again.
     */
    internal fun swap(other: SideModel) {
        val mine = link ?: return
        val theirs = other.link ?: return
        if (mine is HardwareLink && theirs is HardwareLink) {
            stop()
            other.stop()
            val myAddress = mine.address
            val myName = storage.getString(NAME_KEY)
            mine.bind(theirs.address, other.storage.getString(NAME_KEY))
            theirs.bind(myAddress, myName)
            reconnect()
            other.reconnect()
        } else if (mine !is HardwareLink && theirs !is HardwareLink) {
            stop()
            other.stop()
            demoBinding = other.demoBinding.also { other.demoBinding = demoBinding }
            attach(theirs)
            other.attach(mine)
        }
    }

    private fun restartSearchTimer() {
        searchTimer?.cancel()
        searchTimer = null
        _isSlowToFind.value = false
        timeSearch()
    }

    /**
     * Counts how long Lull has been looking, across searching and connecting, but not while
     * another phone has the BedJet or Bluetooth is unavailable, which already say what is wrong.
     */
    private fun timeSearch() {
        val state = _linkState.value
        val link = link
        // A side bound to no BedJet isn't looking for one: finding BedJets is the picker's job.
        val unbound = link is HardwareLink && link.address == null
        if (shared.demoMode.value || unbound || !(state == LinkState.SEARCHING || state == LinkState.CONNECTING)) {
            searchTimer?.cancel()
            searchTimer = null
            _isSlowToFind.value = false
            return
        }
        if (searchTimer != null) return
        searchTimer = scope.launch {
            delay(SEARCH_PATIENCE_MS)
            _isSlowToFind.value = true
        }
    }

    /**
     * The BedJet talks to one phone at a time, so it is released whenever Lull leaves the screen.
     * Holding on to it would make coming back instant, but a phone that is off screen cannot be
     * relied on to hear another phone asking for the BedJet.
     */
    internal fun appMovedToBackground() {
        link?.stop()
        // A change still settling would otherwise wait until Lull is opened again.
        if (settling != null) commitObserved()
        saveHistory()
    }

    internal fun appBecameActive() {
        link?.start()
        restartSearchTimer()
    }

    fun forgetDevice() {
        val link = link
        setSlot(null)
        if (link !is HardwareLink) return
        link.forget()
        // A link with nothing to connect to, and nothing of the forgotten BedJet left on screen.
        reconnect()
    }

    private fun linkStateChanged(state: LinkState) {
        _linkState.value = state
        timeSearch()
        val link = link
        if (state != LinkState.READY || link == null) return
        connectedAt = clock()
        setGeneration(link.generation)
        refreshUnitName()
        logConnection()
        // Phones take turns with the BedJet, so reconnecting is routine; what is saved on it
        // only needs re-reading now and then.
        if (clock() - lastRefresh <= REFRESH_INTERVAL_MS) return
        scope.launch {
            // Anything the person tapped while connecting goes first.
            delay(1_200)
            while (_isBusy.value) delay(200)
            if (link.state != LinkState.READY) return@launch
            try {
                link.syncClock(LocalTime.now())
                _deviceName.value = link.deviceName()
                // A V2 tells its name in answer to that question, and the link remembers it.
                refreshUnitName()
                _firmware.value = link.firmwareVersion()
                refreshSlots()
                lastRefresh = clock()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Status keeps streaming without these; they are retried on the next connection.
            }
        }
    }

    private fun statusChanged(status: DeviceStatus) {
        _status.value = status
        observe(status)
        val run = _run.value ?: return
        val now = clock()
        val settled = now - run.startedAt > 15_000
        val programStopped = status.mode == Mode.STANDBY && status.extra?.programStep == 0
        if (!(now >= run.endsAt || (settled && programStopped))) return
        setRun(null)
        // Lull lets go of the BedJet overnight, so it only learns about an early stop on the next connection.
        // Nothing on this phone asked for it, so say so and keep the unit's reason code for diagnosis.
        val minutesEarly = ((run.endsAt - now) / MINUTE_MS).toInt()
        if (_isBusy.value) return
        if (minutesEarly > 2) {
            record(
                HistoryEntry.Event.ScheduleEnded(name = run.schedule.name, ending = HistoryEntry.Ending.Early(minutesEarly)),
                noticedLater = isJustConnected,
            )
        } else {
            record(
                HistoryEntry.Event.ScheduleEnded(name = run.schedule.name, ending = HistoryEntry.Ending.Finished),
                at = minOf(run.endsAt, now),
            )
        }
        if (minutesEarly > 2) {
            _errorMessage.value = "Your BedJet turned off ${Format.duration(minutesEarly)} before tonight's schedule ended " +
                "(BedJet reason ${status.lastShutdownReason})."
        }
    }

    private suspend fun refreshSlots() {
        val link = link ?: return
        val slots = link.storedPrograms()
        _deviceSlots.value = slots
        // Keep a copy of programs made elsewhere so reusing a slot never loses them.
        for ((index, program) in slots.withIndex()) {
            if (index == _slot.value || program == null) continue
            val imported = Schedule.importing(program)
            val steps = imported.phases.map { it.step }
            if (shared.schedules.value.none { schedule -> schedule.phases.map { it.step } == steps }) {
                shared.addSchedule(imported)
            }
        }
    }

    // endregion

    // region Tonight

    suspend fun startTonight() {
        val schedule = tonight ?: return
        val link = link ?: return
        perform {
            // A V2 holds just the one sequence, which Lull always replaces.
            val slot: Int
            if (link.generation == Generation.V2) {
                slot = 0
            } else {
                if (_deviceSlots.value.isEmpty()) refreshSlots()
                val free = _slot.value ?: _deviceSlots.value.indexOf(null).takeIf { it >= 0 }
                if (free == null) {
                    _needsSlotChoice.value = true
                    return@perform
                }
                slot = free
                setSlot(slot)
            }
            link.store(schedule.program, slot, LocalTime.now())
            link.startProgram(slot, LocalTime.now())
            confirmStarted()
            setRun(ActiveRun(schedule = schedule, startedAt = clock()))
            record(HistoryEntry.Event.ScheduleStarted(name = schedule.name, phases = schedule.phases))
            if (link is DemoLink && demoElapsedMinutes > 0) {
                link.skipAhead(demoElapsedMinutes)
                setRun(_run.value?.copy(startedAt = clock() - demoElapsedMinutes * MINUTE_MS))
            }
            try {
                refreshSlots()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The schedule is running; the slot list catches up on the next refresh.
            }
        }
    }

    /** A BedJet that can't run a schedule stays off and raises a notification instead of starting it. */
    private suspend fun confirmStarted() {
        val link = link ?: return
        for (attempt in 0 until 20) {
            delay(200)
            val extra = _status.value?.extra ?: continue
            if (extra.programStep > 0) return
            val refusal: BedJetException? = when (extra.notification) {
                Codec.Notification.PROGRAM_NEEDS_CLOCK -> BedJetException.ClockNotSet
                Codec.Notification.PROGRAM_TOO_LONG -> BedJetException.ProgramTooLong
                else -> null
            }
            if (refusal != null) {
                try {
                    link.acknowledgeNotification()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The refusal is what matters; the unit repeats the notification until it is cleared.
                }
                throw refusal
            }
        }
    }

    suspend fun startTonight(replacingSlot: Int) {
        setSlot(replacingSlot)
        startTonight()
    }

    suspend fun endNight() {
        val link = link ?: return
        val name = _run.value?.schedule?.name
        perform {
            link.setMode(Mode.STANDBY)
            setRun(null)
            record(HistoryEntry.Event.ScheduleEnded(name = name, ending = HistoryEntry.Ending.ByYou))
        }
    }

    // endregion

    // region Remote

    suspend fun setMode(mode: Mode) {
        val link = link ?: return
        // Captured first: the BedJet's report that the schedule stopped can clear `run` before this returns.
        val endingSchedule = _run.value?.schedule?.name
        perform {
            link.setMode(mode)
            setRun(null)
            if (endingSchedule != null) {
                record(HistoryEntry.Event.ScheduleEnded(name = endingSchedule, ending = HistoryEntry.Ending.ByYou))
            }
            keepsLongestRuntime = true
            extendRuntime(entering = mode)
        }
    }

    suspend fun setTemperature(halfCelsius: Int) {
        val link = link ?: return
        perform {
            link.setTemperature(halfCelsius)
            extendRuntime()
        }
    }

    suspend fun setFan(percent: Int) {
        val link = link ?: return
        perform {
            link.setFan(percent / 5 - 1)
            extendRuntime()
        }
    }

    suspend fun setRuntime(minutes: Int) {
        val link = link ?: return
        keepsLongestRuntime = false
        perform { link.setRuntime(minutes) }
    }

    /**
     * Air started from the Remote runs for as long as the BedJet allows, until a shorter time is
     * picked there. Schedules keep their own timing.
     */
    private suspend fun extendRuntime(entering: Mode? = null) {
        val link = link
        // A schedule started from another phone times its own phases; leave its timer alone.
        if (!keepsLongestRuntime || _run.value != null || runningElsewhereStep != null || link == null) return
        // The limit depends on mode, temperature and fan, and the unit keeps the previous timer when
        // the mode changes, so wait for it to report the new state before reading the limit.
        delay(600)
        for (attempt in 0 until 20) {
            val status = _status.value
            if (status != null && status.isRunning && status.maxRuntimeMinutes > 0 && (entering == null || status.mode == entering)) {
                if (status.remainingSeconds <= (status.maxRuntimeMinutes - 1) * 60) {
                    link.setRuntime(status.maxRuntimeMinutes)
                }
                return
            }
            delay(200)
        }
    }

    suspend fun setLed(on: Boolean) {
        val link = link ?: return
        perform { link.setLed(on) }
    }

    suspend fun setMuted(muted: Boolean) {
        val link = link ?: return
        perform { link.setMuted(muted) }
    }

    private suspend fun perform(work: suspend () -> Unit) {
        _isBusy.value = true
        try {
            takeControl()
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: e.toString()
        } finally {
            _isBusy.value = false
            lastCommandAt = clock()
        }
    }

    /** Using a control while another phone has the BedJet asks that phone to hand it over first. */
    private suspend fun takeControl() {
        val link = link ?: return
        if (link.state == LinkState.READY) return
        val wasElsewhere = link.state == LinkState.YIELDED
        link.requestControl()
        val deadline = clock() + 8_000
        while (clock() < deadline) {
            delay(20)
            if (link.state == LinkState.READY) return
        }
        throw if (wasElsewhere) BedJetException.InUseElsewhere else BedJetException.NotConnected
    }

    /** Launching with `logConnections` keeps a record of connection times, so they can be measured on a real phone. */
    private fun logConnection() {
        if (!options.logConnections) return
        val timing = (link as? HardwareLink)?.lastConnection ?: return
        val line = String.format(
            Locale.ROOT, "new connection: radio %d, link %d, services %d, usable %d ms, %s\n",
            timing.radioOnMs, timing.linkUpMs, timing.servicesMs, timing.usableMs,
            if (timing.knownUnit) "known unit" else "scanned",
        )
        log(line)
    }

    private fun log(line: String) {
        val existing = storage.readFile(CONNECTIONS_FILE) ?: ""
        val time = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))
        storage.writeFile(CONNECTIONS_FILE, "$existing$time $line")
    }

    // endregion

    // region History

    /** The first reports after connecting describe the BedJet as it was left, not something that just happened. */
    private val isJustConnected: Boolean get() = clock() - connectedAt < 10_000

    /** Waits for the BedJet to hold a setting for a few seconds, so dragging a slider is one entry, not twenty. */
    private fun observe(status: DeviceStatus) {
        // Whether a schedule is running arrives in the second half of the report; without it a phase
        // change can't be told from a change someone made.
        val step = status.extra?.programStep ?: return
        val seen = Seen.of(status, programStep = step, at = clock())
        val isNew = observed?.let { !seen.sameSettings(it) } ?: true
        observed = seen
        val lastSeen = lastSeen
        if (lastSeen != null && seen.sameSettings(lastSeen)) {
            settling?.cancel()
            settling = null
            this.lastSeen = seen
            return
        }
        if (!(isNew || settling == null)) return
        settling?.cancel()
        settling = scope.launch {
            delay(SETTLE_MS)
            commitObserved()
        }
    }

    private fun commitObserved() {
        settling?.cancel()
        settling = null
        val now = observed ?: return
        // The first report ever only sets where the history starts from.
        val before = lastSeen
        if (before == null) {
            lastSeen = now
            return
        }
        lastSeen = now
        val wasAway = before.at < connectedAt && isJustConnected
        when {
            before.programStep == 0 && now.programStep >= 1 -> {
                // Started from this phone, it is recorded once the BedJet confirms it.
                if (_run.value == null && !_isBusy.value) {
                    record(HistoryEntry.Event.ScheduleStarted(name = null, phases = null), noticedLater = wasAway)
                }
                return
            }
            // The schedule moved on to its next phase, or ended; its start and end are recorded on their own.
            before.programStep >= 1 && now.programStep >= 1 && before.programStep != now.programStep -> return
            before.programStep >= 1 && now.programStep == 0 && now.mode == Mode.STANDBY -> return
        }
        if (now.mode == Mode.STANDBY) {
            if (before.mode == Mode.STANDBY) return
            val timerEnds = before.at + before.remainingSeconds * SECOND_MS
            val date = clock()
            val ranOut = lastCommandAt < before.at && (before.remainingSeconds < 120 || (wasAway && timerEnds < date))
            record(
                HistoryEntry.Event.Off(timerRanOut = ranOut),
                at = if (ranOut) minOf(timerEnds, date) else date,
                noticedLater = wasAway && !ranOut,
            )
        } else {
            record(
                HistoryEntry.Event.Air(mode = now.mode, temperature = now.temperature, fanPercent = now.fanPercent),
                noticedLater = wasAway,
            )
        }
    }

    private fun record(event: HistoryEntry.Event, at: Long = clock(), noticedLater: Boolean = false) {
        val history = _history.value.toMutableList()
        history.add(HistoryEntry(date = at, event = event, room = _status.value?.ambientTemperature, noticedLater = noticedLater))
        val previous = history.getOrNull(history.size - 2)
        if (previous != null && previous.date > at) history.sortBy { it.date }
        // Two months is plenty to compare one season's nights with the next.
        val cutoff = clock() - 60 * DAY_MS
        history.removeAll { it.date < cutoff }
        _history.value = history
        saveHistory()
    }

    /** Demo mode keeps its own history, so pretend nights never mix with real ones. */
    private val historyFile: String get() = if (shared.demoMode.value) "history-demo.json" else "history.json"

    private fun loadHistory() {
        settling?.cancel()
        settling = null
        observed = null
        val saved = if (options.ephemeral) null else storage.readFile(historyFile)?.let(::decodeHistory)
        _history.value = saved?.entries ?: (if (options.demoHistory) HistoryEntry.samples(clock()) else emptyList())
        lastSeen = saved?.lastSeen
    }

    private fun saveHistory() {
        if (options.ephemeral) return
        val saved = SavedHistory(entries = _history.value, lastSeen = lastSeen)
        try {
            storage.writeFile(historyFile, json.encodeHistory(saved))
        } catch (e: Exception) {
            // Like Swift's `try?`: the next change saves again.
        }
    }

    private fun decodeHistory(text: String): SavedHistory? =
        try { json.decodeHistory(text) } catch (e: Exception) { null }

    // endregion

    // region Schedules

    /** Modes the Remote and the phase editor offer for this kind of BedJet. */
    val remoteModes: List<Mode> get() = Mode.remoteModes.filter { _generation.value.supports(it) }
    val phaseModes: List<Mode> get() = Mode.phaseModes.filter { _generation.value.supports(it) }

    fun adjustsTemperature(mode: Mode): Boolean = _generation.value.adjustsTemperature(mode)

    fun degrees(halfCelsius: Int): String = "${Format.degrees(halfCelsius, fahrenheit = shared.useFahrenheit.value)}°"

    // endregion

    // region Persistence

    private fun load() {
        val saved = if (options.ephemeral) null else storage.readFile(FILE)?.let { text ->
            try { json.decodeSide(text) } catch (e: Exception) { null }
        }
        if (saved != null) {
            setTonight(saved.tonightId)
            val now = clock()
            setRun(saved.run?.takeIf { it.endsAt > now })
            val ended = saved.run
            if (ended != null && _run.value == null) {
                record(HistoryEntry.Event.ScheduleEnded(name = ended.schedule.name, ending = HistoryEntry.Ending.Finished), at = ended.endsAt)
            }
            setSlot(saved.slot)
        } else {
            setTonight(shared.schedules.value.firstOrNull()?.id)
        }
        isLoaded = true
    }

    private fun save() {
        if (!isLoaded || options.ephemeral) return
        val saved = SavedSide(tonightId = _tonightId.value, run = _run.value, slot = _slot.value)
        try {
            storage.writeFile(FILE, json.encodeSide(saved))
        } catch (e: Exception) {
            // Like Swift's `try?`: the next change saves again.
        }
    }

    // endregion

    private val defaultLabel: String get() = if (index == 0) "Left" else "Right"

    /** What a simulated side was assigned: a BedJet's address and name. */
    private data class Binding(val address: String?, val name: String?)

    private companion object {
        const val FILE = SIDE_FILE
        const val CONNECTIONS_FILE = "connections.txt"
        const val LABEL_KEY = "label"
        /** Where the link remembers the bound unit's name (`BleLink` writes it; this side only reads it). */
        const val NAME_KEY = "BedJetKit.deviceName"
        const val MAX_LABEL_LENGTH = 12
        const val SEARCH_PATIENCE_MS = 15_000L
        const val REFRESH_INTERVAL_MS = 300_000L
        const val SETTLE_MS = 3_000L
        /** Swift's `Date.distantPast`, far enough back that subtracting it from now can't overflow. */
        const val DISTANT_PAST = Long.MIN_VALUE / 4

        fun generationNamed(raw: String): Generation? = Generation.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == raw }
    }
}

/**
 * The app as a whole: the two sides of the bed, which one the screens show, and what the sides share, the schedule
 * library and the settings. Single-threaded like [SideModel]: create it, call it and collect it on the main thread.
 *
 * Each side keeps its settings and files under its own prefix (`side0.`, `side1.`, see [PrefixedStorage]). The
 * schedules (`lull.json`), `useFahrenheit`, `demoMode`, `demoElapsedMinutes` and `selectedSide` are the app's.
 */
class AppModel(
    private val storage: Storage,
    private val scope: CoroutineScope,
    linkFactory: (demo: Boolean, index: Int) -> BedJetLink,
    finderFactory: () -> UnitFinder,
    private val options: LaunchOptions = LaunchOptions(),
    private val json: JsonCodec,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    private val _useFahrenheit = MutableStateFlow(false)
    val useFahrenheit: StateFlow<Boolean> = _useFahrenheit.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    /** Launching with `demoLabelConnected` shows the simulated BedJet as connected, for listing screenshots. */
    val labelsDemoAsConnected: Boolean get() = options.demoLabelConnected

    private val _selectedSide = MutableStateFlow(0)
    val selectedSide: StateFlow<Int> = _selectedSide.asStateFlow()

    private val _isFinding = MutableStateFlow(false)
    val isFinding: StateFlow<Boolean> = _isFinding.asStateFlow()

    /** The picker is open. Its scan pauses while Lull is off screen. */
    private var wantsFinding = false

    val sides: List<SideModel>
    val selected: StateFlow<SideModel>
    private val _selected: MutableStateFlow<SideModel>
    private val finder: UnitFinder

    /** The picker's live list: units the finder sees plus units currently connected to a side. */
    val foundUnits: StateFlow<List<FoundUnit>>

    init {
        // Swift reads `Locale.current.measurementSystem == .us`; these are the countries that use it.
        _useFahrenheit.value = storage.getBoolean("useFahrenheit") ?: (Locale.getDefault().country in FAHRENHEIT_COUNTRIES)
        // The emulator has no Bluetooth, like the iOS Simulator, so it always runs the demo.
        _demoMode.value = options.forceDemo || storage.getBoolean("demoMode") == true
        _selectedSide.value = storage.getInt(SELECTED_SIDE_KEY)?.takeIf { it in 0 until SIDES } ?: 0
        if (!options.ephemeral) migrate()
        loadSchedules()
        val shared = SharedState(
            schedules = schedules, useFahrenheit = useFahrenheit, demoMode = demoMode,
            addSchedule = { schedule -> setSchedules(_schedules.value + schedule) },
            claim = { index, unit -> claim(index, unit) },
        )
        // Launching with `demoElapsedMinutes` 200 makes a night started in demo mode appear that far along.
        val sideOptions = options.copy(
            demoElapsedMinutes = options.demoElapsedMinutes.takeIf { it > 0 } ?: storage.getInt("demoElapsedMinutes") ?: 0,
        )
        sides = List(SIDES) { index ->
            SideModel(
                index = index, storage = PrefixedStorage(storage, sidePrefix(index)), scope = scope, linkFactory = linkFactory,
                options = sideOptions, json = json, clock = clock, shared = shared,
            )
        }
        _selected = MutableStateFlow(sides[_selectedSide.value])
        selected = _selected.asStateFlow()
        finder = finderFactory()
        // A side's BedJet joins the list while it is connected, when it can't be heard, and leaves it when it isn't.
        val sideChanges = combine(sides[0].linkState, sides[1].linkState, sides[0].unitName, sides[1].unitName) { _, _, _, _ -> }
        foundUnits = combine(finder.found, sideChanges) { found, _ -> pickerList(found) }
            .stateIn(scope, SharingStarted.Eagerly, pickerList(finder.found.value))
    }

    // region Sides

    fun selectSide(index: Int) {
        if (index !in sides.indices) return
        _selectedSide.value = index
        _selected.value = sides[index]
        storage.putInt(SELECTED_SIDE_KEY, index)
    }

    /**
     * Swaps ONLY the two BedJet bindings (address + name) and reconnects both, for when the BedJets were assigned
     * the wrong way round. Labels, tonight's schedule, slot, run and history stay with their side.
     */
    fun swapSides() {
        sides[0].swap(sides[1])
    }

    /** Side 0 then side 1. Each side's own `errorMessage` reports its failures, and one failing doesn't stop the other. */
    suspend fun startBothSides() {
        for (side in sides) side.startTonight()
    }

    /** The picker opened: list what the finder hears until a BedJet is picked or the picker closes. */
    fun startFinding() {
        wantsFinding = true
        if (_isFinding.value) return
        finder.start()
        _isFinding.value = true
    }

    /** The picker closed, or a BedJet was picked. */
    fun stopFinding() {
        wantsFinding = false
        pauseFinding()
    }

    private fun pauseFinding() {
        if (!_isFinding.value) return
        finder.stop()
        _isFinding.value = false
    }

    /** A side is being given [unit]: the picker is done, and the other side lets go of the BedJet if it had it. */
    private fun claim(index: Int, unit: FoundUnit) {
        stopFinding()
        val other = sides[1 - index]
        if (other.address != unit.address) return
        // In demo mode both sides are always set up, so the other side takes this side's simulated BedJet in exchange.
        if (_demoMode.value) swapSides() else other.release()
    }

    /** The sides' connected BedJets first, then what the finder hears. */
    private fun pickerList(found: List<FoundUnit>): List<FoundUnit> {
        // A BedJet stops advertising while it is connected, so the sides' links speak for their own.
        val connected = sides.mapNotNull { it.connectedUnit }
        return connected + found.filter { unit -> connected.none { it.address == unit.address } }
    }

    // endregion

    // region Settings

    fun setUseFahrenheit(value: Boolean) {
        _useFahrenheit.value = value
        storage.putBoolean("useFahrenheit", value)
    }

    /** Recreates both links: simulated BedJets, or the real ones the sides are bound to. */
    fun setDemoMode(value: Boolean) {
        _demoMode.value = value
        storage.putBoolean("demoMode", value)
        for (side in sides) side.demoModeChanged()
    }

    fun degrees(halfCelsius: Int): String = "${Format.degrees(halfCelsius, fahrenheit = _useFahrenheit.value)}°"

    // endregion

    // region Lifecycle

    /** Both BedJets are released whenever Lull leaves the screen (see [SideModel.appMovedToBackground]), and the picker's scan pauses. */
    fun appMovedToBackground() {
        for (side in sides) side.appMovedToBackground()
        pauseFinding()
    }

    fun appBecameActive() {
        for (side in sides) side.appBecameActive()
        if (wantsFinding) startFinding()
    }

    // endregion

    // region Schedules

    fun save(schedule: Schedule) {
        val schedules = _schedules.value.toMutableList()
        val index = schedules.indexOfFirst { it.id == schedule.id }
        if (index >= 0) schedules[index] = schedule else schedules.add(schedule)
        setSchedules(schedules)
    }

    /** Also takes it off any side that had it for tonight, which then has the first schedule, as before. */
    fun delete(schedule: Schedule) {
        setSchedules(_schedules.value.filter { it.id != schedule.id })
        for (side in sides) {
            if (side.tonightId.value == schedule.id) side.setTonight(_schedules.value.firstOrNull()?.id)
        }
    }

    private fun setSchedules(value: List<Schedule>) {
        _schedules.value = value
        saveSchedules()
    }

    // endregion

    // region Persistence

    private fun loadSchedules() {
        val saved = if (options.ephemeral) null else storage.readFile(FILE)?.let { text ->
            try { json.decodeSaved(text) } catch (e: Exception) { null }
        }
        _schedules.value = saved?.schedules ?: Schedule.templates
    }

    private fun saveSchedules() {
        if (options.ephemeral) return
        try {
            storage.writeFile(FILE, json.encodeSaved(Saved(schedules = _schedules.value)))
        } catch (e: Exception) {
            // Like Swift's `try?`: the next change saves again.
        }
    }

    /**
     * Before there were two sides, Lull knew one BedJet. On the first launch since, all of that becomes side 0's:
     * the remembered unit, its kind, tonight's schedule, the run, the slot and both histories. Each is copied only
     * where side 0 has nothing yet. The schedules stay in `lull.json`, which both sides share, and the old keys and
     * files are left where they are. Done once, so a BedJet forgotten later doesn't come back from the old keys.
     * An `ephemeral` launch, which leaves the saved state alone, leaves it for the next launch.
     */
    private fun migrate() {
        if (storage.getBoolean(MIGRATED_KEY) == true) return
        val side0 = PrefixedStorage(storage, sidePrefix(0))
        for (key in listOf("BedJetKit.peripheral", "BedJetKit.deviceName", "generation")) {
            if (side0.getString(key) == null) storage.getString(key)?.let { side0.putString(key, it) }
        }
        if (side0.readFile(SIDE_FILE) == null) {
            val old = storage.readFile(FILE)?.let { text -> try { json.decodeSaved(text) } catch (e: Exception) { null } }
            if (old != null && (old.tonightId != null || old.run != null || old.slot != null)) {
                try {
                    side0.writeFile(SIDE_FILE, json.encodeSide(SavedSide(tonightId = old.tonightId, run = old.run, slot = old.slot)))
                } catch (e: Exception) {
                    // Side 0 starts from the first schedule instead.
                }
            }
        }
        for (name in listOf("history.json", "history-demo.json")) {
            if (side0.readFile(name) == null) storage.readFile(name)?.let { side0.writeFile(name, it) }
        }
        storage.putBoolean(MIGRATED_KEY, true)
    }

    // endregion

    private companion object {
        const val SIDES = 2
        const val FILE = "lull.json"
        const val SELECTED_SIDE_KEY = "selectedSide"
        const val MIGRATED_KEY = "migratedToSides"
        val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM")

        /** Where a side keeps its settings and files; the app's `BleLink`s use the same. */
        fun sidePrefix(index: Int): String = "side$index."
    }
}

/** A side's own file (see [SavedSide]), under the side's prefix. */
internal const val SIDE_FILE = "side.json"

internal const val SECOND_MS = 1_000L
internal const val MINUTE_MS = 60_000L
internal const val DAY_MS = 86_400_000L
