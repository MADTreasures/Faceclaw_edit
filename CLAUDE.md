# Faceclaw Edit — notes for coding agents

Companion app for Even Realities G2 glasses running the Faceclaw custom firmware (revision ≥ 34).
Pure Kotlin. The user writes German; code, comments and commit messages are English, user docs German.

## Build & test

- `./gradlew :core:test` — all logic tests (JVM, no hardware). Keep them green.
- `./gradlew :app:assembleDebug :app:lintDebug` — Android app (needs the Android SDK).
- `./gradlew :simulator:screenshots` — renders every glasses screen to `docs/screenshots/*.png`;
  look at the PNGs after UI changes. `./gradlew :simulator:run` opens the desktop simulator.
- `./gradlew :tools:bakeFonts` — re-bakes `core/src/main/resources/fonts/*.fcf` and regenerates
  `gfx/Icons.kt` from `tools/fonts/` (edit the spec list in `tools/.../FontBaker.kt`).
- `FACECLAW_STOCK_IMAGE=/path/g2_2.3.0.24.bin ./gradlew :core:test` — also checks the firmware
  pipeline against Even's real stock image (download it yourself; never commit it).
- `web/designer/build.sh` — regenerates `web/designer/index.html` from `designer.html`.

## Where things are

- `core/.../gfx` software rasteriser (8-bit grey, SDF anti-aliasing), baked fonts, icons, PNG.
- `core/.../ui` design tokens (`Theme.kt` — change the look here), input model, animation,
  `MenuList`, `TextPager`.
- `core/.../shell` window manager: apps, screen stacks, status bar, overlays, display power, frames.
- `core/.../apps` built-in apps; register new ones in `core/.../runtime/GlassesRuntime.kt` (`AppCatalog`).
- `core/.../protocol` G2 protocol: `Envelope` (aa21 framing, CRC16), `CfwTransport`/`CfwDraw`
  (sid 0xF0 custom-firmware transport, draw calls), `G2Events`, `GlassesSession` (connection state
  machine), `FakeGlasses` (firmware model used by tests).
- `core/.../firmware` firmware install: `EvenOtaImage` (container validation and brick guards),
  `PatchSet` + `FirmwareCatalog` (pinned SHA-256 allow-list, bundled g2flash patch set in
  `resources/firmware/`), `OtaProtocol`/`OtaFlasher` (stock OTA flash), `FirmwarePreflight`
  (versions, batteries, on-lens confirmation), `FirmwareInstaller` (whole flow), `FirmwareLink` port.
  Tests use `FakeOtaGlasses` (test sources) with fault injection.
- `app/` Android: `ble/AndroidBleLink` (GATT, implements `BleLink` and `FirmwareLink`), `service/`
  (foreground service, connection manager), `firmware/` (stock image cache, controller, service),
  `platform/` (notifications, media, calendar, weather), `ui/` (Compose, incl. `FirmwareScreen`).
- `web/designer/` browser designer for menus and screens (single HTML file; also published as a
  claude.ai artifact whose db holds the user's designs in the `designs` collection). Design JSON
  format and its mapping to `MenuItem`/draw calls: `docs/design-format.md`.
- `docs/analysis/` detailed specs of the original protocols with source citations — read the
  relevant one before touching protocol code.

## Rules

- The core must not depend on Android. Platform access goes through ports in `platform/Ports.kt`
  or `protocol/BleLink.kt`.
- Shell/app code runs on one logical thread; talk to the shell from other threads via
  `dispatch()`/`post()`.
- Protocol changes need byte-level tests against documented vectors and, where possible, an
  end-to-end test with `FakeGlasses`.
- Never send custom-firmware traffic to glasses that did not report `Faceclaw/<n>` with n ≥ 34.
- Firmware: only images on the `FirmwareCatalog` allow-list are ever flashed; keep every check of
  `docs/analysis/06-firmware.md` §11 (image validation, SHA-256 before flashing, batteries, on-lens
  confirmation, no other traffic during OTA, in-place resend only after an explicit NAK). Changes to
  the OTA flow need `FakeOtaGlasses` tests. Never commit or redistribute Even's firmware.
- Firmware mods are much riskier than app changes: prefer phone-side features. A new patch set comes
  from g2flash's toolchain, gets its own pinned hashes and revision string, and needs hardware
  validation before anyone installs it.
