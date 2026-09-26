# 01 — BLE link layer, session and message transport

Subsystem spec for a clean-room, wire-compatible Kotlin/Android reimplementation of
Faceclaw's link to the Even Realities G2 glasses (both temples) running the Faceclaw
custom firmware (CFW), plus the optional R1 ring link.

Reference revisions: Faceclaw `a6291cf`, g2flash `814db36`, g2-kit-unofficial (MIT) as checked out.
Where Faceclaw/g2flash (tested against the CFW) disagree with g2-kit, Faceclaw/g2flash wins and
the difference is called out.

---

## 0. Conventions

### 0.1 Source path aliases used in citations

| Alias | Absolute path |
|---|---|
| `FC/` | `/home/user/jimrandomh/faceclaw/` |
| `KT/` | `FC/native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/` |
| `KS/` | `KT/session/` |
| `AN/` | `FC/App_Resources/Android/src/main/java/com/faceclaw/app/` |
| `TS/` | `FC/app/` |
| `GF/` | `/home/user/jimrandomh/g2flash/` |
| `GP/` | `GF/patches/` |
| `GK/` | `/home/user/refs/g2-kit-unofficial/` |

Citations are `alias/file:line` or `alias/file:start-end`.

### 0.2 Notation

* Bytes are hex. `LE16` = unsigned 16-bit little-endian. Protobuf fields are written
  `fN` (field number N); `fN=v` a varint field, `fN{...}` a length-delimited sub-message,
  `fN"..."` a length-delimited string/bytes. Wire types: 0 varint, 1 fixed64, 2 length-delimited,
  5 fixed32.
* "TX" = phone → glasses, "RX" = glasses → phone.
* **R** = right temple/arm/lens, **L** = left temple/arm/lens. Each temple is a separate BLE
  peripheral with its own address, bond, GATT server, battery-independent MCU, and drives one lens.
* "Stock envelope" = the `aa 21`/`aa 12` framing used by the stock firmware. "CFW transport" =
  Faceclaw's private sid-`0xF0` transport implemented by the custom firmware.

---

## 1. Topology overview

```
                              GATT client (phone) — two independent LE links
  Phone ─────────────────────────► R temple  "Even G2_<tok>_R_<hex6>"   (stock "master": side id 1)
    │   stock control traffic:      ▲   ▲
    │   auth, prelude, EvenHub,     │   │  inter-lens firmware "bridge"
    │   settings, heartbeats  ──────┘   │  (stock SendDataToBoth, service 0x108; CFW reuses it
    │   input events / stock acks ◄─────┤   with app-id 0xF0 for private-transport relay)
    │                                   ▼
    └──────────────────────────────► L temple  "Even G2_<tok>_L_<hex6>"   (side id 2)
        CFW sid-0xF0 messages (ingress = L, lens mask = both) ──► L processes locally and
        bridges the packet to R; R's ACK comes back over the bridge; L notifies the phone
        with BOTH lenses' ACKs on L's notify characteristic.

  R1 ring: normally BLE-connected to the glasses (either lens), NOT to the phone. Its gestures
  reach the phone as EvenHub events from R. An optional developer "direct" mode also opens a
  third GATT link phone → ring (notify only).
```

Sources: `KT/ConnectionOptions.kt:50` (`sendImagesToLeft = true`), `GP/message_transport.c:227-247`,
`GP/message_transport.c:253-285`, `GP/gesture_fwd.c:160-171`, `TS/ui/dashboard-settings.ts:562-574`.

---

## 2. GATT layer

### 2.1 Services and characteristics

| UUID | Role | Properties used | Used by | Source |
|---|---|---|---|---|
| `00002760-08c2-11e1-9073-0e8ac72e5450` | Control service ("EvenHub/heartbeat svc", handles 0x084x) | — | (lookup by char UUID only) | `GF/g2flash.py:56` |
| `00002760-08c2-11e1-9073-0e8ac72e5401` | **Control write**: all TX envelopes (stock `aa 21` and CFW sid `0xF0`) | Write Without Response | main session, stock flows | `KT/BleProtocol.kt:15`, `GF/g2flash.py:57` |
| `00002760-08c2-11e1-9073-0e8ac72e5402` | **Control notify**: all RX envelopes (acks, async events, CFW ACK/NACK) | Notify (CCCD) | main session, stock flows | `KT/BleProtocol.kt:17`, `GF/g2flash.py:58` |
| `…0e8ac72e6401` | Render/stream write | (unused by Faceclaw) | g2-kit only | `GK/ble/ble.ts:28` |
| `…0e8ac72e6402` | **Render notify**: raw, *unframed* stream (stock LC3 mic packets; CFW mic `SM` frames) | Notify | main session, both arms, best effort | `KT/BleProtocol.kt:23`, `KS/GlassesSessionCore.kt:1761-1763` |
| `00002760-08c2-11e1-9073-0e8ac72e1001` | Firmware-data (OTA) service (handles 0x082x) | — | OTA flow | `GF/g2flash.py:53` |
| `…0e8ac72e0001` | OTA data write (stock envelope, sid `0xC0` ctrl / `0xC1` data) | Write Without Response | OTA flow only | `KT/BleProtocol.kt:19`, `KT/OtaFlashFlow.kt:34-36,246-247` |
| `…0e8ac72e0002` | OTA data notify (ack payload `[opcode, status]`) | Notify | OTA flow only | `KT/BleProtocol.kt:21`, `KT/StockLinkSession.kt:421-426` |
| `00002902-0000-1000-8000-00805f9b34fb` | CCCD, written `01 00` (ENABLE_NOTIFICATION_VALUE) | Write | all | `KT/BleProtocol.kt:173`, `AN/FaceclawBleManager.kt:274-284` |
| `bae80001-4f05-4503-8e65-3af1f7329d1f` | R1 ring service | — | g2-kit | `GK/ble/ring.ts:37` |
| `bae80011-4f05-4503-8e65-3af1f7329d1f` | R1 notify ("phone notify") | Notify | Faceclaw direct-ring mode | `KT/BleProtocol.kt:25` |
| `bae80012-4f05-4503-8e65-3af1f7329d1f` | R1 write | — (Faceclaw never writes the ring) | g2-kit | `GK/ble/ring.ts:38` |
| `bae80013-4f05-4503-8e65-3af1f7329d1f` | R1 notify (data) | Notify | Faceclaw direct-ring mode | `KT/BleProtocol.kt:27`, `GK/ble/ring.ts:39` |

Notes:
* The render service UUID (`…6450` by g2-kit naming) is never needed: Faceclaw resolves a
  characteristic by scanning **all** discovered services for the characteristic UUID
  (`AN/FaceclawBleManager.kt:427-436`). Do the same.
* g2-kit's `ble/docs/transport.md:24,33-37` and `envelope.md:3` describe a Nordic-UART-style
  service `6E40FFF0-…` with chars `fff1/fff2`. **That is wrong**; g2-kit's own code
  (`GK/ble/ble.ts:23-29`) and Faceclaw/g2flash use the `…0e8ac72e5401/5402` characteristics.
* The main session subscribes only to `5402` (mandatory) and `6402` (optional) on each temple
  (`KS/GlassesSessionCore.kt:1758-1763`). The OTA notify is only subscribed by the OTA flow
  (`KT/StockLinkSession.kt:186-200`).

### 2.2 Per-arm roles (what Faceclaw sends where / accepts from where)

| Traffic | R | L | Source |
|---|---|---|---|
| Security auth (sid `0x80`) | yes, first | yes, second | `KS/GlassesSessionCore.kt:1857-1868` |
| Session prelude (sid `0x01`) | yes | no | `KT/MessageBuilder.kt:16-28` (`isLeftArmMessage=false`) |
| EvenHub sid `0xE0` (create layout, heartbeat, shutdown, audio, IMU) | yes | no | `KT/MessageBuilder.kt:46-218` |
| Settings sid `0x09` read / stock sets | yes | no | `KT/MessageBuilder.kt:145-233` |
| CFW settings-channel controls (field 101 lease, 103 mic, wear query), magic 0 | yes (R first) | yes | `KS/GlassesSessionControls.kt:229-305`, `KS/GlassesSessionCore.kt:843-861` |
| CFW private transport sid `0xF0` | (R processes via bridge) | **ingress** | `KT/ConnectionOptions.kt:50`, `KS/GlassesSessionSend.kt:361,372-376` |
| CFW ACK/NACK notifications | — | arrive on ingress arm (L) for both lenses | `KS/GlassesSessionCore.kt:1330-1355` |
| Stock acks (matched by sid+magic, any arm) | yes | (auth ack) | `KS/GlassesSessionCore.kt:1490-1498` |
| Async input events (EvenHub DevEvent, Even AI, compass, CFW wake/idle gestures, ring battery notify, ALS→brightness) | decoded only from R | ignored | `KS/GlassesSessionCore.kt:1366-1369,1379-1476,1499-1540` |
| CFW mic status (settings field 104) | yes | yes | `KS/GlassesSessionCore.kt:1460-1465` |
| Wear state (sid `0x10`) | accepted | accepted | `KS/GlassesSessionCore.kt:1365,1374-1377` |
| Render notify audio (`6402`) | forwarded, tagged "R" | forwarded, tagged "L" | `KS/GlassesSessionCore.kt:1589-1605` |

"Master" lens: stock firmware side ID **1 = right, 2 = left** (`GP/settings_ext.c:82`,
`GP/message_transport.c:41-44`). Only the right/master lens emits the CFW wake/gesture
notifications (`GP/settings_ext.c:173-178,194-195`) and ALS reports; g2-kit independently observed
that the left arm never emits async events (`GK/ble/docs/gotchas.md`, "Arms").

**Discrepancy:** g2-kit claims "there is no internal bus between them … you talk to both arms
yourself" (`GK/ble/docs/transport.md:7`). Faceclaw's stock flows state the opposite ("the lenses
relay messages to each other", `KT/FlashPromptFlow.kt:10-13`), sends EvenHub page creation to R
only, and the CFW explicitly relies on the stock inter-lens bridge (`GP/message_transport.c:8-12,
58-66`). Follow Faceclaw: stock EvenHub/settings traffic goes to R only.

### 2.3 Write type, pacing and flow control

* Every TX write (control and OTA) is **Write Without Response**
  (`KT/ConnectionOptions.kt:7`; `AN/…/AndroidProtocolPlatform.kt:40-44` maps to
  `WRITE_TYPE_NO_RESPONSE`; g2flash uses write type 1 = without response, `GF/g2flash.py:163-171`).
* One BLE write = one envelope frame (never concatenate frames into one write).
* Android pacing (`AN/FaceclawBleManager.kt:301-398`):
  1. For each frame: `gatt.writeCharacteristic(char, frame, WRITE_TYPE_NO_RESPONSE)`.
  2. If the call returns `ERROR_GATT_WRITE_REQUEST_BUSY` or any non-`SUCCESS` code, sleep
     the next delay from `[1, 1, 1, 2, 4, 8, 12, 20, 35, 100, 200]` ms and retry
     (`AN/FaceclawBleManager.kt:29,350-366`). After the 11th retry → fail.
  3. On `SUCCESS`, block until `onCharacteristicWrite` (Android calls it for no-response writes
     once the stack has accepted the packet) or `WRITE_TIMEOUT_MS = 2000` ms
     (`KT/ConnectionOptions.kt:15`). Callback status ≠ `GATT_SUCCESS` → retry with the same delay
     table; latch timeout → fail immediately.
  4. Only then write the next frame. This is the only link-level flow control; there are no
     application credits.
