# Spec 03 — Input events, sensors, audio and other glasses peripherals

Subsystem specification for a wire-compatible, independent re-implementation (native Android / Kotlin, GPLv3)
of the parts of Faceclaw that deal with **user input from the Even Realities G2 glasses and R1 ring, wear /
battery / charging / lock state, the CFW sensors (compass, IMU, ambient light, brightness), the piezo buzzer,
the microphone stream, and the iOS ANCS relay**.

Sources analysed (read-only):

| Prefix | Path | What it is |
|---|---|---|
| `F/` | `/home/user/jimrandomh/faceclaw/` | Faceclaw app (NativeScript TS + Kotlin). Snapshot commit `a6291cf`. |
| `FK/` | `F/native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/` | Shared Kotlin protocol/session core |
| `FA/` | `F/App_Resources/Android/src/main/java/com/faceclaw/app/` | Android-only Kotlin |
| `G/` | `/home/user/jimrandomh/g2flash/` | Flasher + custom-firmware (CFW) patch sources (C) |
| `K/` | `/home/user/refs/g2-kit-unofficial/` | MIT reverse-engineering of the stock G2 protocol |

Precedence rule used throughout: **Faceclaw + g2flash behaviour (tested against the CFW) wins over g2-kit**;
every disagreement is called out. Firmware facts are for the CFW contract **`Faceclaw/34`** built on stock
**2.3.0.24** (`F/app/g2/firmware-compat.ts:29-39`, `G/patches/settings_ext.c:476-487`).

Notation: protobuf messages are written `{f1=…, f3={f5=…}}` where `fN` is the field number. Wire bytes are hex.
`LE16`/`LE32` are little-endian unsigned integers. "R arm" = right temple = master lens (firmware side id 1);
"L arm" = left temple (side id 2) (`G/patches/settings_ext.c:82`, `G/patches/message_transport.c:40-43`).

---

## 0. Foundations this subsystem relies on

### 0.1 Transport primitives (summary — the full transport spec is a separate document)

* GATT characteristics (both arms): write `00002760-08c2-11e1-9073-0e8ac72e5401`, notify
  `00002760-08c2-11e1-9073-0e8ac72e5402`, and the **"render"/mic notify `00002760-08c2-11e1-9073-0e8ac72e6402`**
  (`FK/g2protocol/BleProtocol.kt:15-23`). Faceclaw enables notifications on `…5402` **and** `…6402` on both arms
  at connect time (`FK/g2protocol/session/GlassesSessionCore.kt:1744-1763`).
* Envelope phone→glasses: `aa 21 seq len total idx sid flag <payload chunk> [crc16 LE on last fragment]`;
  glasses→phone uses `aa 12` in byte 1 (`BleProtocol.kt:209-248`, `:960-1017`). CRC is CRC‑16/CCITT‑FALSE
  (poly 0x1021, init 0xFFFF, no reflection, no final XOR) over the complete protobuf, appended little-endian
  (`BleProtocol.kt:910-927`).
* Flag byte values that matter here (`BleProtocol.kt:131-135`):
  * `0x20` request (phone→glasses; an acknowledgement with the same sid + magic is expected),
  * `0x00` reply/ack — **also used by several CFW "push" messages** (see §1),
  * `0x01` and `0x06` async notify (both must be accepted).
* Every protobuf root carries `f1 = command id` and `f2 = magic` ("MagicRandom"); acks are matched on
  `(sid, magic)` (`FK/g2protocol/session/GlassesSessionSend.kt:501-511`). Faceclaw allocates magics from
  100..255 (`FK/g2protocol/BleMagicPool.kt:11-12`); **magic 0 means fire-and-forget** — the message is not placed
  in the in-flight window and no ack is awaited (`GlassesSessionSend.kt:347-351`), and incoming frames with a
  magic outside 100..255 are silently ignored by the ack path (`GlassesSessionSend.kt:1289-1292`).
* **CFW private messages** ("image-handler modes"): a CFW command body `[mode][args…]` travels over the private
  SID `0xF0` message transport (`FK/g2protocol/CfwTransport.kt:91-95`, `G/patches/message_transport.h:4-16`).
  Faceclaw writes every CFW command to the **left** arm with lens mask **BOTH = 3** (LEFT=1, RIGHT=2); the left
  lens forwards it to the right lens over the inter-lens bridge and each selected lens executes the handler
  (`FK/g2protocol/ConnectionOptions.kt:50`, `GlassesSessionSend.kt:372-376`). Handlers that only make sense on
  the master lens check the side id themselves. CFW ACK timeout 500 ms, go-back-N replay, max 3 retries
  (`FK/g2protocol/CfwMessageWindow.kt:10-11`). Every received CFW command also resets the firmware's EvenHub
  keepalive counter exactly like a heartbeat (`G/patches/zlib_glue.c:378-390`).
* CFW mode numbers used by this subsystem (`G/patches/zlib_glue.c:22-170`, mirrored in
  `FK/g2protocol/CfwMessageType.kt:5-39` and `F/app/g2/cfw-message-type.ts`):

| Mode | Name | Section |
|---|---|---|
| 5 | BUZZER | §6 |
| 7 | DIAGNOSTICS (overlay flags; `[7][0x7f]` is used as a no-op for benchmarks) | — |
| 10 (0x0A) | COMPASS | §5.1 |
| 11 (0x0B) | CLEANUP (end-of-session; stops compass/ALS/mic/buzzer, releases leases) | §0.3 |
| 16 (0x10) | AMBIENT_LIGHT | §5.3 |
| 17 (0x11) | RING_BATTERY | §3.4 |
| 30 (0x1E) | BRIGHTNESS | §5.4 |

### 0.2 Sids (service ids) involved

From `K/ble/gen/service_id_def_pb.ts:17-179` plus Faceclaw usage:

| sid | Stock name | Used here for |
|---|---|---|
| 0x07 | UI_FOREGROUND_EVEN_AI_ID | "Hey Even" wakeword notifications (§2.9) |
| 0x08 | UI_BACKGROUND_NAVIGATION_ID | compass heading / calibration notifications (§5.1) |
| 0x09 | UI_SETTING_APP_ID | settings read (battery, charging, silent, fw versions), wear-detector enable, **all CFW private settings fields 100–106** (§0.3, §2.6, §2.7, §3.4, §4, §5.3, §7.5) |
| 0x0D | SERVICE_SYNC_INFO_APP_ID | foreground/background-app sync notifications (display-wake fallback, §2.8) |
| 0x10 | UI_ONBOARDING_APP_ID | wear on/off-head events (§4.1) |
| 0x80 | UX_DEVICE_SETTINGS_APP_ID | security auth (transport spec; not covered here) |
| 0x81 | UX_GLASSES_CASE_APP_ID | case info — **not used by Faceclaw** (§4.8) |
| 0x90 / 0x91 | UX_RING_ROW_DATA_ID / UX_RING_DATA_RELAY_ID | ring health relay — **not used by Faceclaw** (§3.3) |
| 0xE0 | UI_BACKGROUND_EVENHUB_APP_ID | EvenHub: gestures, IMU, mic enable (§2.2, §5.2, §7.1) |
| 0xF0 | (CFW private) | CFW commands + ACKs (§0.1) |

### 0.3 CFW ownership leases — prerequisites for almost every CFW input feature

The CFW keeps two independent, volatile, fail-open "leases" that the phone must hold and renew. Both are
controlled with the same private settings field **101** (`G/patches/settings_ext.c:33-44, 86-103, 316-359`).

Control message (phone→glasses, sid 0x09, flag 0x20, **magic 0**, sent to **both** arms, right arm first when
urgent) (`FK/g2protocol/BleProtocol.kt:524-542`, `FK/g2protocol/MessageBuilder.kt:235-291`):

```
G2SettingPackage { f1 commandId = 1, f2 magic = 0, f101 bytes = ['F','C', 1, op, nonceLo, nonceHi] }
wire: 08 01 10 00 aa 06 06 46 43 01 <op> <nonceLo> <nonceHi>
```

The CFW parses field 101 in a wrapper around the stock nanopb decoder *before* the stock decoder discards the
unknown field (`settings_ext.c:361-403`). Because magic is 0 there is **no ack**; Faceclaw only waits for the
BLE write to complete on both arms ("delivery"), up to 1500 ms (`FK/g2protocol/session/GlassesSessionCore.kt:45`,
`FK/g2protocol/session/GlassesSessionControls.kt:229-272`).

| op | Name | Effect in firmware |
|---|---|---|
| 1 | ACQUIRE/RENEW (wake lease) | `wake_lease_deadline = now + 90000 ms` |
| 2 | RELEASE (wake lease) | clear wake lease; immediately launch any pending (deferred) stock dashboard |
| 3 | WAKE_CLAIM (nonce) | if wake lease active and a wake is pending: adopt phone's nonce, extend fallback to 5000 ms |
| 4 | WAKE_READY (nonce) | if lease active, wake pending and nonce matches: cancel fallback, clear pending |
| 5 | FB_ACQUIRE (framebuffer lease) | `direct_lease_deadline = now + 90000 ms`; a *fresh* acquire (not a renewal) also resets `direct_active` and frees the resource cache |
| 6 | FB_RELEASE | clear framebuffer lease, `direct_active = 0`, free resource cache |
| 7 | WEAR_QUERY | emit the cached stock wear state on sid 0x10 (§4.1) |

Lease semantics (`settings_ext.c:112-137`): deadlines use the firmware's 1 ms tick with signed wrap-safe
comparison; an expired lease reads as inactive and (for the framebuffer lease) triggers release of display
resources.

| Lease | Held by Faceclaw when | Gates (what stops working without it) |
|---|---|---|
| **Framebuffer lease** (ops 5/6) | always, for the whole connected session: acquired with priority right after the prelude (`GlassesSessionCore.kt:1692-1695`), renewed every **45 s** (`GlassesSessionSend.kt:62-69`, `GlassesSessionCore.kt:44`); kept while EvenHub is suspended | CFW gesture forwarding (events 9/10/11/12/14, §2.4), **R1 raw-report forwarding (§2.5)**, brightness mode 30, compass-calibration preservation, ALS lease binding, ANCS relay |
| **Wake lease** (ops 1/2) | when the CFW revision is confirmed AND (`Suspend EvenHub when screen off` is on OR wakeword action ≠ off) (`F/app/g2/dashboard-controller.ts:763-771`); re-acquired after (re)connect, renewed every 45 s (`GlassesSessionSend.kt:70-79`), and force-refreshed immediately before every EvenHub suspend (`dashboard-controller.ts:853-859`) | deferred screen-wake takeover (§2.7), idle gesture forwarding (§2.6), suppression of the stock Even AI app on wakeword (§2.9) |

CFW mode **11 (CLEANUP)** — `[0x0B]` — ends a session: clears both leases, launches any pending dashboard, stops
the buzzer sequencer, the CFW mic session, passive ALS, brightness ownership and the compass
(`G/patches/zlib_glue.c:681-740`). Faceclaw sends it as the very last message before disconnecting (after
draining the window) and falls back to FB_RELEASE on older CFWs (`GlassesSessionCore.kt:377-384, 1106-1180`).

---

## 1. Message catalogue (quick reference)

"Arm" = the link on which the phone writes / receives it. "notify" = flag 0x01 or 0x06.

