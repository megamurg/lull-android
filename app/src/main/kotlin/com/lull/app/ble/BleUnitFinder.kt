package com.lull.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.lull.app.model.FoundUnit
import com.lull.app.model.UnitFinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Finds BedJets for the picker, which binds each side to one (see `BleLink.bind`). Lists every BedJet it hears, one
 * entry per address, with the latest signal strength, and drops one that hasn't been heard for [STALE_MS].
 *
 * One unfiltered low-latency scan runs between [start] and [stop]: unfiltered, because a V2 is known by its name
 * rather than by what it advertises. A BedJet that is connected, to this phone or another, doesn't advertise, so it
 * can't be heard; the model adds the ones connected here from their links.
 *
 * Single-threaded: call it on the main thread. Android's scan callbacks are posted there before they touch anything.
 * Lint can't follow the permission check, hence `MissingPermission`: [start] checks first (`BleLink.permissionsGranted`).
 */
@SuppressLint("MissingPermission")
class BleUnitFinder(context: Context) : UnitFinder {
    private val context: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<List<FoundUnit>>(emptyList())
    override val found: StateFlow<List<FoundUnit>> = _found.asStateFlow()

    /** What has been heard, by address, in the order first heard, with when it was heard last (`elapsedRealtime`). */
    private val heard = LinkedHashMap<String, Sighting>()
    private class Sighting(val unit: FoundUnit, val at: Long)

    private var wanted = false
    private var isScanning = false
    /** A start is waiting for an older scan start to age out (see [ScanStarts]), or for the scanner to recover. */
    private var startPostponed = false
    /** Bumped by [stop], so a postponed start or a sweep scheduled before it does nothing. */
    private var generation = 0

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Starts listening. Calling it again while listening changes nothing. While Bluetooth is off or Nearby devices
     * isn't allowed, it keeps checking every [RETRY_MS] and starts as soon as it can.
     */
    override fun start() {
        wanted = true
        scan()
    }

    /** Stops the scan. What was found is forgotten, so the next time the picker opens it lists what is there then. */
    override fun stop() {
        wanted = false
        generation += 1
        startPostponed = false
        stopScan()
        heard.clear()
        _found.value = emptyList()
    }

    private fun scan() {
        // Android: a scan that is already running is never restarted. Starting scans more than five times in
        // 30 seconds gets the app throttled, and the scan that is running already hears everything.
        if (!wanted || isScanning || startPostponed) return
        val adapter = adapter ?: return
        // Android announces neither, so look again shortly.
        if (!BleLink.permissionsGranted(context) || adapter.state != BluetoothAdapter.STATE_ON) {
            postpone(RETRY_MS)
            return
        }
        // Android: the five starts in 30 seconds are counted for the whole app, the sides' hand-over watches
        // included, so a start that would be the sixth waits for the oldest to age out.
        val wait = ScanStarts.wait(now())
        if (wait > 0) {
            postpone(wait)
            return
        }
        // Null while Bluetooth is still turning on or off.
        val scanner = adapter.bluetoothLeScanner ?: return postpone(RETRY_MS)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            // No filters: a V2 doesn't advertise a service of its own (see [isBedJet]).
            // REVIEW: Android 8.1 and later stop delivering results to an unfiltered scan while the screen is off.
            // The picker is only open on screen, and the model stops the finder when Lull leaves it, so that's fine.
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            ScanStarts.record(now())
            sweep(generation)
        } catch (e: IllegalStateException) {
            // Bluetooth went off just now.
            Log.w(TAG, "Scan not started", e)
            postpone(RETRY_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan not started", e)
        }
    }

    private fun postpone(wait: Long) {
        if (startPostponed) return
        startPostponed = true
        val current = generation
        handler.postDelayed({
            if (generation != current) return@postDelayed
            startPostponed = false
            scan()
        }, wait)
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        releaseScanner()
    }

    /** Hands the callback back to the scanner, whether or not this finder thinks a scan is running. */
    private fun releaseScanner() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            // Bluetooth went off, which stopped it.
        } catch (e: SecurityException) {
            // Permission withdrawn, which stops the app anyway.
        }
    }

    /** Called on a Binder thread; everything is handed to the main thread first. */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { heard(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copy = results.toList()
            handler.post { copy.forEach { heard(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { scanFailed(errorCode) }
        }
    }

    private fun heard(result: ScanResult) {
        // Android may still deliver results posted before the scan stopped.
        if (!isScanning) return
        val device = result.device
        val name = result.scanRecord?.deviceName ?: nameOf(device)
        if (!isBedJet(result, name)) return
        val address = device.address
        heard[address] = Sighting(FoundUnit(address = address, name = name ?: address, rssi = result.rssi), at = now())
        publish()
    }

    /** A BedJet 3 advertises its own service; a V2 is known by its name, which starts with "BEDJET". */
    private fun isBedJet(result: ScanResult, name: String?): Boolean {
        if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(BleLink.SERVICE)) == true) return true
        return (name ?: "").uppercase(Locale.ROOT).startsWith("BEDJET")
    }

    private fun publish() {
        _found.value = heard.values.map { it.unit }
    }

    /**
     * Every second while scanning, drops what hasn't been heard for [STALE_MS]: it was switched off, moved away or
     * connected. Also notices Bluetooth going off, which ends the scan without a word.
     */
    private fun sweep(current: Int) {
        handler.postDelayed({
            if (generation != current || !isScanning) return@postDelayed
            if (adapter?.state != BluetoothAdapter.STATE_ON) {
                // Android: stopScan throws once the adapter is off, so the scan is only forgotten, as in BleLink.
                isScanning = false
                heard.clear()
                publish()
                postpone(RETRY_MS)
                return@postDelayed
            }
            val cutoff = now() - STALE_MS
            if (heard.values.removeAll { it.at < cutoff }) publish()
            sweep(current)
        }, SWEEP_MS)
    }

    /** Android only: CoreBluetooth scans don't fail. Most failures are the system's scanner being briefly unavailable. */
    private fun scanFailed(errorCode: Int) {
        if (!isScanning) return
        Log.w(TAG, "Scan failed: $errorCode")
        isScanning = false
        if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
            // Android: the finder never starts a scan over its own (see [scan]), so the scanner still holds this
            // callback from a scan that was running when Bluetooth went off. Until it is released no new scan
            // with this callback starts.
            releaseScanner()
        }
        // REVIEW: retrying after 2 s is a guess at a sensible pause, as in BleLink.
        postpone(RETRY_MS)
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun nameOf(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: SecurityException) {
            null
        }

    private companion object {
        const val TAG = "BleUnitFinder"
        /** A BedJet not heard for this long is dropped from the list. */
        const val STALE_MS = 15_000L
        const val SWEEP_MS = 1_000L
        const val RETRY_MS = 2_000L
    }
}
