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

## Where things are

- `core/.../gfx` software rasteriser (8-bit grey, SDF anti-aliasing), baked fonts, icons, PNG.
- `core/.../ui` design tokens (`Theme.kt` — change the look here), input model, animation,
  `MenuList`, `TextPager`.
- `core/.../shell` window manager: apps, screen stacks, status bar, overlays, display power, frames.
- `core/.../apps` built-in apps; register new ones in `core/.../runtime/GlassesRuntime.kt` (`AppCatalog`).
- `core/.../protocol` G2 protocol: `Envelope` (aa21 framing, CRC16), `CfwTransport`/`CfwDraw`
  (sid 0xF0 custom-firmware transport, draw calls), `G2Events`, `GlassesSession` (connection state
  machine), `FakeGlasses` (firmware model used by tests).
- `app/` Android: `ble/AndroidBleLink` (GATT), `service/` (foreground service, connection manager),
  `platform/` (notifications, media, calendar, weather), `ui/` (Compose).
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
- Firmware flashing is intentionally not implemented; it needs hardware validation first
  (see `docs/analysis/06-firmware.md` §11).