| # | Dir | sid / char | Flag | Arm | Shape | Purpose | § |
|---|---|---|---|---|---|---|---|
| M1 | G→P | 0xE0 | notify | R | `{f1=2, f13={f3=SysEvent}}` | stock + CFW gestures, exits, IMU | 2.2 |
| M2 | G→P | 0xE0 | notify | R | `{f1=2, f13={f2=TextEvent}}` | stock scrolls on the capture text container | 2.2 |
| M3 | G→P | 0xE0 | notify | R | fixed 25-byte SysEvent with `f100 'RI'` metadata | R1 raw report (CFW ≥ 19) | 2.5 |
| M4 | G→P | 0x09 | **0x00** | R | `{f1=3,f2=0,f102='FC',1,ev∈{1,5},nonce16}` | deferred screen wake | 2.7 |
| M5 | G→P | 0x09 | **0x00** | R | `{f1=3,f2=0,f102='FC',1,ev∈{2,3,4},src,0}` | idle (sleep) gesture | 2.6 |
| M6 | P→G | 0x09 | 0x20 | R then L | `{f1=1,f2=0,f101='FC',1,op,nonce16}` | leases / CLAIM / READY / WEAR_QUERY | 0.3 |
| M7 | G→P | 0x0D | notify | R | `{f1=1, f3={f1=1}}` | stock dashboard became background app | 2.8 |
| M8 | G→P | 0x07 | notify | R | `{f1=1, f3={f1=status}}` | Even AI control (wakeword) | 2.9 |
| M9 | G→P | 0x09 | (any) | R | `{f1=3, f5={f2=silent}}` (glasses-chosen magic) | silent-mode push | 4.4 |
| M10 | P→G | 0x09 | 0x20 | R | `{f1=2, f2=magic, f4={f1=1}}` | settings read | 4.2 |
| M11 | G→P | 0x09 | 0x00 | R | `{…, f4={f5,f6,f12,f13,f14,…}, f100, f104, f106}` | settings read response | 4.2 |
| M12 | P→G | 0x09 | 0x20 | R | `{f1=1, f2=magic, f3={f5={f1=1}}}` | enable wear detector | 4.1 |
| M13 | G→P | 0x10 | 0x00 (stock) / notify (CFW) | R | `{f1=3, f2=0, f5={f1=1, f2=worn}}` | wear on/off-head | 4.1 |
| M14 | P→G | 0xF0 | – | L, lenses BOTH | `[0x0A] …` | compass control | 5.1 |
| M15 | G→P | 0x08 | notify | R | `{f1=15, f2=0, f10={f1=deg}, f100 'CM'…}` | compass heading | 5.1 |
| M16 | P→G | 0xE0 | 0x20 | R | `{f1=19, f2=magic, f22={f1=en, f2=frq}}` | IMU enable | 5.2 |
| M17 | G→P | 0xE0 | notify | R | SysEvent type 8 + `f3 IMUData` floats | IMU sample | 5.2 |
| M18 | P→G | 0xF0 | – | L, BOTH | `[0x10][op]…` | ambient light control | 5.3 |
| M19 | G→P | 0x09 | **0x00** | R | `{f1=3,f2=0,f105='AL'… 24 B}` | ambient light report | 5.3 |
| M20 | P→G | 0xF0 | – | L, BOTH | `[0x1E][1][lvl][vis][dur16]` | brightness target / fade | 5.4 |
| M21 | P→G | 0xF0 | – | L, BOTH | `[0x05][kind]…` | buzzer | 6 |
| M22 | P→G | 0xE0 | 0x20 | R | `{f1=15, f2=magic, f18={f1=en}}` | mic on/off | 7.1 |
| M23 | G→P | 0xE0 | 0x00 | R | `{f1=16, f2=magic, f19={f1=AudioStat}}` | mic on/off ack | 7.1 |
| M24 | G→P | char `…6402` | (no envelope) | **L** (dups possible on R) | 205-byte LC3 packet | mic audio | 7.2 |
| M25 | P→G | 0xF0 | – | L, BOTH | `[0x11][0]` | ring-battery push request (defined, unused) | 3.4 |
| M26 | G→P | 0x09 | notify | R | `{f1=3,f2=0,f106='RB',1,flags,level}` | ring battery push | 3.4 |
| M27 | P→G | 0x09 | 0x20 | R and/or L | `{f1=1,f2=0,f103='MC'…}` | CFW mic control (experimental) | 7.5 |
| M28 | G→P | 0x09 | **0x00** | R and L | `{f1=3,f2=0,f104='MC'… 21 B}` | CFW mic status | 7.5 |
| M29 | G→P | char `…6402` | (no envelope) | R and L | `'SM'` frames | CFW multichannel mic (experimental) | 7.5 |
| M30 | P↔G | EUS write / notify handle 0x844 | – (no envelope) | R | raw `['A','N',1,…]` frames | ANCS relay (iOS only) | 8 |
| M31 | P→G | 0xF0 | – | L, BOTH | `[0x0B]` | session cleanup | 0.3 |

---

## 2. Input events

### 2.1 Physical gestures and sources

Input devices:

* **Right temple touchpad** and **left temple touchpad** (each temple is its own MCU/BLE endpoint).
* **R1 ring** — a separate BLE peripheral that, by default, is paired to the glasses (not the phone) and delivers its
  gesture reports to whichever lens it is connected to (§3.1).
* **IMU head-up** (head tilt), a firmware-detected "gesture" (§2.4, §2.7).
* **"Hey Even" wakeword**, detected on the glasses (§2.9).
* (Phone-side only, synthetic) Wear OS watch and on-phone touch pads (§2.13).

Gesture vocabulary as delivered to the phone (all values are `OsEventTypeList`, see §2.2):

| Gesture | Temple touchpad | R1 ring | Notes |
|---|---|---|---|
| Tap (single) | CLICK_EVENT 0 | CLICK_EVENT 0 | |
| Double tap | DOUBLE_CLICK_EVENT 3 | DOUBLE_CLICK_EVENT 3 | With no EvenHub page on screen the stock idle path turns a temple double-tap into a dashboard launch (deferred, §2.7). |
| Swipe / scroll "up" (backward) | SCROLL_TOP_EVENT 1 (usually as a TextEvent) | SCROLL_TOP_EVENT 1 (ring wire type 4) | |
| Swipe / scroll "down" (forward) | SCROLL_BOTTOM_EVENT 2 | SCROLL_BOTTOM_EVENT 2 (ring wire type 5) | |
| Long press (start of hold) | 9 (CFW) | 9 (ring wire type 0) | Stock would open a "quit app" modal instead; the CFW delivers it as an event (`G/README.md:47-48`). |
| Long-press release | 10 (CFW) | 10 (ring wire type 8) | |
| Tap-then-hold ("short-then-long", 2.2.9 Menu gesture) | 11 (CFW) | 11 (ring wire type 9) | Stock opens the Menu app; the CFW suppresses that while it owns the page (`G/README.md:49-50`). |
| Touch-down ("ring press") | – | 14 (ring wire type 10) | Arrives *before* click/hold/swipe is recognised; used for low-latency games. |
| Head-up (IMU tilt) | 12 (CFW, only while an EvenHub page is on screen) | – | Otherwise surfaces as a deferred wake (§2.7). |
| Silent-mode toggle | long-press **both** touchpads together | – | Firmware then refuses input & app launches (§4.4). |

Source encoding (`EventSourceType`, `K/ble/gen/EvenHub_pb.ts:1032-1053`, `F/app/g2/events.ts:1-20`):

| Value | Name | Meaning |
|---|---|---|
| 0 | TOUCH_EVENT_FORM_DUMMY_NULL | unspecified / field absent (TextEvents have no source field at all; the extension events 12 and 14 are sent without one) |
| 1 | TOUCH_EVENT_FROM_GLASSES_R | right temple |
| 2 | TOUCH_EVENT_FROM_RING | R1 ring |
| 3 | TOUCH_EVENT_FROM_GLASSES_L | left temple |
| 4 | TOUCH_EVENT_FROM_WATCH | **synthetic, phone-side only** (never on the wire) |

Firmware-internal "raw source" (display-thread input record, used by the CFW idle forwarding and hooks): `0` and
`1` = temple touchpads, `4` = ring (`G/patches/settings_ext.c:56-60, 218-222`). Faceclaw maps raw 0 → L (3),
raw 1 → R (1), raw 4 → ring (2), anything else → 0 (`FK/g2protocol/BleProtocol.kt:105-109, 705-719`). The g2flash
comments do not state which of 0/1 is left vs right — see Open questions.

Firmware-internal "subtype" values of the display-thread input record (u32 at record+4), collected from the CFW
sources: `0` tap, `1` double tap, `3` long press, `6` head up, `0x0d` ring touch-down, `0x0e` long-press release,
`0x11` tap-then-long (`G/patches/settings_ext.c:208-225`, `G/patches/gesture_fwd.c:23-29, 97`). Swipe subtypes
are not referenced anywhere.

### 2.2 Stock EvenHub event messages (sid 0xE0)

All gesture events from the glasses arrive on the **right arm's** notify characteristic with a notify flag.
The left arm does not emit them (`K/ble/docs/events.md:80-90`); Faceclaw only decodes EvenHub/Even-AI
events from the right arm (`FK/g2protocol/session/GlassesSessionCore.kt:1499-1504`).

Root message `evenhub_main_msg_ctx` (`K/ble/gen/EvenHub_pb.ts:16-130`) — fields relevant here:

| Field | Name | Type |
|---|---|---|
| 1 | Cmd | enum EvenHub_Cmd_List |
| 2 | MagicRandom | uint32 |
| 13 | DevEvent | SendDeviceEvent |
| 16 | DevPrivateEvent | CommonDevicePrivateEvent `{1 ContainerID, 2 ContainerName, 3 eventId, 4 eventData}` (not used by Faceclaw) |
| 17 | DevSysEvent | CommonDevicePrivateSystemEvent `{1 EventCmd, 2 EventValue}` (not used) |
| 18 | AudioCtrCommand | `{1 AudoFuncEn}` (§7.1) |
| 19 | AudioResCommand | `{1 AudioStat}` (§7.1) |
| 22 | ImuCtrl | `{1 IMUReportEn, 2 reportFrq}` (§5.2) |
| 23 | IMURes | `{1 IMUReportEnStatus}` (§5.2) |

EvenHub `Cmd` values relevant here (`K/ble/gen/EvenHub_pb.ts:1063-1169`, `BleProtocol.kt:137-139`):
`2` OS_NOITY_EVENT_TO_APP_PACKET (all device events), `11` OS_PRIVATE_EVENT_PACKET, `12`/`13` heartbeat,
`14` OS_PRIVATE_SYSTEM_EVENT_PACKET, `15` APP_REQUEST_AUDIO_CTR_PACKET, `16` OS_RESPONSE_AUDIO_CTR_PACKET,
`19` APP_REQUEST_OPEN_IMU_PACKET, `20` OS_RESPONSE_IMU_PACKET. Faceclaw's decoder does **not** check `Cmd`; it only
looks for field 13 (`FK/g2protocol/G2Event.kt:31-34`).

`SendDeviceEvent` (field 13) is a oneof-like container (`K/ble/gen/EvenHub_pb.ts:861-875`):

| Field | Message | Fields |
|---|---|---|
| 1 | List_ItemEvent | 1 ContainerID, 2 ContainerName (string), 3 CurrentSelectItemName (string), 4 CurrentSelectItemIndex, **5 EventType** |
| 2 | Text_ItemEvent | 1 ContainerID, 2 ContainerName (string), **3 EventType** (no source field) |
| 3 | Sys_ItemEvent | **1 EventType**, **2 EventSource**, 3 IMUData, 4 systemExitReasonCode, **100 (CFW) ring metadata bytes** |

Decode precedence in Faceclaw: ListEvent, then TextEvent, then SysEvent (first present wins)
(`FK/g2protocol/G2Event.kt:35-92`). Missing EventType defaults to CLICK (0); missing source defaults to 0.

`OsEventTypeList` (stock values 0–8 from `K/ble/gen/EvenHub_pb.ts:1179-1225`; 9–14 from
`F/app/g2/events.ts:60-82`, `FK/g2protocol/BleProtocol.kt:141-165`, `G/patches/gesture_fwd.c:14-15, 176-186`):

| Value | Name | Produced by | Faceclaw use |
|---|---|---|---|
| 0 | CLICK_EVENT | stock SysEvent (temples, ring w/o CFW), CFW ring (wire 1), CFW idle tap | click |
| 1 | SCROLL_TOP_EVENT | stock TextEvent/SysEvent, CFW ring (wire 4) | scroll-up |
| 2 | SCROLL_BOTTOM_EVENT | stock TextEvent/SysEvent, CFW ring (wire 5) | scroll-down |
| 3 | DOUBLE_CLICK_EVENT | stock SysEvent, CFW ring (wire 2) | double-click |
| 4 | FOREGROUND_ENTER_EVENT | stock | ignored |
| 5 | FOREGROUND_EXIT_EVENT | stock | page gone: invalidate layout |
| 6 | ABNORMAL_EXIT_EVENT | stock | page gone |
| 7 | SYSTEM_EXIT_EVENT | stock | page gone |
| 8 | IMU_DATA_REPORT | stock (after IMU enable) | IMU sample, not input |
| 9 | RING_LONG_PRESS_EVENT (name is historical; also temples) | CFW hooks + CFW ring (wire 0) + CFW idle long | long-press |
| 10 | RING_LONG_PRESS_RELEASE_EVENT | CFW hooks + CFW ring (wire 8) + CFW idle release | long-press-release |
| 11 | SHORT_THEN_LONG_PRESS_EVENT | CFW hook + CFW ring (wire 9) | short-then-long-press |
| 12 | HEAD_UP_EVENT | CFW head-up gate (page on screen) | display-wake (head tilt) |
| 13 | (reserved — "used by an experimental temple edge") | — | never send/expect (`gesture_fwd.c:15`) |
| 14 | RING_PRESS_EVENT | CFW ring (wire 10); legacy dispatcher hook (source 0) | ring-press |
| 127 | (unknown ring wire type) | CFW ring (`gesture_fwd.c:154-155, 176`) | diagnostics only, never acted on |

On exits (5/6/7) Faceclaw marks the layout as not created, clears the displayed fingerprint and the message queue,
and uses the arrival time to confirm a requested shutdown (`GlassesSessionCore.kt:1529-1537`,
`GlassesSessionControls.kt:171-195`).

Minimal stock SysEvent on the wire (legacy extension 14 without source, `F/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/RingInputTest.kt:26`):
`08 02 6a 04 1a 02 08 0e` = `{f1=2, f13={f3={f1=14}}}` (no magic field).

> g2-kit disagreement: `K/ble/docs/events.md:15-45` describes a `ContainerEvent{name=1,index=2,tag=3,status=4}` with
> status 0/1/2 for press/long-press/release. The generated schema (and Faceclaw) use `List_ItemEvent` as above; treat
> the doc as wrong.

### 2.3 The "input page" (precondition for stock EvenHub events)

Stock EvenHub gesture events are only produced while an EvenHub page with an **event-capturing container** is in the
foreground. Faceclaw's page (its pixels come from the CFW direct framebuffer, not from containers) is created with
EvenHub Cmd 0 (`APP_REQUEST_CREATE_STARTUP_PAGE_PACKET`) (`FK/g2protocol/BleProtocol.kt:250-261`):

```
{ f1=0, f2=magic,
  f3 CreateStartUpPageContainer = {
     f1 ContainerTotalNum = 1,
     f3 TextObject = { f1 X=0, f2 Y=0, f3 W=576, f4 H=288, f9 ContainerID=1,
                       f10 ContainerName="dashboard", f11 IsEventCapture=1, f12 Content=" " },
     f5 = 10000 } }            // field 5 = widgetId in the g2-kit schema
```

