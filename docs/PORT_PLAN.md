# Lull for Android — port plan and API contract

This document is the single source of truth for the Android port of Lull
(https://github.com/rkanjani/lull, an iOS/SwiftUI app that schedules a BedJet 3 or BedJet V2 over Bluetooth).
The Swift original is checked out at `/home/claude/rkanjani/lull`. **Port it faithfully: same behaviour,
same bytes on the wire, same screens, same copy.** Where Android forces a difference, this document says what to do.

## 1. Ground rules

- Kotlin 2.1.20, Jetpack Compose + Material 3, coroutines, kotlinx.serialization. No other third-party libraries.
- **No analytics, accounts, network calls or cloud.** Bluetooth only. (Same as the original; it's a feature.)
- Package names: `com.lull.bedjetkit` (library), `com.lull.app` (app). `applicationId = "com.lull.app"`.
- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`.
- Every public type, function and property named in §4 is a **contract**: other agents code against these exact
  names and signatures without seeing your code. Do not rename, drop or re-type them. You may add private helpers freely.
- Temperatures everywhere in the model are **half-degrees Celsius** (`Int`), exactly as the BedJet stores them.
  Fan is `fanStep` 0…19 in the kit (percent = (step+1)*5) and `fanPercent` 5…100 in the app's `Phase`.
- Comments explain *why* (BedJet quirks), mirroring the Swift comments. Keep them; they are protocol knowledge.
- Copy the user-facing strings from the Swift source verbatim (error messages, hints, labels). Where the Swift
  says "phone" keep "phone"; where it mentions iOS-only things (Control Center, "swipe up"), adapt to Android.

## 2. Build environment: what can and cannot be verified here

This machine has **no Android SDK and no access to Maven Central / Google Maven** (network policy). It does have
JDK 21 and a standalone Kotlin 2.1.20 compiler at `/opt/kotlinc-dist/kotlinc` whose `lib/` bundles
`kotlin-stdlib`, `kotlinx-coroutines-core-jvm`, `kotlin-test` and `kotlin-test-junit`.

Therefore:

- `bedjetkit` is a **pure JVM Kotlin module** (plugin `org.jetbrains.kotlin.jvm`). It must not import anything
  from `android.*`/`androidx.*`. It is compiled and its tests are run here with `tools/verify.sh`.
- The app's non-UI logic (`Schedule.kt`, `History.kt`, `Format.kt`, `AppModel.kt`, `Storage.kt`) lives in
  `app/src/main/kotlin/com/lull/app/model/` and must **also be free of Android imports** (use `java.time`,
  `kotlinx.coroutines`, `kotlinx.serialization` only — NOT the serialization *plugin*-generated code for the
  local check, see below). `tools/verify.sh` compiles these files too.
- Compose UI and the BLE transport (`app/src/main/kotlin/com/lull/app/ui/`, `.../ble/`) cannot be compiled here.
  They are reviewed by reading. Write them conservatively: stick to APIs that have existed for years
  (Compose BOM 2024-era APIs are fine), avoid experimental annotations where a stable API exists, and when you
  must use an `@OptIn` API, say which one in a comment.

### Serialization note for the local check

The real build uses the `kotlinx.serialization` compiler plugin with `@Serializable` data classes. The runtime
`kotlinx-serialization-json` jar is **not** available locally, so the local check compiles `model/` **without the
plugin**, against stub annotations `kotlinx.serialization.Serializable`, `SerialName` and `Transient` (plain
annotation classes under `tools/stubs/kotlinx/serialization/`). That means `model/` may use those three annotations
and nothing else from `kotlinx.serialization`: every `Json.encodeToString`/`decodeFromString` call lives in
`app/src/main/kotlin/com/lull/app/AndroidJsonCodec.kt` behind the `JsonCodec` interface (§4.2), which tests replace
with an in-memory fake. Because sealed-class polymorphism needs stable discriminators, annotate every subclass of
`HistoryEntry.Event` and `Ending` with `@SerialName("air")`, `@SerialName("off")`, `@SerialName("scheduleStarted")`,
`@SerialName("scheduleEnded")`, `@SerialName("byYou")`, `@SerialName("finished")`, `@SerialName("early")`.

### Local verification recipe (`tools/verify.sh`)

```
KOTLINC=/opt/kotlinc-dist/kotlinc
LIB=$KOTLINC/lib
# 1. stub JUnit annotation + runner (tools/stubs/org/junit/Test.java, tools/LocalTestRunner.java) -> build/stub
# 2. kotlinc bedjetkit/src/main/kotlin -> build/kit
# 3. kotlinc bedjetkit/src/test/kotlin  (cp: build/stub, build/kit, kotlin-test.jar, kotlin-test-junit.jar) -> build/kit-test
# 4. java LocalTestRunner <test classes>   (runtime cp: WITHOUT kotlin-test-junit.jar, so kotlin.test's DefaultAsserter is used)
# 5. same for app/src/main/kotlin/com/lull/app/model + app/src/test/kotlin/com/lull/app/model
```
Tests are ordinary `kotlin.test` classes (`class CodecTest { @Test fun parsesStatus() {...} }`) so they also run
under Gradle with JUnit 4 on a real machine. Use `kotlinx.coroutines.runBlocking` for suspend tests
(not `kotlinx-coroutines-test`, which isn't available locally).

## 3. Repository layout

```
lull-android/
  settings.gradle.kts            # includes :bedjetkit, :app ; pluginManagement + dependencyResolutionManagement
  build.gradle.kts               # plugin aliases, apply false
  gradle.properties              # android.useAndroidX=true, kotlin.code.style=official, jvmargs
  gradle/libs.versions.toml      # version catalog (versions in §6)
  gradle/wrapper/                # already generated (8.14.3)
  gradlew, gradlew.bat
  bedjetkit/
    build.gradle.kts             # kotlin("jvm"), java 17 toolchain, deps: coroutines-core, test: kotlin-test, junit4
    src/main/kotlin/com/lull/bedjetkit/{Models,Codec,CodecV2,Link,DemoLink,Handoff}.kt
    src/test/kotlin/com/lull/bedjetkit/{CodecTest,CodecV2Test,HandoffTest}.kt
  app/
    build.gradle.kts
    proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/kotlin/com/lull/app/
      LullApplication.kt         # owns the AppModel singleton
      MainActivity.kt            # Compose host; permissions; lifecycle -> model.appBecameActive()/appMovedToBackground()
      AndroidStorage.kt          # Storage impl: SharedPreferences + JSON files in filesDir
      ble/BleLink.kt             # Android BLE transport (port of BLELink.swift)
      model/{Schedule,History,Format,Storage,AppModel}.kt
      ui/{Theme,Components,NightCurve,RootScreen,RemoteScreen,ScheduleScreen,ScheduleEditor,HistoryScreen,DeviceSheet}.kt
    src/main/res/values/{strings,themes,colors}.xml, mipmap icons (adaptive icon from Swift icon.png), xml/ for data extraction rules
    src/test/kotlin/com/lull/app/model/{ScheduleTest,HistoryTest,AppModelTest}.kt
  docs/PORT_PLAN.md (this file), docs/BEDJET_V2_PROTOCOL.md (copied from the Swift repo)
  tools/verify.sh, tools/LocalTestRunner.java, tools/stubs/...
  README.md
```

## 4. API contract

### 4.1 `bedjetkit` (package `com.lull.bedjetkit`) — port of `BedJetKit/Sources/BedJetKit/*.swift`

```kotlin
enum class Mode(val raw: Int) { STANDBY(0), HEAT(1), TURBO(2), EXTENDED_HEAT(3), COOL(4), DRY(5), WAIT(6);
    val button: Int?                     // 0x01 standby,0x02 cool,0x03 heat,0x04 turbo,0x05 dry,0x06 extendedHeat, null for WAIT
    val temperatureRange: IntRange       // heat 45..80, turbo 86..86, extendedHeat 38..67, cool 38..62, dry 40..80, standby/wait 80..80
    val usesTemperature: Boolean         // range.first != range.last
    val usesFan: Boolean                 // != STANDBY && != WAIT
    companion object { fun fromRaw(raw: Int): Mode? } }

enum class Generation { V2, V3;
    fun supports(mode: Mode): Boolean          // V3 all; V2: STANDBY, HEAT, TURBO, COOL, WAIT
    fun adjustsTemperature(mode: Mode): Boolean // V2 -> mode == HEAT ; V3 -> mode.usesTemperature
}

data class ProgramStep(val mode: Mode, val fanStep: Int, val temperature: Int, val hours: Int, val minutes: Int,
                       val endsAtClockTime: Boolean = false) {
    val longestMinutes: Int
    val fitsUnitLimits: Boolean
    companion object { fun longestMinutes(mode: Mode, temperature: Int, fanStep: Int): Int }  // heat table, port exactly
}

data class StoredProgram(val name: String, val steps: List<ProgramStep>)

data class DeviceStatus(val remainingSeconds: Int, val actualTemperature: Int, val targetTemperature: Int, val mode: Mode,
    val fanStep: Int, val maxRuntimeMinutes: Int, val minTemperature: Int, val maxTemperature: Int,
    val ambientTemperature: Int, val lastShutdownReason: Int, val extra: Extra?) {
    data class Extra(val ledEnabled: Boolean, val beepsMuted: Boolean, val programStep: Int, val notification: Int)
    val fanPercent: Int get() = (fanStep + 1) * 5
    val isRunning: Boolean get() = mode != Mode.STANDBY
}

enum class LinkState { BLUETOOTH_OFF, UNAUTHORIZED, SEARCHING, CONNECTING, READY, YIELDED }

data class ConnectionTiming(val radioOnMs: Long, val linkUpMs: Long, val servicesMs: Long, val usableMs: Long, val knownUnit: Boolean)

sealed class BedJetException(message: String) : Exception(message) {
    object NotConnected, InUseElsewhere, Timeout, BadResponse, VerifyFailed, ClockNotSet, ProgramTooLong
    class InvalidProgram(why: String)
    // message = the exact Swift errorDescription strings
}

object Limits { const val SLOTS = 3; const val STEPS_PER_PROGRAM = 32; const val STEPS_PER_FRAGMENT = 8;
                const val NAME_LENGTH = 15; const val MAX_STEP_MINUTES = 11 * 60 + 59 }

object Codec {            // BedJet 3, byte-for-byte port of Codec.swift. All functions return/accept ByteArray.
    object Query { const val DEVICE_NAME = 0x00; const val PROGRAM_NAMES = 0x04; const val FIRMWARE = 0x20; fun fragment(slot: Int, index: Int): Int }
    object Notification { const val CLEAN_FILTER = 1; UPDATE_AVAILABLE = 2; UPDATE_FAILED = 3; PROGRAM_NEEDS_CLOCK = 4; PROGRAM_TOO_LONG = 5 }
    fun setMode(mode: Mode): ByteArray?; fun setRuntime(minutes: Int): ByteArray; fun setTemperature(halfDegrees: Int): ByteArray
    fun setFan(step: Int): ByteArray; fun setClock(hour: Int, minute: Int): ByteArray; fun setLed(on: Boolean): ByteArray
    fun setMuted(muted: Boolean): ByteArray; fun startProgram(slot: Int): ByteArray; fun deleteProgram(slot: Int): ByteArray
    val acknowledgeNotification: ByteArray; fun query(request: Int, tag: Int): ByteArray
    fun nameProgram(slot: Int, name: String): ByteArray; fun writeProgram(slot: Int, steps: List<ProgramStep>): List<ByteArray>
    fun sanitize(name: String): String
    data class Fragment(val total: Int, val steps: List<ProgramStep>)
    fun parseFragment(payload: ByteArray, index: Int): Fragment?; fun parseNames(payload: ByteArray): List<String?>
    fun parseText(payload: ByteArray): String; fun isStatusStart(chunk: ByteArray): Boolean; fun parseStatus(packet: ByteArray): DeviceStatus?
}

object CodecV2 {          // BedJet V2, byte-for-byte port of CodecV2.swift
    object Query { const val INFO = 0x0b; const val DEVICE_NAME = 0x02 }
    const val SEQUENCE_FIRMWARE = 48; const val STEPS_PER_SEQUENCE = 31; val HEAT_RANGE = 44..80
    /** Splits the stream in `buffer`; returns complete packets and the unconsumed tail. */
    class StreamBuffer { fun append(chunk: ByteArray); fun takePackets(): List<ByteArray>; val size: Int; fun clear() }
    fun request(query: Int): ByteArray; fun setMode(mode: Mode, current: Mode): ByteArray?
    fun setTemperature(halfDegrees: Int, status: DeviceStatus): ByteArray; fun setFan(step: Int, status: DeviceStatus): ByteArray
    fun setRuntime(minutes: Int, status: DeviceStatus): ByteArray; fun setSettings(ledOn: Boolean, muted: Boolean): ByteArray
    val clearSequence: ByteArray
    fun writeStep(step: ProgramStep, index: Int, minutes: Int, extended: Boolean): ByteArray   // throws BedJetException.InvalidProgram
    fun tagSequence(tag: ByteArray): ByteArray; fun runSequence(hour: Int, minute: Int, second: Int, extended: Boolean): ByteArray  // time ignored unless extended
    const val UNSUPPORTED_MODES = "A BedJet V2 doesn't have Gentle heat or Dry. Change those phases to Heat or Cool."
    data class Info(val protocolVersion: Int, val firmware: Int, val extendedSequences: Boolean) { val runsSequences: Boolean }
    fun reply(query: Int, packet: ByteArray): ByteArray?; fun parseInfo(payload: ByteArray): Info?; fun parseName(payload: ByteArray): String
    fun parseStatus(packet: ByteArray): DeviceStatus?
}

/** A connection to one BedJet. Implementations are single-threaded on the main dispatcher. */
interface BedJetLink {
    val state: LinkState
    val generation: Generation             // default V3
    val lastStatus: DeviceStatus?
    var onStateChange: ((LinkState) -> Unit)?
    var onStatus: ((DeviceStatus) -> Unit)?
    fun start(); fun stop(); fun requestControl() {}   // default no-op
    suspend fun send(command: ByteArray)
    suspend fun query(request: Int): ByteArray
}
// Extension functions on BedJetLink, ported from the `extension BedJetLink` in Link.swift, with identical logic:
suspend fun BedJetLink.setMode(mode: Mode); setTemperature(halfDegrees: Int); setFan(step: Int); setRuntime(minutes: Int)
suspend fun BedJetLink.setLed(on: Boolean); setMuted(muted: Boolean); acknowledgeNotification()
suspend fun BedJetLink.syncClock(now: java.time.LocalTime = LocalTime.now()); deviceName(): String; firmwareVersion(): String
suspend fun BedJetLink.storedPrograms(): List<StoredProgram?>; programSteps(slot: Int): List<ProgramStep>
suspend fun BedJetLink.store(program: StoredProgram, slot: Int, now: LocalTime = LocalTime.now())
suspend fun BedJetLink.startProgram(slot: Int, now: LocalTime = LocalTime.now())
// Delays inside these (200 ms between V2 writes, 1 s between the two V2 "off" presses) use kotlinx.coroutines.delay.

class DemoLink(slots: List<StoredProgram?> = listOf(null, null, null)) : BedJetLink {
    val slots: List<StoredProgram?>; val clock: Pair<Int, Int>?
    fun skipAhead(minutes: Int)
    fun tick()                             // one simulated second; start() schedules tick() every second on the main dispatcher,
                                           // tests call tick() directly. Needs a CoroutineScope: constructor param `scope: CoroutineScope`
                                           // with default `CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())`? NO — Dispatchers.Main
                                           // doesn't exist on plain JVM. Use: `class DemoLink(slots, private val scope: CoroutineScope? = null)`;
                                           // if scope == null, start() just moves state to READY on the first tick() and never schedules.
}
```

### 4.2 App model (package `com.lull.app.model`) — port of `Lull/Schedule.swift`, `History.swift`, `AppModel.swift`

```kotlin
@Serializable data class Phase(val id: String = randomId(), val mode: Mode, val temperature: Int, val fanPercent: Int, val minutes: Int) {
    val level: Double; val maxMinutes: Int; val step: ProgramStep
    companion object { fun of(mode: Mode, temperature: Int? = null, fan: Int = 40, minutes: Int): Phase } }
@Serializable data class Schedule(val id: String = randomId(), val name: String, val phases: List<Phase>, val bedtime: Int = 22*60+30) {
    val totalMinutes: Int; val program: StoredProgram; fun startMinute(of: Phase): Int
    companion object { fun importing(program: StoredProgram, bedtime: Int = 22*60+30): Schedule; val templates: List<Schedule> } }
val Mode.phaseModes / remoteModes (lists), Mode.title, Mode.activity, Mode.defaultTemperature, Mode.maxMinutes   // as in Schedule.swift
object Format { fun clock(minuteOfDay: Int): String; fun duration(minutes: Int): String; fun degrees(halfCelsius: Int, fahrenheit: Boolean): Int;
                fun halfCelsius(degrees: Int, fahrenheit: Boolean): Int; fun time(epochMillis: Long): String; fun nightTitle(evening: LocalDate): String }
// `Format.clock` uses java.time.format with the default locale's short time style (DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)).

@Serializable data class HistoryEntry(val id: String = randomId(), val date: Long /*epoch millis*/, val event: Event, val room: Int? = null, val noticedLater: Boolean = false) {
    @Serializable sealed class Event { Air(mode, temperature, fanPercent); Off(timerRanOut); ScheduleStarted(name: String?, phases: List<Phase>?); ScheduleEnded(name: String?, ending: Ending) }
    @Serializable sealed class Ending { ByYou; Finished; Early(minutes) } }
data class Night(val id: String, val evening: LocalDate, val entries: List<HistoryEntry>) { val scheduleNames; val phases: List<Phase>
    companion object { fun group(entries: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Night>; fun samples(now: ...): List<HistoryEntry> } }
@Serializable data class ActiveRun(val schedule: Schedule, val startedAt: Long) { val endsAt: Long; fun progress(at: Long): Double; fun phase(at: Long): CurrentPhase? }
data class CurrentPhase(val phase: Phase, val index: Int, val minutesLeft: Int)

interface Storage {                                           // §4.3
    fun getBoolean(key: String): Boolean?; fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?; fun putString(key: String, value: String?)
    fun getInt(key: String): Int?
    fun readFile(name: String): String?; fun writeFile(name: String, contents: String)
}
class InMemoryStorage : Storage

/** Flags that the iOS app reads from launch arguments; the Android app reads them from the Intent extras / BuildConfig. */
data class LaunchOptions(val ephemeral: Boolean = false, val demoHistory: Boolean = false, val demoLabelConnected: Boolean = false,
                         val demoElapsedMinutes: Int = 0, val logConnections: Boolean = false, val forceDemo: Boolean = false)

class AppModel(storage: Storage, scope: CoroutineScope, linkFactory: (demo: Boolean) -> BedJetLink, options: LaunchOptions = LaunchOptions(),
               private val json: JsonCodec)  // JsonCodec: interface { fun encodeSaved(Saved): String; decodeSaved(String): Saved?; encodeHistory/decodeHistory }
{
    // All observable state is exposed as kotlinx.coroutines.flow.StateFlow (read) with the same names as the Swift properties:
    val linkState: StateFlow<LinkState>; status: StateFlow<DeviceStatus?>; deviceName: StateFlow<String?>; firmware: StateFlow<String?>
    val generation: StateFlow<Generation>; deviceSlots: StateFlow<List<StoredProgram?>>; schedules: StateFlow<List<Schedule>>
    val tonightId: StateFlow<String?>; run: StateFlow<ActiveRun?>; slot: StateFlow<Int?>; history: StateFlow<List<HistoryEntry>>
    val useFahrenheit: StateFlow<Boolean>; demoMode: StateFlow<Boolean>; isSlowToFind: StateFlow<Boolean>; isBusy: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>; needsSlotChoice: StateFlow<Boolean>
    val labelsDemoAsConnected: Boolean   // the -demoLabelConnected launch flag, read by ConnectionPill
    // Derived (plain getters, computed from current values): tonight: Schedule?; isConnected; canControl; runningElsewhereStep: Int?;
    //   nights: List<Night>; remoteModes; phaseModes; fun adjustsTemperature(mode): Boolean; fun degrees(halfCelsius): String
    // Setters/actions (same names as Swift):
    fun setTonight(id: String?); fun setUseFahrenheit(Boolean); fun setDemoMode(Boolean); fun clearError(); fun dismissSlotChoice()
    fun appMovedToBackground(); fun appBecameActive(); fun forgetDevice()
    suspend fun startTonight(); suspend fun startTonight(replacingSlot: Int); suspend fun endNight()
    suspend fun setMode(mode: Mode); suspend fun setTemperature(halfCelsius: Int); suspend fun setFan(percent: Int); suspend fun setRuntime(minutes: Int)
    suspend fun setLed(on: Boolean); suspend fun setMuted(muted: Boolean)
    fun save(schedule: Schedule); fun delete(schedule: Schedule)
    // Needed by BleLink for the hand-over courtesy check: `val canYield: () -> Boolean` is set on the link by the factory wrapper in LullApplication.
}
```
`AppModel` is **not** an `androidx.lifecycle.ViewModel` (keeps it compilable here). `LullApplication` creates one
`AppModel` with `MainScope()` and keeps it for the process lifetime; Compose reads it via `collectAsState()`.
Time is `System.currentTimeMillis()`; inject a `clock: () -> Long = System::currentTimeMillis` constructor parameter so tests can control it.

### 4.3 Storage keys and files (must match these names so a reinstall/upgrade keeps data)
SharedPreferences keys: `useFahrenheit`, `demoMode`, `generation` ("v2"/"v3"), `demoElapsedMinutes`,
`BedJetKit.peripheral` (remembered device MAC address), `BedJetKit.deviceName`.
Files in `filesDir`: `lull.json` (schedules, tonightID, run, slot), `history.json`, `history-demo.json` — same JSON shape as the
Swift `Saved`/`SavedHistory` structs (field names `schedules`, `tonightID`, `run`, `slot`; `entries`, `lastSeen`).

### 4.4 UI (package `com.lull.app.ui`) — port of `Lull/Views/*.swift` and `Theme.swift`
Three tabs in a bottom `NavigationBar`: Remote (icon Tune), Schedule (icon Bedtime/moon), History (icon History) — Remote first.
Full-screen `ScheduleEditor` (a `Dialog` with `usePlatformDefaultWidth = false` or an in-app overlay), `PhaseEditor` and
`DeviceSheet` as `ModalBottomSheet`. Dark theme only. Colours from `Theme.swift` (see §5.6).
`NightCurve` is a Compose `Canvas` drawing the same curve (midline, ticks, selection band, gradient area + glow stroke,
progress clip + dot) and exposes `level(minute, phases)` exactly as the Swift static. Accessibility: `contentDescription`
on the timeline as in the Swift `accessibilityLabel`s; `semantics { testTag }` for UI tests.

## 5. Android-specific decisions

### 5.1 Bluetooth permissions and manifest
- API 31+: `BLUETOOTH_SCAN` (with `android:usesPermissionFlags="neverForLocation"`), `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
  (needed for the two-phone hand-over request). API ≤ 30: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`
  (`android:maxSdkVersion="30"`). `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`.
- `MainActivity` requests the runtime permissions on start; while denied the link reports `UNAUTHORIZED`; while the adapter is
  off, `BLUETOOTH_OFF`. The DeviceSheet copy for those two states becomes "Turn on Bluetooth in Quick Settings to connect."
  and "Allow Nearby devices for Lull in Settings to connect."
- `android:exported="true"` on MainActivity, `android:launchMode="singleTask"`, `android:screenOrientation="portrait"` is NOT set (allow rotation).

### 5.2 BleLink (port of BLELink.swift) — same state machine, these Android specifics:
- Service/characteristic UUIDs are identical to the Swift. BedJet 3 service `0000<xxxx>-BED0-0080-AA55-4265644A6574`:
  service 1000, status 2000, name 2001, command 2004, reply 2006. V2 UART service/notify/write as in the Swift.
- Remember the unit by MAC (`BluetoothDevice.address`) under `BedJetKit.peripheral`; connect with `adapter.getRemoteDevice(address)`
  + `connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`.
- **MTU**: Android's default ATT MTU is 23, so a 43-byte program fragment can't be written. After services are discovered call
  `gatt.requestMtu(247)` and only mark the link READY after `onMtuChanged` (or after a 2 s timeout, in which case still proceed —
  the unit's partial-status path handles 20-byte notifications, and `writeProgram` fragments will fail with a clear error).
- Enable notifications properly: `setCharacteristicNotification(ch, true)` AND write the CCCD descriptor
  (`00002902-0000-1000-8000-00805f9b34fb`, `ENABLE_NOTIFICATION_VALUE`); wait for `onDescriptorWrite` before the next GATT op.
- One GATT operation in flight at a time (Android drops concurrent ones). The `serially {}` queue from the Swift already does this
  for commands/queries; the connection setup sequence (discover → CCCD writes → name read → MTU) must also be sequenced.
- Writes: on API 33+ use `gatt.writeCharacteristic(ch, bytes, WRITE_TYPE_DEFAULT)`; below, `ch.writeType = WRITE_TYPE_DEFAULT; ch.value = bytes; gatt.writeCharacteristic(ch)`.
- All callbacks hop to the main thread (`Handler(Looper.getMainLooper())` or `MainScope().launch`) before touching state.
- Scanning: unfiltered scan (`ScanSettings.SCAN_MODE_LOW_LATENCY`) to find a BedJet (service UUID in the advertisement or local name
  starting with "BEDJET", case-insensitive). While READY, scan with a filter on the hand-over UUID only. Android throttles apps that
  start/stop scans more than 5× in 30 s — never restart a scan that is already running with the same filter.
- Hand-over request: `BluetoothLeAdvertiser.startAdvertising` with `AdvertiseData.Builder().addServiceUuid(ParcelUuid(request))`,
  `ADVERTISE_MODE_LOW_LATENCY`, connectable=false, timeout 0 (stop it yourself after `requestDuration` 8 s). Many phones reject
  a 128-bit service UUID together with the device name: set `setIncludeDeviceName(false)`.
- Timeouts/durations identical to Swift: courtesy 10 s, requestDuration 8 s, connectPatience 12 s, operation expiry 5 s.
- On `onConnectionStateChange` with status 133 or disconnect: `gatt.close()`, drop state, reconnect as the Swift `dropConnection()` does.
- `canYield: () -> Boolean` property as in Swift.

### 5.3 Lifecycle
Single activity. `onStart` → `model.appBecameActive()`, `onStop` → `model.appMovedToBackground()` (the BedJet is released
whenever Lull leaves the screen, as on iOS).

### 5.4 Hand-over UUID (must match iOS so an iPhone and an Android phone can hand a BedJet to each other)
`uuid = sha256("lull.handoff." + name)[0..16)`, then `b[6] = (b[6] and 0x0f) or 0x50`, `b[8] = (b[8] and 0x3f) or 0x80`,
interpreted big-endian as a UUID (`UUID(mostSigBits = bytes 0..7, leastSigBits = bytes 8..15)`). Put this in `bedjetkit`
as `object Handoff { fun uuid(deviceName: String): java.util.UUID }` so it is unit-tested. Test vectors:
- "BEDJET-3B04" → `80b8472d-c1d5-500b-868e-a410afbdebf4`
- "BEDJET-3B05" → `c0e13b2a-74b2-5a79-95e0-4fd6c06e26d2`
- "Bedroom"     → `87206fc5-8f30-523e-bb4e-c5fb70d0c25d`

### 5.5 Launch flags
iOS reads `-ephemeral`, `-demoHistory`, `-demoLabelConnected`, `-demoElapsedMinutes N`, `-logConnections` from launch arguments.
Android: `MainActivity` reads the same names as Intent boolean/int extras (`adb shell am start ... --ez ephemeral true`) into
`LaunchOptions`. Demo mode is forced on when `Build.FINGERPRINT` contains "generic"/"emulator" or `Build.HARDWARE` is
"ranchu"/"goldfish" (the emulator has no Bluetooth), mirroring `#if targetEnvironment(simulator)`.

### 5.6 Theme (from Theme.swift)
background #000000; surface rgb(0.055,0.059,0.071) = #0E0F12; raised rgb(0.098,0.102,0.118) = #191A1E;
hairline white@8%; secondary white@55%; tertiary white@32%; cool #4DA3FF (0.30,0.64,1.0); deepCool #2B5CFF (0.17,0.36,1.0);
warm #FF8A3D (1.0,0.54,0.24); hot #FF4D2E (1.0,0.30,0.18); neutral #8F8F99 (0.56,0.56,0.60);
extendedHeat tint rgb(1.0,0.70,0.25) = #FFB340; dry tint rgb(0.35,0.84,0.77) = #59D6C4.
Card: 28 dp continuous corners, surface fill, hairline border, 20 dp padding. Primary button: 56 dp capsule, white fill / black text
(prominent) or raised fill / white text with hairline border. Eyebrow: 11 sp semibold, 1.6 sp letter spacing, uppercase, secondary.
Large titles 34 sp bold. Mode icons (Material Icons Extended): STANDBY PowerSettingsNew, HEAT LocalFireDepartment, TURBO Bolt,
EXTENDED_HEAT WbSunny, COOL AcUnit, DRY Air, WAIT Pause; schedule-started Bedtime; schedule-ended WbTwilight; early ErrorOutline.

## 6. Versions (gradle/libs.versions.toml)
agp = "8.9.1", kotlin = "2.1.20", composeBom = "2025.05.00", activityCompose = "1.10.1", lifecycle = "2.8.7",
coreKtx = "1.16.0", coroutines = "1.10.1", serialization = "1.8.0", junit = "4.13.2".
Plugins: com.android.application, org.jetbrains.kotlin.android, org.jetbrains.kotlin.jvm, org.jetbrains.kotlin.plugin.compose,
org.jetbrains.kotlin.plugin.serialization — all at the kotlin version except AGP.

## 7. Work split
- **Agent KIT**: `bedjetkit/` module + its tests + `tools/verify.sh` (kit half) + `Handoff`. Must pass locally.
- **Agent MODEL**: Gradle files (root, catalog, app module), manifest, `LullApplication`, `MainActivity`, `AndroidStorage`,
  `ble/BleLink.kt`, `model/*` + tests (`tools/verify.sh` model half). Codes against §4.1 without the kit's source.
- **Agent UI**: `ui/*`, `res/*`, codes against §4.2/§4.4.
- Then: reviewer passes (PM + an independent reviewer agent), fixes, final local verification, README.
