# 06 — Firmware: stock image, CFW build, OTA flashing, detection, uninstall

Specification for an independent, wire-compatible and **safe** native Android (Kotlin)
re-implementation of Faceclaw's firmware subsystem. Everything below was derived by reading
the reference sources (read-only); no firmware was downloaded and no build/flash was run.

> **Bricking risk.** A wrong main-app size, load address, or MRAM overrun is **not** caught by
> the glasses' bootloader and can leave a lens in an SWD-only (non-BLE-recoverable) state
> (`g2flash/g2flash.py:64-70`, `:225-230`). Treat §3.4, §4.4, §7 and §11 as normative.

## Sources and conventions

| Prefix used below | Repository path | Revision / licence |
|---|---|---|
| `faceclaw/` | `/home/user/jimrandomh/faceclaw` | commit `a6291cf`, GPLv3 |
| `g2flash/` | `/home/user/jimrandomh/g2flash` | commit `814db36`, GPLv3 |
| `g2kit/` | `/home/user/refs/g2-kit-unofficial` | MIT |
| `KT/` | `faceclaw/native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/` | shared Kotlin core |
| `AND/` | `faceclaw/App_Resources/Android/src/main/java/com/faceclaw/app/` | Android glue |
| `ANDMAIN/` | `faceclaw/native/kotlin/shared/src/androidMain/kotlin/com/faceclaw/app/` | Android actuals |
| `UI/` | `faceclaw/app/phone-ui/` | NativeScript UI (TypeScript) |
| `KTEST/` | `faceclaw/tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/` | Kotlin tests |

* Integers are **little-endian** unless stated. Hex bytes are written lowercase, e.g. `aa 21`.
* "pb" = protobuf payload bytes. Protobuf field notation `f4.f12` = field 12 inside field 4.
* "MUST/SHOULD" = requirement for the re-implementation. "Faceclaw does …" / "g2flash does …"
  = observed reference behaviour. Where the two references differ, both are stated and a
  recommendation is given.
* Citations are `path:line` (or `path:first-last`).

---

## 0. Key facts at a glance

