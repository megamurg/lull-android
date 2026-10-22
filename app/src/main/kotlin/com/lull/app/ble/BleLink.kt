package com.lull.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.lull.app.model.HardwareLink
import com.lull.app.model.Storage
import com.lull.bedjetkit.BedJetException
import com.lull.bedjetkit.BedJetLink
import com.lull.bedjetkit.Codec
import com.lull.bedjetkit.CodecV2
import com.lull.bedjetkit.ConnectionTiming
import com.lull.bedjetkit.DeviceStatus
import com.lull.bedjetkit.Generation
import com.lull.bedjetkit.Handoff
import com.lull.bedjetkit.LinkState
import com.lull.bedjetkit.deviceName
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android Bluetooth transport for a BedJet 3 or V2. Connects to the one unit it is bound to ([bind]), by address, and
 * never looks for any other: with two BedJets on one bed, each side's link keeps to its own, and finding them is
 * [BleUnitFinder]'s job. Unbound, it stays `SEARCHING` without using the radio.
 * A port of `BLELink.swift`: the same state machine, timings and hand-over, on Android's Bluetooth APIs.
 *
 * The BedJet 3 has its own GATT service. The V2 sends its serial protocol (`CodecV2`) through a
 * Microchip "transparent UART" service, which other gadgets use too, so a V2 is recognised by its name.
 *
 * The BedJet accepts a single connection and stops advertising while it has one, so a second phone
 * cannot even see it. Two phones share it by handing over: the phone that wants the BedJet briefly
 * advertises a request, and the phone holding it disconnects when it sees that request.
 *
 * Single-threaded like the Swift `@MainActor` class: every Android callback is posted to the main thread
 * before it touches any state, and [send]/[query] run on the main dispatcher. Each side of the bed has its own
 * link, with its own scan and advertisement callbacks; [storage] is the side's (prefixed), so the remembered unit
 * and its name are the side's too.
 *
 * Android differences, each marked where it happens: CoreBluetooth's central and peripheral managers become
 * the adapter (watched through `ACTION_STATE_CHANGED`), the LE scanner and the LE advertiser; Android runs one
 * GATT operation at a time, so every operation goes through [gattOps]; notifications need their descriptor
 * written; the default packet size is too small, so a larger MTU is asked for; and a GATT client must be
 * closed when a connection ends or fails.
 *
 * Lint can't follow the permission checks, hence `MissingPermission`: they happen before each connection, scan,
 * advertisement and GATT request (`mayUseBluetooth`), and the radio only counts as on with them. Android ends the
 * process when a permission is withdrawn, so a client made with permission keeps it for as long as it exists.
 */
@SuppressLint("MissingPermission")
class BleLink(context: Context, private val storage: Storage) : BedJetLink, HardwareLink {
    private val context: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override var state: LinkState = LinkState.SEARCHING
        private set(value) {
            val oldValue = field
            field = value
            if (value == oldValue) return
            if (value == LinkState.READY) stopRequesting()
            onStateChange?.invoke(value)
        }
    override var generation: Generation = Generation.V3
        private set
    override var lastStatus: DeviceStatus? = null
        private set
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null

    /** Return false to postpone a hand-over, for example while a schedule is being saved. */
    override var canYield: () -> Boolean = { true }
    override var lastConnection: ConnectionTiming? = null
        private set

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Swift's `central != nil`: the radio has been asked about once. */
    private var centralCreated = false
    /** Android: whether [radioReceiver] is registered. */
    private var radioWatched = false
    /** Swift's `central.state`, which Android has no single value for. */
    private var radio = Radio.UNKNOWN
    /** Swift's `requester`: the advertisement asking another phone for the BedJet, while it runs. */
    private var advertising: AdvertiseCallback? = null
    /** Swift's `peripheral`. */
    private var device: BluetoothDevice? = null
    /** Android: the GATT client for [device]. Callbacks from any other client are from an attempt already over. */
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var reply: BluetoothGattCharacteristic? = null
    private var wantsConnection = false
    private var yieldedUntil = DISTANT_PAST
    private var requestGeneration = 0
    private var pendingRequest: UUID? = null
    private var askedAt: Long? = null
    private var linkUpAfter: Long? = null
    private var radioOnAfter = 0L
    private var servicesAfter = 0L
    private var connectGeneration = 0
    /** Android: when [connect] last asked for a connection, and whether the next one is waiting (see [connectIfPossible]). */
    private var lastAttemptAt = DISTANT_PAST
    private var attemptPostponed = false
    /** Android: whether a scan is running, and with which hand-over request as its filter. */
    private var isScanning = false
    private var scanFilter: UUID? = null
    /** Android: a scan is waiting for the oldest recent start to age out (see [scan]). */
    private var scanPostponed = false
    /** Android: the address [locate] is listening for, and the one heard since Bluetooth came on (see [locate]). */
    private var locating: String? = null
    private var located: String? = null
    private var locateGeneration = 0
    private var locatePostponed = false
    /** Android: a hand-over request was seen in this scan (see [discovered]). */
    private var requestSeen = false
    /** Android: the connection's setup sequence has finished (see [report]). */
    private var isSetUp = false

    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var lastHead: ByteArray? = null
    private var lastRemainderRead = DISTANT_PAST
    private var lastExtra: DeviceStatus.Extra? = null
    private var tag = 0
    /** Swift's `queue`: commands and queries one at a time. */
    private val queue = Mutex()
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private var pendingRead: CancellableContinuation<ByteArray>? = null
    /** A V2 answers through the same notifications that carry its status. */
    private var pendingReply: PendingReply? = null
    private val uartBuffer = CodecV2.StreamBuffer()
    private var operation = 0

