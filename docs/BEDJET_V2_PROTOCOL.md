# BedJet V2 protocol

How Lull talks to a BedJet V2. The code is in `BedJetKit/Sources/BedJetKit/CodecV2.swift`; the BedJet 3 protocol
is in `Codec.swift`.

## Sources

- **Official BedJet Android app** (`com.bedjet` 1.0.6, version code 18, "BedJet 3 Smart Remote"), decompiled with
  JADX 1.5.6. The V2 code lives in `MainActivity.sendData`, `decodeHomeV2`, `decodeStatic`, `decodeParam`,
  `BiorhythmFragment.sendAndRunSequence` and `V2Manager`. The Android app reaches a V2 over Bluetooth Classic
  (SPP). The packets below are the bytes it writes to that serial stream.
- **iOS captures** published at [roycamp/bedjet-protocol](https://github.com/roycamp/bedjet-protocol) (MIT).
  They show the official iOS app sending the same packets over BLE, through the Microchip ISSC "transparent UART"
  service. Several of those captures appear as test fixtures in `CodecV2Tests`.

Each section below is labelled by how sure it is:

- **Observed in code**: the decompiled app builds or parses it this way.
- **Captured**: also seen in real iOS↔V2 traffic.
- **Inferred**: not confirmed by either source.

## Transport (BLE)

| | UUID |
| --- | --- |
| Service | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` |
| Notify (unit → phone) | `49535343-1E4D-4BD9-BA61-23C647249616` |
| Write (phone → unit) | `49535343-8841-43F4-A8D4-ECBE34729BB3`, write with response |

The service is Microchip's generic UART bridge, and other products use it too. Lull therefore accepts a peripheral
when its advertised name starts with `BEDJET`, and connects to it before deciding which generation it is (captured).
Notifications are a byte stream, so packets are reassembled from it (`CodecV2.takePackets`).

## Framing (observed in code, captured)

```
prefix  length  type  payload…  checksum
```

- Prefix: `0x58` from the phone, `0x59` from the unit.
- `length` = number of bytes in type + payload (the total packet length minus 3).
- The checksum is `~(sum of all preceding bytes)`, so every byte in the packet sums to `0xff`. The unit checks it on
  commands, and the app drops replies whose checksum fails.

## Commands (phone → unit)

| Type | Payload | Meaning |
| --- | --- | --- |
| `0x0b` | – | Read static info (reply type 1). The iOS app sends it on connect. |
| `0x01` | button | Press a button: 1 Turbo, 2 Heat, 3 Cool, 4 fan up, 5 fan down. A mode's button switches to that mode; pressing the button of the current mode turns the air off. There is no off button. |
| `0x0e` | mode, fan, temp, h, m, s | Scene: mode (0–3, as in status), fan = percent ÷ 5 (1–20), temp = target in half °C with bit 7 = beeps muted, then the timer. The app repeats the current values for anything it isn't changing. |
| `0x07` | temp | Custom heat setpoint. The app only uses it for firmware < 48; newer units get the setpoint through the scene command. |
| `0x11` | flags | Bit 0 = mute beeps, bit 1 = LEDs off. |
| `0x02` / `0x03` | n | Save / recall memory preset *n* (1–3). |
| `0x04` | mem, alarm h, m, now h, m, s | Alarm that recalls memory *mem*. |
| `0x0c` | – | Clear the stored sequence. |
| `0x05` | id, size, data… | Write parameter *id*: `0x02` name (16 bytes), `0x03` sequence tag (10 bytes), `0x80 + n` sequence step *n*. |
| `0x06` | id, size | Read parameter *id* (reply type 5). |
| `0x0d` | [h, m, s] | Run the stored sequence. Units on the newer format get the current time of day. |

The decompiled app also sends `0x09` (enter bootloader), `0x0a` (debug on/off), `0x10` (`v30ena`) and `0x20`
(fan test), and uses an `0x42` bootloader prefix. Lull sends none of these.

### Sequence ("biorhythm") steps

```
0x80+n, 6, mode, fan, hours, minutes, 0, setpoint        older format
0x80+n, 7, mode, fan, hours, minutes, 0, setpoint, 0     newer format
```

- Mode: 0 Wait, 1 Turbo, 2 Heat, 3 Cool. Fan is 1–20 (percent ÷ 5). Setpoint is in half °C; the app stores 80 for
  Turbo and Wait and 40 for Cool.
- A unit uses the newer format when its info packet has bit 0 of byte 10 set and its firmware is 53 or later.
- Steps last a duration. The app converts "end at a time" steps into durations at the moment it sends them.
- The app's order is: clear (`0x0c`), each step, tag (`0x05 03 0a` + 8 random bytes + hour + minute), run
  (`0x0d`), with 200 ms between writes. Lull does the same.
- The app enables sequences only for firmware 48 and later.

**Inferred:**

- **No read-back.** The reply to reading a step back (`0x06 0x80+n`) isn't parsed anywhere in the app. Lull
  therefore doesn't verify a sequence by reading it; it confirms the start by waiting for the unit to report a
  running step instead.
- **31-step cap.** The status reports the running step in 5 bits, so Lull caps sequences at 31 steps. The
  firmware's real limit is unknown.

## Replies (unit → phone)

### Type 2: status, sent continuously (observed in code, captured)

| Byte | Meaning |
| --- | --- |
| 3 | Air temperature in half °C (bits 0–6); bit 7 = LEDs off |
| 4 | Mode in bits 5–7 (0 off, 1 Turbo, 2 Heat, 3 Cool); fan step + 1 in bits 0–4 |
| 5–6 | Timer: hours = b5 >> 4; minutes = (b5 & 0x0f) << 2 \| b6 >> 6; seconds = b6 & 0x3f |
| 7 | Target in half °C (bits 0–6); bit 7 = dual-zone switch (set = low power) |
| 8 | Alarm hour (bits 0–4); bit 7 = beeps muted |
| 9 | Alarm minute (bits 0–5); memory in bits 6–7 |
| 10 | Running sequence step, 1-based, in bits 3–7 (0 = no sequence) |
| 12 | Last shutdown reason, when length > 10. The app's diagnostic messages: 1 bad sensor, 2 airflow blockage, 3 fast thermal sensor, 4 slow thermal sensor, 5 motor RPM, 6 thermal controller, 7 cool-mode thermal controller |
| last | Checksum (the community notes call this a sequence number; it is the checksum) |

The V2 reports no room temperature and no per-mode limits. Lull fills these in from what the official app allows:

| Mode | Target | Longest run |
| --- | --- | --- |
| Heat | 44–80 half °C | 12 h, cut back by the same heat table as the BedJet 3 |
| Turbo | fixed at 86 | 10 min |
| Cool | not adjustable | 12 h |

### Type 1: static info (observed in code)

Byte 3 = protocol version, 4 = firmware version, 5 = build flags, 10 bit 0 = takes the newer sequence format.

### Type 5: parameter (observed in code)

`59, length, 05, id, data…, checksum`. For the name (id 2), `length` 2 means the unit was never named.

## Corrections to the community notes

- **Last status byte.** It is a checksum, not a sequence number. It verifies against the captured OFF packet.
- **"BYTE5" of the timer/fan command.** It is the target temperature with bit 7 = muted, not a fixed "temperature +
  0x80".
- **"BYTE8".** It is the timer's seconds.
- **`58 03 06 02 10 8c`.** It is "read name", not a timer button.
- **Temperatures.** They are in half °C, as on the BedJet 3. The community's °F formula `raw × 0.9 + 32` is the
  same conversion.