`IsEventCapture` must be set explicitly for text containers (`K/ble/docs/gotchas.md:58-60`). Taps on a text
container arrive as a **SysEvent CLICK** (not a TextEvent) (`K/ble/docs/events.md:47-59`); swipes arrive as a
**TextEvent** with SCROLL_TOP/BOTTOM and no source — Faceclaw's own synthetic ring injector mirrors exactly this
(`F/app/g2/dashboard-controller.ts:2850-2858`), and the normaliser accepts scrolls from either message kind (§2.11).

### 2.4 CFW gesture forwarding while the EvenHub page is foreground (`G/patches/gesture_fwd.c`)

The CFW hooks the 2.3.0.24 display-thread dispatcher. All of the following require **framebuffer lease active AND
EvenHub (app id 0xE0) is the current UI mode** (`gesture_fwd.c:40-51`):

* **Long press / release / tap-then-long** (hook sites press 0x444940, release 0x444d08, tap-long 0x44499c,
  `gesture_fwd.c:53-79`): instead of the stock paths (quit modal / Menu app) the CFW calls the stock SysEvent
  sender with `EventType = 9 / 10 / 11` and the record's raw source; the stock sender maps it to `EventSource`.
  When the lease is absent the stock getter runs and stock behaviour is unchanged.
* **Head-up** (`gesture_fwd.c:82-124`): the IMU "head up" sub-event (subtype 6) is normally dropped while an EvenHub
  page is on screen. The CFW forwards it as `EventType = 12` (raw source 0; as with event 14 the stock sender most
  likely leaves `EventSource` unset for this extension type — Faceclaw ignores it) when additionally the **stock
  head-up switch is on** and the firmware is not busy (OTA/onboarding). Faceclaw converts this into a `display-wake` input carrying
  HEAD_UP (`GlassesSessionCore.kt:1505-1513`). Faceclaw never changes the head-up switch itself (it uses the user's
  stock setting; the stock message is §4.2 `DeviceReceive_Head_UP_Setting`).
* **Ring touch-down (legacy, Faceclaw/18)** (`gesture_fwd.c:17-37`): a dispatcher record with raw source 4 and
  subtype 0x0d produces `EventType = 14` with source **0** ("the stock SysEvent sender leaves its source 0").
  Under Faceclaw/19+ ring reports are normally consumed earlier (§2.5), so this path only matters for legacy report
  families.

Wire shape is the ordinary SysEvent (`{f1=2, f13={f3={f1=type, f2=source}}}`), sent as a notify.

### 2.5 R1 ring raw-report forwarding (Faceclaw/19, `G/patches/gesture_fwd.c:143-239`)

The ring talks to one of the lenses over its own link. The CFW hooks the lens's R1 report receiver **before** the
stock 100-tick de-duplication (`gesture_fwd.c:143-155, 200-221`). While the **framebuffer lease** is held — whether
or not an EvenHub page is on screen — every complete R1 gesture report is sent to the phone once (via the right lens)
and the stock receiver is skipped entirely (the stock UI never sees ring input). Without the lease, or for other
report families, the original receiver runs unchanged.

Raw R1 gesture report (11 bytes, validated at `gesture_fwd.c:161-163`):

| Offset | Size | Value |
|---|---|---|
| 0 | 1 | `0x00` |
| 1 | 1 | `0x09` |
| 2 | 1 | `0x61` |
| 3 | 1 | `0x00` |
| 4 | 1 | **wire type** (gesture code) |
| 5 | 1 | **aux** (raw, semantics unknown) |
| 6 | 1 | **speed** (raw, semantics unknown — "swipe speed" per `F/CHANGELOG:31`) |
| 7..10 | 4 | **tick** LE32 — the ring's own unsigned uptime clock, not phone or glasses time |

Wire type → EventType (`gesture_fwd.c:176-186`):

| Wire type | Physical gesture | EventType |
|---|---|---|
| 0 | long press (hold start) | 9 |
| 1 | tap | 0 |
| 2 | double tap | 3 |
| 4 | swipe up | 1 (SCROLL_TOP) |
| 5 | swipe down | 2 (SCROLL_BOTTOM) |
| 8 | long-press release | 10 |
| 9 | tap-then-long | 11 |
| 10 | touch-down ("press") | 14 |
| other (3, 6, 7, 11…) | unknown | 127 |

Routing: if the receiving lens is the **left** lens, it relays the raw 11 bytes over the private inter-lens bridge as
`[kind=3][origin=LEFT(1)][11 raw bytes]` (13 bytes) and the right lens re-runs the conversion
(`gesture_fwd.c:164-173, 223-239`); only the right lens notifies the phone. If enqueueing fails, the stock receiver
runs instead.

Packet sent to the phone (sid 0xE0, notify, **no magic field**, exactly 25 bytes, `gesture_fwd.c:187-196`):

| Offset | Bytes | Meaning |
|---|---|---|
| 0–1 | `08 02` | f1 Cmd = 2 |
| 2–3 | `6a 15` | f13 DevEvent, length 21 |
| 4–5 | `1a 13` | f3 SysEvent, length 19 |
| 6–7 | `08 <type>` | f1 EventType (mapped value above) |
| 8–9 | `10 02` | f2 EventSource = 2 (ring, explicit) |
| 10–12 | `a2 06 0c` | f100 (wire type 2), length 12 |
| 13–14 | `52 49` | `'R','I'` |
| 15 | `01` | version 1 |
| 16 | `01` | flags = 1 |
| 17 | wire type | raw report[4] |
| 18 | aux | raw report[5] |
| 19 | speed | raw report[6] |
| 20 | `00` | reserved |
| 21–24 | tick | raw report[7..10] (LE32) |

Example (`F/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/RingInputTest.kt:8`):
`08026a151a13080e1002a2060c524901010aabcd00efcdab89` → EventType 14, source 2, wire type 10, aux 0xAB, speed 0xCD,
tick 0x89ABCDEF.

Phone-side validation of the metadata (`FK/g2protocol/G2Event.kt:78-91`, `F/app/g2/ring-input.ts:5-11`): accept only
when `EventSource == 2`, length == 12, bytes 0..3 == `52 49 01 01` and byte 7 == 0; otherwise the event has **no**
ring metadata (tick = -1 sentinel in Kotlin). A value that fails validation is not an error — the gesture itself is
still delivered.

### 2.6 Idle ("sleep-mode") gesture forwarding (no app on screen)

When EvenHub has been shut down (Faceclaw "suspends" it while the display is off, §2.7), the stock display thread
drops single taps, long presses and releases. With the **wake lease** held, the CFW reports them on the settings
channel (`G/patches/settings_ext.c:53-60, 208-236`; hooked via the stock idle gate, `gesture_fwd.c:106-114`):

```
sid 0x09, flag 0x00 (stock "reply" sender), right arm only, magic 0
G2SettingPackage { f1=3, f2=0, f102 bytes = ['F','C', 1, event, rawSource, 0] }
wire: 08 03 10 00 b2 06 06 46 43 01 <event> <rawSource> 00
```

| event | Meaning | Phone maps to |
|---|---|---|
| 2 | TAP (subtype 0) | SysEvent CLICK (0) |
| 3 | LONG_PRESS (subtype 3) | 9 |
| 4 | LONG_PRESS_RELEASE (subtype 0x0e) | 10 |

The stock idle branch still runs as before. Double taps and head-ups are *not* reported this way (they go through
the deferred wake, §2.7). Swipes and tap-then-long are not forwarded; tap-then-long in idle opens the **stock Menu**
(only dashboard launches are deferred) — see Gotchas. Inference from the hook order (not verified on hardware):
because ring reports are consumed by §2.5 whenever the framebuffer lease is held — and Faceclaw keeps that lease while
EvenHub is suspended — idle events with raw source 4 should not occur in normal operation; ring taps while suspended
arrive as M3 SysEvents instead.

Faceclaw accepts these only when (a) EvenHub is intentionally suspended (`shutdownRequested`), (b) the frame comes
from the right arm, (c) the body is exactly 6 bytes `46 43 01 …` with event ∈ {2,3,4}
(`GlassesSessionCore.kt:1409-1428`, `BleProtocol.kt:677-721`), and turns them into ordinary `sys-event` inputs.

### 2.7 Screen-wake takeover (deferred stock dashboard)

Purpose: while EvenHub is shut down (low-power), a temple double-tap or an IMU head-up would make the stock idle
policy launch the stock dashboard. The CFW defers that launch and asks the phone to wake Faceclaw instead, with a
fail-open fallback to the stock dashboard (`G/patches/settings_ext.c:45-51, 139-189, 259-295`).

Firmware constants (`settings_ext.c:86-103`): lease 90 000 ms, unclaimed fallback **400 ms**, claimed fallback
**5000 ms**, protocol version 1, events `WAKE=1`, `WAKE_HEADUP=5`.

Wake event (glasses→phone): sid 0x09, flag 0x00, right lens only, magic 0:
`08 03 10 00 b2 06 06 46 43 01 <1|5> <nonceLo> <nonceHi>` (`settings_ext.c:173-189`).

Firmware state machine (per lens; the CLAIM handling "align both lenses to the right/master's nonce" implies that both
lenses run the idle policy and keep their own pending state — which is why CLAIM/READY go to both arms):

```
IDLE --(stock idle path requests dashboard launch [double tap site 0x467d68 → event 1,
        head-up site 0x467e28 → event 5] AND wake lease active AND context ok)-->
      nonce = nonce+1 (skip 0); pending = 1; start fallback timer 400 ms;
      right lens sends wake event                               --> PENDING
IDLE --(any other app id, or lease inactive, or no context)--> stock launch (unchanged)
PENDING --second double tap (event 1)--> launch stock dashboard now (emergency override) --> IDLE
PENDING --head-up (event 5)--> ignored (stays PENDING)
PENDING --CLAIM(nonce) while lease active--> nonce := phone's (aligns both lenses to the right lens's nonce);
                                            fallback := 5000 ms
PENDING --READY(nonce == current) while lease active--> stop timer, pending = 0 (no dashboard) --> IDLE
PENDING --RELEASE (op 2) or CLEANUP (mode 11)--> launch stock dashboard now --> IDLE
PENDING --fallback timer fires--> launch stock dashboard --> IDLE
(If the fallback timer cannot be created/started, the dashboard launches immediately.)
```

Phone behaviour (Kotlin `GlassesSessionCore.kt:1379-1407`, `:520-558`, `:1210-1261`; TS
`F/app/g2/dashboard-controller.ts:2112-2200, 717-761`):

1. Accept the wake event only while EvenHub is intentionally suspended and only from the right arm; parse nonce
   (LE16) and event code (`BleProtocol.kt:636-669`).
2. Immediately enqueue **CLAIM(nonce)** to both arms with priority, right arm first
   (`GlassesSessionControls.kt:229-254`).
3. Emit an input `display-wake` with `eventType = DOUBLE_CLICK (3)` for event 1 or `HEAD_UP (12)` for event 5,
   source 0.
4. TS: if the Glanceboard handles the gesture (head-tilt, §2.12) show it, else wake the shell ("sidebar" focus); then
   run the **wake barrier** `ensureEvenHubSessionActive`: phone screen wake-lock on → `resumeEvenHubSession()`
   (waits ≤ 500 ms for CLAIM to be written to both arms, replays the session prelude, recreates the input page) →
   unblank compositor → `awaitEvenHubSessionReady(4500 ms)`.
5. `awaitEvenHubSessionReady` sends **READY(nonce)** to both arms once the page exists *and* the first desired frame
   has been acked (displayed fingerprint == desired fingerprint), and waits ≤ 1500 ms for its delivery.
   `EVENHUB_WAKE_READY_TIMEOUT_MS = 4500` < claimed fallback 5000 ms (`dashboard-controller.ts:153`).

When EvenHub is suspended (screen off for `EVENHUB_SCREEN_OFF_SUSPEND_DELAY_MS = 5000`, `dashboard-controller.ts:152,
816-895`), Faceclaw sends EvenHub Cmd 9 (shutdown, exit mode 0 on Android) but keeps BLE and both leases.

### 2.8 Stock display-wake fallback (sid 0x0D "sync info")

If the stock dashboard is launched anyway (no lease, or fallback), the glasses send a sync-info notification on
sid 0x0D. Schema (`K/ble/gen/sync_info_pb.ts:16-74`): `sync_info_main_msg_ctx {f1 cmdList (1 = OS_NOTIFY_SYNC_INFO),
f2 magicRandom, f3 dataMsg {f1 backgroundAppID, f2 foregroundAppID}}`. The shape Faceclaw treats as "the stock display
lifecycle woke after a double tap" is exactly `f1=1` and `f3` = the 2 bytes `08 01` (backgroundAppID = 1 =
UI_BACKGROUND_DASHBOARD_APP_ID, no foreground id) (`FK/g2protocol/BleProtocol.kt:1088-1113`), accepted only from the
right arm while EvenHub is suspended; it becomes `display-wake` / DOUBLE_CLICK (`GlassesSessionCore.kt:1429-1445`).

> **Bug in the reference:** `BleProtocol.SID_STATE_CHANGE` is declared as `0x0`, not `0x0d`
> (`BleProtocol.kt:51`), although the doc comment says sid 0x0D (`:1092`). As written, this fallback can never
> match. A re-implementation should use **0x0D**. g2-kit's `decodeStateChange` misnames the inner fields as
> `sid`/`eventCode` (`K/ble/events.ts:52-121`).

