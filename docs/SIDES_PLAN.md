# Two sides, one bed — design and API contract

Lull for Android gains support for **two BedJets on one bed, controlled from one phone**. Each half of the bed is a
*side*; each side is bound to one BedJet and has its own remote, tonight's schedule, program slot and history. The
schedule library and the app settings are shared. Both BedJets are connected at the same time.

This document extends `PORT_PLAN.md`. Everything there still applies unless changed here. The same ground rules hold:
`model/` stays free of Android imports and is verified by `tools/verify.sh`; `ble/` and `ui/` are review-only.

## 1. Behaviour

- There are always exactly two sides, index 0 and 1, default labels "Left" and "Right". Labels are editable in the
  BedJet sheet (max 12 characters). A side with no BedJet assigned shows "Not set up" in its pill and its Remote shows
  "Choose this side's BedJet" with a button that opens the BedJet sheet.
- **Pairing**: the BedJet sheet has a "Sides" section listing both sides with their label and assigned BedJet name
  (or "Not set up"). Tapping a side opens the picker: a list of every BedJet currently visible over Bluetooth (name,
  plus the address when two names are equal), refreshed live while the picker is open, with a scanning indicator,
  "Also show the other side's BedJet" implicit (a BedJet already assigned to the other side is listed but marked
  "(Right's)" and choosing it moves it). Below the list: "Not sure which is which? Assign one, tap Heat on the Remote,
  and feel which side warms. Swap them here if it's the wrong one." A "Swap sides" button swaps the two assignments.
  Picking a BedJet stops the finder and binds that side to the unit's address and advertised name.
  While the picker is open, a BedJet that is connected to this phone is **not** visible to scanning (it stops
  advertising), so the picker also lists the connected unit(s) from their links (name + "connected").
- **Connections**: each side has its own `BedJetLink`. A link bound to an address connects only to that address;
  it never scans for "any BedJet". If the unit doesn't answer within `CONNECT_PATIENCE_MS` it keeps trying by
  address (with the existing 1 s pause between attempts). A side with no address keeps `LinkState.SEARCHING` with
  no radio activity — it's the picker's job to find units. `forget()` on a side clears its address and name.