    /** Android: GATT operations waiting for the one in flight, which is [currentOp]. */
    private val gattOps = ArrayDeque<GattOp>()
    private var currentOp: GattOp? = null
    private var opGeneration = 0
    /** Android: [runNextOp] is starting operations; one started from inside another waits in [gattOps]. */
    private var startingOps = false

    override fun start() {
        requestControl()
    }

    override fun stop() {
        wantsConnection = false
        askedAt = null
        stopRequesting()
        stopScan()
        yieldedUntil = DISTANT_PAST
        if (state == LinkState.READY) {
            gatt?.disconnect()
        } else {
            // Nothing is connected, so no disconnect will arrive to tidy up. (Android: an attempt still under way
            // is cancelled by closing its client, after which it reports nothing.)
            abandonConnection()
            clearConnection()
            state = LinkState.SEARCHING
        }
        unwatchRadio()
    }

    override fun requestControl() {
        wantsConnection = true
        // Not set up: nothing to connect to or ask for, so the radio is left alone until the link is bound.
        if (address == null) return
        val wasYielded = state == LinkState.YIELDED
        if (wasYielded) {
            yieldedUntil = DISTANT_PAST
            state = LinkState.SEARCHING
        }
        if (state != LinkState.READY && askedAt == null) {
            askedAt = now()
            radioOnAfter = 0
        }
        watchRadio()
        if (!centralCreated) {
            centralCreated = true
            // CoreBluetooth reports the radio's state just after the manager is made; so does this.
            handler.post { if (radioWatched) refreshRadio() }
        } else {
            // A phone that handed over may have been waiting on the unit for a long time. Now somebody is waiting too.
            val device = device
            if (wasYielded && device != null) expectAnswer(device)
            // Android doesn't announce that Lull was allowed Bluetooth, so look again.
            refreshRadio()
            connectIfPossible()
        }
        val request = handoffUuid
        if (state == LinkState.READY || request == null) return
        // Ask straight away rather than waiting to see whether the BedJet answers: if it is free
        // nobody is listening, and if another phone has it every moment of waiting is wasted.
        requestGeneration += 1
        val generation = requestGeneration
        advertise(request)
        handler.postDelayed({ if (requestGeneration == generation) stopRequesting() }, REQUEST_DURATION_MS)
    }

    /** The bound unit's address, which [bind] remembers in [storage] across launches; null while the link has none. */
    override var address: String? = storage.getString(REMEMBERED_KEY)?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        private set

    /**
     * Binds the link to the unit at [address] (null: none), remembering the [name] it advertised until the unit
     * tells its own. A different unit ends whatever was under way with the previous one; a started link then asks
     * for the new one straight away, and an unbound one waits, idle.
     */
    override fun bind(address: String?, name: String?) {
        // Android: `getRemoteDevice` throws for anything that isn't a MAC address.
        val target = address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        if (target == this.address) {
            // The name the unit told is kept over the one it advertised.
            if (target != null && name != null && storage.getString(NAME_KEY) == null) storage.putString(NAME_KEY, name)
            return
        }
        // The previous unit's attempt, connection, hand-over request and hand-over watch, all of which name it.
        connectGeneration += 1
        stopRequesting()
        stopScan()
        yieldedUntil = DISTANT_PAST
        askedAt = null
        lastAttemptAt = DISTANT_PAST
        // Android: once its client is closed a connection reports nothing more, so the tidy-up happens here.
        abandonConnection()
        clearConnection()
        this.address = target
        // A unit being assigned was just heard by the picker's scan, so the stack knows it: ask it directly.
        locating = null
        located = target
        storage.putString(REMEMBERED_KEY, target)
        storage.putString(NAME_KEY, if (target != null) name else null)
        if (target == null) {
            unwatchRadio()
            state = LinkState.SEARCHING
            return
        }
        if (state == LinkState.READY || state == LinkState.CONNECTING || state == LinkState.YIELDED) state = LinkState.SEARCHING
        if (wantsConnection) requestControl()
    }

    /** Forgets the bound unit, its address and its name. The link then waits, idle, until it is bound again. */
    override fun forget() {
        bind(null, null)
    }

    override suspend fun send(command: ByteArray) {
        withContext(Dispatchers.Main.immediate) {
            serially { write(command) }
        }
    }

    override suspend fun query(request: Int): ByteArray = withContext(Dispatchers.Main.immediate) {
        if (generation == Generation.V2) {
            return@withContext serially {
                // A request the unit missed is simply asked again.
                try {
                    exchange(request)
                } catch (e: BedJetException.Timeout) {
                    exchange(request)
                }
            }
        }
        serially {
            // The reply characteristic keeps its previous contents until the unit has answered,
            // so retry until the echoed request and tag match.
            for (attempt in 0 until 4) {
                tag = if (tag == 255) 1 else tag + 1
                write(Codec.query(request, tag))
                if (attempt > 0) delay(150)
                val data = read()
                if (data.size >= 2 && (data[0].toInt() and 0xff) == request && (data[1].toInt() and 0xff) == tag) {
                    return@serially data.copyOfRange(2, data.size)
                }
            }
            throw BedJetException.BadResponse
        }
    }

    // region Connection