### 2.9 Wakeword ("Hey Even") takeover

The glasses run the wakeword detector themselves and report on **sid 0x07** (Even AI) regardless of the CFW
(`F/app/ui/gestures.ts:62-67`). Schema (`K/ble/gen/even_ai_pb.ts:293-379, 568-590`):

```
EvenAIDataPackage { f1 commandId (1 = CTRL), f2 magicRandom, f3 ctrl = EvenAIControl { f1 status, f2 errorCode } }
status: 0 STATUS_UNKNOWN, 1 EVEN_AI_WAKE_UP (wakeword), 2 EVEN_AI_ENTER (manual entry), 3 EVEN_AI_EXIT
```

Faceclaw decodes only commandId 1 with a readable status, from the right arm with notify flag, into an
`even-ai` input (`FK/g2protocol/G2Event.kt:16-30`); **only status 1 is acted on** (`F/app/ui/shell/shell.ts:1564-1632`).

CFW takeover: the entry of the stock `even_ai_display_ctrl` is patched so that START (first argument 0) is
suppressed while the **wake lease** is active (`G/patches/settings_ext.c:405-427`). Without that, the stock Even AI
app takes the foreground and displaces EvenHub before Faceclaw's first frame can be acked
(`dashboard-controller.ts:763-771`). The sid-0x07 notification still reaches the phone.

Phone action (setting `voice.wakeWordAction`, default `voice-input`, values `voice-input | off | turn-screen-on`,
`F/app/ui/dashboard-settings.ts:613-621`):

* If the action is not `off`: wake the shell if the screen is off and run the wake barrier (resume EvenHub) *before*
  routing (`dashboard-controller.ts:2164-2183`).
* Shell (`F/app/ui/shell/shell.ts:758-781`): ignored while a window is already capturing voice; `off` → nothing;
  otherwise wake; `voice-input` opens a hands-free voice dialog targeting the assistant (or continues an open
  assistant conversation). The wakeword is processed even with the screen off, unlike ordinary input.

### 2.10 Silent mode

The wearer toggles silent mode by long-pressing both touchpads; the firmware then refuses input events and app
launches and powers the display down. It is reported by an unsolicited settings push
`G2SettingPackage {f1=3 (DeviceSendToAPP), f5 deviceSendInfoToApp {f2 silentModeSwitch}}` with a glasses-chosen
magic (`FK/g2protocol/BleProtocol.kt:762-787`), and re-read on every settings poll (`f4.f14`, §4.2) because the
"off" transition is not reliably pushed (`GlassesSessionSend.kt:940-945`). Any list/text container event also
proves silent mode is off (`GlassesSessionCore.kt:1523-1528`). The phone only displays the state
(`F/app/g2/glasses-display-state.ts`).

### 2.11 Phone-side decode and normalisation pipeline

#### 2.11.1 Kotlin notification dispatch (`FK/g2protocol/session/GlassesSessionCore.kt:1314-1567`)

For each GATT notification `(address, characteristic, value)`:

1. If `address` is the configured **direct ring** and the characteristic is `bae80011…`/`bae80013…` → direct ring
   decoder (§3.2). Return.
2. If the characteristic is `…6402` → audio packet path (§7). Return.
3. Ignore anything but `…5402`.
4. If `value[6] == 0xF0` → CFW ACK/NACK handling (transport spec). Return.
5. (iOS only) right-arm values starting `'A','N'` → ANCS relay tap (§8). Return.
6. Parse the envelope (single frame; see Gotchas). Decode the wear event (§4.1, any arm) and, for the right arm only,
   a compass event (§5.1).
7. Under the session lock, in this order (a matched earlier step suppresses later event steps as noted in the code):
   1. deferred-wake event (§2.7) — only while suspended, right arm, sid 9;
   2. idle gesture event (§2.6) — only while suspended, right arm, sid 9;
   3. sync-info display wake (§2.8) — only while suspended, right arm;
   4. sid 9 from right arm with notify flag: ring battery push (§3.4); any sid-9 frame: mic status field 104 (§7.5);
   5. sid 9 ALS report field 105 → brightness policy (right arm only) + listeners (§5.3);
   6. sid 9 silent-mode push (§2.10) — returns early;
   7. non-notify frames with a magic → ack resolution;
   8. right-arm notify frames → `G2Event.decode` (EvenHub sid 0xE0 or Even AI sid 0x07). A SysEvent HEAD_UP (12)
      becomes `display-wake`/12. Non-IMU events refresh the "last user input" timestamp (which delays battery polls,
      §4.2). Exit events invalidate the page (§2.2).
8. Outside the lock: emit wear change, compass event, IMU sample (if the SysEvent carried `IMUData`), then the input
   event (unless it is a pure IMU_DATA_REPORT) to the listener on the main thread with the raw fields
   `(kind, containerName, eventType, eventSource, systemExitReasonCode, frameId, ringTick, ringType, ringAux,
   ringSpeed)` (`GlassesSessionControls.kt:491-508`, `FK/callbacks/FaceclawBleCommunicatorListener.kt:6-17`).

`kind` ∈ `list-click | text-click | sys-event | even-ai | display-wake` (+ phone-synthetic `watch-gesture`).

#### 2.11.2 Raw → normalised input (`F/app/ui/shell/shell.ts:1559-1657`)

| Raw kind | Raw eventType | Normalised `InputEvent` |
|---|---|---|
| sys-event | 14 with source 0 or 2 | `ring-press` (source ring) — **source 1/3/other → unknown** |
| sys-event | 0 | `click` (source mapped) |
| sys-event | 3 | `double-click` |
| sys-event | 2 | `scroll-down` (source only if explicit 1/2/3/4) |
| sys-event | 1 | `scroll-up` |
| sys-event | 9 | `long-press` |
| sys-event | 10 | `long-press-release` |
| sys-event | 11 | `short-then-long-press` |
| text-click | 2 / 1 | `scroll-down` / `scroll-up` |
| text-click | other (incl. CLICK) | unknown |
| list-click | any | unknown (Faceclaw has no list containers) |
| even-ai | 1 | `wakeword` |
| even-ai | 0/2/3 | unknown |
| display-wake | any | `display-wake` (the eventType 3 vs 12 is kept on the raw event for the Glanceboard) |
| watch-gesture | 0/1/2/3 | `swipe-left/right/up/down` (source watch) |
| anything else | | `unknown {kind, eventSource, eventType}` |

Source mapping for gesture events: 2 → `ring`, 3 → `left-arm`, 1 → `right-arm`, 4 → `watch`, **anything else
(including 0) → `ring`** (`shell.ts:1643-1655`). Ring metadata (`tick/type/aux/speed`) is attached as
`event.ringInput` when present (`F/app/ui/gestures.ts:12-24`).

#### 2.11.3 Ring de-duplication filter (`F/app/ui/input-monitor.ts:22-56`)

Because the CFW forwards ring reports *before* the stock 100-tick suppression, the phone reproduces that filter on
the ring clock:

```
state lastTick = 0                      // 0 = "no previous report"
accept(e):
  raw = e.ringInput; if raw is absent -> accept        // temples, legacy, watch pass through
  if raw.type != 8 && raw.type != 10 && lastTick != 0 &&
     ((raw.tick - lastTick) mod 2^32) < 100 -> reject   // rejected reports do NOT move lastTick
  if raw.type != 10: lastTick = raw.tick                // press (10) never filters nor advances;
  accept                                                 // release (8) always passes and advances
acceptInput(e) = accept(e) && !(e.ringInput && e.type == "unknown")   // wire type 127 is dropped (but advanced the clock)
```

The decision is cached per event object so controller and shell apply it once; debug observers see every event with
a `filtered` flag. The filter state is reset with `resetRingInputFilter()` (test semantics:
`F/tests/input-events.test.cjs:137-158`). The unit of the ring tick is not known (see Open questions).

#### 2.11.4 Controller gating before the shell (`F/app/g2/dashboard-controller.ts:2112-2235`)

1. Normalise + `acceptInput`; drop if rejected.
2. **Glasses locked** (§4.5): only a `double-click` whose raw source is ring (2) or watch (4), or any `display-wake`,
   is honoured: screen on → (ring/watch double-click only) sleep; screen off → wake + wake barrier. Everything else is
   dropped (including temple double-taps while the page is up).
3. **Screen off**: offer the gesture to the Glanceboard first (§2.12.4); if it consumes it, stop.
4. `wakeword` (action ≠ off) or `display-wake`: pre-wake the shell and run the wake barrier if EvenHub is suspended /
   resuming.
5. `shell.receiveInput(event)` (§2.12).

### 2.12 Default mapping from gestures to UI actions

Glyphs used in on-glasses hints (`F/app/ui/gestures.ts:126-133`): tap `●`, double tap `●●`, scroll `▲▼`, long press
`—`, tap-then-hold `●—`.

#### 2.12.1 Shell routing order (`F/app/ui/shell/shell.ts:727-890`)

| Input | Behaviour |
|---|---|
| `ring-press` | Delivered only to the focused foreground window when screen on, focus = window, no overlay; never wakes, never operates menus, never cancels the hold timer. |
| `display-wake` | Wake the screen if it is off (focus sidebar). Never sleeps. |
| `wakeword` | See §2.9. |
| any other, screen **off** | Only `double-click` wakes (focus sidebar); everything else ignored. |
| `long-press` | Opens the **system menu** ("escape menu"). Exceptions: if a voice layer/overlay is up it is consumed; if the window is hold-to-talk or claims long-press, the press is forwarded to the window, and (non hold-to-talk) a 4000 ms timer still opens the system menu if the hold continues (`LONG_PRESS_ESCAPE_MENU_MS`, `shell.ts:211`). Any other input cancels that timer. |
| `short-then-long-press` | The **app context menu** gesture: forwarded to the foreground window (focusing it from the sidebar); over an open system menu it switches to the app menu if the app has one; hold-to-talk windows get the system menu. |
| `long-press-release` | Ends voice capture; forwarded to the focused window (hold-to-talk / game moves). |
| other with an overlay open | Delivered to the overlay stack. |
| other with sidebar focus | See 2.12.2. |
| other with window focus | Delivered to the window; watch swipes are translated by `directionalFallback` for windows that do not accept directional input (up/down → scroll, right → click, left → double-click) (`F/app/ui/gestures.ts:100-114`). |

#### 2.12.2 Sidebar (app switcher) focus (`shell.ts:959-990`)

| Gesture | Action |
|---|---|
| scroll-up / swipe-up | previous window (wraps) |
| scroll-down / swipe-down | next window (wraps) |
| click / swipe-right | focus the selected window ("select") |
| double-click | **turn the display off** (sleep) |

#### 2.12.3 Menus and pages

`MenuCore` (`F/app/ui/menu-core.ts:217-231`): scroll-up = move selection up, scroll-down = down, click = activate.
`MenuLayer` (`F/app/ui/menu.ts:322-337`): double-click = **back** (pop the layer), click = select. Text pages:
double-click = back (`menu.ts:386-397`). Summary of the default vocabulary:

| Action | Ring / temple gesture |
|---|---|
| Up / previous | scroll-up (ring swipe up, wire type 4) |
| Down / next | scroll-down (ring swipe down, wire type 5) |
| Select | tap |
| Back | double tap (at the root: display off; when off: wake) |
| System menu | long press (`—`) |
| App context menu | tap-then-hold (`●—`) |
| Hold-to-talk / game hold | long press … release |
| Low-latency action (games) | ring-press (touch-down) |

#### 2.12.4 Glanceboard (sleep-time display) (`F/app/g2/glance-state.ts:13-86`, `F/app/g2/glance-host.ts:20-110`)

Only while the shell screen is off and the board is enabled (default **disabled**,
`F/app/apps/glanceboard/glanceboard-settings.ts:48-56`):

| Gesture | Glance event | Effect |
|---|---|---|
| click | press (if "Show on tap" ≠ Disabled) | show; auto-hide after the tap duration (3/5/7/10 s, default 5 s; reducer default 3000 ms); each press restarts the timer; ignored during a hold |
| head-tilt (`display-wake` with eventType 12) | press (if "Show on head tilt", default on) | as click; if disabled the head-up wakes the regular UI |
| long-press, short-then-long-press | hold (if "Show on long press", default on) | show until release |
| long-press-release | release | hide if holding |
| double-click | dismiss (only while visible) | hide, then the double-click wakes the regular UI |

The board is an opaque full-screen compositor surface at z-order 900 (lock screen 1000, shell 1). Showing it runs the
same wake barrier (resumes EvenHub); hiding it while asleep re-blanks and re-arms the suspend timer.

### 2.13 Synthetic input sources (phone-side, not a glasses protocol)

* **Wear OS watch** (`F/app/g2/wear-remote.ts`): produces the same raw events with source 4 (`TOUCH_EVENT_FROM_WATCH`)
  and `watch-gesture` swipes (0 left, 1 right, 2 up, 3 down) (`F/app/g2/events.ts:46-58`,
  `dashboard-controller.ts:1799-1860, 2753-2860`).
* **Phone touch pads / mirror** (`F/app/phone-ui/phone-gestures.ts:10-70`): hold threshold 500 ms, a second tap
  within 280 ms and < 36 dip becomes a double tap, movement > 14 dip cancels the hold, swipe threshold ≥ 30 dip,
  multi-touch = double tap; hold after a tap = short-then-long. The phone "ring pad" injects ring-sourced events and
  only has a vertical axis.

---

## 3. R1 ring

### 3.1 Topology

