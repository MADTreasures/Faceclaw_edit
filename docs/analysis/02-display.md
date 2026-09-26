# 02 — Display: getting pixels onto the G2 (stock EvenHub + CFW full-screen) and the phone rendering pipeline

Status: specification for a clean-room, wire-compatible Kotlin/Android re-implementation.
Target firmware contract: **"Faceclaw/34"** custom firmware (CFW) built on stock **G2 2.3.0.24**.
Everything in section 3 is required for the CFW path; section 2 is only a fallback / bootstrap.

---

## 0. Scope, conventions, source index

### 0.1 Source abbreviations (all paths are read-only reference checkouts)

| Prefix | Path |
|---|---|
| `FC/` | `/home/user/jimrandomh/faceclaw/` (Faceclaw app, commit a6291cf) |
| `KT/` | `FC/native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/` |
| `AND/` | `FC/App_Resources/Android/src/main/java/com/faceclaw/app/` |
| `TS/` | `FC/app/` |
| `TEST/` | `FC/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/` |
| `G2F/` | `/home/user/jimrandomh/g2flash/` (commit 814db36) |
| `P/` | `G2F/patches/` (CFW C sources; all compiled as one translation unit, `P/patches_main.c:23-46`) |
| `KIT/` | `/home/user/refs/g2-kit-unofficial/` (MIT, stock protocol RE) |

Citations are `path:line` (line numbers refer to the file itself).

### 0.2 Conventions

* All CFW binary fields are **little-endian** unless stated (`rd16/rd32`, `P/utils.c:53-59`).
* "u8/u16/u32" unsigned, "s8/s16" two's-complement signed.
* **Packed 4bpp** ("A4"): 2 pixels per byte, **the even (left) pixel is the high nibble**, rows top-down, stride = `ceil(width/2)` bytes; an odd-width row ends in a pad nibble (low nibble) (`P/draw.c:115-119`, `KT/util/BmpUtil.kt:28-71`).
* A pixel value ("level", "nibble") is 0..15; 0 = dark (LED off), 15 = brightest.
* "Lens bit": in the CFW transport **1 = LEFT lens, 2 = RIGHT lens, 3 = both** (`P/message_transport.h:5-7`, `P/message_transport.c:41-44`). Note the stock side-ID function returns the opposite numbering (1 = right, 2 = left, `P/zlib_glue.c:203`); never mix them up.
* "Message type" / "mode" = first byte of a CFW private message (historically called "image-handler mode").
* CRC-16 used everywhere below = **CRC-16/CCITT-FALSE**: poly 0x1021, init 0xFFFF, no reflection, no final XOR (`P/message_transport.c:31-39`, `KT/g2protocol/CfwTransport.kt:118-129`). Check value `crc("123456789") = 0x29B1` (`TEST/ProtocolTest.kt:21`).

### 0.3 Relationship to other specs

The BLE link (GATT, MTU, security auth, stock `aa21` envelope) and the private SID-0xF0 transport are presumably specified in a separate transport spec; §2.1 and §3.3 restate exactly as much as is needed to send display traffic. Input events (taps, gestures, EvenHub events) are out of scope except where the display lease gates them.

---

## 1. Display hardware facts

### 1.1 Panels and framebuffer

