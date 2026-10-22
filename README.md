# Lull for Android

Schedule your BedJet 3 or BedJet V2 through the night. No account, no cloud.

This is an Android port of [Lull for iOS](https://github.com/rkanjani/lull) (MIT). It does the same things the
iOS app does, with the same bytes on the wire:

- **Schedules**: build a night out of phases (Cool, Heat, Gentle heat, Turbo, Dry, Pause), each with its own
  temperature, fan speed and duration, and see the whole curve at a glance.
- **Runs on the device**: tonight's schedule is written into one of a BedJet 3's three program slots, or into a
  BedJet V2's single sequence, and the BedJet runs it. Your phone can sleep, leave the room, or run out of battery.
- **Remote**: a direct remote for mode, temperature, fan and shut-off timer.
- **History**: every change through the night, with the time and room temperature.
- **Two phones, one BedJet**: the BedJet only accepts one connection, so Lull hands it over between phones
  automatically. The hand-over request is derived the same way as on iOS, so an iPhone and an Android phone running
  Lull can hand one BedJet to each other.
- **Private**: Bluetooth only. No accounts, analytics, or network calls.
- **Two sides, one phone** (Android only): a dual-zone bed with two BedJets is controlled from one phone. Each side
  ("Left" and "Right", renameable) has its own BedJet, remote, tonight's schedule and history, and both stay
  connected at once; a side switcher sits under the title of every screen. Tap the connection pill → **Sides** to
  pick which BedJet is which. Not sure which is which? Assign one, tap Heat, feel which side warms, and use
  **Swap sides** if it's the wrong one. **Start Both Sides** on the Schedule tab starts both nights at once.

> Lull is an independent project and is not affiliated with or endorsed by BedJet LLC.

## Building

Requirements: Android Studio (Ladybug 2024.2 or newer, which bundles a JDK), an Android SDK with API 35, and a phone
running Android 8.0 (API 26) or later. The first Gradle sync downloads the dependencies, so it needs the internet.

1. Open the `lull-android` folder in Android Studio (**File → Open**). Let Gradle sync finish.
2. Plug in your phone with USB debugging on, or start an emulator.
3. Run the **app** configuration.

From the command line: `./gradlew assembleDebug` (the APK lands in `app/build/outputs/apk/debug/`), or
`./gradlew installDebug` with a phone connected.

**No BedJet? No problem.** The emulator has no Bluetooth, so on it the app uses a simulated BedJet (`DemoLink`) that
speaks the same bytes as the real one. On a phone, turn on **Demo mode** from the connection pill in the top right.

### Permissions

On Android 12 and later the app asks for the **Nearby devices** permission (Bluetooth scan, connect and advertise).
On Android 8–11 Bluetooth scanning requires the **Location** permission and Location to be switched on; the app
never uses your location. Until the permission is granted the connection pill shows "No access".

### Running the tests

```bash
./gradlew :bedjetkit:test :app:testDebugUnitTest
```

`tools/verify.sh` runs the same unit tests with a standalone Kotlin compiler and no Android SDK (it is how the port
was checked on a machine without one; see "What has been verified" below).

### Launch flags

The iOS app reads a few launch arguments for screenshots and tests. The Android app reads the same names as Intent
extras, for example:

```bash
adb shell am start -n com.lull.app/.MainActivity --ez ephemeral true --ez demoHistory true --ei demoElapsedMinutes 200
```

`ephemeral` starts from the built-in schedules and saves nothing; `demoHistory` fills an empty History with sample
nights; `demoLabelConnected` labels the demo BedJet "Connected"; `demoElapsedMinutes N` makes a night started in demo
mode appear N minutes along; `logConnections` appends connection timings to `connections.txt` in the app's files.

## Repository layout

| Path | What it is |
| --- | --- |
| `bedjetkit/` | Pure-Kotlin library: the BedJet 3 and V2 protocols (`Codec`, `CodecV2`), the `BedJetLink` interface and its operations, the simulated BedJet (`DemoLink`), and the hand-over UUID derivation. A port of the iOS `BedJetKit` package, with its captured-byte tests. |
| `app/src/main/kotlin/com/lull/app/model/` | The app's logic with no Android dependencies: schedules, phases, history, formatting, persistence interfaces and `AppModel` (a port of `AppModel.swift`). |
| `app/src/main/kotlin/com/lull/app/ble/BleLink.kt` | The Android Bluetooth transport, a port of `BLELink.swift`: connection, status, commands, and the two-phone hand-over. |
| `app/src/main/kotlin/com/lull/app/ui/` | The Jetpack Compose screens: Remote, Schedule, the schedule editor, History, the BedJet sheet, and the night curve. |
| `app/src/main/kotlin/com/lull/app/*.kt` | Android glue: `LullApplication`, `MainActivity`, storage and JSON. |
| `docs/PORT_PLAN.md` | The plan and API contract the port was built to; `docs/BEDJET_V2_PROTOCOL.md` is the V2 protocol write-up from the iOS repository. |
| `tools/` | `verify.sh` and the small test runner it uses. |

## What has been verified, and what hasn't

The port was produced on a machine with no Android SDK and no access to Maven, so the layers were checked in
different ways:

- **`bedjetkit` (protocol):** compiled and unit-tested. Every test from the iOS package is ported, including the
  packets captured from real BedJet 3 and V2 units, and all pass. The heat-table and hand-over UUID derivations were
  checked byte for byte against the Swift.
- **`app/.../model` (schedules, history, `AppModel`):** compiled and unit-tested against the simulated BedJet, and the
  JSON persistence round-tripped through the real kotlinx.serialization plugin and runtime.
- **`BleLink` and the Compose UI:** reviewed line by line against the Swift and against the published AndroidX API
  signature files, by more than one reviewer, but **not compiled and not run on a phone**. The first build in Android
  Studio may still turn up a small compile error in these files. One known soft spot: the Material icon names
  (`Icons.Filled.WbTwilight`, `Icons.Filled.Bedtime`, `Icons.Outlined.Air`, `Icons.Outlined.Bed` and the rest in
  `ui/Theme.kt`) were checked from memory; if the build reports an unresolved icon, swap in any icon that exists
  (`Icons.Filled.WbSunny`, `Icons.Filled.Whatshot`, …). The Bluetooth transport needs a real BedJet to prove out —
  please try it in a room with the official BedJet app closed, and report what the connection pill shows.

## Differences from the iOS app

- Dates and times follow the phone's locale and 12/24-hour setting; schedules, history and settings are stored in
  the app's private storage and included in Android backups. Files from the iOS app aren't shared or imported.
- The app icon is a plain crescent rather than the iOS artwork.
- The schedule editor closes (without saving) if the phone is rotated while it is open.
- Launch arguments are Intent extras (above) rather than Xcode scheme arguments.
- There is no `bedjetctl` command-line tool; `tools/verify.sh` covers the protocol tests instead.

## License

[MIT](LICENSE), the same as the iOS app.