* Default (`developer.ringConnectionMode = "glasses"`, `F/app/ui/dashboard-settings.ts:564-573`): **the phone never
  opens a BLE connection to the ring.** The ring is bonded to the glasses (by the official Even app) and its gestures
  reach the phone through the glasses (§2.5). Faceclaw does not write any ring command.
* `"direct"` (developer, "currently unreliable"): additionally connect to the ring and subscribe to its notify
  characteristics (§3.2). The ring address is only passed to the session in this mode
  (`dashboard-controller.ts:1326`).
* The ring may be connected to either lens; the CFW relays left-lens reports to the right lens (§2.5).

### 3.2 Direct ring link (developer mode)

Connect sequence (`GlassesSessionCore.kt:1773-1843, 1949-1963`): only after the glasses session is ready and the
message queues are empty; connect (5 s timeout), request high priority, MTU 247 (`ConnectionOptions.kt:13`), discover
services, enable notifications on **`bae80011-4f05-4503-8e65-3af1f7329d1f`** ("phone notify") and
**`bae80013-4f05-4503-8e65-3af1f7329d1f`** (data notify); success if at least one subscribed. Retry 2000 ms after
failure/disconnect (`ConnectionOptions.kt:14`).

Decoders (`FK/g2protocol/FaceclawRingEventDecoder.kt:9-91`) — produce `sys-event` with source 2 and **no** ring
metadata (so they bypass §2.11.3):

* 11-byte report `00 09 61 00 code paramLo paramHi tick(LE32)`: code `0x00` → 9, `0x01` → 0, `0x02` → 3, `0x04` → 1,
  `0x05` → 2, `0x08` → 10; anything else (including 9 and 10) → ignored.
* 3-byte report `ff type param`: `03 20` → 9 (hold); `04 01` → 0 (tap); `04 02` → 3 (double tap);
  `05 xx` → xx ≤ 1 ? 2 (SCROLL_BOTTOM, "swipe forward") : 1 (SCROLL_TOP, "swipe backward").

Since the ring keeps sending the same reports to the glasses, direct mode can deliver **duplicates** (once direct,
once via the glasses). Faceclaw does nothing about it.

### 3.3 g2-kit's ring protocol (reference only; not used by Faceclaw)

`K/ble/ring.ts:1-33, 37-82`: service `bae80001-…`, write `bae80012-…`, notify `bae80013-…` (`bae80010/11`
described as unused — Faceclaw nevertheless subscribes to `bae80011`). Frame: `[0x00][4-byte random anti-replay hash]
[0x64][seqGroup 1|2][0x64][seq u8][flags u16 BE: 0 req,1 set,2 push,3 resp][0x00][cmd][sub][0x00][payload]`.
Commands include pairAuth 0x08/0x0d, linkToGlasses 0x0a/0x12 (payload: 2-byte nonce + glasses right-arm MAC
reversed, sent twice by the official app), time sync 0x05/0x12 (`b9 0e 10 ff` + LE32 unix seconds), health pushes on
cmd 0x01. The stock glasses also relay ring health on sid 0x91 (`K/ble/gen/ring_pb.ts:18-214`: `RingDataPackage
{f1 commandId, f2 magic, f3 RingEvent{ringMac, eventId (1 = BLE_ADV), eventParam}, f4 RingRawData{battery,
chargeStates, hr, …}}`). None of this is needed for gestures when the ring is bonded to the glasses.

### 3.4 Ring battery (`G/patches/ring_battery.c`)

The CFW reads the stock dashboard's cached ring battery (never opening a second ring connection) and appends
**settings field 106** to *every* sid-0x09 settings READ response (`ring_battery.c:143-159`,
`settings_ext.c:476-487`):

```
f106 bytes = ['R','B', 1, flags, level]        (key bytes d2 06, length 05)
flags: bit0 = ring connected, bit1 = level valid, bit2 = charging; level 0..100, 255 = unknown/disconnected
```

Mode 17 `[0x11][0x00]` (exactly 2 bytes) asks the master lens to push the same field as a standalone notify:
`08 03 10 00 d2 06 05 52 42 01 <flags> <level>` (sid 0x09, notify flag) (`ring_battery.c:161-168`).
**Faceclaw never sends mode 17** (the constant is only declared, `FK/g2protocol/CfwMessageType.kt:22`); it reads
field 106 from the periodic settings poll (§4.2) and also accepts unsolicited pushes from the right arm
(`GlassesSessionCore.kt:1446-1459`).

Phone parse rules (`FK/g2protocol/BleProtocol.kt:601-629`): body must be exactly 5 bytes `52 42 01 flags level`;
reject if any flag bit other than 0..2 is set; if valid (bit1): require connected (bit0) and level ≤ 100 → `(level,
charging = bit2 ? 1 : 0)`; if not valid: require level == 255 and not charging → `(-1, -1)`; anything else → invalid.
A settings response without a valid field 106 clears the ring battery to unknown (`GlassesSessionSend.kt:931-934`).
The top bar shows "R1" battery from these values (`dashboard-controller.ts:1431-1455`).

### 3.5 Ring advertisement (pairing only)

Name `EVEN R1_<hex6>` (last three octets of the human-order MAC); manufacturer data `45 52` ("ER", company id
0x5245 read LE) + MAC (6 bytes, wire order = reversed) + on ring fw 2.2.7.x an ASCII serial suffix
(`F/app/g2/even-advertisement.ts:13-21, 139-175`).

---

## 4. Wear, batteries, charging, lock

### 4.1 Wear (on-head / off-head) detection

1. **Enable the stock detector** (right arm, sid 0x09, flag 0x20, normal magic, ack expected)
   (`FK/g2protocol/BleProtocol.kt:447-458`, `MessageBuilder.kt:160-173`):
   `G2SettingPackage {f1=1 (DeviceReceiveInfo), f2=magic, f3 DeviceReceiveInfoFromAPP {f5 Wear_Detection_Setting
   {f1 wearDetectionSwitch=1}}}`.
2. **Ask the CFW for the current cached state**: field-101 op 7 (WEAR_QUERY) to both arms (§0.3). The CFW reads the
   stock cached status (1 = off-head, 2 = on-head) and, only if it is 1 or 2, emits a wear event
   (`settings_ext.c:354-357`).
3. **Wear events** (sid 0x10) (`settings_ext.c:238-257`, `K/ble/gen/onboarding_pb.ts:18-32, 84-130, 180-197`):
   `OnboardingDataPackage {f1 commandId=3 (EVENT), f2 magic=0, f5 OnboardingEvent {f1 event=1 (GLS_WEAR_STATUS),
   f2 eventParam = 1 on-head | 0 off-head}}` = `08 03 10 00 2a 04 08 01 10 <0|1>`.
   Stock firmware only sends this during onboarding and with flag 0x00; the CFW retargets both stock call sites
   (0x4ade92, 0x4adef6) to a sender that always emits it via the generic notify sender (right lens)
   (`F/app/g2/firmware/cfw-patches.ts:184-190`). **Recognise it by sid + shape, not by flag**
   (`BleProtocol.kt:723-744`).

Order matters: enable first, then query (Faceclaw queues `enable, queryRight, queryLeft` at the head of the queue,
`GlassesSessionCore.kt:638-656`). Faceclaw only does this when the lock screen is enabled, the CFW revision is
confirmed and the phase is connected (`dashboard-controller.ts:660-672`). Wear changes are emitted only when the
value differs from the cached one; the cache resets to unknown on every session reset and the TS side treats the wear
state as unknown until a fresh report after reconnect (`GlassesSessionControls.kt:447`,
`dashboard-controller.ts:1393-1399`).

### 4.2 Settings read: battery, charging, silent mode, firmware versions

Request (right arm, sid 0x09, flag 0x20) (`BleProtocol.kt:507-517`): `{f1=2 (DeviceReceiveRequest), f2=magic,
f4 {f1 settingInfoType=1 (APP_REQUIRE_BASIC_SETTING)}}`.

Response (ack, matched by magic): `DeviceReceiveRequestFromAPP` in field 4 (`K/ble/gen/g2_setting_pb.ts:197-291`):

| f4.N | Name | Used by Faceclaw |
|---|---|---|
| 1 | settingInfoType | – |
| 2 | autoBrightnessLevel | – |
| 3 / 4 | y / x CoordinateLevelRestored | – |
| **5 / 6** | left / right SoftwareVersion (string) | firmware info |
| 7 / 8 / 9 | headUpSwitch / headUpAngle / headUpAngleCalibration Restored | – |
| 10 | wearDetectionSwitchRestored | – |
| 11 | deviceRunningStatus | – |
| **12** | battery (0..100) | headset battery |
| **13** | chargingStatus (> 0 = charging) | charging mode |
| **14** | silentModeSwitchRestored | silent mode backstop |
| 15..19 | calibrations, autoBrightnessSwitchRestored, unreadMessageCount | – |

CFW additions at the root of the same response: **f100** string `"Faceclaw/34"`, **f104** mic status (21 bytes,
§7.5), **f106** ring battery (§3.4) (`settings_ext.c:476-487`).

Parsing: battery missing → no snapshot (`BleProtocol.kt:746-760`); firmware info requires at least one version string
or the extension (`:797-811`).

**Per-arm battery:** each arm answers a settings read on its own link with its own battery (the arms have
independent batteries) (`FK/g2protocol/FlashPromptFlow.kt:229-257`). The main session only polls the **right** arm,
so the headset battery shown is the right arm's.

Poll cadence (`GlassesSessionSend.kt:218-221, 919-958`, `ConnectionOptions.kt:29-33`):

* immediately after the first session becomes ready (firmware info + battery);
* then when the queues are idle, not suspended, ≥ **5000 ms** since the last user input or (re)connection, and
  ≥ **5 min** since the previous poll;
* every **30 s** while in charging mode.

g2-kit disagreement: `K/ble/docs/settings.md:10-19, 31-36` lists `G2SettingPackage` fields as `1 battery,
2 brightness, 3 wear_detect…`; that is wrong — the generated schema is `f1 commandId, f2 magicRandom,
f3 deviceReceiveInfoFromApp, f4 deviceReceiveRequestFromApp, f5 deviceSendInfoToApp, f6 deviceRespondToApp,
f7 appRespondToDevice` (`K/ble/gen/g2_setting_pb.ts:542-576`), which Faceclaw uses.

### 4.3 Charging ("glasses in the case") mode