- **Hand-over** works per side exactly as before (the request UUID is derived from each unit's own name).
  Two phones each holding one side is fine; two phones fighting over one side hand over as before.
- **Schedules**: the library (`schedules`) is shared. Each side has its own `tonightId`, `slot`, `run`, history and
  `lastSeen`. "Use Tonight" applies to the selected side. On the Schedule screen, **Start Night** starts the selected
  side; a second button **Start Both Sides** appears when both sides have a tonight schedule and neither is running,
  and starts side 0 then side 1 (sequentially, each reporting its own errors). **End Night** ends the selected side.
- **Side switcher**: a two-segment control ("Left | Right") directly under the large title on Remote, Schedule and
  History. The ConnectionPill shows the selected side's state. Switching sides is instant and remembered
  (`selectedSide` persisted).
- **Demo mode**: two `DemoLink`s named "BEDJET-DEMO-L" and "BEDJET-DEMO-R" (DemoLink gets a `name` constructor
  parameter, default "BEDJET-DEMO"); both sides come pre-assigned in demo mode so the emulator shows the full feature.
  The second demo unit starts with empty slots.
- **Migration**: on first launch after this change, the old single-unit data becomes side 0's: `BedJetKit.peripheral`
  / `BedJetKit.deviceName` → `side0.BedJetKit.peripheral` / `side0.BedJetKit.deviceName`; `lull.json`'s `tonightID`,
  `run`, `slot` → side 0 (`schedules` stay shared); `history.json` → `history-side0.json`; `history-demo.json` →
  `history-demo-side0.json`. The old keys/files are left in place (harmless).

## 2. Model contract (package `com.lull.app.model`)

```kotlin
/** One half of the bed: everything that used to be AppModel except the shared parts. */
class SideModel internal constructor(
    val index: Int,                                   // 0 or 1
    storage: Storage,                                 // ALREADY prefixed with "side<index>." by AppModel (see PrefixedStorage)
    scope: CoroutineScope, linkFactory: (demo: Boolean, index: Int) -> BedJetLink, options: LaunchOptions,
    json: JsonCodec, clock: () -> Long, shared: SharedState,
) {
    // Same StateFlows as the old AppModel had for per-unit state:
    val linkState, status, deviceName, firmware, generation, deviceSlots, tonightId, run, slot, history,
        isSlowToFind, isBusy, errorMessage, needsSlotChoice
    val label: StateFlow<String>                      // "Left"/"Right" by default; persisted under key "label"
    val unitName: StateFlow<String?>                  // the bound BedJet's advertised name (key "BedJetKit.deviceName"), null = not set up
    val isSetUp: Boolean                              // unitName != null (demo: always true)
    // Derived getters as before: tonight, isConnected, canControl, runningElsewhereStep, nights, remoteModes, phaseModes,
    //   adjustsTemperature(mode), degrees(halfCelsius) (reads shared useFahrenheit)
    // Actions as before: setTonight, clearError, dismissSlotChoice, forgetDevice, startTonight(), startTonight(replacingSlot),
    //   endNight, setMode, setTemperature, setFan, setRuntime, setLed, setMuted
    fun setLabel(label: String)
    /** Binds this side to a BedJet found by the picker and (re)connects. */
    fun assign(unit: FoundUnit)
    // Lifecycle, called by AppModel: appMovedToBackground(), appBecameActive(), reconnect(), stop()
}

data class FoundUnit(val address: String, val name: String, val rssi: Int?, val connectedToSide: Int? = null)

/** What both sides share. */
class SharedState(val schedules: StateFlow<List<Schedule>>, val useFahrenheit: StateFlow<Boolean>, val demoMode: StateFlow<Boolean>)

class AppModel(storage: Storage, scope: CoroutineScope, linkFactory: (demo: Boolean, index: Int) -> BedJetLink,
               finderFactory: () -> UnitFinder, options: LaunchOptions = LaunchOptions(), json: JsonCodec,
               clock: () -> Long = System::currentTimeMillis) {
    val sides: List<SideModel>                        // size 2
    val selectedSide: StateFlow<Int>; fun selectSide(index: Int)
    val selected: StateFlow<SideModel>                // sides[selectedSide]
    val schedules: StateFlow<List<Schedule>>; fun save(schedule); fun delete(schedule)   // shared library; delete also clears a side's tonightId that pointed at it
    val useFahrenheit: StateFlow<Boolean>; fun setUseFahrenheit(Boolean)
    val demoMode: StateFlow<Boolean>; fun setDemoMode(Boolean)                            // recreates both links
    val labelsDemoAsConnected: Boolean
    fun degrees(halfCelsius: Int): String
    /** The picker's live list: units the finder sees plus units currently connected to a side. */
    val foundUnits: StateFlow<List<FoundUnit>>; val isFinding: StateFlow<Boolean>
    fun startFinding(); fun stopFinding()            // picker open/closed
    fun swapSides()                                   // swaps ONLY the two BedJet bindings (address + name) and reconnects both; labels, tonight, slot, run and history stay with their side
    suspend fun startBothSides()                      // side 0 then side 1; each side's own errorMessage reports failures
    fun appMovedToBackground(); fun appBecameActive()
}

/** Scans for BedJets for the picker. Android implementation in ble/; tests use a fake. */
interface UnitFinder {
    val found: StateFlow<List<FoundUnit>>             // de-duplicated by address, newest RSSI
    fun start(); fun stop()
}

/** Storage view with every key and file name prefixed, so each side keeps its own settings and files. */
class PrefixedStorage(private val base: Storage, private val prefix: String) : Storage   // keys: "$prefix$key"; files: "$prefix$name"
```
Notes:
- `SideModel` keeps the old `AppModel.swift` logic unchanged; it just no longer owns `schedules`/`useFahrenheit`/`demoMode`
  and reads them from `SharedState`. Its persistence file is `side.json` (prefixed → `side0.side.json`) holding
  `tonightID`, `run`, `slot`; history is `history.json` / `history-demo.json` (prefixed). Migration (§1) is done by
  `AppModel` before the sides are created, by copying old storage values under the side-0 prefix when the prefixed
  keys are absent and the old ones present.
- `HardwareLink` gains: `fun bind(address: String?, name: String?)` (set the target; null = no unit) and
  `val address: String?`. `BleLink` no longer reads/writes the remembered address itself except through `bind`/`forget`
  and still stores the name it reads from the unit under `BedJetKit.deviceName` (via its prefixed Storage).
- `AppModel` tests: two `DemoLink`s, a fake `UnitFinder`; assign/swap/startBothSides/migration/selection persistence.

## 3. BLE contract (package `com.lull.app.ble`)

- `BleLink(context, storage: Storage)` as before, but: on `start()` with no bound address it stays SEARCHING idle (no
  scan); with an address it connects directly; `expectAnswer` retries by address instead of scanning for any BedJet;
  `discovered()` is only used for hand-over requests while READY. `bind()`/`forget()` per §2.
- `class BleUnitFinder(context) : UnitFinder` — one unfiltered low-latency scan while started, reporting BedJets
  (`isBedJet` as in BleLink: service UUID in the advertisement or name starting with "BEDJET"), de-duplicated by
  address, RSSI updated on every sighting, stale entries (not seen for 15 s) dropped. Respects the same scan-throttle
  caution (never restart a running scan). Permission-checked.
- Both links run in one process: they must not share `scanCallback`/advertiser state. Each link already owns its own.

## 4. UI contract (package `com.lull.app.ui`)

- `RootScreen(model: AppModel)` unchanged signature. Screens take `model: AppModel` and read `val side by model.selected.collectAsState()`
  then collect the side's flows (`side.linkState.collectAsState()` etc.). All per-unit calls go to `side`; `schedules`,
  `save`, `delete`, `useFahrenheit`, `degrees`, `demoMode` go to `model`.
- `Screen(...)` gets a `SideSwitcher` row under the title: a `SingleChoiceSegmentedButtonRow` with the two labels,
  selected = `model.selectedSide`, tap → `model.selectSide`. The pill shows the selected side (label "Not set up" when
  `!side.isSetUp`).
- Remote: when `!side.isSetUp`, the orb area shows "Choose this side's BedJet" + a `PrimaryButton("Choose BedJet")`
  that opens the DeviceSheet (same sheet as the pill), instead of the controls.
- Schedule: Start Night → `side.startTonight()`; `Start Both Sides` (prominent = false) when both sides have `tonight != null`
  and both `run == null` → `model.startBothSides()`. The running card is the selected side's.
- DeviceSheet: new first section "Sides": two rows (label + unit name or "Not set up"), tapping opens `UnitPicker`
  (a second `ModalBottomSheet` or an in-sheet expansion) that calls `model.startFinding()` on open / `stopFinding()` on
  close, lists `model.foundUnits` (name; address below in small text when two names match; "connected" when
  `connectedToSide != null`), tap → `side.assign(unit)`; plus "Swap sides" and the hint text from §1. Labels editable
  via a small text field in the row (or a rename dialog). The existing sections (Status/Name/Firmware, Can't find,
  BedJet toggles, Saved on the BedJet) are for the selected side; "Forget This BedJet" → `side.forgetDevice()`.
- `ErrorAlert`/`SlotChooser`: show for whichever side has `errorMessage`/`needsSlotChoice` set (check both sides;
  the dialog says which side in its title when both sides are set up: "Something went wrong (Left)").