* **All** `BluetoothGatt` calls in the process (connect, MTU, discovery, CCCD, write, disconnect)
  for all addresses (both temples and the ring) are serialized by one global lock
  (`AN/FaceclawBleManager.kt:447-451`). All frames of one logical message are written while holding
  it, so fragments of two messages never interleave (required: interleaving multi-fragment
  messages corrupts the firmware's reassembly, `GK/ble/docs/gotchas.md` "Write serialization").
  Consequence: `connect()` holds the lock for up to 5 s, blocking writes to the other arm.
* API note: Faceclaw calls the API-33 overload `writeCharacteristic(char, value, writeType): Int`
  and `BluetoothStatusCodes` (API 31+) unconditionally although `minSdkVersion 24`
  (`FC/App_Resources/Android/app.gradle:269`). A reimplementation must use
  `setValue()/setWriteType()/writeCharacteristic(char)` below API 33.
* Any failed logical write in the session scheduler is a transport failure → full reconnect
  (`KS/GlassesSessionSend.kt:238-246`).

### 2.4 MTU

* Request **512** on each temple immediately after connect; the boolean result is ignored
  (`KT/ConnectionOptions.kt:8`, `KS/GlassesSessionCore.kt:1753`).
* The negotiated value is recorded only from `onMtuChanged(status == GATT_SUCCESS)`; unknown ⇒ **23**
  (`AN/FaceclawBleManager.kt:208-211,487-493`).
* Usage:
  * CFW transport packetizer uses the real MTU: stream bytes per packet = `min(252, MTU − 14)`,
    packet size = that + 11 ≤ MTU − 3 (`KT/CfwTransport.kt:85`).
  * The stock envelope packetizer **always** uses a 240-byte max frame (232-byte chunks) regardless
    of MTU (`KT/BleProtocol.kt:209-217`, called with the default in `KS/GlassesSessionSend.kt:378-383`).
    This silently assumes ATT MTU ≥ 243. (See Gotchas.)
* Ring: request MTU 247 (`KT/ConnectionOptions.kt:13`).
* The MTU the glasses actually grant is not recorded in any source (g2-kit mentions "the 244-byte
  MTU we negotiate", `GK/ble/docs/envelope.md:53`). See Open questions.

### 2.5 Connection priority, PHY, connection interval

Phone side:
* `device.connectGatt(ctx, autoConnect=false, cb, TRANSPORT_LE, PHY_LE_2M | PHY_LE_1M)` (PHY mask 3)
  (`AN/FaceclawBleManager.kt:152-158`).
* `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` right after connect; fire-and-forget, no
  completion is awaited (`KS/GlassesSessionCore.kt:1748-1751`, `AN/AndroidSessionLink.kt:9-11`).
* `setPreferredPhy(2M,2M,NO_PREFERRED)` + `readPhy()` are only issued by the developer bandwidth
  benchmark (link mode bits: 1 = re-request HIGH, 2 = request 2M) (`AN/FaceclawBleManager.kt:192-206`).

Firmware side (CFW link-tuning patches, applied to stock 2.3.0.24;
`FC/app/g2/firmware/cfw-patches.ts:84-101`, identical entries in `GP/cfw_patches.json`):

| Patch | Effect |
|---|---|
| offset 1376071 `7c20→7d20` "Set Local Feature: enable LE 2M bit 8" | glasses advertise LE 2M PHY support |
| offset 4468651 `0c001800→06000600` | preferred connection interval min=max=7.5 ms (6×1.25 ms; stock min 15 ms / max 30 ms), slave latency 0 |
| offset 1057215 `0500→a325` "_connectParamReq_impl: force requested mode to fast (0xa3)" | peripheral always requests the fast parameter set; idle "slow" requests disabled |

Documented effect: 3–10× throughput, ~5 %/day extra battery (`FC/CHANGELOG:93-100`).
The stock `dev_config` command `BLE_CONNECT_PARAM` (sid 0x80, cmd 7, `BleConnectParam{f1 MTU, f2
connInterval, f3 setSpeed(0 SLOW/1 FAST)}`, `GK/ble/gen/dev_pair_manager_pb.ts:94-118`) exists but is
not used by Faceclaw.

### 2.6 Bonding, encryption and the security-auth exchange

* The glasses require an encrypted (bonded) LE link. Stock firmware ≥ 2.2.9 answers no queries until
  the sid-`0x80` AUTHENTICATION exchange completes over an encrypted link, and **closes
  unauthenticated links after ~30 s** (`KT/BleProtocol.kt:33-38`, `GF/g2flash.py:28-31`,
  `KT/OtaFlashFlow.kt:8-9`). The CFW is built on 2.3.0.24, so the same applies.
* Faceclaw never calls `createBond()`. Pairing is triggered implicitly: the auth request on an
  unencrypted link causes the glasses to require security, the OS runs SMP (possibly showing the
  system pairing dialog), and the firmware sends the auth *success* once the link is encrypted
  (`KT/BleProtocol.kt:460-466`, `KT/StockLinkSession.kt:289-298`).
* Before encryption the firmware immediately answers the auth request with a **non-success**
  result (non-empty field 3); this must not be treated as success (`KT/StockLinkSession.kt:492-497`).
  Success = `f1=4, f2=<same magic>, f3{}` (field 3 present and empty, bytes `1a 00`)
  (`KT/BleProtocol.kt:485-505`, `GF/g2flash.py:635-638`).
* Where bonding happens: onboarding's device-info probe (`KT/DeviceInfoProbeFlow.kt:38-114`), strictly
  one arm at a time, **right then left**, because Android pairs one device at a time and a second
  arm connected while the first was pairing was seen to drop (`KT/DeviceInfoProbeFlow.kt:47-57`).
  Per arm up to 3 connect+auth attempts, waiting while the OS reports `BOND_BONDING`
  (`KT/DeviceInfoProbeFlow.kt:122-169`).
* Stock-flow auth algorithm (`KT/StockLinkSession.kt:299-382`), per arm, poll every 250 ms:
  * deadline = start + 30 000 ms (`SECURITY_AUTH_TIMEOUT_MS`); hard cap 90 000 ms while `BONDING`;
  * send the request at start; if a write failed, re-send up to 3 sends total, ≥1000 ms apart;
  * while bond state is `BONDING`: do not write, do not give up (until the 90 s cap);
  * when the bond transitions to `BONDED` (and was not bonded initially): extend deadline to
    ≥ now + 6000 ms and, 2000 ms after bonding (`postBondGraceMs`), re-send the request once;
  * success = any sid-0x80 non-notify frame that `isAuthenticationSuccess` for any magic sent
    in this attempt; link drop ⇒ `LINK_DROPPED`; timeout ⇒ `UNCONFIRMED`.
* Main-session auth is **soft** (`KS/GlassesSessionCore.kt:1845-1893`): write auth to R then L
  back-to-back, wait ≤ 6000 ms (`SECURITY_AUTH_SOFT_TIMEOUT_MS`) until both are "acked" (any
  non-notify reply with sid 0x80 and matching magic resolves it — even a non-success reply), then
  log success/unconfirmed and continue regardless.
* Missing bond: after a failed connect, if either configured arm reports `BOND_NONE`
  (`isBonded()` is true unless the state is definitely `BOND_NONE`, `AN/FaceclawBleManager.kt:95-105`)
  the worker **halts** reconnecting and reports phase `unpaired` (`KS/GlassesSessionControls.kt:347-387`).

### 2.7 How the R1 ring relates

* Default ("Only via glasses", setting `developer.ringConnectionMode = "glasses"`): the ring keeps its
  own BLE link to the glasses; the phone never connects to it; the communicator is constructed with
  an empty ring address, which disables every direct-ring code path
  (`TS/ui/dashboard-settings.ts:562-574`, `TS/g2/dashboard-controller.ts:1322-1326`).
  * Ring gestures arrive from R as EvenHub SysEvents with `EventSource = 2` (TOUCH_EVENT_FROM_RING)
    (`KT/BleProtocol.kt:167-171`).
  * CFW (≥ Faceclaw/18–19, under the framebuffer lease): ring touch-down as SysEvent type 14 and the
    raw 11-byte R1 report metadata in SysEvent field 100 (`"RI",1,1,type,aux,speed,0,tickLE32`)
    (`GP/gesture_fwd.c:140-197`, `KT/G2Event.kt:78-91`). If the ring is attached to the left lens,
    the CFW relays the report over the bridge to R (bridge kind 3) (`GP/gesture_fwd.c:160-171,229-239`).
  * Ring battery: CFW settings field 106 (§5.4.6) and CFW mode 17.
* "Direct" (developer, "currently unreliable"): additionally connect phone → ring after the glasses
  session is ready and both queues are idle (`KS/GlassesSessionCore.kt:1773-1829`): connect (5 s),
  HIGH priority, MTU 247, discover, subscribe `bae80011` and `bae80013` (failure of one is tolerated,
  both failing is an error). No writes. Retry 2000 ms after a failure/disconnect
  (`KT/ConnectionOptions.kt:14`). Notifications decoded by `KT/FaceclawRingEventDecoder.kt`:
  11-byte `00 09 61 00 code paramLE16 tickLE32` (code 0 long-press, 1 tap, 2 double-tap, 4 swipe up,
  5 swipe down, 8 long-press release) or 3-byte `ff type param`.
  g2-kit's ring command protocol (`GK/ble/ring.ts:9-29`) is not used by Faceclaw.

### 2.8 Android permissions / process (context)

Runtime: `BLUETOOTH_SCAN` (neverForLocation) + `BLUETOOTH_CONNECT` on API ≥ 31, else
`ACCESS_FINE_LOCATION` (`TS/g2/android-permissions.ts:38-44`). A foreground service of type
`connectedDevice|microphone|location` keeps the process alive while connected
(`FC/App_Resources/Android/src/main/AndroidManifest.xml:113`). A partial wake lock
`Faceclaw:G2Screen` is held while the glasses screen is on (`AN/FaceclawBleCommunicator.kt:371-390`).

---

## 3. Discovery, pairing selection and presence

### 3.1 Scan

* `BluetoothLeScanner.startScan(null /* no filters */, settings, cb)`; settings: `SCAN_MODE_LOW_LATENCY`,
  report delay 0, `CALLBACK_TYPE_ALL_MATCHES`, `MATCH_MODE_AGGRESSIVE`, `MATCH_NUM_MAX_ADVERTISEMENT`
  (API ≥ 23), `setLegacy(false)` (API ≥ 26, accept extended advertising)
  (`AN/FaceclawDeviceDiscovery.kt:49-63,219-275`).
* Per result, Java builds JSON `{address, name, manufacturerData(hex), rssi, txPower, connectable,
  bonded, source:"scan", seenAtMs}` (`AN/FaceclawDeviceDiscovery.kt:66-94,141-162`):
  * `name` = live `scanRecord.deviceName`, falling back to the cached `device.name` (the cache can
    hold a stale pre-reset name).
  * `manufacturerData` = the Even record (company id `0x5245`) **with the 2 company-id bytes
    restored little-endian** (`45 52` = "ER") in front; if absent, the first record of any company
    (for diagnostics) (`AN/FaceclawDeviceDiscovery.kt:103-125`).
  * `rssi` 127 or 0 ⇒ null.
* Admission (Java pre-filter, must be a superset of TS): manufacturer data starts `45 52`, or the
  uppercase name contains `G2`, or starts with `EVEN R1` (`AN/FaceclawDeviceDiscovery.kt:127-139`).
* Bonded devices are replayed as `source:"paired"` entries with no RSSI and no manufacturer data
  (`AN/FaceclawDeviceDiscovery.kt:307-364`).
* A connected temple stops advertising; the pairing screen disconnects the session first
  (`TS/phone-ui/main-view-model.ts:902-930`).

### 3.2 G2 temple advertisement

Manufacturer-specific data (as seen after re-prepending the company id), confirmed by HCI capture
(`TS/g2/even-advertisement.ts:4-12`):

| Offset | Len | Content |
|---|---|---|
| 0 | 2 | `45 52` ("ER") — company identifier 0x5245 read LE |
| 2 | 14 | Serial number, ASCII (e.g. `S211GBBC180304`). **Identical on both temples of a pair** — this is the pair identity |
| 16 | 6 | This temple's BLE MAC, **wire order = reversed human order** (`E0 EC B6 14 12 E0` ⇄ `E0:12:14:B6:EC:E0`) |
| 22 | 1 | Flag byte (observed `01`; meaning unknown, preserved) |

Local name: `Even G2_<token>_<L|R>_<hex6>`, e.g. `Even G2_32_L_B6ECE0`. `<hex6>` = last three octets of
**that temple's** MAC (per-arm, not a pair id). Side = the name contains `_L_` xor `_R_` (both or
neither ⇒ unplaceable ⇒ rejected) (`TS/g2/even-advertisement.ts:78-92`).

Parsing rules (`TS/g2/even-advertisement.ts:46-72,226-284`):
* Serial: bytes 2..15; skip bytes ≤ 0x1F and 0x7F; any byte > 0x7E ⇒ no serial; empty ⇒ none.
* Embedded MAC: bytes 16..21 reversed; flag a mismatch vs the radio address (warning only).
* Flag: byte 22 (only when length ≥ 23 and signature present).
* Renamed firmware without `G2` in the name is admitted on the `ER` signature alone, but still needs
  an `_L_`/`_R_` marker to be classified.
* Test vector: `4552` + ASCII(`S211GBBC180304`) + `E0ECB61412E0` + `01`, name `Even G2_32_L_B6ECE0`,
  address `E0:12:14:B6:EC:E0` ⇒ left, serial S211GBBC180304, flag 1 (`FC/tests/even-advertisement.test.cjs:27-60`).

### 3.3 R1 ring advertisement

Manufacturer data `45 52` + MAC (6, wire order) + (ring fw 2.2.7.x) ASCII serial suffix (e.g. `140137`);
name `EVEN R1_<hex6>` where hex6 = last three octets of the human-order MAC
(`TS/g2/even-advertisement.ts:14-22`). Admission requires the name to match `^EVEN R1([_ ]|$)` (a bare
"R1" substring matches unrelated devices) (`TS/g2/even-advertisement.ts:226-233`). MAC extraction:
prefer bytes 2..7; if the name suffix is parseable, the candidate's wire bytes `[2],[1],[0]` must equal
the suffix octets, else search every 6-byte window, else fail closed
(`TS/g2/even-advertisement.ts:117-155`). Suffix: bytes 8.. if all in `0x30..0x5A`.

### 3.4 Pairing L and R (`TS/g2/pairing-candidates.ts`)

* `DiscoveryAggregator.ingest` (`:92-147`): classify; if classification fails, retry with the
  name/manufacturer data remembered from an earlier report of the same address (Android splits
  ADV/scan-response). Merge with the previous record (keep serial, embedded MAC, mfg data, bonded,
  latest seenAt). RSSI smoothing: exponential, `s += 0.35 × (rssi − s)` (`:74`).
* Pairs: group temples by `serialKey(serial)` (decoded normalized serial, else uppercase raw); a
  group with no serial is keyed by address. One slot per side; newest wins (`:176-190`).
  `completeness` = complete/left-only/right-only; only complete pairs are selectable (`:389`).
* Mixed-arms warning: exactly one lone-left and one lone-right with different serials ⇒ both rows get
  a mismatch message (`:217-223`, `TS/g2/glasses-hardware-identity.ts:276-325`).
* Prune entries not seen for 12 000 ms, except bonded ones (`:85,150-155`).
* "Closest" badge only if the best smoothed RSSI leads the runner-up by ≥ 8 dB (`:82,256-267`).
* On selection: save R/L (and ring) addresses and a `PairedGlassesIdentity` (serial, names, addresses,
  ring name/address, pairedAtMs) (`TS/phone-ui/pairing-view-model.ts:306-330`).

Serial decoding (`TS/g2/glasses-hardware-identity.ts:23-28,117-260`): normalize = trim, uppercase, take
leading `[0-9A-Z]*` (drops `_L_1` suffixes). `[0..2)` family: `S1` G1, `S2` G2, `B2` R1. G2 frame from
`[0..3)`: `S20|S28` A, `S21|S29` B, `S22` C. Colour `[5]`: `A` grey, `B` brown, `C` green. Unknown ⇒ null.

### 3.5 Persisted addresses

`ApplicationSettings` keys `deviceAddress.right|left|ring`, values normalized to `AA:BB:CC:DD:EE:FF`
uppercase (non-12-hex input passes through trimmed/uppercased) (`TS/g2/device-addresses.ts:13-37`,
`TS/g2/even-advertisement.ts:294-301`); identity under `deviceIdentity.*` (`TS/g2/device-addresses.ts:69-110`).
Auto-connect requires valid R and L MACs (`TS/phone-ui/main-view-model.ts:864-878`).

### 3.6 Proximity (pairing list only) and presence

* Log-distance model `d = 10^((P1m − rssi)/(10·n))`, clamp 0.1–100 m; G2 `P1m=-62 dBm, n=2.2`,
  R1 `-68, 2.3`; zones `<0.5` immediate, `<3` near, `<10` far, else distant; advertised TX power is
  deliberately ignored; confidence 0.85 × (0.4 if rssi<−85, 0.7 if <−75, 0.85 if <−65)
  (`TS/g2/ble-proximity.ts:49-140`). No proximity-based auto-connect exists.
* Presence (`TS/g2/glasses-presence.ts:1-46`): `{connected, worn|null, charging}` fed from the session
  phase, wear-state and battery callbacks; "can carry an alert" = connected ∧ worn ∧ ¬charging.

---

## 4. Stock envelope framing (`aa 21` / `aa 12`)

### 4.1 Frame layout (as implemented by Faceclaw and g2flash)

```
 byte  0     1     2      3      4          5         6     7      8 …
      AA  | 21 | seq | len  | totFrags | fragIdx | sid | flag | chunk[len]
            (TX)                                                  ↑ payload stream slice
            12 (RX)
```

| Byte | Field | TX value (phone→glasses) | RX (glasses→phone) |
|---|---|---|---|
| 0 | Start of frame | `0xAA` | `0xAA` |
| 1 | Direction/type | `0x21` | `0x12` |
| 2 | Transport sequence ("seq"): **same value on every fragment** of one logical message; a per-message counter | Faceclaw main session: global counter starting `0x40`, `+1` per logical message (both arms share it), masked `& 0xFF` (`KS/GlassesSessionCore.kt:211`, `KS/GlassesSessionSend.kt:378-383`) | chosen by glasses (ignored except as reassembly key) |
| 3 | `len` = number of bytes following the 8-byte header in this frame (chunk length, includes any CRC bytes in this chunk) | `chunk.size` | same |
| 4 | Total fragments (1..255) | | |
| 5 | Fragment index, **1-based** | | |
| 6 | sid (service id, §5.1) | | |
| 7 | flag (§4.5) | | |
| 8.. | chunk | | |

Frame length = `8 + len`. Sources: `KT/BleProtocol.kt:207-248`, `GF/g2flash.py:139-147`,
`GK/ble/envelope.ts:1-7,52-90`, parse `KT/BleProtocol.kt:960-1017`.

### 4.2 Payload stream, CRC and fragmentation

1. Build the protobuf payload `pb`.
2. `crc = CRC-16/CCITT-FALSE(pb)` — poly `0x1021`, init `0xFFFF`, no reflection, no final XOR, check
   value `CRC("123456789") = 0x29B1`, `CRC("") = 0xFFFF` (`KT/BleProtocol.kt:910-927`,
   `FC/tests/kotlin/…/ProtocolTest.kt:21-22`).
3. Stream = `pb ‖ crc_lo ‖ crc_hi` (CRC **little-endian**, covers **only the protobuf payload** — not
   the header).
4. `chunkSize = min(232, maxWrite − 8)` with `maxWrite = 240` in Faceclaw (require chunkSize ≥ 12);
   `totFrags = max(1, ceil((|pb|+2)/chunkSize))`, must be ≤ 255 (`KT/BleProtocol.kt:209-222`). Max
   payload = 255·232 − 2 = 59 158 bytes.
5. Fragment `i` (0-based) carries stream bytes `[i·chunkSize, min((i+1)·chunkSize, end))`; header
   `seq` identical for all fragments, `fragIdx = i+1`.

Equivalent variant: g2-kit/g2flash split `pb` into 232-byte chunks and append the 2 CRC bytes to the
last chunk (last chunk may then be 234 bytes) (`GK/ble/envelope.ts:52-90`); Faceclaw splits
`pb‖crc` so the CRC may straddle fragments. Both produce the same reassembled stream; the firmware
reassembles by concatenation. Use Faceclaw's scheme.

Worked example — the captured prelude (verified: CRC of the 17-byte pb is `0x42A1`):
```
aa 21 92 13 01 01 01 20 | 08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00 | a1 42
   seq=0x92 len=19 1/1 sid=01 flag=20   pb (17 bytes)                           CRC LE
```
(`GK/ble/messages.ts:411-417`, `KT/BleProtocol.kt:175-205`, `FC/tests/kotlin/…/SharedGraphicsTest.kt:13`.)

Multi-fragment example: a 300-byte pb, seq 0x46, sid e0, flag 20 →
frame 1 `aa 21 46 e8 02 01 e0 20` + 232 bytes; frame 2 `aa 21 46 46 02 02 e0 20` + 68 pb bytes + CRC (70).

### 4.3 Discrepancies with g2-kit's `envelope.md` (do NOT follow the doc)

`GK/ble/docs/envelope.md:10-46` documents `aa 21 LL LL SS FF II MM MM MM MM …` with a 2-byte length,
4-byte magic in the header and a big-endian CRC over everything from `aa`. This is wrong; g2-kit's
own code (`GK/ble/envelope.ts`, `GK/ble/crc.ts`) and all Faceclaw/g2flash code use §4.1/§4.2 (CRC over
pb only, LE, magic inside the protobuf). The doc's rule "seq is a group key, constant across fragments"
is correct.

### 4.4 Protobuf conventions: command and magic

Almost every stock message is a protobuf whose **field 1 = command id** and **field 2 = "magicRandom"
(request id, uint32 varint)**; the reply echoes field 2 (`GK/ble/gen/*` `…Package{commandId=1,
magicRandom=2,…}`, `KT/BleProtocol.kt:813-820`). The glasses match replies by (sid, magic).

Magic allocation in the Faceclaw main session — `BleMagicPool` (`KT/BleMagicPool.kt:1-44`):
* values **100..255** (156 values), LRU: `allocate()` takes the head of a deque initialised
  `[100..255]`, `release()` appends to the tail; allocating from an empty pool throws.
* The same pool supplies CFW stream ids (§7). Released records `{label, reason, releasedAt}` are kept
  per (sid, magic) to classify late/duplicate acks (`KS/GlassesSessionSend.kt:1289-1315`).
* Fixed magics: prelude uses **156** (not allocated from the pool); CFW controls on sid 0x09 use
  **0** (fire-and-forget, never tracked).
* Magic ≥ 128 is a 2-byte varint (e.g. 200 → `c8 01`). g2-kit reports that values ≥ 256 are dropped by
  some handlers (`GK/ble/docs/envelope.md:73-86`); stay ≤ 255.
* Stock flows (`StockLinkSession`) instead cycle 100..255 and use seq starting 0x40
  (`KT/StockLinkSession.kt:108-110,167-179`). g2flash uses its (1-based) seq as the auth magic and
  encodes it as one raw byte (`GF/g2flash.py:151-161`) — only valid for magic < 128.

### 4.5 Flags

| Value | Meaning | Direction | Source |
|---|---|---|---|
| `0x20` | Request (FLAG_REQUEST) — used for all stock requests except auth | TX | `KT/BleProtocol.kt:131` |
| `0x00` | Security-auth request (sid 0x80); CFW packets (sid 0xF0); OTA (sid 0xC0/0xC1) | TX | `KT/BleProtocol.kt:41`, `KT/CfwTransport.kt:77-116`, `KT/OtaFlashFlow.kt:36` |
| `0x00` | Response/ack; also used by some unsolicited stock/CFW frames (see below) | RX | `GK/ble/envelope.ts:28-31` |
| `0x01` | Async notification (FLAG_NOTIFY) | RX | `KT/BleProtocol.kt:133` |
| `0x06` | Async notification, alternate (FLAG_NOTIFY_ALT) | RX | `KT/BleProtocol.kt:135` |
| `0x02` | Observed 8-byte "abort" frame on sid 0xE0 after broken reassembly (g2-kit only) | RX | `GK/ble/envelope.ts:75-79` |

Unsolicited frames that arrive with flag **0x00** and must not be treated as stray acks: the stock
wear event (sid 0x10), CFW field-102 wake/gesture events (sid 0x09, magic 0), device settings pushes
(sid 0x09, commandId 3, device-chosen magic) (`KT/BleProtocol.kt:723-744,762-787`, `GP/settings_ext.c:173-206`).

### 4.6 Inbound parsing

Notification values on `5402` normally hold exactly one frame, but the firmware can pack several
back-to-back (each self-delimited by `len`) and some Android stacks truncate long values (observed on a
Samsung tablet for values > 64 bytes) (`KT/BleProtocol.kt:929-958`).

Three implementations exist:
1. **Faceclaw main session (Android and iOS)**: `parseFrame(value)` of the *first* frame only; `pb` =
   bytes `[8, 8+len)` including any trailing CRC; no CRC validation; no multi-fragment reassembly;
   `msgType`/`msgSeq` = top-level f1/f2 varints found among the first 8 fields (skips wire-type-2 fields,
   stops at other wire types) (`KT/BleProtocol.kt:960-1017`). Parsers call `stripTrailingCrc`
   (drop last 2 bytes) before decoding (`KT/BleProtocol.kt:1019-1025`). Works because all replies
   Faceclaw consumes fit one frame.
2. **Stock flows**: `splitFrames(value)` then per-frame `parseFrame` (`KT/StockLinkSession.kt:420-440`).
3. **`MessageReceiver`** (used by the iOS TS layer and tests) — the most correct; **recommended**
   (`KT/MessageReceiver.kt:1-127`):
   * per-link byte buffer; append each notification; resync by skipping bytes until `aa 21|12`;
   * wait until `8+len` bytes are available;
   * sid `0xF0` frames are passed through whole (CFW ACKs, §7.7);
   * otherwise reassemble keyed by `link:seq:sid:flag`; `fragIdx==1` starts a new partial; a fragment
     that is not the next index, or whose `totFrags` differs, drops the partial; invalid
     (`count==0 || idx==0 || idx>count`) drops it; total > 65 536 bytes drops it; partials idle
     > 5000 ms are purged;
   * on the last fragment: last 2 bytes = CRC LE over the rest; drop on CRC mismatch or if the
     payload is not structurally valid protobuf (every key's field number ≥ 1, wire types 0/1/2/5,
     lengths in range, varints ≤ 5 bytes);
   * emit `(sid, flag, payload-without-CRC, command=f1 or −1, magic=f2 or −1)`.

### 4.7 Ack correlation (main session)

* A received frame is an **ack candidate** iff: parsed OK, not a wear event, not a CFW wake
  notification, not a settings silent-mode push, `msgSeq ≥ 0`, and flag ∉ {0x01, 0x06}
  (`KS/GlassesSessionCore.kt:1490-1498`).
* Match: first in-flight message with the same **sid and magic**, from **any** arm
  (`KS/GlassesSessionSend.kt:501-511`). On match: remove from in-flight, store the reply pb (with CRC)
  as `ackPayload`, release the magic, run `onAck`, reset `consecutiveAckTimeouts`, signal waiters
  (`KS/GlassesSessionSend.kt:513-529`).
* No match: if magic ∈ 100..255, log as "late ACK after timeout", "duplicate ACK" (possible Even-app
  contention), "late ACK for released message", or "unexpected ACK"; never applied
  (`KS/GlassesSessionSend.kt:1289-1315`).
* The reply's command (f1) and flag are not checked. Replies seen: EvenHub responses use the paired
  "OS_RESPONSE_*" command (§5.5); settings replies carry `f4`; auth replies `f1=4`.

### 4.8 More worked TX frames (computed with the rules above; seq values arbitrary)

| Message | Frame bytes |
|---|---|
| Heartbeat, magic 150, seq 0x40 | `aa 21 40 0b 01 01 e0 20` `08 0c 10 96 01 72 02 08 00` `81 b6` |
| Settings read, magic 101, seq 0x41 | `aa 21 41 0a 01 01 09 20` `08 02 10 65 22 02 08 01` `c6 42` |
| Security auth, magic 100, seq 0x42 | `aa 21 42 0c 01 01 80 00` `08 04 10 64 1a 04 08 01 10 04` `71 9c` |
| CFW FB-lease ACQUIRE (settings f101), seq 0x43 | `aa 21 43 0f 01 01 09 20` `08 01 10 00 aa 06 06 46 43 01 05 00 00` `29 26` |
| EvenHub shutdown exitMode 0, magic 102, seq 0x45 | `aa 21 45 0a 01 01 e0 20` `08 09 10 66 5a 02 08 00` `ac 30` |
| Create layout, magic 101, seq 0x44 | `aa 21 44 2b 01 01 e0 20` `08 00 10 65 1a 23 08 01 1a 1c 08 00 10 00 18 c0 04 20 a0 02 48 01 52 09 64 61 73 68 62 6f 61 72 64 58 01 62 01 20 28 90 4e` `2f 48` |

Field keys for the CFW extension fields (wire type 2): f100 `a2 06`, f101 `aa 06`, f102 `b2 06`,
f103 `ba 06`, f104 `c2 06`, f105 `ca 06`, f106 `d2 06`.

---

## 5. Service IDs and message catalog

### 5.1 SID table

Stock enum from `GK/ble/gen/service_id_def_pb.ts:17-185`, plus usage.

| sid | Enum name | Faceclaw use | Direction | Notes |
|---|---|---|---|---|
| `0x00` | UI_DEFAULT_APP_ID | Faceclaw's `SID_STATE_CHANGE` constant (**suspect**, see §5.7) | RX | `KT/BleProtocol.kt:51` |
| `0x01` | UI_BACKGROUND_DASHBOARD_APP_ID | session prelude (g2-kit calls it "app-launch") | TX/RX ack | §5.3 |
| `0x03` | UI_FOREGROUND_MEUN_ID | — | | |
| `0x04` | UI_FOREGROUND_NOTIFICATION_ID | — | | |
| `0x05` | UI_TRANSLATE_APP_ID | — | | |
| `0x06` | UI_TELEPROMPT_APP_ID | — | | |
| `0x07` | UI_FOREGROUND_EVEN_AI_ID | Even AI status events (wakeword) | RX | `KT/BleProtocol.kt:113`, `KT/G2Event.kt:16-30` |
| `0x08` | UI_BACKGROUND_NAVIGATION_ID | compass heading notifications (cmd 15/16/17) | RX (R) | `KT/BleProtocol.kt:115-121,1027-1086` |
| `0x09` | UI_SETTING_APP_ID | settings read/write/push + CFW fields 100–106 | TX/RX | §5.4 |
| `0x0A`–`0x0C` | transcribe, conversate, quicklist | — | | |
| `0x0D` | SERVICE_SYNC_INFO_APP_ID | g2-kit "state-change" async bus (display wake etc.) | RX | §5.7 |
| `0x0E` | UI_HEALTH_APP_ID | (g2-kit calls 0x0e "widget-transform") | | |
| `0x0F` | UI_LOGGER_APP_ID | — | | |
| `0x10` | UI_ONBOARDING_APP_ID | wear state events | RX | §5.6 |
| `0x20`–`0x22` | module configure, system alert, system close | — | | |
| `0x80` | UX_DEVICE_SETTINGS_APP_ID (`dev_config` protocol) | security auth | TX/RX | §5.2 |
| `0x81` | UX_GLASSES_CASE_APP_ID | — | | |
| `0x90` / `0x91` | UX_RING_ROW_DATA_ID / UX_RING_DATA_RELAY_ID | — | | |
| `0xC0` / `0xC1` | UX_OTA_TRANSMIT_CMD_ID / UX_OTA_TRANSMIT_RAW_DATA_ID | OTA ctrl/data (on OTA characteristic) | TX | `KT/OtaFlashFlow.kt:34-44` |
| `0xC2`–`0xC7` | OTA export, EFS | — (g2-kit labels `0xC5` "Android notification JSON") | | `GK/ble/envelope.ts:34-42` |
| `0xE0` | UI_BACKGROUND_EVENHUB_APP_ID | EvenHub page/heartbeat/audio/IMU + input events | TX/RX | §5.5 |
| `0xF0` | (not stock) | **CFW private transport** | TX / RX ACK | §7 |
| `0xFF` | INVALID_SERVICE_ID | | | |

g2-kit's `envelope.ts` SID labels (`0x09` "firmware status/version telemetry", `0x80` "heartbeat +
settings queries") are informal/misleading; use the enum above.

### 5.2 sid 0x80 — security authentication (`dev_config` protocol)

Schema `DevCfgDataPackage{f1 commandId(eDevCfgCommandId), f2 magicRandom, f3 authMgr(AuthMgr), …,
f10 setDeviceInfo, f11 getDeviceInfo, f13 baseHeartBeat, f128 timeSync, f129 AudControl}`,
`eDevCfgCommandId`: 4 AUTHENTICATION, 7 BLE_CONNECT_PARAM, 12 GET_DEVICE_INFO, 14 BASE_CONNECT_HEART_BEAT,
128 TIME_SYNC, 129 AUD_CONTROL, 255 COMMAND_ERROR (`GK/ble/gen/dev_config_protocol_pb.ts:46-232`).
`AuthMgr{f1 secAuth, f2 phoneType(eDevice: 3 PHONE_IOS, 4 PHONE_ANDROID), f3 result}`
(`GK/ble/gen/dev_pair_manager_pb.ts:168-219`).

Request (sid 0x80, flag **0x00**): `f1=4, f2=magic, f3{ f1=1, f2=4 }` →
`08 04 10 <magic> 1a 04 08 01 10 04` (`KT/BleProtocol.kt:460-483`, identical bytes in `GF/g2flash.py:158`).
Note: `secAuth` is declared `bytes` in g2-kit's schema, but the tested request encodes it as varint 1
(wire type 0). Send exactly these bytes. `phoneType = 4` (Android) is sent on iOS too.

Success reply: `f1=4, f2=<magic>, f3{}` i.e. `08 04 10 <magic> 1a 00` (+CRC). Non-success (e.g.
`f3{f1=1}`) is the pre-encryption answer (`FC/tests/kotlin/…/StockFlowsTest.kt:60-61`). The lens also
emits periodic sid-0x80 *notify* frames with device-chosen magic; ignore them.

Time sync: `TimeSync{f1 timestamp uint32, f2 timezone int32, f3 result}` via cmd 128/field 128
(`GK/ble/gen/dev_settings_pb.ts:45-59`) exists; **Faceclaw never sends time sync** (the phone renders
all time displays into frames). `GET_DEVICE_INFO` is likewise unused; "device info" in Faceclaw means
the sid-0x09 settings read (§5.4).

### 5.3 sid 0x01 — session prelude (mandatory)

Payload = `DashboardDataPackage{f1 commandId=2 (Dashboard_Receive), f2 magicRandom=156, f4
dashboardReceive{ f3 bashboardConfig{ f2 widgetComponents{ f2 stock{ f1 stockTotal=0, f2 stockNum=0 }}}}}`
= `08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00` (`KT/BleProtocol.kt:181-205`;
decoding from `GK/ble/gen/dashboard_pb.ts:915-950,249-264,416-436,643-663`). g2-kit calls it
"AppLaunchRequest{type:2}" — a misnomer.

Sent to **R**, sid 0x01, flag 0x20, fixed magic 156, with a fresh transport seq; ack = any non-notify
sid-0x01 frame with f2 = 156. Required once per BLE session before EvenHub traffic is accepted, and again
before re-creating the EvenHub page after a `Cmd 9` shutdown (`GK/ble/docs/sids.md:18-40`,
`KS/GlassesSessionCore.kt:1234-1238`).

### 5.4 sid 0x09 — settings (`G2SettingPackage`)

Schema (`GK/ble/gen/g2_setting_pb.ts:542-702`):
`G2SettingPackage{f1 commandId, f2 magicRandom, f3 deviceReceiveInfoFromApp, f4 deviceReceiveRequestFromApp,
f5 deviceSendInfoToApp, f6 deviceRespondToApp, f7 appRespondToDevice}`;
commandId: 1 DeviceReceiveInfo (app→device set), 2 DeviceReceiveRequest (read), 3 DeviceSendToAPP (push),
4 DeviceRespondToAPP, 5 AppRespondToDevice. (g2-kit's `settings.md:8-20` "wire shape" with battery=1,
brightness=2… is wrong; use the generated schema.)

#### 5.4.1 Read ("battery query", also firmware-info probe)
TX `f1=2, f2=magic, f4{ f1=1 (APP_REQUIRE_BASIC_SETTING) }` = `08 02 10 <magic> 22 02 08 01`
(`KT/BleProtocol.kt:507-517`), to R, flag 0x20, ack timeout 3500 ms.

RX reply (commandId not checked): `f2=magic`, `f4 DeviceReceiveRequestFromAPP{f1 settingInfoType,
f2 autoBrightnessLevel, f3 y, f4 x, f5 leftSoftwareVersion (string), f6 rightSoftwareVersion (string),
f7–f9 head-up, f10 wearDetectionSwitchRestored, f11 deviceRunningStatus, f12 battery (0–100),
f13 chargingStatus (>0 = charging), f14 silentModeSwitchRestored, f15–f19 …}`
(`GK/ble/gen/g2_setting_pb.ts:197-291`), plus CFW-appended top-level fields (§5.4.6).
Faceclaw extracts: battery/charging/silent (`KT/BleProtocol.kt:746-760`), versions f4.f5/f4.f6 and
extension f100 (`KT/BleProtocol.kt:789-811`), ring battery f106, mic status f104.

The response buffer in firmware is 256 bytes (`GP/settings_ext.c:26-31,474`).

#### 5.4.2 Stock sets (DeviceReceiveInfo)
* Wear detection: `f1=1, f2=magic, f3{ f5{ f1=0|1 } }` (`KT/BleProtocol.kt:447-458`) — used.
* Brightness: `f1=1, f2=magic, f3{ f1{ f1 autoAdjust=1 } | f1{ f2 level } }` (oneof; stock clamps 2..100)
  (`KT/BleProtocol.kt:424-445`) — builder exists but Faceclaw uses CFW mode 30 instead.

#### 5.4.3 Device pushes
Silent mode (wearer long-presses both touchpads): `f1=3, f2=<device magic>, f5{ f2 silentModeSwitch }`
(`KT/BleProtocol.kt:762-787`). The "on" push is confirmed, the "off" push is not; every settings read
re-reads f4.f14 as a backstop (`KS/GlassesSessionSend.kt:940-945`).

#### 5.4.4 Firmware versions / CFW revision string (field 100)
Top-level **field 100, wire type 2, string `"Faceclaw/<n>"`** appended by the CFW to every sid-0x09
settings response (`GP/settings_ext.c:4-31,476-487`). Current firmware sends **`Faceclaw/34`**
(`GP/settings_ext.c:480`; the compiled patch set in `FC/app/g2/firmware/cfw-patches.ts` contains the
bytes `46616365636c61772f3334`). Stock firmware never sends field 100. Older CFW sent
`"EVENCFW/<ver> <feature tokens>"`. See §7.13 for how it is judged.

#### 5.4.5 Settings-read cadence
First session of a communicator instance: queued immediately after session-ready (before the layout is
created) to learn firmware info (`KS/GlassesSessionCore.kt:1706-1717`). Afterwards (`KS/GlassesSessionSend.kt:919-926`):
only when not suspended, session ready, both queues empty, ≥ 5000 ms since the last input event or
session start (`BATTERY_INPUT_QUIET_MS`), and (never polled this session or ≥ 300 000 ms since the
last poll). In charging mode: every 30 000 ms (`KT/ConnectionOptions.kt:29-33`).

#### 5.4.6 CFW private settings fields (all top-level fields of `G2SettingPackage`)

| Field | Dir | Content | Source |
|---|---|---|---|
| 100 | RX (in every settings response) | string `Faceclaw/<n>` | `GP/settings_ext.c:476-487` |
| 101 | TX | control record `'F','C',ver=1,op,nonceLo,nonceHi` (§7.12) | `GP/settings_ext.c:33-43,316-359` |
| 102 | RX (R only, flag 0x00, `f1=3, f2=0`) | wake event `'F','C',1,event(1 double-tap / 5 head-up),nonceLE16` or idle gesture `'F','C',1,event(2 tap/3 long/4 release),rawSource,0` | `GP/settings_ext.c:45-61,173-206` |
| 103 | TX | mic control `'M','C',ver,op,…` | `KT/BleProtocol.kt:62-69,544-559` |
| 104 | RX (both temples; in reads and standalone pushes) | mic status, 21 bytes `'M','C',ver,…` | `KT/BleProtocol.kt:561-579` |
| 105 | RX (master lens) | ALS report, 24 bytes `'A','L',1,reason,…` | `KT/BleProtocol.kt:71-76,581-599` |
| 106 | RX (reads; mode-17 notify from R) | ring battery `'R','B',1,flags,level`; flags bit0 connected, bit1 valid, bit2 charging; level 255 = unknown | `GP/ring_battery.c:17-33`, `KT/BleProtocol.kt:601-629` |

Controls sent in fields 101/103 use **magic 0** and a normal `f1=1` (DeviceReceiveInfo) wrapper; the CFW
scans the raw protobuf before the stock nanopb decoder, which then ignores the unknown field
(`GP/settings_ext.c:361-403`). They are fire-and-forget (not tracked for acks).

### 5.5 sid 0xE0 — EvenHub (list only; display details are in another spec)

`evenhub_main_msg_ctx{f1 Cmd, f2 MagicRandom, <one command-specific field>}` (`GK/ble/gen/EvenHub_pb.ts:16-130`).
The command enum value and the payload field number **differ** — do not confuse them (g2-kit's
`audio.md:18-34` calls the audio command "Cmd=18"; 18 is the field, the Cmd is 15):

| Cmd | Name | Payload field | Faceclaw use |
|---|---|---|---|
| 0 | APP_REQUEST_CREATE_STARTUP_PAGE_PACKET | f3 CreateMessage | create the input layout (text container "dashboard", id 1, 0,0,576×288, capture=1, content " ", widgetId 10000) (`KT/BleProtocol.kt:250-261`) |
| 1 | OS_RESPONSE_CREATE_STARTUP_PAGE_PACKET | f4 | RX |
| 2 | OS_NOITY_EVENT_TO_APP_PACKET | f13 DevEvent {f1 ListEvent, f2 TextEvent, f3 SysEvent} | RX input events (decoded on R only) (`KT/G2Event.kt:31-93`) |
| 3 | APP_UPDATE_IMAGE_RAW_DATA_PACKET | f5 ImgRawMsg | not used (images go via CFW) |
| 4 | OS_RESPONSE_IMAGE_RAW_DATA_PACKET | f6 | |
| 5 | APP_UPDATE_TEXT_DATA_PACKET | f9 TextUpgrade | builder only (dead code) |
| 6 | OS_RESPONSE_TEXT_DATA_PACKET | f10 | |
| 7 | APP_REQUEST_REBUILD_PAGE_PACKET | f7 | not used |
| 8 | OS_RESPONSE_REBUILD_PAGE_PACKET | f8 | |
| 9 | APP_REQUEST_SHUTDOWN_PAGE_PACKET | f11 {f1 exitMode} | suspend EvenHub / legacy teardown (`KT/BleProtocol.kt:404-407`) |
| 10 | OS_RESPONSE_SHUTDOWN_PAGE_PACKET | f12 | |
| 11 | OS_PRIVATE_EVENT_PACKET | f16 | |
| 12 | APP_REQUEST_HEARTBEAT_PACKET | f14 {f1 Cnt=0} | heartbeat (`KT/BleProtocol.kt:294-297`) |
| 13 | OS_RESPONSE_HEARTBEAT_PACKET | f15 | RX |
| 14 | OS_PRIVATE_SYSTEM_EVENT_PACKET | f17 | |
| 15 | APP_REQUEST_AUDIO_CTR_PACKET | f18 {f1 enable 0/1} | mic on/off (`KT/BleProtocol.kt:394-402`) |
| 16 | OS_RESPONSE_AUDIO_CTR_PACKET | f19 | |
| 17 / 18 | menu startup notify / failed | f20 / f21 | |
| 19 | APP_REQUEST_OPEN_IMU_PACKET | f22 {f1 IMUReportEn, f2 reportFrq (only when enabling)} | IMU stream (`KT/BleProtocol.kt:409-422`) |
| 20 | OS_RESPONSE_IMU_PACKET | f23 | |

Enum source: `GK/ble/gen/EvenHub_pb.ts:1063-1180`. SysEvent `{f1 EventType, f2 EventSource
(1 R, 2 ring, 3 L), f3 IMUData{f1,f2,f3 as fixed32 float, despite the schema's double}, f4
systemExitReasonCode, f100 CFW ring metadata}` (`KT/BleProtocol.kt:1205-1262`, `KT/G2Event.kt:55-91`).
SysEvent FOREGROUND_EXIT(5)/ABNORMAL_EXIT(6)/SYSTEM_EXIT(7) mean the EvenHub page is gone: the session
marks the layout as not created and clears both queues (`KS/GlassesSessionCore.kt:1529-1537`).

### 5.6 sid 0x10 — wear state (onboarding)

`OnboardingDataPackage{f1 commandId=3 (EVENT), f2 magic, f5 OnboardingEvent{f1 event=1 (GLS_WEAR_STATUS),
f2 eventParam 0|1}}` (`GK/ble/gen/onboarding_pb.ts:84-197`). **Stock sends it with flag 0x00**; recognise by
sid and shape, not flag (`KT/BleProtocol.kt:723-744`). The CFW also emits it on demand (WEAR_QUERY, op 7)
and outside onboarding: bytes `08 03 10 00 2a 04 08 01 10 <0|1>` via the notify sender
(`GP/settings_ext.c:238-257`).

### 5.7 sid 0x0D — sync-info / "state change" (display wake)

g2-kit: async cross-subsystem state bus on sid 0x0D (`GK/ble/docs/sids.md:13`, `GK/ble/events.ts:124-131`).
The enum names 0x0D `SERVICE_SYNC_INFO_APP_ID` with `sync_info_main_msg_ctx{f1 cmdList (0 APP_REQUEST_SYNC_INFO,
1 OS_NOTIFY_SYNC_INFO), f2 magic, f3 dataMsg{f1 backgroundAppID, f2 foregroundAppID}}`
(`GK/ble/gen/sync_info_pb.ts:16-74`).
Faceclaw's "display wake" recogniser expects **`f1=1, f3{f1=1}`** (exactly 2 bytes inside f3) with a notify
flag from R, i.e. OS_NOTIFY_SYNC_INFO with backgroundAppID = 1 (dashboard) — and its KDoc says
"sid=0x0d" — **but the constant it compares against is `SID_STATE_CHANGE = 0x0`**
(`KT/BleProtocol.kt:51,1088-1113`). As built, the check can only match sid 0x00. Recommended: match
sid 0x0D (see Open questions). This path is only a stock-firmware fallback; with the CFW wake lease
the field-102 wake event (§7.12) is used.

### 5.8 Other RX notifications consumed

* sid 0x07 Even AI: `f1=1 (CTRL)`, `f3{ f1 status }` (1 WAKE_UP "Hey Even", 2 ENTER, 3 EXIT) → TS event
  kind `even-ai` (`KT/G2Event.kt:16-30`, `TS/g2/events.ts:21-40`).
* sid 0x08 navigation: cmd 15 COMPASS_CHANGED with `f10{f1 heading 0..359}` and CFW diagnostic f100
  (12 bytes `'C','M',1,…`), cmds 16/17 calibration started/complete; decoded from R only
  (`KT/BleProtocol.kt:117-121,1027-1086`).

### 5.9 OTA (context only)

On the OTA characteristic: sid 0xC0 control `[op, …]` (0 BEGIN, 1 FILE_CHECK + 128-byte subheader,
2 block marker, 3 END), sid 0xC1 data (4096-byte blocks, ≈18 frames), flag 0x00; each acked on `0002` as
`[opcode, status]` (0 OK, 7 CHECK_FAIL, 8/9 OK at END…). A block marker and its data frames share one
seq and must be written as one uninterrupted transaction. Auth (sid 0x80 on the control channel) must
succeed first; no control traffic during the transfer; lenses flashed left then right
(`KT/OtaFlashFlow.kt:1-44,230-311`, `GF/g2flash.py:18-35,139-149,640-712`).

---

## 6. Session lifecycle (main session: `GlassesSessionCore`)

### 6.1 Components and threads

```
 TS main thread ──(FaceclawCommunicatorBridge: FIFO promise queue)──► FaceclawBleCommunicator (facade)
                                                                         │ owns
      ┌──────────────────────────────────────────────────────────────────┤
      ▼                                                                  ▼
 GlassesSessionCore (platform-neutral)                       AndroidSessionLink → FaceclawBleManager
   • worker thread "FaceclawBleCommunicator": run() loop       (global GATT lock, per-op latches)
     – connect sequence, driveSession() scheduler,             GATT callbacks on binder threads:
       ALL scheduled writes                                      onCharacteristicChanged → core.onNotification
   • monitor (reentrant lock + condition) guards state          onConnectionStateChange → core.onConnectionStateChange
   • InterruptibleSleep: worker naps; interrupt() on new work
   • listener callbacks posted to the Android main looper
```
Sources: `AN/FaceclawBleCommunicator.kt:52-125`, `KS/SessionPorts.kt:15-122`,
`KS/GlassesSessionCore.kt:1-31,1267-1312`, `FC/native/…/util/Concurrency.kt:9-40`.

`SessionLink` (the GATT boundary; all calls block with explicit timeouts):
`connect(addr, ms)`, `requestHighPriority(addr)`, `requestMtu(addr, mtu, ms)`, `negotiatedMtu(addr)`,
`discoverServices(addr, ms)`, `enableNotifications(addr, uuid, enable, ms)`,
`writeFrames(addr, uuid, frames, mode, ms)`, `disconnect(addr)`, `close()`, `isBonded(addr)`,
`prepareBenchmarkLink(addr, mode)`, `recordDisplayFrameSent()` (`KS/SessionPorts.kt:15-49`).

### 6.2 Connect sequence (`connectLoopOnce`, `KS/GlassesSessionCore.kt:1655-1728`)

| Step | Action | Timeout / rule | Failure |
|---|---|---|---|
| 0 | phase `connecting` | | |
| 1 | `connectArm(R)`: `connectGatt` (returns immediately if a GATT client already exists for the address) | 5000 ms (`CONNECT_TIMEOUT_MS`); on timeout `disconnect()+close()` the GATT | throw |
| 1a | `requestConnectionPriority(HIGH)` | no wait | — |
| 1b | `requestMtu(512)` | 5000 ms; result ignored | — |
| 1c | `discoverServices()` | 5000 ms, status must be GATT_SUCCESS | throw |
| 1d | enable notify `5402` (setCharacteristicNotification + CCCD write `01 00`) | 5000 ms (`DESCRIPTOR_TIMEOUT_MS`) | throw |
| 1e | enable notify `6402` | 5000 ms | ignored |
| 2 | `connectArm(L)` — same | | throw |
| 3 | settle 800 ms (interruptible by disconnect) | `KS/GlassesSessionCore.kt:1730-1742`; g2-kit also settles 800 ms (`GK/ble/session.ts:70`) | abort quietly |
| 4 | security auth R then L (soft, §2.6) | ≤ 6000 ms total | continue |
| 5 | prelude to R (clears both queues first) | ≤ 2000 ms (`PRELUDE_TIMEOUT_MS`) | throw "prelude ack timeout" |
| 6 | **session ready**: `sessionReady=true`, `shutdownRequested=false`, `fixedLayoutCreated=false`, clear queues, reset all timers/counters, `lastHeartbeatAckedAtMs=0`, **reset both CFW transports**, enqueue FB-lease ACQUIRE (op 5) to R,L at the queue head; if the wake lease is wanted, enqueue wake ACQUIRE (op 1) to R,L at the head (ends up before the FB lease) | | |
| 7 | phase `connected`; if first session of this instance: enqueue settings read at the tail | | |
| 8 | `tryConnectRing("initial")` if direct ring configured | failures only reschedule the ring | |
| Any throw | if either arm is `BOND_NONE` → `handleUnpairedFailure` (halt, phase `unpaired`), else `handleTransportFailure("connect failed")` (retry in 2000 ms) | | |

Per-arm order is always R then L (connect, auth, lease controls); disconnect is R then L then ring.

### 6.3 Steady state: the scheduler (`driveSession`, `KS/GlassesSessionSend.kt:17-248`)

Single worker thread; each pass (all decisions under the monitor, the BLE write outside it):

1. Drain in-order fully-acked CFW messages (§7.9). If the CFW cleanup (mode 11) has been acked: discard
   anything pending and sleep 250 ms (nothing may be written after cleanup).
2. **FB lease renewal**: if session ready and ≥ 45 000 ms since the last FB ACQUIRE was queued and none is
   pending/in flight → enqueue ACQUIRE to R,L (tail) (`FACECLAW_WAKE_LEASE_RENEW_MS`, `KS/GlassesSessionCore.kt:44`).
3. **Wake lease renewal**: same rule if the wake lease is enabled.
4. If anything is in flight:
   * CFW replay check (§7.9) → requeue failed window at the head, loop.
   * else if the **oldest** in-flight message's deadline has passed → remove it, release magic, reset the
     CFW transport of that arm if it was a CFW message, `handleAckTimeoutLocked` (§6.6), loop.
5. **Charging mode** (§6.8): discard desired frames; if nothing in flight write the next pending message;
   else if due write a battery read; else sleep 1000 ms.
6. Otherwise:
   * layout not created and both queues empty and not suspended → enqueue **create-layout**
     (Cmd 0; ack ⇒ `fixedLayoutCreated=true`; timeout ⇒ transport failure) (`KS/GlassesSessionSend.kt:585-612`).
   * layout created, queues empty, debug-overlay desired state changed → enqueue CFW mode 7.
   * layout created, queues empty, compass desired state changed → enqueue CFW mode 10.
   * brightness policy / benchmark maintenance (may enqueue CFW messages).
   * `windowHasRoom = inFlight.size < WINDOW_SIZE (3)` (`KT/ConnectionOptions.kt:52-57`).
   * **heartbeat** decision (§6.5); may write a heartbeat or block this pass.
   * if room and pending non-empty and `CfwMessageWindow.canSend(head)` → write the head.
   * else if the display path is ready (layout created, not suspended, not in image-retry backoff, no image
     queued, window has room) and the desired frame differs from the last enqueued one → plan and enqueue
     the frame's CFW messages, loop.
   * else if the settings read is due → write it.
   * else sleep 100 ms (work outstanding) or 250 ms (idle).
7. `writeMessage` (`KS/GlassesSessionSend.kt:342-408`):
   * provisional deadline `now + ackTimeout + 2000`; **insert into in-flight before writing** (only if
     `magic ≠ 0`) so a fast ack can match;
   * target = L if `isLeftArmMessage` else R;
   * CFW (sid 0xF0): if it is a replay (`cfwRetries > 0`) reset that arm's transport first; frames =
     `cfwTransports[arm].encode(msg, streamId=magic, lenses=3, mtu=negotiatedMtu(target))`;
   * stock: `framePb(msg, sid, flag, nextTransportSeq++)`;
   * write all frames (`writeFrames`, 2000 ms per frame);
   * on return: `sentAt = now`, deadline = `sentAt + ackTimeout`; on success run `onSent`;
   * on a failed CFW write reset that arm's transport; any failed write ⇒ transport failure.

Legacy serial "prewrite" (all-but-last frame written early) only runs when `WINDOW_SIZE ≤ 1` and only for
stock image messages; it is dead with the current configuration (`KS/GlassesSessionSend.kt:115-125,270-286,410-473`).

### 6.4 Queue and window rules (flow control)

* Two queues: `pendingMessages` (deque; "priority" items are `addFirst`) and `inFlightMessages` (ordered by
  write time) (`KS/GlassesSessionCore.kt:310-312`).
* **At most 3 tracked messages in flight** (all kinds together; stock and CFW) — pipelining.
  Fire-and-forget messages (magic 0: lease/mic/wear controls) are never in flight and don't use the window.
* GATT writes are strictly sequential (one frame at a time, global lock).
* Heartbeat is a barrier: sent only when nothing is in flight, and while it is pending nothing else is sent
  ("can lead to inter-lens sync issues") (`KS/GlassesSessionSend.kt:288-319`).
* CFW eviction barrier: a CFW message whose first byte is 22 (EVICT_RESOURCE) is only sent when no CFW
  message is in flight (`KT/CfwMessageWindow.kt:13-19`).
* Suspended (`shutdownRequested`): no heartbeats, no layout, no images, no battery polls; lease renewals,
  explicitly queued controls and wake CLAIM/READY still flow.

### 6.5 Heartbeat

* Message: EvenHub Cmd 12, `f14{f1=0}`, to R, flag 0x20, pool magic, **ack timeout 1500 ms**
  (`KT/MessageBuilder.kt:5-7,205-218`).
* Eligible only when the layout exists and the page is not suspended (heartbeats before the first CREATE are
  wasted: `GK/ble/docs/gotchas.md` "Heartbeat").
* Timing is ack-age based, not a fixed interval (`KT/ConnectionOptions.kt:26-28`, `KS/GlassesSessionSend.kt:288-319`):
  `elapsed = now − lastHeartbeatAckedAtMs`
  * `elapsed ≥ 4000` (READY) and none pending and nothing in flight → send, unless an image is waiting to
    be sent and `elapsed < 6000` (defer: the image's ack also refreshes the firmware keepalive);
  * `elapsed ≥ 6000` (URGENT) → block all other sends until the heartbeat can go;
  * heartbeat pending/in flight → block other sends.
  * The first heartbeat goes out immediately after the layout is created (`lastHeartbeatAckedAtMs = 0`).
* `lastHeartbeatAckedAtMs` is also refreshed by every acked CFW **image** message and benchmark no-op,
  because the CFW resets the stock EvenHub keepalive on every private message
  (`KS/GlassesSessionSend.kt:869-876`, `GP/zlib_glue.c:378-390`).
* Firmware side: the stock EvenHub keepalive counter is incremented every tick and tears the page/plugin
  task down ("Connection lost") once it passes 899; it is reset by the sid-0xE0 heartbeat and (CFW) by any
  sid-0xF0 message (`GP/zlib_glue.c:209-218,378-390`). g2-kit observes ≈ 10 s and uses a 5 s interval with
  1.5 s ack wait (`GK/ui/heartbeat.ts:1-9,46-47`).
* Heartbeat timeout: `onTimeout` escalates to a transport failure only if `now − lastHeartbeatSentAtMs ≥ 10 000`
  (`KS/GlassesSessionSend.kt:328-338`). Because `lastHeartbeatSentAtMs` is the send time of the heartbeat that
  just timed out (~1.5 s earlier), this practically never fires; the effective detector is the consecutive
  timeout limit (§6.6) — ≈ 9 heartbeat timeouts. Recommended: fail when no heartbeat ack for ≥ 10 s.
* Stock flows (flash prompt) instead send a heartbeat every 4000 ms without waiting for acks
  (`KT/StockLink.kt:79`, `KT/FlashPromptFlow.kt:260-284`).

### 6.6 Ack timeouts and failure escalation

| Message kind | ackTimeout | On timeout | Source |
|---|---|---|---|
| any stock message (default) | 3500 ms | `consecutiveAckTimeouts++`; kind-specific `onTimeout`; if `> 8` consecutive ⇒ transport failure | `KT/MessageBuilder.kt:5`, `KS/GlassesSessionSend.kt:1160-1171` |
| heartbeat | 1500 ms | see §6.5 | |
| prelude | loop waits 2000 ms | throw ⇒ reconnect | `KS/GlassesSessionCore.kt:1917-1936` |
| security auth | 6000 ms (soft) | continue | |
| create-layout | 3500 ms | transport failure | `KS/GlassesSessionSend.kt:600-609` |
| audio control (mic) | 3500 ms | transport failure | `KS/GlassesSessionSend.kt:913-915` |
| shutdown (suspend) | 3500 ms | log only (keep BLE); explicit shutdown ⇒ transport failure | `KS/GlassesSessionControls.kt:150-156` |
| settings read, IMU, wear detect | 3500 ms | log only | |
| CFW messages (sid 0xF0) | 500 ms | go-back-N replay (§7.9); 3 retries then transport failure | `KT/CfwMessageWindow.kt:10-11` |
| buzzer sequence (CFW) | 500 ms | transport failure (if it reaches the generic path) | `KS/GlassesSessionCore.kt:1084-1086` |

`consecutiveAckTimeouts` resets on every resolved ack. Deadlines start **after** the write completes.

### 6.7 Disconnect detection and reconnect policy

Detection:
* GATT `onConnectionStateChange(DISCONNECTED)` for R or L → `sessionReady=false`, clear both queues, drop
  layout state, and schedule reconnect at `now + 2000` (`KS/GlassesSessionCore.kt:1607-1650`,
  `AN/FaceclawBleManager.kt:454-478`). (The manager closes the GATT on disconnect.)
* Write failure (after the retry table) ⇒ transport failure.
* > 8 consecutive ack timeouts; CFW replay limit; create-layout/audio/prelude timeouts; charging ended.
* Unhandled exception in the worker loop ⇒ transport failure ("loop error").
* There is no inactivity timer on inbound traffic (`lastIncomingAtMs` is informational).

`handleTransportFailure(reason)` (`KS/GlassesSessionControls.kt:389-413`): possibly emit the Even-app
conflict warning (write failure within 15 000 ms of session ready while the official Even app's
notification is active; at most once per 60 s, `KS/GlassesSessionControls.kt:642-658`); abort benchmark;
reset session flags; clear queues; `reconnectAfterMs = now + 2000`; **disconnect both arms**; phase
`retrying` ("Reconnecting after <reason>").

Reconnect loop (`KS/GlassesSessionCore.kt:1267-1312`): while running and not ready: if halted (unpaired) sleep
100 ms forever; else wait until `reconnectAfterMs` then `connectLoopOnce()`. **Fixed 2000 ms delay, unlimited
attempts, no exponential backoff** (`KT/ConnectionOptions.kt:39`). Only an explicit new connect (new
communicator) clears the halted state.

TS policy (`TS/g2/reconnect-policy.ts:1-26`): an in-memory "manual-disconnected" flag suppresses the main
page's auto-connect after a user Disconnect, the flash flow, an incompatible firmware or an unpaired arm;
lifted by an explicit connect, pairing, or a successful firmware install. Auto-connect runs when the main
page appears if not suppressed and both MACs are valid (`TS/phone-ui/main-view-model.ts:864-878`).
`unpaired` phase ⇒ TS disconnects and suppresses auto-reconnect (`TS/g2/dashboard-controller.ts:1356-1363,1650-1676`).

### 6.8 Charging mode and silent mode

* Every settings read carries `chargingStatus`. Charging ⇒ `chargingMode=true`: clear queues, layout
  considered gone, no heartbeats (the firmware tears EvenHub down by itself), battery read every 30 s; phase
  `charging` with status "Glasses charging. Battery N%." Charging → not charging ⇒ transport failure ("charging
  ended") ⇒ full reconnect (`KS/GlassesSessionSend.kt:976-1004`).
* Silent mode (both touchpads long-pressed): the firmware ignores input and powers the display down while the
  link stays healthy. Tracked from pushes, reads and implicitly cleared by any list/text click
  (`KS/GlassesSessionSend.kt:960-974`, `KS/GlassesSessionCore.kt:1523-1528`). Not cleared on disconnect.

### 6.9 EvenHub suspend / resume and the CFW deferred wake (sleep path)

* `suspendEvenHubSession()` (`KS/GlassesSessionCore.kt:1187-1203`, `KS/GlassesSessionControls.kt:127-197`):
  reset the phone-side resource-cache model; `shutdownRequested=true`; clear pending; queue EvenHub Cmd 9
  (exitMode 0) at the head (and a compass-disable CFW message ahead of it if the compass may be on); wait for
  its ack (≤ 4000 ms) and then for the page-exit SysEvent (≤ 4000 ms more). Both BLE links stay up. TS
  schedules this 5000 ms after the glasses screen turns off when enabled
  (`TS/g2/dashboard-controller.ts:152,816-894`).
* While suspended with the **wake lease** held, a double tap / head-up makes the CFW defer the stock dashboard
  and send field-102 wake event (nonce) from R (§7.12). The session, if suspended and the frame came from R,
  immediately queues **CLAIM(nonce)** to R then L at the head and emits a `display-wake` input event
  (eventType DOUBLE_CLICK 3 or HEAD_UP 12) (`KS/GlassesSessionCore.kt:1379-1408`). Idle taps/long-presses
  (events 2/3/4) become `sys-event` inputs without a handshake (`:1409-1428`).
* `resumeEvenHubSession()` (`KS/GlassesSessionCore.kt:1210-1261`): if a CLAIM is still queued wait ≤ 500 ms for
  its delivery; write the **prelude** directly (on the caller's thread); then `shutdownRequested=false`,
  layout not created, clear everything except queued wake-lease controls; the scheduler re-creates the layout
  and sends the retained frame. TS then calls `awaitEvenHubSessionReady(4500)`, which once the layout exists
  and the desired frame is displayed queues **READY(nonce)** to R,L and waits ≤ 1500 ms for delivery
  (`KS/GlassesSessionCore.kt:520-558`, `TS/g2/dashboard-controller.ts:153,733-750`).

### 6.10 Graceful disconnect

TS (`TS/g2/dashboard-controller.ts:1687-1775`): suppress auto-reconnect → `setFaceclawWakeLeaseEnabled(false)`
(RELEASE op 2 to R,L; waits ≤ 1500 ms for delivery) → stop producers → `sendCfwCleanup()` → if not acked,
legacy `sendShutdown(0)` → `close()`. With incompatible firmware all firmware messages are skipped.

`sendCfwCleanup()` (`KS/GlassesSessionCore.kt:1106-1180`): requires CFW detected, session ready, layout
created, not suspended. Set `shutdownRequested`, clear pending, wait ≤ 4000 ms for in-flight to drain, clear
again, queue CFW mode 11 (`[0x0B]`), wait ≤ 4000 ms for its ack. After it is acked nothing else may be written
(§6.3 step 1).

`disconnect()` (`KS/GlassesSessionCore.kt:377-408`): if cleanup was not delivered and cannot be, release the FB
lease (op 6 to R,L, wait ≤ 1500 ms); stop the worker (interrupt + join ≤ 5000 ms); reset state; disconnect R,
L, ring; close all GATT; release the wake lock; phase `disconnected`.

### 6.11 Session state machine (phases reported to TS)

```
 disconnected ──start()──► connecting ──(connect seq OK)──► connected ─┬─(charging)──► charging
      ▲                        ▲  │                                   │                 │ charging ends
      │                        │  └─(bond missing)──► unpaired (halt)  │                 ▼
      │                        └──────(2 s)─── retrying ◄──(transport failure / link lost)
      └──── disconnecting ◄── disconnect() from any state
 Within "connected": layout-pending → layout-created ⇄ suspended (Cmd 9 / prelude+Cmd 0)
```
Phase strings: `disconnected`, `connecting`, `connected`, `charging`, `retrying`, `unpaired`,
`disconnecting` (`TS/native/faceclaw-communicator.ts:9-16`). A lost link reports `connecting`
("Connecting to the glasses...") (`KS/GlassesSessionCore.kt:1643-1649`).

---

## 7. CFW private transport (sid 0xF0)

### 7.1 Purpose and history

All Faceclaw-specific commands (drawing, resources, present, brightness, compass, ALS, buzzer, diagnostics,
cleanup, panel access) are carried as opaque "messages" (first byte = message type) over a private reliable
transport that the CFW intercepts **before** stock multipart reassembly: `cfw_receive_packet` handles any
packet on pipe 0 with `packet[0]==0xAA && packet[6]==0xF0` (`GP/message_transport.c:227-247`). Relevant
revisions (`GP/settings_ext.c:429-470`): 5 lens-selecting options byte + bridge forwarding + per-lens ACKs;
6 length-prefixed records spanning packets, stream reset/end flags, ordinals; 8 custom commands use sid F0
only; **10 persistent zlib, decoded CRC, sequenced NACK recovery**; 11 immediate NACK for incomplete
records/handler failures; 12 ACKs repeat up to 3 preceding successes; … 34 current.

### 7.2 TX packet format (phone → glasses)

```
 0   AA
 1   21
 2   seq         = (streamId + packetIndex) & 0xFF      (packetIndex 0-based within this stream)
 3   len         = count + 3                              (options + chunk + CRC)
 4   01          (stock "total fragments" — always 1)
 5   01          (stock "fragment index" — always 1)
 6   F0          sid
 7   00          flag
 8   options     bits 0..1 lens mask: 1 = LEFT, 2 = RIGHT, 3 = both (CFW bit numbering!)
                 bit 7 (0x80) RESET = first packet of a stream
                 bit 6 (0x40) END   = last packet of a stream
                 all other bits must be 0
 9…  chunk       `count` stream bytes, 0 ≤ count ≤ capacity, capacity = min(252, MTU − 14)
 end CRC-16/CCITT-FALSE over bytes [8, end−2) (options byte + chunk), little-endian
```
Packet size = `count + 11` ≤ MTU − 3 (≤ 263). Sources: `KT/CfwTransport.kt:77-116`,
`GP/message_transport.h:4-16`, `GP/message_transport.c:46-56`, `GF/send_message_probe.py:23-66`.

Golden vector (`FC/tests/kotlin/…/ProtocolTest.kt:20-33`): `frame("123456789abcdef", streamId=255,
lenses=3, mtu=23)` (uncompressed, RESET_CONTEXT) →
```
aa21ff0c0101f000 83 0b0f0078c3 31323334 862b      options 0x83 = RESET|both; record hdr 0b 0f00 78c3
aa21000c0101f000 03 3536373839616263 64 8c7b      seq wraps 0xff → 0x00
aa2101050101f000 43 6566 de70                     options 0x43 = END|both
```

### 7.3 Record format (stream bytes, concatenated across a stream's packets)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | flags: bits 0..1 lens mask (**must equal** the stream's options lens mask), bit 2 `0x04` COMPRESSED, bit 3 `0x08` RESET_CONTEXT; other bits 0 |
| 1 | 2 | body length LE16 (bytes on the wire; the compressed length when COMPRESSED), 0..65535 |
| 3 | 2 | CRC-16/CCITT-FALSE of the **decoded** (uncompressed) message, LE |
| 5 | n | body |

The 5-byte header may itself span packets; packet boundaries carry no meaning to the record parser
(`GP/message_transport.c:18-21,158-225`). Faceclaw sends exactly **one record per stream** (one message per
stream, RESET on its first packet, END on its last) — the firmware also supports several records per stream,
numbered by an ordinal (`GF/send_message_probe.py:36-66`).

### 7.4 Compression

Phone (`KT/CfwTransport.kt:6-48`, `AN/…/AndroidProtocolPlatform.kt:47-64`):
* One persistent `java.util.zip.Deflater()` per ingress arm (default level 6, zlib wrapper, 32 KiB window).
* Encode **at write time, in wire order**: `body = deflate(msg, SYNC_FLUSH)` (loop `deflate(buf, 0, 4096,
  SYNC_FLUSH)` until it returns less than a full buffer). Every record therefore ends with `00 00 FF FF`; the
  first record after a reset starts with the zlib header (`78 9C`). Never use `Z_FINISH`.
* `flags = lensMask | COMPRESSED | (RESET_CONTEXT if resetPending)`.
* Empty output (repeated empty sync flush) ⇒ clear COMPRESSED and send the empty body raw.
* Compressed body > 65 535 ⇒ the deflater state is now unusable: `reset()`, send the **raw** message with
  `flags = lensMask | RESET_CONTEXT`, and the next record starts a fresh context (RESET_CONTEXT again).
* Otherwise `resetPending = false`, remember the lens mask. A change of lens mask resets the context.
* `reset()` = `deflater.reset()`, `resetPending = true`. Called on: session ready (both arms), session teardown,
  before writing any **replayed** message, after a CFW message times out on the generic path, after a failed
  CFW write, and implicitly on lens-mask change; `close()` frees the native deflater
  (`KS/GlassesSessionCore.kt:1688`, `KS/GlassesSessionControls.kt:445`, `KS/GlassesSessionSend.kt:110-111,372-376,405-406`).

Firmware (`GP/transport_compression.c:1-70`): per (receiving lens, ingress origin) stream, a persistent zlib 1.1.4
inflater created lazily with `inflateInit2(windowBits = 15)` (zlib header required); each compressed body must
be ≥ 4 bytes and end in `00 00 FF FF`; output capped at 65 535 bytes (one extra byte detects overflow); the
inflater is destroyed on RESET_CONTEXT or any error.

Worked example (Python zlib, same parameters as Java; exact deflate bytes need not match, any valid stream
works): message `07 02` (diagnostics "show"), stream id 100, first after reset, both lenses:
```
record : 0f 0a00 daa4 | 78 9c 62 67 02 00 00 00 ff ff        flags 0x0f = both|COMPRESSED|RESET_CONTEXT
packet : aa 21 64 12 01 01 f0 00 | c3 | <record> | 98 99      options 0xc3 = RESET|END|both
next   : aa 21 65 10 01 01 f0 00 | c3 | 07 0800 daa4 62 67 02 00 00 00 ff ff | 7f a5
```

### 7.5 Streams and sequence numbers

* Stream id = the message's pool magic (100..255) (`KT/MessageBuilder.kt:82-104`); the first packet's seq =
  stream id; each following packet `+1` mod 256.
* Firmware per stream: on RESET adopt `next_sequence = stream_id = packet[2]`, ordinal 0, options = lens mask;
  every packet must have `seq == next_sequence` and the same lens mask, else the in-progress record is aborted
  (NACK) and the packet dropped (`GP/message_transport.c:163-181`). END (or any new RESET) with an incomplete
  record aborts it with a NACK (`:163-165,217-223`). After END the stream is inactive; the next packet must carry
  RESET.
* A lost *first* packet produces no NACK (stream inactive → dropped silently); the phone's 500 ms ACK timeout
  recovers it.

### 7.6 Lens targeting and bridge forwarding (firmware)

On the ingress lens (`GP/message_transport.c:227-247`): validate; `here` = own lens bit (stock side 1→RIGHT(2),
2→LEFT(1)); if `options & (here ^ 3)`: forward the whole packet to the peer as bridge envelope
`[kind=1 REQUEST, origin=here, packet…]` (≤ 265 bytes); if `options & here`: process locally. Validation failure
aborts the local stream.
On the peer (`:253-275`): `kind 1` from the other lens ⇒ validate and process with `origin` = ingress lens.
Replies: if `here == origin` send on BLE, else return `[kind=2 RETURN, origin, ack…]` over the bridge; the
ingress lens checks the ACK's lens byte equals `origin ^ 3` and sends it on its own BLE link (`:277-285`).
Bridge kind 3 is used both for panel-result fragments (`:270-276,292-302`) and for ring reports
(`GP/gesture_fwd.c:156,229-239`) — see Open questions. All sid-0xF0 handlers on a lens run serialized under an
image mutex (`GP/zlib_glue.c:355-376`).

### 7.7 ACK / NACK format (glasses → phone, on the ingress arm's `5402`)

```
 0  AA   1  12   2  txseq (glasses-chosen, ignore)   3  len = total − 8   4  01   5  01   6  F0   7  00
 8  kind          01 = ACK, 03 = NACK
 9  streamId      the stream id (seq of the RESET packet)
10  ordinal LE16  record index within the stream (always 0 for Faceclaw)
12  lens          processing lens: 01 = LEFT, 02 = RIGHT
13  size LE16     decoded message size
15  crc LE16      the record header's declared CRC of the decoded message
17  history[k]    ACK only, k = 0..3 entries of 7 bytes, newest first:
                  streamId(1) ordinal(LE16) size(LE16) crc(LE16) — earlier successes of the same
                  (lens, ingress) stream state; lens = byte 12
 end CRC-16/CCITT-FALSE over [8, end−2), LE
```
Total 19 (NACK: exactly 19) … 40 bytes. Sources: `GP/message_transport.c:75-101`, `KT/CfwTransport.kt:134-190`,
`GF/send_message_probe.py:69-94`.

History rules (`GP/message_transport.c:75-101,116-156,183-185`): ACK history is bounded by what fits one
notification on the ingress link: `packet_capacity = max(9, min(len−11, 30))` over the packets of the current
stream; `ack_capacity` rises to it and is lowered only on RESET_CONTEXT; entries included =
`min(ack_count, (ack_capacity−9)/7)`. So one entry needs an ingress packet ≥ 27 bytes, three need ≥ 41.
A NACK clears the history; RESET_CONTEXT clears it. Processing is remembered even if sending the reply fails, so
a later ACK's history repairs a lost one.

Examples (illustrative CRCs computed with §4.2):
```
ACK  L, stream 100, size 2, crc a4da : aa12050b0101f000 01 64 0000 01 0200 daa4 3f39
NACK R, stream 100                   : aa12070b0101f000 03 64 0000 02 0000 daa4 e2fc
```

Firmware validity for an ACK (`GP/message_transport.c:116-156`): flags only in {lens, 0x04, 0x08} and lens ==
stream options; context valid (RESET_CONTEXT sets it; any failure clears it — after a failure **only a record
with RESET_CONTEXT recovers, even for uncompressed records**); inflate OK; CRC(decoded) == header CRC; handler
(`cfw_message_received` → type dispatch) returns 0. Otherwise NACK and invalidate the context. Unknown, retired
or truncated message types are rejected (NACK); drawing/present require the FB lease (§7.12)
(`GP/zlib_glue.c:17-21,595-599`). Mode 7 with an unknown sub-op is accepted (benchmark no-op).

### 7.8 Firmware receive state machine (per receiving lens × ingress origin)

```
on packet p (length L):
  if p invalid (L<11, p[3]!=L-8, hdr bytes, reserved option bits, CRC):  abort(); return
  if p.opt & RESET: if mid-record: abort()          // NACK the partial record
                    stream := {active, next_seq = sid = p[2], ordinal = 0, lens = p.opt&3, pktcap = 9}
  if !active || p[2] != next_seq || (p.opt&3) != lens: abort(); discard; return
  next_seq++ (mod 256); pktcap = max(pktcap, min(L-11, 30))
  for each stream byte in p[9 .. L-2):
     collect 5-byte header → allocate body; collect body; when complete → complete()
  if p.opt & END: if mid-record: abort()  ; discard (stream inactive)
complete(): validate/inflate/CRC/dispatch → reply ACK(+history) or NACK; free; ordinal++
```

### 7.9 Phone-side window: acceptance, ordered completion, go-back-N replay

Per message (`KT/OutboundMessage.kt:1-62`): `cfwChecksum = CRC(message)`, `cfwAckLenses` (bitmask),
`cfwRetries`, `cfwRetryPending`.

On a notification with `value[6] == 0xF0` (`KS/GlassesSessionCore.kt:1330-1355`):
1. `parseAcks(value)`: reject unless size 19..40, `(size−19) % 7 == 0`, `AA 12`, `value[3]==size−8`, `01 01 F0 00`,
   kind ∈ {1,3}, lens ∈ {1,2}, NACK ⇒ size 19, CRC valid — **validate everything before applying anything**.
   Returns the primary ACK plus history entries (history entries inherit the primary's lens). Invalid ⇒ drop.
2. For each entry: find the in-flight CFW message with the same **ingress address** (the arm it was written
   to) and `magic == streamId`; `acceptCfwAck`: ordinal must be 0 and lens 1|2; NACK ⇒ `cfwRetryPending = true`;
   ACK ⇒ require `size == message.size` and `crc == cfwChecksum`, then `cfwAckLenses |= lens`.
3. Drain: while the **first CFW message** in flight is fully acked (`lenses == 3`) and not retry-pending,
   resolve it (in order; later fully-acked messages wait behind an unresolved head)
   (`KT/CfwMessageWindow.kt:20-30`, `KS/GlassesSessionSend.kt:490-499`).

Replay (`KT/CfwMessageWindow.kt:31-49`, `KS/GlassesSessionSend.kt:80-102`): each scheduler pass, if **any**
in-flight CFW message is retry-pending, or not fully acked with its deadline passed, then **every** in-flight
CFW message (including fully-acked ones behind the failure) is replayed: for each, if `cfwRetries ≥ 3` ⇒
transport failure ("CFW recovery retry limit"); else remove from in-flight, release its magic, allocate a
**fresh** stream id, `cfwRetries++`, clear acks/deadline; then put them back at the head of the pending queue
in original order. Each replayed write resets that arm's compressor first (RESET_CONTEXT).

Consequences: a message is written at most 4 times; commands that were already executed may execute again —
all CFW commands must be idempotent (the eviction barrier in §6.4 exists because upload-after-evict replays are
not). ACK timeout 500 ms after the write completes (p99 observed ACK 63 ms, max 77 ms)
(`KT/CfwMessageWindow.kt:7-10`).

### 7.10 CFW message types (first byte; masked with 0x7F; bit 7 = "lenses differ")

From `KT/CfwMessageType.kt:1-39` (mirrors `GP/zlib_glue.c:22-175`); payload details belong to the display spec.

| ID | Name | Status |
|---|---|---|
| 3 | BOUNDING_BOX | retired on the wire (phone optimizer only) |
| 5 | BUZZER | active |
| 6 | FULL_FRAME | retired |
| 7 | DIAGNOSTICS (`[7][0]` clear, `[7][1]` hide overlay, `[7][2]` show; `[7][0x7F]` no-op) | active |
| 8 | MULTI_SEGMENT | retired |
| 9 | RECT_COPY | retired |
| 10 | COMPASS (`[10][0]` stop, `[10][1]` start, `[10][2][intervalLE16][minChangeLE16]`) | active |
| 11 | CLEANUP (`[11]`) — end of custom session; must be the last message | active |
| 12–14 | retired | rejected |
| 15 | STOCK_FONT_STRING | retired |
| 16 | AMBIENT_LIGHT (op 0 query, 1 start polling, 2 stop) | active |
| 17 | RING_BATTERY (`[17][0]` → field-106 notify) | active |
| 18 | RETIRED_CACHE_WRITE | rejected |
| 19 / 20 | CACHED_IMAGE / CACHED_TEXT | retired |
| 21 / 22 | UPLOAD_RESOURCE / EVICT_RESOURCE | active (22 behind the window barrier) |
| 23–25 | PANEL_READ / PANEL_WRITE / PANEL_PATTERN | active |
| 26 / 27 / 28 | DRAW_CALLS / SET_ROOT_DISPLAY_LIST / PRESENT | active, require FB lease |
| 29 | CREATE_SURFACE | active |
| 30 | BRIGHTNESS | active |

### 7.11 Kinds of CFW traffic Faceclaw sends (all to L, lens mask 3)

`image` (draw/present), `resources`, `brightness-output` (mode 30), `als-control` (16), `compass-control` (10),
`sound` (5 buzzer, 7 debug flags), `bandwidth` (7/0x7F), `cfw-cleanup` (11)
(`KT/MessageBuilder.kt:61-113`, `KS/GlassesSessionSend.kt:637-904`, `KS/GlassesSessionBrightness.kt:17-25`).

### 7.12 CFW leases and wake handshake (settings field 101/102)

Control record (TX, field 101): `46 43 01 <op> <nonceLo> <nonceHi>` (`KT/BleProtocol.kt:519-542`):

| op | Name | Firmware effect (`GP/settings_ext.c:316-359`) | Faceclaw usage |
|---|---|---|---|
| 1 | WAKE ACQUIRE/RENEW | wake lease deadline = now + 90 000 ms | when TS wants wake takeover (screen-off suspend or wakeword); renewed every 45 s |
| 2 | WAKE RELEASE | clear lease; launch a pending stock dashboard now | disconnect / setting off |
| 3 | WAKE CLAIM(nonce) | if pending: adopt nonce, extend fallback to 5000 ms | on field-102 wake event |
| 4 | WAKE READY(nonce) | if pending and nonce matches: cancel fallback | after resume frame displayed |
| 5 | FB ACQUIRE/RENEW | direct-framebuffer lease = now + 90 000 ms (a fresh lease drops the resource cache) | at every session ready (priority), renewed every 45 s |
| 6 | FB RELEASE | release, drop resource cache, restore stock repaints | disconnect fallback |
| 7 | WEAR QUERY | emit current wear state on sid 0x10 | after enabling wear detection |

Every control is written to **R then L** (priority: at the queue head), magic 0; "delivery" = both writes
completed (`onSent` count 2), not an ack (`KS/GlassesSessionControls.kt:229-341`).

Deferred-wake protocol (fail-open, `GP/settings_ext.c:259-295`): with the wake lease valid and the stock idle
path about to launch the dashboard (double tap or head-up), the CFW increments a non-zero nonce, arms a 400 ms
fallback timer and sends field 102 `46 43 01 <1|5> <nonce LE16>` from R (flag 0x00, `f1=3, f2=0`).
Phone ⇒ CLAIM (fallback extended to 5 s) ⇒ resume ⇒ READY (fallback cancelled). Missing CLAIM/READY ⇒ the
stock dashboard launches. A second double tap while pending forces the stock dashboard. Idle gestures under
the wake lease: `46 43 01 <2 tap|3 long|4 release> <raw source: 0 left temple, 1 right temple, 4 ring> 00`,
mapped to SysEvent CLICK(0)/RING_LONG_PRESS(9)/RING_LONG_PRESS_RELEASE(10) with source 3/1/2
(`KT/BleProtocol.kt:671-721`).

### 7.13 CFW detection and compatibility

* Kotlin (`KT/BleProtocol.kt:1453-1480`, `KS/GlassesSessionSend.kt:948-952`): every settings reply with field 100
  → `customFirmwareDetected = extension.trim().startsWith("Faceclaw/")` (any revision). Gates cleanup, the
  scene/resource planner and other private modes; `onFirmwareInfo(left, right, extension)` is emitted to TS.
* TS (`TS/g2/firmware-compat.ts:28-145`): **required revision `REQUIRED_FACECLAW_FIRMWARE_VERSION = 34`**;
  `parseFirmwareExtension`: empty ⇒ stock; `Faceclaw/<int ≥ 0>` ⇒ faceclaw(n); other `Faceclaw/…` ⇒ other;
  prefix `EVENCFW` ⇒ legacy (always outdated); else other. Compatible ⇔ faceclaw ∧ n ≥ 34 (newer accepted).
  Base stock image 2.3.0.24 (`BASE_STOCK_VERSION`, `VALIDATED_STOCK_VERSION`).
* Mismatch (`TS/g2/dashboard-controller.ts:1484-1510,1615-1642`): log + user-visible warning, then (after any
  in-progress connect finishes) `disconnect({skipFirmwareCleanup: true})` — no lease release, cleanup or
  shutdown messages are sent to foreign firmware — and suppress auto-reconnect; status "Disconnected
  (incompatible firmware)." No data reported at all is not treated as incompatible.
* Onboarding classification (`TS/g2/firmware-compat.ts:170-210`): custom / older-faceclaw / other-custom /
  flashable-stock (≤ 2.3.0.24) / newer-stock-validated / newer-stock-unvalidated / unknown.
* g2flash advises third parties to accept only an exact recognized string (`GF/README.md` "Glasses with a custom
  firmware identify themselves…"); Faceclaw accepts ≥ 34.

---

## 8. Android implementation structure

### 8.1 Classes

| Class | Role | Source |
|---|---|---|
| `FaceclawDeviceDiscovery` | scan + bonded replay → JSON to TS | `AN/FaceclawDeviceDiscovery.kt` |
| `FaceclawBleManager` | blocking GATT wrapper: global lock, per-op `CountDownLatch` keyed by address, per-frame write-complete wait with retry table, callbacks → `FaceclawBleListener` | `AN/FaceclawBleManager.kt` |
| `AndroidSessionLink` | `SessionLink` over the manager | `AN/AndroidSessionLink.kt` |
| `AndroidStockLink` | `StockLink` over its own manager (probe / flash prompt / OTA) | `AN/AndroidStockLink.kt` |
| `GlassesSessionCore` (+ `GlassesSessionSend`, `GlassesSessionControls`, `GlassesSessionBrightness`) | session state machine, scheduler, CFW window | `KS/` |
| `MessageBuilder`, `BleMagicPool`, `OutboundMessage` | message construction, magic allocation, per-attempt state | `KT/` |
| `BleProtocol`, `CfwTransport`, `CfwMessageWindow`, `MessageReceiver`, `G2Event` | codecs | `KT/` |
| `StockLinkSession` | stock request/reply plumbing for onboarding flows | `KT/StockLinkSession.kt` |
| `FaceclawBleCommunicator` | JVM facade for TS: worker thread, main-looper marshalling, wake lock, keyguard broadcasts, Even-app detection | `AN/FaceclawBleCommunicator.kt` |

### 8.2 Threading/locking rules
* Worker thread performs connect and all scheduled writes; `onNotification`/`onConnectionStateChange` run on
  binder threads and only mutate state under the monitor, then `interrupt()` the worker's sleep.
* Blocking public calls (e.g. `sendShutdown`, `sendCfwCleanup`, `setFaceclawWakeLeaseEnabled`,
  `awaitEvenHubSessionReady`, `startG2AudioCapture`) wait on the monitor condition in ≤ 100 ms slices. The TS
  bridge invokes them on the Android main thread (via `setTimeout(0)`), so they can block the UI thread for
  seconds — run them on a background dispatcher in a reimplementation.
* Listener callbacks are posted to the main looper (`host.postToMain`); compass callbacks to the registering
  thread's looper (`KS/GlassesSessionCore.kt:895-904`).

### 8.3 What TypeScript sees (`FaceclawBleCommunicatorListener`, `FC/native/…/callbacks/FaceclawBleCommunicatorListener.kt:1-38`)

| Callback | Meaning |
|---|---|
| `onStateChange(phase, status)` | §6.11 phases + human status |
| `onRingEvent(kind, containerName, eventType, eventSource, exitReason, frameId, ringTick, ringType, ringAux, ringSpeed)` | input; kind ∈ `list-click`, `text-click`, `sys-event`, `even-ai`, `display-wake` |
| `onBatteryState(headset, headsetCharging, ring, ringCharging)` | from settings reads / field 106 (−1 unknown) |
| `onSilentMode(bool)`, `onWearState(bool)`, `onPhoneLockState(bool)` | |
| `onEvenAppConflict(msg)` | §6.7 |
| `onFrameMetrics`, `onFrameFinished` | display pipeline |
| `onFirmwareInfo(left, right, extension)` | §7.13 |

TS → Kotlin calls are serialized FIFO through a promise chain with a `setTimeout(0)` hop each (frame
submissions may run inline when the queue is empty) (`TS/native/faceclaw-communicator.ts:252-290`).

---

## 9. Constants summary

| Constant | Value | Source |
|---|---|---|
| Control write / notify | `…0e8ac72e5401` / `…5402` | `KT/BleProtocol.kt:15-17` |
| Render notify | `…0e8ac72e6402` | `KT/BleProtocol.kt:23` |
| OTA write / notify | `…0e8ac72e0001` / `…0002` | `KT/BleProtocol.kt:19-21` |
| CCCD | `0x2902`, value `01 00` | `KT/BleProtocol.kt:173` |
| R1 notify | `bae80011-…`, `bae80013-…` | `KT/BleProtocol.kt:25-27` |
| Even company id | `0x5245` ("ER") | `AN/FaceclawDeviceDiscovery.kt:43` |
| Desired MTU (glasses / ring) | 512 / 247 | `KT/ConnectionOptions.kt:8,13` |
| Connect / services / CCCD timeouts | 5000 / 5000 / 5000 ms | `KT/ConnectionOptions.kt:10-12` |
| Write timeout (per frame) | 2000 ms | `KT/ConnectionOptions.kt:15` |
| Write retry delays | 1,1,1,2,4,8,12,20,35,100,200 ms | `AN/FaceclawBleManager.kt:29` |
| Connect settle | 800 ms | `KS/GlassesSessionCore.kt:1660` |
| Auth soft / stock timeout / pairing cap / post-bond grace | 6000 / 30 000 / 90 000 / 2000 ms | `KT/ConnectionOptions.kt:21-24`, `KT/StockLink.kt:72-74` |
| Prelude wait | 2000 ms | `KT/ConnectionOptions.kt:16` |
| Stock ack timeout | 3500 ms | `KT/MessageBuilder.kt:5` |
| Heartbeat ack timeout | 1500 ms | `KT/MessageBuilder.kt:7` |
| Heartbeat ready / urgent / failure deadline | 4000 / 6000 / 10 000 ms | `KT/ConnectionOptions.kt:26-28` |
| Max consecutive ack timeouts | 8 (fail on the 9th) | `KT/ConnectionOptions.kt:36` |
| Reconnect delay | 2000 ms, fixed | `KT/ConnectionOptions.kt:39` |
| Battery poll / input quiet / charging poll | 300 000 / 5000 / 30 000 ms | `KT/ConnectionOptions.kt:29-33` |
| Idle sleep | 100 ms (250 idle, 1000 charging) | `KT/ConnectionOptions.kt:38` |
| Window (tracked in-flight) | 3 | `KT/ConnectionOptions.kt:57` |
| Magic pool | 100..255, LRU | `KT/BleMagicPool.kt:11-16` |
| Prelude sid / magic | 0x01 / 156 | `KT/BleProtocol.kt:29-31` |
| Stock chunk / max frame | 232 / 240 bytes | `KT/BleProtocol.kt:214-216` |
| Transport seq start | 0x40 | `KS/GlassesSessionCore.kt:211` |
| CFW sid | 0xF0 | `KT/CfwTransport.kt:52` |
| CFW lens bits | LEFT 1, RIGHT 2, BOTH 3 | `GP/message_transport.h:5-7` |
| CFW record flags | COMPRESSED 0x04, RESET_CONTEXT 0x08 | `KT/CfwTransport.kt:55-56` |
| CFW packet flags | RESET 0x80, END 0x40 | `GP/message_transport.h:13-14` |
| CFW ACK kinds | ACK 1, NACK 3 | `GP/message_transport.h:8-9` |
| CFW max record | 65 535 bytes | `KT/CfwTransport.kt:54` |
| CFW packet capacity | min(252, MTU − 14) | `KT/CfwTransport.kt:85` |
| CFW ACK size / history | 9 + 7·k (k ≤ 3), packet 19..40 | `GP/message_transport.c:16`, `KT/CfwTransport.kt:143-144` |
| CFW ACK timeout / retries | 500 ms / 3 | `KT/CfwMessageWindow.kt:10-11` |
| CFW lease duration / renew | 90 000 / 45 000 ms | `GP/settings_ext.c:101`, `KS/GlassesSessionCore.kt:44` |
| Wake fallback / claimed | 400 / 5000 ms | `GP/settings_ext.c:102-103` |
| Lease delivery wait | 1500 ms | `KS/GlassesSessionCore.kt:45` |
| CFW cleanup drain/ack waits | 4000 + 4000 ms | `KS/GlassesSessionCore.kt:46` |
| Required CFW revision | `Faceclaw/34` | `TS/g2/firmware-compat.ts:29`, `GP/settings_ext.c:480` |
| Discovery: RSSI α / nearest margin / stale | 0.35 / 8 dB / 12 000 ms | `TS/g2/pairing-candidates.ts:74,82,85` |

---

## 10. Gotchas & pitfalls

1. **g2-kit docs vs code.** `envelope.md` header (2-byte length, 4-byte magic, big-endian CRC over the whole
   frame) and `transport.md` UUIDs (`fff0/fff1/fff2`) are wrong; `settings.md` field numbers are wrong;
   `audio.md` "Cmd=18/19" are field numbers (Cmd is 15/16). Use §4/§5.
2. **seq is per message, not per fragment** (stock envelope). CFW is the opposite: seq **increments per packet**
   starting at the stream id.
3. **Two lens numberings.** Stock side id 1 = right, 2 = left; CFW lens bits 1 = left, 2 = right; EvenHub event
   source 1 = R, 2 = ring, 3 = L; CFW raw touch source 0 = left temple, 1 = right temple, 4 = ring.
4. **CRC coverage differs**: stock = CRC of the reassembled protobuf only (LE, appended after it); CFW packet CRC
   covers options + chunk; CFW record CRC covers the *decoded* message; CFW ACK CRC covers bytes 8..end−2.
5. **MTU assumptions.** Stock frames are fixed at ≤ 240 bytes regardless of MTU; a link stuck at MTU 23 breaks
   every multi-byte stock write (Android no-response writes longer than MTU−3 fail/truncate). Recommended: use
   `maxWrite = min(240, MTU − 3)` (the framer supports it; chunk ≥ 12 is required).
6. **Write-without-response still needs pacing**: wait for `onCharacteristicWrite` per frame; never have two
   GATT operations outstanding (process-wide).
7. **Never interleave fragments** of different stock messages on the same characteristic; send a message's
   frames back-to-back. Don't let the heartbeat cut into a multi-frame write.
8. **Auth on every connection**, R then L, before the prelude; unauthenticated links are dropped after ~30 s.
   Pairing dialogs may appear; pair one arm at a time.
9. **Early auth replies are failures** (non-empty field 3). The main session's soft wait treats the first reply
   as done; only stock flows wait for real success. Also ignore periodic sid-0x80 notify frames.
10. **Prelude before EvenHub**, and again after every Cmd 9 shutdown before re-creating the page. Subsequent
    CREATE for an existing page name may never ack (`GK/ble/docs/gotchas.md` "Session").
11. **Flag-0x00 frames that are not acks**: wear events (sid 0x10), CFW wake/gesture events (sid 0x09,
    magic 0), settings pushes (commandId 3). Filter them before ack matching.
12. **Events only from R**; CFW ACKs only from the ingress arm (L). Matching CFW ACKs must check the arm.
13. **Magic 156 is inside the pool range** (prelude). Don't allocate 156 to another message while a prelude is
    outstanding (Faceclaw avoids this only by quiescing traffic around preludes).
14. **Magic ≥ 128 is a 2-byte varint**; hand-built payloads (g2flash auth) that write it as one byte are only
    valid below 128.
15. **Inbound robustness**: the Android session parses only the first frame of a notification and does not check
    the inbound CRC or reassemble. Implement `MessageReceiver` semantics (§4.6) for new code; keep sid-0xF0 ACK
    packets whole (they have their own CRC).
16. **Compression order = wire order.** Compress at write time; a message compressed but not written (or written
    to a different arm) desynchronizes the inflater. After any NACK/timeout the firmware context is invalid until
    a RESET_CONTEXT record arrives — even uncompressed records are NACKed meanwhile.
17. **Empty sync-flush output** must be sent uncompressed; a compressed body < 4 bytes or not ending in
    `00 00 FF FF` is rejected. Never use `Z_FINISH`.
18. **Replays re-execute commands.** Only replay idempotent commands; keep evictions behind an empty CFW window.
19. **Validate the whole ACK packet** (all history entries and CRC) before applying any entry; history entries use
    the primary entry's lens.
20. **FB lease is a prerequisite** for drawing/present; it is fire-and-forget and volatile (90 s). Re-acquire at
    every session ready and renew well within 90 s, or draws NACK and the replay limit tears the link down.
21. **Heartbeat failure deadline bug**: as coded, the 10 s deadline is measured from the last heartbeat send, so it
    never fires; failure detection relies on > 8 consecutive timeouts.
22. **SID_STATE_CHANGE = 0x00** in Faceclaw while its comment and g2-kit say 0x0D (§5.7).
23. **API levels**: Faceclaw uses API-33 GATT write/notify overloads with minSdk 24; branch by API level.
24. **Even app contention**: the official app can hold/steal the link; write failures shortly after connect with
    the Even app active are reported to the user.
25. **Global GATT lock + 5 s connect**: a slow connect of one arm stalls writes to the other.
26. **`firmwareInfoQueried` is per communicator instance**: the immediate settings read happens only on the first
    session of an instance; later reconnects rely on the quiet-period poll (≥ 5 s without input).
27. **Charging**: display traffic stops and the next un-charge forces a full reconnect.
28. **Bridge kind-3 overlap** in the firmware (ring report vs panel result) — see Open questions; don't depend on
    peer-lens panel results.

---

## 11. Open questions / uncertainties

1. **Granted ATT MTU** from the glasses for a 512 request is not recorded (g2-kit: 244). CFW capacity scales with
   it; stock framing assumes ≥ 243.
2. **Is LE 2M actually in use by default?** Faceclaw passes a 1M|2M PHY mask to `connectGatt` and the CFW enables
   the 2M feature bit, but only the benchmark calls `setPreferredPhy(2M)`. Controller behavior unverified.
3. **Actual connection parameters/supervision timeout** after the CFW "fast 7.5 ms" patch are not confirmed (HCI
   must confirm, per the benchmark comment `KS/GlassesSessionSend.kt:19-22`).
4. **Display-wake sid**: `SID_STATE_CHANGE = 0x0` vs documented `0x0D`; whether stock firmware ever sends the
   `f1=1,f3{f1=1}` shape on sid 0 or 0x0D is unverified. Recommend 0x0D.
5. **Referenced note missing**: `notes/ble-connections-2.2.9.md` is cited (`KT/BleProtocol.kt:37`,
   `KT/OtaFlashFlow.kt:13`) but absent from the checkout; g2flash `docs/message-transport.md` likewise.
6. **Auth `secAuth` wire type**: schema says bytes, tested request sends varint 1 — firmware tolerates it;
   meaning of the value unknown. Whether `phoneType=4` matters on iOS is unknown.
7. **Settings reply commandId** (2 vs 4) and whether the stock handler replies to magic-0 control writes are
   unverified (Faceclaw ignores both).
8. **Keepalive tick period** behind the "899 ticks" EvenHub timeout is not stated; ~10 s observed by g2-kit.
9. **Inbound multi-fragment responses** on Android are not reassembled; if any consumed reply ever exceeds one
   frame (e.g. large settings responses with many CFW fields), Faceclaw would misparse it.
10. **Bridge kind 3** is used both for ring reports (`GP/gesture_fwd.c:156`) and panel-result fragments
    (`GP/message_transport.c:270-276`); `faceclaw_input_bridge_received` returns an error for any kind-3 envelope
    that is not 13 bytes, which appears to drop peer-lens panel results. Unverified.
11. **Why CFW ingress is the left arm** (`sendImagesToLeft = true`) is undocumented; the phone code supports right
    ingress (`cfwTransports[1]`). Possibly to keep R's link free for events/acks.
12. **Glasses → phone framing byte 1**: all sources say `0x12`; Faceclaw's parser also accepts `0x21`.
13. **Right vs left battery**: the settings read has one `battery` field; g2-kit claims per-arm values. Faceclaw
    reads R in the session and each arm separately only in the flash prompt.
14. **Stock EvenHub page on L**: Faceclaw creates the page on R only and relies on firmware inter-lens sync;
    g2-kit claims no inter-lens bus. Faceclaw's behavior is the tested one.