When a settings response reports `chargingStatus > 0` (`GlassesSessionSend.kt:983-1004`, `:127-141`):
enter charging mode — clear all queued messages, forget the page and displayed frame, discard pending frames, stop
display traffic and heartbeats (the firmware tears EvenHub down on its own), show "Glasses charging. Battery N%."
and poll settings every 30 s. When charging ends: leave charging mode and force a transport reconnect ("charging
ended"), which rebuilds the session, page and first frame. The glasses case sid 0x81 is not used (§4.8).

### 4.4 Silent mode

See §2.10. State is a property of the glasses: it is deliberately **not** cleared on transport failures / session
resets (`GlassesSessionControls.kt:449-451`) — silent mode can itself be the reason a session was torn down — but it is
reset to unknown on an explicit user disconnect (`GlassesSessionCore.kt:394-399`).

### 4.5 Lock screen

Setting `display.lockScreenEnabled` (default true) (`F/app/ui/dashboard-settings.ts:381-389`).
State machine (`F/app/g2/dashboard-controller.ts:616-658`):

| Trigger | Condition | Result |
|---|---|---|
| wear event off-head | phone locked AND lock enabled | **lock** |
| phone becomes locked | glasses known off-head (`worn === false`) AND lock enabled | **lock** |
| lock setting turned on | phone locked AND off-head | **lock** |
| phone unlocked | – | **unlock** |
| lock setting turned off | – | **unlock** |
| wear event on-head | – | *no change* (only unlocking the phone unlocks the glasses) |

Locked UI: a full-screen opaque compositor surface `lock-screen` at z-order 1000 showing "Glasses locked; unlock the
phone to unlock the glasses." (`F/app/g2/lock-screen.ts:1-24`); input gating per §2.11.4.

Phone lock detection (Android): `KeyguardManager.isDeviceLocked`, re-checked on `ACTION_SCREEN_ON/OFF/USER_PRESENT`
broadcasts and at most once per second from the session worker loop (`FA/FaceclawBleCommunicator.kt:63, 105-124`,
`GlassesSessionControls.kt:586-605`).

### 4.6 Glasses presence (for alarms)

`{connected, worn (null until reported), charging}`; "can carry an alert" = connected ∧ worn === true ∧ ¬charging
(`F/app/g2/glasses-presence.ts:24-46`). Fed from phase, wear and battery reports (`dashboard-controller.ts:1431-1455`).

### 4.7 `ble-proximity.ts`

Not related to locking: an RSSI log-distance model used only to rank pairing candidates (`F/app/g2/ble-proximity.ts:38-127`;
G2 txPower@1 m −62 dBm, n = 2.2; R1 −68 dBm, n = 2.3; zones < 0.5 m / < 3 m / < 10 m).

### 4.8 Glasses case (sid 0x81) — unused

Schema only (`K/ble/gen/glasses_case_pb.ts:18-91`): `GlassesCaseDataPackage {f1 commandId (1 = CASE_INFO), f2 magic,
f3 GlassesCaseInfo {f1 soc, f2 chargeStatus, f3 lidStatus, f4 inCaseStatus, f5 errorCode}}`. Unverified; Faceclaw
infers "in case" from `chargingStatus`.

---

## 5. Sensors

### 5.1 Compass / magnetometer

**Control** — CFW mode 10 (`G/patches/zlib_glue.c:84-91, 528-572`):

| Command | Meaning |
|---|---|
| `[0x0A][0x00]` | stop: `compass_forward = 0`; right lens calls stock `StopIMUCompassFunc` |
| `[0x0A][0x01]` | start with the stock configuration (1000 ms / 5°) |
| `[0x0A][0x02][intervalLE16][minChangeLE16]` | start, then apply `FuncConfig(2, {interval, minChange})`; interval clamped to 50..2000 ms; min-change passed through; on config failure the compass is stopped |

Only the right lens (side 1) touches the IMU (the left cannot open it); both lenses set `compass_forward`. Faceclaw
always uses `[0A 02 64 00 00 00]` (interval 100 ms, min change 0°) to enable and `[0A 00]` to disable
(`GlassesSessionSend.kt:655-692`, `GlassesSessionCore.kt:47-48`). Despite the 100 ms request the observed rate is
about 3 Hz (`F/CHANGELOG:138`).

**Heading notification** (`G/patches/compass.c:89-126`): the CFW wraps the stock heading-report call and additionally
sends, from the right lens, on **sid 0x08 with a notify flag**:

```
{ f1 command = 15 (OS_NOTIFY_COMPASS_CHANGED), f2 magic = 0,
  f10 compass_info_msg { f1 compassIndex = heading (varint, whole degrees 0..359) },
  f100 bytes (12, optional diagnostics) }
```

Diagnostic extension f100 (`compass.c:19-25`):

| Byte | Meaning |
|---|---|
| 0–2 | `'C','M', 1` |
| 3 | magnetic accuracy 0..3 (255 = unavailable) |
| 4 | magnetic anomalies 0..2 (0 none, 1 small/temperature, 2 large; 255 = unavailable) |
| 5 | orientation source: 0 unknown, 1 GRV (game rotation vector, relative), 2 GMRV (geomagnetic RV), 3 RV |
| 6 | flags: bit7 = sample matched this capture; bit0 GRV valid; bit1 GMRV + heading valid; bit2 RV + heading valid; bit3 fresh magnetic bias/flags in this frame |
| 7 | 0 (reserved) |
| 8–11 | LE32 sample timestamp (sensor-hub record time, firmware ms) |

Example (`F/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/CompassTest.kt:10`):
`080f1000520308e702a2060c434d010302038c0098badcfe` → heading 359, accuracy 3, anomalies 2, source 3, flags 0x8C,
sample time 0xFEDCBA98.

Stock calibration notifications on the same sid: command **16** OS_NOTIFY_COMPASS_CALIBRATE_STRAT and **17**
OS_NOTIFY_COMPASS_CALIBRATE_COMPLETE (`K/ble/gen/navigation_pb.ts:430-440`), surfaced with heading −1.

Phone parse (`FK/g2protocol/BleProtocol.kt:1027-1086`): require sid 8, notify flag (0x01/0x06), right arm; command 15
needs field 10 with 0 ≤ heading < 360; diagnostics accepted only when the 12 bytes are `43 4D 01`, byte 7 == 0,
accuracy ≤ 3 or 255, anomalies ≤ 2 or 255, source ≤ 3 — otherwise the heading is delivered without diagnostics
(255 → −1 in the API).

Calibration preservation (Faceclaw/14, `G/patches/compass.c:40-63`): while the framebuffer lease is valid the CFW
keeps the driver's current magnetic accuracy (and cached bias) across IMU reconfiguration instead of resetting it,
so stopping/starting the compass does not lose calibration.

Session lifecycle (`GlassesSessionCore.kt:149-164, 586-618`, `GlassesSessionSend.kt:161-167`,
`GlassesSessionControls.kt:159-167`):

* Reference-counted **owners** (e.g. `"compass"` app, Navigate worker): on while any owner wants it.
* Desired state survives reconnects; it is (re)sent once the page exists and the queue is idle.
* `compassMaybeOn` is set on every enable and cleared only when a disable is **acked**; before any EvenHub shutdown
  a forced disable is queued if it may still be on (the magnetometer keeps running without a page and drains the
  battery). Mode 11 cleanup also stops it.

Phone heading math (`F/app/apps/compass/calibration.ts:15-58`, `heading.ts:41-55`, `F/app/native/geomagnetic.ts`):

```
magnetic = normalize(raw + offset)        offset: persisted integer degrees in (-180, 180]
                                          (key "compass.calibrationOffsetDegrees"), set by the wearer by
                                          aiming at a landmark (scroll ±1°, double-tap to accept)
declination = android.hardware.GeomagneticField(lat, lon, alt=0, now).getDeclination()  (east positive)
true = normalize(magnetic + declination)  shown when reference == "true" (default) and declination known,
                                          else magnetic is shown
normalize(x) = ((x % 360) + 360) % 360
```

The magnetometer is in the right temple, so the raw heading depends on how the glasses sit; that is why the wearer
offset exists.

### 5.2 IMU (accelerometer)

Enable/disable (right arm, sid 0xE0, flag 0x20, normal magic, ack = Cmd 20 with f23 `{f1 IMUReportEnStatus}`,
timeout only logged) (`FK/g2protocol/BleProtocol.kt:409-422`, `MessageBuilder.kt:130-143`,
`K/ble/gen/EvenHub_pb.ts:143-169`):

```
{ f1 Cmd = 19 (APP_REQUEST_OPEN_IMU_PACKET), f2 magic, f22 IMU_CtrlCmd { f1 IMUReportEn = 1|0, f2 reportFrq } }
reportFrq is included only when enabling and > 0.
```

Samples: SysEvent `EventType = 8 (IMU_DATA_REPORT)`, `EventSource` (1 R arm, 2 ring, 3 L arm), `f3 IMUData {f1 x,
f2 y, f3 z}`. **The firmware sends x/y/z as fixed32 floats (wire type 5), although the g2-kit schema declares
`double`** (`BleProtocol.kt:1205-1262`, `K/ble/gen/EvenHub_pb.ts:920-934`). A SysEvent carrying `IMUData` is
delivered to IMU listeners; a pure type-8 event is never treated as user input and does not delay battery polling
(`GlassesSessionCore.kt:1515-1522, 1550-1566`).

`reportFrq` values used: the EvenHub SDK bridge snaps to 100..1000 in steps of 100 (`F/app/apps/evenhub/session.ts:699-720, 1269-1272`);
the developer demo uses 200. Units are not known (rate vs period). g2-kit never observed `IMUData` populated
(`K/ble/ring.ts:345-392`) and Faceclaw labels the stream experimental (`F/app/native/imu.ts:1-15`).

### 5.3 Ambient light sensor (ALS)

Hardware/stock behaviour (`G/patches/als_sensor.c:4-50`): TI **OPT3001** on the master temple (I²C 0x45). Stock only
opens it when the user's auto-brightness setting is on; it polls every 1000 ms, keeps a 5-sample ring, `peak =
max(ring)`, and applies a 6-row curve `{≤10→35, ≤200→50, ≤400→70, ≤1000→70, ≤1300→100, else 100}` scaled by a learned
`scale_q10` (0x266..0x59A), stepping the panel by 2 (5 when far) every 200 ms — the visible flicker. The "ALS value"
is roughly **tenths of a lux** (`raw * lux_base / 1e6` with NV calibration, else `raw / 10`); readings taken while
IMU pitch < −30° are discarded.

**CFW mode 16** (`als_sensor.c:52-73, 302-325`, `zlib_glue.c:110-116`) — handled on both lenses, acts only on the
master:

| Command | Meaning |
|---|---|
| `[0x10][0x00]` | QUERY: send one report from the driver's current globals |
| `[0x10][0x01][flags][intervalLE16][minDeltaLE16][heartbeatLE16]` | PASSIVE START (idempotent): hook the hub's ALS timer message, open the sensor if closed, poll every `interval` ms (clamped 100..5000; default 500 if absent), report when `|value − last reported| ≥ minDelta` (default 1) or `heartbeat` ms since the last report (default 5000; 0 = never); the first poll after a start always reports. `flags` bit0 = bind to the framebuffer lease (auto-stop when it lapses/releases). Stock auto-brightness never steps the panel while passive. |
| `[0x10][0x02]` | PASSIVE STOP: restore stock handler; close the sensor if the CFW opened it and auto-brightness is off |

**Report** (sid 0x09, **flag 0x00**, right lens, magic 0) (`als_sensor.c:75-86, 169-197`):
`08 03 10 00 ca 06 18 <24-byte body>` with body:

| Offset | Size | Meaning |
|---|---|---|
| 0–2 | 3 | `'A','L', 1` |
| 3 | 1 | reason: 0 query, 1 started, 2 poll, 3 stopped |
| 4 | 1 | flags: bit0 sensor opened, bit1 passive hook installed, bit2 stock auto-brightness on, bit3 last passive read OK |
| 5 | 1 | stock status (1 start-read, 2 adjusting, 3 polling) |
| 6–9 | 4 | ALS value LE32 (≈ 0.1 lux) |
| 10–13 | 4 | peak LE32 (max of last 5) |
| 14–15 | 2 | stock target level LE16 |
| 16 | 1 | current brightness setting |
| 17 | 1 | gear (row 0..5 of the stock curve) |
| 18–19 | 2 | scale_q10 LE16 (1024 = 1.0) |
| 20–23 | 4 | firmware ms tick when built |

Phone: Kotlin validates `41 4C 01` and ≥ 24 bytes, feeds right-arm reports into the brightness policy and passes raw
bodies to listeners (`BleProtocol.kt:586-599`, `GlassesSessionCore.kt:1467-1476`); TS decoder
`F/app/native/ambient-light.ts:66-86`.

### 5.4 Brightness (phone-owned policy, CFW mode 30)

Faceclaw does **not** use the stock brightness setting; it owns the ALS in passive mode and sends only a transient
target plus fade time.

**CFW mode 30** (`G/patches/brightness.c:223-238`, `brightness.h:1-16`):

```
[0x1E][version = 1][level 2..100][visible 0|1][durationLE16 ≤ 3000]     (exactly 6 bytes)
```

Rejected (−1 → NACK) if the size/version/range is wrong, duration > 3000, or the framebuffer lease is inactive.
Firmware behaviour (`brightness.c:199-297`), per lens, applied from the display task:

* The latest request wins (single atomic request word).
* On first ownership: start at level 2, invisible.
* Visibility or (while visible) level change → fade from the current level to `visible ? level : 2` over `duration`
  using smoothstep `3t² − 2t³` evaluated on elapsed time.
* **Wake** (invisible → visible) waits for the next PRESENT (mode 28) before starting the fade, so the fade never
  reveals an old frame.
* The panel is written only when the computed level changes; timer at 40 ms (25 Hz) while fading, 1000 ms otherwise.
* When invisible and the level reaches 2, the framebuffer is zeroed and flushed (black); the displayed frame is frozen
  while fading out / asleep; frames keep staging for the next wake.
* Lease loss or cleanup restores the stock brightness level.
* Level 2 is the intentional fade endpoint ("dimmest").

**Phone policy** (`FK/g2protocol/BrightnessPolicy.kt:1-101`, `FK/g2protocol/session/GlassesSessionBrightness.kt:1-42`):

State: `automatic` (default true), manual level (2..100, default 50), `minimum` (default 20), `maximum` (default
100), curve (default `"0:0,0.2:7,1.5:33,5:100"`), fade (280 ms), `target`, filtered lux.

```
curve knots: "lux:percent" pairs, 2..16 of them; stored as (ln(1+lux), percent).
  Valid iff first == 0:0, last percent == 100, lux strictly increasing, percent non-decreasing,
  0 ≤ lux ≤ 1e6, 0 ≤ percent ≤ 100.  (BrightnessPolicy.kt:82-101, F/app/g2/brightness-curve.ts:1-26)
evaluate(lux):
  x = ln(1 + max(lux, 0))
  fraction = piecewise-linear interpolation of percent over x (clamped to the end knots) / 100
  return clamp(round(min + (max - min) * fraction), min, max)
sample(report, now):   // only reason == 2 (POLL) with flags & 0x0B == 0x0B (opened, hooked, read ok)
  lux = ALSvalue / 10.0
  if no previous sample or (now - last) > 10000 ms: filtered = lux
  else: tau = (lux > filtered) ? 0.8 s : 3.0 s   // brighten fast, dim slow
        filtered += (lux - filtered) * (1 - exp(-(now - last)/1000/tau))
  if automatic: next = evaluate(filtered); change target only if |next - target| ≥ 5 (AUTO_CHANGE_THRESHOLD)
setMode/configure: manual → target = manual level; auto → target = evaluate(filtered) if known,
  else clamp(target, min, max). Explicit changes apply immediately (no deadband).
```

Defaults reproduce measured preferences: raw ALS 0 → 20, 2 (0.2 lx) → 26, 15 → 46, 50 → 100
(`F/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/BrightnessPolicyTest.kt:14-22`).

When messages are sent (`GlassesSessionBrightness.kt:6-42`), only with CFW detected, session ready, page created and
not suspended:

* With every desired image enqueue the phone sends mode 30 with `visible = !(fingerprint starts with "blanked:")`
  **before** the image, so visibility is tied to the frame it accompanies (`GlassesSessionSend.kt:733-734`).
* From the maintenance loop when the target changed (not while an image or brightness message is pending).
* Duration: visibility change → fadeMs (280); level change in auto → 1200 ms; manual → 120 ms.
* Skipped if (target, visible) equals the last sent pair; on ACK timeout the cache is invalidated and the next frame
  re-sends.
* **Passive ALS keep-alive**: at most every 2000 ms (and whenever no ALS control is pending) the phone re-sends
  `[10 01 01 <interval LE16> 00 00 e8 03]` — bind-to-lease, min-delta 0 (report every poll), heartbeat 1000 ms,
  interval 500 ms (auto), 5000 ms (manual) or 250 ms (ALS demo). Every re-send also produces a `started` report.

User settings (`F/app/ui/dashboard-settings.ts:34-36, 335-368`): brightness `auto | 2 | 10 | 20 | … | 100` (default
auto; "0" normalises to 2), auto min/max from `2,5,10,20,25,30,40,50,60,70,80,90,100` (defaults 20 / 100), curve
string, fade 280 ms fixed.

Stock setter (reference only, unused by Faceclaw; `BleProtocol.kt:424-445`): sid 0x09
`{f1=1, f2=magic, f3={f1 DeviceReceive_Brightness {f1 autoAdjust=1 | f2 brightnessLevel}}}` — the two are
alternatives; a level alone is a temporary override while auto stays on; stock clamps 2..100.

---

## 6. Piezo buzzer (CFW mode 5)

The G2 "speaker" is a PWM piezo buzzer; only square-wave tones are possible (`G/patches/zlib_glue.c:30-62`).
Mode 5 is handled on **every lens selected by the transport** (Faceclaw: both) and never blocks: all kinds are
timer-driven and return immediately (`G/patches/zlib_glue.c:55-59, 439-446`; `G/demos/sound-test.ts:15-21` is the
demo written to confirm this by timing the acks).

| Kind | Bytes | Behaviour (`zlib_glue.c:439-499`) |
|---|---|---|
| 0 | `[05][00][type]` | preset voice 0..8 from the flash table (`DRV_BuzzerPlayAfterQueue`); >8 ignored |
| 1 | `[05][01][note][oct][beat]` | one table tone: note 1..7, octave 0..3 (28-entry table), beat = duration in ≈62 ms units, beat ≠ 0 |
| 2 | `[05][02]` | stop / silence |
| 3 | `[05][03][freqLE16][duty][msLE16]` | raw tone: freq clamped 1..20000 Hz, duty 0..100 %, ms ≥ 1; auto-stops via the buzzer driver's own timer |
| 4 | `[05][04][n][ (freqLE16, duty, msLE16) × n ]` | **tone sequence** (Faceclaw's only kind): n capped to the steps present and to **48** (`CFW_SEQ_MAX`, `G/patches/cfw_context.h:15`); steps copied into the CFW context and played by a one-shot timer; each step clamped like kind 3; **duty 0 = rest** (PWM off for ms); PWM off after the last step |

Any new mode-5 message first stops an in-flight sequence (a new sound supersedes the old one). Mode 11 cleanup also
stops it. Preset meanings observed in the demo (`G/demos/sound-test.ts:129-137`): 0 beep, 1 long repeating alarm
(~15×, several seconds), 2 ring, 3 beep, 4 two beeps, 5 three low beeps, 6 falling, 7 rising, 8 beep.
(The demo sends mode 5 through the pre-Faceclaw/8 EvenHub ImageRawData path; current CFWs accept custom commands only
over SID 0xF0 — `G/patches/settings_ext.c:450`.)

Phone side (`F/app/ui/sound-effects.ts:18-217`, `F/app/native/worker-buzzer.ts`, `GlassesSessionCore.kt:1067-1091`):

* Payload builder: `[5][4][count]` + per step `freq = clamp(round(f), 1, 20000)` LE16, `duty = clamp(round(d),
  0, 100)` (default 50), `ms = clamp(round(ms), 1, 65535)` LE16; count ≤ 48.
* Rest = `{freq: 1, duty: 0, ms}`.
* Effects longer than 48 steps are split into phrases (each ≤ 48 steps, broken at rests); the player sends one
  message per chunk and **sleeps the chunk's total duration** before sending the next.
* Notes: equal temperament, A4 = 440 Hz, `hz = round(440 · 2^((midi − 69)/12))`; mini-DSL `"NOTE/ms …"` with `.`/`r`
  for rests; `sweep(from, to, ms, steps)` = geometric frequency steps.
* Catalog (names): coin, powerup, oneup, laser, pew, zap, warp, explosion, success, notify, chime, doorbell, error,
  deny, tick, doublebeep, sonar, alarm, siren, wail, heartbeat, sadtrombone, r2d2, questcomplete, nokia, imperial,
  mario, scale. `questcomplete` plays once on the first connection after onboarding
  (`dashboard-controller.ts:2586-2597`). Games persist a per-app `<appId>.soundOn` toggle (`F/app/ui/sound-setting.ts`).
* Preconditions: connected, session ready, page created, payload ≥ 3 bytes. Sent as a CFW command (so it is ordered
  with frames and subject to CFW replay — a replayed sequence restarts the sound).

---

## 7. Microphone audio

### 7.1 Stock enable/disable (EvenHub AudioCtrCmd)

Request (right arm, sid 0xE0, flag 0x20, normal magic) (`FK/g2protocol/BleProtocol.kt:394-402`,
`MessageBuilder.kt:115-128`):

```
{ f1 Cmd = 15 (APP_REQUEST_AUDIO_CTR_PACKET), f2 magic, f18 AudioCtrCmd { f1 AudoFuncEn = 1 | 0 } }
```

Ack: `{f1 = 16 (OS_RESPONSE_AUDIO_CTR_PACKET), f2 magic, f19 AudioResCmd {f1 AudioStat}}` — Faceclaw matches it by magic
only (`K/ble/gen/EvenHub_pb.ts:105-110, 221-242, 1142-1149`).

> g2-kit disagreement: `K/ble/docs/audio.md:18-31` and `K/ble/docs/events.md:61-64` say "Cmd=18"/"Cmd=19"; those are
> the **field numbers** (the comment at `K/ble/audio.ts:8-10` repeats the mistake). The command values are 15/16;
> g2-kit's own code uses the enum constant 15 (`K/ble/audio.ts:59-63`) and Faceclaw uses 15 (`BleProtocol.kt:137`).

Behaviour (`GlassesSessionCore.kt:431-487`, `GlassesSessionControls.kt:199-219`, `GlassesSessionSend.kt:906-917`):

* Preconditions: session running and ready, **EvenHub page created and not suspended** (the mic is bound to the
  EvenHub plugin task; the firmware rejects AudioCtrCmd without an active page — `K/ble/audio.ts:4-7`).
* The enable is queued at the head of the queue; the caller blocks until the ack or `3500 + 2000 + 500 ms`.
  Ack of an enable → capture active. **An ack timeout of the audio-control message triggers a transport reconnect.**
* Stop: clear the listener immediately, drop queued audio-control messages, send the disable if still connected and
  wait for its ack.
* The enable dies silently with the page (suspend, charging, disconnect, resume): `audioCaptureActive` is cleared on
  resume/prelude/disconnect and callers must re-enable (Faceclaw re-arms a parked capture on the first rendered frame,
  `dashboard-controller.ts:1467-1474`). Heartbeats must keep running while capturing (`K/ble/docs/gotchas.md:79-81`).
* The EvenHub suspend is postponed while a capture is held (`dashboard-controller.ts:843-849`).

### 7.2 Packet format (characteristic `…6402`)

Each BLE notification is one **205-byte** packet, **not** envelope-framed (`FK/audio/Lc3PacketFramer.kt:20-43`,
`K/ble/lc3-decoder.ts:8-17`):

| Offset | Size | Meaning |
|---|---|---|
| 0–199 | 5 × 40 | five LC3 frames, 10 ms each |
| 200–201 | 2 | SSR — signed LE16 signal-strength ratio computed on the raw stereo capture before the mono downmix |
| 202–203 | 2 | direction-of-arrival angle — signed LE16 degrees (asin-derived TDOA) |
| 204 | 1 | packet counter, mod 256 |

LC3 parameters: **16 000 Hz, mono, 10 ms frames (160 samples), 40 bytes/frame (32 kbit/s)**; 5 frames = 50 ms =
800 samples per packet; **20 packets/s** (≈ 32.8 kbit/s on air) (`K/ble/audio.ts:30-47`).

> g2-kit disagreement: `K/ble/docs/audio.md:9-13, 36-53` claims service "6450", a 2-byte `0xCC/0xCD` header, one
> 203-byte frame and 20 ms per notification. That is wrong; the layout above is what Faceclaw and g2-kit's own
> decoder implement. The trailer (SSR/angle) is Faceclaw's reading of the firmware's `service_audio.c`.

### 7.3 Which arm; duplicates

The stream arrives on the **left** arm's `…6402` characteristic even though the enable is written to the right arm
(`K/ble/audio.ts:11-15`). Faceclaw subscribes on both arms, forwards packets from either arm with the arm label
(`GlassesSessionCore.kt:1589-1605`), counts non-"L" packets as `wrongArmPackets` but still decodes them
(`FK/audio/VoiceCaptureSession.kt:743-761`). Both arms may relay the same packet, so the framer drops duplicates by
counter (below).

Queueing: bounded queue of **80** packets, drop-oldest; expected inter-packet gap 50 ms, "late" if > 90 ms
(`VoiceCaptureSession.kt:61-64, 131`, `FK/audio/VoicePorts.kt:90-118`).

### 7.4 Decoding

Framer (`FK/audio/Lc3PacketFramer.kt:65-92`):

```
if packet.size != 205 -> decodeErrors++, drop
gap = (counter - lastCounter) & 0xff        (skip check for the very first packet)
if gap == 0 or gap >= 128 -> duplicatePackets++, drop     // duplicates & late copies across the wrap
missingPackets += gap - 1
decode the 5 frames (bytes 0..199) -> 800 S16 samples; on failure decodeErrors++, drop
lastCounter = counter; lastSsr = s16(200); lastAngle = s16(202)
```

Faceclaw does **not** run packet-loss concealment for missing packets; g2-kit's `G2AudioDecoder.feed` conceals up to
8 missing packets (400 ms) with `lc3_decode(NULL)` and treats larger gaps as a resync (`K/ble/lc3-decoder.ts:224-330`).
The optional "beam filter" drops packets whose firmware angle is outside the configured wedge when `ssr > 0`
(`VoiceCaptureSession.kt:451-478`).

Codec: Google **liblc3 v1.1.3** (Apache-2.0) compiled into `libfaceclaw_lc3.so` via JNI (`F/App_Resources/Android/app.gradle:25, 96-140`,
`F/App_Resources/Android/src/main/native/faceclaw_lc3_decoder.c:7-91`, `FA/FaceclawLc3Decoder.kt:24-35`):
`lc3_decoder_size(10000, 16000)`, `lc3_setup_decoder(10000, 16000, 0, mem)`, then per frame
`lc3_decode(dec, frame, 40, LC3_PCM_FORMAT_S16, pcm + 160·i, stride 1)`. g2-kit also uses liblc3 through FFI
(`K/ble/lc3-decoder.ts:33-58`) — there is no pure-TS/Java decoder in any of the references.

**Pure-Kotlin/Java feasibility:** feasible but not free. Only the LC3 *decoder* at one configuration (16 kHz, 10 ms,
40 bytes, mono) is needed: bitstream side info + arithmetic decoding of spectral lines, noise filling, global gain,
TNS, SNS (spectral noise shaping) interpolation, inverse MDCT (160-point, low-delay window), LTPF post-filter and
PLC. liblc3's decoder path is a few thousand lines of portable C with fixed tables; a straight port to Kotlin (float
arithmetic) is realistic (~2–4k lines) and license-compatible (Apache-2.0 → GPLv3). Bit-exactness with liblc3 is not
required for ASR, but correctness must be validated against liblc3 output (e.g. SNR on recorded packets). The
pragmatic choice for a first implementation is the NDK/JNI route exactly as Faceclaw does; a Kotlin port can replace
it later. Android's public APIs do not expose a generic LC3 decoder for arbitrary frames.

### 7.5 CFW mic_control (experimental; "Microphones" app extended path)

Rides the settings channel (`G/patches/mic_control.c:39-91, 157-178, 359-429`). **Hardware arming is gated and
"ABI-inferred"; the UI defaults it off** (`F/app/apps/microphones/mic-protocol.ts:36-41`). Setting
`developer.useMicControl` (default true) only selects this path in the Microphones app
(`F/app/ui/dashboard-settings.ts:544-551`).

* Control (phone→glasses, sid 0x09, flag 0x20, magic 0, per temple): `{f1=1, f2=0, f103=['M','C',1,op,…]}`
  (`BleProtocol.kt:550-559`). Ops: 1 CONFIGURE `[src(0 codec DMIC/I2S,1 PDM), chanMask(bit0 front, bit1 rear),
  codec(0 LC3,1 raw), fmt(0 16-bit,1 24,2 32), rateLE16 (100 Hz units, clamp 80..480), brLE16 (100 bps units, ≤ 5000),
  flags(bit0 BEAMFORM, bit1 ARM_HW)]`; 2 QUERY; 3 STOP; 4 RENEW. CONFIGURE/RENEW arm a 90 s fail-open lease; the app
  renews every 30 s.
* Status (glasses→phone, sid 0x09, flag 0x00, both temples on their own links, and appended to every settings READ):
  `f104 = ['M','C',1, active, src, chanMask, codec, fmt, rateLE16, brLE16, flags, hwArmed, sideId(1 R / 2 L),
  framesLE32, effRateLE16]` (21 bytes).
* Stream: `'SM'` frames on `…6402` (same characteristic as stock LC3), 21-byte header `['S','M',1, flags(bit7 =
  truncated), seqLE16, tickLE32, nCh, rateDivLE16 (effective, 100 Hz units), fmt, codec(actual; currently always 1 =
  raw PCM), angleS16, ssrS16, payLenLE16]` + payload ≤ 1600 bytes; stereo arrives as two concatenated 400-byte channel
  blocks (front, rear). Capture always runs at 16 kHz regardless of the requested rate.
* Phone: `startG2AudioForwarding` forwards `…6402` packets **without** sending AudioCtrCmd
  (`GlassesSessionCore.kt:863-892`).

---

## 8. iOS notifications: ANCS relay (brief; iOS only)

Android does not need this (Android reads notifications locally). The CFW exposes the glasses' own ANCS client
connection to the phone app (`G/patches/ancs_relay.c:1-234`, `F/app/g2/ancs-client.ts:1-287`):

* Commands are written raw (no `aa 21` envelope) to the right arm's write characteristic; the CFW intercepts EUS writes
  starting `'A','N'`: `['A','N', 1, op, tokenLE32, …]`, op 0 START (8 bytes; requires the framebuffer lease), op 1 STOP,
  op 2 = an ANCS Control-Point command (9..80 bytes total: GetNotificationAttributes, GetAppAttributes,
  PerformNotificationAction). Write results are ATT error codes.
* Replies are notifications on handle 0x844 (the notify characteristic): `['A','N', 1, kind, tokenLE32, seqLE16,
  flags(bit0 first, bit1 last), data…]`, chunked to MTU−3 (≤ 244). kind 0 status (0 start accepted, 1 subscribed/ready,
  2 error), 1 Notification Source (8 bytes), 2 Data Source fragments, 3 CP write completed.
* Faceclaw's client re-assembles by kind/sequence, rejects stale tokens, retries every 5 s, timeouts 10 s.
  Firmware revision 16 introduced the current form (`F/app/g2/ancs-client.ts:3`).

---

## 9. Time sync, clock and dashboard messages

* Faceclaw sends **no** time-sync, dashboard or widget messages to the glasses; the clock and status bar are drawn
  on the phone into Faceclaw's own frames. (The dashboard schema exists in `K/ble/gen/dashboard_pb.ts` but is unused.)
* The only dashboard-related traffic relevant to input/wake is the deferred dashboard launch (§2.7) and the sid-0x0D
  sync-info notification (§2.8). If the stock dashboard does appear (fallback), it shows the glasses' own clock,
  which Faceclaw never sets (see Open questions).
* The ring has its own time base (ring tick, §2.5) and its own time-sync command in g2-kit (§3.3); Faceclaw does not
  sync the ring.

---

## 10. Timing and constants summary

| Constant | Value | Source |
|---|---|---|
| Framebuffer / wake lease length | 90 000 ms | `G/patches/settings_ext.c:101` |
| Lease renewal (phone) | 45 000 ms | `FK/g2protocol/session/GlassesSessionCore.kt:44` |
| Lease control delivery wait | 1 500 ms | `GlassesSessionCore.kt:45` |
| Unclaimed wake fallback | 400 ms | `settings_ext.c:102` |
| Claimed wake fallback | 5 000 ms | `settings_ext.c:103` |
| CLAIM delivery wait before resume prelude | 500 ms | `GlassesSessionCore.kt:1230-1232` |
| Wake READY timeout | 4 500 ms | `F/app/g2/dashboard-controller.ts:153` |
| EvenHub suspend delay after screen off | 5 000 ms | `dashboard-controller.ts:152` |
| CFW cleanup drain/ack wait | 4 000 ms | `GlassesSessionCore.kt:46` |
| Ring dedup window | 100 ring ticks | `F/app/ui/input-monitor.ts:34-35` |
| Long-press escape-menu timer | 4 000 ms | `F/app/ui/shell/shell.ts:211` |
| Glanceboard tap duration | 3/5/7/10 s (default 5 s) | `F/app/apps/glanceboard/glanceboard-settings.ts:58-87` |
| Settings/battery poll | first at connect, then 5 min (idle ≥ 5 s), 30 s charging | `FK/g2protocol/ConnectionOptions.kt:29-33` |
| Generic ack timeout (stock sids) | 3 500 ms | `ConnectionOptions.kt:25` |
| CFW ack timeout / retries | 500 ms / 3 | `FK/g2protocol/CfwMessageWindow.kt:10-11` |
| Compass request | interval 100 ms, min change 0° | `GlassesSessionCore.kt:47-48` |
| Compass interval clamp | 50..2000 ms (stock default 1000 ms / 5°) | `G/patches/zlib_glue.c:531-557` |
| ALS interval clamp | 100..5000 ms (default 500) | `G/patches/als_sensor.c:135-137` |
| ALS keep-alive re-send | ≥ 2 000 ms; interval 500 / 5000 / 250 ms; heartbeat 1000 ms | `GlassesSessionBrightness.kt:30-42` |
| Brightness fade | 280 ms (visibility), 1200 ms (auto change), 120 ms (manual); ≤ 3000 ms | `GlassesSessionBrightness.kt:13-14`, `brightness.c:226-228` |
| Brightness auto deadband | 5 levels | `FK/g2protocol/BrightnessPolicy.kt:83` |
| Lux filter | τ 0.8 s brighten / 3.0 s dim; reset after 10 s gap | `BrightnessPolicy.kt:41-63` |
| Buzzer sequence cap | 48 steps | `G/patches/cfw_context.h:15` |
| Buzzer freq / duty / ms | 1..20000 Hz / 0..100 % / 1..65535 ms | `G/patches/zlib_glue.c:469-475` |
| Mic LC3 | 16 kHz, 10 ms, 40 B/frame, 5 frames/packet, 205 B, 20 pkt/s | `FK/audio/Lc3PacketFramer.kt:22-38` |
| Mic queue | 80 packets, drop oldest | `FK/audio/VoiceCaptureSession.kt:62` |
| Direct ring MTU / retry | 247 / 2000 ms | `ConnectionOptions.kt:13-14` |
| Phone lock re-check | ≤ 1/s + broadcasts | `GlassesSessionControls.kt:586-596` |

---

## 11. Gotchas & pitfalls

1. **CFW pushes use flag 0x00 and magic 0.** Wake/idle events (f102), ALS reports (f105) and mic status (f104) are sent
   with the stock "reply" sender (`FW_SEND` = 0x47ef05, flag 0) (`G/patches/gesture_fwd.c:194-196`,
   `settings_ext.c:188, 205`, `als_sensor.c:117, 196`, `mic_control.c:354`). Do not filter settings-channel
   pushes by notify flag; dispatch on field presence. They must not be treated as acks (magic 0 is outside the phone's
   magic range). Conversely the ring-battery push (f106), compass (sid 8), ring SysEvents and CFW wear events use the
   notify sender.
2. **Stock wear events use flag 0x00** — recognise sid 0x10 by shape (`BleProtocol.kt:723-728`).
3. **`SID_STATE_CHANGE = 0x0` bug** (§2.8): use 0x0D.
4. **Only the right arm emits input**; decode EvenHub/Even AI/compass/ALS only from the right arm (wear events may be
   accepted from any arm). The left arm is silent for async events (`K/ble/docs/gotchas.md:108-115`).
5. **Ring input never reaches the stock UI while the framebuffer lease is held** — even with no EvenHub page. A ring
   double tap while suspended therefore arrives as an ordinary SysEvent DOUBLE_CLICK (source 2), not as a deferred
   wake; only temple double-taps and head-ups use the deferred-wake path. If the lease lapses, ring gestures revert to
   stock behaviour (quit modal on long press, stock 100-tick filtering, stock dashboard on double tap).
6. **Re-implement the 100-tick ring filter** on the phone (§2.11.3); without it, the unfiltered CFW stream produces
   double gestures. Press (10) and release (8) are exempt; tick 0 is a "no previous" sentinel; use unsigned 32-bit
   arithmetic.
7. **Event 14's source is 0 on the legacy path** — accept `eventType 14` with source 0 *or* 2 as ring-press, and nothing
   else. Unknown source values map to "ring" for other gestures.
8. **Temple swipes arrive as TextEvents without a source; taps as SysEvents.** Handle scrolls from both kinds; ignore
   TextEvent CLICK to avoid double taps.
9. **Tap-then-hold while suspended opens the stock Menu** — the CFW defers only dashboard launches (app id 1).
10. **Idle/wake events are only meaningful while the phone has intentionally suspended EvenHub** (Faceclaw ignores them
    otherwise). Always send CLAIM immediately (400 ms fail-open!) and READY only after the first frame is displayed.
11. **Wake lease must be fresh before suspending** — Faceclaw re-acquires it and waits for delivery right before the
    shutdown, and postpones the suspend if that fails.
12. **Mic enable is page-scoped and fatal on timeout**: without a live EvenHub page AudioCtrCmd is rejected; after any
    resume the mic must be re-enabled; heartbeats must continue during capture; Faceclaw reconnects on an audio-control
    ack timeout.
13. **Audio arrives on the left arm while the command goes to the right arm**; subscribe to `…6402` on both and
    de-duplicate by the counter at byte 204 (gap 0 or ≥ 128 = duplicate/late).
14. **IMU floats are fixed32**, not doubles (`BleProtocol.kt:1205-1210`).
15. **Compass must be explicitly disabled before EvenHub shutdown** — the magnetometer keeps sampling without a page;
    only an acked disable clears the "maybe on" flag.
16. **Brightness mode 30 requires the framebuffer lease** and must be ordered with frames: send it before the frame
    whose visibility it describes; a wake waits for the next PRESENT.
17. **The ALS passive start is re-sent every ~2 s** by Faceclaw; each resend emits a `started` report and resets the
    "first poll always reports" logic. Only `poll` reports with flags 0x0B may drive the policy.
18. **Single-frame parsing on Android**: `GlassesSessionCore.onNotification` parses each notification as one envelope
    frame and does not use `BleProtocol.splitFrames`/`MessageReceiver`, although the firmware can pack several frames
    into one notification and some stacks truncate values > 64 bytes (`BleProtocol.kt:929-958`). A re-implementation
    should reassemble with a per-link stream parser (like `FK/g2protocol/MessageReceiver.kt:28-93`, which also verifies
    the CRC).
19. **Direct ring mode duplicates gestures** (glasses path + direct path) and its events bypass the ring filter.
20. **Buzzer and other CFW commands can be replayed** by the go-back-N recovery; a replayed sequence restarts the sound.
    A new mode-5 message always supersedes the playing sequence, so pace multi-phrase effects by their duration.
21. **Silent mode makes the glasses look dead** (no input, no app launch, display off) while BLE is healthy; the "off"
    transition is not reliably pushed, so re-read field 4.14 on every poll.
22. **g2-kit docs are wrong in several places** (container event fields, AudioCtrCmd "Cmd=18/19", audio header/service,
    settings field numbers). Trust the generated schemas and the Faceclaw code.
23. **Head-up forwarding depends on the stock head-up switch**, which Faceclaw never sets; users must enable head-up in
    the stock settings (or the re-implementation must send `DeviceReceive_Head_UP_Setting`).
24. **Lock gate subtlety**: while locked, a *temple* double-tap with the page up is ignored (only ring/watch double-tap
    or a deferred display-wake toggles the display).

---

## 12. Open questions

1. **Raw source 0 vs 1 → left vs right temple**: Faceclaw assumes 0 = left, 1 = right for idle events
   (`BleProtocol.kt:105-107`); the firmware comments only say "0/1 = temple touchpads". Also unknown: exactly how the
   stock SysEvent sender maps raw sources for CFW events 9/10/11 (Faceclaw relies on it producing 1/2/3).
2. **Ring report fields `aux` and `speed`** (and the unused wire types 3/6/7): semantics are unknown; Faceclaw only
   displays them. The unit of the ring tick (ms? 10 ms?) is unknown; the 100-tick window was copied from stock.
3. **Physical direction of temple swipes** vs SCROLL_TOP/BOTTOM is not documented in the code.
4. **Stock handling of long-press events 9/10 without the CFW** on 2.3.0.24 (the enum values exist; whether stock ever
   sends them to EvenHub is unclear).
5. **Notify flag byte** produced by the firmware's notify sender (0x01 vs 0x06) and the flag of the silent-mode push
   are not pinned down; accept both notify values and do not depend on the flag for settings pushes.
6. **IMU `reportFrq` units** (Hz vs period) and whether IMU samples are populated at all on 2.3.0.24.
7. **Compass calibration events 16/17**: whether the stock firmware emits them outside the stock Navigation app.
8. **Mic stream on the right arm**: whether the right arm ever sends its own (non-duplicate) audio; which physical mics
   feed the stock mono downmix.
9. **Stock dashboard visibility during fallback**: while the framebuffer lease is still held (it is kept during
   suspension) the CFW suppresses stock repaints and brightness may still be at the "invisible" level 2 — the stock
   dashboard launched by the 400 ms / 5 s fallback might not actually be visible. Not verified.
10. **Glasses clock**: Faceclaw never sets the glasses' time; whether the stock dashboard clock is correct without the
    official app is unknown.
11. **Per-arm battery display**: whether the left arm's settings read (not done by the main session) is needed to show
    a meaningful combined battery; stock semantics of field 4.12 per arm.
12. **Buzzer on both lenses**: mode 5 executes on every selected lens; whether both temples contain a buzzer (double
    sound / phase) is not documented.
13. **CFW mic_control hardware path** is explicitly unvalidated in the firmware (ABI-inferred seams); treat as
    experimental and keep ARM_HW off by default.