    private fun connectIfPossible() {
        if (!wantsConnection || radio != Radio.ON || device != null) return
        if (state == LinkState.YIELDED && now() < yieldedUntil) return
        val adapter = adapter ?: return
        // Unbound, the link waits; [bind] starts it again.
        val bound = address ?: return
        // Android: an attempt can fail at once (status 133 while the stack is busy or out of GATT clients), and
        // `dropConnection` would then try again straight away, in a tight loop. Attempts are a second apart.
        val wait = lastAttemptAt + RETRY_PAUSE_MS - now()
        if (wait > 0) {
            if (!attemptPostponed) {
                attemptPostponed = true
                handler.postDelayed({
                    attemptPostponed = false
                    connectIfPossible()
                }, wait)
            }
            return
        }
        // The first time after Bluetooth came on, listen for the unit before asking it (see `locate`).
        if (located != bound) {
            locate(bound)
            return
        }
        connect(adapter.getRemoteDevice(bound))
    }

    /**
     * Android: a direct connection by address only works once the phone's Bluetooth stack has heard the unit
     * advertise since Bluetooth came on, because that is how it learns whether the address is public or random
     * (CoreBluetooth keeps that itself). So the first connection after that listens for the unit's own address and
     * connects to the device the scan hands over, which carries its address type. A unit that stays silent for
     * `CONNECT_PATIENCE_MS` (held by another phone, or already known to the stack) is then asked directly.
     */
    private fun locate(address: String) {
        if (!mayUseBluetooth()) return
        if (locating == address && isScanning) return
        stopScan()
        locating = address
        if (state != LinkState.YIELDED) state = LinkState.SEARCHING
        val wait = ScanStarts.wait(now())
        if (wait > 0) {
            if (!locatePostponed) {
                locatePostponed = true
                handler.postDelayed({
                    locatePostponed = false
                    locating = null
                    connectIfPossible()
                }, wait)
            }
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            ScanStarts.record(now())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Scan not started", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan not started", e)
        }
        locateGeneration += 1
        val generation = locateGeneration
        handler.postDelayed({
            if (locateGeneration != generation || locating != address || !wantsConnection) return@postDelayed
            // Not heard. It may be held by another phone, or the stack may know its address already: ask it directly.
            locating = null
            located = address
            stopScan()
            connectIfPossible()
        }, CONNECT_PATIENCE_MS)
    }

    private fun connect(target: BluetoothDevice) {
        if (!mayUseBluetooth()) return
        stopScan()
        lastAttemptAt = now()
        device = target
        // A phone that handed over keeps saying so while it waits; the connection completes
        // whenever the BedJet becomes free again.
        if (state != LinkState.YIELDED) state = LinkState.CONNECTING
        // A direct connection (autoConnect false) is the quick one. Android gives up on it after about
        // 30 seconds, usually with status 133, and `dropConnection` then simply tries again, which is how
        // a phone that handed over keeps waiting as CoreBluetooth's `connect` does.
        gatt = target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        expectAnswer(target)
    }

    /**
     * CoreBluetooth waits for a unit forever. An Android attempt can stall without reporting, so give the unit a
     * while, then ask again, by address: this link only ever connects to the unit it is bound to.
     */
    private fun expectAnswer(target: BluetoothDevice) {
        connectGeneration += 1
        val generation = connectGeneration
        handler.postDelayed({
            if (connectGeneration != generation || !wantsConnection || device !== target ||
                // A phone that handed over is meant to wait.
                !(state == LinkState.CONNECTING || state == LinkState.SEARCHING) || radio != Radio.ON
            ) {
                return@postDelayed
            }
            abandonConnection()
            device = null
            // Listen for it again before the next attempt (see `locate`); a unit held by another phone goes quiet.
            located = null
            connectIfPossible()
        }, CONNECT_PATIENCE_MS)
    }

    private fun dropConnection() {
        clearConnection()
        stopScan()
        // An attempt that ended is no proof the stack still knows the unit, so it is listened for again first.
        located = null
        locating = null
        if (state != LinkState.YIELDED) state = LinkState.SEARCHING
        connectIfPossible()
    }

    /** What `dropConnection` clears in the Swift, without looking for the BedJet again. */
    private fun clearConnection() {
        isSetUp = false
        device = null
        gatt = null
        command = null
        reply = null
        statusCharacteristic = null
        lastHead = null
        lastExtra = null
        lastStatus = null
        uartBuffer.clear()
        gattOps.clear()
        currentOp = null
        // Last, because a caller resumed here may carry on straight away.
        pendingReply?.let { pending ->
            pendingReply = null
            pending.continuation.resumeWithException(BedJetException.NotConnected)
        }
        pendingWrite?.let { pending ->
            pendingWrite = null
            pending.resumeWithException(BedJetException.NotConnected)
        }
        pendingRead?.let { pending ->
            pendingRead = null
            pending.resumeWithException(BedJetException.NotConnected)
        }
    }

    /**
     * Swift's `cancelPeripheralConnection` for a connection that isn't up: once a GATT client is closed Android
     * reports nothing more about it, so the caller tidies up itself.
     */
    private fun abandonConnection() {
        gatt?.let { client ->
            client.disconnect()
            client.close()
        }
        gatt = null
        gattOps.clear()
        currentOp = null
    }

    // endregion

    // region Radio (CoreBluetooth's manager states)

    private enum class Radio { UNKNOWN, ON, OFF, UNAUTHORIZED }

    private val radioReceiver = object : BroadcastReceiver() {
        // Delivered on the main thread. The adapter's own state is read rather than the broadcast's.
        override fun onReceive(context: Context, intent: Intent) {
            refreshRadio()
        }
    }

    private fun watchRadio() {
        if (radioWatched) return
        radioWatched = true
        // ACTION_STATE_CHANGED is a protected broadcast, so exporting the receiver lets nothing but Android reach it.
        // REVIEW: it is sent by the Bluetooth stack's own process rather than the system server; RECEIVER_NOT_EXPORTED
        // probably receives it too, but exported is the setting known to work on every version.
        ContextCompat.registerReceiver(
            context, radioReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /**
     * Android: while stopped the radio isn't watched, so its state is unknown until the next start looks again.
     * (CoreBluetooth keeps its manager informed throughout.)
     */
    private fun unwatchRadio() {
        if (!radioWatched) return
        radioWatched = false
        radio = Radio.UNKNOWN
        try {
            context.unregisterReceiver(radioReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
    }

    private fun currentRadio(): Radio {
        // No Bluetooth at all is CoreBluetooth's `.unsupported`, which Lull shows as off.
        val adapter = adapter ?: return Radio.OFF
        if (!permissionsGranted(context)) return Radio.UNAUTHORIZED
        return when (adapter.state) {
            BluetoothAdapter.STATE_ON -> Radio.ON
            // Like `.resetting` and `.unknown`: wait for the next report.
            BluetoothAdapter.STATE_TURNING_ON -> radio
            else -> Radio.OFF
        }
    }

    private fun refreshRadio() {
        val current = currentRadio()
        if (current == radio) return
        radio = current
        radioStateChanged()
    }

    /** `centralManagerDidUpdateState` and `peripheralManagerDidUpdateState` in one. */
    private fun radioStateChanged() {
        when (radio) {
            Radio.ON -> {
                radioOnAfter = askedAt?.let { now() - it } ?: 0
                connectIfPossible()
                pendingRequest?.let { advertise(it) }
            }
            Radio.UNAUTHORIZED -> {
                releaseRadio()
                state = LinkState.UNAUTHORIZED
            }
            Radio.OFF -> {
                releaseRadio()
                state = LinkState.BLUETOOTH_OFF
            }
            Radio.UNKNOWN -> {}
        }
    }

    /**
     * CoreBluetooth ends scans, advertising and connections itself when Bluetooth goes away. Android stops scans
     * and advertising too, but a GATT client must still be closed, and a disconnect may or may not be reported.
     */
    private fun releaseRadio() {
        // REVIEW: stopScan/stopAdvertising throw once the adapter is off, so they are only forgotten here.
        isScanning = false
        scanFilter = null
        // The stack forgets what it heard when Bluetooth goes off, so the unit is listened for again (see `locate`).
        locating = null
        located = null
        advertising = null
        gatt?.close()
        clearConnection()
    }

    // endregion

    // region Hand-over between phones

    /**
     * Requests are specific to one BedJet, so a neighbour's phone looking for theirs, or this phone's link to the
     * other side's BedJet, is ignored.
     * REVIEW: until the unit has been connected once, the name is the one it advertised when it was assigned, which
     * may not be the name it reports, so a request made before then may go unheard. And two BedJets with the same
     * name (the picker shows their addresses) make the same request, so a phone holding one would also hand over
     * the other; as on iOS, renaming one in the BedJet app tells them apart.
     */
    private val handoffUuid: UUID?
        get() = storage.getString(NAME_KEY)?.takeIf { address != null }?.let { Handoff.uuid(it) }

    private fun advertise(request: UUID) {
        pendingRequest = request
        if (radio != Radio.ON || !advertisingAllowed()) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        stopAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            // Stopped by `stopRequesting` after `REQUEST_DURATION_MS`, or as soon as the BedJet answers.
            .setTimeout(0)
            .build()
        // Many phones refuse a 128-bit service UUID together with the device name, which wouldn't fit anyway.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(request))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // The request is a courtesy; without it Lull still connects whenever the BedJet is free.
                Log.w(TAG, "Hand-over request not advertised: $errorCode")
            }
        }
        try {
            advertiser.startAdvertising(settings, data, callback)
            advertising = callback
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Hand-over request not advertised", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Hand-over request not advertised", e)
        }
    }

    private fun stopRequesting() {
        requestGeneration += 1
        pendingRequest = null
        stopAdvertising()
    }

    private fun stopAdvertising() {
        val callback = advertising ?: return
        advertising = null
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (e: IllegalStateException) {
            // Bluetooth went off, which stopped it.
        } catch (e: SecurityException) {
            // Permission withdrawn, which stops the app anyway.
        }
    }

    /** While holding the BedJet, listen for another phone asking for it. */
    private fun watchForRequests() {
        val request = handoffUuid
        if (state != LinkState.READY || request == null) return
        requestSeen = false
        scan(request)
    }

    private fun handOver(attempt: Int = 0) {
        val gatt = gatt
        if (state != LinkState.READY || gatt == null) return
        if (!canYield() || pendingWrite != null || pendingRead != null) {
            // Finish what is in flight first. If that takes too long, keep the BedJet and listen again.
            if (attempt >= 15) return watchForRequests()
            handler.postDelayed({ handOver(attempt + 1) }, 300)
            return
        }
        yieldedUntil = now() + COURTESY_MS
        state = LinkState.YIELDED
        gatt.disconnect()
        handler.postDelayed({ connectIfPossible() }, COURTESY_MS)
    }

    // endregion

    // region Scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { discovered(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copy = results.toList()
            handler.post { copy.forEach { discovered(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { scanFailed(errorCode) }
        }
    }

    /** Only ever a hand-over watch: this link never scans for a BedJet (see [BleUnitFinder]). */
    private fun scan(request: UUID) {
        // Android ignores apps that start scans more than five times in 30 seconds, so a scan that is already
        // running as wanted is left alone. (CoreBluetooth just updates a running scan.)
        if (isScanning && scanFilter == request) return
        stopScan()
        if (!mayUseBluetooth()) return
        // Android: a sixth start within 30 seconds is refused (silently on some versions, leaving a scan that never
        // reports). Quick reconnect loops and app switches can get there, so wait for the oldest start to age out
        // and then decide again what to scan for. The count is the app's, shared with the other side's link and
        // the picker ([ScanStarts]).
        val wait = ScanStarts.wait(now())
        if (wait > 0) {
            if (!scanPostponed) {
                scanPostponed = true
                handler.postDelayed({
                    scanPostponed = false
                    if (!wantsConnection) return@postDelayed
                    watchForRequests()
                }, wait)
            }
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(request)).build())
        // NOTE: while READY this filtered low-latency scan (100% duty cycle) runs for the whole connection. The cost
        // is bounded because the BedJet is released whenever Lull leaves the screen, so it only runs while Lull is
        // visible, next to a lit display. SCAN_MODE_BALANCED (~25%) would save power and radio time on phones
        // that struggle to scan and hold a connection at once, but could take ~4 s of the requester's 8 s to hear it.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            scanFilter = request
            ScanStarts.record(now())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Scan not started", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan not started", e)
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        scanFilter = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            // Bluetooth went off, which stopped it.
        } catch (e: SecurityException) {
            // Permission withdrawn, which stops the app anyway.
        }
    }

    /**
     * Android only: CoreBluetooth scans don't fail. Most failures here are the system's scanner being briefly
     * unavailable, so look again shortly.
     */
    private fun scanFailed(errorCode: Int) {
        if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
            // Android: Lull never starts a scan over one of its own (see [scan]), so the scanner still holds this
            // callback from a scan that was running when Bluetooth went off (`releaseRadio` can't stop it then).
            // Until it is stopped no new scan with this callback starts, so stop it now and start again.
            val request = scanFilter
            if (!isScanning || request == null) return
            stopScan()
            scan(request)
            return
        }
        // REVIEW: retrying after 2 s is a guess at a sensible pause; Swift has nothing to port here.
        Log.w(TAG, "Scan failed: $errorCode")
        isScanning = false
        scanFilter = null
        handler.postDelayed({ watchForRequests() }, 2_000)
    }

    private fun discovered(result: ScanResult) {
        // Android may still deliver results posted before the scan stopped; CoreBluetooth doesn't.
        if (!isScanning) return
        val wanted = locating
        if (wanted != null) {
            // Listening for the bound unit (see `locate`): connect to the device the scan hands over, address type and all.
            if (result.device.address != wanted || device != null) return
            locating = null
            located = wanted
            stopScan()
            connect(result.device)
            return
        }
        // The only other thing scanned for, while connected, is another phone asking for this BedJet.
        if (state != LinkState.READY) return
        val services = result.scanRecord?.serviceUuids ?: emptyList()
        val request = handoffUuid
        // CoreBluetooth reports a phone once per scan, Android every advertisement it hears; a request
        // is answered once, until `watchForRequests` listens again.
        if (request != null && ParcelUuid(request) in services && !requestSeen) {
            requestSeen = true
            handOver()
        }
    }

    // endregion

    // region Operations

    /** The unit handles one request at a time, so everything goes through a single queue. */
    private suspend fun <T> serially(work: suspend () -> T): T = queue.withLock { work() }

    private suspend fun write(data: ByteArray) {
        val command = command
        if (state != LinkState.READY || gatt == null || command == null) throw BedJetException.NotConnected
        suspendCancellableCoroutine<Unit> { continuation ->
            pendingWrite = continuation
            enqueue(
                GattOp(
                    awaits = Awaits.WRITE, uuid = command.uuid,
                    start = { client -> pendingWrite === continuation && writeCharacteristic(client, command, data) },
                    failed = {
                        if (pendingWrite === continuation) {
                            pendingWrite = null
                            continuation.resumeWithException(BedJetException.NotConnected)
                        }
                    },
                ),
            )
            expire {
                pendingWrite?.let { pending ->
                    pendingWrite = null
                    pending.resumeWithException(BedJetException.Timeout)
                }
            }
        }
    }

    private suspend fun read(): ByteArray {
        val reply = reply
        if (state != LinkState.READY || gatt == null || reply == null) throw BedJetException.NotConnected
        return suspendCancellableCoroutine { continuation ->
            pendingRead = continuation
            enqueue(
                GattOp(
                    awaits = Awaits.READ, uuid = reply.uuid,
                    start = { client -> pendingRead === continuation && client.readCharacteristic(reply) },
                    failed = {
                        if (pendingRead === continuation) {
                            pendingRead = null
                            continuation.resumeWithException(BedJetException.NotConnected)
                        }
                    },
                ),
            )
            expire {
                pendingRead?.let { pending ->
                    pendingRead = null
                    pending.resumeWithException(BedJetException.Timeout)
                }
            }
        }
    }

    private suspend fun exchange(request: Int): ByteArray {
        val command = command
        if (state != LinkState.READY || gatt == null || command == null) throw BedJetException.NotConnected
        return suspendCancellableCoroutine { continuation ->
            // Listen before writing, since the reply can arrive before the write is acknowledged.
            val pending = PendingReply(request, continuation)
            pendingReply = pending
            val bytes = CodecV2.request(request)
            enqueue(
                GattOp(
                    awaits = Awaits.WRITE, uuid = command.uuid,
                    start = { client -> pendingReply === pending && writeCharacteristic(client, command, bytes) },
                    failed = {
                        if (pendingReply === pending) {
                            pendingReply = null
                            continuation.resumeWithException(BedJetException.NotConnected)
                        }
                    },
                ),
            )
            expire {
                pendingReply?.let { waiting ->
                    pendingReply = null
                    waiting.continuation.resumeWithException(BedJetException.Timeout)
                }
            }
        }
    }

    private fun expire(fail: () -> Unit) {
        operation += 1
        val current = operation
        handler.postDelayed({ if (operation == current) fail() }, OPERATION_EXPIRY_MS)
    }

    /**
     * Notifications carry the live readings. When the link's packet size is too small for the whole
     * status, the unit flags the notification as partial and serves the remainder (program progress,
     * LED and beep settings) to a read of the same characteristic.
     */
    private fun receiveStatus(chunk: ByteArray) {
        if (!Codec.isStatusStart(chunk)) {
            val head = lastHead
            if (head != null) Codec.parseStatus(head + chunk)?.extra?.let { lastExtra = it }
            return
        }
        var status = Codec.parseStatus(chunk) ?: return
        val extra = status.extra
        if (extra != null) {
            lastExtra = extra
        } else {
            status = status.copy(extra = lastExtra)
            lastHead = chunk
            val characteristic = statusCharacteristic
            if (gatt != null && characteristic != null && now() - lastRemainderRead > 2_000) {
                lastRemainderRead = now()
                enqueue(GattOp(awaits = Awaits.READ, uuid = characteristic.uuid, start = { client -> client.readCharacteristic(characteristic) }))
            }
        }
        report(status)
    }

    /** A V2 streams packets that notifications may split, and answers requests among its status updates. */
    private fun receiveUART(chunk: ByteArray) {
        uartBuffer.append(chunk)
        for (packet in uartBuffer.takePackets()) {
            CodecV2.parseStatus(packet)?.let { report(it) }
            val name = CodecV2.reply(CodecV2.Query.DEVICE_NAME, packet)?.let { CodecV2.parseName(it) }
            if (!name.isNullOrEmpty()) {
                // The unit's own name identifies it to the other phone during a hand-over.
                storage.putString(NAME_KEY, name)
                watchForRequests()
            }
            val pending = pendingReply ?: continue
            val payload = CodecV2.reply(pending.query, packet) ?: continue
            pendingReply = null
            pending.continuation.resume(payload)
        }
    }

    /**
     * CoreBluetooth subscribes in the callback that makes the link ready, so the Swift hears no status before
     * then. Android subscribes earlier in the setup sequence; reports that arrive meanwhile wait for [becomeReady].
     */
    private fun report(status: DeviceStatus) {
        lastStatus = status
        if (isSetUp) onStatus?.invoke(status)
    }

    // endregion

    // region GATT (Android: one operation at a time)

    private enum class Awaits { WRITE, READ, DESCRIPTOR, MTU }

    /**
     * One GATT request. Android drops a request made while another is in flight, so each waits its turn.
     * [start] makes the request and says whether Android took it; [awaits] is the callback that finishes it
     * (null: done as soon as it starts). [failed] runs when the request couldn't be made.
     */
    private class GattOp(
        val awaits: Awaits?,
        val uuid: UUID?,
        val patience: Long = OPERATION_EXPIRY_MS,
        val start: (BluetoothGatt) -> Boolean,
        val failed: () -> Unit = {},
    )

    private class PendingReply(val query: Int, val continuation: CancellableContinuation<ByteArray>)

    private fun enqueue(op: GattOp) {
        gattOps.addLast(op)
        runNextOp()
    }

    private fun runNextOp() {
        // Starting an operation can resume a caller, which may ask for another straight away.
        if (startingOps) return
        startingOps = true
        try {
            startOps()
        } finally {
            startingOps = false
        }
    }

    private fun startOps() {
        while (currentOp == null) {
            val op = gattOps.removeFirstOrNull() ?: return
            val client = gatt
            val started = client != null && mayUseBluetooth() && try {
                op.start(client)
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                op.failed()
                continue
            }
            if (op.awaits == null) continue
            currentOp = op
            opGeneration += 1
            val generation = opGeneration
            // A callback that never comes would hold up everything after it. Callers have their own expiry.
            handler.postDelayed({
                if (currentOp === op && opGeneration == generation) {
                    currentOp = null
                    runNextOp()
                }
            }, op.patience)
        }
    }

    private fun finishOp(awaits: Awaits, uuid: UUID?) {
        val op = currentOp ?: return
        if (op.awaits != awaits || (op.uuid != null && op.uuid != uuid)) return
        currentOp = null
        runNextOp()
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.setValue(data)
            client.writeCharacteristic(characteristic)
        }

    /** Swift's `setNotifyValue(true, for:)`: Android also needs the descriptor written. */
    @Suppress("DEPRECATION")
    private fun subscribe(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!client.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        // CoreBluetooth picks notifications when the characteristic has them, otherwise indications.
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.setValue(value)
            client.writeDescriptor(descriptor)
        }
    }

    private fun isCurrent(client: BluetoothGatt): Boolean = client === gatt

    /** Called on a Binder thread; everything is handed to the main thread first. Values are copied before that. */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post { connectionStateChanged(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { if (isCurrent(gatt)) servicesDiscovered(gatt, status) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { if (isCurrent(gatt)) finishOp(Awaits.MTU, null) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { if (isCurrent(gatt)) finishOp(Awaits.DESCRIPTOR, null) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid
            handler.post { if (isCurrent(gatt)) written(uuid, status) }
        }

        // Android 13 and later call the overloads that carry the value (whose default implementations forward to
        // these); earlier versions call only these, whose value must be copied before the next operation reuses it.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            handler.post { if (isCurrent(gatt)) valueRead(uuid, value, status) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            handler.post { if (isCurrent(gatt)) valueRead(uuid, copy, status) }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: return
            handler.post { if (isCurrent(gatt)) valueChanged(uuid, value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            handler.post { if (isCurrent(gatt)) valueChanged(uuid, copy) }
        }
    }

    /** `didConnect`, `didFailToConnect` and `didDisconnectPeripheral`, which Android reports through one callback. */
    private fun connectionStateChanged(client: BluetoothGatt, status: Int, newState: Int) {
        // A unit given up on may still report back; that attempt is already over.
        if (!isCurrent(client)) {
            client.close()
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connectGeneration += 1
            linkUpAfter = askedAt?.let { now() - it }
            if (!client.discoverServices()) client.disconnect()
            // Android only: a connection whose setup never finishes, for example because the services are never
            // reported, is dropped and tried again, where CoreBluetooth would leave it waiting.
            // REVIEW: an Android safeguard with no Swift counterpart; 12 s (`CONNECT_PATIENCE_MS`) is generous for
            // discovery, a descriptor write, a read and the MTU exchange.
            handler.postDelayed({ if (gatt === client && !isSetUp) client.disconnect() }, CONNECT_PATIENCE_MS)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            // A failed connection (often status 133) and a disconnect end alike. Either way the client is
            // closed, or Android soon runs out of them.
            // REVIEW: as in the Swift, a failed attempt on the bound unit is retried (a second apart, see
            // `connectIfPossible`) and each retry restarts `expectAnswer`'s 12 s, for as long as the link is started.
            Log.d(TAG, "Disconnected: status $status")
            client.close()
            dropConnection()
        }
    }

    /** `didDiscoverServices` and `didDiscoverCharacteristicsFor`: Android finds the characteristics with the services. */
    private fun servicesDiscovered(client: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            client.disconnect()
            return
        }
        servicesAfter = askedAt?.let { now() - it } ?: 0
        val bedJet = client.getService(SERVICE)
        val uart = client.getService(UART_SERVICE)
        val characteristics = when {
            bedJet != null -> {
                generation = Generation.V3
                listOf(STATUS_UUID, NAME_UUID, COMMAND_UUID, REPLY_UUID).mapNotNull { bedJet.getCharacteristic(it) }
            }
            uart != null -> {
                generation = Generation.V2
                listOf(UART_NOTIFY_UUID, UART_WRITE_UUID).mapNotNull { uart.getCharacteristic(it) }
            }
            else -> {
                client.disconnect()
                return
            }
        }
        // Discover → subscribe to the status → read the name → larger packets → ready, one step at a time.
        val setup = mutableListOf<GattOp>()
        for (characteristic in characteristics) {
            when (characteristic.uuid) {
                STATUS_UUID, UART_NOTIFY_UUID -> {
                    statusCharacteristic = characteristic
                    setup += GattOp(
                        awaits = Awaits.DESCRIPTOR, uuid = null, start = { subscribe(it, characteristic) },
                        // REVIEW: CoreBluetooth can't refuse here. Without status the link would be ready but silent,
                        // so the rest of the setup is dropped with the connection, which is then tried again.
                        failed = {
                            gattOps.clear()
                            gatt?.disconnect()
                        },
                    )
                }
                NAME_UUID -> setup += GattOp(awaits = Awaits.READ, uuid = NAME_UUID, start = { it.readCharacteristic(characteristic) })
                COMMAND_UUID, UART_WRITE_UUID -> command = characteristic
                REPLY_UUID -> reply = characteristic
            }
        }
        if (command == null || (reply == null && generation != Generation.V2)) {
            client.disconnect()
            return
        }
        // Android starts at 23-byte packets: the status arrives in two parts and a program fragment (43 bytes)
        // doesn't fit. Without an answer, carry on; the status's partial path copes.
        setup += GattOp(awaits = Awaits.MTU, uuid = null, patience = MTU_PATIENCE_MS, start = { it.requestMtu(MTU) })
        setup += GattOp(awaits = null, uuid = null, start = { becomeReady(); true })
        // Android: queued together and then started, so a subscription refused at once (`failed` above) clears the
        // whole sequence. Enqueued one by one, the steps after it would still run, and the link turn ready.
        gattOps.addAll(setup)
        runNextOp()
    }

    /** The end of `didDiscoverCharacteristicsFor`, once Android has finished setting the connection up. */
    private fun becomeReady() {
        // Only connections somebody asked for are timed, not quiet reconnects after a hand-over.
        lastConnection = askedAt?.let { asked ->
            linkUpAfter?.let { linkUp ->
                // Every connection now goes straight to the bound unit; none is found by scanning.
                ConnectionTiming(
                    radioOnMs = radioOnAfter, linkUpMs = linkUp, servicesMs = servicesAfter,
                    usableMs = now() - asked, knownUnit = true,
                )
            }
        }
        askedAt = null
        linkUpAfter = null
        isSetUp = true
        state = LinkState.READY
        lastStatus?.let { onStatus?.invoke(it) }
        watchForRequests()
        if (generation == Generation.V2) {
            // The official app asks for the unit's details first thing, and the name is needed for hand-overs.
            scope.launch {
                ignoringFailure { query(CodecV2.Query.INFO) }
                ignoringFailure { deviceName() }
            }
        }
    }

    private inline fun ignoringFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Like Swift's `try?`.
        }
    }

    /** `didWriteValueFor`. */
    private fun written(uuid: UUID, status: Int) {
        finishOp(Awaits.WRITE, uuid)
        // REVIEW: Android gives only a status code where CoreBluetooth gives an error; a refused write is
        // reported as the unit sending something unexpected.
        val error = if (status == BluetoothGatt.GATT_SUCCESS) null else BedJetException.BadResponse
        // Android: the waiting write is taken before anyone is resumed. A caller resumed here carries on straight away
        // (`Dispatchers.Main.immediate`), so the next caller's write could otherwise be ended by this callback.
        val pending = pendingWrite
        pendingWrite = null
        if (error != null) {
            pendingReply?.let { waiting ->
                pendingReply = null
                waiting.continuation.resumeWithException(error)
            }
        }
        if (pending == null) return
        if (error != null) pending.resumeWithException(error) else pending.resume(Unit)
    }

    /** `didUpdateValueFor` after a read. */
    private fun valueRead(uuid: UUID, value: ByteArray, status: Int) {
        finishOp(Awaits.READ, uuid)
        val ok = status == BluetoothGatt.GATT_SUCCESS
        when (uuid) {
            STATUS_UUID -> if (ok) receiveStatus(value)
            NAME_UUID -> {
                // The unit's own name identifies it to the other phone during a hand-over.
                if (!ok || value.isEmpty()) return
                storage.putString(NAME_KEY, Codec.parseText(value))
                watchForRequests()
            }
            REPLY_UUID -> {
                val pending = pendingRead ?: return
                pendingRead = null
                // Like CoreBluetooth, a failed read still hands back whatever the characteristic holds, and the
                // query asks again when the echoed request and tag don't match. With nothing to hand back it fails.
                if (ok || value.isNotEmpty()) pending.resume(value) else pending.resumeWithException(BedJetException.BadResponse)
            }
        }
    }

    /** `didUpdateValueFor` for a notification. */
    private fun valueChanged(uuid: UUID, value: ByteArray) {
        when (uuid) {
            STATUS_UUID -> receiveStatus(value)
            UART_NOTIFY_UUID -> receiveUART(value)
        }
    }

    // endregion

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun mayUseBluetooth(): Boolean = permissionsGranted(context)

    private fun advertisingAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "BleLink"

        private fun uuid(short: String): UUID = UUID.fromString("0000$short-BED0-0080-AA55-4265644A6574")
        /** The BedJet 3's own service, which it advertises; [BleUnitFinder] recognises a BedJet 3 by it. */
        internal val SERVICE: UUID = uuid("1000")
        private val STATUS_UUID: UUID = uuid("2000")
        private val NAME_UUID: UUID = uuid("2001")
        private val COMMAND_UUID: UUID = uuid("2004")
        private val REPLY_UUID: UUID = uuid("2006")
        private val UART_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val UART_NOTIFY_UUID: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        private val UART_WRITE_UUID: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        /** The Client Characteristic Configuration descriptor, which turns notifications on. */
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REMEMBERED_KEY = "BedJetKit.peripheral"
        private const val NAME_KEY = "BedJetKit.deviceName"

        /** How long a phone that handed over stays out of the way before it quietly waits for the BedJet again. */
        private const val COURTESY_MS = 10_000L
        /** How long a request is advertised. Shorter than `COURTESY_MS`, so one request is answered once. */
        private const val REQUEST_DURATION_MS = 8_000L
        /**
         * How long a unit gets to accept a connection before Lull asks it again.
         * Longer than `REQUEST_DURATION_MS`, so another phone has time to hand the unit over.
         */
        private const val CONNECT_PATIENCE_MS = 12_000L
        private const val OPERATION_EXPIRY_MS = 5_000L
        /** Room for the whole status and a 43-byte program fragment, and what most phones accept. */
        private const val MTU = 247
        private const val MTU_PATIENCE_MS = 2_000L
        /** Android: the least time between two connection attempts on the bound unit. */
        private const val RETRY_PAUSE_MS = 1_000L
        /** Swift's `Date.distantPast`. */
        private const val DISTANT_PAST = Long.MIN_VALUE / 4

        /** What [MainActivity][com.lull.app.MainActivity] asks for: the "Nearby devices" group, or location before Android 12. */
        val requiredPermissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        /**
         * Whether Lull may scan and connect. Without this the link reports `UNAUTHORIZED`. Advertising the
         * hand-over request needs `BLUETOOTH_ADVERTISE` too, which Android grants with the other two.
         */
        fun permissionsGranted(context: Context): Boolean {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // REVIEW: before Android 12, scanning also needs Location turned on in Quick Settings; a phone with
                // it off finds nothing, so the picker stays empty (and a hand-over request goes unheard), with no
                // specific message.
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }
    }
}

/**
 * Android: a sixth scan start within 30 seconds is throttled (`AppScanStats`), and the count is the app's: both
 * sides' links (hand-over watches) and the picker ([BleUnitFinder]) start scans, so they share it. Main thread only.
 */
internal object ScanStarts {
    /** A little margin covers the system recording a start slightly after Lull does. */
    private const val PER_WINDOW = 5
    private const val WINDOW_MS = 30_500L
    private val starts = ArrayDeque<Long>()

    /** How long until another scan may start, in `elapsedRealtime` milliseconds; 0 or less: now. */
    fun wait(now: Long): Long = if (starts.size < PER_WINDOW) 0L else starts.first() + WINDOW_MS - now

    fun record(now: Long) {
        starts.addLast(now)
        if (starts.size > PER_WINDOW) starts.removeFirstOrNull()
    }
}
