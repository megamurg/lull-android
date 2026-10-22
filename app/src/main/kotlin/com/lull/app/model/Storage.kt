package com.lull.app.model

import com.lull.bedjetkit.DeviceStatus
import com.lull.bedjetkit.Mode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Small settings and the app's JSON files. On Android: SharedPreferences "lull" and files in `filesDir`
 * (see `AndroidStorage`), standing in for `UserDefaults` and Application Support on iOS.
 *
 * Shared keys: `useFahrenheit`, `demoMode`, `demoElapsedMinutes`, `selectedSide`, `migratedToSides`. Shared file:
 * `lull.json` (the schedules). Each side's own, under the prefix `side0.` or `side1.` (see [PrefixedStorage]): keys
 * `generation` ("v2"/"v3"), `label`, `BedJetKit.peripheral`, `BedJetKit.deviceName`; files `side.json`,
 * `history.json`, `history-demo.json`, `connections.txt`. Before there were two sides, the per-side ones had no
 * prefix; `AppModel` moves them to side 0 once.
 */
interface Storage {
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    /** A null value removes the key. */
    fun putString(key: String, value: String?)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    /** The file's contents, or null when it doesn't exist or can't be read. */
    fun readFile(name: String): String?
    /** Replaces the file atomically. Failures are swallowed, like Swift's `try? data.write(to:options: .atomic)`. */
    fun writeFile(name: String, contents: String)
}

/** Keeps everything in memory, for tests and previews. */
class InMemoryStorage : Storage {
    private val values = mutableMapOf<String, Any>()
    val files = mutableMapOf<String, String>()

    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun getInt(key: String): Int? = values[key] as? Int
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun readFile(name: String): String? = files[name]
    override fun writeFile(name: String, contents: String) { files[name] = contents }
}

/** Storage view with every key and file name prefixed, so each side keeps its own settings and files. */
class PrefixedStorage(private val base: Storage, private val prefix: String) : Storage {
    override fun getBoolean(key: String): Boolean? = base.getBoolean("$prefix$key")
    override fun putBoolean(key: String, value: Boolean) = base.putBoolean("$prefix$key", value)
    override fun getString(key: String): String? = base.getString("$prefix$key")
    override fun putString(key: String, value: String?) = base.putString("$prefix$key", value)
    override fun getInt(key: String): Int? = base.getInt("$prefix$key")
    override fun putInt(key: String, value: Int) = base.putInt("$prefix$key", value)
    override fun readFile(name: String): String? = base.readFile("$prefix$name")
    override fun writeFile(name: String, contents: String) = base.writeFile("$prefix$name", contents)
}

/**
 * Turns the saved state into JSON and back. Lives behind an interface so the model compiles without the
 * kotlinx.serialization runtime; the app's implementation is `AndroidJsonCodec`. Decoding returns null for
 * anything unreadable, like Swift's `try? JSONDecoder().decode`.
 */
interface JsonCodec {
    fun encodeSaved(saved: Saved): String
    fun decodeSaved(text: String): Saved?
    fun encodeSide(side: SavedSide): String
    fun decodeSide(text: String): SavedSide?
    fun encodeHistory(history: SavedHistory): String
    fun decodeHistory(text: String): SavedHistory?
}

/** Flags that the iOS app reads from launch arguments; the Android app reads them from the Intent extras / BuildConfig. */
data class LaunchOptions(
    /** UI tests launch with this flag so every run starts from the built-in schedules. */
    val ephemeral: Boolean = false,
    /** Fills an empty History with a few sample nights, for listing screenshots. */
    val demoHistory: Boolean = false,
    /** Shows the simulated BedJet as connected, for listing screenshots. */
    val demoLabelConnected: Boolean = false,
    /** Makes a night started in demo mode appear this many minutes along. */
    val demoElapsedMinutes: Int = 0,
    /** Keeps a record of connection times in `connections.txt`, so they can be measured on a real phone. */
    val logConnections: Boolean = false,
    /** Demo mode regardless of the saved setting, as on the iOS Simulator; set on the Android emulator, which has no Bluetooth. */
    val forceDemo: Boolean = false,
)

/**
 * `lull.json`: the schedules, which both sides share. Before there were two sides it also held tonight's schedule,
 * the run and the slot of the one BedJet; those are only read now, once, to move them to side 0's [SavedSide],
 * and are written empty.
 */
@Serializable
data class Saved(
    val schedules: List<Schedule>,
    @SerialName("tonightID") val tonightId: String? = null,
    val run: ActiveRun? = null,
    val slot: Int? = null,
)

/** A side's `side.json`: which schedule is tonight's, the schedule running on its BedJet and the slot Lull uses there. */
@Serializable
data class SavedSide(
    @SerialName("tonightID") val tonightId: String? = null,
    val run: ActiveRun? = null,
    val slot: Int? = null,
)

/** A side's `history.json` / `history-demo.json`. */
@Serializable
data class SavedHistory(
    val entries: List<HistoryEntry>,
    val lastSeen: Seen? = null,
)

/** The settings worth remembering from a status report. The timer is carried along to tell when it ran out. */
@Serializable
data class Seen(
    val mode: Mode,
    val temperature: Int,
    val fanPercent: Int,
    val programStep: Int,
    val remainingSeconds: Int,
    /** Epoch milliseconds. */
    val at: Long,
) {
    fun sameSettings(other: Seen): Boolean =
        mode == other.mode && fanPercent == other.fanPercent && programStep == other.programStep &&
            (!mode.usesTemperature || temperature == other.temperature)

    companion object {
        fun of(status: DeviceStatus, programStep: Int, at: Long): Seen = Seen(
            mode = status.mode,
            temperature = status.targetTemperature,
            fanPercent = status.fanPercent,
            programStep = programStep,
            remainingSeconds = status.remainingSeconds,
            at = at,
        )
    }
}
