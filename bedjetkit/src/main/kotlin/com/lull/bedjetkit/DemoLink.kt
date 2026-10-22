package com.lull.bedjetkit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A simulated BedJet that understands the same bytes as the real one.
 * Used for previews, the Android emulator (which has no Bluetooth) and tests.
 *
 * Like every link it is single-threaded: call it from one thread (the main dispatcher in the app).
 *
 * @param scope Where `start()` runs the once-a-second [tick]. Without one nothing is scheduled, and tests call
 *   [tick] themselves. (The Swift original uses a main-run-loop `Timer`, and plain JVM has no main dispatcher.)
 * @param now The clock that times program steps, in epoch milliseconds; replaceable so tests can move time.
 * @param name What the unit calls itself when asked, so two simulated BedJets (one per side of the bed) can be told apart.
 */
class DemoLink(
    slots: List<StoredProgram?> = listOf(null, null, null),
    private val scope: CoroutineScope? = null,
    private val now: () -> Long = System::currentTimeMillis,
    val name: String = "BEDJET-DEMO",
) : BedJetLink {
    override var state: LinkState = LinkState.SEARCHING
        private set(value) {
            val old = field
            field = value
            if (value != old) onStateChange?.invoke(value)
        }
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null

    private val storedSlots: MutableList<StoredProgram?> = slots.toMutableList()
    val slots: List<StoredProgram?> get() = storedSlots.toList()

    var clock: Pair<Int, Int>? = null
        private set
    override val lastStatus: DeviceStatus? get() = if (state == LinkState.READY) status else null

    private var status = DeviceStatus(
        remainingSeconds = 0, actualTemperature = 46, targetTemperature = 60, mode = Mode.STANDBY, fanStep = 7,
        maxRuntimeMinutes = 0, minTemperature = 38, maxTemperature = 80, ambientTemperature = 45, lastShutdownReason = 0,
        extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = false, programStep = 0, notification = 0),
    )

    /** The program that is running: its slot, the step it is on and when that step ends (epoch milliseconds). */
    private data class Run(val slot: Int, val step: Int, val stepEnds: Long)

    private var running: Run? = null
    private val pendingSteps: MutableMap<Int, List<ProgramStep>> = mutableMapOf()

    /** Stands in for the Swift `timer`: non-null between `start()` and `stop()`, even when there is no scope. */
    private var started = false
    private var timer: Job? = null

    override fun start() {
        if (started) return
        started = true
        state = LinkState.CONNECTING
        timer = scope?.launch {
            while (true) {
                delay(1_000)
                tick()
            }
        }
    }

    override fun stop() {
        timer?.cancel()
        timer = null
        started = false
        state = LinkState.SEARCHING
    }

    override suspend fun send(command: ByteArray) {
        val bytes = command
        if (bytes.size < 2) throw BedJetException.BadResponse
        when (bytes.ubyte(0)) {
            0x01 -> press(bytes.ubyte(1))
            0x02 -> if (bytes.size >= 3) {
                status = status.copy(
                    remainingSeconds = min(bytes.ubyte(1) * 60 + bytes.ubyte(2), status.maxRuntimeMinutes) * 60,
                )
            }
            0x03 -> {
                status = status.copy(targetTemperature = bytes.ubyte(1))
                limitHeat()
            }
            0x07 -> {
                status = status.copy(fanStep = bytes.ubyte(1))
                limitHeat()
            }
            0x08 -> if (bytes.size >= 3) clock = Pair(bytes.ubyte(1), bytes.ubyte(2))
            0x40 -> store(bytes)
            else -> {}
        }
        onStatus?.invoke(status)
    }

    override suspend fun query(request: Int): ByteArray = when (request) {
        Codec.Query.DEVICE_NAME -> name.encodeToByteArray() + byteArrayOf(0)
        Codec.Query.FIRMWARE -> "V1.20 (demo)".encodeToByteArray() + byteArrayOf(0)
        Codec.Query.PROGRAM_NAMES -> storedSlots.fold(ByteArray(0)) { data, program ->
            val slot = ByteArray(16)
            if (program != null) {
                for ((i, byte) in program.name.encodeToByteArray().take(Limits.NAME_LENGTH).withIndex()) slot[i] = byte
            } else {
                slot[0] = 0x01
            }
            data + slot
        }
        in 7 until 19 -> {
            val slot = (request - 7) / 4
            val index = (request - 7) % 4
            val steps = storedSlots[slot]?.steps ?: emptyList()
            val fragments = Codec.writeProgram(slot = slot, steps = steps)
            if (index in fragments.indices) {
                fragments[index].copyOfRange(2, fragments[index].size)
            } else {
                byteArrayOf(steps.size.toByte())
            }
        }
        else -> throw BedJetException.BadResponse
    }

    private fun press(button: Int) {
        when (button) {
            in 0x01..0x06 -> {
                running = null
                updateExtra { it.copy(programStep = 0) }
                val mode = Mode.entries.firstOrNull { it.button == button } ?: Mode.STANDBY
                enter(mode, minutes = if (mode == Mode.STANDBY) 0 else if (mode == Mode.TURBO) 10 else 60)
                // Manual modes start at an hour and can be stretched; these ceilings are illustrative.
                status = status.copy(
                    maxRuntimeMinutes = when (mode) {
                        Mode.STANDBY -> 0
                        Mode.TURBO -> 10
                        Mode.HEAT -> 240
                        else -> 720
                    },
                )
                limitHeat()
            }
            0x46, 0x47 -> updateExtra { it.copy(ledEnabled = button == 0x46) }
            0x48, 0x49 -> updateExtra { it.copy(beepsMuted = button == 0x48) }
            0x52 -> updateExtra { it.copy(notification = 0) }
            in 0x80..0x82 -> {
                val slot = button - 0x80
                val program = storedSlots[slot] ?: return
                // Like the real unit, refuse to start rather than stop partway through the night.
                if (clock == null) {
                    updateExtra { it.copy(notification = Codec.Notification.PROGRAM_NEEDS_CLOCK) }
                    return
                }
                if (!program.steps.all { it.fitsUnitLimits }) {
                    updateExtra { it.copy(notification = Codec.Notification.PROGRAM_TOO_LONG) }
                    return
                }
                running = Run(slot = slot, step = -1, stepEnds = now())
                advanceProgram(start = now())
            }
            in 0x83..0x85 -> storedSlots[button - 0x83] = null
            else -> {}
        }
    }

    private fun enter(mode: Mode, minutes: Int) {
        val range = mode.temperatureRange
        status = status.copy(
            mode = mode,
            remainingSeconds = minutes * 60,
            maxRuntimeMinutes = minutes,
            // Like the real unit, report the limits of the mode it is in.
            minTemperature = if (mode.usesTemperature) range.first else 20,
            maxTemperature = if (mode.usesTemperature) range.last else 80,
        )
        if (status.targetTemperature !in range) {
            status = status.copy(targetTemperature = range.first)
        }
    }

    /** Hot air at a high fan is cut off sooner, so manual Heat's ceiling follows temperature and fan. */
    private fun limitHeat() {
        if (status.mode != Mode.HEAT || running != null) return
        val longest = ProgramStep.longestMinutes(
            mode = Mode.HEAT,
            temperature = status.targetTemperature,
            fanStep = status.fanStep,
        )
        status = status.copy(maxRuntimeMinutes = min(240, longest))
        status = status.copy(remainingSeconds = min(status.remainingSeconds, status.maxRuntimeMinutes * 60))
    }

    private fun store(bytes: ByteArray) {
        if (bytes.size < 3) return
        val target = bytes.ubyte(1)
        if (target in 4..6) {
            val slot = target - 4
            val name = Codec.parseText(bytes.copyOfRange(3, bytes.size))
            val steps = pendingSteps.remove(slot)
            if (steps != null) {
                storedSlots[slot] = StoredProgram(name = name, steps = steps)
            } else {
                storedSlots[slot] = storedSlots[slot]?.copy(name = name)
            }
        } else if (target in 7 until 19) {
            val slot = (target - 7) / 4
            val index = (target - 7) % 4
            val fragment = Codec.parseFragment(bytes.copyOfRange(2, bytes.size), index = index) ?: return
            var steps = if (index == 0) emptyList() else (pendingSteps[slot] ?: emptyList())
            steps = steps + fragment.steps
            pendingSteps[slot] = steps
            if (steps.size >= fragment.total) {
                storedSlots[slot] = StoredProgram(name = storedSlots[slot]?.name ?: "", steps = steps)
            }
        }
    }

    /** Pretends the running program started this much earlier, for demos that should show a night in progress. */
    fun skipAhead(minutes: Int) {
        val run = running ?: return
        running = run.copy(stepEnds = run.stepEnds - minutes * 60_000L)
        while (true) {
            val current = running ?: break
            if (now() < current.stepEnds) break
            advanceProgram(start = current.stepEnds)
        }
        running?.let { current ->
            // `Int(timeIntervalSinceNow)` in the Swift: whole seconds, rounded toward zero.
            status = status.copy(remainingSeconds = max(0, ((current.stepEnds - now()) / 1000).toInt()))
        }
        onStatus?.invoke(status)
    }

    private fun advanceProgram(start: Long) {
        val current = running ?: return
        val program = storedSlots[current.slot] ?: return
        var run = current.copy(step = current.step + 1)
        if (run.step >= program.steps.size) {
            running = null
            updateExtra { it.copy(programStep = 0) }
            enter(Mode.STANDBY, minutes = 0)
            return
        }
        val step = program.steps[run.step]
        val minutes = step.hours * 60 + step.minutes
        run = run.copy(stepEnds = start + minutes * 60_000L)
        running = run
        status = status.copy(fanStep = step.fanStep, targetTemperature = step.temperature)
        updateExtra { it.copy(programStep = run.step + 1) }
        enter(step.mode, minutes = minutes)
    }

    /**
     * One simulated second. `start()` calls this every second when the link has a scope; tests call it directly.
     * The first tick after `start()` finishes connecting, as the first firing of the Swift timer does.
     */
    fun tick() {
        if (state == LinkState.CONNECTING) state = LinkState.READY
        running?.let { run -> if (now() >= run.stepEnds) advanceProgram(start = run.stepEnds) }
        if (status.mode != Mode.STANDBY && status.mode != Mode.WAIT) {
            status = status.copy(remainingSeconds = max(0, status.remainingSeconds - 1))
            if (status.remainingSeconds == 0 && running == null) enter(Mode.STANDBY, minutes = 0)
        }
        val goal = if (status.mode == Mode.STANDBY || status.mode == Mode.WAIT) {
            status.ambientTemperature
        } else {
            status.targetTemperature
        }
        status = status.copy(actualTemperature = status.actualTemperature + (goal - status.actualTemperature).sign)
        onStatus?.invoke(status)
    }

    /** Swift's `status.extra?.field = value`: changes nothing when there is no extra. */
    private fun updateExtra(change: (DeviceStatus.Extra) -> DeviceStatus.Extra) {
        status = status.copy(extra = status.extra?.let(change))
    }
}