| Fact | Value | Source |
|---|---|---|
| Stock base version | **2.3.0.24** | `g2flash/build_cfw.sh:44`, `faceclaw/app/g2/firmware/cfw-patches.ts:20` |
| Stock image URL | `https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin` | `g2flash/build_cfw.sh:43`, `faceclaw/app/g2/firmware-builder.ts:32` |
| Stock SHA-256 | `187ccf2bcc5c17a212106e8a376745511e8289c4232b634a7ea94b9bf25a0979` | `g2flash/build_cfw.sh:49`, `cfw-patches.ts:21` |
| Stock size | 4,537,963 B (`0x453e6b`) | offset of the append op = EOF (`g2flash/patches/cfw_patches.json` op #31; `apply_patches.py:72-76`) |
| CFW (patched) SHA-256 | `7d8764f8b720252354dcd1695d23578b11f9b7cf5df9895d08b9f6668e9d0ee7` | `g2flash/build_cfw.sh:50`, `cfw-patches.ts:22` |
| CFW size | 4,607,691 B (stock + 69,728 appended) | derived from op #31 |
| Patch set | 38 ops: 37 in-place (2–4 B each), 1 append (69,728 B); no compression | `g2flash/patches/cfw_patches.json` |
| CFW revision string | **`Faceclaw/34`** in settings-response protobuf field 100 | `g2flash/patches/settings_ext.c:480`; also present at blob offset `0x10bbc` of the appended bytes |
| Revision the app requires | `>= 34` | `faceclaw/app/g2/firmware-compat.ts:29,102-105` |
| OTA service / write / notify | `00002760-08c2-11e1-9073-0e8ac72e1001` / `…e0001` / `…e0002` | `g2flash/g2flash.py:53-55`, `KT/BleProtocol.kt:19-21` |
| Control service / write / notify | `…e5450` / `…e5401` / `…e5402` | `g2flash/g2flash.py:56-58`, `KT/BleProtocol.kt:15-17` |
| Envelope | `aa 21 seq len tot idx sid flag <chunk>`; CRC-16/CCITT-FALSE (LE) of the whole pb appended before chunking; 232-byte chunks | `g2flash/g2flash.py:19-20,114-147`, `KT/BleProtocol.kt:209-248` |
| OTA opcodes (sid `0xc0`) | `00` BEGIN, `01`+128-B subheader FILE_CHECK, `02` block marker, `03` END; block data on sid `0xc1` | `g2flash/g2flash.py:21-25`, `KT/OtaFlashFlow.kt:34-40` |
| Block size | 4096 B payload (18 fragments) | `g2flash/g2flash.py:24,684-688`, `KT/OtaFlashFlow.kt:42` |
| Ack | notify on `…e0002`: pb `[opcode, status]`; status 0 = OK; END OK = {0, 8, 9}; 7 = CHECK_FAIL | `g2flash/g2flash.py:25,105-111`, `KT/OtaFlashFlow.kt:46-50` |
| Component checksum | CRC-32C **MSB-first, init 0, xorout 0** (poly `0x1edc6f41`) | `g2flash/g2flash.py:120-128`, `KT/FirmwareImage.kt:45-64` |
| Main-app preamble checksum | zlib CRC-32 over `payload[8:ps]`, stored at `payload+4` | `g2flash/g2flash.py:278-293` |
| MRAM ceiling | programmed end `0x438000 + ps − 0x20` MUST be ≤ `0x7f0000` | `g2flash/g2flash.py:71-76,259-269`, `KT/FirmwareImage.kt:28-30,159-165` |
| Lens order | left, then right; one at a time | `g2flash/g2flash.py:918`, `KT/OtaFlashFlow.kt:86-95` |
| Minimum battery (Faceclaw) | 30 % per arm | `UI/onboarding-flash-view-model.ts:31-33` |

---

## 1. Architecture overview

```
                        ┌──────────────────────── build (phone, no BLE) ───────────────────────┐
 Even CDN ──HTTPS GET──►│ stock EVENOTA 2.3.0.24 (4,537,963 B)                                  │
                        │   verify SHA-256 == 187ccf2b…0979  (else abort)                       │
                        │   extract EvenHub fonts → <filesDir>/evenhub-firmware-20.json         │
                        │   apply 38 byte-patch ops (offset / expected-old / new)               │
                        │   verify SHA-256 == 7d8764f8…0ee7  (else abort)                       │
                        │   write <filesDir>/g2_2.3.0.24_cfw.bin                                 │
                        └──────────────────────────────────────────────────────────────────────┘
                        ┌──────────────── flash prompt (stock-compatible BLE) ─────────────────┐
                        │ connect+auth right & left arm → Yes/No page on lens (right arm)      │
                        │ → user taps "Yes, flash" → read battery per arm (≥30 %)              │
                        └──────────────────────────────────────────────────────────────────────┘
                        ┌──────────────────────── OTA flash (per lens) ────────────────────────┐
                        │ LEFT : connect → subscribe → auth(sid 0x80) → BEGIN →                │
                        │        for each component: FILE_CHECK(subheader) → N×(marker+4 KiB)  │
                        │        → END (glasses verify CRC-32C) → disconnect; lenses reboot    │
                        │ RIGHT: same (connect window 120 s because of the reboot)             │
                        └──────────────────────────────────────────────────────────────────────┘
 After reboot: settings READ response (sid 0x09) carries field 100 = "Faceclaw/34".
 Uninstall = identical flow with the unpatched stock image (g2_2.3.0.24.bin).
```

Code map (Android path): TypeScript UI (`UI/onboarding-*.ts`) → TS wrappers
(`faceclaw/app/native/{device-info-probe,flash-prompt-communicator,firmware-flasher}.ts`) →
Android hosts (`AND/FaceclawDeviceInfoProbe.kt`, `AND/FaceclawFlashPromptCommunicator.kt`,
`AND/FaceclawFirmwareFlasher.kt`, each owning an `AND/AndroidStockLink.kt` over
`AND/FaceclawBleManager.kt`) → shared cores (`KT/DeviceInfoProbeFlow.kt`,
`KT/FlashPromptFlow.kt`, `KT/OtaFlashFlow.kt`, all on `KT/StockLinkSession.kt`) plus
`KT/FirmwareImage.kt` (container validation) and `KT/BleProtocol.kt` (framing/protobuf).
The image build is TypeScript (`faceclaw/app/g2/firmware-builder.ts`, `firmware-fonts.ts`,
`firmware/cfw-patches.ts`). Each flow owns its **own** GATT manager and runs on its own worker
thread; callbacks are posted to the main thread (`AND/FaceclawFirmwareFlasher.kt:57-87`).

---

## 2. Obtaining the stock image

### 2.1 Pinned image

| Item | Value | Source |
|---|---|---|
| Version | 2.3.0.24 | `g2flash/build_cfw.sh:7,44` |
| URL | `https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin` | `g2flash/build_cfw.sh:43`; `faceclaw/app/g2/firmware-builder.ts:32` |
| URL pattern | `https://cdn.evenreal.co/firmware/<32 lowercase hex>.bin`; "The CDN names firmware files by MD5" (unverified here — the hex is presumably the MD5 of the file). There is no version→URL lookup in the sources: the URL is pinned per patch-set base and must be updated together with it (`firmware-builder.ts:28-31`) | `firmware-builder.ts:28-32`; `g2flash/build_cfw.sh:43` |
| SHA-256 | `187ccf2bcc5c17a212106e8a376745511e8289c4232b634a7ea94b9bf25a0979` | `g2flash/build_cfw.sh:49`; `g2flash/patches/cfw_patches.json` `base_sha256`; `cfw-patches.ts:21`; `g2flash/patches/patch_compress.py:541`; `g2flash/patches/stock_abi_230.json:3` |
| Local file name | `g2_2.3.0.24.bin` (Faceclaw derives it from `CFW_PATCH_SET.base`) | `g2flash/build_cfw.sh:44`; `firmware-builder.ts:35` |
| CFW file name | `g2_2.3.0.24_cfw.bin` (`base` with `.bin` → `_cfw.bin`) | `g2flash/build_cfw.sh:45`; `firmware-builder.ts:36` |
| Previous base (history only) | 2.2.9.22, CDN name beginning `fc250b05…` (truncated in source); `old_base_sha256 = a03fbea9f68a9de6bc271daabb9f3a41c59053d1086622c76a4e990f829cc561` (presumed to be 2.2.9.22) | `firmware-builder.ts:31`; `stock_abi_230.json:4` |

The firmware is Even Realities' property and is **never redistributed**: both g2flash and
Faceclaw download it at build time and patch locally (`g2flash/README.md:13-16`,
`g2flash/build_cfw.sh` header; `firmware-fonts.ts:6-7` for the same reasoning about fonts).

### 2.2 How Faceclaw downloads and stores it

`downloadAndVerifyBase()` (`firmware-builder.ts:153-165`) →
`downloadFirmware()` (`:167-178`):

1. `fetchWithUserAgent(FIRMWARE_URL)` — a plain `GET`, header `User-Agent: Faceclaw/<appVersion>`
   (`faceclaw/app/util/http.ts:15-17,33-34`, `faceclaw/app/version.ts:2`). No range requests,
   no explicit timeout, no retries, whole body read into memory (`response.arrayBuffer()`).
2. Transport error → `FirmwareBuildError("Could not reach Even's firmware CDN: …")`
   (`:170-173`); non-2xx → `"Firmware download failed (HTTP <status>)."` (`:174-176`).
3. SHA-256 (Android: `java.security.MessageDigest` via `ANDMAIN/FaceclawFirmwareUtil.kt:12-16`,
   called from `faceclaw/app/native/firmware-files.ts:2`). Mismatch →
   `"Downloaded stock firmware failed verification.\nexpected …\ngot …"` (`:157-163`).
4. **No cache.** Every install, uninstall and font repair re-downloads (no existence check in
   `buildCustomFirmware` `:90-125`, `buildStockFirmware` `:132-151`,
   `downloadAndExtractEvenHubFonts` `:70-83`).
5. Outputs go to NativeScript `knownFolders.documents()` (Android: the app's `filesDir`) and are
   overwritten every run: `g2_2.3.0.24_cfw.bin` (install, `:120-121`) or `g2_2.3.0.24.bin`
   (uninstall, `:146-147`), plus `evenhub-firmware-20.json` (`:180-200`,
   `firmware-fonts.ts:10`). The Android writer is a plain `FileOutputStream` —
   **not atomic** (`ANDMAIN/FaceclawFirmwareUtil.kt:19-30`); iOS writes atomically
   (`faceclaw/app/native/firmware-files.ios.ts:4-6`).
6. The OTA flasher later re-reads the file by path (`KT/OtaFlashFlow.kt:52-57,82`) and validates
   the container, but on Android does **not** re-check the SHA-256 (iOS does:
   `faceclaw/app/native/firmware-flasher.ios.ts:71-73`, accepting only the CFW or stock hash).

g2flash's `build_cfw.sh` caches instead: it reuses `g2_2.3.0.24.bin` when its SHA-256 already
matches, `--force-download` forces a fresh `curl -fL --retry 3` (`g2flash/build_cfw.sh:110,136-142`).

**Re-implementation requirements**

* MUST verify the downloaded bytes' SHA-256 against the pin before any other use.
* SHOULD download to a temp file and atomically rename; MAY cache the verified stock image keyed
  by its SHA-256 (re-verify on every use).
* MUST re-verify the SHA-256 of the exact bytes about to be streamed, immediately before
  flashing, and accept only the allow-listed hashes (stock base or CFW output) (§11).
* SHOULD apply a sane network timeout and surface HTTP errors verbatim.

---

## 3. EVENOTA container format

### 3.1 Overall layout

```
file off  ┌────────────────────────────────────────────┐
0x0000    │ magic "EVENOTA\0" (8 bytes)                │ checked only by firmware-fonts.ts:206
0x0008    │ u32 N = component count (5 or 6)           │ g2flash.py:179, FirmwareImage.kt:92
0x000C    │ 52 bytes, meaning unknown — preserve       │ never parsed by any implementation
0x0040    ├────────────────────────────────────────────┤
          │ TOC entry i (16 B) at 0x40 + 16·i:         │ g2flash.py:184, FirmwareImage.kt:98-101
          │   +0x0 u32 eid   (meaning unknown, unused) │
          │   +0x4 u32 off   (file offset of subheader)│
          │   +0x8 u32 size  (= ps + 128)              │ patch_compress.py:582
          │   +0xC u32 crc   (CRC-32C of payload)      │ g2flash.py:296-298
0x40+16N  ├────────────────────────────────────────────┤
          │ component 0: 128-B subheader + ps-B payload │ packed, NOT aligned
          │ component 1 …                              │ (2.3.0.24 main app starts at odd
          │ …                                          │  offset 0xbe36b)
          │ component N−1 = ota/s200_firmware_ota.bin  │ payload ends exactly at EOF
          └────────────────────────────────────────────┘
```

* Firmware 2.2.4 had 5 components; 2.2.6 had 6 (`KT/FirmwareImage.kt:24-26`).
  2.3.0.24 has 6: the main app is TOC index 5 (`cfw_patches.json` op descriptions "[5] …", and
  N ≤ 6 is enforced).
* **Only subheaders and payloads are transmitted to the glasses.** The 64-byte header and the
  TOC are phone-side metadata used for parsing and validation only (see §7: FILE_CHECK sends the
  subheader, blocks send the payload).
* The only component name known from the sources is `ota/s200_firmware_ota.bin` (the main
  application, "mainApp") (`g2flash/g2flash.py:62`). Names of the other five 2.3.0.24
  components are not recorded anywhere in the sources (see Open questions). g2flash mentions an
  "80-block codec component" on 2.2.9 (`g2flash/g2flash.py:338-340`).

### 3.2 Component subheader (128 bytes, streamed verbatim in FILE_CHECK)

| Offset | Size | Meaning | Source |
|---|---|---|---|
| `0x00` | 8 | unknown — preserve | — |
| `0x08` | 4 | `ps`: payload size in bytes | `g2flash/g2flash.py:188`; `KT/FirmwareImage.kt:106` |
| `0x0C` | 4 | CRC-32C of the payload (must equal the TOC `crc`) | `g2flash/g2flash.py:204-218`; `KT/FirmwareImage.kt:131-135` |
| `0x10` | 32 | unknown — preserve | — |
| `0x30` | 80 | component name/path, NUL-terminated ASCII (latin-1) | `g2flash/g2flash.py:189`; `KT/FirmwareImage.kt:21-22,110` |

The payload (`ps` bytes) immediately follows the subheader (`payloadStart = off + 128`,
`KT/FirmwareImage.kt:41-42`). The glasses verify the payload against the CRC-32C in this
subheader at END (status 7 = CHECK_FAIL on mismatch; `g2flash/g2flash.py:204-208`,
`g2flash/README.md:244-247`). A `PATH_ERR` (2) status exists, suggesting the name is validated
too (`g2kit/ble/gen/ota_transmit_pb.ts:28-31`) — never edit it.

### 3.3 Main-app payload preamble (first 32 bytes of `ota/s200_firmware_ota.bin`)

| Payload offset | Size | Meaning | Source |
|---|---|---|---|
| `0x00` | 4 | bits 0–23: length the bootloader erases/programs — MUST equal `ps`; bits 24–31: a flag byte, `0x04` in 2.3.0.24 (meaning unknown; preserve) | `g2flash/g2flash.py:233-236,246,252-258`; `patch_compress.py:584-586`; op #34 old/new `805a3904`→`e06a3a04` |
| `0x04` | 4 | CRC-32 (zlib) of `payload[0x08 : ps]` | `g2flash/g2flash.py:278-279,288-293` |
| `0x08`–`0x13` | 12 | unknown (covered by the CRC-32) | — |
| `0x14` | 4 | load address — MUST be `0x00438000` | `g2flash/g2flash.py:245-251`; `KT/FirmwareImage.kt:149-155` |
| `0x18`–`0x1F` | 8 | unknown | — |

The bootloader "XIP-programs" `payload[0x20:ps]` to internal MRAM starting at `0x00438000`, so
payload byte `k` lands at MRAM `0x438000 + k − 0x20` (`g2flash/patches/patch_compress.py:40-47,138-140`).
For 2.3.0.24 the file-offset ↔ MRAM relation is `file_off = mram_addr − 0x379bf5`
(`patch_compress.py:65`, `stock_abi_230.json:5`).

### 3.4 MRAM ceiling (brick guard)

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `APP_LOAD_ADDR` | `0x00438000` | main app programmed here | `g2flash/g2flash.py:71`; `KT/FirmwareImage.kt:28` |
| `APP_PREAMBLE` | `0x20` | not programmed | `g2flash/g2flash.py:74`; `KT/FirmwareImage.kt:30` |
| `OTA_FLAG_ADDR` | `0x007FE000` | OTA magic word (last 8 KB of MRAM) | `g2flash/g2flash.py:73` |
| `MRAM_END` | `0x00800000` | end of Apollo510b 4 MB MRAM | `g2flash/g2flash.py:72` |
| `APP_MAX_END` | `0x007F0000` | conservative ceiling leaving ~56 KB for BLE-bond/KV NV + flag | `g2flash/g2flash.py:75-76`; `KT/FirmwareImage.kt:29` |

Rule: `prog_end = 0x438000 + ps − 0x20` MUST be `≤ 0x7F0000` (`g2flash/g2flash.py:259-269`;
`KT/FirmwareImage.kt:159-165`; duplicated at generation time in `patch_compress.py:416-429`).
Rationale: the bootloader takes destination and length verbatim from the preamble and runs
unbounded erase+program loops; running into the OTA flag clobbers the flag and the bond/KV NV
band, running past MRAM end faults mid-erase and leaves the lens in a permanent,
BLE-unrecoverable bootloop (`g2flash/g2flash.py:64-70,225-230`). A consequence: BLE bonds live
in that NV band, so a correct flash does **not** require re-pairing.

| Image | `ps` | `prog_end` | headroom to `0x7F0000` | blocks (4096 B) | last block |
|---|---|---|---|---|---|
| stock 2.3.0.24 main app | 3,758,720 (`0x395a80`) | `0x7cda60` | 140,704 B | 918 | 2,688 B |
| CFW main app | 3,828,448 (`0x3a6ae0`) | `0x7deac0` | 70,976 B | 935 | 2,784 B |

(Derived from ops #32/#34; `patch_compress.py:45-47` confirms `0x007cda60`.)

### 3.5 Checksum algorithms (exact)

**Component checksum — "CRC-32C MSB-first"** (`g2flash/g2flash.py:120-128`,
`KT/FirmwareImage.kt:45-64`, `g2flash/patches/patch_compress.py:515-525`):

| Parameter | Value |
|---|---|
| width | 32 |
| poly | `0x1EDC6F41` (Castagnoli), **non-reflected** (MSB-first table) |
| init | `0x00000000` |
| refin / refout | false / false |
| xorout | `0x00000000` |
| check `"123456789"` | `0xC052A8C8` |
| bytes `00 01 … ff` | `0xD7B91914` |
| empty input | `0x00000000` |

Test vectors from `KTEST/FirmwareImageTest.kt:56-64` (independently re-verified).
This is **not** the common iSCSI CRC-32C (reflected, init/xorout `0xFFFFFFFF`,
check `0xE3069283`) — a library CRC32C will silently produce wrong values.
Table-driven form: `t[b] = b<<24` shifted 8× with `(c<<1) ^ 0x1edc6f41` when the top bit is set;
`crc = (crc << 8) ^ t[((crc >>> 24) ^ byte) & 0xff]`. Coverage: the `ps` payload bytes only (not the
subheader). Stored as u32 LE at TOC `+0x0C` **and** subheader `+0x0C`.

**Main-app preamble checksum — zlib CRC-32** (`g2flash/g2flash.py:278-279,288-293`,
`patch_compress.py:598-600`): CRC-32/ISO-HDLC — poly `0x04C11DB7` reflected (`0xEDB88320`),
init `0xFFFFFFFF`, refin/refout true, xorout `0xFFFFFFFF`, check `0xCBF43926`
(`java.util.zip.CRC32` on Android). Coverage: `payload[8 : ps]` (everything after the CRC field,
including the rest of the preamble and all code; the length word at `+0` and the CRC at `+4` are
excluded). Stored as u32 LE at `payload+4`.

**Recompute order** (after any length-preserving or length-changing edit;
`g2flash/g2flash.py:275-317`, `patch_compress.py:590-603`):
1. If the edit changed the main-app size: update subheader `ps` (`off+8`), TOC `size`
   (`0x40+16i+8` = ps+128) and preamble low-24 length (preserve the top byte)
   (`patch_compress.py:580-588`). The main app must be the last component so no other offsets move
   (`patch_compress.py:572-574`).
2. Main app only: preamble CRC-32 over `payload[8:ps]` → write to `payload+4`.
3. For every component: CRC-32C over the payload → write to TOC `+0xC` and subheader `+0xC`.

`g2flash.py --recompute-checksums IMAGE` performs steps 2–3 in place, atomically
(temp file + `os.replace`), printing each changed field (`g2flash/g2flash.py:275-317,891-896`;
`g2flash/README.md:244-247`). It does **not** fix sizes (step 1). Faceclaw has no recompute
function — `KT/FirmwareImage.kt` only validates; the embedded patch set already carries the
recomputed values (ops #32–#37).

### 3.6 Validation rules

| Check | g2flash `validate_firmware` | Faceclaw `FirmwareImage.validate` | TS `firmware-fonts.ts` | Re-impl |
|---|---|---|---|---|
| file ≥ 0x40 B | yes (`g2flash.py:177`) | yes (`FirmwareImage.kt:91`) | yes (`:206`) | MUST |
| magic `EVENOTA\0` | no | no | yes (`:206`) | MUST |
| 0 < N ≤ 64 | yes (`:179-181`) | yes (`:92-93`) | yes (`:213`) | MUST |
| N ∈ {5, 6} | yes (`:198-200`) | yes (`:126-128`) | no | MUST |
| TOC inside file | implicit | yes (`:95-96`) | per entry | MUST |
| subheader off ≥ TOC end, inside file | partial (`:186-187`) | yes (`:102-104`) | yes (`:217`) | MUST |
| `ps > 0`, payload inside file | no (CRC catches) | yes (`:106-109`) | yes (`:221`) | MUST |
| components don't overlap | no | yes (`:112-116`) | no | MUST |
| TOC `size == ps + 128` | no | no | no | SHOULD (true for 2.3.0.24, ops #32/#33) |
| TOC crc == subheader crc == computed CRC-32C | yes (`:209-218`) | yes (`:130-135`) | no | MUST |
| exactly one `ota/s200_firmware_ota.bin` | "present" (`:201-203`) | present + not duplicated (`:136-141`) | first match | MUST |
| preamble load addr `0x438000` | yes (`:245-251`) | yes (`:149-155`) | no | MUST |
| preamble length == ps | yes (`:252-258`) | yes (`:150,156-158`) | no | MUST |
| preamble CRC-32 correct | **no** | **no** | no | MUST (cheap; bootloader behaviour on mismatch unknown) |
| MRAM ceiling | yes (`:259-269`) | yes (`:159-165`) | no | MUST |
| SHA-256 in allow-list | no (operator responsibility) | no on Android (iOS yes) | n/a | MUST |

### 3.7 Known values in 2.3.0.24 (for self-tests)

| Field | File offset | Stock value | CFW value |
|---|---|---|---|
| TOC[5].size | `0x98` | `0x00395b00` | `0x003a6b60` |
| TOC[5].crc | `0x9c` | `0x2a32c6c0` | `0x833dd6f8` |
| mainApp subheader offset | TOC[5].off = `0xbe36b` | — | unchanged |
| subheader.ps | `0xbe373` | `0x00395a80` (3,758,720) | `0x003a6ae0` (3,828,448) |
| subheader.crc | `0xbe377` | `0x2a32c6c0` | `0x833dd6f8` |
| payload start | `0xbe3eb` | — | — |
| preamble word 0 | `0xbe3eb` | `0x04395a80` | `0x043a6ae0` |
| preamble CRC-32 | `0xbe3ef` | `0xeabcfdef` | `0x8891f238` |
| EOF | — | `0x453e6b` | `0x464ecb` |

(All from `g2flash/patches/cfw_patches.json` ops #31–#37, decoded as u32 LE.) Components 0–4 are
byte-identical in stock and CFW; every in-place code edit lies inside the main-app payload.

---

## 4. The CFW patch set

### 4.1 Format (`g2flash/patches/cfw_patches.json`)

JSON object (`apply_patches.py:16-31`, written by `gen_patches.py:42-49` with `indent=2`):

```json
{
  "base": "g2_2.3.0.24.bin",
  "base_sha256": "187ccf2b…0979",
  "output_sha256": "7d8764f8…0ee7",
  "patches": [
    {"offset": 1045797, "old": "38b584b0", "new": "5df3a9b8", "desc": "ring receiver entry: …"},
    …
    {"offset": 4537963, "old": "", "new": "<139,456 hex chars>", "desc": "append injected blobs to main-app payload"},
    …
  ]
}
```

* `offset`: decimal file offset. `old`: expected current bytes (lowercase hex; empty = append).
  `new`: replacement bytes (lowercase hex). `desc`: free text.
* In-place ops always have `len(old) == len(new)` (checked for all 37).
* No op types, no compression, no relocations — the JSON is a literal byte diff.
  (`patch_compress.py` is **not** a patch compressor: it is the generator whose CFW features
  include image compression; `g2flash/README.md:152-154`.)
* File: 145,807 B, SHA-256 `81c5148d5a8f6db9c7b45624b267de38821a2aead0d45d7c0e724d0a0124e5f2`.
  Appended blob: 69,728 B, SHA-256 `c391327292c743888b6d77ca5a4208ac7271451e574c70c537e2f599f4c5f84d`
  (computed here; not pinned by the sources).

### 4.2 Faceclaw's embedded copy (`faceclaw/app/g2/firmware/cfw-patches.ts`)

* 146,282 bytes, 253 lines, UTF-8 TypeScript; header "AUTO-GENERATED from
  g2flash/patches/cfw_patches.json — do not edit by hand" (`cfw-patches.ts:1-3`).
* Exports types `FirmwarePatchOp {offset, old, new, desc?}` and
  `FirmwarePatchSet {base, baseSha256, outputSha256, patches}` (`:5-17`) and the constant
  `CFW_PATCH_SET` (`:19-253`) — i.e. the same data with keys renamed to camelCase
  (`base_sha256`→`baseSha256`, `output_sha256`→`outputSha256`).
* Generated by `faceclaw/scripts/update_deltas.sh`: runs `build_cfw.sh --skip-venv` in the sibling
  g2flash checkout (`:32-36`), maps keys (`:50-55`), `json.dumps(indent=2, ensure_ascii=False)`
  (`:64`), writes atomically only when changed (`:91-116`).
* Verified here: the TS object is **identical** to the JSON (base, both hashes, all 38 ops).
* Re-implementation: ship the JSON (GPLv3, same licence as the rebuild) as an app asset or a
  Kotlin resource; pin its SHA-256 at build time.

### 4.3 Op inventory (38 ops, in list order)

MRAM = `offset + 0x379bf5` for bytes inside the main-app code (for cross-reference with
`patch_compress.py` site constants). Bytes shown for ≤4-byte ops.

| # | File offset | MRAM | Len | Old | New | Description (from JSON) |
|---|---|---|---|---|---|---|
| 0 | 0x0ff525 (1045797) | 0x47911a | 4 | `38b584b0` | `5df3a9b8` | ring receiver entry: timestamped unfiltered reports under framebuffer lease |
| 1 | 0x15f0e9 (1437929) | 0x4d8cde | 2 | `15e0` | `fae7` | route ANCS write completions through relay event hook |
| 2 | 0x15f0e3 (1437923) | 0x4d8cd8 | 4 | `fef788ff` | `fdf2b8fd` | cfw_ancs_event |
| 3 | 0x15f0ff (1437951) | 0x4d8cf4 | 4 | `fef7e8fb` | `fdf2f2fa` | cfw_ancs_open |
| 4 | 0x15f111 (1437969) | 0x4d8d06 | 4 | `fef7e7fb` | `fdf209fb` | cfw_ancs_close |
| 5 | 0x152f63 (1388387) | 0x4ccb58 | 4 | `39694d00` | `59637d00` | connection-bound ANCS EUS write callback |
| 6 | 0x0ed2e9 (971497) | 0x466ede | 4 | `fff717f8` | `6ff3d9f9` | bl faceclaw_input_bridge_received (…) |
| 7 | 0x0ed445 (971845) | 0x46703a | 4 | `fef769ff` | `6ff32bf9` | bl faceclaw_input_bridge_received (…) |
| 8 | 0x0ef27b (979579) | 0x468e70 | 4 | `fdf74ef8` | `6df310fa` | bl faceclaw_input_bridge_received (…) |
| 9 | 0x15cd9f (1428895) | 0x4d6994 | 4 | `f8f76cfd` | `fcf228fa` | bl cfw_receive_packet (private SID-f0 probe before TPL reassembly) |
| 10 | 0x14ff47 (1376071) | 0x4c9b3c | 2 | `7c20` | `7d20` | Set Local Feature: enable LE 2M bit 8 |
| 11 | 0x442fab (4468651) | 0x7bcba0 | 4 | `0c001800` | `06000600` | fast connection interval min=max=7.5 ms; latency remains 0 |
| 12 | 0x1021bf (1057215) | 0x47bdb4 | 2 | `0500` | `a325` | _connectParamReq_impl: force requested mode to fast (0xa3) |
| 13 | 0x113ef3 (1130227) | 0x48dae8 | 4 | `5ff43432` | `5ff43332` | reserve final 1 KiB of primary TLSF arena for CFW context anchor |
| 14 | 0x130ce7 (1248487) | 0x4aa8dc | 4 | `d4f712fb` | `2af3f2fa` | bl settings_send_wrapper (append caps field 100) |
| 15 | 0x1303e7 (1246183) | 0x4a9fdc | 4 | `f5f7e2f8` | `2af376fc` | bl settings_decode_wrapper (Faceclaw lease field 101) |
| 16 | 0x0ee173 (975219) | 0x467d68 | 4 | `02f083fc` | `6cf3e2fc` | bl faceclaw_display_start (fail-open wake takeover) |
| 17 | 0x0ee233 (975411) | 0x467e28 | 4 | `02f023fc` | `6cf346fd` | bl faceclaw_display_start_headup (fail-open wake takeover) |
| 18 | 0x0ee0ff (975103) | 0x467cf4 | 4 | `07f085fb` | `6ef3e2f9` | bl headup_gate |
| 19 | 0x0cac55 (830549) | 0x44484a | 4 | `19f03efb` | `91f391fb` | bl gesture_ring_press_mode |
| 20 | 0x0cad4b (830795) | 0x444940 | 4 | `1ff071ff` | `91f34afb` | bl gesture_press |
| 21 | 0x0cada7 (830887) | 0x44499c | 4 | `1ff043ff` | `91f344fb` | bl gesture_short_long |
| 22 | 0x0cb113 (831763) | 0x444d08 | 4 | `1ff08dfd` | `91f37af9` | bl gesture_release |
| 23 | 0x17f5a1 (1570209) | 0x4f9196 | 4 | `7fb50600` | `dbf283be` | even_ai_display_ctrl entry → conditional lease trampoline |
| 24 | 0x100b99 (1051545) | 0x47a78e | 4 | `f6f787f9` | `55f311f9` | bl display_copy_hook (640x480 direct framebuffer) |
| 25 | 0x100cd5 (1051861) | 0x47a8ca | 4 | `f6f7e9f8` | `55f373f8` | bl display_copy_hook (640x480 direct framebuffer) |
| 26 | 0x13429d (1262237) | 0x4ade92 | 4 | `d9f7d0fd` | `26f333fb` | bl faceclaw_send_wear_event (outside onboarding) |
| 27 | 0x134301 (1262337) | 0x4adef6 | 4 | `d9f79efd` | `26f301fb` | bl faceclaw_send_wear_event (outside onboarding) |
| 28 | 0x13fadd (1309405) | 0x4b96d2 | 4 | `67f02dff` | `1af30bfc` | bl compass_decode_capture |
| 29 | 0x13f4d9 (1307865) | 0x4b90ce | 4 | `fff7c9fb` | `1af389ff` | bl compass_report_event |
| 30 | 0x13d9c9 (1300937) | 0x4b75be | 4 | `00210170` | `1cf36ffc` | bl compass_preserve_accuracy |
| 31 | 0x453e6b (4537963) | 0x7cda60 | 69728 | (empty) | 69,728 B | append injected blobs to main-app payload |
| 32 | 0x0be373 (779123) | — | 4 | `805a3900` | `e06a3a00` | main-app subheader payload size (ps) |
| 33 | 0x000098 (152) | — | 4 | `005b3900` | `606b3a00` | main-app TOC entry size (ps + 128) |
| 34 | 0x0be3eb (779243) | — | 4 | `805a3904` | `e06a3a04` | main-app preamble length (low 24 bits) |
| 35 | 0x0be3ef (779247) | — | 4 | `effdbcea` | `38f29188` | [5] ota/s200_firmware_ota.bin preamble crc32 |
| 36 | 0x00009c (156) | — | 4 | `c0c6322a` | `f8d63d83` | [5] ota/s200_firmware_ota.bin component crc32c (TOC) |
| 37 | 0x0be377 (779127) | — | 4 | `c0c6322a` | `f8d63d83` | [5] ota/s200_firmware_ota.bin component crc32c (subheader) |

Notes: ops are **not** sorted by offset (#1 > #2; fix-ups #32–#37 come after the append and
touch the header/TOC). Spans were checked: no two ops overlap. Ops #10–#12 change the lens's BLE
radio policy (LE 2M, 7.5 ms interval, always-fast) — relevant for later reconnections, not for the
OTA path itself (`patch_compress.py:80-91`). The g2flash README states the CFW makes **no**
changes to how OTA updates are installed (`g2flash/README.md:99-101`).

### 4.4 Apply algorithm (normative; mirrors `apply_patches.py:44-78` and `firmware-builder.ts:209-251`)

```
input : base bytes B, spec S
1. require sha256(B) == S.base_sha256                      # apply_patches.py:95-104
2. buf = copy(B)
3. for i, op in enumerate(S.patches):                      # IN LIST ORDER
     old = hex(op.old or ""); new = hex(op.new)            # strict hex (even length, [0-9a-fA-F]) — firmware-builder.ts:254-259
     if old non-empty:
        cur = buf[op.offset : op.offset+len(old)]
        if cur == new and cur != old: continue             # already applied (idempotency)
        if cur != old: FAIL "patch #i @off: expected old, found cur"
        buf[op.offset : op.offset+len(new)] = new
     else:                                                 # append
        end = op.offset + len(new)
        if op.offset <= len(buf) and end <= len(buf) and buf[op.offset:end] == new: continue
        if op.offset != len(buf): FAIL "append expects offset == current length"
        buf += new
4. require sha256(buf) == S.output_sha256                  # apply_patches.py:112-119; firmware-builder.ts:110-117
5. persist buf
```

Re-implementation notes:
* Because step 1 pins the whole base, the idempotency branches can never trigger on the happy path;
  keep them only for compatibility, never as a substitute for step 1.
* Out-of-range offsets must fail cleanly (a JS `subarray` silently truncates; use bounds checks).
* Step 4 is the only proof that the output equals the reviewed image. Never flash on mismatch.
* After step 4, also run the full §3.6 validation on the output (defence in depth).

### 4.5 Faceclaw on-phone build pipeline

`buildCustomFirmware(onProgress)` (`firmware-builder.ts:90-125`):

| Step | Progress phase | Failure message (FirmwareBuildError) |
|---|---|---|
| download | `downloading` | "Could not reach Even's firmware CDN: …" / "Firmware download failed (HTTP n)." |
| verify base | `verifying-base` | "Downloaded stock firmware failed verification.\nexpected …\ngot …" |
| extract fonts | `extracting-fonts` | "The firmware was verified, but its EvenHub fonts could not be extracted: …" (`:180-200`) — **aborts the build** |
| patch | `patching {applied,total}` (per op) | "patch #i @ 0x…: unexpected bytes in stock image …" (`:228-233`) |
| verify output | `verifying-output` | "Patched firmware failed verification.\nexpected …\ngot …" (`:113-117`) |
| write | `writing` | native write error |
| done | `done {path, bytes}` | returns `{path, bytes, sha256}` |

`buildStockFirmware` (uninstall; `:132-151`) = download → verify base → write `g2_2.3.0.24.bin`
(no fonts, no patches) and returns `sha256 = baseSha256`.
`downloadAndExtractEvenHubFonts` (`:70-83`) = download → verify → fonts only (used when the CFW is
already installed but the phone-side fonts are missing).
Progress callbacks are wrapped in try/catch so UI errors can't break the build (`:93-99`).

### 4.6 Fonts: extracted for the phone, **not** injected into the glasses

`faceclaw/app/g2/firmware-fonts.ts` reads three LVGL fonts out of the **verified stock** image
(before patching) and saves them as JSON for phone-side rendering of EvenHub text
("their redistribution terms are unclear", `:1-8`). Nothing is written to the glasses.

* Output file `evenhub-firmware-20.json` (`:10`), schema
  `{lineHeight, baseline, glyphs: {"<codepoint>": [boxW, boxH, ofsX, ofsY, bitmapOffset, bitmapLength]}, bitmapBase64}`
  (`:45-50,81-110`); valid only with ≥ 700 glyphs (`:53-58,77-79`).
* Main app located by name, payload mapped at `0x438000` after skipping the 32-byte preamble (`:62-73`).
* Font descriptors (MRAM addresses, **2.3.0.24 only**): `0x007b699c` (lineHeight 27, baseLine 5),
  `0x007b69b0` (26, 5), `0x007b68c0` (20, 2); earlier bases listed at `:28-30`; first font wins per
  code point (`:31-35,133-134`). JSON `lineHeight = 27`, `baseline = 27 − 5 = 22` (`:104-107`).
* Descriptor: `+0` bitmap ptr, `+4` glyph-dsc ptr, `+8` cmap ptr, `+18` u16 packed
  (`cmapCount = bits 0–8`, `bpp = bits 9–12` must be 4, `format = bits 14–15` must be 3)
  (`:114-126`). Glyph dsc = 16 B: `+0` u32 bitmap index, `+8` u16 box_w, `+10` u16 box_h,
  `+12` i16 ofs_x, `+14` i16 ofs_y (`:135-141`). Cmap = 20 B: `+0` u32 range_start,
  `+4` u16 range_length, `+6` u16 glyph_id_start, `+8` u32 unicode_list, `+12` u32 glyph_id_ofs,
  `+16` u16 list_length, `+18` u8 type (0,1,2,3 handled per `:152-191`). Row stride
  `floor(box_w/2)+1` bytes (4 bpp with a padding byte for even widths, `:193-197`).
* Because a font failure aborts `buildCustomFirmware`, a re-implementation SHOULD decouple font
  extraction from flashing (fonts are not needed to flash) but keep it tied to the same verified
  stock bytes.

### 4.7 How the patch set is produced (context only — not needed on the phone)

`gen_patches.py` → `patch_compress.build_patch_ops` compiles position-independent Thumb C
(`patches/*.c`, via `build.py`/clang `thumbv7em`) into one blob, appends it 4-byte aligned at the end
of the main-app payload, retargets `bl` call sites, fixes sizes and checksums, and records only the
bytes that changed (`g2flash/patches/gen_patches.py:1-47`, `patch_compress.py:40-60,527-603`).
It refuses any base other than the audited SHA-256 (`patch_compress.py:541-542`,
`audit_stock.py:33-45`). `build_cfw.sh` always applies the committed JSON; when clang is available it
merely checks reproducibility (`g2flash/build_cfw.sh:145-176`). A different clang produces a
different JSON — hence the JSON, not the compiler output, is the pinned artifact.

---

## 5. CFW revision and firmware detection

### 5.1 What the CFW advertises

`settings_send_wrapper` intercepts the one call that frames the sid-`0x09` settings responder's
reply and appends protobuf **field 100, wire type 2 (string)** with the revision string before the
envelope CRC is computed (`g2flash/patches/settings_ext.c:4-31,476-488`):

* Current value: **`"Faceclaw/34"`** (`settings_ext.c:480`; also found verbatim in the committed
  appended blob). Encoded as `a2 06 0b 46 61 63 65 63 6c 61 77 2f 33 34` (key 800|2 = varint `a2 06`).
* The CFW also appends fields 103/104 (mic control/status) and 106 (ring battery,
  `'R','B',1,flags,level`) to the same response (`settings_ext.c:68-75,484-485`;
  `g2flash/patches/ring_battery.c:20-33`). Parsers MUST skip unknown fields.
* Appends are all-or-nothing per field against a 256-byte buffer
  (`g2flash/patches/protobuf.c:26-50`, `settings_ext.c:474`).
* The CFW reports the **same software version strings as its stock base** ("2.3.0.24"); only field
  100 distinguishes CFW from stock (`g2flash/README.md:73-77`).
* `<n>` is bumped whenever the firmware contract changes and is independent of app versions
  (`g2flash/README.md:76-79`, `settings_ext.c:14-21`); history in `settings_ext.c:429-471` and
  `:478-479` (e.g. 17 = rebase to 2.3.0.24; 33 = dithered color remap; 34 = brightness setter fix).
  Older builds sent `"EVENCFW/<ver> <feature tokens>"` (`settings_ext.c:18-21`).
* Stock firmware never sends field 100 ("Tag 100 is far above the stock message's fields
  (1..19), so stock decoders and the phone bridge skip it as an unknown field", `settings_ext.c:23-24`).
* Third parties "should use a different prefix, and assume … only compatible if you recognize the
  exact string" (`g2flash/README.md:80-83`).

### 5.2 Settings READ (sid `0x09`) — request/response

Request (`KT/BleProtocol.kt:508-517`; schema `g2kit/ble/gen/g2_setting_pb.ts:542-576,197-291`):

```
G2SettingPackage {
  f1 commandId   = 2  (DeviceReceiveRequest)
  f2 magicRandom = magic
  f4 deviceReceiveRequestFromApp { f1 settingInfoType = 1 (APP_REQUIRE_BASIC_SETTING) }
}
bytes: 08 02 10 <magic varint> 22 02 08 01
```

Envelope: control write char `…5401`, sid `0x09`, flag `0x20` (`FLAG_REQUEST`, `KT/BleProtocol.kt:131`).
Faceclaw allocates magics 100..255 in probe/prompt sessions (`KT/StockLinkSession.kt:17-18,76-81`).

Response (on `…5402`, sid `0x09`, same magic in f2): `f4` carries `f5 leftSoftwareVersion`
(string), `f6 rightSoftwareVersion` (string), `f12 battery` (uint, %), `f13 chargingStatus`,
`f14 silentModeSwitchRestored`, …; the CFW adds top-level `f100` (and 103/104/106).
Faceclaw extracts `(left, right, extension)` in `parseSettingsFirmwareInfo` (`KT/BleProtocol.kt:798-811`)
and battery in `parseSettingsBattery` (`:747-760`); both strip the trailing 2-byte CRC first
(`:1020-1025`). Returns null if f4 is absent or everything is empty.

Faceclaw accepts **any** sid-`0x09` frame that carries firmware versions (even with a device-chosen
magic, i.e. an unsolicited push) as an answer (`KT/StockLinkSession.kt:374-378`,
`KT/DeviceInfoProbeFlow.kt:198-204`).

### 5.3 Classification (`faceclaw/app/g2/firmware-compat.ts`)

Constants: `REQUIRED_FACECLAW_FIRMWARE_VERSION = 34` (`:29`); prefixes `"Faceclaw/"`, `"EVENCFW"`
(`:31-32`); `BASE_STOCK_VERSION = [2,3,0,24]`, `VALIDATED_STOCK_VERSION = [2,3,0,24]` (`:37-40`).

`parseFirmwareExtension(s)` (`:52-62`), on `s.trim()`:

| Input | Result |
|---|---|
| empty | `none` (stock) |
| starts with `Faceclaw/` and `parseInt` of the rest is finite ≥ 0 | `faceclaw{version}` (note: `parseInt("34abc") = 34`) |
| starts with `Faceclaw/` otherwise | `other{text}` |
| starts with `EVENCFW` | `legacy-faceclaw{text}` (always outdated) |
| anything else | `other{text}` (custom firmware from another source) |

`hasCompatibleFirmware` = `faceclaw` and `version ≥ 34` (`:102-105`) — **newer revisions are
accepted** ("revisions only add to the contract", `:98-101`).

`classifyOnboardingFirmware(info)` (`:179-210`), with `version` = the higher of the two reported
dotted versions, missing parts = 0, non-numeric parts = 0 (`:78-96,148-154`):

| Condition (in order) | Kind |
|---|---|
| `faceclaw` and version ≥ 34 | `custom` |
| `faceclaw` and version < 34, or `legacy-faceclaw` | `older-faceclaw` |
| `other` | `other-custom` |
| no version reported | `unknown` |
| stock version ≤ 2.3.0.24 | `flashable-stock` |
| stock version ≤ VALIDATED (2.3.0.24) | `newer-stock-validated` (currently **unreachable**: BASE == VALIDATED) |
| otherwise | `newer-stock-unvalidated` |

Tests pin these rules, e.g. 2.2.4.34, 2.2.10.10, 2.3.0.1 → `flashable-stock`; left 2.3.0.24 +
right 2.3.0.25 → `newer-stock-unvalidated`; `Faceclaw/34` on base 2.3.0.1 → `custom`
(`faceclaw/tests/firmware-compat.test.cjs:59-71`).

"Validated stock firmware" = a stock release newer than the patch base on which flashing the CFW
(a downgrade) has been tested; none exists today. `firmwareIncompatibilityMessage`
(`:112-145`) produces user text for the main screen; it returns null when nothing was reported.

### 5.4 Where detection runs

1. **Onboarding probe** (`KT/DeviceInfoProbeFlow.kt`), §5.5.
2. **Normal session**: on the first session of the app the main communicator queues a settings
   query (`KT/session/GlassesSessionCore.kt:1706-1716`), parses field 100 from the ack
   (`KT/session/GlassesSessionSend.kt:948-951`) and emits `onFirmwareInfo`; the TS controller shows
   a warning and schedules a disconnect + suppresses auto-reconnect if incompatible
   (`faceclaw/app/g2/dashboard-controller.ts:1484-1511,1615-1631`).

### 5.5 Device-info probe flow (`KT/DeviceInfoProbeFlow.kt`)

Session: magic 100..255, seq starts `0x40`, frame tracing on (`:28`, `KT/StockLinkSession.kt:17-19`).

1. Require a right address (`:40-43`); left optional and ignored if equal to right (`:45`).
2. `bringUpArm(right)`, then `bringUpArm(left)` — strictly sequential, because Android pairs one
   device at a time (`:47-57`). Each: up to `ARM_ATTEMPTS = 3` (`:22`); before a retry wait while
   the OS is BONDING (cap 90 s) then 1 s (`:126-135`); state `connecting` → `bringUp`
   (connect, priority, MTU, discover, subscribe `…5402`) → state `authenticating` →
   security auth (§6.5). SUCCESS → true; UNCONFIRMED → false (tolerated); LINK_DROPPED → retry
   (`:136-160`). All attempts failed → error "could not connect to the <arm> lens … if the phone
   showed a Bluetooth pairing request, accept it and try again" (`:165-168`).
3. If the right link dropped while the left paired, re-bring-up the right (`:62-71`).
4. `queryArm(right)`: state `querying`; session prelude (§6.4; throws if unacked); up to 2 settings
   reads with 4 s timeout, falling back to a captured unsolicited push (`:177-209`).
5. If the right never answered, repeat `queryArm(left)` (observed silent right lens on 2.2.9,
   `:81-91`).
6. Parse and report `(left, right, extension)` (`:103-108`) or error (`:96-101`); always close the
   link (`:111-113`).

Mixed-lens caveat: the extension comes from whichever arm answered. After a partial flash
(left = CFW, right = stock) the right arm answers without field 100 → "flashable-stock" →
reflash is offered (the safe outcome). A right = CFW / left = stock pair would read as `custom`
(only possible with a non-Faceclaw flasher order).

---

## 6. BLE transport used by the stock-firmware flows

### 6.1 GATT layout

| Role | UUID | Handle (g2flash notes) | Used for | Source |
|---|---|---|---|---|
| OTA service | `00002760-08c2-11e1-9073-0e8ac72e1001` | `0x082x` | firmware data | `g2flash/g2flash.py:53` |
| OTA write | `00002760-08c2-11e1-9073-0e8ac72e0001` | `0x0822` | c0/c1 frames, write-without-response | `g2flash/g2flash.py:54`; `KT/BleProtocol.kt:19` |
| OTA notify | `00002760-08c2-11e1-9073-0e8ac72e0002` | `0x0824` | OTA acks | `g2flash/g2flash.py:55`; `KT/BleProtocol.kt:21` |
| Control service | `00002760-08c2-11e1-9073-0e8ac72e5450` | `0x084x` | EvenHub/settings/auth | `g2flash/g2flash.py:56` |
| Control write | `00002760-08c2-11e1-9073-0e8ac72e5401` | `0x0842` | sid 0x01/0x09/0x80/0xe0 | `g2flash/g2flash.py:57`; `KT/BleProtocol.kt:15`; `g2kit/ble/ble.ts:24` |
| Control notify | `00002760-08c2-11e1-9073-0e8ac72e5402` | `0x0844` | acks/events | `g2flash/g2flash.py:58`; `KT/BleProtocol.kt:17`; `g2kit/ble/ble.ts:25` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | — | enable notify (`01 00`) | `KT/BleProtocol.kt:173`; `AND/FaceclawBleManager.kt:274-284` |

Faceclaw's Android code looks characteristics up by UUID across all discovered services
(`AND/FaceclawBleManager.kt:427-436`), so it never names the service UUIDs. Each arm is a separate
peripheral advertising `Even G2_<serial>_<L|R>_<last-3-MAC-bytes>` (`g2flash/g2flash.py:434-436`).

> **Source conflict:** `g2kit/ble/docs/transport.md:22-40` claims the command channel is
> `6E40FFF0…`/`fff1`/`fff2`; `g2kit/ble/docs/envelope.md:8-44` describes a different header
> (`aa 21 LL LL …`, 4-byte magic, CRC over the header). Both contradict g2-kit's **own code**
> (`g2kit/ble/ble.ts:24-29`, `g2kit/ble/envelope.ts:1-90`) and Faceclaw/g2flash. Follow the code.

### 6.2 Envelope framing

```
TX (phone→glasses):  aa 21 SEQ LEN TOT IDX SID FLAG | chunk (LEN bytes, ≤ 232)
RX (glasses→phone):  aa 12 SEQ LEN TOT IDX SID FLAG | chunk
body   = pb ‖ crc16(pb) as 2 bytes little-endian
chunks = body split into 232-byte pieces; TOT = max(1, ceil(len(body)/232)) ≤ 255; IDX = 1..TOT
```

| Byte | Meaning |
|---|---|
| 0–1 | `aa 21` (TX) / `aa 12` (RX) |
| 2 | SEQ: transport message id — **identical for every fragment of one message** (a group key, not a fragment counter) |
| 3 | LEN: payload bytes in this frame |
| 4 | TOT: total fragments |
| 5 | IDX: 1-based fragment index |
| 6 | SID: service id (`0x01` app/prelude, `0x09` settings, `0x80` dev-config/auth, `0xe0` EvenHub, `0xc0` OTA control, `0xc1` OTA data) |
| 7 | FLAG: `0x20` request (settings/prelude/EvenHub), `0x00` for auth and OTA, RX `0x01`/`0x06` = async notify |

CRC-16/CCITT-FALSE: poly `0x1021`, init `0xFFFF`, refin/refout false, xorout `0x0000`,
check `"123456789"` = `0x29B1`; computed over the **concatenated pb only** (not the header);
appended little-endian; therefore present only at the end of the last fragment
(`g2flash/g2flash.py:19-20,114-119,139-147`; `KT/BleProtocol.kt:209-248,910-927`;
`g2kit/ble/crc.ts:1-19`, `g2kit/ble/envelope.ts:3-7`).
g2flash splits `pb‖crc` into 232-byte chunks (`g2flash/g2flash.py:143-146`, "validated byte-for-byte
vs capture" `:113`), as does Faceclaw (`KT/BleProtocol.kt:216-246`; chunk = min(232, maxWrite−8)).
g2-kit instead chunks the pb and appends the CRC to the last chunk (`g2kit/ble/envelope.ts:52-90`);
the results differ only when `len(pb) mod 232 ≥ 231`. Use the g2flash form.

Frames are up to 240 bytes, so the ATT MTU must be ≥ 243. RX parsing: a notification value can hold
several back-to-back frames (Faceclaw splits by LEN, `KT/BleProtocol.kt:938-958`); `parseFrame`
requires ≥ 10 bytes and `aa 21|12` (`:961-1017`). Neither reference verifies the CRC of received
OTA acks.

Magic (protobuf field 2) correlates requests and replies and must stay < 256
(`g2kit/ble/docs/envelope.md:73-85`); keep it < 128 wherever it is hand-encoded as one byte.

### 6.3 Android GATT behaviour (Faceclaw)

* `connectGatt(context, autoConnect=false, cb, TRANSPORT_LE, PHY_LE_2M | PHY_LE_1M)`, 5 s timeout;
  an existing client for the address is reused (`AND/FaceclawBleManager.kt:130-182`,
  `KT/ConnectionOptions.kt:10`).
* `prepareLink`: `requestConnectionPriority(HIGH)` then `requestMtu(512)` (5 s); the MTU result is
  **ignored** (`AND/AndroidStockLink.kt:28-31`, `KT/ConnectionOptions.kt:8`).
* `discoverServices` 5 s; `enableNotifications` = `setCharacteristicNotification` + CCCD write,
  5 s (`AND/FaceclawBleManager.kt:236-299`, `KT/ConnectionOptions.kt:11-12`).
* All writes are `WRITE_TYPE_NO_RESPONSE` (`KT/ConnectionOptions.kt:7`,
  `ANDMAIN/g2protocol/AndroidProtocolPlatform.kt:40-44`), **one frame at a time, waiting for each
  `onCharacteristicWrite` callback** (2 s) before the next — this is the flow control
  (`AND/FaceclawBleManager.kt:301-382`, `KT/ConnectionOptions.kt:15`).
* `ERROR_GATT_WRITE_REQUEST_BUSY`, other start failures and non-success callbacks are retried with
  delays `1,1,1,2,4,8,12,20,35,100,200` ms, then the write fails (`AND/FaceclawBleManager.kt:29,350-398`).
* All BluetoothGatt calls are serialized by one process-wide lock (`AND/FaceclawBleManager.kt:447-451`).
* Writes on a disconnected address throw `IllegalStateException("Not connected: …")` (`:419-425`);
  disconnect callbacks close the GATT (`:466-477`).

### 6.4 Session prelude (probe and prompt only; the OTA flow does not send it)

`sendPrelude` (`KT/StockLinkSession.kt:178-190`): sid `0x01`, flag `0x20`, magic 156, 2 s timeout;
pb `08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00` (`KT/BleProtocol.kt:29-31,175-205`);
must be acked (reply keyed by sid `0x01` + magic 156).

### 6.5 Security authentication handshake (control channel)

Required before OTA BEGIN: stock 2.2.9 closes an unauthenticated GATT link ~30 s after connect,
even while OTA data flows, and gates query responses on it (`g2flash/g2flash.py:28-31,151-161`;
`KT/BleProtocol.kt:33-38`; `KT/OtaFlashFlow.kt:8-13`; `g2flash/README.md:272-275`).

Request — `DevCfgDataPackage` (`g2kit/ble/gen/dev_config_protocol_pb.ts:50-60,162-164`) on
`…5401`, **sid `0x80`, flag `0x00`**:

```
f1 commandId = 4 (AUTHENTICATION)
f2 magicRandom = magic
f3 authMgr { f1 = 1 (varint), f2 phoneType = 4 (PHONE_ANDROID) }
bytes: 08 04 10 <magic> 1a 04 08 01 10 04
```

(`g2flash/g2flash.py:159-161`; `KT/BleProtocol.kt:460-483`; `PHONE_ANDROID = 4` in
`g2kit/ble/gen/dev_pair_manager_pb.ts:219-221`.) The bytes are copied from a stock-app 2.2.8
capture (`g2flash/g2flash.py:155-157`); g2-kit's schema declares `AuthMgr.f1` as `bytes secAuth`
(`dev_pair_manager_pb.ts:172-174`) — send the captured varint form anyway. It carries no key
material; it arms a flag, and the firmware reports success only once the BLE link is **encrypted**
(bonded) (`KT/BleProtocol.kt:460-466`).

Success reply on `…5402`, sid `0x80`, not flagged as notify: pb (CRC stripped) exactly
`08 04 10 <magic> 1a 00` — command 4, same magic, **empty** field 3 (`g2flash/g2flash.py:633-638`;
`KT/BleProtocol.kt:485-505`). A GATT write completing is not success.

| Aspect | g2flash | Faceclaw (`KT/StockLinkSession.kt:208-312`) |
|---|---|---|
| magic | = the envelope SEQ (first message → 1) | own range: OTA session `0x60..0x7f` (`KT/OtaFlashFlow.kt:63-65`), probe/prompt 100..255 |
| wait | 4 s | 30 s (`securityAuthTimeoutMs`), extended to ≥ 6 s after a fresh bond, hard cap 90 s while the OS reports BONDING |
| non-success reply | raises "authentication rejected" | ignored ("expected before the link is encrypted"; `:401-406`), keep waiting |
| resend | never | on failed write (≤ 3 sends, 1 s apart) and once 2 s after a fresh bond (`:246-262`) |
| during OS pairing | n/a (assumes bonded) | no writes while BONDING (`:230-239`) |
| link drop | timeout | `LINK_DROPPED` (`:259-260,272-278`) |
| async sid-0x80 notifies (flag `0x01`/`0x06`) | not filtered | routed as events, never treated as the reply (`:379-384`) |

On an unbonded phone the auth write is what triggers SMP pairing (possibly with an OS dialog;
`KT/OtaFlashFlow.kt:299-303`). The OTA flow requires `SUCCESS` (`:302-304`); the probe tolerates
`UNCONFIRMED` (`KT/DeviceInfoProbeFlow.kt:116-121,153-160`).

---

## 7. OTA flash protocol

### 7.1 Messages (all on OTA write `…e0001`, flag `0x00`)

| Name (g2flash) | Proto name (`g2kit/ble/gen/ota_transmit_pb.ts:83-108`) | SID | pb | Expected ack |
|---|---|---|---|---|
| BEGIN | `OTA_TRANSMIT_START` (0) | `0xc0` | `00` | op `00` |
| FILE_CHECK | `OTA_TRANSMIT_INFORMATION` (1) | `0xc0` | `01` ‖ 128-byte subheader (verbatim from the image) | op `01`, status MUST be 0 |
| block marker | `OTA_TRANSMIT_FILE` (2) | `0xc0` | `02` | — (acked with the data) |
| block data | — | `0xc1` | up to 4096 payload bytes | op `02` |
| END | `OTA_TRANSMIT_RESULT_CHECK` (3) | `0xc0` | `03` | op `03`, status ∈ {0, 8, 9} |
| (unused) | `OTA_TRANSMIT_NOTIFY` (4) | — | — | not used by either flasher |

(`g2flash/g2flash.py:21-25,148-149,680,708,775`; `KT/OtaFlashFlow.kt:34-40,188-192,224,228-244`.)

* A block is **two envelope messages sharing one SEQ**: the 1-byte marker message on sid `0xc0` and
  the data message on sid `0xc1`, each with its own CRC-16 and its own TOT/IDX
  (`g2flash/g2flash.py:139-141,647-665`; `KT/OtaFlashFlow.kt:227-234`; test
  `KTEST/StockFlowsTest.kt:359-363`). They MUST be written back-to-back with nothing interleaved
  on the OTA characteristic (`g2flash/g2flash.py:163-171` write lock).
* A 4096-byte block = 4098-byte body = 18 fragments: 17 × 232 + 1 × 154 bytes (the last carries
  152 data bytes + CRC). The final block of a component is `ps mod 4096` bytes, sent **unpadded**
  (`g2flash/g2flash.py:688`; `KT/OtaFlashFlow.kt:202-204`).
* FILE_CHECK is a single fragment (131-byte body, 139-byte frame).
* SEQ numbering for the OTA stream restarts at 1 after authentication on every (re)connection and
  increments per message (marker+data count once; NAK resends and retries take fresh SEQs); it wraps
  `0xff → 0x00` (`g2flash/g2flash.py:133-138,763-765`; `KT/OtaFlashFlow.kt:112-115`,
  `KT/StockLinkSession.kt:83-92`). With ~935 main-app blocks the counter wraps several times per lens,
  including the value 0.
* The OTA path has **no block index and no de-duplication**: a marker resets the receive offset,
  data fragments accumulate by arrival, and the flash writer advances one block per accepted block.
  Re-sending a block the device already accepted double-advances the offset and corrupts the rest of
  the component (END → 7 CHECK_FAIL). There is also no committed-offset query, and stock 2.2.9's
  BLE-close handler resets the OTA session ("conn close reset"), so there is no resume
  (`g2flash/g2flash.py:83-93`).
* No other traffic on the control channel between BEGIN and the final END: the official-app capture
  shows none, heartbeats do not satisfy the 2.2.9 deadline, OTA data itself refreshes the transfer
  watchdog, and a concurrent writer would interleave with marker/data transactions
  (`g2flash/g2flash.py:32-34,769-771`; `KT/OtaFlashFlow.kt:8-13`).

### 7.2 Acks

Notifications on `…e0002` (`aa 12` frames); pb = `[opcode, status]` followed by the CRC-16
(`g2flash/g2flash.py:25,606-611`; `KT/StockLinkSession.kt:329-336`). Match on **opcode only**;
neither implementation checks the ack's SID/FLAG/SEQ (Faceclaw's test fake uses sid `0xc1`,
`KTEST/StockFlowsTest.kt:51-53`, but the real value is not documented). Always drain the ack queue
immediately before sending each request (`g2flash/g2flash.py:657,774`; `KT/OtaFlashFlow.kt:230,238`)
and ignore acks with another opcode (`KT/OtaFlashFlow.kt:257-260`).

Status codes (`eOTATransmitRsp`, `g2kit/ble/gen/ota_transmit_pb.ts:17-72`; `g2flash/g2flash.py:105-111`):

| Code | Name | Meaning in practice |
|---|---|---|
| 0 | SUCCESS | OK (block accepted / FILE_CHECK accepted) |
| 1 | HEADER_ERR | NAK |
| 2 | PATH_ERR | NAK |
| 3 | CRC_ERR | NAK (a dropped data fragment is documented to produce *a* NAK, `g2flash/g2flash.py:653,661-663`; which code is not recorded) |
| 4 | TIMEOUT | NAK |
| 5 | NO_RESOURCES | NAK |
| 6 | FLASH_WRITE_ERR | NAK |
| 7 | CHECK_FAIL | END: bytes in flash ≠ component CRC-32C |
| 8 | UPDATING | END: **normal success** ("what every component actually returns on a good flash") |
| 9 | SYS_RESTART | END: success |
| 10 | FAIL | NAK |

Interpretation: BEGIN — any status accepted, non-{0,8,9} only logged (`g2flash/g2flash.py:775-777`;
`KT/OtaFlashFlow.kt:118-121`); FILE_CHECK — must be 0; block — 0 = accepted, anything else = explicit
NAK; END — {0,8,9} = component verified.

### 7.3 Per-lens sequence (normative)

Preconditions: §11 checklist passed; image validated (§3.6) and SHA-256 re-verified; user approved;
battery OK; no other client connected to the glasses.

| Step | Action | Faceclaw | g2flash |
|---|---|---|---|
| L1 | Drop any existing link to this lens | fresh GATT manager per flow | `tp.disconnect()` (`g2flash.py:747`) |
| L2 | Connect (5 s) + priority HIGH + MTU 512 + discover (5 s) | `KT/StockLinkSession.kt:95-102` | bleak scan ≤ 20 s then connect (`g2flash.py:535-561`) |
| L3 | Subscribe notifications | `…5402` then `…e0002` (`:103-108`) | `…e0002` then `…5402`, then sleep 2.5 s, drain (`g2flash.py:756-760`) |
| L4 | Security auth on control channel (§6.5) | required SUCCESS (`KT/OtaFlashFlow.kt:292-306`) | 4 s, exact reply (`g2flash.py:761`) |
| L5 | Settle | sleep 2.5 s after auth (`notifySettleMs`, `KT/OtaFlashFlow.kt:111`) | (before auth, L3) |
| L6 | Clear OTA acks; OTA SEQ := 1 | `KT/OtaFlashFlow.kt:112-115` | `_reset_seq()`, drain (`g2flash.py:764-765,774`) |
| L7 | BEGIN, wait ack op 0 (8 s); timeout = lens failure | `KT/OtaFlashFlow.kt:118-121` | `g2flash.py:775-777` |
| L8 | For each component in **TOC order**: §7.4 with retries (§7.5) | `:130-136` | `g2flash.py:808-811` |
| L9 | Disconnect; the lens reboots into the new image | `:137-138` | `tp.close()` (`g2flash.py:937-938`) |

Lens connection windows (Faceclaw, `KT/OtaFlashFlow.kt:267-290`; `KT/StockLink.kt:85-89`): repeat
L2–L4 until success or window expiry — 30 s for the first (left) lens, **120 s** for the second
(right) lens; 2.5 s between attempts (`otaRetryDelayMs`), disconnecting first. The window is only
checked between attempts (one auth wait can itself last 30–90 s).

### 7.4 Per-component procedure (one attempt)

```
sub  = image[off : off+128]; payload = image[off+128 : off+128+ps]
clear acks; send c0 [01]+sub; st = wait(op 1, 8 s)
if st != 0: fail attempt ("FILE_CHECK rejected status=st")
nb = ceil(ps / 4096)
for b in 0..nb-1:
    blk = payload[b*4096 : min((b+1)*4096, ps)]
    for t in 1..3:                                  # BLOCK_NAK_RETRIES = 3 → ≤ 3 sends total
        seq = next_seq(); clear acks
        write c0 [02] (seq) then c1 blk (seq), contiguous
        st = wait(op 2, 4 s)                        # TIMEOUT → fail attempt immediately,
                                                    #   NEVER resend this block in place
        if st == 0: break                           # accepted
        # explicit NAK: device did not advance → safe to resend in place
    else: fail attempt ("block b NAK'd 3x")
    progress(...)
clear acks; send c0 [03]; st = wait(op 3, 8 s)      # glasses verify CRC-32C here
return st                                           # {0,8,9} = component done
```

Sources: `g2flash/g2flash.py:647-708`; `KT/OtaFlashFlow.kt:178-263`.
Constants: `BLOCK_ACK_TIMEOUT = 4 s` (a healthy block acks in well under 1 s; a lost marker/last
fragment means no ack ever comes, and waiting longer only runs into the link supervision timeout —
`g2flash/g2flash.py:94-98`), control acks 8 s (`g2flash/g2flash.py:612`; `KT/StockLink.kt:83-84`).

### 7.5 Retry and recovery

Component level: up to `COMPONENT_RETRIES = 3` attempts (`g2flash/g2flash.py:100,710-741`;
`KT/OtaFlashFlow.kt:44,141-176`). Every retry restarts the component from FILE_CHECK; blocks already
sent in the failed attempt are resent **only** as part of a fresh FILE_CHECK stream.

| Failure | g2flash | Faceclaw (Kotlin) |
|---|---|---|
| explicit block NAK | resend in place, ≤ 3 sends | same |
| block ack timeout (ambiguous) | abandon attempt; **reconnect**: disconnect, wait 10 s, connect, discover, re-subscribe, 2.5 s, auth, SEQ := 1, fresh **BEGIN** (must be {0,8,9}); up to `RECONNECT_ATTEMPTS = 3`; then restart the component from FILE_CHECK (`g2flash.py:101-102,733-737,779-807`) | abandon attempt; drain, sleep 1.5 s, restart the component with FILE_CHECK **on the same link** — no reconnect, no new BEGIN (`KT/OtaFlashFlow.kt:158-173`) |
| END status ∉ {0,8,9} (e.g. 7) or FILE_CHECK ≠ 0 or NAK×3 | drain, sleep 1.5 s, retry component (`g2flash.py:738-740`) | same (`KT/OtaFlashFlow.kt:165-173`) |
| GATT link lost | covered by the timeout path | next write throws "Not connected" → counted as a failed attempt → after 3 attempts the lens fails |
| component fails 3× | lens fails; **continues with the next lens** (`g2flash.py:924-936`) | whole flash fails; right lens not attempted (`KT/OtaFlashFlow.kt:99-105`) |
| write returns false | n/a | ignored; detected only as an ack timeout (`KT/OtaFlashFlow.kt:246-247`) |

Recommended for the re-implementation (safest union of both):
1. Explicit NAK → resend the same block in place (≤ 3 sends).
2. Ack timeout, END failure, or link loss → never resend the ambiguous block; if the link is down
   (or on any timeout, to be safe) reconnect, re-subscribe, re-authenticate, reset SEQ to 1, send a
   fresh BEGIN, then restart the **failed component** from FILE_CHECK (g2flash's documented path,
   exercisable with its fault-injection hooks `g2flash/g2flash.py:42-50,647-671`; whether it was
   hardware-tested is not recorded). Use a fresh auth magic < 128 for the re-auth (see §12 #8). If
   the reconnect/BEGIN fails 3 times, fail the lens.
3. After a lens fails, stop (Faceclaw behaviour) and let the user retry the whole install; a manual
   retry restarts both lenses from component 0 (`UI/onboarding-flash-view-model.ts:524`) — re-sending
   already-verified components is known to be acceptable.
4. Keep the phone-side validation + END CRC check as the integrity backstop: any stream corruption
   shows up as END status 7, never as a silently bad image (§11).

### 7.6 Lens order, reboot, re-pairing

* Left lens first, then right, strictly one at a time (`g2flash/g2flash.py:918,924-938`;
  `KT/OtaFlashFlow.kt:15-17,86-95`; `g2flash/README.md:280-281`). The rationale for left-first is not
  documented.
* "Finishing either lens reboots BOTH lenses, so the second lens is briefly unreachable"
  (`KT/OtaFlashFlow.kt:15-17`): Faceclaw emits state `rebooting` ("Left lens done. Both lenses reboot
  briefly; reconnecting for the right lens."), sleeps 5 s (`rebootSettleMs`) and allows 120 s to reach
  the right lens (`:93-95`, `KT/StockLink.kt:86-87`).
* After all components of a lens verify, g2flash prints "glasses should reboot into the new firmware"
  (`g2flash/g2flash.py:812`). Between the two lens flashes the pair is mixed (left new, right old).
* No re-pairing is expected: bonds live in the MRAM NV band above the app (§3.4), and the right-lens
  reconnect uses the existing bond (the auth step would trigger pairing again if the bond were lost).
* Faceclaw performs no explicit post-flash verification in the flash flow; verification happens at
  the next normal connection (§5.4). A re-implementation SHOULD reconnect after the reboot and
  confirm field 100 == `Faceclaw/34` (or no field 100 after an uninstall).

### 7.7 Progress reporting

`onProgress(lens, componentIndex(1-based), componentCount, blockIndex(1-based), blockCount,
bytesSent, bytesTotal)` every 20 blocks and on the last block; `bytesTotal` = sum of all `ps`
(`KT/OtaFlashFlow.kt:122-129,218-221`; `faceclaw/app/g2/firmware-types.ts:3-12`;
`faceclaw/native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/callbacks/FaceclawFirmwareFlasherListener.kt:7-20`).
States: `validating` → `connecting(lens)` → `flashing(lens)` → `rebooting(msg)` → … → `done`
or `error(msg)` (`KT/OtaFlashFlow.kt:81-103`; `faceclaw/app/g2/firmware-types.ts:1`).
UI bar: `((lensIndex + bytesSent/bytesTotal) / 2) × 100`, left = 0, right = 1
(`UI/onboarding-flash-view-model.ts:492-501`). Completion text (both install and uninstall):
"Both lenses flashed. The glasses are rebooting into the custom firmware." (`KT/OtaFlashFlow.kt:98`).

### 7.8 Timing constants

| Constant | Faceclaw | g2flash |
|---|---|---|
| connect / discover / CCCD | 5 s each (`KT/ConnectionOptions.kt:10-12`) | bleak defaults; scan 20 s (`g2flash.py:437`) |
| per-frame write callback | 2 s (`KT/ConnectionOptions.kt:15`) | n/a |
| auth wait | 30 s (+bond extension, 90 s cap) (`KT/ConnectionOptions.kt:21`, `KT/StockLink.kt:72`) | 4 s (`g2flash.py:623`) |
| settle after CCCDs/auth | 2.5 s (`KT/StockLink.kt:88`) | 2.5 s (`g2flash.py:759`) |
| BEGIN / FILE_CHECK / END ack | 8 s (`KT/StockLink.kt:84`) | 8 s (`g2flash.py:612`) |
| block ack | 4 s (`KT/StockLink.kt:83`) | 4 s (`g2flash.py:94`) |
| block sends on NAK | 3 total (`KT/OtaFlashFlow.kt:43`) | 3 total (`g2flash.py:99`) |
| component attempts | 3 (`KT/OtaFlashFlow.kt:44`) | 3 (`g2flash.py:100`) |
| delay before component retry | 1.5 s (`KT/StockLink.kt:90`) | 1.5 s (`g2flash.py:740`) |
| reconnect attempts / settle | — | 3 / 10 s (`g2flash.py:101-102`) |
| lens connect window | 30 s left, 120 s right; 2.5 s between tries (`KT/StockLink.kt:85-89`) | — |
| inter-lens reboot settle | 5 s (`KT/StockLink.kt:87`) | — |
| device: unauthenticated-link kill | ~30 s (2.2.9) (`g2flash/g2flash.py:29-31`) | |
| device: OTA watchdog | "90-second OTA watchdog" (2.2.9), refreshed by OTA data (`g2flash/g2flash.py:32-34,337-340`) | |

### 7.9 g2flash CLI extras (reference tool behaviour)

`--lens left|right|both`, `--stop-before discover|heartbeat|file_check|flash|done` (dry-run gates;
`flash` stops after BEGIN + the first FILE_CHECK), `--component-retries`, `--block-nak-retries`,
`--reconnect-attempts`, `--reconnect-delay`, `--debug`, `--recompute-checksums`
(`g2flash/g2flash.py:78-80,857-896`; `g2flash/README.md:233-247`). Transports: `g2://local`
(bleak) and `g2://droidbridge` (HTTP/WebSocket GATT proxy, one reused HTTP connection because a
new TCP connection per fragment "approaches 2.2.9's 90-second OTA watchdog",
`g2flash/g2flash.py:320-393`). Fault injection via `G2_FAULT_COMPONENT/BLOCK/MODE` (`frag`/`ack`)
and `G2_LOSS_RATE` (`:42-50`). Warranty gate: must type `my warranty is void` unless
`--my-warranty-is-void` (`:839-854`).

---

## 8. Flash prompt flow (on-glasses confirmation + battery check)

### 8.1 Purpose

A minimal stock-compatible session (prelude, one text + one list container, heartbeats; no images)
that (a) connects and security-authenticates **both** arms up front so any OS pairing dialogs appear
before flashing, (b) shows a Yes/No confirmation on the lens so the wearer physically confirms, and
(c) reads each arm's battery (`KT/FlashPromptFlow.kt:5-22`). With `skipPrompt` it only does
connect + auth + battery (used when re-checking after a low-battery refusal).

### 8.2 Sequence (`KT/FlashPromptFlow.kt:87-182`)

1. `connecting` → bring up right arm (subscribe `…5402`), then left arm (if configured) (`:94-108`).
   Failure → `error("<msg>: right arm (<addr>)")` (`:184-191`).
2. `connected` → authenticate right, then left; each must reach SUCCESS, else error
   "could not authenticate with the <arm> arm (<addr>) — if the phone shows a Bluetooth pairing
   request, accept it and try again" (`:110-120,198-206`).
3. Prelude on the right arm (§6.4) (`:121`).
4. Unless `skipPrompt`: create the prompt page on the right arm (≤ 2 attempts, 3 s ack each,
   `:208-227`); start a heartbeat every 4 s (`:260-284`); state `prompting`; wait ≤ 120 s for a
   selection (`KT/StockLink.kt:80`). Outcomes: cancelled → `cancelled`; no answer → `timeout`
   ("No response from the glasses.") or `disconnected` ("Lost connection to the glasses.") if the right
   link dropped; then send EvenHub shutdown; declined → `onResult(false)`, `result("declined")`.
5. `battery` → settings read (§5.2) on **each** arm's own link, 2 attempts × 3 s; −1 if no answer
   (`:234-258`); `onBattery(right, left)`.
6. `onResult(true)`, `result("approved")`; tear down both links (`:171-174,299-305`).

Left-arm disconnects only lose the left battery reading (`:329-336`).

### 8.3 Prompt page encoding (EvenHub, control write, sid `0xe0`, flag `0x20`)

`buildCreatePromptPage` (`KT/BleProtocol.kt:299-332`) — `EvenHub{Cmd=0 CreateStartUpPage,
MagicRandom=magic, CreateMessage(f3)={…}}` (field names per `g2kit/ble/gen/EvenHub_pb.ts:20-30,452-472`):

```
f1 Cmd = 0, f2 MagicRandom = magic,
f3 CreateStartUpPageContainer {
  f1 ContainerTotalNum = 2
  f2 ListObject { f1 x=0, f2 y=150, f3 w=280, f4 h=120, f9 id=2, f10 name="flashmenu",
                  f11 ItemContainer { f1 ItemCount=2, f3 IsItemSelectBorderEn=1,
                                      f4 "No, cancel", f4 "Yes, flash" },
                  f12 IsEventCapture=1 }
  f3 TextObject { f1 x=0, f2 y=0, f3 w=280, f4 h=130, f9 id=1, f10 name="flashwarn",
                  f12 text=<warning> }
  f5 widgetId = 10000
}
```

Geometry stays within the stock ~280×130 container limit so it works before flashing
(`KT/BleProtocol.kt:300-305`). Item 0 = decline, item 1 = approve (`KT/FlashPromptFlow.kt:35-40`).
Warning texts (≈50-column grid): install "Flashing custom firmware will void your warranty. Continue?";
uninstall "Reinstalling the official firmware removes Faceclaw's custom features. Continue?"
(`UI/onboarding-flash-view-model.ts:83-88`).

Heartbeat: `EvenHub{Cmd=12, magic, f14 HeartPacketCmd{f1 Cnt=0}}` = `08 0c 10 <m> 72 02 08 00`
(`KT/BleProtocol.kt:294-297`). Shutdown: `EvenHub{Cmd=9, magic, f11 ShutDownCmd{f1 exitMode=0}}` =
`08 09 10 <m> 5a 02 08 00` (`:404-407`).

### 8.4 Selection event

Async frame (control notify, sid `0xe0`, flag `0x01` or `0x06`): pb `f13 DevEvent → f1 ListEvent
{f2 ContainerName, f3 CurrentSelectItemName, f4 CurrentSelectItemIndex, f5 EventType}`
(`KT/BleProtocol.kt:369-392`; `g2kit/ble/gen/EvenHub_pb.ts:865,983-993`). Only
`ContainerName == "flashmenu"` and `EventType == 0 (CLICK, default when absent)` count; approve when
index == 1 or the item name starts with "yes" (case-insensitive); the first decision wins
(`KT/FlashPromptFlow.kt:307-327`).

---

## 9. Uninstall and interaction with the official Even app

* **Uninstall = flash the unmodified stock 2.3.0.24 image** with exactly the same prompt, battery
  and OTA flow (`firmware-builder.ts:127-151`; `UI/onboarding-flash-view-model.ts:29,401`;
  `g2flash/README.md:104-106`). It restores 2.3.0.24 even if the glasses originally ran another stock
  version. No firmware check is performed before uninstalling.
* Entry points: main-page overflow item "Uninstall custom firmware" (`UI/main-page.xml:16`,
  `UI/main-view-model.ts:937-954`), which first disconnects the main session.
* After an uninstall Faceclaw stays in the manual-disconnected state (it would immediately flag stock
  firmware as incompatible) (`UI/onboarding-flash-view-model.ts:507-513`).
* The CFW does not change the OTA updater. Connecting the official Even app to glasses whose CFW base
  is older than Even's current release makes the app offer an OTA update; installing it **fully
  removes the CFW** (`g2flash/README.md:99-106`; onboarding copy "You can uninstall … by reconnecting
  the official Even Realities app", `UI/onboarding-view-model.ts:41`).
* The CFW is intended to stay backwards compatible with the official app (only reacts to private
  messages), but this is untested; EvenHub apps are especially likely to break
  (`g2flash/README.md:85-91`).
* If the glasses ran a stock version newer than 2.3.0.24, flashing the CFW is a downgrade; the check
  page warns that the Even app may need to upgrade them back first (`UI/onboarding-firmware-check-view-model.ts:258-266`).
* **Exclusive access:** the glasses accept one connected app/phone at a time; the Even app (Android
  package `com.even.sg`, `AND/FaceclawEvenAppDetector.kt:15`) must be disconnected first — see §10.4.

---

## 10. Onboarding UX around firmware

### 10.1 Screen chain

```
onboarding step 1 (splash) → step 2 "Before You Continue" (disclaimer; Agree / Back)
 → permissions page → step 3 "Custom Firmware Required" (Flash Firmware | Preview Only)
 → "Disconnect Other Apps" (onboarding-unpair) → pairing page (scan, pick pair, save addresses)
 → "Checking Firmware" (onboarding-firmware-check) → flash page (autoStart) → main page
```

(`UI/onboarding-view-model.ts:19-47,58-105`; `UI/pairing-view-model.ts:344-348`;
`UI/config-view-model.ts:237-240` for manual addresses.) "Preview Only" sets
`onboarding.previewOnly = true` and completes onboarding without touching glasses
(`UI/onboarding-view-model.ts:99-104`, `UI/onboarding-state.ts:3-5,38-44`).

**Warranty acknowledgement** (Faceclaw has no typed phrase): step 2 disclaimer — "If this software
somehow breaks your headset … not covered by the hardware's warranty … may void the hardware's
warranty anyways …" with an **Agree** button (`UI/onboarding-view-model.ts:29-36`); step 3 repeats
"Installing custom firmware is not covered by warranty" (`:41`); and the on-lens prompt "Flashing
custom firmware will void your warranty. Continue?" requires selecting "Yes, flash" (§8).
g2flash requires typing `my warranty is void` (`g2flash/g2flash.py:839-854`; `g2flash/README.md:115-119`).

### 10.2 Firmware check page (`UI/onboarding-firmware-check-view-model.ts`)

Phases `checking | fonts | custom | flashable | newer-validated | newer | error` (`:24`).

| Phase | Trigger | Headline / text (abridged) | Primary | Secondary |
|---|---|---|---|---|
| checking | page open / Retry | "Checking Firmware"; probe states → "Connecting to the right lens…", "Pairing with … Each lens pairs separately; if your phone asks to pair, tap Pair.", "Reading the firmware version…" (`:164-214`) | — | Back |
| custom | kind `custom`, fonts present | "Custom Firmware Detected" (`:325-338`) | Finish | hidden |
| fonts | kind `custom`, fonts missing | "Preparing G2 Fonts" — downloads stock to extract fonts, no reflash (`:286-307`) | — | hidden |
| flashable | kind `older-faceclaw`, `other-custom` or `flashable-stock` | "Firmware Update Available" / "Other Custom Firmware" / "Ready to Install" (`:235-257`) | Install Firmware | Back |
| newer-validated | kind `newer-stock-validated` | "Ready to Install" + downgrade note (`:258-266`) | Install Firmware | Back |
| newer | kind `newer-stock-unvalidated` | "Unrecognized Firmware" — "… Flashing may not work correctly and carries extra risk." (`:268-274`) | Proceed Anyway | Back |
| error | probe failure, or kind `unknown` | "Couldn't Check Firmware" — e.g. "Connected, but couldn't read a firmware version. Make sure the glasses are on and the Even app is disconnected, then retry." (`:276-282,360-366`) | Retry | Back |

Error-only escape hatches: **Install** (flash as if stock was detected) and **Skip** (treat as
compatible CFW) (`:148-160`; `UI/onboarding-firmware-check-page.xml:12-16`). Install/Proceed
navigates to the flash page with `{mode:"install", fromOnboarding:true, autoStart:true}` (`:342-350`).
Finish sets `previewOnly=false`, `onboarding.complete=true`, resumes auto-reconnect (`:352-358`).

### 10.3 Flash page (`UI/onboarding-flash-view-model.ts`)

Phases `intro | prompt | building | flashing | flashed | error` (`:27`); modes `install | uninstall`
(`:29`). Constructor suppresses the main page's auto-reconnect (`:62-65`, `faceclaw/app/g2/reconnect-policy.ts:1-26`).

1. **intro** (skipped with `autoStart`): "Connect & Confirm" (`:68-80,170-181`).
2. **prompt** — "Confirm On Your Glasses" (or "Checking Battery" when `skipPrompt`) (`:241-280`):
   BLE permissions; addresses from storage, else an 8 s scan that accepts only a serial-joined
   complete L/R pair (`:529-543`, `faceclaw/app/native/device-discovery-common.ts:5-37`); start the
   prompt flow (§8). Secondary button = Cancel (`:183-190,379-390`).
3. **battery rule** (`:31-33,337-377`): refuse if any *reported* arm < **30 %** → headline
   "Charge Your Glasses", Retry re-runs only the battery check (`skipPrompt: true`). Unknown readings
   are ignored; both unknown → log "battery level unknown; proceeding".
4. **building** — "Preparing Firmware": `buildCustomFirmware` or `buildStockFirmware` with progress
   texts (`:394-441`); failure → error with Retry = rebuild only.
5. **flashing** — "Flashing Firmware", progress bar visible, Back hidden (`:196-201,445-501`); status
   texts per flasher state (`:471-490`). "Keep both lenses powered on and nearby — each lens takes a
   few minutes … Do not close the app during flashing." (`:455-457`).
6. **flashed** — "All Done"; install mode resumes auto-reconnect; Finish completes onboarding
   (`:503-525,545-555`).
7. **error** — headline "Something Went Wrong" (default); Retry depends on where it failed
   (`:572-577`); a flash failure's Retry is `startFlashing()` again **without** a new prompt or
   battery check (`:524`).

### 10.4 Unpair page (`UI/onboarding-unpair-page.xml`, `UI/onboarding-unpair-view-model.ts`)

"Disconnect Other Apps": "Your glasses can only be connected to one app or phone at a time. If they're
connected to another app (such as Even Realities or MentraOS) or another phone, disconnect them there
before continuing." (`xml:5-8`). Even-app instructions: "go to Home, select your glasses, open the
Connection submenu, and press Disconnect (or disable the Even app's "nearby devices" permission)."
(`view-model:10-13`). Android-only button "Open Even App Settings" opens
`ACTION_APPLICATION_DETAILS_SETTINGS` for `com.even.sg`, with a dialog if not installed
(`view-model:6-8,15-17`; `faceclaw/app/native/even-app-conflict.ts:25-39`;
`AND/FaceclawEvenAppDetector.kt:53-68`). Continue → pairing page. Nothing is unpaired programmatically.

### 10.5 Other entry points

* Main warnings modal: "Install custom firmware" when the connected firmware is incompatible
  (`UI/main-page.xml:87-89`, `UI/main-view-model.ts:932-935`).
* "Prepare fonts" → the firmware-check page's font-only path (`UI/main-view-model.ts:709-727`).

---

## 11. Safety checklist for an independent implementation

Verify **all** of the following before the first OTA write (BEGIN):

**Image integrity**
1. The bytes to be flashed hash (SHA-256, computed over the exact buffer that will be streamed, read
   back from storage) to an allow-listed value: CFW `7d8764f8…0ee7` (install) or stock
   `187ccf2b…0979` (uninstall). Never flash user-supplied or partially written files.
2. If building: stock SHA-256 matched **before** patching; every op's expected-old bytes matched;
   output SHA-256 matched after patching (§4.4).
3. Container validation per §3.6 (magic, N ∈ {5,6}, bounds, no overlap, TOC size = ps+128,
   CRC-32C in TOC == subheader == computed, preamble CRC-32 correct, exactly one main app, load address
   `0x438000`, preamble length == ps, `0x438000 + ps − 0x20 ≤ 0x7F0000`).
4. CRC implementations pass their check values (`0xC052A8C8`, `0xD7B91914`, `0xCBF43926`,
   `0x29B1`) at startup/test time.

**Target & environment**
5. Both lens addresses known, distinct and from the same pair (serial join); connect and authenticate
   both arms before starting (bonds present).
6. The wearer confirmed on the lens (physical-presence proof that the right glasses are connected) and
   acknowledged the warranty.
7. Each reported arm battery ≥ 30 % (consider refusing when a reading is missing).
8. No other client connected (Even app disconnected); your own main session disconnected and its
   auto-reconnect suppressed for the duration.
9. Firmware classification known (§5.3); treat `newer-stock-unvalidated` and failed probes as
   "expert override" paths with explicit extra warnings.
10. Negotiated ATT MTU ≥ 243 (240-byte frames); refuse otherwise (Faceclaw does not check).
11. Security auth SUCCESS observed on this connection before BEGIN.
12. Keep the process alive and the link busy: foreground service + wake lock; block back navigation
    and app teardown during `flashing` (Faceclaw's page disposal cancels the flash — §12).

**During the transfer**
13. Only the OTA characteristic is written between BEGIN and the last END (no heartbeats/queries).
14. Marker + data share a SEQ and are contiguous; a new SEQ for every other message.
15. Resend a block in place **only** after an explicit NAK; on timeout/link loss restart the
    component from FILE_CHECK (after reconnect + auth + fresh BEGIN if the link went down).
16. Treat END ∈ {0,8,9} as success, anything else as failure (retry the component ≤ 3 times).
17. Flash left then right; after the left finishes allow ≥ 5 s + up to 120 s for the right to return.
18. After completion, reconnect and confirm the expected field-100 value (or its absence after
    uninstall) before declaring success to the user.

**Never**
* never patch or flash any stock image other than the audited SHA-256;
* never enlarge the main app beyond the MRAM ceiling or change the preamble load address;
* never "fix" a stale checksum on an image you did not build from the pinned inputs;
* never retry a flash automatically in a loop without the user (each retry re-writes flash).

---

## 12. Gotchas & pitfalls

1. **Wrong CRC-32C variant.** The component CRC is MSB-first/init 0/xorout 0, not iSCSI. A mismatch is
   caught only at END (status 7) after a multi-minute transfer — validate first.
2. **Preamble CRC-32 must be computed before the component CRC-32C** (it lies inside the payload).
3. **Header/TOC are never sent.** Only subheader + payload; editing the TOC alone changes nothing on
   the device, editing the subheader changes what the device verifies.
4. **Replaying an ambiguous block corrupts the component** (no index, no de-dup) — the core OTA rule.
5. **SEQ is a group key.** Incrementing it per fragment makes the firmware drop the message
   (`g2kit/ble/docs/envelope.md:50-68`). Marker and data must share one SEQ.
6. **CRC-16 covers the pb, not the header,** and is little-endian at the end of the last fragment;
   g2-kit's `envelope.md` says otherwise — it is wrong.
7. **`BLOCK_NAK_RETRIES = 3` means 3 sends in total,** not 1 + 3.
8. **Hand-encoded magics:** g2flash's auth builder writes the magic as one raw byte
   (`g2flash/g2flash.py:159-160`) — valid only for magic < 128. On the first connection the magic is
   1, but `recover_session` calls `authenticate()` **before** `_reset_seq()`
   (`g2flash/g2flash.py:796-797`), so a mid-flash re-auth uses the running OTA counter (any value
   0–255): for values ≥ 128 the request is a malformed varint and the exact-prefix reply check can
   fail, making g2flash's reconnect path unreliable (fails safe — the lens is reported failed).
   Encode magics with a real varint encoder and keep them < 128.
9. **The first auth reply may be a non-success** (before encryption); only `1a 00` is success.
   g2flash would abort there; wait instead.
10. **MTU is not verified** by Faceclaw (`AND/AndroidStockLink.kt:28-31`); 240-byte frames on a 23-byte
    MTU fail.
11. **Write results are ignored** in `OtaFlashFlow.writeOta`; a dropped marker shows up only as a 4 s
    timeout.
12. **Stale acks:** ack matching is by opcode only; always clear the queue immediately before writing.
13. **Android back / teardown cancels a flash:** the flash page hides its buttons but
    `navigatingFrom → dispose → flasher.close() → cancel()` (`UI/onboarding-flash-page.ts:18-22`,
    `UI/onboarding-flash-view-model.ts:596-599`); no foreground service or wake lock is taken
    (iOS keeps the screen awake and aborts on backgrounding: `faceclaw/app/native/firmware-flasher.ios.ts:59-69`).
14. **Non-atomic write + no pre-flash SHA check on Android** (§2.2).
15. **No download cache** — every install/uninstall/font repair downloads ~4.5 MB.
16. **Font extraction failure aborts the CFW build** although fonts are not flashed (§4.5).
17. **Uninstall success text says "custom firmware"** because the Kotlin completion detail overrides the
    mode-specific message (`KT/OtaFlashFlow.kt:98`, `UI/onboarding-flash-view-model.ts:517-521`).
18. **Flash retry skips the prompt and battery check** (`UI/onboarding-flash-view-model.ts:524`).
19. **Unknown battery → proceed** (`UI/onboarding-flash-view-model.ts:351-353,359-371`).
20. **The CFW reports stock version strings;** only field 100 identifies it. `parseInt` accepts
    `"Faceclaw/34x"` as 34.
21. **Compatibility is `>= 34`, not exact** (`firmware-compat.ts:102-105`), while the g2flash README
    tells third parties to require the exact string (`g2flash/README.md:80-83`, `:93-97`: CFW
    revisions are not mutually compatible).
22. **`newer-stock-validated` is dead code** while `BASE == VALIDATED` (`firmware-compat.ts:37-40`).
23. **Detection follows the answering arm;** a mixed-lens pair can be misread (§5.5).
24. **Ops are not offset-sorted** and the append sits in the middle of the list; apply strictly in order.
25. **Container offsets are unaligned** (main-app subheader at `0xbe36b`, file↔MRAM delta `0x379bf5`
    is odd); never assume 4-byte alignment when reading u32 fields.
26. **"Faceclaw/<ver>" is also Faceclaw's HTTP User-Agent** (`faceclaw/app/version.ts:2`) — unrelated to
    the firmware revision; don't confuse them in logs/greps.
27. **g2flash continues to the right lens after the left failed; Faceclaw stops.** Prefer stopping.
28. **Documentation drift:** g2flash README still cites Faceclaw/14 and /17
    (`g2flash/README.md:53-56,157-159`), `demos/detect-cfw.ts` expects `EVENCFW/6` tokens and an old
    g2-kit fork API (`g2flash/demos/detect-cfw.ts:5-17,19,28`), `g2kit/ble/docs/settings.md:10-20`
    shows a `G2SettingPackage` shape that contradicts the generated schema, and code references
    `notes/ble-connections-2.2.9.md` and `docs/firmware-rebase-2.3.0.md`, which are absent from these
    checkouts.
29. **Protocol behaviour was characterised on 2.2.8/2.2.9** (captures, 30 s auth deadline, 90 s
    watchdog, "conn close reset"), while the pinned base is 2.3.0.24; re-validate timing assumptions on
    real hardware.

---

## 13. Open questions / uncertainties

1. Meaning of container header bytes `0x0C–0x3F`, TOC `eid`, subheader bytes `0x00–0x07` and
   `0x10–0x2F`, and preamble fields other than length/CRC/load address (top byte `0x04`).
2. Names and roles of 2.3.0.24 components 0–4 (only `ota/s200_firmware_ota.bin` is named anywhere).
3. Does the bootloader verify the preamble CRC-32, and what happens on mismatch?
4. Do components already verified by END survive a GATT drop + new BEGIN (g2flash assumes so; Faceclaw
   never reconnects mid-lens)? When exactly does the device commit/apply a component (status 8
   UPDATING vs 9 SYS_RESTART; OTA flag at `0x7FE000`)?
5. What state is a lens left in after an aborted, partially flashed OTA (old image intact?), and how is
   it recovered (presumably by flashing again)?
6. Mechanism and timing of "finishing either lens reboots BOTH lenses"; which END triggers it.
7. The real SID/FLAG/SEQ of OTA ack frames; whether other notifications ever appear on `…e0002`;
   whether `OTA_TRANSMIT_NOTIFY (4)` is ever sent by the device.
8. Whether the 30 s unauthenticated-link kill and the 90 s OTA watchdog (per-component, overall or
   inactivity?) apply to 2.3.0.x; "90-second OTA watchdog on the 80-block codec component" is ambiguous.
9. Whether SEQ value 0 is special to the firmware (both flashers use it after wrap-around, apparently
   without problems).
10. Whether the stock OTA receiver refuses downgrades (e.g. from a newer stock to the 2.3.0.24-based
    CFW) — only "validated" downgrades are meant to be offered, and none are validated today.
11. Whether flashing the 2.3.0.24-based image onto much older stock (e.g. 2.2.4.x, classified
    `flashable-stock`) has been tested.
12. Whether the device requires the final short block to be padded (both references send it unpadded
    and succeed).
13. The official Even app's own flashing strategy (e.g. lens order, parallelism, retries) beyond the
    captured byte format.
14. Whether the MD5-in-URL claim holds (it would allow an additional integrity check).

### 13.1 Disagreements between sources (summary)

| Topic | Disagreement | Where resolved in this spec |
|---|---|---|
| Envelope header, CRC coverage, command-channel UUIDs | `g2kit/ble/docs/{envelope,transport}.md` vs g2-kit code, Faceclaw and g2flash | §6.1–6.2 (follow the code) |
| Fragmenting `pb‖crc` | g2flash/Faceclaw chunk the body; g2-kit chunks the pb and appends the CRC | §6.2 (use g2flash form) |
| `G2SettingPackage` shape | `g2kit/ble/docs/settings.md` vs generated schema | §5.2 (schema) |
| Auth request field 1 | captured varint `08 01` vs schema `bytes secAuth` | §6.5 (send captured bytes) |
| Non-success auth reply | g2flash aborts; Faceclaw keeps waiting | §6.5 (wait) |
| Notification enable order / 2.5 s settle placement | g2flash: OTA notify, control notify, settle, auth; Faceclaw: control, OTA, auth, settle | §7.3 (either works per references; both settle 2.5 s) |
| Recovery after block-ack timeout | g2flash reconnects + fresh BEGIN; Faceclaw retries on the same link | §7.5 (reconnect path) |
| After a lens fails | g2flash continues with the other lens; Faceclaw aborts | §7.5 (abort) |
| Required revision | app accepts `>= 34`; g2flash README says require the exact string | §5.3, §12 #21 |
| Current revision numbers in docs | README mentions Faceclaw/14, /17 (`g2flash/README.md:53,159`); `faceclaw/notes/ios-protocol-sync.md:23-24` mentions 13; code and compiled patch set = 34 | §5.1 (34 is authoritative) |
| Flashable older stock | classifier treats any stock ≤ 2.3.0.24 as flashable; no evidence of testing on e.g. 2.2.4 | §13 #11 |

---

## Appendix A — Worked byte examples

All CRCs computed with the algorithms of §3.5/§6.2 (independently re-verified).

| Message | Bytes |
|---|---|
| BEGIN (SEQ 1) | `aa 21 01 03 01 01 c0 00` `00 f0 e1` |
| FILE_CHECK (SEQ 2) | `aa 21 02 83 01 01 c0 00` `01` ‖ 128-byte subheader ‖ CRC16-LE(`01`‖subheader) |
| block marker (SEQ s) | `aa 21 ss 03 01 01 c0 00` `02 b2 c1` |
| block data, 4096 B (SEQ s) | 18 frames: `aa 21 ss e8 12 01 c1 00` + 232 B … `aa 21 ss e8 12 11 c1 00` + 232 B, `aa 21 ss 9a 12 12 c1 00` + 152 B + CRC16-LE(block) |
| END (SEQ e) | `aa 21 ee 03 01 01 c0 00` `03 93 d1` |
| OTA ack (RX) | `aa 12 ?? LL ?? ?? ?? ??` `op status crcLo crcHi` — LEN presumably `04` (g2flash strips the last 2 bytes as CRC, `g2flash/g2flash.py:609`); only bytes 8–9 are interpreted |
| auth request (magic `0x60`, SEQ 1) | `aa 21 01 0c 01 01 80 00` `08 04 10 60 1a 04 08 01 10 04` `b7 5d` |
| auth success pb (magic `0x60`) | `08 04 10 60 1a 00` `20 e6` |
| prelude (magic 156) | `aa 21 SS 13 01 01 01 20` `08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00` `a1 42` |
| settings query (magic 100, SEQ `0x41`) | `aa 21 41 0a 01 01 09 20` `08 02 10 64 22 02 08 01` `97 e8` |
| field 100 in settings reply | `a2 06 0b` `46 61 63 65 63 6c 61 77 2f 33 34` ("Faceclaw/34") |
| EvenHub heartbeat pb (magic `0x65`) | `08 0c 10 65 72 02 08 00` (CRC `54 15`) |
| EvenHub shutdown pb (magic `0x66`) | `08 09 10 66 5a 02 08 00` (CRC `ac 30`) |

The CRC-16 of the single bytes `00`, `02`, `03` is `0xe1f0`, `0xc1b2`, `0xd193`, so BEGIN, marker and
END frames always end in `f0 e1`, `b2 c1`, `93 d1` regardless of SEQ.

## Appendix B — Minimal reference algorithms (pseudocode)

```
crc16_ccitt_false(data):            crc32c_msb(data):                   // table t: t[b]=b<<24 shifted
  c = 0xFFFF                          crc = 0                            //   8x with poly 0x1EDC6F41
  for b in data:                      for b in data:
    c ^= b << 8                         crc = (crc << 8) ^ t[((crc >>> 24) ^ b) & 0xFF]
    repeat 8: c = (c & 0x8000)        return crc & 0xFFFFFFFF
        ? ((c << 1) ^ 0x1021) & 0xFFFF
        : (c << 1) & 0xFFFF
  return c        // append [c & 0xFF, c >> 8]

frames(sid, flag, seq, pb):
  body = pb + le16(crc16_ccitt_false(pb))
  tot  = max(1, ceil(len(body) / 232))       // must be ≤ 255
  for i in 0..tot-1:
    chunk = body[i*232 : (i+1)*232]
    emit [0xAA, 0x21, seq, len(chunk), tot, i+1, sid, flag] + chunk
```

## Appendix C — Where each responsibility lives (reference code)

| Responsibility | Reference files |
|---|---|
| Pinned URL/hashes, download, patch apply, persist | `faceclaw/app/g2/firmware-builder.ts`; `g2flash/build_cfw.sh`; `g2flash/patches/apply_patches.py` |
| Patch data | `g2flash/patches/cfw_patches.json`; `faceclaw/app/g2/firmware/cfw-patches.ts`; `faceclaw/scripts/update_deltas.sh` |
| Patch generation (dev only) | `g2flash/patches/{gen_patches.py,patch_compress.py,build.py,audit_stock.py,stock_abi_230.json,*.c}` |
| Revision string | `g2flash/patches/settings_ext.c:429-488` |
| Container parse/validate/CRC | `KT/FirmwareImage.kt`; `g2flash/g2flash.py:173-317` |
| Fonts (phone-side) | `faceclaw/app/g2/firmware-fonts.ts` |
| Compatibility rules | `faceclaw/app/g2/firmware-compat.ts`; `faceclaw/tests/firmware-compat.test.cjs` |
| Probe | `KT/DeviceInfoProbeFlow.kt`; `AND/FaceclawDeviceInfoProbe.kt`; `faceclaw/app/native/device-info-probe.ts` |
| Prompt | `KT/FlashPromptFlow.kt`; `AND/FaceclawFlashPromptCommunicator.kt`; `faceclaw/app/native/flash-prompt-communicator.ts` |
| OTA | `KT/OtaFlashFlow.kt`; `AND/FaceclawFirmwareFlasher.kt`; `faceclaw/app/native/firmware-flasher.ts`; `g2flash/g2flash.py:604-812` |
| Shared session plumbing / auth | `KT/StockLinkSession.kt`; `KT/StockLink.kt`; `KT/BleProtocol.kt`; `KT/ConnectionOptions.kt` |
| Android GATT | `AND/AndroidStockLink.kt`; `AND/FaceclawBleManager.kt` |
| UI | `UI/onboarding-view-model.ts`, `UI/onboarding-unpair-*`, `UI/onboarding-firmware-check-*`, `UI/onboarding-flash-*`, `UI/main-view-model.ts:932-954` |
| Tests pinning behaviour | `KTEST/FirmwareImageTest.kt`; `KTEST/StockFlowsTest.kt` |
| Protocol schemas | `g2kit/ble/gen/{ota_transmit_pb.ts,dev_config_protocol_pb.ts,dev_pair_manager_pb.ts,g2_setting_pb.ts,EvenHub_pb.ts}` |