| Property | Value | Source |
|---|---|---|
| Lenses | 2 (left, right). Each arm is an independent BLE peripheral with its own MCU, panel, framebuffer, heap and CFW state. | `KIT/ble/docs/transport.md:97-112`; `P/zlib_glue.c:203-204` |
| Physical panel resolution (per lens) | **640 × 480** | `P/zlib_glue.c:229-232`; `TS/graphics/image.ts:7-8`; `G2F/README.md:52-53` |
| Physical framebuffer | packed 4bpp, stride 320 B, **153,600 B**; pointer at SRAM `*(uint8_t**)0x200008B8` (stock copier's destination) | `P/zlib_glue.c:226,229-232`; `P/image_buffers.c:3` |
| Grey levels | 16 (4 bits). Panel output is roughly **linear in light**: level 1 already emits ≈1/15 of full brightness | `KT/graphics/DimDither.kt:9-13,25-26` |
| Colour | monochrome green (phone preview renders "green-on-black (matching the physical glasses)") | `KT/graphics/PreviewPalette.kt:7-9` |
| Stock app area | **576 × 288**. Stock LVGL composes a 576×288 A4 buffer; the display task copies it into the 640×480 framebuffer (`FUN_00470aa0`) at two queue-message cases (types 3 and 6) | `P/patch_compress.py:195-202`; `P/zlib_glue.c:197,222` |
| Placement of the 576×288 area inside 640×480 | **not established** by the sources (see Open questions) | — |
| Panel controller | JEDEC-style ID read (cmd 0x9F) must return `BD 40 10 ..` before register writes are allowed | `P/panel.c:148` |

### 1.2 Refresh behaviour

* The panel is refreshed **full-frame** by the stock display task. The CFW never uses partial panel refresh: every presentation queues a "type-3 refresh" with rect `(0,0)-(640,480)` via `FUN_0047ac1e(0,0,0,0,640,480)` (`P/zlib_glue.c:221,657`), after which the display task calls the (hooked) copier and refreshes the panel (`P/panel.c:40-44` shows the equivalent explicit sequence: D-cache clean of the framebuffer, then `PANEL_REFRESH(0,0,0,0,640,480)`).
* Partial *updates* exist only at the protocol level (the phone sends dirty rectangles into a CFW-owned 640×480 "screen buffer"; each presentation still copies all 153,600 bytes).
* Firmware-side timers relevant to display: display-list animation re-render every **45 ms** (`P/zlib_glue.c:313-314`), brightness fade tick every **40 ms** (1000 ms when idle) (`P/brightness.c:49,101-102`).
* Actual panel scan rate is unknown; the panel has an "RFFQ" (refresh frequency) field in status register 1 bits 3..5 that bounds the legal luminance value (`P/panel.c:154-158`).

### 1.3 Which arm, which lens

| Path | What you send, where | What each lens shows | ACKs / events | Source |
|---|---|---|---|---|
| Stock EvenHub (sid 0xE0) | Send once, to the **right** arm. The arms relay internally. | Same image on both lenses (stock composes per lens). | ACKs and async events come from the right arm. (Faceclaw writes EvenHub traffic to the right arm; `isLeftArmMessage=false`.) | `KT/g2protocol/FlashPromptFlow.kt:10-13`; `G2F/demos/video-bench.ts:49-53`; `KT/g2protocol/MessageBuilder.kt:175-218` |
| CFW private (sid 0xF0) | Send each message **once to one arm** ("ingress") with lens mask **3**. Faceclaw uses the **left** arm (`sendImagesToLeft = true`). | The ingress lens forwards every packet to the peer lens over the inter-arm bridge before processing locally; each lens executes the same commands into its own buffers. Only `DRAW_FLAG_DEPTH` shifts differ per lens (§3.10.3). | **Each lens ACKs separately** (lens bit 1 or 2); the peer's ACK is relayed back and notified on the **ingress** arm. A message is complete only when both lens bits have ACKed. | `KT/g2protocol/ConnectionOptions.kt:50`; `P/message_transport.c:227-251,253-289`; `KT/g2protocol/OutboundMessage.kt:226-242` |

Disagreement: `KIT/ble/docs/transport.md:99-102` claims "There is no internal bus between them". The CFW code explicitly uses the stock inter-arm bridge (`CFW_BRIDGE_SEND`, "SendDataToBoth", `P/message_transport.c:5-12`) and Faceclaw relies on relaying; prefer Faceclaw/g2flash.

### 1.4 Brightness controls (overview; details §3.14)

| Mechanism | Wire | Range | Notes | Source |
|---|---|---|---|---|
| Stock setting | sid 0x09 `G2SettingPackage{commandId=1, magicRandom, deviceReceiveInfoFromApp(3){deviceReceiveBrightness(1){autoAdjust(1)=1 \| brightnessLevel(2)=level}}}` | 2..100 (stock clamps) | `autoAdjust` and `brightnessLevel` are oneof-like alternatives; a level alone is only a temporary manual override while auto stays enabled. Persisted by stock. | `KT/g2protocol/BleProtocol.kt:424-445`; `KIT/ble/gen/g2_setting_pb.ts:304-529` |
| CFW mode 30 | private message `[30][1][level][visible][duration u16]` | level 2..100, fade ≤ 3000 ms | Transient, not persisted; fades with smoothstep; `visible=0` fades to level 2 then blanks the framebuffer; requires the framebuffer lease. Faceclaw's only brightness path on CFW. | `P/brightness.c:39-54`; `KT/g2protocol/session/GlassesSessionBrightness.kt:6-28` |
| CFW mode 16 | ambient-light sensor query / passive polling | — | Lets the phone own auto-brightness (stock adjuster stops stepping the panel). | `P/als_sensor.c:52-80` |
| CFW modes 23-25 | raw panel register read/write/test patterns | — | Diagnostics only; do not use in production (§3.16). | `P/panel.c`, `P/zlib_glue.c:144-155` |

### 1.5 Per-lens CFW memory budget (important for failure modes)

| Buffer | Size | Heap | Lifetime | Source |
|---|---|---|---|---|
| Screen buffer (phone-drawn app pixels) | 153,600 B | stock EvenHub TLSF heap (`FW_MALLOC`, arena 0x2020219C, 0x70800 B) | allocated on first draw/present; freed only by mode 11 | `P/image_buffers.c:30-39`; `P/malloc.h:8-11` |
| Composition buffer (what is presented) | 153,600 B | "heap 13" (LVGL TLSF, 0x2013519C, 0xCD000 B) | allocated at first PRESENT; freed by mode 11 | `P/image_buffers.c:10-20`; `P/malloc.h:14` |
| Resource cache | **196,608 B (192 KiB)** incl. 2 KiB pointer table | EvenHub heap | allocated at first non-empty upload; freed with the framebuffer lease | `P/resource_cache.h:4-8`; `P/resource_cache.c:124-132,168-177` |
| Per-message receive + inflate output | up to 65,535 + 65,536 B, zlib state | heap 13 | per message | `P/message_transport.c:24-27`; `P/transport_compression.c:40-70` |

Allocation failures make the command fail (NACK) and set a sticky "ALLOC" diagnostic flag (`P/malloc.c:49-80`).

Discrepancy: several comments still say "256 KiB texture cache" (`P/patch_compress.py:18`, `P/settings_ext.c:456`); the code constant is 192 KiB (`P/resource_cache.h:4`) and Faceclaw mirrors 196,608 (`KT/g2protocol/ResourceCacheState.kt:24`). Use 192 KiB.

---

## 2. Stock EvenHub display path (bootstrap and fallback)

Faceclaw itself uses the stock path only for (a) the mandatory session prelude, (b) an input-capturing text page, and (c) the pre-flash Yes/No prompt; it never sends stock images. The image details below come from g2-kit and are sufficient for a minimal no-CFW fallback.

### 2.1 Stock envelope (summary)

Phone → glasses writes go to characteristic `00002760-08c2-11e1-9073-0e8ac72e5401` (write-without-response); notifications arrive on `...5402` (`KT/g2protocol/BleProtocol.kt:15-17`). (g2-kit's docs name these fff2/fff1 under a different service UUID, `KIT/ble/docs/transport.md:118-133`; Faceclaw's UUIDs are the tested ones.)

```
aa 21 <seq> <len> <totalFrags> <fragIdx 1..n> <sid> <flag> <chunk...>
```
* `chunk` ≤ 232 bytes (`min(232, maxWrite-8)`); the protobuf plus its CRC-16 (little-endian, over the whole protobuf) is split across fragments; `len` = chunk length (includes CRC bytes present in that chunk). All fragments of one message carry the **same** `seq` (it is a group key, not a counter). ≤ 255 fragments. (`KT/g2protocol/BleProtocol.kt:207-248`; `KIT/ble/envelope.ts:1-8,50-90`; `KIT/ble/docs/gotchas.md:133-136`.)
* Requests use `flag = 0x20` (`KT/g2protocol/BleProtocol.kt:131`; `KIT/ble/envelope.ts:27`). Responses/notifications come back as `aa 12 ...` with the same layout.
* Every EvenHub/settings protobuf carries a "magic" (field 2) echoed in the ACK; keep it within one byte (Faceclaw allocates 100..255 LRU, `KT/g2protocol/BleMagicPool.kt:189-213`; `KIT/ble/docs/gotchas.md:138-141`).
* Do not interleave fragments of two messages on one arm (`KIT/ble/docs/gotchas.md:208-216`).

Note: `KIT/ble/docs/envelope.md:8-48` draws a different header layout and says the CRC is big-endian and requests use flag 0x00; its own implementation (`KIT/ble/envelope.ts`) and Faceclaw disagree with the doc. **Use the layout above** — verified: the prelude below reproduces g2-kit's captured bytes exactly.

Worked example (session prelude, sid 0x01, seq 0x92):
```
aa 21 92 13 01 01 01 20 | 08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00 | a1 42
```
(`KIT/ble/messages.ts` `PRELUDE_F5872`; `KT/g2protocol/BleProtocol.kt:175-205`.)

### 2.2 Session bring-up (stock)

1. Connect both arms, request MTU (Faceclaw asks 512), enable notifications (`KT/g2protocol/session/GlassesSessionCore.kt:1744-1771`).
2. sid 0x80 security-auth request on **each** arm (required by stock 2.2.9+; see transport spec) (`KT/g2protocol/session/GlassesSessionCore.kt:1845-1893`).
3. Prelude (above) on the right arm; wait for its ACK (sid 0x01, magic 156).
4. `Cmd=0` CreateStartUpPage on the right arm; wait for ACK.
5. Heartbeat `Cmd=12` at least every ~4–5 s (Faceclaw stock flow: 4 s, `KT/g2protocol/StockLink.kt:79`; g2-kit 5 s). The plugin task is torn down after ~10 s without traffic (`KIT/ble/docs/containers.md:108-113`); firmware-side this is a tick counter at `0x20078454` that fires "Connection lost" teardown when it passes 899 (`P/zlib_glue.c:380-389`).

### 2.3 `evenhub_main_msg_ctx` (sid 0xE0 payload)

Top-level fields (`KIT/ble/gen/EvenHub_pb.ts:16-130`):

| # | Field | # | Field |
|---|---|---|---|
| 1 | `Cmd` (enum below) | 13 | `DevEvent` (SendDeviceEvent) |
| 2 | `MagicRandom` (u32, keep ≤ 255) | 14 | `HeartPacketCmd` (HeartBeatPacket) |
| 3 | `CreateMessage` (CreateStartUpPageContainer) | 15 | `DevResHeartPacket` |
| 4 | `StartupResCmd` | 16 | `DevPrivateEvent` |
| 5 | `ImgRawMsg` (ImageRawDataUpdate) | 17 | `DevSysEvent` |
| 6 | `ImgResCmd` (ResponseImageRawDataCmd) | 18 | `AudioCtrCommand` |
| 7 | `RebuildContainer` (RebuildPageContainer) | 19 | `AudioResCommand` |
| 8 | `RebuildResCmd` | 20 | `MenuStartEv` |
| 9 | `TextUpgrade` (TextContainerUpgrade) | 21 | `MenuRes` |
| 10 | `TextResCmd` | 22 | `ImuCtrl` |
| 11 | `ShutDownCmd` (ShutDownContaniner) | 23 | `IMURes` |
| 12 | `ShutDownResCmd` | | |

`Cmd` enum `EvenHub_Cmd_List` (`KIT/ble/gen/EvenHub_pb.ts:1065-1170`): 0 APP_REQUEST_CREATE_STARTUP_PAGE, 1 OS_RESPONSE_CREATE_STARTUP_PAGE, 2 OS_NOTIFY_EVENT_TO_APP, **3 APP_UPDATE_IMAGE_RAW_DATA**, 4 OS_RESPONSE_IMAGE_RAW_DATA, **5 APP_UPDATE_TEXT_DATA**, 6 OS_RESPONSE_TEXT_DATA, **7 APP_REQUEST_REBUILD_PAGE**, 8 OS_RESPONSE_REBUILD_PAGE, **9 APP_REQUEST_SHUTDOWN_PAGE**, 10 OS_RESPONSE_SHUTDOWN_PAGE, 11 OS_PRIVATE_EVENT, **12 APP_REQUEST_HEARTBEAT**, 13 OS_RESPONSE_HEARTBEAT, 14 OS_PRIVATE_SYSTEM_EVENT, 15 APP_REQUEST_AUDIO_CTR, 16 OS_RESPONSE_AUDIO_CTR, 17 OS_NOTIFY_MENU_STARTUP, 18 APP_RESPONSE_MENU_STARTUP_FAILED, 19 APP_REQUEST_OPEN_IMU, 20 OS_RESPONSE_IMU.

Result codes `EvenHub_ErrorCode_List` (`KIT/ble/gen/EvenHub_pb.ts:1237-1311`): 0 CREATE_PAGE_SUCCESS, 1 CREATE_INVALID_CONTAINER, 2 CREATE_OVERSIZE_RESPONSE_CONTAINER, 3 CREATE_OUTOFMEMORY_CONTAINER, 4 UPGRADE_IMAGE_RAW_DATA_SUCCESS, 5 ..._FAILED, 6 REBUILD_PAGE_SUCCESS, 7 REBUILD_PAGE_FAILED, 8 UPGRADE_TEXT_DATA_SUCCESS, 9 ..._FAILED, 10 SHUTDOWN_SUCCESS, 11 SHUTDOWN_FAILED, 12 HEARTBEAT_SUCCESS, 13 AUDIO_CTR_SUCCESS, 14 AUDIO_CTR_FAILED.

Faceclaw's wrapper: `wrapEvenHub(cmd, magic, innerField, inner) = {1:cmd, 2:magic, innerField:inner}` (`KT/g2protocol/BleProtocol.kt:813-820`).

### 2.4 Container messages (field numbers)

`CreateStartUpPageContainer` (Cmd 0, ctx field 3) and `RebuildPageContainer` (Cmd 7, ctx field 7) (`KIT/ble/gen/EvenHub_pb.ts:399-472`):

| # | Field |
|---|---|
| 1 | `ContainerTotalNum` |
| 2 | repeated `ListObject` (ListContainerProperty) |
| 3 | repeated `TextObject` (TextContainerProperty) |
| 4 | repeated `ImageObject` (ImageContainerProperty) |
| 5 | `widgetId` (Create only; Faceclaw and g2-kit send 10000) |

`ImageContainerProperty`: 1 XPosition, 2 YPosition, 3 Width, 4 Height, 5 ContainerID, 6 ContainerName (`KIT/ble/gen/EvenHub_pb.ts:485-514`; `KT/g2protocol/BleProtocol.kt:847-857`).

`TextContainerProperty`: 1 XPosition, 2 YPosition, 3 Width, 4 Height, 5 BorderWidth, 6 BorderColor, 7 BorderRadius, 8 PaddingLength, 9 ContainerID, 10 ContainerName, 11 IsEventCapture, 12 Content (`KIT/ble/gen/EvenHub_pb.ts:631-690`; `KT/g2protocol/BleProtocol.kt:822-845`).

`ListContainerProperty`: 1..4 geometry, 5 BorderWidth, 6 BorderColor, 7 BorderRadius, 8 PaddingLength, 9 ContainerID, 10 ContainerName, 11 ItemContainer{1 ItemCount, 2 ItemWidth, 3 IsItemSelectBorderEn, 4 repeated ItemName}, 12 IsEventCapture (`KIT/ble/gen/EvenHub_pb.ts:757-848`).

`TextContainerUpgrade` (Cmd 5, ctx field 9): 1 ContainerID, 2 ContainerName, 3 ContentOffset, 4 ContentLength, 5 Content. Stock semantics (per Faceclaw's emulation): write `Content` at `ContentOffset` and truncate the remainder (`TS/apps/evenhub/session.ts:862-874`).

`ShutDownContaniner` (Cmd 9, ctx field 11): 1 exitMode. `exitMode=0` with Faceclaw tears down the whole plugin task (the prelude must be replayed before the next Create) (`KT/g2protocol/session/GlassesSessionCore.kt:1235-1237`); `exitMode=1` is an app's request to quit (stock shows a confirm) (`TS/apps/evenhub/session.ts:904-920`).

`HeartBeatPacket` (Cmd 12, ctx field 14): 1 Cnt (send 0) (`KT/g2protocol/BleProtocol.kt:294-297`).

The 1-byte-per-field Faceclaw input page (Cmd 0), exactly as sent every session (`KT/g2protocol/BleProtocol.kt:250-261`): one text container, id 1, name `"dashboard"`, 0,0,576,288, `IsEventCapture=1`, content `" "`, widgetId 10000. Encoded with magic 100:
```
08 00 10 64 1a 23 08 01 1a 1c 08 00 10 00 18 c0 04 20 a0 02 48 01 52 09 64 61 73 68 62 6f 61 72 64 58 01 62 01 20 28 90 4e
```

### 2.5 Stock images (from g2-kit)

**Format**: a complete **4bpp indexed BMP** (not raw pixels) (`KIT/ble/image.ts:1-12,39-95`):
* 14-byte file header: `'B' 'M'`, u32 fileSize, u16 0, u16 0, u32 pixelOffset = 118.
* 40-byte BITMAPINFOHEADER: width, height (**positive → bottom-up rows**), planes 1, bpp 4, compression 0 (BI_RGB), imageSize, 0, 0, colorsUsed 16, important 0.
* Palette: 16 × BGRA with `B=G=R=i*17, A=0`.
* Rows bottom-up, each `ceil(w/2)` bytes padded to a multiple of 4; high nibble = left pixel.
* 288×144 → exactly 20,854 bytes (`KIT/ble/image.test.ts:70-74`).
* Index 0 = black/off. (`KIT/ble/docs/images.md:10-12` says "index 0 is transparent"; on an emissive display these are the same thing. `images.md:14` also claims "no padding, raw" — contradicted by `image.ts`; trust the code.)

**Container geometry**: the full stock area is 576×288; g2-kit tiles it as a 2×2 grid of **288×144** image containers because ~20 KB per container is the largest payload observed to ingest cleanly (`KIT/ble/image.ts:3-24`; `KIT/ble/docs/images.md:21-46`). Declare all tiles in one `Cmd=7` rebuild with 4 ImageObjects (`KIT/ble/messages.ts` `buildImageContainers`). Stock page limits enforced by stock: ≤ 4 image containers, ≤ 8 text containers, exactly 1 event-capture container (Faceclaw logs these as "stock limits", `TS/apps/evenhub/session.ts:848-853`).

**Upload**: one `Cmd=3` message per fragment, ctx field 5 = `ImageRawDataUpdate` (`KIT/ble/gen/EvenHub_pb.ts:579-618`; `KIT/ble/messages.ts` `buildImageRawData`):

| # | Field | Value |
|---|---|---|
| 1 | ContainerID | container id |
| 2 | ContainerName | ≤ 14 chars |
| 3 | MapSessionId | same for all fragments of one image; new per image |
| 4 | MapTotalSize | total BMP bytes |
| 5 | CompressMode | 0 raw; on stock ≥ 2.2.6.10: 1 = RLE (Even's own format, undocumented), 2 = LZ4 *block*; unknown values are silently treated as raw |
| 6 | MapFragmentIndex | 0..n-1 in order |
| 7 | MapFragmentPacketSize | length of this fragment |
| 8 | MapRawData | fragment bytes, **≤ 4096** (g2-kit: 6144/8192 rejected with error 7; Faceclaw historically used 3800) |

Each fragment gets its own magic and an ACK (`Cmd=4`, `ResponseImageRawDataCmd` with ErrorCode). For LZ4 the firmware decompresses into a buffer of exactly W×H bytes, so the compressed BMP must inflate to ≤ W×H (`G2F/demos/video-bench.ts:22-31,102-105`).
Sources for fragment rules: `KIT/ble/image.ts:200-219`; `KIT/ble/messages.ts` (`buildImageRawData` comment: "~2.4 s per image in serial ... ~400–500 ms firmware ack latency per 4 KB fragment"); `KT/g2protocol/ConnectionOptions.kt:34`.

Stock image quirks (`KIT/ble/docs/images.md:77-165`, `gotchas.md:163-179`):
* The **first** Cmd=3 stream after a container is created renders nothing → send a sacrificial warm-up frame.
* After an aborted stream, bump MapSessionId by ≥ 2 (Faceclaw's `skipSessionIds` increments by 2 always, `KT/g2protocol/ConnectionOptions.kt:51`; `KT/g2protocol/session/GlassesSessionCore.kt:1942-1947`).
* ACKs occasionally go missing although the fragment landed; tolerate ≤ 3 consecutive misses; sliding window of ~4.
* Throughput ≈ 8.8 KB/s effective; full 576×288 (4 tiles) ≈ 5 fps at best.
* Faceclaw's legacy `planImageFragments` assumes a fragment may declare a logical size larger than its payload with implied trailing zeros (`KT/g2protocol/BleImageOptimizer.kt:10-79`); unverified on current stock — do not rely on it.

### 2.6 Stock text/list (what Faceclaw uses on stock)

* Container name ≤ 14 characters; a second `Cmd=0` for an existing name is accepted but **not ACKed** (`KIT/ble/docs/containers.md:9-37`).
* Text content ≲ 1000 bytes per container; fixed LVGL 20 px proportional font; ~50×10 character grid on 576×288; `IsEventCapture` must be set for taps (`KIT/ble/docs/text.md:172-241`).
* Faceclaw's pre-flash prompt uses a 280×130 text container at (0,0) plus a 280×120 list at (0,150) "within the stock ~280x130 container-size cap" (`KT/g2protocol/BleProtocol.kt:299-332`), yet Faceclaw's own input page is a 576×288 text container on stock-based firmware. See Open questions.

### 2.7 Faceclaw's EvenHub *app host* (phone-side emulation, not a wire path)

Faceclaw can run third-party EvenHub web apps. It does **not** forward their pages to stock; it re-implements the EvenHub page compositor on the phone and ships the result through the CFW pipeline (§4):
* Canvas 576×288 ("stock band") or 576×452 ("extended layout") (`TS/apps/evenhub/compositor.ts:23-24`; `TS/apps/evenhub/session.ts:313-314,1042-1047`).
* Paint order: explicit `zOrderIndex` ascending if any container has one, else images first then lists/texts; before painting an image container, previously deferred text draws are baked so the image can cover them (`TS/apps/evenhub/compositor.ts:133-172`).
* Text uses the extracted stock 20 px font (`EvenHubFont`) wrapped with pretext/LVGL rules; text brightness levels 0..4 map to grey `[0,64,128,191,255]` (`TS/apps/evenhub/compositor.ts:27-40`).
* Image payload decoding: BMP (1/4/8/24/32 bpp), PNG, raw 8bpp (w×h bytes) or raw packed 4bpp (`ceil(w*h/2)` bytes, level×17); an image smaller than its container is **tiled** to fill it (stock quirk) (`TS/apps/evenhub/session.ts:1480-1528`; `TS/apps/evenhub/compositor.ts:80-94`).

### 2.8 Minimal stock-only fallback (recommended)

1. §2.2 steps 1–5.
2. `Cmd=7` rebuild with one event-capture list or text container plus up to 4 image containers (288×144 at (0,0),(288,0),(0,144),(288,144)).
3. Per tile: build the 4bpp BMP (§2.5), split into ≤ 4096-byte fragments, send `Cmd=3` fragments (unique magic each, shared MapSessionId), window ≤ 4, wait for ACKs; send a warm-up frame first.
4. Only re-send tiles whose pixels changed. Heartbeat every 4 s.
Expect ≈ 1–5 fps and ~20 KB per changed tile.

---

## 3. CFW display path (Faceclaw/34) — full detail

### 3.1 Architecture (per lens)

```
phone ──BLE write (ingress arm, sid 0xF0 packets)──► cfw_receive_packet (hook before stock TPL reassembly)
          │                                             ├─ forwards packet to peer lens over inter-arm bridge (if its bit set)
          │                                             └─ reconstructs record → inflate (persistent zlib) → CRC check
          │                                                    └─ image_worker(message)  [image mutex]
          │                                                          ├─ control types (5,7,10,16,17,30,...) 
          │                                                          ├─ 21/22/29  → resource cache (192 KiB, ids 0..511)
          │                                                          ├─ 26 DRAW_CALLS → draws into SCREEN buffer (640×480 A4)
          │                                                          ├─ 27 SET_ROOT  → validates & records root display list
          │                                                          └─ 28 PRESENT  → [display gate] composition buffer :=
          │                                                                 root list ? render(root) : copy(screen)
          │                                                                 → publish "direct job", queue full refresh
          ◄──ACK/NACK per lens (relayed to ingress arm)            stock display task → display_copy_hook:
                                                                       memcpy composition → physical FB, D-cache clean,
                                                                       panel refresh; afterwards suppress stock repaints
                                                                       while the framebuffer lease is valid
```
Sources: `P/message_transport.c:227-251`; `P/zlib_glue.c:11-16,355-421,424-641,748-794`.

Key idea: the phone never writes the physical framebuffer. It maintains (1) a persistent CFW **screen buffer** by sending incremental draw calls, (2) optionally retained **resources** (images, fonts, surfaces, display lists), and (3) an optional **root display list** that composes screen + resources into a **composition buffer** at each PRESENT (and every 45 ms while animating). The composition buffer is what the panel shows.

### 3.2 Detecting the CFW and its revision

Send a stock settings query on sid 0x09: `G2SettingPackage{1: commandId=2, 2: magic, 4: {1: 1}}` (`KT/g2protocol/BleProtocol.kt:507-517`). The CFW appends **protobuf field 100 (string) `"Faceclaw/<n>"`** to every sid-0x09 settings response (`P/settings_ext.c:4-31,476-487`). Current: `"Faceclaw/34"` (`P/settings_ext.c:480`); Faceclaw requires exactly revision ≥ 34 (`TS/g2/firmware-compat.ts:29,99-104`). Older builds advertised `"EVENCFW/<ver> <tokens>"` (treat as outdated). Anything else = foreign CFW. Stock firmware has no field 100. The versions of both lenses are fields 4.5 / 4.6 of the same response (`KT/g2protocol/BleProtocol.kt:797-811`).

Revision milestones that matter for display (`P/settings_ext.c:429-481`): 8 = custom commands moved to SID 0xF0 only, stock EvenHub image handling restored; 10 = persistent transport zlib + decoded CRC + sequenced NACK; 11 = immediate NACK on failures; 12 = ACK history; 23 = resource IDs + chunked upload (21) + eviction (22) + compaction; 27 = extended integers & expressions; 29 = rect-copy coordinates may be expressions; 33 = two-LUT dithering remap; 34 = unchanged brightness no longer re-invokes the stock setter.

### 3.3 Private transport (SID 0xF0) — what the display needs

(Condensed; normative sources `P/message_transport.c`, `P/transport_compression.c`, `KT/g2protocol/CfwTransport.kt`.)

**Packet** (each BLE write; `P/message_transport.c:46-56`; `KT/g2protocol/CfwTransport.kt:77-116`):
```
off 0  aa 21
    2  seq      stream id on the first packet of a stream, +1 per following packet (u8 wrap)
    3  len      = total packet length − 8
    4  01  5  01  6  f0 (sid)  7  00
    8  options  bit0 LEFT, bit1 RIGHT (lens mask; constant within a stream),
                bit7 0x80 RESET = first packet of a new stream (stream id := seq),
                bit6 0x40 END   = last packet of the stream; other bits must be 0
    9… data     slice of the record byte stream, ≤ min(252, MTU−14) bytes
    …  crc16 LE over bytes [8 .. len+6) (options + data)
```
Total ≤ 263 bytes (≥ 11 bytes overhead per packet). Packet boundaries have no meaning to the record parser (`P/message_transport.c:156-157`).

**Record** (the message; starts at the first data byte of the stream): `[flags u8][wireSize u16][crc16 of DECODED message u16][body…]` (`P/message_transport.c:189-205`).
* flags: bits 0-1 lens mask (must equal packet options' lens bits), bit 2 `COMPRESSED` (4), bit 3 `RESET_CONTEXT` (8); any other bit → NACK (`P/message_transport.h:8-11`; `P/message_transport.c:122-124`).
* `wireSize` ≤ 65,535; decoded size ≤ 65,535.
* If COMPRESSED, the body is a chunk of **one persistent RFC-1950 zlib stream per ingress lens** that must end with a complete `SYNC_FLUSH` (`00 00 FF FF`), never `Z_FINISH` (`P/transport_compression.c:1-2,53-54`). RESET_CONTEXT resets the inflater (the next compressed body must start with a zlib header). A lens only accepts records after it has seen a RESET_CONTEXT since boot/last failure ("only an explicit record reset can recover", `P/message_transport.c:125-132,142-145`). An uncompressed record with RESET_CONTEXT is always valid — the simplest encoder sets `flags = lensMask | 8` on every record (this is `CfwTransport.frame`, `KT/g2protocol/CfwTransport.kt:70-75`).
* Faceclaw: one message per stream, stream id = the message's 1-byte "magic" (100..255, unique among in-flight messages) (`KT/g2protocol/MessageBuilder.kt:82-104`); Java `Deflater()` default level, zlib wrapper, `SYNC_FLUSH` per message (`FC/native/kotlin/shared/src/androidMain/kotlin/com/faceclaw/app/g2protocol/AndroidProtocolPlatform.kt:47-63`); falls back to an uncompressed RESET_CONTEXT record if the compressed body exceeds 65,535 (`KT/g2protocol/CfwTransport.kt:28-48`); resets the deflater on any replay/timeout/write failure (`KT/g2protocol/session/GlassesSessionSend.kt:372-376,405-406,110-111`).

**ACK / NACK** (notification on the ingress arm, stock `aa 12` framing, sid 0xF0; `P/message_transport.c:75-104`; `KT/g2protocol/CfwTransport.kt:136-180`):
```
aa 12 <seq> <len> 01 01 f0 00 | kind(1=ACK,3=NACK) stream_id ordinal(u16) lens(1=L,2=R) size(u16) crc(u16)
                              | ACK only: 0..3 × [stream_id ordinal(u16) size(u16) crc(u16)]  (previous successes, newest first)
                              | crc16 LE over body
```
Packet size 19..40 bytes; NACK is exactly 19. `size`/`crc` are of the **decoded** message; the phone matches `(stream_id == magic, ordinal == 0, size == message.length, crc == crc16(message))` (`KT/g2protocol/OutboundMessage.kt:226-242`). History entries repair lost ACKs and must be applied like primary ACKs (`KT/g2protocol/session/GlassesSessionCore.kt:1330-1352`).
A command handler returning failure yields a NACK (`P/message_transport.c:141-147`).

**Golden vector** (uncompressed, stream 255, lenses 3, MTU 23) for body `"123456789abcdef"` (`TEST/ProtocolTest.kt:20-33`):
```
aa 21 ff 0c 01 01 f0 00 83 0b 0f 00 78 c3 31 32 33 34 86 2b
aa 21 00 0c 01 01 f0 00 03 35 36 37 38 39 61 62 63 64 8c 7b
aa 21 01 05 01 01 f0 00 43 65 66 de 70
```

**Window** (Faceclaw; `KT/g2protocol/CfwMessageWindow.kt`, `KT/g2protocol/ConnectionOptions.kt:57`, `KT/g2protocol/session/GlassesSessionSend.kt:80-114`):
* ≤ 3 messages in flight (`WINDOW_SIZE = 3`, counts all sids).
* A CFW message is complete when **both** lens bits have ACKed; completion is processed strictly in send order (head-of-line).
* ACK timeout 500 ms after the write finishes (write itself may take ≤ 2000 ms). On a NACK or timeout of any unresolved CFW message, **all** unresolved CFW messages are re-queued at the front in original order with fresh stream ids ("go-back-N"); max 3 retries each, then transport failure → reconnect.
* A resource **eviction** (type 22) may only be sent when no other CFW message is in flight (`CfwMessageWindow.canSend`) because a replay could re-execute an older message that references a reused id.

### 3.4 The framebuffer lease

**Wire** — a stock settings write on **sid 0x09**, sent separately to **each arm**, fire-and-forget (magic 0, no ACK tracked):
```
G2SettingPackage{ 1: commandId = 1, 2: magic = 0, 101 (bytes): 'F' 'C' 0x01 op nonceLo nonceHi }
```
(`KT/g2protocol/BleProtocol.kt:519-542`; `P/settings_ext.c:33-44,316-359`). The CFW scans raw field 101 before nanopb discards the unknown field (`P/settings_ext.c:361-403`).
Encoded acquire (op 5): pb `08 01 10 00 aa 06 06 46 43 01 05 00 00`; as a frame e.g. `aa 21 10 0f 01 01 09 20 08 01 10 00 aa 06 06 46 43 01 05 00 00 29 26`. Release (op 6): `... 46 43 01 06 00 00`.

| op | Name | Effect | Source |
|---|---|---|---|
| 1 | ACQUIRE/RENEW (wake lease) | separate 90 s "wake-takeover" lease (double-tap/head-up deferral, idle gestures). Not needed for display. | `P/settings_ext.c:323-324` |
| 2 | RELEASE (wake lease) | | `P/settings_ext.c:325-327` |
| 3, 4 | WAKE_CLAIM / WAKE_READY | wake handshake (input spec) | `P/settings_ext.c:328-340` |
| **5** | **FB_ACQUIRE** | `direct_lease_deadline = now + 90,000 ms`. If the previous lease had lapsed (or none): also `direct_active = 0` and **free the resource cache** (a *renewal* of a live lease keeps both). | `P/settings_ext.c:341-349` |
| **6** | **FB_RELEASE** | deadline = 0, `direct_active = 0`, free the resource cache | `P/settings_ext.c:350-353` |
| 7 | WEAR_QUERY | emit wear state on sid 0x10 | `P/settings_ext.c:354-358` |

`cfw_fb_lease_active()` returns true while `now < deadline` (signed wrap-safe compare). The first call after expiry clears the deadline, clears `direct_active` and frees the resource cache (`P/settings_ext.c:124-137`). It does **not** free the screen/composition buffers or clear their contents (only mode 11 does).

**What requires the lease** (message fails/NACKs without it): 21 upload (non-empty), 22 evict (non-empty), 26 draw calls, 27 set root, 28 present, 29 create surface, 30 brightness, and every image/text draw op (`P/resource_cache.c:139,207,228`; `P/zlib_glue.c:596`; `P/texture_draw.c:283,307,455`; `P/brightness.c:44`). Other CFW features (gesture forwarding, compass calibration preservation, ALS lease binding) also treat it as "Faceclaw owns the session".

**What it means for the stock UI** (`P/zlib_glue.c:742-770`): after a successful direct presentation, `direct_active = 1`. While `direct_active` and the lease is valid, every stock display refresh **skips the stock 576×288 compositor copy** and just re-refreshes the already-correct physical framebuffer — stock LVGL content (EvenHub containers, system pop-ups) is invisible. When the lease lapses or is released, the next stock refresh copies the stock composition again ("fail-open"). Before the first PRESENT of a fresh lease the stock page is visible (Faceclaw's stock page is a blank text container, so the lenses are dark).

**Faceclaw policy**: acquire on both arms immediately when the session becomes ready (queued ahead of the EvenHub layout, `KT/g2protocol/session/GlassesSessionCore.kt:1690-1695`); renew every **45 s** (`FACECLAW_WAKE_LEASE_RENEW_MS`, `GlassesSessionCore.kt:44`; `GlassesSessionSend.kt:62-69`); release (op 6, priority, both arms, wait ≤ 1.5 s for the writes) on disconnect when mode 11 is unavailable (`KT/g2protocol/session/GlassesSessionControls.kt:275-341`).

### 3.5 Message types ("image-handler modes") — complete table

Dispatch: `image_worker` takes a per-lens image mutex; types 23–25 compare the **full byte**, everything else uses `type & 0x7F` (bit 7 historically meant "lenses differ" and is otherwise ignored) (`P/zlib_glue.c:172-175,355-374,427`). Unknown/retired types return failure → NACK (`P/zlib_glue.c:617`). Every accepted message on each lens first resets the stock EvenHub keep-alive counter (`P/zlib_glue.c:376-390`) — so a steady CFW stream also keeps the EvenHub page alive.

| Type | Name | Status | Payload after the type byte | Lease | Display gate | Notes / source |
|---|---|---|---|---|---|---|
| 0–2, 4 | — | never defined | — | | | rejected |
| 3 | BOUNDING_BOX | **retired on wire**; phone-internal "optimizer record" | historical `[l/4 u8][t/2 u8][w/4 u8][h/2 u8][fid u16][RLE]` | | | now expressed as draw op 1 (compact) `P/zlib_glue.c:23-29` |
| 5 | BUZZER | active (not display) | `[kind]…` kinds 0 preset, 1 note, 2 stop, 3 raw tone, 4 tone sequence | no | no | `P/zlib_glue.c:30-62,439-499` |
| 6 | FULL_FRAME | retired; optimizer record | historical `[RLE of all nibbles of the packed 640×480 frame]` | | | → draw op 1 (u16 form) `P/zlib_glue.c:63-68` |
| 7 | DIAGNOSTICS | active | `[sub]`: 0 clear sticky flags/fid tracking, 1 hide overlay, 2 show overlay, other = no-op success | no | no | Faceclaw sends `[7][1]`/`[7][2]` once per layout; benchmark uses `[7][0x7F]+random` as an ACKed no-op `P/zlib_glue.c:501-526`; `KT/g2protocol/session/GlassesSessionSend.kt:637-653`; `GlassesSessionControls.kt:82-93` |
| 8 | MULTI_SEGMENT | retired; optimizer record | historical `[count u8]{[len u16][sub-message]}` | | | flattened by the phone `P/zlib_glue.c:72-78` |
| 9 | RECT_COPY | retired; optimizer record | historical `[x u16][y u16][w u16][h u16][dx s16][dy s16]` (dx,dy = destination) | | | → draw op 2 with source CURRENT `KT/g2protocol/DrawProtocol.kt:285-293` |
| 10 | COMPASS | active (not display) | `[0]` stop, `[1]` start, `[2][interval u16][minChange u16]` | no | no | `P/zlib_glue.c:84-91,528-572` |
| 11 | CLEANUP | active | none (extra bytes ignored) | no | **yes** | releases everything, §3.15 `P/zlib_glue.c:92-97,584-589,681-740` |
| 12, 13, 14 | RETIRED_12..14 | retired (old texture commands) | — | | | rejected `P/zlib_glue.c:98-103`; `P/settings_ext.c:456-457` |
| 15 | STOCK_FONT_STRING | retired on wire; optimizer record | historical `[x s16][y s16][options u8][len u8][UTF-8]` | | | → draw op 3 `P/zlib_glue.c:104-109` |
| 16 | AMBIENT_LIGHT | active (brightness support) | `[0]` query; `[1][flags][interval u16][minDelta u16][heartbeat u16]` passive start; `[2]` stop | no | no | reports on sid 0x09 field 105 `P/als_sensor.c:52-80` |
| 17 | RING_BATTERY | active (not display) | `[0]` | no | no | `P/zlib_glue.c:117-119` |
| 18 | RETIRED_CACHE_WRITE | retired | — | | | rejected; use 21 `P/zlib_glue.c:120-121` |
| 19 | CACHED_IMAGE | retired; optimizer record | historical `[id u16][x s16][y s16][options u8]` | | | → draw op 4 `P/zlib_glue.c:122-125` |
| 20 | CACHED_TEXT | retired; optimizer record | historical `[font u16][x s16][y s16][options u8][len u8][bytes]` | | | → draw op 5 `P/zlib_glue.c:126-134` |
| **21** | UPLOAD_RESOURCE | active | `[count u16]{[id u16][total u32][offset u16][size u16][bytes×size]}` | yes (if count>0) | no | §3.7 `P/resource_cache.c:133-194` |
| **22** | EVICT_RESOURCE | active | `[count u16]{[id u16]}` (length must be exactly 2+2·count) | yes (if count>0) | no | §3.7 `P/resource_cache.c:195-222` |
| 23 | PANEL_READ | diagnostic | 12-byte request `[23][reqId u16][lenses][op][len][addr u32][value u16]` | no* | (display task) | §3.16 |
| 24 | PANEL_WRITE | diagnostic | same shape | no* | | §3.16 |
| 25 | PANEL_PATTERN | diagnostic | `[25][reqId u16][lenses][pattern 0..18][0][0 u32][value u16]` | no* | | §3.16 |
| **26** | DRAW_CALLS | active | draw sequence `[count u16]{[len u16][call]}` executed on the **screen buffer**; validate-all then apply; does not present | **yes** | no | §3.10 `P/zlib_glue.c:156-159,595-605` |
| **27** | SET_ROOT_DISPLAY_LIST | active | `[id u16]` (message length exactly 3); 65535 clears | **yes** | no | §3.12 `P/zlib_glue.c:160-162,606-611` |
| **28** | PRESENT | active | none (message length exactly 1) | **yes** | **yes** | §3.12 `P/zlib_glue.c:163-165,613-615` |
| **29** | CREATE_SURFACE | active | `[count u16]{[id u16][width u16][height u16]}` | yes | no | §3.7 `P/resource_cache.c:225-254` |
| **30** | BRIGHTNESS | active | `[1][level 2..100][visible 0/1][duration u16 ≤3000]` (length exactly 6) | yes | no | §3.14 `P/brightness.c:39-54` |

\* panel ops are not lease-gated at queue time, but their pattern/register effects are undone when the lease is not active (`P/panel.c:184-195`).

"Display gate": for 11 and 28 the worker takes the stock display semaphore before touching shared buffers and keeps it until the display task has consumed the presentation; if after the (timed) wait a previous direct job or panel job is still pending, the message fails (NACK) (`P/zlib_glue.c:286-295,399-418`). Other messages never wait on the display.


### 3.6 Per-lens CFW state relevant to display

| State | Meaning | Reset by |
|---|---|---|
| `screen_buffer` (640×480 A4) | target of DRAW_CALLS; "the delta base" — contains only what the phone drew (app pixels) | allocated zeroed at first use; freed by 11 |
| `composition_buffer` (640×480 A4) | what PRESENT publishes to the panel | allocated zeroed at first PRESENT; freed by 11 |
| resource cache | 512 ids, 192 KiB arena | lease release/expiry/fresh acquire, 11 |
| `root_display_list` (id+1, 0 = none) | root list composed at each PRESENT | 27, cache release, 11 |
| `animation_origin_ms` | set by each inbound PRESENT; expressions' `TIME` counts from here | PRESENT |
| `direct_pending/direct_shadow` | one queued presentation job for the display task | consumed by the display task |
| `direct_active`, `direct_lease_deadline` | stock-repaint suppression and the lease | §3.4 |

(`P/cfw_context.h:30-143`; `P/image_buffers.c`.) The context itself is a heap object whose pointer lives in 1 KiB of SRAM carved out of the stock primary TLSF arena (`P/cfw_context.h:7-11,145-151`; `P/patch_compress.py:93-105`).

### 3.7 Resource cache (types 21, 22, 29)

**Constants** (`P/resource_cache.h:4-19`; mirrored in `KT/g2protocol/ResourceCacheState.kt:24-34`, `KT/g2protocol/ResourceFlags.kt`):

| Name | Value |
|---|---|
| Cache size | 196,608 B (192 KiB) |
| Resource ids | 0..511 (`CFW_RESOURCE_COUNT = 512`) |
| Pointer table | first 2048 B of the cache (512 × u32) |
| Arena | 194,560 B after the table |
| Max resource size | 65,536 B |
| Block header | 16 B: `span u32, length u32, id u32, received u32` |
| Block span | `16 + align4(length)` |
| Free marker | `id = 0xFFFFFFFF` |

**Arena algorithm** (`P/resource_cache.c`): blocks tile the arena contiguously. A live block's `length` = payload size; a free block's `length` = offset of the next free block (singly linked free list, head in context). Allocation is first-fit over the free list, splitting when the remainder ≥ 16 B; if nothing fits, **compact** (slide all live blocks down, preserving ids and received prefixes, republish pointers) and retry once (`:71-123`). Eviction marks blocks free and rebuilds the free list, coalescing adjacent free blocks (`:50-69,195-222`). The pointer-table entry for an id is non-zero **only when the resource is completely received** (`received == length`): incomplete resources are invisible to draws (`:12-16`). Lookups validate alignment/bounds/id (`:27-39`).

**UPLOAD (21)** — `[count u16]` then `count` entries `{[id u16][total u32][offset u16][size u16][size bytes]}` (`:133-194`):
* Whole batch validated first (all-or-nothing): `count ≤ 512`; each id < 512, **ids distinct within the batch** (so at most one chunk per resource per message), `1 ≤ total ≤ 65536`, `size ≥ 1`, `offset + size ≤ total`, entry fits in the message; exact message length.
* New id: `offset` must be 0; capacity check `Σ span(total) of new resources + used ≤ 194,560`.
* Existing id: `total` must equal the stored length; `offset ≤ received`; if `offset < received` the overlapping bytes must be **identical** (replays of already-received chunks are accepted and ignored); if `offset == received`, the chunk is appended.
* `count = 0` is a valid no-op even without the lease. The cache is allocated lazily on the first non-empty upload.
* Replacing a resource with different content under the same id requires evicting it first.

**EVICT (22)** — `[count u16]{[id u16]}`, length exactly `2+2·count`, ids < 512 and distinct; absent ids are no-ops (`:195-222`).

**CREATE_SURFACE (29)** — `[count u16]{[id u16][w u16][h u16]}`, `1 ≤ w ≤ 640`, `1 ≤ h ≤ 480`, `5 + ceil(w/2)·h ≤ 65536`, ids distinct (`:225-254`). Allocates a **zeroed raw LARGE image** `[0x04][w u16][h u16][pixels]`. If the id already exists it must be an identical-shape raw LARGE image (then its pixels are **preserved**); otherwise the whole message fails. Surfaces are then painted with draw calls whose `DRAW_FLAG_RESOURCE_TARGET` selects them.

Example upload vector (id 0, 3 bytes `01 01 1f`, `TEST/ResourceCacheTest.kt:32-46`):
`15 01 00 | 00 00 | 03 00 00 00 | 00 00 | 03 00 | 01 01 1f`.

**Phone-side cache model** (`KT/g2protocol/ResourceCacheState.kt`): mirrors the arena byte accounting (`allocationBytes = 16 + align4(size)`, arena 194,560, 512 ids) so it never asks the firmware for more than fits; resources are **content-addressed** (`CachedResource`: FNV-1a-64 hash + byte equality; optional `owner` key for mutable surfaces) (`:5-14`).
* `prepare(resources)`: per frame; hits refresh LRU clocks; the current frame's hits and the pinned set are protected; a miss evicts unprotected LRU entries until it fits (after a feasibility check), takes the lowest free id, queues an upload; returns −1 when impossible (that draw stays baked into pixels) (`:62-98`).
* `drainCommands(maxPayload)`: first all queued evictions as type-22 messages (batches of `(max−3)/2` ids); if the model was reset (`resetRequired`) and there are uploads, evict **all 512 ids** first; then uploads in chunks of `maxPayload − 13` bytes, never two chunks of one id in one message; resources with an `owner` are emitted as a CREATE (29) `[29][01 00][id][w][h]` instead of an upload (`:117-163`). Faceclaw uses `maxPayload = 3600`.
* `reset()` on any uncertainty (reconnect, resource-command timeout, EvenHub suspend) bumps a generation counter that forces a full keyframe (`:50-56`; `KT/g2protocol/session/GlassesSessionSend.kt:842-856,1205-1219`).
* `checkpoint()` lets a planner roll back a rejected plan (`:100-115`).

### 3.8 Resource formats

Byte 0 of every resource = header flags (`P/resource_cache.h:10-19`): bits 0-1 type (0 image, 1 font, 2 display list), bit 2 `LARGE` (4), bit 3 `RLE` (8).

**Image** (`P/texture_draw.c:8-16,91-127`):
```
small:  [flags u8][width u8][height u8][pixels…]         (flags ∈ {0x00, 0x08})
large:  [flags u8][width u16][height u16][pixels…]       (flags ∈ {0x04, 0x0C})
```
* 1 ≤ width ≤ 640, 1 ≤ height ≤ 480; any flag bit outside `LARGE|RLE` (including a non-zero type) → invalid.
* Raw pixels: packed 4bpp rows, stride `ceil(w/2)`, exactly `ceil(w/2)·h` bytes required (extra bytes allowed).
* RLE pixels: token stream (§3.9) covering **exactly `w·h` pixels with no row padding**; decoding stops at the token that completes the count (so images can be packed back to back inside a font).
* Only **raw** images (no RLE, type 0) can be draw **targets** or rect-copy **sources** (`P/display_list.c:108-124`).
* Faceclaw builders: `rawImage(w,h,pixels) = [0x04][w u16][h u16][pixels]` (`KT/g2protocol/DrawProtocol.kt:177-182`); glyph/icon cached images are small RLE `[0x08][w][h][RLE]` (`KT/graphics/GlyphAtlas.kt:282-324`; `KT/graphics/ImageAtlas.kt:228-265`).

**Font** (`P/texture_draw.c:300-302`; `P/display_list.c:232-251`):
```
[0x01][96 × u16 glyph offset for chars 32..127][glyph images…]      header = 193 bytes
```
Offsets are relative to the start of the resource; an offset < 193 means "absent" (0 is used). Each glyph is an image (above). Faceclaw builds one font resource per font id containing all glyphs requested this frame plus those in the previous snapshot while ≤ 64 KiB, current-frame glyphs first, sorted by code (`KT/graphics/FontResourceAtlas.kt:3-39`).

**Display list** (`P/display_list.c:260-279`): `[0x02][draw sequence]` (≥ 3 bytes). Builder `DrawProtocol.displayList(calls)` (`KT/g2protocol/DrawProtocol.kt:84`).

### 3.9 Pixel RLE (and deflate)

One token format is used everywhere (bbox draw op, RLE images, the phone's optimizer records) (`P/rle.h:43-55`; `P/rle.c:40-60`; `P/texture_draw.c:59-86`; `KT/g2protocol/BleImageOptimizer.kt:561-612`):

| Token | Meaning |
|---|---|
| `[n<<4 \| c]`, n = 1..15 | n pixels of colour c |
| `[0<<4 \| c][n8]`, n8 = 1..255 | n8 pixels of c |
| `[0<<4 \| c][0x00][lo][hi]` | 16-bit LE count, **1..65535** (0 is an error) |

The low nibble is always the colour. Runs longer than 65535 are split. Runs may cross rows (the stream is in raster order). Decoders must reject: a run exceeding the remaining pixels, a truncated token, a zero 16-bit count, and (for the bbox op) trailing bytes after the last pixel.

Two pixel-sequence conventions exist:
* **Exact-pixel RLE** — exactly `w·h` pixels in raster order, no pad nibbles: bbox draw op (both forms), RLE images, glyph/icon images (`KT/g2protocol/DrawProtocol.kt:202-236`).
* **Packed-nibble RLE** — the nibble sequence of a packed buffer, i.e. `2·ceil(w/2)` nibbles per row including the pad nibble at odd widths: the historical mode-6/mode-3 records (`BleImageOptimizer.rleEncode`). For even widths the two are identical. Converting a FULL_FRAME record to a bbox op is therefore only valid for even widths (Faceclaw only uses 640).

Encoder choice (both Faceclaw encoders): count ≤ 15 → 1-byte token; 16..255 → 2-byte; ≥ 256 → 4-byte. The firmware decoder also accepts non-canonical forms (e.g. a 2-byte token with count 3).
Worst case = 1 byte per pixel.

Deflate is **not** part of the draw formats; it is applied by the transport to whole messages (§3.3). RLE-then-deflate compresses UI frames well (`G2F/demos/video-bench.ts:6-9`).

### 3.10 Draw calls (type 26 messages and display lists)

#### 3.10.1 Sequence and call framing

```
sequence := [count u16] { [len u16] [call: len bytes] } × count           (no trailing bytes)
call     := [op u8] [flags u8] [target u16 if flags&1] [depth s8 if flags&2] [op payload]
```
(`P/display_list.c:646-732`; `KT/g2protocol/DrawProtocol.kt:28-39,71-81`.)
* `flags`: bit0 `DRAW_FLAG_RESOURCE_TARGET` (1) — draw into raw image resource `target` instead of the inherited target; bit1 `DRAW_FLAG_DEPTH` (2) — per-lens horizontal shift; other bits → invalid (`P/display_list.c:24-29`).
* Walk limits: ≤ **4096 calls** per walk (nested lists included), nesting depth ≤ **8**, no cycles (ancestor check) (`P/display_list.c:6-9,260-279`).
* Type-26 message = `[0x1A] sequence`; target = the 640×480 screen buffer; **the whole sequence is validated (structure, resources present, bounds) before any pixel is written**; any failure rejects the whole message (NACK) (`P/zlib_glue.c:602-605`). Faceclaw splits call lists into messages ≤ 65,535 bytes and ≤ 4096 calls (`KT/g2protocol/DrawProtocol.kt:300-319`).
* Special resource ids: `65535` = SCREEN (the screen buffer), `65534` = CURRENT (the current target) (`P/display_list.c:8-9`).

#### 3.10.2 Numeric encodings

* Fixed fields are LE u8/u16/s16.
* **Extended integer** (used by rounded-rect x/y and rect-copy x/y/dx/dy) — big-endian-prefixed varint, `P/draw_expression.c:26-37`, `KT/g2protocol/DrawExpression.kt:38-80`:

| First byte | Total bytes | Value bits | Payload |
|---|---|---|---|
| `0xxxxxxx` | 1 | 7 | low 7 bits |
| `10xxxxxx` | 2 | 14 | 6 bits + 1 byte |
| `110xxxxx` | 3 | 21 | 5 bits + 2 bytes |
| `1110xxxx` | 4 | 28 | 4 bits + 3 bytes |
| `11110000` | 5 | 32 | 4 following bytes |
| `0xF1..0xFE` | — | — | invalid (call rejected) |
| `0xFF` | — | — | **expression escape**: followed by an *unsigned* extended integer `L`, then `L` bytes of expression program (§3.11) |

Continuation bytes are most-significant first. Signed fields sign-extend from the value bit count. Encoders use the shortest form: e.g. −1 → `7F`, −5 → `7B`, 2 → `02`, 640 → `82 80`, 480 → `81 E0`.
Outer framing errors (bad prefix, truncated, `L` beyond the call) reject the call; errors *inside* a well-framed program make the value 0 (`KT/g2protocol/DrawExpression.kt:90-99`; `P/draw_expression.c:162-170`).

#### 3.10.3 Stereo depth (`DRAW_FLAG_DEPTH`)

`depth` s8. Left lens shift = `floor(depth/2)`, right lens shift = `−floor((depth+1)/2)` (`P/display_list.c:672-679`; `KT/g2protocol/DrawProtocol.kt:44-47`). The two lenses are therefore separated by exactly `depth` pixels (the right lens takes the odd pixel). The shift is added to the inherited shift (nested lists and resource targets inherit it) and applies to x only. Faceclaw uses depth 2 (menu highlights), 4 (sidebar/menus), −2 (top bar) (`TS/graphics/image.ts:514`; `TS/ui/shell/shell.ts:236`; `TS/ui/shell/chrome-layer.ts:231`).

#### 3.10.4 Draw opcodes

Ops (`P/display_list.c:12-22`; `KT/g2protocol/DrawCallType.kt`). "In target" means `0 < w, 0 < h, x+w ≤ targetW, y+h ≤ targetH` checked **before** the depth shift (`P/display_list.c:149-157`). Pixel writes after the shift are clipped individually.

| op | Name | Payload | Semantics |
|---|---|---|---|
| 1 | BOUNDING_BOX | `[bboxFlags u8]` then compact `[x/4 u8][y/2 u8][w/4 u8][h/2 u8]` or (bboxFlags bit0 = U16) `[x u16][y u16][w u16][h u16]`, then **exact-pixel RLE** filling the rest of the call | Opaque write of a w×h rectangle. Rect must be in target; RLE must cover exactly w·h pixels with no trailing byte. Other bboxFlags bits invalid. Fast path decodes straight into the buffer when compact, shift even and fully inside. (`P/display_list.c:281-345`) |
| 2 | RECT_COPY | `[src u16][x ext][y ext][w u16][h u16][dx ext][dy ext]` | Copy src rect (x,y,w,h) to (dx,dy)+shift. `src`: 65535 = screen buffer, 65534 = current target, else a raw image resource. x,y ≥ 0 and the source rect must be inside the source **for every evaluated time**. Destination clipped; overlapping copies behave like memmove (also across different nibble alignment). If `|dx| > 65536` or the rect is entirely off-target it is skipped. (`P/display_list.c:347-427`) |
| 3 | STOCK_FONT_STRING | `[x s16][y s16][options u8][len u8][len bytes]` | Text in the stock **20 px LVGL font chain** (with stock fallbacks and pair kerning). Bytes 1..31 move the pen by `byte − 11` (−10..+20 px); everything else must be strict UTF-8 (no NUL, no overlongs, no surrogates). Glyph at `(x+ofsX, y + (lineHeight − baseLine) − boxH − ofsY)`, advance = glyph `adv_w`. Uses the live font root at `0x20077A54`; fails if stock hasn't loaded it. (`P/texture_draw.c:447-528`; `P/display_list.c:429-449`) |
| 4 | IMAGE | `[id u16][x s16][y s16][options u8]` | Draw image resource at (x,y). (`P/texture_draw.c:278-298`) |
| 5 | TEXT | `[font u16][x s16][y s16][options u8][len u8][len bytes]` | Bytes 1..31: pen += byte−11. Bytes 32..127: draw that glyph image at (pen, y) and advance pen by the **glyph image width**. Byte 0 or > 127, or a used char without a glyph (offset < 193) → invalid. (`P/texture_draw.c:300-355`; `P/display_list.c:480-508`) |
| 6 | REMAP_COLORS | `[x u16][y u16][w u16][h u16][evenLUT 8 B][oddLUT 8 B]` | Per pixel `v := LUT[v]`, using `evenLUT` where (shifted target x + y) is even, `oddLUT` otherwise. Each LUT = 16 nibbles packed, entry i at byte i/2, **high nibble for even i**. Rect must be in target (pre-shift); columns clipped after shift. (`P/display_list.c:510-568`) |
| 7 | DISPLAY_LIST | `[id u16]` | Execute display-list resource id against the current target (inheriting shift). |
| 8 | ROUNDED_RECT | `[x ext][y ext][w u16][h u16][radius u16][fill u8][border u8]` | 1 ≤ w ≤ 640, 1 ≤ h ≤ 480, fill ≤ 15, border ≤ 16 (16 = no border). Fill is **max-blended** (`v := max(v, fill)`); border pixels are overwritten. Geometry below. (`P/display_list.c:570-619`) |
| 9 | CLEAR | `[color u8]` | Fill the **entire** target buffer (padding nibbles included) with `color*0x11`; ignores depth. (`P/display_list.c:621-633`) |

**Options byte** (ops 3, 4, 5; `P/texture_draw.h:6-9`, `P/texture_draw.c:129-138`): bits 0-3 `top` (brightness), bit 4 `TRANSPARENT` (0x10), bit 5 `INVERSE` (0x20). LUT: `lut[i] = (src(i) · top) / 15` (integer), `src(i) = INVERSE ? 15−i : i`. Transparency tests the **source** value before the LUT: source 0 pixels are skipped. `options = 0x0F` = opaque identity (raw images then use a fast packed copy), `0x1F` = transparent identity. Faceclaw draws glyphs with `top = nibble(value) | 0x10` and icons with `0x1F`.

**Rounded-rect geometry** (firmware is normative, `P/display_list.c:159-211,570-619`): clamp `r = min(radius, w/2, h/2)` (integer). For row `yy` (0-based within the rect) let `e = min(yy, h−1−yy)`, `dy = 2r − (2e+1)`; if `dy ≤ 0` the inset is 0, else the inset is the smallest `k ≥ 0` with `(2(r−k)−1)² ≤ 4r² − dy²`. The row's span is `[x+k, x+w−k)`. With a border (< 16): rows 0 and h−1, or any row when w ≤ 2 or h ≤ 2, are all border; other rows use an inner inset computed the same way for row `yy−1` of height `h−2` and radius `max(r−1,0)`, giving border on `[x+k, x+1+k')` and `[x+w−1−k', x+w−k)` and fill between. Fill value 0 with blending is a no-op; fill 15 is a plain overwrite. Rows with y outside the target are skipped; the whole rect is skipped if entirely off-target. Faceclaw's Kotlin reference uses an equivalent per-pixel test `dx²+dy² ≤ 4r²` with `dx = max(0, 2r−(2·min(x,w−1−x)+1))` (`KT/graphics/DisplayListRenderer.kt:122-128,377-399`).

**Test vectors** (`TEST/DrawWireTest.kt:8-37`):
```
image(0x1234, −32768, 32767, 0x1F, target 511, depth −128) = 04 03 ff 01 80 34 12 00 80 ff 7f 1f
rectCopy(CURRENT, 1,2, 3,4, −5,−6)                        = 02 00 fe ff 01 02 03 00 04 00 7b 7a
stockText(−1, 2, 15, "Aé")                                = 03 00 ff ff 02 00 0f 03 41 c3 a9
text(511, −2, 3, 31, 01 41 7f)                            = 05 00 ff 01 fe ff 03 00 1f 03 01 41 7f
playList(511, depth 127)                                   = 07 02 7f ff 01
roundedRect(−1, 2, 640, 480, 65535, 15, 16)               = 08 00 7f 02 80 02 e0 01 ff ff 0f 10
remap identity (lut(640,480,256))                          = 06 00 00 00 00 00 80 02 e0 01 01 23 45 67 89 ab cd ef 01 23 45 67 89 ab cd ef
bbox 8×2 all 15 (compact)                                  = 01 00 00 00 00 02 01 0f 10
bbox 1×1 at (1,0) colour 15 (u16)                          = 01 00 01 01 00 00 00 01 00 01 00 1f
clear(15, target 511)                                      = 09 01 ff 01 0f
sequence([playList…])                                      = 01 00 05 00 07 02 7f ff 01
type-26 message                                            = 1a 01 00 05 00 07 02 7f ff 01
set root SCREEN                                            = 1b ff ff
screenCopy(640,352)                                        = 02 00 ff ff 00 00 80 02 60 01 00 00
screenCopy(640,352, depth −32)                             = [09 00 00] [02 02 e0 ff ff 00 00 80 02 60 01 00 00]
```
(Note in the roundedRect vector `640` is the u16 width `80 02`, not an extended integer.)

### 3.11 Draw expressions (revision 27+)

A value encoded with the `0xFF` escape is a program for a small typed stack VM evaluated at draw time (`P/draw_expression.c`, `KT/g2protocol/DrawExpression.kt:106-259`, TS builder `TS/graphics/draw-expression.ts`).

* Program ≤ **1024 bytes**, stack ≤ **32** entries; each entry is `i32` or `f32` (IEEE single).
* Result = top of stack; an `f32` result is converted with F2I semantics. Any error (unknown op, underflow, type mismatch, overflow of the stack, non-finite float, divide by zero, out-of-range F2I, a truncated operand, an empty stack at the end) makes the **value 0** and discards the "animation pending" signal for that program.

| Op | Code | Stack effect / semantics |
|---|---|---|
| PUSH_I32 | 1 | followed by a **signed extended integer** (§3.10.2) |
| PUSH_F32 | 2 | followed by 4 bytes LE float; must be finite |
| DUP | 3 | a → a a |
| DROP | 4 | a → |
| SWAP | 5 | a b → b a |
| IADD, ISUB, IMUL | 16, 17, 18 | i32 wrap-around |
| IDIV, IMOD | 19, 20 | truncating; b = 0 → error; `INT_MIN / −1 = INT_MIN`, `INT_MIN % −1 = 0` |
| INEG | 21 | wrap |
| IMIN, IMAX | 22, 23 | signed |
| FADD, FSUB, FMUL, FDIV | 32, 33, 34, 35 | f32; FDIV by 0 → error |
| FNEG, FMIN, FMAX | 36, 37, 38 | |
| I2F | 48 | i32 → f32 |
| F2I | 49 | f32 → i32, truncate toward 0; non-finite or outside [−2³¹, 2³¹) → error |
| TIME | 50 | i32 `max` (≥ 0) → `min(elapsedMs, max)`; if the result < `max` the frame is marked **animation pending** |
| LERP | 64 | a b t (f32) → a + (b−a)·t |
| SMOOTHSTEP | 65 | t → clamp(t,0,1); t²(3−2t) |
| EASE_IN_QUAD / OUT / IN_OUT | 66 / 67 / 68 | t², 1−(1−t)², t<½ ? 2t² : 1−2(1−t)² |
| EASE_IN_CUBIC / OUT / IN_OUT | 69 / 70 / 71 | t³, 1−(1−t)³, t<½ ? 4t³ : 1−4(1−t)³ |
| (bridge only) ELAPSED | 128 | TS→Kotlin placeholder replaced by `PUSH_I32 elapsed` before sending; the firmware rejects it (value 0) |

All easing ops clamp t to [0,1] first. Integer operands of I-ops must be i32-typed and F-ops f32-typed (no implicit conversion). `elapsedMs` = firmware ms since the last inbound PRESENT (`draw_elapsed_ms = FW_MS_TICK − animation_origin_ms`, `P/zlib_glue.c:600,627-629`); inside a type-26 message it is the time since the previous PRESENT.

Standard animation program (Faceclaw `DrawValue.animate(from, to, durationMs, elapsedMs)`, `KT/g2protocol/DrawExpression.kt:9-35`):
```
PUSH from; I2F; PUSH to; I2F; PUSH (D−e); TIME; PUSH e; IADD; I2F; PUSH D; I2F; FDIV; SMOOTHSTEP; LERP; F2I
```
= `round-toward-0( lerp(from, to, smoothstep((min(t, D−e) + e)/D)) )`; pending until `t ≥ D−e`.


### 3.12 Display lists, the root list, PRESENT and animation

**SET_ROOT (27)** `[0x1B][id u16]`: `id = 65535` clears the root. Otherwise the list graph rooted at `id` is fully validated (resources exist and are the right type, nesting/cycle/budget limits, every call well-formed, rect copies in bounds at the current elapsed time) **before** the root changes; failure → NACK and the old root remains (`P/zlib_glue.c:606-611`; `P/display_list.c:750-760`). Validation does not draw.

**PRESENT (28)** `[0x1C]` (`P/zlib_glue.c:613-641`):
1. Take the display gate (§3.5). Increment the brightness "present epoch".
2. `animation_origin_ms := now` (only inbound PRESENT resets time; timer re-renders keep it).
3. If there is **no root**: `composition := copy(screen buffer)`. If there **is** a root: validate the root graph, then render it **into the composition buffer** (target = composition, 640×480; the list must itself copy the screen, typically `RECT_COPY` from SCREEN = 65535; anything it doesn't draw keeps the previous composition content).
4. Publish the composition as the pending direct job and queue a full refresh (`P/zlib_glue.c:643-664`); failure to queue → NACK.
5. If any `TIME` expression returned less than its max during the render, schedule the animation timer.

The ACK of PRESENT means "queued for the display task", not "photons emitted".

**Animation** (`P/zlib_glue.c:309-354`): a one-shot 45 ms timer. On each tick, if the lease is valid and no direct/panel job is pending, the worker re-renders the root list into the composition buffer with `elapsed = now − origin` and presents again, recording paint time; it keeps rescheduling while the render still reports "animation pending". It stops on: lease loss, render failure, cleanup, or **any** incoming 21, 22, 26, 27 or 29 message ("a new staged frame must not leak through an animation redraw before its PRESENT", `P/zlib_glue.c:431-437`) — until the next PRESENT restarts it. Since animation re-renders read the screen buffer, the phone must not change the screen between PRESENTs of one animation (it can't: any draw stops it).

**Canonical root list** (Faceclaw, `KT/graphics/ShellScene.kt:10-25`; `KT/g2protocol/DrawProtocol.kt:123-129`):
```
depth == 0:  [RECT_COPY src=SCREEN (0,0,640,480) → (0,0)]
depth != 0:  [CLEAR 0] [RECT_COPY src=SCREEN (0,0,640,480) → (0,0) with DRAW_FLAG_DEPTH depth]
then: presentation "selections" (menu highlights etc.), then for each shell layer:
      [REMAP_COLORS whole screen with DimDither LUTs]  (only if the layer dims what is below it)
      [IMAGE surfaceId at (layer.x, layer.y), options 0x0F, depth = layer.depth]
      [that layer's own selections]
```
The root list is itself a content-addressed resource (`CachedResource(displayList(rootCalls))`), re-uploaded whenever its bytes change (`KT/g2protocol/ScenePlanner.kt:215-218`).

**Phone reference interpreter**: `KT/graphics/DisplayListRenderer.kt` executes the same grammar on phone buffers (validate pass then apply pass, `:161-187`), used for the phone preview; `KT/graphics/DisplayListPlayer.kt` reproduces the 45 ms timer semantics (PRESENT resets the clock, redraw while `animationPending`, `:12-42`). It is a good executable spec but differs from the firmware in minor ways (e.g. rect copy snapshots the source; rounded-rect uses a per-pixel test); where they differ, the C code is normative.

**TS display-list authoring** (`TS/graphics/display-list.md`, `display-list.ts`): apps attach `DisplayList{resources: gray8 images, calls: ROUNDED_RECT | IMAGE | RECT_COPY, timeline{token, startedAt}}` to an image at a placement `(x, y, width, height, depth)`. Kotlin (`KT/graphics/FrameDisplayList.kt`) allocates cache ids for the local images, adds the placement to coordinates, binds `ELAPSED` (128) to "ms since timeline start" and appends the calls to the root list (`:27-69`). Bridge limits: ≤ 256 images/record, ≤ 64 KiB per packed image, ≤ 4 MiB per record, ≤ 4096 calls, ≤ 900 bytes per expression before binding (`TS/graphics/display-list.md:89-92`). `E.progress(duration, delay)` builds `(min(elapsed,end) + time(max(end−min(elapsed,end),0))) … / duration` so an animation survives intermediate PRESENTs without restarting (`TS/graphics/draw-expression.ts:287-303`).

### 3.13 Direct framebuffer presentation (the core CFW patch)

`patch_compress.py` rebuilds stock 2.3.0.24 by (a) appending one position-independent code blob to the main-app image and (b) redirecting a set of `bl` call sites into it (`P/patch_compress.py:40-60,340-510`). Display-relevant sites:

| Site (stock address) | Change | Purpose | Source |
|---|---|---|---|
| `0x47A78E`, `0x47A8CA` (display task, queue msg types 3 and 6) | `bl FUN_00470aa0` → `bl display_copy_hook` | replace the stock 576×288→640×480 copy with the hook | `P/patch_compress.py:195-202,493-495` |
| `0x4D6994` (BLE ATT write callback) | `bl TPL_ReceivePacket` → `bl cfw_receive_packet` | private SID-0xF0 transport before stock reassembly | `:146-148,453-455` |
| 3 bridge sites | → `faceclaw_input_bridge_received` | ordered inter-lens delivery of private packets | `:149-153,450-452` |
| `0x4AA8DC` | settings responder send → `settings_send_wrapper` | adds field 100 `"Faceclaw/34"` | `:161,466-467` |
| `0x4A9FDC` | settings decode → `settings_decode_wrapper` | parses lease field 101 | `:162-164,468-470` |
| `0x48DAE8` | TLSF init size 0x2D000 → 0x2CC00 | reserve 1 KiB SRAM for the CFW context pointer | `:93-105,462-464` |
| `0x4C9B3C`, `0x7BCBA0`, `0x47BDB4` | LE 2M feature bit; fast conn interval min=max=7.5 ms latency 0; force fast mode | BLE throughput | `:80-91,456-461` |

`display_copy_hook()` runs on the stock display task in place of the stock copy (`P/zlib_glue.c:742-794`):
```
panel_service()                                   // diagnostics; may restore panel registers
if brightness_service() returns "freeze/black": drop any pending direct job; return   (§3.14)
if a panel test pattern is active: drop any pending direct job; return
if no direct job pending:
    if direct_active and lease deadline still in the future: return    // keep the physical frame; suppress stock repaint
    direct_active = 0; stock copy (FUN_00470aa0); return               // fail-open
memcpy(physical FB, composition, 153600); draw debug overlay if enabled
clear pending job; D-cache clean(FB, 153600); direct_active = 1       // (on failure: stock copy, direct_failed)
```
The display gate is held by the presenting worker from before it touches the composition buffer until the display task signals after the hook, so the composition buffer cannot change mid-copy; the next PRESENT blocks (with a timeout → NACK) until then. This serialises PRESENTs with panel refreshes and is the firmware's back-pressure.

### 3.14 Brightness message (type 30)

`[0x1E][version=1][level 2..100][visible 0|1][duration u16 LE ≤ 3000]`, exactly 6 bytes, lease required (`P/brightness.c:39-54`). The command task only latches a request word `level | visible<<8 | duration<<9 | (presentEpoch & 1023)<<21` and starts a 40 ms timer that queues display refreshes; all panel writes happen on the display task inside `display_copy_hook` (`P/brightness.c:1-16,33-38,58-114`):
* First request after activation starts from level 2, invisible.
* A change of `visible` or (while visible) of `level` starts a fade from the current level to the target (`visible ? level : 2`) over `duration` ms using smoothstep in Q10: `t = elapsed·1024/duration; gain = t²(3072−2t)/2²⁰; level = from + round((to−from)·gain/1024)` (`P/brightness.c:18-25`).
* **Wake** (invisible → visible): the fade does not start until a PRESENT *newer than the request* is pending; until then the physical frame stays frozen (pending older jobs are dropped). This makes the new frame fade in instead of flashing stale content. Faceclaw therefore always queues the brightness message **before** the frame's commands (`KT/g2protocol/session/GlassesSessionSend.kt:733-734`).
* **Sleep** (visible = 0): fades down to level 2, then zeroes the physical framebuffer and keeps it black; the phone may keep staging frames (they appear on the next wake).
* Output is written through the stock calibrated brightness sequence (`BRIGHTNESS_CONVERT` + `BRIGHTNESS_APPLY`) only when the value changes and the display is on; the stock persistent setting and ALS learning are bypassed (`P/brightness.c:19-31,95-100`).
* Lease loss, cleanup (11) or release restores the stock level.
Faceclaw policy (`KT/g2protocol/BrightnessPolicy.kt`, `session/GlassesSessionBrightness.kt`): phone owns auto-brightness from the ALS (type 16 passive mode, 500 ms polls when auto, 5000 ms manual); target = min + (max−min)·curve(ln(1+lux)), default curve `0:0,0.2:7,1.5:33,5:100`, bounds 20..100, deadband 5; fade 280 ms on visibility changes (0..1500 configurable), else 1200 ms (auto) / 120 ms (manual).

### 3.15 Cleanup (type 11) and lifecycle

`[0x0B]` (gated) returns the lens to stock behaviour without freeing the context itself (`P/zlib_glue.c:677-740`): stop animation; clear lease deadline, `direct_active`, pending job; free resource cache, screen and composition buffers, clear root; stop/delete buzzer timer; stop mic, ALS passive mode, brightness (restore stock level), compass; clear the wake lease (launching a pending stock dashboard if one was deferred); hide the diagnostic overlay. Faceclaw sends it as the very last message before an intentional disconnect, after draining all in-flight messages, and treats its ACK as "safe to close" (`KT/g2protocol/session/GlassesSessionCore.kt:1100-1181`).

Session state machine (per lens, display-relevant):
```
          FB_ACQUIRE (fresh)                 first PRESENT
 IDLE ───────────────────────► LEASED ─────────────────────► DIRECT
  ▲  (stock UI visible;          │ (stock UI still visible;     │ (CFW frame shown;
  │   CFW draw msgs NACK)        │  CFW msgs accepted)          │  stock repaints suppressed)
  │                              │                              │
  └──── lease expiry (90 s) / FB_RELEASE / CLEANUP ◄────────────┘   (FB_ACQUIRE renewal keeps state)
```
Buffers survive lease loss (screen/composition keep content; resource cache is freed); only CLEANUP frees everything.

### 3.16 Diagnostics (not needed for normal operation)

* **Overlay** (type 7 sub 2 shows, 1 hides, 0 clears flags): three lines of 6×12 Terminus text at (2,2), (2,14), (2,26) drawn onto the physical framebuffer after each direct copy: sticky `REORDER/SKIP/DUP/ALLOC` flags or `OK`, previous worker/present durations and 10-sample timer-paint average (µs); last message size + CRC; heap free/max-alloc KiB for the LVGL, EvenHub and "other" heaps (`P/debug.c:142-201`; font `P/draw.c:9-118`). Hidden by default at boot and after cleanup.
* **Panel access** (types 23/24/25), 12-byte request `[type][reqId u16][lenses mask (bit0 left, bit1 right)][op][len][addr u32][value u16]`, validated by `P/panel.c:70-91`, executed on the display task, reply fragments on sid 0xF0 `[4][reqId u16][lens][offset][totalSize][≤3 data bytes]` (`P/message_transport.c:288-302`). Read ops: 0 = snapshot (ID, SR1, SR2, SR3, LUM, current, 0xC1, 12 bytes at 0x1FFF, CRC-16 of the framebuffer, pattern, saved flag), 0xFE = read framebuffer bytes (≤ 32), 0x81 = read 12 bytes at address ≤ 0x1FFF, 5/0x35/0x59/0x47 (1 B), 0x37/0xC1 (2 B), 0x9F (4 B). Write ops (type 24): 0 (display on/off via SR1 bit 6), 1 (SR1, mask 0x78), 0x31 (≤ 7), 0x46 current (≤ 63), 0x57 (SR3), 0x36 luminance (big-endian u16, max by RFFQ index `{21331,10664,7109,5331,4264,3366,2907,2558}`), 0x97, 0xFE restore baseline, 0xFD recover. Register writes auto-revert after 15 s or lease loss. Type 25 patterns: 0 = off, 1..16 = uniform level (pattern−1), 17 = horizontal ramp (level = x/40), 18 = centre patch 160×160 at (240,160) with `value` (≤ 15); patterns expire after 60 s. Result header `[1][status 0 ok/2 invalid-or-no-backend/3 io error/4 partial][type][op][tick u32]` (`P/panel.c:122-227`). Use only for bring-up.

### 3.17 History: retired modes and the "576 carrier lift"

* Revisions < 8 carried custom image commands **inside stock EvenHub `Cmd=3` ImgRawDataUpdate messages**: `MapRawData = [mode byte][zlib(RLE pixels)]`, addressed to a **576×288 EvenHub image container** — the "carrier" (`G2F/demos/video-bench.ts:6-20,423-436,494-503`). Stock caps an image container far below that (288×144 tiles in practice, §2.5), so the CFW lifted the stock size limit to 576×288 ("img576" capability token in old `EVENCFW/<v>` advertisements, `G2F/demos/README.md:39-43`; `KT/g2protocol/BleProtocol.kt:300-303`; `G2F/README.md:152-153`). This is the "576 carrier lift". The exact stock check that was patched is not present in the current sources (inference).
* Revision 8: "stock EvenHub image handling restored; custom commands use SID-f0 only" (`P/settings_ext.c:450`). The current patch list (`P/cfw_patches.json`, printed by `P/patch_compress.py:434-508`) contains **no carrier/size patch**; the README line and `G2F/demos/video-bench.ts` / `detect-cfw.ts` are stale and will not work against Faceclaw/34.
* Revision 10 moved zlib from inside each image mode to the transport; revisions 12–14 (texture commands), 18 (cache write) were retired; revision 23 introduced resource ids (21/22); 26–29 draw calls/root/present/surfaces replaced modes 3/6/8/9/15/19/20 (`P/settings_ext.c:442-470`; `P/zlib_glue.c:18-170`).
* Faceclaw still *produces* the retired layouts internally (TexturePlanner, BleImageOptimizer) and converts them with `DrawProtocol.fromOptimized` before sending (§4.9). A re-implementation should generate draw calls directly.
* Historical frame-id diagnostics (`fid` in mode-3 records, the firmware's REORDER/SKIP/DUP flags, `P/debug.c:91-112`) are dead: `cfw_diag` has no remaining caller, and `fid` is dropped during conversion (`KT/g2protocol/DrawProtocol.kt:256-263`).

### 3.18 Frame pacing, acknowledgement and coalescing (Faceclaw behaviour)

Producer side (`KT/g2protocol/session/GlassesSessionCore.kt:1029-1058`; `KT/graphics/SurfaceCompositor.kt:599-724`):
* Every surface update recomposites synchronously and stores the result as the single **desired frame** slot (latest wins). Each composite has a monotonically increasing sequence number; an older composite that loses the store race is discarded; a desired frame superseded before being planned is finished as "discarded".
* A composite's fingerprint (string of surface geometry + content fingerprints + shell-scene fingerprint) identical to the last enqueued one → nothing is sent (`maybeFinishNoChangeDesiredFrame`, `GlassesSessionSend.kt:250-268`).
* TS render loops coalesce (one render in flight, one queued) and apply back-pressure by awaiting "frame finished" (≤ 6 s) (`TS/g2/dashboard-controller.ts:155,2485-2548`).

Send loop (`KT/g2protocol/session/GlassesSessionSend.kt:17-248`), each iteration:
1. Drain in-order CFW completions; re-queue CFW messages for replay on NACK/timeout (§3.3).
2. Renew the framebuffer lease every 45 s (both arms, fire-and-forget).
3. If the EvenHub layout is not yet created, queue it (right arm, `Cmd=0`, ACK required).
4. Heartbeat gate: if ≥ 4 s since the last heartbeat/image ACK and nothing is in flight, send an EvenHub heartbeat — unless an image is waiting and < 6 s, in which case the image goes first (image ACKs reset the firmware keep-alive). While a heartbeat is in flight nothing else is sent ("inter-lens sync issues"). Heartbeat failure after 10 s → reconnect (`GlassesSessionSend.kt:288-340`; `ConnectionOptions.kt:26-28`).
5. If the window has room (< 3 in flight) and the queue is non-empty: send the next queued message. If the window has room, the queue holds no image message, the retry back-off (2 s after an image timeout) has elapsed, and the desired fingerprint differs from the last enqueued one: **plan** the desired frame now (§4.10) — so planning happens as late as possible and always from the newest composite, and the next frame's delta is computed against the last *enqueued* frame so it can pipeline behind unacknowledged ones.
6. The frame's commands are queued FIFO: all but the last as kind `resources`, the final PRESENT as kind `image`. The PRESENT's ACK (both lenses) finishes the frame and updates `displayedFingerprint` when no other image is outstanding (`GlassesSessionSend.kt:800-904`).
Recovery: a timed-out `resources` message resets the cache model (full keyframe next); CFW replays exhausting 3 retries or > 8 consecutive ACK timeouts → full reconnect, which resets the transport contexts, the cache model and the delta base (`GlassesSessionControls.kt:389-413`; `GlassesSessionSend.kt:1160-1219`).

### 3.19 Bandwidth and latency numbers

| Quantity | Value | Source |
|---|---|---|
| BLE link on CFW | LE 2M PHY (phone must request; Faceclaw connects with `PHY_LE_2M|PHY_LE_1M`), 7.5 ms interval, latency 0, fast mode forced; MTU 512 requested | `P/patch_compress.py:80-91`; `AND/FaceclawBleManager.kt:151-157`; `KT/g2protocol/ConnectionOptions.kt:8` |
| Sustained CFW throughput | ≈ 41 KiB/s with 2,000-byte messages, window 3 (hardware-tested on 2.2.9; "2.3.0 needs hardware validation") | `P/patch_compress.py:80-81` |
| Per-packet payload | ≤ 252 B data in ≤ 263 B writes (11 B overhead) + 5 B record header per message | §3.3 |
| CFW ACK latency | p99 63 ms, max 77 ms (Flappy capture) | `KT/g2protocol/CfwMessageWindow.kt:158-161` |
| Input-to-pixels | ≈ 250 ms, "a handful of fps" (Flappy ≈ 12 fps, Pinball ≈ 9 fps render ticks) | `TS/apps/flappy/flappy-app.worker.ts:7-9`; `TS/apps/pinball/pinball-app.worker.ts:6-11` |
| On-glasses animation | 45 ms tick (≈ 22 fps max), no BLE traffic | `P/zlib_glue.c:313` |
| Raw full frame | 153,600 B (640×480×4 bit) | |
| Stock EvenHub images | ≈ 8.8 KB/s; 288×144 BMP (20,854 B) ≈ 2.4 s serial | `KIT/ble/docs/images.md:138-150`; `KIT/ble/messages.ts` |


---

## 4. Phone-side rendering pipeline

### 4.1 Data flow

```
TS app/shell paint ──► GrayImage (8-bit grey + deferred draws) ──► Plane[] ──► flattenPlanesWithDraws
      │                                                                  │
      │   app windows: submitSurfaceFrame(surfaceId, gray8, rect, fingerprint, drawRecords)
      │   shell chrome: submitShellScene(encodeShellScene(planes))
      ▼
Kotlin SurfaceCompositor (retained 8-bit surfaces, z-order, colour key)
      │  composite(): screenGray (app surfaces only) + ScreenDraw list + ShellScene
      ▼
BmpUtil.pack4bppFromGray8 → packed 640×480 A4 "screen" + fingerprint → desired-frame slot
      ▼  (send loop, §3.18)
ScenePlanner: shell layers → surface resources + bbox updates; app screen → TexturePlanner
      (cached glyph/icon draws + RLE deltas) or plain 32-row bbox bands; root display list; PRESENT
      ▼
CFW transport (deflate, window) → glasses
```
The glasses' **screen buffer holds only app pixels** ("the delta base contains only app pixels; shell resources are composed at presentation", `KT/g2protocol/ScenePlanner.kt:172`). Shell chrome (sidebar, top bar, overlays, menu highlights) lives in retained resources composed by the root list, so chrome changes never dirty the app delta and vice versa.

### 4.2 In-memory images

**TS `GrayImage`** (`TS/graphics/image.ts:143-647`): `width`, `height`, `pixels: Uint8Array` (8-bit grey, row-major, one byte per pixel) and an ordered list of **deferred draws** that are *not* yet in `pixels` and always render **above** the raster:
* `glyph` {font, glyph, x = pen x, y = line top, value 0..255} (`:63-70,481-483`);
* `image` {source GrayImage (immutable, flattened), x, y, optional `presentation`} — plain images are colour-key blits (source 0 = transparent) (`:78-84,492-495`);
* `image` with `presentation` = retained stereo/menu drawing replayed on the glasses (menu selection: rounded highlight + transparent row image; transparent image; masked image; or an arbitrary `DisplayList`) (`:514-531`);
* `fwtext` {EvenHubFont run: x, y, value, members [cp, dx, ink]} drawn by firmware op 3 (`:117-133,503-512`).
Drawing primitives write raster directly: `fillRect`, `drawRect`, `drawLine` (Bresenham), `fillRoundedRect`, `drawRoundedRect` (row spans with pixel-centre circle test, default radius 8), `bitBlt` (optionally colour-keyed; carries the source's deferred draws along) (`:194-385,626-647`). `to8bppBuffer()` bakes deferred draws (`:467-475`). Content hash = FNV-1a over pixels folded with draw identities (`:387-455`).

**Colour-key convention**: grey 0 = transparent, **1 = opaque black** (both quantise to level 0; painters of colour-key surfaces clamp intentional black to 1) (`KT/graphics/SurfaceCompositor.kt:18-27`; `TS/ui/shell/geometry.ts:69-74`).

**Quantisation to the panel** (must be identical everywhere, the planner compares pixels exactly): `nibble(v) = min(15, (v + 8) >> 4)` (`KT/util/BmpUtil.kt:15-26`; `TS/graphics/image.ts:660-662`). Level n is best written as grey `16·n` (which quantises back to n).

**Canonical frame format**: packed A4, stride `ceil(w/2)`, high nibble = left, odd rows padded with a zero low nibble (`KT/util/BmpUtil.kt:28-71`). Same layout as the firmware buffers.

**Photo tone** (`KT/graphics/GrayPacket.kt:14-116`): ARGB → luma `(299R+587G+114B)/1000 · A/255` → tone curve `255·(v/255)^gamma` (photo preset gamma 2.2 undoes sRGB because the panel is linear) → optional **serpentine Floyd–Steinberg** error diffusion onto the 16 levels (7/16, 3/16, 5/16, 1/16), writing level n as `16n` and level 0 as **1** (colour-key safe). PNG assets decoded in TS use Rec.709 luma × alpha without dithering (`TS/graphics/imagefile.ts:9-33`).

**Antialiased glyph bake** (firmware-exact): for coverage nibble `c` and draw value `v`, pixel = `floor(c · nibble(v) / 15) · 16`, skipping c = 0 — the same LUT the firmware applies for ops 3/4/5, so on-glasses replays match the phone composite bit-for-bit (`TS/graphics/image.ts:665-701`; `TS/graphics/evenhub-font.ts:401-428`).

### 4.3 Planes (TS)

A frame is `Plane[] = {image, x, y, depth?, dimUnderneath?, shellKey?}` composited in array order with the colour-key rule (`TS/graphics/plane.ts:14-35`). `flattenPlanesWithDraws` bakes each plane's regular deferred draws into a single raster (so a later plane's raster can cover an earlier plane's text), but returns glyph/image/fwtext draws (translated) for wire replay, plus presentation records (`TS/graphics/plane.ts:69-133`):
* A plane with non-zero `depth` is removed from the screen raster entirely and re-emitted as a cropped **masked-image presentation** at that depth (the glasses replay it per lens with the shift).
* For every presentation, **occlusion rectangles** are computed: run-length rows of pixels covered by later, non-depth planes; these become `RECT_COPY src=SCREEN` calls after the presentation so later opaque content is restored on top (`TS/graphics/plane.ts:105-128`; `KT/graphics/MenuSelection.kt:145-162`).
Plane fingerprint: `depth:x,y+wxh:imageFingerprint` joined by `|` (`TS/graphics/plane.ts:140-144`).

### 4.4 Surfaces (Kotlin `SurfaceCompositor`)

(`KT/graphics/SurfaceCompositor.kt`; TS twin for the iOS/preview path `TS/graphics/surface-compositor.ts`.)
* Screen size configured once: 640×480 on Android (`TS/g2/dashboard-controller.ts:1233,1353`).
* Surface = id, x, y, w, h, zOrder, transparency (`OPAQUE = 0` copies all bytes, `COLOR_KEY = 1` skips 0), visible, depth, retained 8-bit pixels, content fingerprint, retained draw list. Resizing a surface zeroes it (`:321-380`).
* Updates may cover any rect; the draw list always describes the full surface and replaces the previous one (`:483-597`).
* Composite: sort by (zOrder, id), blend visible surfaces onto black; the `"shell"` surface is skipped when a shell scene is present (it is sent as a scene instead); underlay dim (legacy, when no shell scene): surfaces below a z threshold scaled by `max(1, (v·dim+128)>>8)`; image draws of dimmed surfaces drop out of the draw list (they stay baked) (`:623-776`).
* Stereo depth applies only if the topmost visible surface is opaque and covers the whole screen; it becomes the scene's `screenDepth` (root-list shifted screen copy) (`:425-444`).
* If any visible surface has zOrder > 1 (e.g. the lock screen at 1000, `TS/g2/dashboard-controller.ts:681`), the shell scene is suppressed for that composite (`:709-710`).
* Blanked mode → all-zero composite with fingerprint `blanked:WxH`; this is also what drives brightness `visible=0` (`:638-652`; `KT/g2protocol/session/GlassesSessionSend.kt:733-734`).
* Faceclaw's standard layout (`TS/ui/shell/geometry.ts`): top bar 28 px, sidebar 64 px (so the app viewport is 576 px wide, the EvenHub width) or 0 in the "640x480" display mode; window bands "min" = 288 px total (28 px top bar + 260 px content), "medium" = 316, "max" = 480, placed at `top = round((480 − band)·f)` with f ∈ {0, 0.25, 0.5, 0.75, 1} from the Vertical-position setting (0.5 → top 96 for "min"); app surfaces z 0 opaque; shell surface z 1 colour-key; lock screen z 1000.

### 4.5 Shell scene (chrome)

TS `encodeShellScene(planes)` (`TS/graphics/shell-scene.ts:21-39`) → Kotlin `ShellScene.decode` (`KT/graphics/ShellScene.kt:70-90`). Wire (internal bridge, LE):
```
[layerCount u16 ≤ 64] { [key u16][x s16][y s16][w u16][h u16][dim u16 ≤256][selectionCount u16 ≤64][depth s16]
                        [w·h grey bytes] [selection records…] }
```
Each shell plane is cropped to its non-zero bounding box (must fit a 64 KiB resource: `5+ceil(w/2)·h ≤ 65536`), `dim = round(dimUnderneath·256)` (256 = none) dims everything below it. Keys must be unique; layers + retained resources < 511. On the glasses each layer becomes a **CREATE_SURFACE resource** (owner key `shell:<key>:<w>x<h>`), updated with bbox draw calls targeting that resource, and drawn by the root list (§3.12).

### 4.6 Deferred-draw records (TS → Kotlin) and atlases

Per-frame draw buffer (`TS/graphics/glyph-wire.ts:28-31,146-239`; parsed by `KT/graphics/SurfaceCompositor.kt:46-97`), LE tagged records in draw order:

| Tag | Record |
|---|---|
| 0 GLYPH | `[0][fontId u16][encoding u32][penX s16][lineY s16][value u8]` (12 B) |
| 1 TEXTURE_IMAGE | `[1][imageId u32][x s16][y s16]` (9 B) |
| 2 FIRMWARE_TEXT | `[2][x s16][y s16][value u8][count u8]` + count × `[cp u32][dx s16][ink u8]` |
| 3/4/5 presentation | `[tag][x s16][y s16][w u16][h u16][radius u16][bg u8][border u8][depth s8][occlusions u16][w·h grey][occl × (x,y,w,h u16)]` — 3 menu selection (rounded rect + transparent image), 4 transparent image, 5 masked image |
| 7 DISPLAY_LIST | `[7][len u32][x s16][y s16][w u16][h u16][depth s8][token u32][elapsed u32][nImg u16]{[w u16][h u16][grey]}[nCalls u16]{[op u8][depth s16][operands]}` |

(`TS/graphics/presentation-wire.ts:291-335`; `TS/graphics/display-list.ts:126-161`; `KT/graphics/DrawRecordKind.kt`; `KT/graphics/MenuSelection.kt:164-195`; `KT/graphics/FrameDisplayList.kt:71-133`.) These are internal; a native re-implementation can keep the same concepts with direct object references.

Atlases (process-wide registries so identity survives to the encoder):
* **GlyphAtlas** (`KT/graphics/GlyphAtlas.kt`): fonts keyed by stable `atlasKey` string → small int id. Glyph = tight bbox width × **cell height (font line height)** with ink rows at `inkTop`, `bbxX` bearing; 1-bit glyphs store ink as source level 15 (LUT maps to exactly the requested top level), AA glyphs store coverage nibbles. Pre-encoded cached image `[0x08][w u8][cellH u8][exact RLE]` (`:211-325`). Registration buffers: `[keyLen u8][key][cellHeight u8][count u16]` then per glyph `[encoding u32][bbxX s8][inkTop u8][width u8][inkHeight u8]` + 1-bit rows as u32 (bit `ceil(w/8)·8−1−col`) or AA packed nibble rows (`:60-206`; `TS/graphics/glyph-wire.ts:249-318`). Only encodings 32..127 with the ink inside the line cell participate (`TS/graphics/glyph-wire.ts:65-78`).
* **ImageAtlas** (`KT/graphics/ImageAtlas.kt`): content-addressed (key `img:WxH:hash`), ≤ 255×255, stores quantised nibbles and `[0x08][w][h][RLE]`; drawn with options 0x1F (transparent identity) so exactly the non-zero pixels are written.
* **FwGlyphAtlas** (`KT/graphics/FwGlyphAtlas.kt`): rasters of the stock 20 px font (never uploaded; used only to verify/punch op-3 draws): `[cp u32][ofsX s16][inkTop s16][boxW u8][boxH u8][ceil(boxW/2)·boxH nibble bytes]`.
* **FontResourceAtlas** (§3.8) turns GlyphAtlas glyphs into font resources.

### 4.7 Frame planning: `ScenePlanner` (exact command order)

`ScenePlanner.plan(screen, w, h, draws, scene, …)` (`KT/g2protocol/ScenePlanner.kt:183-244`), called once per frame with the packed app screen:
1. If the cache model generation changed (reset): forget the delta base, shell layer pixels and the current root id.
2. For each shell layer, an owned surface resource `rawImage(w,h)`; `pin` surfaces + presentation resources; `prepare` them (must all fit, else error "Shell surfaces exceed the resource budget").
3. `drainCommands(3600)` → evictions (22), uploads (21), CREATE_SURFACE (29).
4. Build root calls (§3.12).
5. For each shell layer: `pixelCalls(previousLayerPixels, layerPixels, target = surfaceId)` → type-26 messages.
6. App screen: if texture caching is enabled, `TexturePlanner.plan(base = previous screen or null, …)`; if it returns a plan → its resource commands (21/22) + its optimizer payload converted with `DrawProtocol.fromOptimized` → type-26 messages; otherwise `pixelCalls(base, screen, target = screen)` → type-26 messages.
7. Root list resource: `prepare([displayList(rootCalls)])` + `drainCommands(3600)`; `pin` everything used.
8. If any eviction was emitted this frame, **prepend** `SET_ROOT(65535)` (clear root before evicting anything it might reference).
9. If the root id changed or evictions happened: append `SET_ROOT(rootId)`.
10. Append `PRESENT`.
11. Remember the screen as the next delta base.

**`pixelCalls(old, new, w, h, target)`** (`:227-244`): whole buffers equal → nothing. Otherwise for each 32-row band (last band shorter) whose bytes differ (or `old == null`): find the leftmost/rightmost changed pixel in the band; if `w % 4 == 0` and the band height is even, widen to 4-pixel alignment; emit **one bbox call** covering `[left..right] × band rows` (compact form when aligned and < 256 units, else u16), exact-pixel RLE of the new pixels (`KT/g2protocol/DrawProtocol.kt:202-236`). A full 640×480 keyframe = 15 bbox calls of 640×32.

### 4.8 `TexturePlanner` (text and icons as on-glasses draws)

(`KT/g2protocol/TexturePlanner.kt:52-274`.) Goal: send text/icons as cached draws instead of pixels, with the final screen buffer bit-identical to the phone composite.
1. Changed region vs the delta base: none → return null; whole frame or no base → full-frame mode; else the aligned changed box (x and width to 4 px, y and height to 2 px, computed from differing **bytes**, `BleImageOptimizer.kt:162-219`), split into ≤ 6 tighter rects (bands of changed rows merged across gaps < 4 rows, column clusters merged across gaps < 6 bytes, each tightened vertically, `:385-541`).
2. Candidates (only draws intersecting a changed rect):
   * GLYPH with a GlyphAtlas entry, encoding 32..127, gx/y in 0..65535, and **every ink pixel inside the panel equals `src·top/15`** in the new composite (top = nibble(value)) — otherwise it stays baked. ≤ 4600 glyphs.
   * IMAGE with an ImageAtlas entry, x/y in 0..65535, every non-zero source pixel equals the composite. ≤ 60 images.
   * FWTEXT runs: members whose FwGlyphAtlas raster matches the composite; maximal correct stretches become op-3 strings (≤ 255 UTF-8 bytes, pen restarts at each stretch), ≤ 80 strings (`:284-407`).
3. `ensureResident`: build/refresh the font resource per font id (§3.8) and `prepare` fonts + images (misses may evict LRU; −1 → baked) (`:703-716`).
4. Group glyphs into op-5 runs: sort by (font, lineY, top, gx); a run continues while font/line/top match and the gap to the next glyph needs ≤ 4 adjust bytes; gaps are encoded as bytes `step+11` with step ∈ [−10, +20]; run text ≤ 255 bytes; glyph advance inside a run = glyph image width; ≤ 180 runs (`:734-819`).
5. If nothing is drawable or total sub-commands > 255 → roll back cache changes, return null.
6. **Punch**: copy the new frame and zero every pixel that a selected draw will write (ink / non-zero image pixels) — this is what makes the remaining delta compress to almost nothing.
7. Encode the punched frame: full-frame → one U16 bbox over 640×480 (via historical record 6); else one compact bbox per rect (via record 3). Then image draws (options 0x1F), glyph runs (options `top|0x10`), firmware-text runs — in that order, so draws land on top of the punched rects.
8. Payload > 65,519 B → roll back, null. Resource commands drained with 3600-byte uploads.

A full-frame record larger than 65,535 decoded bytes can't be one message; Faceclaw's non-scene fallback then emits independent 640×64 bands (`KT/g2protocol/BleImageOptimizer.kt:291-327`; `session/GlassesSessionSend.kt:804-814`). With draw calls the planner simply splits calls across messages.

### 4.9 Fonts

| Font | Kind / file | Metrics | Used for | Source |
|---|---|---|---|---|
| Terminus 12 | BDF 1-bit `fonts/terminus/ter-u12n.bdf` | 6×12 cell, ascent 10, descent 2, default char U+FFFD | small bitmap UI role; terminal (6×12 cells) | `TS/graphics/bdffont.ts:207-218`; BDF header |
| Terminus 16 / 24 / 32 | BDF `ter-u16n/u24n/u32n` | 8×16 (12/4), 12×24 (19/5), 16×32 (26/6) | medium / large bitmap roles | same |
| TerminusV 12 | BDF proportional, ISO-8859-1 only, `terv12n.bdf` | 12 px, ascent 10, descent 2; codes < 32 dropped; falls back to Terminus 12 | alternative small face | `TS/graphics/bdffont.ts:212-226` |
| TTF faces | Roboto (Light/Regular/Bold), RobotoMono, Inter 18pt, Montserrat (`fonts/ttf/`) rendered by Android minikin per code point | size in px; line height = ascent+descent | **default UI font Roboto-Light 14 px**; small role line height must be 8..21; medium grows to ≥ 20, large to ≥ 26 | `TS/graphics/ui-fonts.ts:28-40,76,151-191`; `TS/graphics/ttf-font.ts` |
| EvenHub font | stock firmware 20 px Latin/Greek/Cyrillic/emoji glyphs **extracted from the user's firmware at CFW install** (not distributed); 4-bit, effectively 1-bit; row stride `floor(w/2)+1` | line height 27, baseline 22 (fallback defaults) | EvenHub app emulation; op-3 firmware-text runs | `TS/graphics/evenhub-font.ts:1-21,152-156` |
| Source Han Sans SC Light 20 | serialized LVGL font from the G2 CJK flash partition, bundled (OFL), starts at U+00A4 | from file header | CJK fallback of the EvenHub font | `KT/graphics/LvglFontFile.kt`; `TS/graphics/lvgl-font.ts` |
| CFW overlay font | Terminus 6×12 ASCII 32..126 compiled into the firmware | — | diagnostics overlay only | `P/draw.c:9-118` |

LVGL font file (`KT/graphics/LvglFontFile.kt:103-249`): header `"ZZZZ"`; line height u16 @0x38; baseline = height − u16 @0x3A; pointers are absolute addresses based at 0x80100000; descriptor pointer @0x34 → {glyph bitmaps ptr, glyph descriptors ptr, cmaps ptr, …, packed u16 @+18: cmap count bits 0-8, bpp bits 9-12 (must be 4), bitmap format bits 14-15 (must be 3)}; cmap records 20 B `{rangeStart u32, rangeLength u16, glyphIdStart u16, unicodeList ptr u32, glyphIdOffsets ptr u32, listLength u16, type u8}` types 0 (format0-full, u8 offsets), 1 (sparse full, u16 offsets), 2 (format0-tiny), 3 (sparse tiny); glyph descriptor 16 B `{bitmapIndex u32 @+0, (bytes 4..7 unused by Faceclaw), boxW u16 @+8, boxH u16 @+10, ofsX s16 @+12, ofsY s16 @+14}`; bitmap rows `floor(boxW/2)+1` bytes (the firmware's "A4 aligned" format always ends every row in a fresh byte, `P/texture_draw.c:240-245`).

### 4.10 Text layout

* **BDF** (`TS/graphics/bdffont.ts:161-204`): integer advances (`DWIDTH`); `y` is the line top, baseline = `y + ascent`; glyph top = `baseline − (bbxHeight + bbxY)`, left = `penX + bbxX`; `\n` returns to x and adds `lineHeight`; missing glyphs → exact fallback font → default char → `?` → fallback font's default.
* **TTF** (`TS/graphics/ttf-font.ts:141-198`): fractional pen accumulates; each glyph drawn at `round(penX)`; advance from a 26.6 fixed-point value; pair kerning = `measure("ab") − adv(a) − adv(b)` memoised (discarded if |kern| > size); ligatures disabled; coverage quantised with `nibble()` (fringe < 8 → 0); gamma 1.0; `atlasKey = "ttf:<basename>:<fnv32(path) base36>@<size>g<gamma>"`.
* **Wrapping** (`TS/graphics/textwrap.ts:270-456`): split paragraphs on `\n`; break opportunities after a whitespace run and after `- / , ; :`; greedy longest prefix whose summed integer advances ≤ width; skip leading whitespace on continuation lines, trim trailing whitespace; if no opportunity fits, break at the furthest fitting code point (at least one per line); optional `breakLongWords`; `truncateText` appends `...`.
* **EvenHub text** (`TS/graphics/evenhub-font.ts:180-340`): advances and kerning from `@evenrealities/pretext` (`getTextWidth(a+b) − getTextWidth(b)`), LVGL-compatible wrap (drop leading spaces, break at space/hyphen/CJK); glyphs outside the "uncertain" ranges are emitted as firmware-text runs (op 3), others baked.

### 4.11 Preview, mirror, screenshots

* `SurfaceCompositor.previewComposite()` renders the same shell scene through `DisplayListRenderer` + `DisplayListPlayer` (animated at 45 ms) on top of the app composite and returns grey = level·16 (`KT/graphics/SurfaceCompositor.kt:296-304,611-621,708-723`; `KT/graphics/ShellScene.kt:38-66`). Preview-only mode (no glasses) uses the same compositor headless (`AND/FaceclawPreviewCompositor.kt`).
* Phone bitmap: `PreviewPalette` maps grey g → `v = round(255·(g/255)^0.7)` (brighten gamma 0.7), ARGB grey or green-only (`0xFF00vv00`) (`KT/graphics/PreviewPalette.kt:163-203`; `TS/native/faceclaw-communicator.ts:7`; `AND/PreviewBitmapUtil.kt:15-33`).
* Screenshots: 4-bit greyscale PNG (IHDR bit depth 4, colour type 0, filter 0) of `grey >> 4` (`KT/graphics/SharedScreenshots.kt:8-33`); GIF recording of preview composites (`KT/graphics/SharedGifScreenRecorder.kt`).
* The preview does **not** show per-lens depth shifts unless rendered with `rightLens` (`KT/graphics/ShellScene.kt:26-37`).

### 4.12 Icons

SVG sources (Lucide, stroke width 2) are rasterised once by the platform renderer to a grey `GrayImage` and cached (`TS/graphics/icons.ts:1-20`); drawn with `drawImage` so they remain identifiable → ImageAtlas → cached IMAGE draws (§4.8).


---

## 5. Recommended minimum viable path (CFW, full 640×480)

Goal: the simplest correct implementation that shows a full frame and then incremental updates on Faceclaw/34 glasses. It deliberately skips compression, resources, root lists, brightness and animations; all of those can be layered on later without changing the basics.

### 5.1 Model

* Keep one **packed 640×480 A4 frame** (`lastSent`, 153,600 B) = what you believe the CFW screen buffer holds on both lenses. Render your UI into an 8-bit buffer, quantise with `min(15,(v+8)>>4)`, pack (high nibble = left pixel).
* All CFW messages go to **one arm** (use the left arm like Faceclaw, or the right) with lens mask **3**; EvenHub and settings messages as described below.
* With no root display list ever set, **PRESENT shows exactly the screen buffer** (`P/zlib_glue.c:634-635`).

### 5.2 Bring-up sequence

| # | Arm | Message | Wait for |
|---|---|---|---|
| 1 | both | GATT connect (2M PHY preferred), MTU 512, high priority, enable notifications on `…5402` | — (Faceclaw then sleeps 800 ms) |
| 2 | both | sid 0x80 security-auth (transport spec) | success notify (soft) |
| 3 | right | prelude `aa 21 92 13 01 01 01 20 08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00 a1 42` | ACK (sid 0x01, magic 156) |
| 4 | right | settings query `{1:2, 2:magic, 4:{1:1}}` on sid 0x09 | response; require field 100 = `"Faceclaw/N"`, N ≥ 34; otherwise use §2.8 |
| 5 | right **and** left | FB lease acquire `{1:1, 2:0, 101:"FC" 01 05 00 00}` on sid 0x09 (no ACK) | — |
| 6 | right | EvenHub input page (§2.4 bytes, `Cmd=0`) | ACK (sid 0xE0, echoed magic) |
| 7 | CFW arm | reset your CFW stream state: the first record on this connection must carry `RESET_CONTEXT` | — |

### 5.3 First (key) frame

1. `DRAW_CALLS` message(s): for each band `b = 0..14` (rows `32b .. 32b+31`) one compact bbox call
   ```
   01 00  00  00  (16·b)  a0  10  <exact-pixel RLE of the 640×32 band>
   op fl  bbF x/4  y/2    w/4 h/2
   ```
   Message = `1a <count u16> { <len u16> <call> }…`; start a new message before it would exceed 65,535 bytes (worst case a noisy band is ~20.5 KB, so ≥ 3 messages). Example all-black band 0: `01 00 00 00 00 a0 10 00 00 00 50` (RLE: colour 0, 16-bit count 0x5000 = 20,480). Alternatively the very first frame after a reconnect may start with a `CLEAR`: `1a 01 00 03 00 09 00 00`.
2. `PRESENT`: `1c`.
3. Frame each message as one CFW stream (§3.3). Uncompressed example, stream id 100/101, MTU 512:
   ```
   clear   : aa 21 64 10 01 01 f0 00 c3 0b 08 00 d4 55 1a 01 00 03 00 09 00 00 d3 37
   present : aa 21 65 09 01 01 f0 00 c3 0b 01 00 4d 32 1c 1c d9
   ```
   (`c3` = options LEFT|RIGHT|RESET|END; `0b` = record flags LEFT|RIGHT|RESET_CONTEXT; `08 00`/`01 00` = size; next two bytes = CRC of the message.)
4. The frame is on the panel once PRESENT has been ACKed by **both** lenses (two notifications on the ingress arm, lens byte 1 and 2, `size`/`crc` equal to the message's). Set `lastSent = frame`.

### 5.4 Incremental frames

1. Diff the new packed frame against `lastSent` (the frame you last *sent*, not the last acknowledged — this allows pipelining).
2. For each dirty region emit one bbox call with the **new** pixels (exact-pixel RLE, row-major, no padding). Use the compact form when `x, w` are multiples of 4 and `y, h` multiples of 2 (all quotients < 256), else the u16 form (`bboxFlags = 01`, `x y w h` u16). Faceclaw's simple policy: per 32-row band, one rect spanning the leftmost..rightmost changed pixel, widened to 4-pixel alignment (§4.7) — good enough.
3. Send `DRAW_CALLS` then `PRESENT`. Skip both if nothing changed.
4. Keep ≤ 3 CFW messages in flight; match ACKs by (stream id, size, CRC); a message is done when both lenses ACKed; complete strictly in order.
5. Coalesce: if the UI produces frames faster than they drain, keep only the newest pending frame and diff it against `lastSent` when a window slot frees up.

### 5.5 Keep-alive and recovery

* Renew the FB lease on both arms every ≤ 45 s (lease is 90 s).
* If no heartbeat/CFW ACK for 4 s and nothing is in flight, send EvenHub heartbeat `Cmd=12` to the right arm (`08 0c 10 <magic> 72 02 08 00`); don't send anything else while it is outstanding (ACK timeout 1.5 s; give up and reconnect after 10 s).
* On NACK or a 500 ms ACK timeout: re-send **all** unacknowledged CFW messages in their original order with new stream ids (with compression: reset the deflater and set RESET_CONTEXT). After 3 retries of a message, reconnect.
* After any reconnect or doubt, send a full keyframe (the CFW screen buffer usually survives, but do not rely on it).
* Draw calls must be idempotent: a replay may execute a message twice (bbox writes absolute pixels, so this is safe).

### 5.6 Teardown

Wait for in-flight messages to drain, then send `0b` (CLEANUP, ACKed by both lenses) as the last message and disconnect. If that fails, send FB lease release (op 6) to both arms. Without either, the last CFW frame stays frozen on the lenses for up to 90 s.

### 5.7 Upgrade path (in order of value)

1. **Transport deflate** (persistent zlib, `SYNC_FLUSH` per record, `COMPRESSED|RESET_CONTEXT` on the first record, then `COMPRESSED` only). Large win for RLE'd UI.
2. **Brightness (type 30)** for proper blank/wake fades, bound to your "screen off" state (§3.14).
3. **Shell layers as surfaces + root list** (CREATE_SURFACE, bbox into surfaces, root = screen copy + `IMAGE` per layer, SET_ROOT, PRESENT) — chrome changes no longer touch the screen buffer; enables per-layer stereo depth and dim-underneath (REMAP_COLORS with DimDither LUTs).
4. **Texture cache** for text and icons (fonts/images as resources, glyph-run TEXT calls, punched deltas) — §4.8.
5. **Display-list animations** (expressions with `TIME`) for smooth motion without BLE traffic.

---

## 6. Gotchas & pitfalls

1. **Retired types are NACKed.** Types 3, 6, 8, 9, 12–15, 18, 19, 20 exist only as Faceclaw-internal "optimizer records"; sending them to Faceclaw/34 fails (`P/zlib_glue.c:617`). `G2F/demos/video-bench.ts`, `detect-cfw.ts` and the README's "576 carrier lift" describe the pre-revision-8 EvenHub-carrier protocol and are stale.
2. **All-or-nothing messages.** One malformed or unresolvable call rejects the whole DRAW_CALLS message (validation pass precedes drawing). With Faceclaw's go-back-N this becomes 3 replays and then a reconnect. Validate on the phone (Faceclaw's `DisplayListRenderer` is a usable validator).
3. **Font text:** every byte in an op-5 string must be 1..31 (pen adjust `b−11`) or 32..127 **with a glyph present**; 0 or > 127 or a missing glyph invalidates the call. Glyph advance is the glyph *image width*, not a metric — position glyphs with adjust bytes.
4. **Op 3 (stock font)** needs stock's 20 px font root (SRAM `0x20077A54`) to be non-null, uses stock kerning/fallback chains; fallback glyphs with shifted baselines render differently from naive phone predictions (Faceclaw keeps several Unicode ranges baked, `TS/graphics/evenhub-font.ts:77-96`).
5. **Root lists present only what they draw.** A root without `RECT_COPY SCREEN` shows stale composition content. With a depth shift, `CLEAR` first so the uncovered edge is not stale.
6. **Evicting a resource used by the current root** makes the next PRESENT fail validation → clear the root (`1b ff ff`) before evictions, set it again after (Faceclaw does this).
7. **Evictions are a barrier**: never have an eviction in flight together with other CFW messages (a replayed older message could reference a reused id) (`KT/g2protocol/CfwMessageWindow.kt:163-168`).
8. **Uploads**: one chunk per id per message; first chunk at offset 0; chunks contiguous; re-sent chunks must be byte-identical; a different resource under a live id must be evicted first; capacity is checked for the whole batch.
9. **Lease semantics**: fire-and-forget on sid 0x09, per arm, 90 s. A *fresh* acquire (after expiry) frees the resource cache and shows stock content until the next PRESENT; a *renewal* does not. Lease loss frees the cache but not the screen/composition buffers.
10. **Frozen frames**: while the lease is valid, stock UI (including its own "Connection lost" messages) is invisible; if the phone dies the last frame persists ≤ 90 s.
11. **Lens bit numbering** in the CFW transport is 1 = left, 2 = right, the reverse of the stock side id (1 = right, 2 = left).
12. **ACK matching** uses the *decoded* size and CRC; accept the redundant history entries of later ACKs as ACKs too, or a single lost notification stalls the window until timeout.
13. **Compression context**: every compressed record must end in `00 00 FF FF`; a lens refuses all records after a failure until one carries RESET_CONTEXT; the deflater must be reset whenever you re-send or change the lens mask (Faceclaw resets on lens-mask change, `KT/g2protocol/CfwTransport.kt:32`).
14. **Size limits**: decoded message ≤ 65,535 B, compressed body ≤ 65,535 B; ≤ 4096 calls per walk; nesting ≤ 8; expressions ≤ 1024 B / 32 stack; resources ≤ 65,536 B; 512 ids; 192 KiB total with 16 B/block overhead.
15. **Compact bbox units**: x and w are in units of 4 px, y and h in units of 2 px, each stored in one byte. `rect-in-target` is checked before the depth shift.
16. **Odd widths**: packed-nibble RLE (with pad nibbles) ≠ exact-pixel RLE; draw ops always want exact-pixel RLE.
17. **Rounded-rect fill is max-blend** (it can only brighten), border overwrites, `CLEAR` ignores depth, REMAP parity uses the *shifted* x.
18. **Brightness wake freeze**: after a `visible=1` request the display does not change until a *newer* PRESENT arrives (older pending frames are dropped). Always follow a wake brightness message with a PRESENT; `visible=0` ends in a black framebuffer while frames keep staging.
19. **Colour key**: shell/overlay surfaces use grey 0 as transparent; intentional black must be grey 1; dithering must not emit 0 (Faceclaw writes 1).
20. **Quantisation must be identical** on every path (compositor, glyph bakes, atlases): `min(15,(v+8)>>4)`; the texture planner's "draw lands correctly" checks compare exact levels.
21. **Stock EvenHub**: container names ≤ 14 chars; a second CREATE for the same name is not ACKed; first image stream after CREATE is dropped; do not interleave message fragments on one arm; keep magic < 256.
22. **Legacy helpers with bugs, do not copy**: `BmpUtil.build4bppBmpFromPacked` writes `'B', 0x04` instead of `'B','M'` (`KT/util/BmpUtil.kt:96-97`); `BleProtocol.buildImageRawData` puts the container id into field 3 (MapSessionId) (`KT/g2protocol/BleProtocol.kt:282-286`); the non-CFW branch of `enqueueDesiredImageLocked` still sends retired optimizer records over SID 0xF0 (`KT/g2protocol/session/GlassesSessionSend.kt:748-796`). None of these are used with Faceclaw/34.
23. **g2-kit doc/code mismatches**: envelope header drawing, request flag (0x00 vs 0x20), CRC byte order, service UUIDs, "no bus between arms", raw vs BMP image data. Prefer `KIT/ble/*.ts` code and Faceclaw.
24. **Heap pressure**: each lens needs ~150 KB + 150 KB + 192 KB plus per-message buffers; allocation failures NACK and latch the `ALLOC` overlay flag.
25. **Keep-alive**: CFW messages reset the EvenHub keep-alive counter only while they flow; an idle UI must send EvenHub heartbeats or the page is torn down (inputs stop).
26. **Idempotency**: go-back-N replays re-execute messages; avoid non-idempotent screen-buffer operations in DRAW_CALLS (e.g. `RECT_COPY CURRENT` scrolling the screen onto itself would scroll twice). Faceclaw no longer emits such calls. (Inference.)
27. **DRAW_CALLS stop animations**: any 21/22/26/27/29 message stops the root-list animation timer until the next PRESENT.
28. **Resource-cache size**: comments say 256 KiB; the code (and the phone model) use 192 KiB.

---

## 7. Open questions / uncertainties

1. **Placement of the stock 576×288 area** inside the 640×480 panel (centred at (32,96)? offset by the per-lens X/Y calibration settings, `KIT/ble/docs/settings.md:70-76`?) is not in the sources. Consequently it is unknown whether the user's lens X/Y calibration is applied to the CFW direct path (the CFW copies the full 640×480 buffer verbatim, so probably not).
2. **Physical refresh rate / latency** of the panel and the timeout of the display semaphore wait (`FUN_0047a31e`) are unknown.
3. **Whether an EvenHub page is required** for the direct path to refresh the panel (e.g. to keep the display powered). Faceclaw always creates its input page before sending frames; do the same.
4. **Stock container size caps**: g2-kit uses 576×288 text/list containers and 288×144 image tiles; Faceclaw comments claim a "~280x130 container-size cap" lifted by the CFW (`KT/g2protocol/BleProtocol.kt:300-303`), yet current CFW patches contain no such lift and Faceclaw's own input page is 576×288. The true stock limits on 2.3.0.24 (especially the maximum image container) are unverified.
5. **"576 carrier lift"**: reconstructed from stale README/demo text and old capability tokens (§3.17); the patched check itself is not in the current sources.
6. **Stock CompressMode 1 (RLE)** format and whether `MapFragmentPacketSize > len(MapRawData)` (implied zero padding) is accepted by current stock are unknown.
7. **Throughput on 2.3.0.24**: the BLE policy was measured on 2.2.9 (≈ 41 KiB/s); the comment says 2.3.0 "needs hardware validation".
8. **Depth sign perception**: the math is exact (left shift `floor(d/2)`, right `−floor((d+1)/2)`), but which sign reads as "nearer" depends on the optics; Faceclaw uses +4 for menus, −2 for the top bar.
9. **Lease race across arms**: the right arm receives its lease write directly and CFW messages via the left arm's bridge; if the forwarded messages arrive before the right arm processed its lease, the right lens NACKs (Faceclaw's replay would then recover). Not observed/confirmed either way.
10. **Stock brightness range**: g2-kit says 0–100 (0 = dark), Faceclaw says stock clamps to 2–100; the CFW uses 2 as its "off" fade endpoint.
11. **Comment vs behaviour on suspend**: Faceclaw assumes `Cmd=9` suspend releases the FB lease/cache (`KT/g2protocol/session/GlassesSessionCore.kt:1187-1193`); nothing in the CFW code ties them together. Resetting the phone-side cache model is safe either way.
12. **Order of operations inside one lens across tasks**: sid-0x09 lease writes are processed by the settings task, SID-0xF0 packets on the BLE receive path; strict ordering between them is assumed, not proven.

---

## Appendix A — Constant summary

| Constant | Value | Source |
|---|---|---|
| Panel | 640×480, A4, stride 320, 153,600 B | `P/zlib_glue.c:229-232` |
| Stock area | 576×288 | `P/patch_compress.py:195-197` |
| CFW SID | 0xF0 | `P/message_transport.h:4` |
| Packet options | 1 L, 2 R, 0x40 END, 0x80 RESET | `P/message_transport.h:5-14` |
| Record flags | lens bits, 4 COMPRESSED, 8 RESET_CONTEXT | `P/message_transport.h:10-11` |
| Max message | 65,535 | `P/message_transport.h:12` |
| ACK kinds | 1 ACK, 3 NACK; history ≤ 3 × 7 B | `P/message_transport.h:8-16` |
| Phone window / ACK timeout / retries | 3 / 500 ms / 3 | `KT/g2protocol/ConnectionOptions.kt:57`; `CfwMessageWindow.kt:160-161` |
| Lease field / ops / duration / renew | settings field 101, `FC 01 op nonce16`, op 5 acquire / 6 release, 90 s / 45 s | `P/settings_ext.c:86-103`; `KT/.../GlassesSessionCore.kt:44` |
| Capability string | field 100 `"Faceclaw/34"` | `P/settings_ext.c:480` |
| Message types | 5 buzzer, 7 diag, 10 compass, 11 cleanup, 16 ALS, 17 ring battery, 21 upload, 22 evict, 23–25 panel, 26 draw, 27 root, 28 present, 29 surface, 30 brightness | `P/zlib_glue.c:22-170` |
| Draw ops | 1 bbox, 2 rect copy, 3 stock text, 4 image, 5 text, 6 remap, 7 list, 8 rounded rect, 9 clear | `P/display_list.c:12-22` |
| Call flags | 1 resource target, 2 depth | `P/display_list.c:25-29` |
| bbox flag | 1 = u16 coordinates | `P/display_list.c:31-33` |
| Special ids | 65535 SCREEN, 65534 CURRENT | `P/display_list.c:8-9` |
| Options | 0x0F top mask, 0x10 transparent, 0x20 inverse | `P/texture_draw.h:6-9` |
| Border sentinel | 16 = no border | `P/display_list.c:35` |
| Resource types / flags | 0 image, 1 font, 2 list; 4 LARGE, 8 RLE | `P/resource_cache.h:11-19` |
| Cache | 192 KiB, 512 ids, 2 KiB table, 16 B block header, 64 KiB max | `P/resource_cache.h:4-8,23-25` |
| Font header | 1 + 96×u16 = 193 B, chars 32..127 | `P/texture_draw.c:300-302` |
| Walk limits | 4096 calls, depth 8 | `P/display_list.c:6-7` |
| Expressions | ≤ 1024 B, stack 32, opcodes 1–5,16–23,32–38,48–50,64–71 | `P/draw_expression.c:4-22` |
| Animation tick | 45 ms | `P/zlib_glue.c:313` |
| Brightness | `[30][1][2..100][0/1][≤3000 ms]`, tick 40 ms | `P/brightness.c:39-54` |
| Quantisation | `min(15,(v+8)>>4)` | `KT/util/BmpUtil.kt:15-16` |
| Preview gamma | 0.7 | `TS/native/faceclaw-communicator.ts:7` |
| Stock image fragment | ≤ 4096 B (Faceclaw 3800) | `KIT/ble/image.ts:200-205`; `KT/g2protocol/ConnectionOptions.kt:34` |
| Stock heartbeat | `Cmd=12` every 4–5 s | `KT/g2protocol/StockLink.kt:79` |

## Appendix B — Where to look for executable references

* Firmware draw semantics: `P/display_list.c`, `P/texture_draw.c`, `P/draw_expression.c`, `P/resource_cache.c`, `P/zlib_glue.c`, `P/brightness.c`.
* Phone encoders: `KT/g2protocol/DrawProtocol.kt`, `DrawExpression.kt`, `ResourceCacheState.kt`, `CfwTransport.kt`, `ScenePlanner.kt`, `TexturePlanner.kt`, `BleImageOptimizer.kt` (RLE).
* Phone reference renderer: `KT/graphics/DisplayListRenderer.kt`, `DisplayListPlayer.kt`, `ShellScene.kt`.
* Golden test vectors: `TEST/DrawWireTest.kt`, `TEST/ProtocolTest.kt`, `TEST/ResourceCacheTest.kt`, `TEST/RleEncoderTest.kt`, `TEST/DisplayListTest.kt`, `TEST/DimDitherTest.kt`, `TEST/DrawExpressionTest.kt`.
* DimDither LUT algorithm (dimming by factor f/256): for level 1..15, target = cbrt(level·f/256); choose half-step k ∈ 1..2·level minimising |cbrt((⌈k/2⌉ + ⌊k/2⌋)/2) − target|; even-parity LUT = ⌈k/2⌉, odd = ⌊k/2⌋; level 0 → 0. Example f = 64: even `0 1 1 1 1 2 2 2 2 3 3 3 3 4 4 4`, odd `0 0 0 1 1 1 1 2 2 2 2 3 3 3 3 4` (`KT/graphics/DimDither.kt:124-150`; `TEST/DimDitherTest.kt:6-11`).
