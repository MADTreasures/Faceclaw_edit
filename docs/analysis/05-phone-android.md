# 05 — Phone-side app and Android platform integration

Specification of the phone-side half of Faceclaw (Android): the phone screens and flows, the
Android components (manifest, services, receivers, listeners, providers), the TypeScript↔Kotlin
bridge, settings and other persistence, and the build/release pipeline. It ends with a recommended
architecture for a clean-room Kotlin/Jetpack Compose rebuild, a list of pitfalls the original hit,
and open questions.

* Reference checkout: `jimrandomh/faceclaw` at commit `a6291cf`, app version `0.7.2`
  (`app/version.ts:1`). All `path:line` citations are relative to that checkout.
* Terminology: **TS** = TypeScript under `app/` (NativeScript); **Android Kotlin** = classes in
  `App_Resources/Android/src/main/java/com/faceclaw/app/` (compiled by NativeScript's Gradle
  build); **KMP** = the Kotlin Multiplatform module `native/kotlin/shared/` (compiled to an AAR);
  **glasses UI / shell** = the UI drawn on the G2 lenses (specified elsewhere); **phone UI** =
  what appears on the phone's own screen (this spec).
* Out of scope here (covered by sibling specs): the G2 BLE protocol and session core, the glasses
  shell/menus/apps, EvenHub emulation internals, voice/ASR/LLM pipelines, firmware build/OTA
  internals. They are referenced only where the phone side calls into them.

---

## 1. Context: how the original phone app is put together

### 1.1 Process and runtime model

| Aspect | Original behaviour | Source |
|---|---|---|
| Package / application id | `com.faceclaw.app` | `nativescript.config.ts:4` |
| Application class | `com.tns.NativeScriptApplication` (NativeScript runtime) | `App_Resources/Android/src/main/AndroidManifest.xml:60` |
| Activities | One UI activity `com.tns.NativeScriptActivity` (`singleTask`), NativeScript's `ErrorReportActivity`, plus the native `FaceclawAlarmActivity` | `AndroidManifest.xml:86-108,166-173` |
| JS threads | Main isolate on the Android main thread (phone UI + glasses shell + controller); one NativeScript Worker isolate per "worker app" (games, Terminal, Navigate, …). Worker↔main messages are small JSON; pixels go worker→Java directly | `app/ui/shell/worker-window.ts:11-16` |
| Central singleton | `dashboardController` — owns connection lifecycle, the glasses shell, preview display, notification hook, Wear remote, input API, timers, text-editing sessions. Created at first import (by the main page) | `app/g2/dashboard-controller.ts:329-452,2881` |
| Phone UI ↔ controller | Phone view-models subscribe to an immutable `DashboardSnapshot` (pushed on every change) and call controller methods for actions | `dashboard-controller.ts:92-136,970-1017,2872-2878` |
| Native code | Android Kotlin classes with a Java-style static/instance API, called by fully-qualified name from TS (`com.faceclaw.app.X`), plus the KMP AAR (`com.faceclaw.app.*` and `com.faceclaw.shared.*`) | `native/kotlin/README.md` |

### 1.2 Startup sequence (Android)

`app/bootstrap.android.ts` runs before anything else (`app/app.ts` imports `./bootstrap`):

1. In debug builds only, run a Kotlin-bridge smoke test that exercises both call directions and
   logs `FACECLAW_KOTLIN_BRIDGE_PASS`/`_FAIL` (never fatal) — `bootstrap.android.ts:10`,
   `app/native/kotlin-bridge.ts:5-38`.
2. Push the HTTP User-Agent (`Faceclaw/<version>`) into Kotlin so okhttp requests carry it —
   `bootstrap.android.ts:12`, `app/util/http.ts:43-50`, `app/version.ts:2`.
3. Register the `ACTION_SEND` share handler — `bootstrap.android.ts:13` (§3.11).
4. Register the phone-rotation lock — `bootstrap.android.ts:14` (§3.11).
5. `Application.run` → `app-root.xml` is a `Frame` whose default page is `phone-ui/launch-page`
   (`app/app-root.xml:1`).

Everything else (BLE, notifications hook, media, timers, Wear, input API) is started lazily by
the `dashboardController` constructor when the main page first imports it
(`dashboard-controller.ts:329-452`). Onboarding pages deliberately avoid importing the controller
(`app/phone-ui/config-view-model.ts:197-205` uses a lazy `require` for exactly this reason).

```
┌──────────────────────────── Android process com.faceclaw.app ────────────────────────────┐
│ NativeScriptActivity (phone UI pages)        Services / receivers (Kotlin, no JS needed)  │
│   └─ Frame → pages → view-models             ├─ FaceclawForegroundService (connectedDevice│
│        │ subscribe(snapshot)/actions         │     |microphone|location)                  │
│        ▼                                     ├─ FaceclawMediaNotificationListenerService  │
│   dashboardController (main JS isolate) ─────┼─ FaceclawWearListenerService (Play svcs)   │
│     ├─ shell (glasses UI)                    ├─ FaceclawAlarmReceiver / RescheduleReceiver│
│     ├─ FaceclawCommunicatorBridge ──► FaceclawBleCommunicator (worker thread, GATT)       │
│     ├─ PreviewDisplayTarget ──► FaceclawPreviewCompositor (preview-only mode)            │
│     └─ bridges: media, notifications, alarms, wear, calendar, location, settings, …      │
│   Worker isolates (per app) ── frames ──► FaceclawBleCommunicator.getActive() (static)    │
│ FaceclawSettings (SharedPreferences "faceclaw_settings") shared by all isolates           │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Phone UI: screens and flows

### 2.1 Navigation graph

```
launch-page ──(onboarding.complete?)──► main-page (clear history)
     └──────────────(no)──────────────► onboarding-page step 1 (splash)
                                             │ Next
                                             ▼
                                        step 2 "Before You Continue" ──footer──► document-page
                                             │ Agree                              (LICENSE / PRIVACY)
                                             ▼
                                        permissions-page {onboarding:true}
                                             │ Continue (confirm if required missing)
                                             ▼
                                        onboarding-page step 3 "Custom Firmware Required"
                              ┌──────────────┴──────────────┐
                   Preview Only                          Flash Firmware
          previewOnly=true, complete=true                     ▼
                     ▼                           onboarding-unpair-page ("Disconnect Other Apps")
                 main-page                                    │ Continue
                                                              ▼
                                                 pairing-page {onboarding:true} ──"Enter addresses
                                                              │ Continue        manually"──► config-page
                                                              ▼                                │ Continue
                                                 onboarding-firmware-check-page ◄──────────────┘
                              ┌─────────────────┬─────────────┴───────────────┐
                        custom (fonts ok /    flashable / newer        error: Retry | Install | Skip
                        fonts extracted)      (Install / Proceed)
                              │ Finish            ▼
                              ▼            onboarding-flash-page {install, autoStart}
                          main-page               │ Finish (complete=true, previewOnly=false)
                                                  ▼
                                              main-page

main-page overflow ─► Connect|Disconnect · Pair glasses (→ pairing-page, or → unpair-page when
                      preview-only) · Permissions · Conversations (→ speakers / ask / conversation)
                      · Take screenshot · Record screen · Uninstall custom firmware (→ flash-page
                      {uninstall}); warnings modal ─► flash-page {install} · firmware-check-page
```

Navigation rules worth preserving:

* The launch page is invisible; on `loaded` it navigates (no animation, `clearHistory`) to the
  main page if `onboarding.complete` is true, else to onboarding
  (`app/phone-ui/launch-page.ts:5-13`).
* Leaving onboarding (Finish/Preview Only/Save) calls `finishOnboardingNavigation()`, which on
  Android navigates to `main-page` with `clearHistory` (`app/phone-ui/onboarding-navigation.ts:4-9`).
* Pages keep their view-model across back navigation (they only build one if
  `page.bindingContext` is empty), so a return from a detour keeps selections/state
  (`onboarding-page.ts:12-15`, `pairing-page.ts` `navigatingTo`, `config-page-shared.ts:6-11`).
  The main page is the exception: it builds a fresh model on every `navigatingTo` and disposes it
  on `unloaded` (`app/phone-ui/main-page.ts:9-12,29-42,170-172`).
* The onboarding page accepts a `{ step }` context so the permissions page can forward to step 3
  (`onboarding-page.ts:5-15`).

### 2.2 Visual conventions

* Light theme: page background `#ffffff`, text `#222222`, action bar `#424242` with white text and
  a white-tinted overflow icon (`app/app.css:5-17`, `App_Resources/Android/src/main/res/values/styles.xml`);
  Android force-dark disabled (`res/values-v29/styles.xml`). Dark-mode variants exist for a few
  elements via the NativeScript theme's `.ns-dark` class (`app.css:44-91`).
* Onboarding body text 18sp; primary buttons 18sp (`app.css:62-70`).
* Main-page mirror "stand-in message" box: `#222222` background, white text, padding 16
  (`app.css:116-125`).
* Remote-control pads: `#1f1f1f` background, 14dp radius, `#3a3a3a` border, 230dp tall watch pad,
  150×250dp ring pad (`app/phone-ui/remote-controls.css:94-133`). Selected tab `#424242`/white,
  unselected `#e2e2e2`/`#333` (`remote-controls.css:9-22`).
* Tab-bar icons (mic, keyboard) are vector drawables `ic_tab_mic`, `ic_tab_keyboard`
  (`res/drawable/`); the mic art is derived from a Noun Project icon — a rebuild should use its
  own art (e.g. Material Symbols).

### 2.3 Onboarding page (3 steps)

Single page, `actionBarHidden`, layout rows `*,auto,auto` with padding 20: content, footer links,
and a two-button row (secondary left, primary right) (`app/phone-ui/onboarding-page.xml:1-27`).
Content per step (`app/phone-ui/onboarding-view-model.ts:19-47`):

| Step | Layout | Headline / content (paraphrased) | Secondary | Primary |
|---|---|---|---|---|
| 1 | Splash: headline "Faceclaw", tagline "Perching on the faces of giants", logo sized `min(480, 0.4 × screen height)` (`:144-146`) | — | hidden | **Next** → step 2 |
| 2 | Scrollable body; footer links "License (GPLv3)" · "Privacy Policy" shown only on this step (`:153-155`) | "Before You Continue": unofficial software, not by Even Realities; breaking the headset is not Even's fault; may void warranty; beta, don't rely on it | **Back** → step 1 | **Agree** → permissions page `{onboarding:true}` (`:63-70`) |
| 3 | Scrollable body | "Custom Firmware Required": Faceclaw needs its custom firmware; choice of *Preview Only* (explore on phone, nothing written) or *Flash Firmware*; can return to stock by reconnecting the official app; not covered by warranty | **Preview Only** → `previewOnly=true`, `onboarding.complete=true`, go to main (`:99-104`) | **Flash Firmware** → unpair page (`:72-76`) |

Footer links open the document page with `{fileName: "LICENSE"|"PRIVACY", title}` (`:79-92`).

Onboarding flags (`app/phone-ui/onboarding-state.ts`), stored in NativeScript `ApplicationSettings`
(not the Faceclaw settings store — see §5.1):

| Key | Meaning |
|---|---|
| `onboarding.complete` (bool, default false) | Main page is the start page. First false→true transition also arms the welcome sound (`:11-19`). |
| `onboarding.previewOnly` (bool) | User chose Preview Only; hides glasses-specific menu items; cleared by pairing/flash flows (`:38-44`). |
| `onboarding.welcomeSoundPending` (bool) | Play a one-time celebratory buzzer sequence on the first successful connection (consumed in `dashboard-controller.ts:1475-1480`). |

### 2.4 Permissions page

Reached from onboarding (step 2 → here → step 3) and from the main menu. In onboarding mode the
action bar is hidden and a large "Permissions" headline is shown; otherwise the action bar title
is "Permissions" (`app/phone-ui/permissions-page.xml:1-36`, `permissions-view-model.ts:151-165`).

* A list of cards, each: title, grey "Optional" badge when optional, description, and a ✓ when
  granted. Tapping an ungranted card runs its request; tapping a granted card does nothing; only
  one request runs at a time (`permissions-view-model.ts:194-200,229-240`).
* Statuses are re-checked on page load and on every app resume, because two of the grants happen
  in system Settings screens (`:138-147`).
* Primary button: "Continue" in onboarding (rendered dimmed while any required permission is
  missing, but still tappable → confirm dialog "Missing Permissions … Are you sure you want to
  continue?" with Continue / Go Back), "Done" otherwise (pops). Secondary "Back" only in
  onboarding (`:170-223`).
* A denial simply leaves the card unchecked (no error UI on Android) (`:229-240`).

Android cards, in display order (`permissions-view-model.ts:33-92`; request plumbing in
`app/g2/android-permissions.ts`):

| Card | Required? | Granted when | Request action |
|---|---|---|---|
| Nearby Devices | required | API ≥31: `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT`; else `ACCESS_FINE_LOCATION` (`android-permissions.ts:37-45,112-115`) | runtime request (code 4247) |
| Send Notifications ("a notification will be pinned to keep Faceclaw running while the screen is off") | required | API ≥33: `POST_NOTIFICATIONS`; older: always true (`:135-139`) | runtime request (code 4248) |
| Battery Optimization | required | `PowerManager.isIgnoringBatteryOptimizations(pkg)` (`app/native/battery-optimization.ts:14-20`) | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` URI (`:23-33`) |
| Read Notifications | optional | package listed in `Settings.Secure enabled_notification_listeners` (`FaceclawEvenAppDetector.kt:21-38`) | open `ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| Microphone | optional | `RECORD_AUDIO` | runtime request (code 4243) |
| Location | optional | **fine** location held (approximate-only leaves it unchecked) | request FINE+COARSE together (code 4246), then re-check fine (`android-permissions.ts:207-220`) |
| Calendar | optional | `READ_CALENDAR` | runtime request (code 4244) |

Runtime requests use `ActivityCompat.requestPermissions` on the foreground (or start) activity and
listen for the activity's permission-result event filtered by request code
(`android-permissions.ts:61-101`).

### 2.5 "Disconnect Other Apps" (onboarding-unpair) page

Purpose: the glasses accept only one phone/app connection at a time, and an app still connected
blocks advertising, pairing and GATT access. Content (`app/phone-ui/onboarding-unpair-page.xml:1-21`,
`onboarding-unpair-view-model.ts`):

* Headline "Disconnect Other Apps"; text: glasses can only be connected to one app or phone at a
  time (e.g. Even Realities, MentraOS); disconnect there first.
* Even-app instructions: Home → select glasses → Connection → Disconnect, or disable the Even
  app's "Nearby devices" permission (`onboarding-unpair-view-model.ts:10-13`).
* Button "Open Even App Settings" (width ≤320dp): opens the system App-info page for package
  `com.even.sg`; if not installed shows "Even app not installed …" dialog; on failure "Unable to
  open Even app settings" (`app/native/even-app-conflict.ts:25-39`, `FaceclawEvenAppDetector.kt:53-72`).
* Back (pop, or fall back to onboarding step 3) / Continue → pairing page `{onboarding:true}`
  (`onboarding-unpair-view-model.ts:19-36`).

This page is also the entry point of "Pair glasses" from the main menu while in preview-only mode
(`app/phone-ui/main-view-model.ts:915-919`).

### 2.6 Pairing page (scan & choose glasses)

Layout (`app/phone-ui/pairing-page.xml:1-90`): headline ("Pair Your Glasses" in onboarding with
action bar hidden, else action bar "Pair Glasses"), instructions ("Select your G2 glasses and R1
ring below. Wear the glasses or take them out of the case…"), a status row with spinner, a
scrolling list with sections **Glasses** and **Ring (optional)**, a "Selected" summary, and two
button rows: [Back|Cancel] [Continue|Save], then [Pause scan|Scan again] [Enter addresses manually].

Glasses row (84dp art column + details + chevron): product artwork (80×30) with a colour swatch
+ colourway label when no variant photo exists, model title, badge "Yours" (serial matches the
previously paired identity) or "Closest", variant summary, proximity line (glyph + summary;
emphasised when zone is "immediate", <0.5 m), device name/serial, arms summary (which arms were
heard), warning, chevron `✓` selected / `›` selectable / `…` incomplete
(`pairing-view-model.ts:457-476`, `app/g2/pairing-candidates.ts:322-340`,
`app/g2/ble-proximity.ts:69-80`). Ring rows are similar without the swatch.

Behaviour (`app/phone-ui/pairing-view-model.ts`):

* `start()`: ensure BLE permissions; check Bluetooth enabled ("Bluetooth is off. Turn it on, then
  tap Scan."); start a low-latency scan streaming raw advertisements into a `DiscoveryAggregator`;
  immediately emit already-bonded devices (they appear with no signal and gain a serial once
  heard); re-render every 500 ms (`:109-159`, `:42`).
* Scan filtering, serial/side decoding and pair grouping are pure TS (unit-tested) — see the BLE
  spec; the Kotlin scanner only admits anything whose name contains G2, starts with the stock
  "EVEN R1" prefix, or carries Even's `ER` (0x5245) manufacturer data, and re-inserts the company
  id into the manufacturer payload (`FaceclawDeviceDiscovery.kt` header, `EVEN_COMPANY_ID`).
* Entries not heard for 12 s are pruned (`pairing-candidates.ts:85`); a selected pair that
  disappears or degrades to one arm is deselected so Save can never write a one-armed selection
  (`pairing-view-model.ts:386-401`).
* Status texts: "Scanning…", "Scanning… N pairs ready · M with one arm heard · K rings. Tap a pair
  to select it.", "Scan paused.", "Scan failed: …". After 10 s with nothing heard: hint to take
  glasses out of the case / disconnect other apps / (Android ≤11) turn on Location
  (`:228-249,420-435`).
* Selection is always an explicit tap (never auto-select). Tapping an incomplete pair shows why
  in the status line instead of selecting (`:287-303`).
* Primary enabled when a complete pair is selected, or (non-onboarding only) just a ring
  (`:275-277`). Saving writes `deviceAddress.right/left/ring` and the paired identity (serial, arm
  names/addresses, ring name/address, time) (`:305-342`); a ring-only save updates the stored
  identity's ring fields.
* After saving: onboarding → `previewOnly=false`, go to firmware check; otherwise return to main
  (`:343-351`).
* The scan and refresh timer pause on `Application.suspend` and restart on resume; navigating
  forward (manual entry) pauses, backing out disposes (`:79-107`, `pairing-page.ts` `navigatingFrom`).
* From the main menu, pairing first disconnects the current session (a connected arm stops
  advertising) and lifts auto-reconnect suppression because pairing is a detour, not a Disconnect
  (`main-view-model.ts:915-933`).

### 2.7 "Configure Devices" page (manual MAC entry)

Reached from the pairing page ("Enter addresses manually") in either mode
(`app/phone-ui/config-page.xml`, `config-view-model.ts`):

* Intro text: configure MAC addresses for the G2 arms and optional R1 ring; no connection test.
* Identity card (artwork, product name, variant, "Serial …", "Left … · Right … · Ring … · paired
  <date>") shown only while the stored identity still matches the entered left/right addresses
  (`config-view-model.ts:47-103`, `app/g2/device-addresses.ts:117-123`).
* Three text fields: Right arm MAC, Left arm MAC, R1 ring MAC (optional); no autocorrect/capitals.
* Buttons "Load paired devices" (Android only: fills from bonded devices) and "Scan for glasses"
  (→ pairing page; outside onboarding it first disconnects and lifts reconnect suppression)
  (`:190-222`).
* Save/Continue validates: each address normalised to `AA:BB:CC:DD:EE:FF`; right and left
  required, ring optional; all distinct; errors in the status line (`:243-268`). Onboarding
  continues to the firmware check.
* A "discovery log" text area shows what the bonded-device loader found.

### 2.8 Firmware check page (onboarding)

`actionBarHidden`; headline, spinner, centred status text; bottom: an error-only row
[Install][Skip] above [Back][primary] (`app/phone-ui/onboarding-firmware-check-page.xml`).

Flow (`app/phone-ui/onboarding-firmware-check-view-model.ts`):

1. `checking` — "Checking Firmware". Ensure BLE permissions; require a valid right-arm address
   ("No glasses address is configured…"); run the stock-firmware device-info probe (right lens,
   then left; each lens bonds separately and the OS may show a pairing dialog). Status follows
   probe states: "Connecting to the right lens…", "Pairing with the left lens… Each lens pairs
   separately; if your phone asks to pair, tap Pair.", "Reading the firmware version…"
   (`:164-214`).
2. Classify (`classifyOnboardingFirmware`, see firmware spec) and branch (`:216-284`):

| Classification | Phase / headline | Primary | Notes |
|---|---|---|---|
| `custom` (fonts present) | custom / "Custom Firmware Detected" | **Finish** | Back hidden |
| `custom` (fonts missing) | fonts / "Preparing G2 Fonts" → "Fonts Ready" | **Finish** | Downloads stock firmware to extract EvenHub fonts; glasses are **not** reflashed (`:286-322`) |
| `older-faceclaw` | flashable / "Firmware Update Available" | **Install Firmware** | Names required CFW revision (34) |
| `other-custom` | flashable / "Other Custom Firmware" | **Install Firmware** | |
| `flashable-stock` | flashable / "Ready to Install" | **Install Firmware** | |
| `newer-stock-validated` | newer-validated / "Ready to Install" | **Install Firmware** | Warns official app may need to re-upgrade |
| `newer-stock-unvalidated` | newer / "Unrecognized Firmware" | **Proceed Anyway** | Extra risk warning |
| unknown / exception | error / "Couldn't Check Firmware" | **Retry** | Plus escape hatches **Install** (flash anyway) and **Skip** (treat as custom) (`:149-160`) |

3. Install/Proceed → flash page with `{mode:"install", fromOnboarding:true, autoStart:true}`
   (`:342-350`). Finish → lift reconnect suppression, `previewOnly=false`,
   `onboarding.complete=true`, go to main (`:352-358`).
4. Probe is closed on dispose/back; a generation counter discards stale async results.

The same page is reused from the main-page warning "Prepare G2 fonts" (§2.10.7).

### 2.9 Flash page (install or uninstall custom firmware)

`actionBarHidden`, swipe-back disabled; headline, spinner, progress bar (only while flashing),
status, and a small monospace-ish log (12sp) of `[HH:MM:SS] line` entries; bottom
[secondary][primary] (`app/phone-ui/onboarding-flash-page.xml`). Context:
`{mode: "install"|"uninstall", fromOnboarding, autoStart}` (`onboarding-flash-page.ts:5-15`).

Phases (`app/phone-ui/onboarding-flash-view-model.ts:27`) and buttons:

| Phase | Headline | Primary | Secondary |
|---|---|---|---|
| intro | "Flash Custom Firmware" / "Uninstall Custom Firmware" + explanation | Connect & Confirm | Back |
| prompt | "Confirm On Your Glasses" (or "Checking Battery" on a battery-only retry) | — | Cancel |
| building | "Preparing Firmware" | — | hidden |
| flashing | "Flashing Firmware" + progress bar | — | hidden |
| flashed | "All Done" | Finish | hidden |
| error | "Something Went Wrong" / "Charge Your Glasses" | Retry (phase-specific) | Back |

Behaviour:

* Entering the page **suppresses auto-reconnect** (the flasher needs the glasses to itself)
  (`:61-65`).
* Prompt: resolve addresses (stored, or an 8 s scan that also saves them) (`:529-543`); connect
  both arms with the stock protocol; system pairing prompts may appear ("accept it, once per
  lens"); show a Yes/No prompt on the lens: "Flashing custom firmware will void your warranty.
  Continue?" / "Reinstalling the official firmware removes Faceclaw's custom features. Continue?"
  (kept short for the ~50-column lens grid) (`:84-89,241-306`); then read both arms' battery.
* Declined → error "You declined on the glasses…". Battery below **30 %** on either arm → error
  "Charge Your Glasses" whose Retry re-checks only the battery (no second lens confirmation)
  (`:33,308-351`). Unknown battery → proceed with a log note.
* Building: install builds the patched image; uninstall uses the unmodified stock image
  (`:394-437`). Progress texts: downloading from Even's CDN → verifying → extracting fonts →
  "Applying patches (a/b)" → verifying output → saving.
* Flashing: progress = `((lensIndex + bytesSent/bytesTotal) / 2) × 100` with left = first half,
  right = second half; status "Flashing <lens> lens — part i/n, block j/m…"
  (`:492-499`). Warns not to close the app.
* Success: install → lift auto-reconnect suppression (uninstall stays manually disconnected
  because stock firmware would immediately be flagged incompatible) (`:505-523`). Finish on an
  install sets `previewOnly=false` + `onboarding.complete=true` and goes to main (`:545-556`).
* Back from intro/error: pop when in onboarding, else go to main (`:557-570`).

### 2.10 Main page ("remote controls" + mirror)

`app/phone-ui/main-page.xml`, `main-page.ts`, `main-view-model.ts`, shared
`remote-controls.xml`/`remote-controls-view-model.ts`, `keyboard-input-panel.xml`.

#### 2.10.1 Page lifecycle

On `loaded` (`main-page.ts:109-168`): clean up any previous state; `refreshEvenAppStatus()`
(re-checks battery-optimization, fonts, alarm reliability and Even-app conflict); re-attach the
model's subscriptions; **auto-connect**; restore previously open glasses apps (once per process);
recompute layout metrics; attach the phone preview with a visibility predicate
(`page.isLoaded && no stand-in message && page view isShown()`); listen for orientation changes;
auto-focus/dismiss the IME when the text editor or keyboard panel opens/closes. On `unloaded` the
model is disposed (all controller/settings listeners removed) (`main-view-model.ts:84-131`).

Auto-connect (`main-view-model.ts:864-878`): only when phase is `disconnected`; first
`ensurePreviewDisplay()` (brings up the headless preview pipeline in preview-only mode); then, if
auto-reconnect is not suppressed and both arm addresses are valid, `connect()`; failures are
swallowed (status shows them).

#### 2.10.2 Action bar

Custom title view: "Faceclaw" left; right-aligned short connection label and a ⚠️ warning icon;
the overflow (popup) menu (`main-page.xml:2-17`).

Short connection label (`main-view-model.ts:150-180`), tap → alert "Connection status" with the
full status sentence (`:182-184`):

| Phase | Label |
|---|---|
| connected | Connected |
| charging | Charging |
| disconnecting | Disconnecting |
| connecting | "Reconnecting" if status starts with "Reconnecting", else "Connecting" |
| disconnected | "Failed" if status starts with "Failed"; "Preview" in preview mode; else "Disconnected" |

Overflow menu (`main-page.xml:10-16`):

| Item | Enabled / visible | Action |
|---|---|---|
| Connect / Disconnect / Disconnecting... | enabled unless disconnecting (Disconnect stays live while connecting — the only exit from a retry loop); hidden in preview-only | `connect()` or `disconnect()`; errors become "Failed: …" status (`main-view-model.ts:814-862`) |
| Pair glasses | enabled when not connecting/disconnecting | disconnect + lift suppression → pairing page; preview-only → unpair page (`:915-933`) |
| Permissions | always | permissions page `{onboarding:false}` |
| Conversations | always | conversations page |
| Take screenshot | always | save 4-bit grayscale PNG of the occupied screen area (§6.2) |
| Record screen | always | start animated-GIF recording; a "Stop recording" button appears under the controls (`main-page.xml:36,63`) |
| Uninstall custom firmware | enabled when not connecting/disconnecting; hidden in preview-only | disconnect → flash page `{uninstall, fromOnboarding:false}` (`:941-955`) |

#### 2.10.3 Layout: the mirror

* **Portrait**: rows `auto,*`: the mirror image full width, height = `widthDIPs × 480/640`
  (`main-view-model.ts:228-230`), then the controls area (padding 20) with the Stop-recording
  button below.
* **Landscape**: columns `*,345`: the mirror left, sized to the largest 4:3 box fitting the
  measured cell (re-measured on layout change, handles split screen and IME) (`:240-253`);
  controls in a 345dp column.
* When `displayPreviewMessage` is non-empty, a dark box of the same size replaces the image
  (no reflow) (`main-page.xml:25-31,51-59`). Messages (`dashboard-controller.ts:1019-1040`):
  charging → e.g. "Charging · G2 78%"; silent mode → "Connected (Silent mode enabled)";
  disconnected/connecting with last battery <5 % → "Disconnected (low battery)"; never covered in
  preview mode.
* Mirror source: the Java compositor's retained 640×480 8-bit gray composite (all surfaces,
  including worker apps), converted to ARGB via a palette with brighten gamma 0.7 in white or
  green-on-black (`phone.previewColor`) (`native/kotlin/shared/.../graphics/PreviewPalette.kt`,
  `PreviewBitmapUtil.kt`).
* Refresh policy (`dashboard-controller.ts:2674-2744`): event-driven — every composited frame
  schedules a refresh, floored at 150 ms with a trailing update so the last frame of a burst
  always lands; a 1 s safety poll; **skipped** when no page is attached, when the activity lacks
  window focus, or when the phone screen is not interactive (avoids building 640×480 bitmaps
  nobody sees; the original saw native-heap pressure from this). GIF capture continues
  independently at ≥1 s cadence while recording.

#### 2.10.4 Remote controls (tabbed area under the mirror)

Tab bar: `[Settings] [Watch] [Ring] [🎤] [⌨]` (`remote-controls.xml:22-35`). The selected tab
is remembered for the process lifetime only (default **Watch**) (`remote-controls-view-model.ts:9`).
The whole area collapses while the text-setting editor or keyboard panel is up
(`main-view-model.ts:1205-1207`).

**Settings tab** (`remote-controls.xml:39-52`, `remote-controls-view-model.ts:67-130`):

* "Screen size" button showing the current display mode + " ▾" → action dialog "Display mode"
  listing *Band · 576×288*, *Tall · 576×480*, *Full panel · 640×480* (✓ on current) → writes
  `display.mode`.
* Brightness row: ☀ + slider (2…100, disabled while Auto) + "Auto" switch. Slider values snap to
  the nearest 10 (minimum 2) and write `display.brightness`; the Auto switch toggles between
  `"auto"` and the last manual level (default 50) (`:95-129`).
* Values refresh when changed from the glasses Settings app (settings-change listener,
  `main-view-model.ts:117-121`).

**Watch tab** — a touchpad using the Wear-watch input scheme, origin tagged `"watch"`
(`remote-controls.xml:56-59`, `main-view-model.ts:1080-1160`): a centered line showing what the
next gesture lands on (`glassesDisplayLabel`: "Glasses disconnected" / "Silent mode" /
"Display off" / "Charging · G2 n%" / foreground window title / "Launcher",
`app/g2/glasses-display-state.ts:12-30`) and the legend "▲▼ swipe ● select ●● back ‒ menu".
Pad size: height ≤230dp, width `min(available, 345)` (1.5:1 face) (`main-view-model.ts:263-282`).

**Ring tab** — a portrait 150×≤250dp pad with six cosmetic "touch-strip" markings, using the R1
ring's scheme, origin `"ring"` (`remote-controls.xml:66-78`, `main-view-model.ts:1036-1079`).

Gesture → synthetic input mapping (sent through `dashboardController.injectSyntheticRingInput`):

| Phone gesture | Watch pad (origin watch) | Ring pad (origin ring) |
|---|---|---|
| tap | `click` (ignored while a two-finger touch is in progress) | `click` |
| double-tap | deferred; on finger-up → `double-click` | same |
| double-tap then hold (longPress after doubleTap) | `short-then-long-press`, then `long-press-release` at finger-up | same |
| long press | `long-press-start`; finger-up → `long-press-release` (a real hold, e.g. Glanceboard stays up) | same |
| two-finger tap | `double-click` (back) | — |
| swipe ↑ ↓ ← → | `swipe-up/down/left/right` | ↑ `scroll-up`, ↓ `scroll-down`; ← → ignored |

Implementation note: the platform recognizers report `doubleTap` on the second finger-down and
still run long-press on the same press, so each pad defers its double-click until finger-up and
converts a doubleTap followed by longPress into tap-then-hold (`main-view-model.ts:1023-1035`).
In the controller, a watch-origin `scroll-*`/`short-then-long-press` is ignored while the display
is off; a plain `long-press` is expanded into press + immediate release
(`dashboard-controller.ts:1799-1821`).

**🎤 button** → `injectSyntheticRingInput("wakeword")` (same as saying "Hey Even")
(`main-view-model.ts:1077-1079`). **⌨ button** → `startKeyboardInput()`: opens the keyboard
dialog on the glasses (only when connected/charging or in preview mode, and not while locked)
(`dashboard-controller.ts:1168-1176`).

#### 2.10.5 Touching the mirror

When `phone.mirrorTouch` is on (default true), gestures on the mirror image are sent with
normalized coordinates (`main-view-model.ts:1163-1200`) and handled by
`dashboardController.handleMirrorTouch` (`dashboard-controller.ts:1830-1880`):

* Only while connected/charging or in preview mode.
* Glasses locked: only double-tap passes (as `double-click`).
* Display off: double-tap → `double-click` (wake); tap or hold → `click` (shows Glanceboard).
* Non-tap gestures → watch scheme (`double-click`, `long-press`, `swipe-*`).
* Tap → map to 640×480 lens pixels; if the sidebar strip is visible and x < 64 → focus the window
  under that sidebar point; else if inside the foreground app viewport and the window's
  `hitTest` consumes it → done; otherwise → `click`.

#### 2.10.6 Text-setting editor ("phone as keyboard" for glasses settings)

When a glasses screen edits a string setting (API keys, hosts, addresses, drafts…), the glasses
show "Look at the phone app to type a value." and the phone shows a bottom-anchored panel
(cap 440dp; controls collapse) (`main-page.xml:106-178`, `app/ui/dashboard-settings.ts:1177-1207`):

* Title (`activeTextEditorTitle`); one or two text fields (the second has its own label); keyboard
  type email/text; password → secure entry; no autocorrect/capitalisation; select-all on focus;
  optional labelled toggle switch; Cancel / Submit.
* Colours forced to dark-text-on-white in both themes (`main-page.ts:49-68`).
* Typing writes the setting on every keystroke **without** echoing the value back into the field
  (echoing dropped characters from pasted keys) (`dashboard-controller.ts:1148-1162`).
* IME action: "next" on the first of two fields moves focus; "done"/Submit commits the fields'
  current text and finishes; a validation error shows an alert and keeps the editor open;
  Cancel restores the values captured at start (and the toggle) and closes the glasses editor
  (`main-view-model.ts:956-1015`, `dashboard-controller.ts:2050-2089`).

#### 2.10.7 Keyboard-input panel

The ⌨ button opens a keyboard dialog on the glasses; the phone shows a bottom panel (max 280dp):
"Keyboard input", a multi-line field "Type a message" (Enter inserts a newline), one send button
per destination (at most two; labels from the glasses menu, e.g. assistant and foreground app —
the shared iOS model labels them "Send to Agent" / "Type into App"), and "Discard"
(`keyboard-input-panel.xml:1-18`, `main-view-model.ts:489-575`,
`keyboard-input-view-model.ts:21-26`). Text is mirrored live to the glasses; the panel's text is
local state, cleared whenever a dialog opens or closes; IME focuses automatically.

#### 2.10.8 Warnings ("Attention needed") modal

The ⚠️ icon shows when any warning is active; tapping opens a centered scrollable modal (width
`min(screen−32, 480)`) with a Close button; it auto-closes when the last warning clears
(`main-page.xml:75-105`, `main-view-model.ts:758-795`).

| Warning | Condition | Button → action |
|---|---|---|
| Even app conflict | the stock Even app's persistent notification ("Even Notification Service") is active (needs notification access) | "Open Even app settings" (App-info of `com.even.sg`) (`dashboard-controller.ts:1124-1146,164-165`) |
| Incompatible firmware | firmware info from the session reports a non-matching CFW | "Install custom firmware" → disconnect → flash page install (`dashboard-controller.ts:1484-1509`) |
| Battery optimization | not exempt | "Allow unrestricted background usage" → system dialog; re-poll every 5 s ×12 (`dashboard-controller.ts:1059-1078`) |
| G2 fonts missing | glasses paired but EvenHub font extraction never done | "Prepare G2 fonts" → disconnect, lift suppression → firmware check page (`main-view-model.ts:716-727`, `dashboard-controller.ts:1086-1102`) |
| Alarm reliability | Timers engine has something armed and the phone self-check found issues | "Open phone settings" → system screen for the most serious fixable issue; re-poll 5 s ×12 (`dashboard-controller.ts:1104-1122`, §3.7) |

#### 2.10.9 Developer BLE-bandwidth overlay

If `developer.showBleBandwidth` is on, a small grey label overlays the bottom edge on every tab,
polled at 1 Hz from Java counters: "BLE sent: N messages, N bytes · X kB/s, Y fps, Z B/frame"
over a 5 s window (`main-view-model.ts:1209-1262`, `app/phone-ui/ble-bandwidth-meter.ts`,
`app/native/ble-traffic.ts`).

### 2.11 Conversations (saved caption sessions)

Despite the name, "Conversations" is **not** the assistant chat history: it is the review UI for
caption sessions recorded by the glasses' Microphones app (captions + speaker identification),
stored in SQLite (§6.1). The assistant's own history lives in the settings key
`assistant.conversations` and has no phone UI.

Conversations list (`app/phone-ui/conversations-page.xml`, `conversations-view-model.ts`):

* Action bar "Conversations" with actions **Ask AI** (→ ask page) and **Speakers**.
* Search bar (hint "Search (e.g. lunch past 2 days, angry)"); submit runs a query, clear resets.
  The query is parsed into structured filters (`app/phone-ui/conversation-search.ts`): relative
  ranges "past/last N hours|days|weeks|months" (N may be a word, "a", "couple", "few"),
  "today", "yesterday" (bracketed day), "this week", "this month"; one inferred emotion word; the
  remaining content words become a LIKE text query; filler words are stripped only when a filter
  matched.
* Horizontal speaker chips (colour = speaker colour) act as a single-select speaker filter.
* Rows: title (session title or start date/time), a sentiment dot coloured by average sentiment
  bucket, meta "date · mm:ss · N lines", and speaker chips; tap → conversation detail. Empty
  states: "No conversations yet — enable Captions + Save captions in the Microphones app on the
  glasses." / "No conversations match this search." (`conversation-format.ts:48-49,72-78`).
* Reloads on every `navigatingTo` so edits made elsewhere show.

Conversation detail (`conversation-page.xml`, `conversation-view-model.ts`):

* Title = session title or date; header date and meta "duration · N lines · speakers · (no
  recording)".
* A 120dp sentiment trend graph drawn into a bitmap (zero line, score polyline, emotion-coloured
  dots) when there are ≥2 lines (`conversation-view-model.ts:222-268`).
* Transcript rows: bold coloured "Speaker: " + text, optional translation, time, emotion chip
  (non-neutral). Tap a row → play the session recording from that line's audio offset (or
  wall-clock delta); tap again toggles pause; the playing row is highlighted with ⏸/▶
  (`:275-352`). Long-press → "Reassign speaker" picker (`:444-466`).
* Overflow: **Re-diarize** (offline re-clustering of voices with progress % → summary "N voices,
  M lines reassigned, K new speakers"), **Split speaker…** (move one speaker's lines in this
  session to a new profile, with confirm), **Delete conversation** (confirm; deletes rows and the
  recording file) (`:370-442`).
* The MediaPlayer is released on `unloaded`.

### 2.12 Speakers

Action bar "Speakers"; rows: colour swatch, name, tag chip, "my voice" badge, meta "Last heard …
· N lines" (`speakers-page.xml`, `speakers-view-model.ts`). Tap → action sheet: **View insights**
(alert with last heard, last-conversation recap, action items, facts — generated on-device after
a captioned conversation when the local LLM is downloaded), **Rename**, **Set tag**, **Set color**
(10 presets Sky/Amber/Green/Red/Purple/Yellow/Teal/Pink/Brown/Gray), **Set as my voice**
(exactly one wearer profile), **Merge into…** (confirm; moves lines and blends voice-prints),
**View conversations** (conversations list filtered to that speaker), **Delete** (lines keep text
but lose the label) (`speakers-view-model.ts:17-230`).

### 2.13 Ask about conversations

Action bar "Ask about conversations"; a question field (IME "go"), an **Ask** button ("Thinking…"
while busy), a status line, the streaming answer, and a context note
(`ask-page.xml`, `ask-view-model.ts`). Answers come only from the on-device LLM with
retrieval over the conversation store (keyword/date/emotion filters, ≤7000 chars of context)
(`app/apps/microphones/conversation-qa.ts:17-22`). If the local model is not downloaded, the page
explains how to enable it on the glasses (Settings > Assistant). Leaving the page cancels the
stream.

### 2.14 Document page

Displays a bundled text document (`about/LICENSE`, `about/PRIVACY`, …) in a scroll view (13sp)
with a back navigation button; missing files show "(<name> is missing from this build)"
(`document-page.xml`, `document-page.ts:19-26`). The build copies `README.md`, `LICENSE`,
`PRIVACY`, `ACKNOWLEDGEMENTS.md` into `about/` (`webpack.config.js:22-29`).

### 2.15 Glasses-side settings that affect the phone UI

There is **no phone settings screen**; all user settings are edited in the glasses Settings app
(using the phone as a keyboard for text values). The "Phone display" section there holds the
phone-UI settings (`app/ui/dashboard/settings-menus.ts:190-199`): Rotation (`phone.rotation`),
Preview color (`phone.previewColor`), Touch mirror (`phone.mirrorTouch`). Other sections: Display,
Voice, Assistant, API Keys (incl. input tokens), Terminal, Navigate, Watch, Developer, About, Quit
(`settings-menus.ts:98-249`).

---

## 3. Android components

### 3.1 Manifest: permissions and why

All from `App_Resources/Android/src/main/AndroidManifest.xml` (line numbers in the second column).

| Permission | Line | maxSdk / flags | Why / who uses it | How obtained |
|---|---|---|---|---|
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` | 11-12 | — | Legacy storage for the glasses Files/Teleprompter apps on older Android | runtime (legacy) |
| `MANAGE_EXTERNAL_STORAGE` | 15 | — | "All files access" for the glasses Files app browsing arbitrary paths | Settings page `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` opened from the Files app (`app/native/file-access.ts:25-44`) |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | 16-18 | — | Cloud STT/LLM APIs, agent bridge websocket, g2mirror, model/firmware downloads, EvenHub; Wi-Fi status icon | normal |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | 19-20 | maxSdk 30 | Legacy BLE | normal |
| `ACCESS_FINE_LOCATION` | 23 | — | Navigate (precise GPS + bearing); BLE scanning on API ≤30 | runtime |
| `ACCESS_COARSE_LOCATION` | 24 | — | Weather and compass declination (coarse only) | runtime |
| `BLUETOOTH_CONNECT` | 25 | — | GATT to both lenses (+ optional ring) | runtime |
| `BLUETOOTH_SCAN` | 26 | `usesPermissionFlags="neverForLocation"` | Pairing scan | runtime |
| `POST_NOTIFICATIONS` | 27 | — | Foreground-service notification; alarm notifications | runtime (API ≥33) |
| `SCHEDULE_EXACT_ALARM` | 33 | maxSdk 32 | Exact alarms on Android 12 | special access |
| `USE_EXACT_ALARM` | 34 | — | Exact alarms on 13+ (alarm-clock apps) | install-time |
| `RECEIVE_BOOT_COMPLETED` | 35 | — | Re-arm alarms after reboot | normal |
| `VIBRATE` | 36 | — | Alarm vibration | normal |
| `USE_FULL_SCREEN_INTENT` | 37 | — | Ringing alarm activity over the lock screen | normal / special on 14+ |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 38 | — | `FaceclawAlarmService` type `mediaPlayback` | normal |
| `READ_CALENDAR` | 39 | — | Calendar app / Glanceboard widget | runtime |
| `RECORD_AUDIO` | 40 | — | Consent gate for voice input (the audio actually comes from the glasses mic over BLE, or the phone mic in preview mode); enables FGS type `microphone` | runtime |
| `MODIFY_AUDIO_SETTINGS` | 41 | — | Media volume control from the glasses, alarm-stream volume raise, audio effects | normal |
| `FOREGROUND_SERVICE` | 42 | — | FGS | normal |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 43 | — | Main FGS type | normal (needs a BT runtime perm on 14+) |
| `FOREGROUND_SERVICE_MICROPHONE` | 44 | — | Main FGS optional type | normal |
| `FOREGROUND_SERVICE_LOCATION` | 45 | — | Main FGS optional type (Navigate with screen locked) | normal |
| `WAKE_LOCK` | 46 | — | G2-screen and alarm partial wake locks | normal |
| `QUERY_ALL_PACKAGES` | 50 | — | Load other apps' notification small icons (`Icon.loadDrawable` fails for invisible packages); also app labels, media-browser service discovery, Even-app detection | normal (Play-restricted; sideloaded app) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 54 | — | Show the system Doze-exemption dialog directly | normal (Play-restricted) |

Deliberately **not** requested: `CAMERA` (QR scanning happens inside Play services, §3.13),
`READ_PHONE_STATE` (the cellular icon is skipped instead, `FaceclawSystemStatusIconProvider.kt:79-82`),
`ACCESS_BACKGROUND_LOCATION` (the location FGS type keeps while-in-use access flowing).
There is no `<queries>` element; package visibility relies on `QUERY_ALL_PACKAGES`.

### 3.2 Manifest: application, activities, services, receivers, provider

| Element | Key attributes | Purpose | Lines |
|---|---|---|---|
| `<application>` | `allowBackup="true"` (no backup rules), `usesCleartextTraffic="true"` (g2mirror uses plain `ws://` over a tailnet), `hardwareAccelerated`, label "Faceclaw" | — | 59-66, `res/values/strings.xml` |
| meta-data `com.google.mlkit.vision.DEPENDENCIES=barcode_ui` | — | Ask Play services to install the barcode-scanner module at install time | 71-73 |
| `androidx.core.content.FileProvider` | authority `${applicationId}.fileprovider`, not exported, grants URIs; paths: `cache-path name=privacy_policies path=privacy-policies/` | Hand cached EvenHub privacy-policy documents to system viewers (`app/apps/evenhub/privacy-policy.ts:90-103`) | 76-84, `res/xml/file_provider_paths.xml` |
| `com.tns.NativeScriptActivity` | `launchMode=singleTask`, exported, `configChanges=keyboard\|keyboardHidden\|orientation\|screenSize\|smallestScreenSize\|screenLayout\|locale\|uiMode` (never recreated), `windowSoftInputMode=adjustResize`, launch theme then `AppTheme`; filters `MAIN/LAUNCHER` and `SEND` + `text/plain` | The phone UI | 86-107 |
| `com.tns.ErrorReportActivity` | — | NativeScript crash screen (debug) | 108 |
| `FaceclawForegroundService` | not exported; `foregroundServiceType="connectedDevice\|microphone\|location"` | Keeps the process alive while connected (§3.3) | 109-113 |
| `FaceclawMediaNotificationListenerService` | not exported; `permission=BIND_NOTIFICATION_LISTENER_SERVICE`; filter `android.service.notification.NotificationListenerService` | Notification mirror + media-session access (§3.4) | 114-123 |
| `FaceclawWearListenerService` | exported; filters `MESSAGE_RECEIVED` (`wear://*/faceclaw…`) and `CAPABILITY_CHANGED` | Play services wakes the app for watch messages (§3.12) | 127-139 |
| `FaceclawAlarmReceiver` | not exported | AlarmManager target | 144-147 |
| `FaceclawAlarmRescheduleReceiver` | exported; `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `QUICKBOOT_POWERON` (+ HTC variant) | Re-arm alarms | 148-160 |
| `FaceclawAlarmService` | not exported; `foregroundServiceType="mediaPlayback"` | Ringing service | 161-165 |
| `FaceclawAlarmActivity` | not exported, `excludeFromRecents`, `singleInstance`, `showWhenLocked`, `turnScreenOn`, `Theme.Material.NoActionBar` | Lock-screen ringing UI | 166-173 |
| `FaceclawSettingsPortReceiver` | exported but `permission="android.permission.DUMP"` (only the adb shell holds it) | adb settings export/import (§5.4) | 178-182 |
| `res/values/wear.xml` | string-array `android_wear_capabilities` = `faceclaw_phone` | Lets the watch app find the phone | — |

### 3.3 Foreground service and staying alive with the phone locked

**Service** — `FaceclawForegroundService.kt`:

* Intents: `com.faceclaw.app.action.START|UPDATE|STOP`, extra `text` (`:21-24`). A null intent
  (sticky restart) is treated as START (`:40`).
* STOP → `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`, `START_NOT_STICKY` (`:43-47`).
  START/UPDATE → ensure channel, build notification, `startForeground(4201, n, types)`; UPDATE
  also `notify()`; returns `START_STICKY` (`:49-65`).
* Channel `faceclaw-connection` "Glasses connection", `IMPORTANCE_LOW`, description "Keeps
  Faceclaw connected to the glasses.", `setShowBadge(false)`; the legacy channel
  `faceclaw-dashboard` is deleted because a channel's badge flag is frozen at creation and
  Samsung's launcher showed a red "1" (`:26-32,68-88`).
* Notification: title "Faceclaw", text from the intent (default "Connected to glasses"), app icon,
  ongoing, only-alert-once, content intent → main activity with `SINGLE_TOP|CLEAR_TOP`, immutable
  PendingIntent (`:90-114`).
* Types (API ≥29): always `CONNECTED_DEVICE`; `| MICROPHONE` if `RECORD_AUDIO` is granted;
  `| LOCATION` if `ACCESS_FINE_LOCATION` is granted — claiming a type without its permission
  makes `startForeground` throw on API 34+ (`:116-130`). Types are re-evaluated on every
  START/UPDATE.

**Who drives it** (`app/native/foreground-service.ts:21-38`, `dashboard-controller.ts`):

| Event | Call | Source |
|---|---|---|
| `connect()` after BLE permissions pass | `startForegroundService(START, "Connecting to the glasses")` | `dashboard-controller.ts:1340-1341` |
| Connected, every 60 s shell-refresh tick, rate-limited to one per 30 s | `startService(UPDATE, "Connected")` | `:1548-1553,2645-2651,153` |
| `disconnect()` completes, or `connect()` fails | `startService(STOP)` | `:1784,1598` |

Preview-only mode never starts the service. The service holds no logic of its own: the BLE
worker thread, JS runtime and timers simply stay alive because the process is foreground.

**G2-screen wake lock** — a non-reference-counted `PARTIAL_WAKE_LOCK` tagged
`Faceclaw:G2Screen`, acquired when the glasses display turns on and released when it turns off,
when the glasses start charging, and on disconnect (`FaceclawBleCommunicator.kt:26,371-390`;
`GlassesSessionCore.kt:406,421-423`; `dashboard-controller.ts:596,735,1378`). Rationale: with the
phone unplugged, still and screen-off (deep Doze) the CPU would otherwise suspend between BLE
packets and render timers would drop to a few fps; Doze ignores wake locks of apps that are not
exempt from battery optimization, hence the required "Battery Optimization" permission card and
warning (`app/native/battery-optimization.ts:5-12`). With the display off, no wake lock is held
(power management relies on the glasses sleeping and waking on double-tap/wakeword).

**Battery-optimization exemption** — checked with `PowerManager.isIgnoringBatteryOptimizations`;
requested with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI (a one-tap system
dialog). After requesting from the main page, the controller re-polls every 5 s for up to 60 s to
clear the warning (`dashboard-controller.ts:1067-1078`).

**Phone lock tracking** — the communicator registers a runtime receiver for `SCREEN_ON`,
`SCREEN_OFF`, `USER_PRESENT`; each signal re-reads `KeyguardManager.isDeviceLocked` and emits
`onPhoneLockState` on change (`FaceclawBleCommunicator.kt:63,106-123`). Glasses-lock policy
(`dashboard-controller.ts:615-658`): lock when the glasses are taken off while the phone is
locked, or when the phone locks while the glasses are off-head (only if `display.lockScreenEnabled`);
unlock when the phone unlocks (the watch may also unlock if `watch.canUnlock`).

**Other keep-alive dependencies** — EvenHub WebViews keep running only because the main Looper
keeps ticking under the FGS (§3.14); the alarm subsystem is designed to work with **no** FGS and no
JS (§3.6).

### 3.4 NotificationListenerService (`FaceclawMediaNotificationListenerService.kt`)

Pattern: the bound service instance publishes itself into a static `activeService` in
`onCreate`/`onListenerConnected` and clears it in `onDestroy`/`onListenerDisconnected`; every API
is a `@JvmStatic` function that operates on that instance, so callers never bind
(`:35-39,622-645`). Without the user's grant the service never binds and every query returns
empty.

Static API used by TS (`app/native/notification-icons.ts`):

| Function | Behaviour | Lines |
|---|---|---|
| `getActiveNotificationsJson(max)` | Active notifications sorted newest-first by `postTime`, filtered (below), as a JSON array | 170-215 |
| `getActiveNotificationIconGrays(size≤96, max)` | Concatenated `size×size` 8-bit gray icons for the top-bar strip; skips group summaries; one icon per group key | 90-142 |
| `getNotificationIconGrayForKey(key, size)` | One icon for a notification (empty if gone) | 150-168 |
| `invokeNotificationAction(key, index)` | `actions[index].actionIntent.send()` — **no RemoteInput results are filled**, so "reply" actions cannot carry text | 217-242 |
| `dismissNotification(key)` | `cancelNotification(key)` | 244-260 |
| `hasActiveNotificationTitle(title)` | Used to detect the stock Even app's persistent notification | 55-88 |
| `add/removeNotificationListener(FaceclawNotificationListener)` | `onNotificationPosted(key)` posted to the main Looper | 41-53,262-275 |

Notification JSON (`:517-574`): `key`, `packageName`, `appName` (the `android.substName` extra if
present, else the app label), `postTime`, `when`, `category`, `title` (`EXTRA_TITLE_BIG` else
`EXTRA_TITLE`), `text`, `bigText`, `subText`, `infoText`, `summaryText`, `lines[]`
(`EXTRA_TEXT_LINES`, non-empty only), `actions[]` = `{index, title, enabled}` (actions with empty
titles skipped; `enabled` = has a PendingIntent). TS normalises every field to a string/number
and records each seen app in `notifications.sources` for per-app muting
(`app/native/notification-sources.ts:12-58`).

Filtering (`:337-378`): exclude Faceclaw's own notifications (FGS and alarm notifications would
otherwise stack a modal over the ringing screen), `CATEGORY_TRANSPORT`, anything with an
`android.mediaSession` extra, and anything whose ranking importance is `IMPORTANCE_MIN` or lower.

Posted-event de-duplication (`:277-326,647-661`): an in-memory set of active keys, rebuilt on
listener connect; `onNotificationPosted` emits for new keys and for updates of ordinary
notifications, but **not** for updates of already-active ongoing/no-clear notifications
(progress bars, media, navigation). Removal is not reported to JS; the TS side invalidates caches
and re-reads on demand.

Icon pipeline (`:393-468`): prefer the small icon, `mutate()` + tint white (status-bar icons are
alpha templates; some apps ship noise in the colour channels), else the large icon in colour;
render at intrinsic size, halve repeatedly while ≥2× target, then scale to target (a single
filtered downscale turned avatars into speckle); gray = Rec.709 luminance × alpha, then gamma
1.6. A debug copy of every strip icon is written to
`<externalFilesDir>/debug-icons/icon-<i>-<pkg>.png` (`:470-493`).

TS caching (`notification-icons.ts:8-130`): 24 px icons; strip cache with a 60 s backstop TTL,
invalidated eagerly on post/dismiss/action; per-key cache (LRU, 128 entries); an `allowStale`
read never blocks the render path (the fetch costs ~40–100 ms of Java rasterisation) and asks the
caller to repaint once fresh data is fetched.

Controller reaction to a post (`dashboard-controller.ts:2561-2576`): read the notification; if its
app is not muted, wake the glasses display (sidebar focus) when it was off and open a
notification modal over the app viewport.

Access check: `Settings.Secure "enabled_notification_listeners"` contains the component (full or
short name) or the package name (`FaceclawEvenAppDetector.kt:19-38`).

### 3.5 Media: controller, app filtering, browser

**`FaceclawMediaController.kt`** (one per process, created lazily by
`app/native/media-controller.ts`):

* Uses `MediaSessionManager.getActiveSessions(listenerComponent)` — requires the
  notification-listener grant. The active-sessions listener is registered only while the grant
  exists and a `ContentObserver` on `enabled_notification_listeners` re-syncs it when the user
  grants/revokes access later (`:73-87,108-150`).
* Chooses the active controller: the first non-ignored session that is `PLAYING`, else the first
  non-ignored one (`:361-375`); registers a `MediaController.Callback` on it (playback, metadata,
  queue, destroyed).
* Emits on the main Looper: `onSessionAppsChanged([{packageName, appName}])` for discovery and
  `onStateChange(playbackState, packageName, appName, title, artist, album, positionMs,
  durationMs, playbackSpeed, canPlayPause, canSkipNext, canSkipPrevious, accessEnabled, status)`
  where playbackState ∈ `notification-access-required|idle|playing|paused|buffering|stopped`,
  position is extrapolated from `lastPositionUpdateTime × speed`, duration −1 when unknown
  (`:377-540,542-555`).
* Commands: `playPause` (pause when playing/buffering/connecting, else play), `skipNext`,
  `skipPrevious`, `skipToQueueItem(id)`; `get/setMediaVolumePercent` (0–100 of `STREAM_MUSIC`);
  `getAlbumArtGray(maxSize, gamma, dither)` (ALBUM_ART → ART → DISPLAY_ICON, gray packet);
  `getQueueJson()` → `[{id, title, active}]`; `openNotificationAccessSettings()`
  (`:190-335`).
* `setIgnoredPackagesJson([...])` applies the filter and immediately re-chooses (`:158-172`).

**Media-app filtering** (`app/native/media-apps.ts`): built-in ignore list of browsers, social
and messaging apps that publish sessions for embedded clips (`com.android.chrome`,
`org.mozilla.firefox`, `com.microsoft.emmx`, `com.sec.android.app.sbrowser`,
`com.facebook.katana`, `com.facebook.orca`, `com.instagram.android`, `com.whatsapp`,
`org.telegram.messenger`, `com.snapchat.android`, `com.reddit.frontpage`,
`com.twitter.android`, `com.discord`) (`:7-21`). Discovered apps are remembered in `music.apps`
(`[{packageName, appName, enabled?}]`); the effective ignore set = defaults − explicitly enabled
+ explicitly disabled (`:65-72`); pushed to Kotlin at creation and on every change of that key
(`media-controller.ts:176-184`). TS also extrapolates position at read time.

**`FaceclawMediaBrowser.kt`** — client for other apps' `MediaBrowserService` (what Android Auto
uses); binding starts the player's process, so libraries can be browsed and played with the phone
locked (`:21-30`):

* `listBrowsableAppsJson()` — `queryIntentServices("android.media.browse.MediaBrowserService")`,
  `[{packageName, serviceClass, appName}]` sorted by name (`:58-90`).
* `connect(requestId, package, serviceClass)` — single connection, 15 s timeout, generation
  counter guards stale callbacks → `onConnectResult(requestId, ok, rootId, error)`;
  `onConnectionSuspended` → `onDisconnected()` (`:92-162`).
* `browse(requestId, parentId)` — subscribe, take the first `onChildrenLoaded`, unsubscribe,
  15 s timeout → `onBrowseResult(requestId, json [{mediaId,title,subtitle,browsable,playable}],
  error)` (`:164-227`).
* `playFromMediaId(id)` via a `MediaController` on the browser's session token; `disconnect()`
  keeps the binding alive 5 s after a play command so the player is not killed before it promotes
  itself to a foreground service (`:38-41,229-266`).
* All MediaBrowser calls run on the main thread; results on the main thread.

### 3.6 Alarms (Timers app) — works without JS

Components: `FaceclawAlarms` (static facade), `FaceclawAlarmReceiver`,
`FaceclawAlarmRescheduleReceiver`, `FaceclawAlarmService` (ringing FGS), `FaceclawAlarmActivity`,
and the shared KMP cores `AlarmSchedule` + `GlassesStatusGate` + `AlarmRingingPolicy`
(`native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/alarms/`). TS wrapper:
`app/native/alarms.ts`; caller: `app/apps/timer/timer-engine.ts`.

Storage: **device-protected** SharedPreferences `faceclaw-alarms` (readable before first unlock
after reboot) with keys `scheduled` (JSON array of `{id, at, title, text, kind, snoozeMinutes}`),
`journal` (phone-side actions), `log` (≤60 lines) (`FaceclawAlarms.kt:53,71-105`,
`AlarmSchedule.kt:175-183`). Ids are the JS engine's item ids (ms epoch × 100 + serial); `kind`
∈ `timer|alarm` only changes wording.

Flows:

1. **Schedule** `schedule(ctx, id, triggerAtMs, title, text, kind, snoozeMinutes)`: persist, then
   `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent→main activity), broadcast PI)`
   (exact, fires through Doze, shows the system alarm icon); on `SecurityException` (exact alarms
   revoked) fall back to `setAndAllowWhileIdle`. PendingIntents use request code
   `(id xor (id ushr 32)) & 0x7fffffff` and `FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE`, so a reschedule
   replaces rather than duplicates (`FaceclawAlarms.kt:120-155,435-463`, `AlarmSchedule.kt:191-193`).
2. **Cancel** cancels the PI, removes the entry, stops ringing (`:157-167`).
3. **Due**: receiver → `FaceclawAlarmService.ring()` via `startForegroundService` (`ACTION_RING`
   + extras). The JS engine may also ring on its own timeout (`FaceclawAlarms.ring`, which cancels
   the AlarmManager alarm); the policy de-duplicates per id (`FaceclawAlarmReceiver.kt`,
   `FaceclawAlarms.kt:209-218`, `AlarmRingingPolicy.kt:129-167`).
4. **Escalation policy** (`AlarmRingingPolicy.kt:58-206,266-273`): every ringing item starts as a
   *silent* phone notification (the glasses ring). It escalates to phone sound + vibration when
   the glasses cannot carry it (no fresh status in the last 3 min, or not connected / not worn /
   charging), when JS has not confirmed delivery to the glasses within **5 s**, or when the wearer
   has not acknowledged within **30 s** of delivery. Sound auto-silences after **10 min**
   (notification stays, "Not answered"). Delivery/stop signals that arrive before the ring
   request are kept for 15 s.
5. **Notification** (`FaceclawAlarmService.kt:308-372`): channel `faceclaw-alarms` "Alarms",
   `IMPORTANCE_HIGH`, **silent** (no sound, no vibration — the service plays the sound itself),
   bypass DND, public on the lock screen; category ALARM, ongoing, full-screen intent →
   `FaceclawAlarmActivity`; text "<text> · Ringing on the glasses|Ringing|Not answered"; actions
   "Snooze N min" (alarms) / "+N min" (timers) and "Dismiss" (foreground-service PendingIntents).
   Notification id `0x41000000 | (requestCode & 0xffffff)`; the FGS notification is re-promoted to
   another ringing item when the current one ends (`:133-135,278-306`).
6. **Sound** (`:377-507`): looping `MediaPlayer` of the default alarm ringtone (fallback: ringtone)
   with `USAGE_ALARM`; transient audio focus; if the alarm stream is muted, raise it to 60 % for
   the duration and restore afterwards; vibration waveform `[0,600,400,600,1200]` repeating;
   partial wake lock `faceclaw:alarm` bounded to 10 min + 45 s (`:512-523`).
7. **Phone actions** (notification buttons or the activity): Dismiss → journal + drop schedule;
   Snooze → journal + re-arm on the phone directly so it holds with JS gone. Each action is
   appended to the `journal` and, if a JS listener is registered, delivered live on the main
   Looper (`FaceclawAlarms.kt:257-280`). The Timers engine drains the journal at boot before
   re-evaluating (`timer-engine.ts:142-146`).
8. **Glasses status**: JS pushes `(connected, worn, charging)` at boot, on every presence change
   and every 60 s (`timer-engine.ts:72,147-150,170-173`); `deliveredToGlasses(id)` starts the ack
   clock; `acknowledge(id)` (dismiss/snooze on the glasses) stops the phone quietly.
9. **Reschedule receiver**: on boot / package replaced / time or zone change / quick-boot, replay
   the schedule: past-due entries ring if ≤5 min late, older ones are dropped and logged as
   missed, future ones re-armed (`FaceclawAlarmRescheduleReceiver.kt`, `AlarmSchedule.kt:83-106`).
10. **Ringing activity** (`FaceclawAlarmActivity.kt`): shown over the lock screen, turns the screen
    on, requests keyguard dismissal, keeps the screen on; black UI with large time (64sp), title
    (+ "(+N more)"), text/status, 64dp "Snooze N min"/"+N min" and "Dismiss" buttons that apply to
    **every** ringing item; polls the service snapshot every second and finishes when nothing rings.
11. **Reliability self-check** `checkReliability()` → `[{code, message, fixable}]`, most serious
    first (`FaceclawAlarms.kt:285-350`), and `openReliabilityFix(code)` (`:364-414`):

| Code | Condition | Fix screen |
|---|---|---|
| `exact-alarm` | API ≥31 and `!canScheduleExactAlarms()` | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| `notifications` | notifications disabled for the app | `ACTION_APP_NOTIFICATION_SETTINGS` |
| `alarm-channel` | alarm channel importance NONE | `ACTION_CHANNEL_NOTIFICATION_SETTINGS` |
| `full-screen` | API ≥34 and `!canUseFullScreenIntent()` | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` |
| `dnd-total` / `dnd-alarms` | DND total silence / priority mode without alarms | `ACTION_SOUND_SETTINGS` |
| `background-restricted` | `ActivityManager.isBackgroundRestricted` | App details |
| `battery-optimized` | not exempt | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `alarm-volume` | alarm stream volume 0 | `ACTION_SOUND_SETTINGS` |
| `oem-killer` | manufacturer ∈ xiaomi, huawei, oppo, vivo, oneplus, realme, meizu, asus | App details (dontkillmyapp.com hint) |
| `schedule-stale` (not fixable) | soonest stored entry ≠ `AlarmManager.nextAlarmClock` (e.g. force-stop cleared alarms) | auto re-arms |

    The Timers engine re-checks every 5 min and on changes; the main page shows the first issue
    only while something is armed (`timer-engine.ts:205-247`).

### 3.7 Calendar provider

`FaceclawCalendarProvider.getUpcomingEventsJson(context, maxEvents, windowMs)` queries
`CalendarContract.Instances` (recurrences expanded) for `[now, now+window]`, ordered by begin,
capped at `min(200, max)`; each event `{id (EVENT_ID), title, startMs, endMs, allDay, location,
calendarName}`; `SecurityException` → `[]` (`FaceclawCalendarProvider.kt:24-94`). TS
(`app/native/calendar.ts`): default 50 events / 14 days, 30 s cache, returns `[]` without
`READ_CALENDAR`, `invalidateCalendarCache()` after a grant. Read synchronously on the JS thread.

### 3.8 Location

| | `FaceclawLocationProvider` (one-shot) | `FaceclawLocationTracker` (stream) |
|---|---|---|
| Users | Weather, compass declination | Navigate, EvenHub location API |
| Permission | coarse or fine | fine ("Precise location permission is required for navigation.") |
| Strategy | newest cached fix across enabled providers if ≤10 min old; else `requestSingleUpdate` on NETWORK (or best coarse/low-power) with 15 s timeout; timeout falls back to a cached fix ≤24 h | GPS if enabled else NETWORK; `requestLocationUpdates(max(500, interval), 0 m)`; seed with a cached fix only if ≤60 s old measured with `elapsedRealtimeNanos` (a stale seed started routes at the previous destination) |
| Errors | "Location permission is required.", "Turn on Location on your phone, then retry.", "Couldn't get your current location. Tap to retry." | "Turn on Location…", "Location was turned off on the phone." |
| Callback thread | main Looper | Looper of the constructing thread (works from worker isolates) |
| Output | lat, lon, accuracy (−1 unknown), time | lat, lon, accuracy, bearing, speed (−1 unknown → TS null), time |
| Source | `FaceclawLocationProvider.kt:21-188` | `FaceclawLocationTracker.kt:33-184`, `app/native/location-tracker.ts` |

Background delivery while the phone is locked relies on the FGS `location` type (§3.3).

### 3.9 System status icons and phone battery

* `FaceclawSystemStatusIconProvider.getSystemStatusIconGrays(ctx, size≤96)` → concatenated gray
  icons, in order: Wi-Fi level 0–4 (only when the active network is Wi-Fi and RSSI ∈ (−127,0)),
  cellular level 0–4 (API ≥28; `SecurityException` swallowed), hotspot (reflection
  `getWifiApState` ∈ {12,13}) (`FaceclawSystemStatusIconProvider.kt:22-103`). Art is the shared
  `StatusIconArt`. TS: 24 px, 5 s cache (`app/native/system-status-icons.ts`).
* Phone battery: sticky `ACTION_BATTERY_CHANGED` → percent and charging/full
  (`app/native/phone-battery.ts:28-44`).

### 3.10 Share intents and phone rotation

* **Share**: `SEND` + `text/plain` filter on the singleTask activity; handled from the launch
  intent and from `onNewIntent` (`app/native/share-intents.ts:5-24`). Accepts `text/*` (or null
  type): `EXTRA_TEXT`, else `EXTRA_STREAM` read as UTF-8 through the ContentResolver; capped at
  200 000 chars (`:3,26-93`). Result → `dashboardController.openSharedTextDocument(text)`: wake
  the glasses display if off and open the first app that declares `openSharedText` as a new
  document window titled "Shared text" (`dashboard-controller.ts:1882-1893`).
* **Rotation**: `phone.rotation` ∈ `auto|portrait|landscape` →
  `setRequestedOrientation(UNSPECIFIED|PORTRAIT|SENSOR_LANDSCAPE)`, applied on activity
  create/resume and on setting change (`app/native/phone-rotation.ts:5-30`).

### 3.11 Settings port receiver

adb-only export/import of the settings file on release builds where `run-as` is unavailable;
described with the scripts in §5.4 (`FaceclawSettingsPortReceiver.kt`).

### 3.12 Wear OS bridge (brief)

* `FaceclawWearListenerService` (started by Play services, even when the app is not running)
  forwards `onMessageReceived`/`onCapabilityChanged` to the `FaceclawWearBridge` singleton
  (`FaceclawWearListenerService.kt:15-23`).
* The bridge is the only code touching the Wearable Data Layer; JS sees only `(path, json,
  nodeId)` strings, delivered on the thread that registered the listener. Messages arriving
  before JS registers are acked with `jsReady=false` (watch shows "Open Faceclaw on the phone");
  only idempotent state requests are queued for replay (≤16, ≤30 s old); gestures are never
  replayed late (`FaceclawWearBridge.kt:27-49,73-74,291-324`).
* Outbound: Data item `/faceclaw/state` (keys `json`, `updatedAt`; delivered even if the watch is
  out of range), messages `/faceclaw/ack`, `/faceclaw/event`, `/faceclaw/battery/request`.
  Everything is a no-op without Play services (`:130,209-290`).
* Both apps must share `applicationId` `com.faceclaw.app` **and** signing key; capabilities
  `faceclaw_phone` (phone) / `faceclaw_watch` (watch). Full message table: `wear/PROTOCOL.md`;
  semantics in `app/g2/wear-remote.ts` (other spec). Phone-side settings: `watch.*` (§5.6).

### 3.13 QR scanner

* Primary: Play services ML Kit code scanner (`GmsBarcodeScanning`, QR only, auto-zoom) — the
  scanner UI and camera live in Play services, so no `CAMERA` permission; availability =
  `GoogleApiAvailability == SUCCESS`; exactly one of `onResult/onCancelled/onError` fires on the
  main thread (`FaceclawQrScanner.kt:17-72`).
* Fallback: the ZXing `com.google.zxing.client.android.SCAN` intent via
  `startActivityForResult` (request code `0x51d0`) when some installed app handles it
  (`app/native/qr-scan.ts:21-132`). Used by the Developer app's "Load app from QR code".

### 3.14 EvenHub WebView hosting (brief; see EvenHub spec)

* App WebViews live in one full-screen `FrameLayout` inserted as the **first** child of the
  activity's content view — behind the phone UI, full-size and VISIBLE but occluded — so Chromium
  keeps painting; raised to the front to show an app's UI on the phone
  (`FaceclawEvenHubWebViewHost.kt:14-57`).
* `FaceclawEvenHubWebView` overrides `onWindowVisibilityChanged` to always report VISIBLE so the
  page is never frozen when the activity stops; renderer priority IMPORTANT
  (`FaceclawEvenHubWebView.kt:7-35`).
* A document-start shim replaces `setTimeout/setInterval/requestAnimationFrame` with queues the
  host ticks via `evaluateJavascript` on the main Looper at a fixed rate (not subject to
  Chromium's background throttling); works with the screen off because the FGS keeps the Looper
  alive. Packaged apps are served offline from a fake per-app https origin with the shim spliced
  into HTML; remote URLs use `WebViewCompat.addDocumentStartJavaScript` (WebView ≥83) with an
  `onPageStarted` fallback (`FaceclawEvenHubWebViewClient.kt`, `FaceclawEvenHubDocumentStart.kt`).
* JS↔host: `window.__faceclawEvenHub` (a `@JavascriptInterface`) wrapped as
  `window.flutter_inappwebview.callHandler`; calls bounced to the main thread
  (`FaceclawEvenHubJsBridge.kt`, `FaceclawEvenHubListener`).
* EvenHub app `localStorage` is persisted in NativeScript `ApplicationSettings` under
  `evenhub:<packageId>:ls:<key>` (`app/apps/evenhub/session.ts:770-773,921-923`).

### 3.15 Other platform touchpoints

| Feature | Behaviour | Source |
|---|---|---|
| Even-app conflict detection | Package `com.even.sg`; "active" = its persistent notification titled "Even Notification Service" is present (needs notification access). Used by the warnings modal and by the session core (`isEvenAppActive`) | `FaceclawEvenAppDetector.kt:15-47`, `FaceclawBleCommunicator.kt:67` |
| Open URL on phone | `ACTION_VIEW` + `CATEGORY_BROWSABLE` + `NEW_TASK` from the application context (usable from workers) | `app/native/open-url.ts:11-25` |
| Input API ("input tokens") | Local TCP listener on `127.0.0.1:8791` plus detected Tailscale tunnel addresses (never wildcard/Wi-Fi/LAN); token-authenticated (SHA-256 hashes stored, ≤32 tokens, permissions input/text/assistant); JSON frames ≤64 KiB; 5 s request/read timeouts; listener runs only while ≥1 token exists; interface re-sync every 3 s; secret copied via clipboard | `FaceclawRemoteInput.kt`, `app/remote/*.ts`, `RemoteInputSession.kt:207-209`, `scripts/faceclaw-input.md` |
| Screenshots / recordings | 4-bit gray PNG `screen-<yyyyMMdd-HHmmss-SSS>.png` and animated GIF `recording-<ts>.gif` in `<externalFilesDir>/screenshots/` | `ScreenshotUtil.kt`, `GifScreenRecorder.kt` |
| Frame timings | Per-frame latency stats exported to `<externalFilesDir>/frame-timings.txt` by a daemon thread | `FrameTimings.kt` |
| HTTP identity | okhttp interceptor adds `User-Agent: Faceclaw/<version>` unless present | `FaceclawHttp.kt`, `app/util/http.ts` |
| Phone mic (preview mode) | `AudioRecord` with `VOICE_RECOGNITION` source when no glasses are paired | `AndroidSpeechEngines.kt:103-125`, `FaceclawVoiceController.kt:151-153` |

### 3.16 Inventory of the Android Kotlin layer

| Class | Role | Covered in |
|---|---|---|
| `FaceclawForegroundService` | Connection FGS | §3.3 |
| `FaceclawMediaNotificationListenerService` | Notification mirror | §3.4 |
| `FaceclawMediaController`, `FaceclawMediaBrowser` | Media sessions / libraries | §3.5 |
| `FaceclawAlarms`, `FaceclawAlarmReceiver`, `FaceclawAlarmRescheduleReceiver`, `FaceclawAlarmService`, `FaceclawAlarmActivity` | Alarm clock | §3.6 |
| `FaceclawCalendarProvider` | Calendar | §3.7 |
| `FaceclawLocationProvider`, `FaceclawLocationTracker` | Location | §3.8 |
| `FaceclawSystemStatusIconProvider` | Status icons | §3.9 |
| `FaceclawSettings`, `FaceclawSettingsPortReceiver` | Settings store / adb port | §5 |
| `FaceclawConversationStore` | SQLite for captions/speakers | §6.1 |
| `FaceclawWearListenerService`, `FaceclawWearBridge` | Wear OS | §3.12 |
| `FaceclawQrScanner` | QR | §3.13 |
| `FaceclawEvenHubWebViewHost/WebView/WebViewClient/JsBridge/DocumentStart` | EvenHub hosting | §3.14 / EvenHub spec |
| `FaceclawEvenAppDetector` | Even-app conflict + notification-access helpers | §3.15 |
| `FaceclawRemoteInput` | Input API sockets | §3.15 |
| `FaceclawBleCommunicator`, `FaceclawBleManager`, `AndroidSessionLink` | Glasses session over GATT (worker thread, wake lock, keyguard receiver) | BLE spec |
| `FaceclawDeviceDiscovery` | Pairing scan | §2.6 / BLE spec |
| `FaceclawDeviceInfoProbe`, `FaceclawFlashPromptCommunicator`, `FaceclawFirmwareFlasher`, `AndroidStockLink` | Stock-firmware probe, lens Yes/No prompt, OTA flash | §2.8-2.9 / firmware spec |
| `FaceclawPreviewCompositor` | Headless compositor for preview-only mode | §2.10.3 |
| `PreviewBitmapUtil`, `ScreenshotUtil`, `GifScreenRecorder`, `FrameTimings`, `FaceclawResourceUsage` | Mirror bitmaps, captures, diagnostics | §2.10 / §3.15 |
| `ImageFileLoader`, `FontFileRenderer`, `IconRenderer` | Gray-packet image/text/SVG rasterisation for the glasses | graphics spec |
| `FaceclawVoiceController`, `AndroidSpeechEngines`, `AndroidNoiseEffects`, `FaceclawLc3Decoder`, `FaceclawCaptionEngine`, `FaceclawSpeakerId`, `FaceclawDiarizer`, `FaceclawAudioTranscoder` | Voice capture, LC3, on-device ASR (sherpa-onnx), speaker ID, diarization, AAC transcoding | voice spec |
| `FaceclawLlamaRunner`, `FaceclawModelDownloader` | On-phone LLM (llama.cpp JNI), resumable model downloads | assistant spec / §6.2 |
| `FaceclawSseRequest`, `FaceclawWebSocket`, `FaceclawHttp` | okhttp SSE/WebSocket transports for TS | §4 |
| `com.k2fsa.sherpa.onnx.*` (Java) | Vendored sherpa-onnx JNI ABI classes | §7 |

---

## 4. TypeScript ↔ Kotlin bridging design

A pure-Kotlin rebuild removes this boundary entirely, but its rules encode real constraints
(thread confinement, isolates, bulk data) that still apply to a Compose app.

### 4.1 Mechanism

* NativeScript generates metadata for every Java/Kotlin class on the classpath (including the KMP
  AAR); TS calls constructors, instance methods and statics by fully-qualified name, synchronously,
  on the calling JS thread (e.g. `new com.faceclaw.app.FaceclawMediaController(ctx)`,
  `com.faceclaw.app.FaceclawSettings.getInstance(ctx)`). The Kotlin code therefore keeps a
  Java-shaped API (`@JvmStatic`, `@JvmField`, `const val`, `companion object` singletons) so TS
  and the manifest need no changes (`native/kotlin/README.md`, "Platform boundaries").
* The KMP module (`native/kotlin/shared`) is built to an AAR by a NativeScript `before-prepare`
  hook and consumed through a local plugin `@faceclaw/kotlin` (`scripts/kotlin-build.cjs:28-58`,
  `native/kotlin/plugin/package.json`). Android-only classes live in `App_Resources/...` and are
  compiled by NativeScript's own `kotlin-android` Gradle plugin.
* Debug builds run bridge smoke tests at startup: `KotlinBridge.greet/roundTrip` checks both call
  directions and main-thread callbacks, logging `FACECLAW_KOTLIN_BRIDGE_PASS` (and a protocol
  smoke test logging `FACECLAW_KOTLIN_PROTOCOL_PASS`) (`app/native/kotlin-bridge.ts:5-38`,
  `app/native/kotlin-platform.android.ts:20-33`). `app/native/kotlin-data.ts` is the iOS
  (`NSData`) counterpart and unused on Android.

### 4.2 What crosses the boundary

| Kind | Representation | Examples |
|---|---|---|
| Scalars | `Int/Long/Double/Float/Boolean/String` (longs become JS numbers) | ids, timestamps, levels |
| Structured data | **JSON strings** in both directions (no shared object model) | notifications, calendar events, media sessions/queue/children, alarm journal & reliability issues, conversation rows & filters, advertisements, wear messages, remote-input requests |
| Bulk pixels / bytes | `ByteArray` returned to JS, or `java.nio.ByteBuffer` passed from a JS `ArrayBuffer` without per-element copying | notification/status icon grays (`size²` bytes each), "gray packet" `[wLo,wHi,hLo,hHi,pixels…]` images, surface frames, shell scenes, buzzer payloads (`FaceclawBleCommunicator.kt:306-358`, `PreviewBitmapUtil.kt` header) |
| Android objects | `Context` (application), `Activity` (QR scan), `Bitmap` (composite preview → `ImageSource`), `Intent` | — |
| Callbacks | Kotlin interfaces in KMP `callbacks/` implemented in JS: `new com.faceclaw.app.XListener({ method: (...) => … })`; the JS side must hold a strong reference to the proxy or it is garbage-collected (`app/native/settings-store.ts:13-14`) | see table 4.4 |
| Simple signals | `java.lang.Runnable` | remote-input "request ready" (`app/native/remote-input.ts:6-8`) |

Callback interfaces (all in `native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/callbacks/`):
`FaceclawAlarmListener`, `FaceclawAmbientLightListener`, `FaceclawAudioPacketListener`,
`FaceclawBleCommunicatorListener` (state, ring/input events, battery, silent mode, wear state,
phone lock, Even-app conflict, frame metrics, frame finished, firmware info),
`FaceclawBleListener`, `FaceclawCaptionEngineListener`, `FaceclawCompassListener`,
`FaceclawDeviceDiscoveryListener`, `FaceclawDeviceInfoProbeListener`, `FaceclawEvenHubListener`,
`FaceclawFirmwareFlasherListener`, `FaceclawFlashPromptListener`, `FaceclawImuListener`,
`FaceclawLlamaListener`, `FaceclawLocationListener`, `FaceclawLocationTrackerListener`,
`FaceclawMediaBrowserListener`, `FaceclawMediaControllerListener`, `FaceclawMicStatusListener`,
`FaceclawModelDownloaderListener`, `FaceclawNotificationListener`, `FaceclawQrScannerListener`,
`FaceclawSettingsListener`, `FaceclawSseListener`, `FaceclawVoiceControllerListener`,
`FaceclawWearListener`, `FaceclawWebSocketListener`.

### 4.3 Threading and dispatch rules

1. **Never call into a JS isolate from a foreign thread.** Every listener callback is posted to a
   Looper: either the Looper of the thread that constructed the object / registered the listener
   (`Looper.myLooper()` captured; falls back to main) — `FaceclawSettings.registerListener`
   (`FaceclawSettings.kt:81-84`), `FaceclawLocationTracker`, `FaceclawSseRequest`,
   `FaceclawWebSocket`, `FaceclawModelDownloader`, `FaceclawLlamaRunner`, `FaceclawWearBridge` —
   or always the main Looper (notification listener, media controller/browser, alarms, QR,
   communicator, probe/flasher). This is what lets worker isolates use location, SSE, websockets,
   settings.
2. **Re-dispatch on the JS side.** Most TS wrappers copy the listener set and deliver through
   `setTimeout(0)`, so JS logic never runs inside the native callback frame and listener-set
   mutation during delivery is safe (`app/native/faceclaw-communicator.ts:243-250`,
   `app/native/notification-icons.ts:209-219`, `app/ui/dashboard-settings.ts:61-70`).
3. **Ordered calls into the glasses session.** `FaceclawCommunicatorBridge.enqueueJavaCall` chains
   every Java call through a promise queue, each yielding one macrotask before running, so calls
   from many async callers keep FIFO order (e.g. the compositor screen size must land before any
   window configures a surface); an inline fast path runs a latency-sensitive call immediately
   when nothing is queued (saves ~18 ms per frame submit) (`faceclaw-communicator.ts:252-284`,
   `dashboard-controller.ts:1346-1352`).
4. **Blocking work on Kotlin-owned threads**: the BLE session worker thread
   ("FaceclawBleCommunicator"), probe/flasher workers, model-download thread, llama executor,
   diarizer/transcoder threads, socket accept loops; results posted back per rule 1.
5. **Cross-isolate singletons via Java statics.** Workers cannot share JS objects, so they find
   process-wide natives through statics: `FaceclawBleCommunicator.getActive()`,
   `FaceclawPreviewCompositor.getActive()`, `FaceclawSettings.getInstance()`, the alarm policy
   (`FaceclawBleCommunicator.kt:28-36`, `FaceclawPreviewCompositor.kt:31-41`).
6. **KMP cores are thread-agnostic**: they take ports (GATT link, session host, dispatcher,
   storage, clock) and call listeners synchronously; the Android adapters own threads, Handlers
   and OS APIs (`native/kotlin/README.md`).

### 4.4 Bridge map (Android)

| TS module | Kotlin class | Callback interface | Callback thread |
|---|---|---|---|
| `native/settings-store.ts` | `FaceclawSettings` (+ `SettingsChangeHub`) | `FaceclawSettingsListener` | registering isolate's Looper |
| `native/faceclaw-communicator.ts` | `FaceclawBleCommunicator` | `FaceclawBleCommunicatorListener`, `…Imu/AmbientLight/MicStatus/Compass/AudioPacket` | main |
| `native/preview-display.ts` | `FaceclawPreviewCompositor` | `Runnable` frame listener | main |
| `native/device-discovery.ts` | `FaceclawDeviceDiscovery` | `FaceclawDeviceDiscoveryListener` | main |
| `native/device-info-probe.ts`, `flash-prompt-communicator.ts`, `firmware-flasher.ts` | `FaceclawDeviceInfoProbe`, `FaceclawFlashPromptCommunicator`, `FaceclawFirmwareFlasher` | respective listeners | main |
| `native/notification-icons.ts` | `FaceclawMediaNotificationListenerService` (statics) | `FaceclawNotificationListener` | main |
| `native/media-controller.ts`, `media-browser.ts` | `FaceclawMediaController`, `FaceclawMediaBrowser` | `FaceclawMediaControllerListener`, `FaceclawMediaBrowserListener` | main |
| `native/alarms.ts` | `FaceclawAlarms` | `FaceclawAlarmListener` | main |
| `native/calendar.ts` | `FaceclawCalendarProvider` | — (synchronous) | — |
| `native/location.ts`, `location-tracker.ts` | `FaceclawLocationProvider`, `FaceclawLocationTracker` | location listeners | main / constructing Looper |
| `native/system-status-icons.ts` | `FaceclawSystemStatusIconProvider` | — | — |
| `native/wear-bridge.ts` | `FaceclawWearBridge` | `FaceclawWearListener` | registering Looper |
| `native/qr-scan.ts` | `FaceclawQrScanner` | `FaceclawQrScannerListener` | main |
| `native/remote-input.ts` | `FaceclawRemoteInput` | `Runnable` | main |
| `native/voice-control.ts` | `FaceclawVoiceController` | `FaceclawVoiceControllerListener` | main |
| `native/llama.ts`, `asr-model.ts` | `FaceclawLlamaRunner`, `FaceclawModelDownloader` | `FaceclawLlamaListener`, `FaceclawModelDownloaderListener` | constructing Looper |
| `native/sse.ts`, `socket.ts` | `FaceclawSseRequest`, `FaceclawWebSocket` | `FaceclawSseListener`, `FaceclawWebSocketListener` | constructing Looper |
| `phone-ui/conversation-format.ts` | `FaceclawConversationStore` | — (synchronous SQL on the JS thread) | — |
| `native/foreground-service.ts`, `battery-optimization.ts`, `notification-access.ts`, `even-app-conflict.ts`, `share-intents.ts`, `phone-rotation.ts`, `g2/android-permissions.ts` | Android SDK directly (Intents, PowerManager, ActivityCompat…) | Activity events | main |

---

## 5. Settings and configuration persistence

### 5.1 Stores

| Store | Android location | Holds | Accessed via |
|---|---|---|---|
| **Faceclaw settings** | SharedPreferences `faceclaw_settings` → `shared_prefs/faceclaw_settings.xml` | every user setting in §5.6 (strings and booleans only) | `FaceclawSettings` singleton (`FaceclawSettings.kt:26,42`); TS `native/settings-store.ts` |
| **NativeScript ApplicationSettings** | NativeScript's own SharedPreferences file (believed to be `shared_prefs/prefs.db.xml`; not verified — no `node_modules` in the checkout) | `onboarding.*`, `deviceAddress.*`, `deviceIdentity.*`, EvenHub app localStorage `evenhub:<pkg>:ls:<key>` | `ApplicationSettings` (`app/phone-ui/onboarding-state.ts`, `app/g2/device-addresses.ts:13-104`, `app/apps/evenhub/session.ts:770-773`) |
| **Alarm store** | device-protected SharedPreferences `faceclaw-alarms` | `scheduled`, `journal`, `log` JSON | `FaceclawAlarms` (§3.6) |
| **SQLite** | `databases/faceclaw-conversations.db` | caption sessions, segments, speakers | §6.1 |
| **Files** | app files/external/cache dirs | open apps, firmware, models, fonts, EvenHub packages, captures | §6.2 |

`FaceclawSettings` deliberately uses a separate file from NativeScript's ApplicationSettings
("the old TS-side settings were deliberately abandoned, not migrated") (`FaceclawSettings.kt:8-23`).
Consequence: the adb export/import (§5.4) moves only `faceclaw_settings.xml`; pairing addresses,
identity and onboarding flags stay on the phone.

### 5.2 Store semantics (`FaceclawSettings` + `SettingsChangeHub`)

* API: `getString/setString/getBoolean/setBoolean(key, default)`; defaults are supplied by the
  caller and never written. Numbers, enums and JSON blobs are stored as **strings** (e.g.
  `assistant.bridgePort = "8790"`, `display.brightness = "50"`) (`FaceclawSettings.kt:24-39,65-72`).
  (ApplicationSettings is used once with a number: `deviceIdentity.pairedAtMs`.)
* Writes use `SharedPreferences.apply()` (asynchronous flush) (`FaceclawSettings.kt:31,37`).
* Singleton: the main isolate calls `getInstance(context)` first; workers call `getInstance()`
  which throws if uninitialised (`FaceclawSettings.kt:41-66`).
* Change notification: each isolate registers **one** listener from its own thread; every
  `set*` posts `onSettingChanged(key)` to every registrant through its own dispatcher (Looper
  Handler). A registrant without a Looper is accepted but never notified. Notifications fire on
  every set, even when the value is unchanged (`SettingsChangeHub.kt:21-70`, `FaceclawSettings.kt:77-89`).
  The TS relay delivers to its listeners one macrotask later (`app/ui/dashboard-settings.ts:61-70`).
  Consumers (the controller, phone view-models, media controller, rotation, input API) react by key
  or re-read everything.

### 5.3 Typed setting model (TS `ConfigSetting*`, `app/ui/dashboard-settings.ts:31-226`)

* Every setting has `id`, `label`, `storageKey`, `defaultValue`, optional `description` (shown on
  the glasses Settings panel) and `formatValue` (display text; secrets mask themselves as
  "first 6 chars…" or "ab…wxyz", unset shows "(not set)"/"(empty)").
* **Boolean**: `get/set/toggle`.
* **Enum**: `values` list; stored value normalised (unknown → default, or a custom normaliser);
  `isDisabled(value)` greys options that are unusable (cloud STT/LLM options without an API key,
  local model not downloaded); `next()` skips disabled values (`:113-165`).
* **String**: normaliser (e.g. strip control characters, trim, strip trailing slashes), optional
  validator; with a validator the last valid value is mirrored to `<key>.valid` so an invalid
  draft can be previewed while consumers use `getValidValue()` (`:167-226`). `editorTitle` titles
  the phone editor, `glassesEditTitle` the glasses page, `inputKind` ∈ `text|email|password`
  selects the phone keyboard. A registry by id (`getStringSettingById`) lets a worker ask the main
  isolate to open the phone editor on a setting (`:180-186`, `dashboard-controller.ts:2308`).
* "Staging buffer" settings (drafts typed on the phone and read back by a glasses app on confirm)
  are ordinary string settings not listed in the Settings app (e.g. `terminal.newConnectionDraft`,
  `developer.appUrl`, `navigate.destinationNameDraft`).

### 5.4 Export / import

**Android (adb)** — whole-file copy of `shared_prefs/faceclaw_settings.xml`:

| Step | Debug (debuggable) build | Release build |
|---|---|---|
| Preflight | `adb` on PATH; exactly one device in state `device`; package installed; `run-as <pkg> true` succeeds ⇒ debug (`scripts/adbutil.sh:64-100`) | same; `run-as` fails ⇒ release |
| Export (`scripts/pull_config.sh`) | `adb exec-out run-as com.faceclaw.app cat shared_prefs/faceclaw_settings.xml > faceclaw_settings.xml` (`:37-39`) | `adb shell am broadcast -n com.faceclaw.app/.FaceclawSettingsPortReceiver -a com.faceclaw.app.SETTINGS_EXPORT`; require result `data="exported:…"`; `adb pull /sdcard/Android/data/com.faceclaw.app/files/faceclaw-settings-export.xml`; then `adb shell rm` it (`:40-52`) |
| Import (`scripts/push_config.sh`) | validate XML locally (xmllint or python); `am force-stop`; push to `/data/local/tmp`, `run-as cp` into `shared_prefs/`, remove staging (`:44-61`) | validate; force-stop; push to `…/files/faceclaw-settings-import.xml`; broadcast `SETTINGS_IMPORT`; require `data="imported:…"` (`:62-74`) |

Receiver behaviour (`FaceclawSettingsPortReceiver.kt:54-145`): export copies the prefs file to
`getExternalFilesDir()/faceclaw-settings-export.xml` and returns `exported: <path>` (or
`error: no settings file yet`); import requires the staged file, validates it is a
SharedPreferences `<map>` document by parsing to the end, copies the live file to
`faceclaw_settings.xml.bak`, installs the staged file, deletes it, returns `imported: …`, and then
**calls `System.exit(0)` 500 ms later** so stale in-memory SharedPreferences cannot overwrite the
imported file. Guarded by `android.permission.DUMP` (adb shell only). The staged file sits in
app-specific external storage (unreadable by other apps on Android 11+; readable by
`READ_EXTERNAL_STORAGE` holders on 7–10 during the transfer window).

Script arguments: a bare first argument or `-f FILE` names the local file (default
`faceclaw_settings.xml`, gitignored); other flags pass through to adb (`-s SERIAL`)
(`scripts/adbutil.sh:21-54`). `scripts/resetOnboarding.sh` strips `onboarding.*` entries from all
`shared_prefs/*.xml` via `run-as` + `sed` (debug) or falls back to `pm clear` (`:58-104`).

**iOS / portable JSON** (for reference; useful as the rebuild's portable format):
`scripts/ios_config.py` reads `{"schema":1,"settings":{…}}` JSON or Android `<map>` XML (`string`,
`boolean`, `int`, `long`, `float`; rejects DOCTYPE/ENTITY, nested content, duplicates), limits
2 MiB / 4096 entries / keys ≤512 chars, and **merges** supplied keys on push
(`scripts/ios_config.py:19-80`, `scripts/ios-config.md`).

**`SettingsDocument` (KMP, schema 2)** (`native/kotlin/shared/.../SettingsDocument.kt`): a JSON
`{"schema":2,"settings":{…}}` codec in which a fixed set of "structured" keys whose string values
are JSON are stored as nested JSON (`terminal.connections`, `teleprompter.recents`,
`launcher.folders`, `notifications.sources`, `files.bookmarks`, `music.apps`,
`microphones.array-config`, `display.uiFont2`, `terminal.font`, `navigate.savedDestinations`,
`navigate.recentDestinations`, `evenhub.installedApps.v1`, `timers.state`,
`assistant.conversations`, `ios.ble.peripheral.*`); migration 1→2 converts them; decode enforces
2 MiB, ≤4096 entries, non-empty keys ≤512 chars, no nulls; `encodeForStorage` refuses to emit a
document that would not decode (`:4-61`). **No caller references it in this commit** (the iOS
config port still emits schema 1) — treat it as a design sketch.

### 5.5 Where settings are edited

On the glasses: Settings app sections Display, Voice, Assistant, API Keys, Terminal, Navigate,
Phone display, Watch, Developer, About, Quit (`app/ui/dashboard/settings-menus.ts:98-249`); app
specific settings inside each app's context menus (Glanceboard, Timers, Microphones, Calculator,
Files, Compass, Roam, EvenHub login). Text values are typed on the phone (§2.10.6). On the phone:
only the remote-controls Settings tab (display mode, brightness) and the pairing/config pages
(addresses).

### 5.6 Complete key list

Types: **bool**; **enum** (string from a fixed set); **str** (free string); **num** (number
stored as string); **json** (JSON stored as string); **secret** (str containing a credential —
included in exports). Unless noted, keys live in the Faceclaw settings store. Sources: `DS` =
`app/ui/dashboard-settings.ts`.

**Onboarding and pairing (NativeScript ApplicationSettings store)**

| Key | Type | Default | Notes | Source |
|---|---|---|---|---|
| `onboarding.complete` | bool | false | start page selector | `phone-ui/onboarding-state.ts:3` |
| `onboarding.previewOnly` | bool | false | preview-only mode | `:4` |
| `onboarding.welcomeSoundPending` | bool | false | one-time welcome buzzer | `:5` |
| `deviceAddress.right`, `.left`, `.ring` | str | "" | normalised MAC `AA:BB:CC:DD:EE:FF`; ring optional | `g2/device-addresses.ts:13-37` |
| `deviceIdentity.serial` (upper-cased), `.leftName`, `.rightName`, `.leftAddress`, `.rightAddress`, `.ringName`, `.ringAddress` | str | "" | what the pairing scan learned | `:69-104` |
| `deviceIdentity.pairedAtMs` | number | 0 | `ApplicationSettings.setNumber` | `:91,103` |
| `evenhub:<packageId>:ls:<key>` | str | "" | EvenHub app localStorage | `apps/evenhub/session.ts:921-923` |

**Display / top bar**

| Key | Type | Default | Values / notes | Source |
|---|---|---|---|---|
| `display.mode` | enum | `576x288` | `576x288` (Band), `576x480` (Tall), `640x480` (Full panel) | DS:311-333 |
| `display.brightness` | enum | `auto` | `auto`, `2`, `10`…`100` step 10; `"0"`→`"2"` | DS:34-35,335-344 |
| `display.autoBrightnessMin` / `display.autoBrightnessMax` | enum | `20` / `100` | 2,5,10,20,25,30,40,50,60,70,80,90,100 | DS:346-356 |
| `display.autoBrightnessCurve` (+ `.valid`) | str | `0:0,0.2:7,1.5:33,5:100` | 2–16 `lux:percent` pairs, validated | DS:357-361, `g2/brightness-curve.ts:1` |
| `display.screenTimeout` | enum | `30s` | `15s`,`30s`,`1m`,`3m`,`never` | DS:371-379 |
| `display.lockScreenEnabled` | bool | true | lock glasses when removed while phone locked | DS:381-389 |
| `display.verticalPosition` | enum | `middle` | `top`,`upper`,`middle`,`lower`,`bottom` | DS:474-484 |
| `display.timeFormat` | enum | `24h` | `24h`,`12h` | DS:294-303 |
| `dashboard.systemCard.batteryDisplayMode` | enum | `stacked` | `icon`,`percentage`,`stacked`,`stacked-percentage` | DS:231-239 |
| `display.battery.phoneVisibility`, `…glassesVisibility`, `…ringVisibility`, `…watchVisibility` | enum | `always` | `always`,`low` (<50 %),`never` | DS:242-272 |
| `display.uiFont2` | json | `{"kind":"ttf","file":"Roboto-Light.ttf","size":14}` | or `{"kind":"bitmap","face":"terminus"\|"terminusv"}`; size 6–64 | `graphics/ui-fonts.ts:23,57,79-91` |
| `display.uiFont` | str (legacy) | — | migrated into `display.uiFont2` | `ui-fonts.ts:26,83` |

**Phone display, watch**

| Key | Type | Default | Values / notes | Source |
|---|---|---|---|---|
| `phone.rotation` | enum | `auto` | `auto`,`portrait`,`landscape` | DS:395-403 |
| `phone.previewColor` | enum | `white` | `white`,`green` | DS:405-414 |
| `phone.mirrorTouch` | bool | true | touches on the mirror act on the glasses | DS:416-426 |
| `watch.remoteEnabled` | bool | true | accept Wear input | DS:429-437 |
| `watch.canUnlock` | bool | true | watch may unlock the glasses lock screen | DS:439-446 |
| `watch.crownClockwiseNext` | bool | false | crown direction | DS:448-455 |
| `watch.mirrorAssistant` | bool | true | stream assistant replies/alerts to the watch | DS:457-463 |

**Voice and assistant**

| Key | Type | Default | Values / notes | Source |
|---|---|---|---|---|
| `voice.enabled` | bool | true | master voice switch | DS:519-525 |
| `voice.provider` | enum | `onboard` | `onboard` (Moonshine), `onboard-whisper`, `elevenlabs`, `whisper` (OpenAI cloud), `soniox`; cloud values disabled without key | DS:590-605 |
| `voice.wakeWordAction` | enum | `voice-input` | `voice-input`,`off`,`turn-screen-on` | DS:612-620 |
| `voice.elevenLabsApiKey`, `voice.openAiApiKey`, `voice.sonioxApiKey` | secret | "" | OpenAI key also used for OpenAI LLMs | DS:697-727 |
| `llm.anthropicApiKey` | secret | "" | | DS:730-738 |
| `assistant.backend` | enum | `direct` | `direct` (phone LLM/cloud), `external` (agent bridge) | DS:645-654 |
| `assistant.model` | enum | `auto` | `auto`,`hauku`,`sonnet`,`opus`,`fable`,`luna`,`terra`,`sol`,`qwen` (availability-gated) | DS:741-756, `assistant/models.ts:3-13` |
| `assistant.skipConfirmationAfterWakeword` | bool | false | | DS:630-636 |
| `assistant.bridgeHost` | str | "" | e.g. Tailscale IP | DS:656-665 |
| `assistant.bridgePort` | num | `8790` | | DS:667-675 |
| `assistant.bridgeToken` | secret | "" | | DS:677-686 |
| `assistant.allowProactive` | bool | true | agent may act outside conversations | DS:688-695 |
| `assistant.conversations` | json | "" | `{selectedId, conversations:[{id, model, reasoning, history:{messages, transcript}}]}` — assistant chat history, no credentials | `ui/shell/shell.ts:337-345`, `assistant/conversations.ts:13-58` |

**Integrations**

| Key | Type | Default | Notes | Source |
|---|---|---|---|---|
| `maps.mapboxApiKey` | secret | "" | Navigate (public `pk.` token) | DS:759-767, `native/mapbox.ts:13` |
| `integrations.nightscout.siteUrl` | str | "" | trailing slashes stripped | DS:857-867 |
| `integrations.nightscout.apiToken` | secret | "" | | DS:869-879 |
| `integrations.nightscout.max-cannula-age-hours`, `…cartridge-low-units`, `…battery-low-voltage`, `…max-loop-age-minutes` | num | `0` | 0 = alert off | DS:881-906 |
| `integrations.nightscout.alwaysShowInTopBar` | bool | false | | DS:907-913 |
| `integrations.roam.graphName` / `integrations.roam.apiToken` | str / secret | "" | | DS:833-855 |
| `integrations.evenhub.email` / `integrations.evenhub.token` | str / secret | "" | EvenHub account | `apps/evenhub/credentials.ts:13-24` |
| `transient.evenhub.email`, `…password`, `…rememberMe` | str/secret/bool | "", "", true | login-form staging | `credentials.ts:70-96` |
| `integrations.evenhub.password` | legacy | — | cleared at startup if present | `credentials.ts:165-168` |

**Terminal, Navigate, Developer, Input API**

| Key | Type | Default | Notes | Source |
|---|---|---|---|---|
| `terminal.connections` | json | "" | `[{id, url (g2mirror://token@host), …}]` | `apps/terminal/connections.ts:13-90` |
| `terminal.newConnectionDraft` | str | "" | staging | DS:776-786 |
| `terminal.launchPresets` | str | `shell` | comma-separated | DS:804-813 |
| `terminal.autoReconnect` / `terminal.wakeOnBell` | bool | true / false | | DS:815-831 |
| `terminal.displayMode` | enum | `default` | `default`,`global`,`576x288`,`576x480`,`640x480` | DS:489-518 |
| `terminal.verticalPosition`, `navigate.verticalPosition` | enum | `global` | `global`,`top`…`bottom` | DS:503-518 |
| `navigate.displayMode` | enum | `global` | `global` + the three modes | DS:489-518 |
| `terminal.font` | json | `{"kind":"bitmap","face":"terminus"}` | | `graphics/ui-fonts.ts:24,95-99` |
| `navigate.homeAddress`, `navigate.workAddress` | str | "" | | DS:929-951 |
| `navigate.rememberRecent` | bool | true | off clears the list | DS:953-961 |
| `navigate.savedDestinations` | json | `[]` | `[{id,name,address}]` | DS:967-972 |
| `navigate.recentDestinations` | json | `[]` | `[{name,place,longitude,latitude,atMs}]` | DS:979-984 |
| `navigate.destinationNameDraft`, `navigate.destinationAddressDraft` | str | "" | staging | DS:993-1012 |
| `developer.firmwareDebugFlags` | bool | false | CFW overlay | DS:527-533 |
| `developer.suspendEvenHubWhenScreenOff` | bool | true | | DS:535-541 |
| `developer.useMicControl` | bool | true | | DS:543-550 |
| `developer.showBleBandwidth` | bool | false | phone overlay (§2.10.9) | DS:552-559 |
| `developer.ringConnectionMode` | enum | `glasses` | `glasses`,`direct` (next connect) | DS:563-572 |
| `developer.saveVoiceRecordings` | bool | false | | DS:622-628 |
| `developer.appUrl` | str | "" | staging for "Load app from URL" | DS:793-802 |
| `remoteInput.tokens.v1` | json | `[]` | ≤32 tokens; SHA-256 hashes + name + permissions | `remote/service.ts:5-7`, `remote/protocol.ts:37` |
| `remoteInput.interface` | str | `tailscale` | `tailscale`, `localhost`, `interface:<name>` | `remote/listeners.ts:3`, `remote/service.ts:10` |
| `remoteInput.newName` | str | `My app` | ≤80 chars, staging | `ui/dashboard/remote-input-menu.ts:13-14` |

**Notifications, media, glasses apps**

| Key | Type | Default | Notes | Source |
|---|---|---|---|---|
| `notifications.sources` | json | `[]` | `[{packageName, appName, showOnGlasses}]` per-app mute | `native/notification-sources.ts:3-58` |
| `music.apps` | json | `[]` | `[{packageName, appName, enabled?}]` | `native/media-apps.ts:3,31-83` |
| `glanceboard.enabled` | bool | false | | `apps/glanceboard/glanceboard-settings.ts:48-53` |
| `glanceboard.layout` | enum | `2x2` | `2x2`,`2x3` | `:30-36` |
| `glanceboard.tapDuration` | enum | `5s` | `off`,`3s`,`5s`,`7s`,`10s` | `:59-72` |
| `glanceboard.showOnLongPress` / `glanceboard.showOnHeadTilt` / `glanceboard.showLines` | bool | true | | `:89-137` |
| `glanceboard.depth` | enum | `0` | `-64`…`64` step 16 | `:106-119` |
| `glanceboard.quadrants.slot.0` … `.5` | enum | system-card, calendar, music, calendar, none, none | `none`,`system-card`,`calendar`,`terminal`,`nightscout`,`compass`,`music` | `:8,28,144-157` |
| `timers.state` | json | "" | timers + alarms engine state (Timers spec) | `apps/timer/timer-engine.ts:54` |
| `timers.soundOnGlasses` / `timers.wakeGlasses` | bool | true | | `timer-engine.ts:76-92` |
| `timers.snoozeMinutes` | enum | `10` | `5`,`10`,`15` | `timer-engine.ts:93-99` |
| `microphones.captions-enabled`, `…translate-enabled`, `…save-recordings`, `…beam-filter`, `…wearer-commands-only` | bool | false | | `apps/microphones/mic-settings.ts:52-130` |
| `microphones.save-captions`, `microphones.anc-enabled` | bool | true | | same |
| `microphones.captions-retention` / `microphones.recordings-retention` | enum | `1m` / `1w` | `none`,`1d`,`1w`,`1m`,`1q`,`1y`,`forever` | `mic-settings.ts:10,86-102` |
| `microphones.array-config` | json | "" | per-temple mic-control configuration | `apps/microphones/mic-control.ts:28,52-68` |
| `calculator.defaultMode` / `calculator.listening` / `calculator.usesDegrees` | enum/enum/bool | `solve` / `tap` / false | `solve`,`explain`,`graph`; `tap`,`continuous` | `apps/calculator/calculator.ts:29-56` |
| `files.viewMode` / `files.bookmarks` | enum / json | `icons` / `[]` | `icons`,`list`; string array of paths | `apps/files/file-browser.ts:38-66` |
| `launcher.folders` | json | "" | appId → folder name | `apps/launcher/launcher-folders.ts:11-122` |
| `teleprompter.recents` | json | `[]` | | `apps/teleprompter/recent-files.ts:16-37` |
| `evenhub.installedApps.v1` | json | "" | installed EHPK index | `apps/evenhub/installed-apps.ts:21` |
| `evenhub.store.searchQuery` | str | "" | | `apps/evenhub/store-layer.ts:33-38` |
| `compass.northReference` | enum | `true` | `true`,`magnetic` | `apps/compass/heading.ts:15,34-38` |
| `compass.calibrationOffsetDegrees` / `compass.calibrated` / `compass.debugInfo` | num / bool / bool | `0` / false / false | | `apps/compass/calibration.ts:15-41`, `debug.ts:4-11` |
| `compass.declination.degrees`, `.latitude`, `.longitude`, `.locatedAtMs` | num | "" | cached declination | `apps/compass/declination.ts:14-17` |
| `minesweeper.difficulty` | str | "" | `Easy`/`Medium`/`Hard` | `apps/minesweeper/minesweeper-app.worker.ts:60-66` |
| `flappy.highScore`, `pinball.highScore` | num | `0` | | respective workers |
| `<appId>.soundOn` | bool | true | per-game sound toggle | `ui/sound-setting.ts:8-26` |

(iOS-only keys such as `ios.ble.peripheral.<addr>` and `ios.settings.changeToken` are not used on
Android.)

---

## 6. Other persistence

### 6.1 Conversation store (SQLite) — `FaceclawConversationStore.kt`

`SQLiteOpenHelper` singleton, database `faceclaw-conversations.db`, version **2**
(`:24-39`). All values cross to TS as JSON strings; embeddings are float32 little-endian BLOBs,
exposed as base64. Called synchronously from the JS thread.

Schema (`:50-95`):

| Table | Columns |
|---|---|
| `speakers` | `id` INTEGER PK AUTOINCREMENT; `name` TEXT NOT NULL; `color` TEXT NOT NULL DEFAULT `'#4FC3F7'`; `is_wearer` INTEGER NOT NULL DEFAULT 0; `embedding` BLOB; `embedding_count` INTEGER NOT NULL DEFAULT 0; `created_at` INTEGER NOT NULL; `last_heard_at` INTEGER; `tag` TEXT; `last_recap` TEXT; `action_items` TEXT (JSON string array); `facts` TEXT (JSON string array); `insights_updated_at` INTEGER; `insights_session_id` INTEGER |
| `sessions` | `id` INTEGER PK AUTOINCREMENT; `started_at` INTEGER NOT NULL; `ended_at` INTEGER; `title` TEXT; `audio_path` TEXT; `audio_codec` TEXT; `avg_sentiment` REAL; `segment_count` INTEGER NOT NULL DEFAULT 0 |
| `segments` | `id` INTEGER PK AUTOINCREMENT; `session_id` INTEGER NOT NULL; `speaker_id` INTEGER; `started_at` INTEGER NOT NULL; `ended_at` INTEGER NOT NULL; `audio_offset_ms` INTEGER; `text` TEXT NOT NULL; `lang` TEXT; `translation` TEXT; `translation_lang` TEXT; `sentiment` REAL; `emotion` TEXT; `search_meta` TEXT; `angle` INTEGER; `embedding` BLOB |
| Indexes | `idx_segments_session(session_id)`, `idx_segments_speaker(speaker_id)`, `idx_segments_time(started_at)`, `idx_sessions_time(started_at)` |

Migration 1→2 adds the five insight columns to `speakers` (`:97-107`). No foreign keys; integrity
is maintained by the API.

API (JSON in/out):

| Method | Semantics |
|---|---|
| `startSession(startedAtMs, title)` → id; `endSession(id, endedAtMs, audioPath, codec, avgSentiment)`; `setSessionAudio`; `getSessionAudioPath` | session lifecycle |
| `querySessions(filterJson)` | filters `sinceMs`, `untilMs`, `speakerId`, `emotion`, `query` (LIKE over title/segment text/search_meta), `limit` (default 200, max 1000); newest first; each row includes `speakers:[{id,name,color}]` (`:151-234`) |
| `insertSegment(json)` → id (increments `segment_count`); `updateSegment(id, json)` | fields `sessionId, speakerId?, startedAt, endedAt, audioOffsetMs?, text, lang?, translation?, translationLang?, sentiment?, emotion?, searchMeta?, angle?, embeddingBase64?` (`:238-314`) |
| `querySegments(sessionId)` | ascending by time (`:316-333`) |
| `searchSegments(filterJson)` | across sessions; `query` also matches translation; default limit 300 (`:353-409`) |
| `querySegmentEmbeddings(sessionId)` | for re-diarization |
| `createSpeaker`, `querySpeakers` (by `last_heard_at` desc, with segment counts), `renameSpeaker`, `setSpeakerColor`, `setSpeakerTag`, `setSpeakerInsights` | speakers |
| `setSpeakerWearer(id, true)` | clears the flag everywhere first: exactly one wearer (`:515-526`) |
| `updateSpeakerEmbedding(id, b64, maxCount)` | running-mean centroid capped at `maxCount` samples (`:530-560`) |
| `mergeSpeakers(from, into)` | one transaction: move segments, count-weighted centroid blend, keep target's insights unless it has none, drop source (`:563-635`) |
| `reassignSegmentSpeaker`, `reassignSessionSpeaker`, `deleteSpeaker` (segments keep text, lose label) | |
| `applyRetention(captionCutoffMs, recordingCutoffMs)` → `{sessionsDeleted, recordingsDeleted}` | strips recordings older than their cutoff (keeps transcripts), deletes whole sessions older than the caption cutoff (0 disables either) (`:656-719`) |
| `deleteSession(id)` | deletes the recording file, segments and session (`:721-729`) |

### 6.2 Files

| Data | Location | Written by | Notes |
|---|---|---|---|
| Open glasses apps | `filesDir/open-apps.json` = `{version:1, open:[appId…], foreground}` | `ui/shell/open-apps-persistence.ts:9-67` | Debounced 1 s; launcher excluded; restored once on main-page load, unknown ids skipped, only primary windows (`dashboard-controller.ts:2343-2391`) |
| Stock firmware image | `filesDir/g2_2.3.0.24.bin` | `g2/firmware-builder.ts:146` | Downloaded from `cdn.evenreal.co`, SHA-256 pinned; used for uninstall |
| Custom firmware image | `filesDir/g2_2.3.0.24_cfw.bin` | `firmware-builder.ts:120` | Patched, output SHA-256 pinned (`g2/firmware/cfw-patches.ts:19-22`) |
| EvenHub font extraction | `filesDir/evenhub-firmware-20.json` | `firmware-builder.ts:57,184` | Presence drives the "fonts missing" warning |
| User fonts | `filesDir/fonts/` | `graphics/installed-fonts.ts:49` | |
| EvenHub packages | `filesDir/evenhub-installed/<safePackageId>/package.ehpk` (+ artwork) | `apps/evenhub/installed-apps.ts:91-135,230-236` | Index in `evenhub.installedApps.v1` |
| On-device ASR models | `filesDir/faceclaw-voice-asr/<dir>/` — Moonshine base-en quantized (3 files, ~141 MB), Whisper base.en int8 (3 files, ~161 MB) | `native/asr-model.ts:40-140` | Hugging Face URLs; per-file SHA-256 pinned |
| Speaker models | `filesDir/faceclaw-mic-models/<id>/` — WeSpeaker CAM++ (~29 MB), pyannote segmentation 3.0 (~6 MB) | `apps/microphones/mic-models.ts:40-90` | |
| On-phone LLM | `getExternalFilesDir("llm")/Qwen3-4B-Instruct-2507-Q4_K_M.gguf` (~2.5 GB; falls back to `filesDir`) | `native/llama.ts:20-66` | `.part` file while downloading |
| Mic recordings | `getExternalFilesDir()/mic-recordings/` (WAV, transcoded to AAC-LC `.m4a`) | `apps/microphones/mic-session.ts:1030`, `FaceclawAudioTranscoder.kt` | Referenced by `sessions.audio_path` |
| Screenshots / GIF recordings | `getExternalFilesDir()/screenshots/` | `ScreenshotUtil.kt`, `GifScreenRecorder.kt` | adb-pullable |
| Debug notification icons | `getExternalFilesDir()/debug-icons/` | NLS (§3.4) | overwritten each refresh |
| Frame timings | `getExternalFilesDir()/frame-timings.txt` | `FrameTimings.kt` | |
| Settings transfer | `getExternalFilesDir()/faceclaw-settings-export.xml`, `…-import.xml` | §5.4 | transient |
| Settings backup | `shared_prefs/faceclaw_settings.xml.bak` | §5.4 | after import |
| EvenHub privacy policies | `cacheDir/privacy-policies/` | `apps/evenhub/privacy-policy.ts:90-103` | shared through FileProvider |

Downloads of large models use the shared `ResumableDownload` over okhttp
(`FaceclawModelDownloader.kt`): a `.part` file resumed with HTTP `Range`, SHA-256 and total-size
verification, rename on success; connect 30 s / read 60 s timeouts; `cancel()` keeps the `.part`
for a later resume; progress/done/error posted to the constructing thread.

---

## 7. Build, release and CI

### 7.1 Toolchain and configuration

| Item | Value | Source |
|---|---|---|
| Framework | NativeScript 9 (`@nativescript/core ~9.0.0`, Android runtime `9.1.1`, webpack 5, TypeScript ~5.4) | `package.json` |
| App id | `com.faceclaw.app`; V8 flags `--expose_gc`, `markingMode: none` | `nativescript.config.ts:3-12` |
| compileSdk / buildTools / targetSdk / minSdk | 35 / 35 / 35 / **24** | `App_Resources/Android/app.gradle:258-270` |
| ABIs | **arm64-v8a only** (NativeScript adds all four; the list is cleared first) | `app.gradle:271-278` |
| Version | `versionName` = `FACECLAW_VERSION` parsed from `app/version.ts`; `versionCode` = major·10000 + minor·100 + patch (0.7.2 → 702) | `app.gradle:242-256,281-282` |
| Kotlin | 2.4.20 (app via `before-plugins.gradle:15`; KMP module + Android KMP library plugin 8.13.2; JVM target 17) | `native/kotlin/build.gradle.kts`, `shared/build.gradle.kts` |
| JDK / SDK | JDK 21, Android SDK 35, NDK (newest installed), SDK CMake package | `README.md`, `app.gradle:49-69,152-159` |
| Misc | `generatedDensities = []`; aapt `--no-version-vectors` | `app.gradle:284-289` |

Dependencies (`app.gradle:4-22`): `com.squareup.okhttp3:okhttp:4.12.0` (WebSocket/SSE/downloads),
`androidx.webkit:webkit:1.12.1` (document-start script injection),
`com.google.android.gms:play-services-code-scanner:16.1.0` (QR),
`com.google.android.gms:play-services-wearable:19.0.0` (watch),
`com.google.mlkit:language-id:17.0.6` and `com.google.mlkit:translate:17.0.3` (caption
translation); plus the KMP AAR and `kotlin-stdlib` via the local plugin
(`native/kotlin/plugin/platforms/android/include.gradle`).

Native libraries, produced in `preBuild` (`app.gradle:71-240,309-313`; outputs gitignored under
`src/main/jniLibs/arm64-v8a/`):

| Library | How | Notes |
|---|---|---|
| `libonnxruntime.so`, `libsherpa-onnx-jni.so` | downloaded from the sherpa-onnx **v1.13.0** Android release tarball | JNI ABI must match the vendored `com.k2fsa.sherpa.onnx` Java classes |
| `libfaceclaw_lc3.so` | Google **liblc3 v1.1.3** sources + `faceclaw_lc3_decoder.c` compiled with NDK clang (`aarch64-linux-android24`, `-O3`, 16 KB `max-page-size`/`common-page-size`) | G2 mic audio is LC3 |
| `libfaceclaw_llama.so` | **llama.cpp tag b10333** + JNI shim, CMake/Ninja, `-march=armv8.2-a+dotprod+fp16` (Tensor G2 floor), `llvm-strip --strip-unneeded` (~50 MB → ~8 MB) | first build 2–4 min |

Build steps download sources over the network at build time (cached in the build dir).

### 7.2 Build scripts

* `build.sh` → `npx nativescript build android`; `build_and_run.sh` → build, `adb connect
  $DEVICE_ID`, `nativescript run android --device $DEVICE_ID --justlaunch`; `build_wear.sh` →
  `wear/gradlew build`. All source `scripts/before_build.sh`, which creates `build_paths.sh` from
  the template (JAVA_HOME, ANDROID_HOME, PATH, ANDROID_KEYSTORE, DEVICE_ID) and re-installs the
  Kotlin hook.
* The NativeScript `before-prepare` hook (`hooks/before-prepare/faceclaw-kotlin.js`) checks the
  generated Android runtime is not stale (else "run `ns platform clean android`") and builds the
  KMP AAR with `wear/gradlew :shared:assembleAndroidMain`, copying it into the local plugin only if
  changed (`scripts/kotlin-build.cjs:20-58`). App Gradle deletes stale generated copies of Java
  sources that moved to Kotlin, per `native/kotlin/migrated-java-sources.json`
  (`app.gradle:292-313`).
* Webpack copies `README.md`, `LICENSE`, `PRIVACY`, `ACKNOWLEDGEMENTS.md` into `about/`
  (`webpack.config.js:22-29`).

### 7.3 Release and signing (`scripts/release.sh`)

1. Read the version from `app/version.ts`; require `ANDROID_KEYSTORE`.
2. Prompt (silently) for the keystore passphrase; validate with `keytool -list` and pick the first
   `PrivateKeyEntry` alias; prompt for the key passphrase (empty = same) (`:41-57`).
3. `npx nativescript build android --release --key-store-path … --key-store-password …
   --key-store-alias … --key-store-alias-password … --copy-to dist/Faceclaw-<ver>.apk` (`:66-72`).
4. Build the watch app with the **same key** via AGP injected signing properties →
   `dist/Faceclaw-Wear-<ver>.apk` (the Data Layer only routes between apps with identical package
   name and signature) (`:74-82`).
5. Print `apksigner verify --print-certs` for both (`:85-94`). Passphrases appear briefly on
   process command lines.

Distribution is by APK on GitHub Releases (sideload), not the Play Store (`README.md`
"Installation"). Installing a self-built debug APK over a release build requires uninstalling
first (different signature) — export settings first (§5.4).

### 7.4 CI and tests

* `.github/workflows/tests.yml`: on push to `main` and PRs — ubuntu, `npm ci`, `npm test`
  (compiles NativeScript-free TS modules with `tsc` and runs `node --test` on
  `tests/*.test.cjs`). **No APK build and no Kotlin tests in CI.**
* `.github/workflows/pages.yml`: deploys `website/` to GitHub Pages after verifying generated
  README/site blocks are in sync (`node scripts/sync-site.mjs --check`).
* Local: `npm run test:kotlin` (KMP common + Android host tests on the JVM), iOS simulator tests,
  debug-startup logcat markers (§4.1). Hardware behaviour needs device testing.
* Developer tooling: `scripts/profile-android.cjs` (ART + V8 sampling on debug builds),
  `scripts/pull-frame-timings.sh`, `scripts/faceclaw-input.cjs` (Input API CLI),
  `scripts/resetOnboarding.sh`.

---

## 8. Recommended architecture for a pure Kotlin / Jetpack Compose rebuild

### 8.1 Guiding principles

1. **Nothing long-lived belongs to the Activity.** The glasses session, shell renderer, settings,
   notification/media repositories and alarm scheduler are application-scoped singletons (manual
   DI container or Hilt). The Activity/Compose layer only observes state and sends intents. The
   original gets this by accident (NativeScript keeps the JS runtime alive under the FGS); make it
   explicit.
2. **One foreground service owns "connected" lifetime.** Start it when the user connects (or
   auto-connect fires from a visible UI), stop it on deliberate disconnect. It hosts a
   `CoroutineScope` for the session; the UI binds to `StateFlow`s exposed by the app graph, not to
   the service.
3. **Thread confinement as in §4.3**: GATT callbacks on a dedicated `HandlerThread`; the session
   state machine on a single-threaded dispatcher; compositing/encoding on `Dispatchers.Default`;
   UI on Main. Publish immutable snapshots (`StateFlow<ConnectionUiState>`) instead of mutable
   listeners; one-shot events via `SharedFlow`.
4. **Keep wire/storage compatibility where it is cheap**: same application id if you want to
   replace the original in place (then signatures differ → uninstall required), same settings
   key names and value encodings (strings/booleans) so an exported `faceclaw_settings.xml` from
   the original imports cleanly, same Wear protocol and capability names.
5. **Everything that must work without the UI** (alarms, notification listener, Wear listener,
   settings port) is a self-contained Android component that talks to repositories, never to a
   ViewModel.

### 8.2 Module layout (suggested)

| Module | Contents |
|---|---|
| `:app` | `MainActivity` (singleTask; handles `SEND` text in `onCreate`/`onNewIntent`), Compose navigation graph, screens, ViewModels |
| `:core:settings` | `SettingKey<T>` registry (bool/enum/string/json/secret with defaults, normalisers, validators, `.valid` shadow), SharedPreferences-backed store named `faceclaw_settings` with change `Flow`, export/import codecs (Android XML + portable JSON), pairing/onboarding store |
| `:core:platform` | Permission model, `GlassesConnectionService` (FGS), wake-lock policy, battery-optimization helpers, phone-lock monitor, `NotificationListenerService` + `PhoneNotificationRepository`, `MediaSessionRepository`, `MediaLibraryClient`, calendar/location/status-icon/battery providers, Even-app detector, share-intent parser, `SettingsPortReceiver` |
| `:core:alarms` | AlarmManager scheduling, device-protected store, ringing policy, ringing FGS + lock-screen activity, reliability checks |
| `:glasses:session`, `:glasses:shell`, `:glasses:firmware` | From the sibling specs (BLE session, glasses UI/compositor, probe/prompt/flash/build) |
| `:feature:captions` (later) | Conversation store (Room, same schema), conversations/speakers/ask screens |
| `:feature:wear`, `:feature:evenhub`, `:feature:inputapi`, `:feature:models` (later) | Wear bridge, WebView hosting, local TCP input API, model downloads/LLM/ASR |

### 8.3 Screen mapping (original page → Compose destination)

| Original | Compose destination | ViewModel responsibilities |
|---|---|---|
| launch-page | start gate (no UI) | read `onboarding.complete` → `Main` or `Onboarding(1)`, clearing back stack |
| onboarding-page (1–3) | `Onboarding(step)` | step content, Preview-only path, footer links |
| permissions-page | `Permissions(onboarding)` | permission cards; `RequestMultiplePermissions` launchers; system-settings intents; re-check on `ON_RESUME` |
| onboarding-unpair-page | `DisconnectOtherApps` | open Even app details, continue |
| pairing-page | `Pairing(onboarding)` | scan lifecycle tied to `STARTED`, aggregator (pure Kotlin port of the TS pairing logic), explicit selection, save addresses + identity |
| config-page | `ManualDevices(onboarding)` | MAC validation/normalisation, bonded-device fill, identity card |
| onboarding-firmware-check-page | `FirmwareCheck` | probe, classification table §2.8, font extraction |
| onboarding-flash-page | `Flash(mode, fromOnboarding, autoStart)` | prompt/battery/build/flash state machine §2.9; block back while writing |
| main-page (+ remote controls, editors, modal) | `Main` | connection snapshot, mirror bitmap flow, gesture → input mapping, text-editor & keyboard sessions, warnings, overflow actions |
| conversations / conversation / speakers / ask | `Conversations(speakerId?)`, `Conversation(id)`, `Speakers`, `AskConversations` | later milestone |
| document-page | `Document(name)` | render bundled asset text |

Main-screen details worth reproducing exactly: status label table (§2.10.2), mirror sizing and
stand-in messages (§2.10.3), pad gesture tables (§2.10.4–2.10.5), editor commit/cancel semantics
(§2.10.6), warning list (§2.10.8). For gestures, implement one `pointerInput` detector with the
thresholds of the shared recognizer the iOS port uses (`app/phone-ui/phone-gestures.ts:10-69`):
hold = 500 ms; slop 14 dp; swipe when the larger axis delta ≥ 30 dp; a single tap is emitted
after 280 ms unless a second down lands within 36 dp (→ double-tap, or tap-then-hold if held);
any second pointer → back (double-tap) on release; a hold always emits a release on up/cancel.

### 8.4 Milestones

| Area | v1 (essential) | Later |
|---|---|---|
| Onboarding | disclaimer, permissions, disconnect-other-apps, pairing scan + manual MACs, firmware check, flash install/uninstall, preview-only | — |
| Main screen | mirror (white/green palette), Watch/Ring pads, mirror touch, Settings tab (display mode, brightness), mic + keyboard buttons, text-setting editor, keyboard panel, warnings modal, connect/disconnect/pair menu | screenshots, GIF recording, BLE bandwidth overlay |
| Keep-alive | FGS (connectedDevice + conditional microphone/location), G2-screen wake lock, battery-optimization flow, phone-lock monitor | OEM-specific guidance |
| Notifications | listener, list/icons/actions/dismiss, posted events, per-app mute, Even-app detection | text quick-reply via `RemoteInput` (new capability) |
| Media | session controller (state, play/pause/skip, volume, art, queue), ignored-apps list | MediaBrowser library browsing |
| Settings | typed store, key compatibility, adb export/import (DUMP-guarded receiver + scripts) | in-app export to a user-chosen file (SAF), portable JSON |
| Alarms | — (only if the Timers app ships in v1; then all of §3.6) | full alarm subsystem |
| Other | share `text/plain` → glasses document, rotation lock, open-URL, status icons, phone battery, calendar, one-shot location | location tracker (Navigate), captions store + review UI, Wear OS, QR scanner, EvenHub WebView hosting, Input API, on-device models (sherpa-onnx, llama.cpp) |

### 8.5 Implementation notes for v1 components

* **Permissions**: model each card as `{id, required, isGranted(), request}` exactly as in §2.4;
  POST_NOTIFICATIONS only on API ≥33; BLE permission set by API level; fine location card counts
  only precise access. Re-check on `Lifecycle.Event.ON_RESUME`. Never request runtime permissions
  from background code paths — route "permission needed" to a phone notification or a pending
  prompt shown next time the Activity is visible (the original requests from glasses-triggered
  flows and silently fails without an Activity).
* **FGS**: `ServiceCompat.startForeground(id, notification, types)` within 5 s of
  `startForegroundService`; types = `CONNECTED_DEVICE` ∪ (`MICROPHONE` if `RECORD_AUDIO`) ∪
  (`LOCATION` if fine location); recompute when permissions change *while the app is in the
  foreground*; low-importance channel with `showBadge=false`; prefer `START_NOT_STICKY` unless the
  service can actually re-establish the session itself after a restart.
* **Wake lock**: partial, non-reference-counted, held only while the glasses display is on,
  released on display-off/charging/disconnect; consider a generous timeout as a safety net.
* **Notification listener**: expose a `StateFlow<List<PhoneNotification>>` refreshed on
  posted/removed/ranking-changed (the original only reports "posted" and re-reads lazily); keep the
  filtering, grouping, icon rendering and de-duplication rules from §3.4.
* **Settings store**: keep SharedPreferences (not DataStore) if compatibility with the adb
  scripts/`run-as` path matters; commit synchronously before `System.exit` on import; exclude the
  settings file from Auto Backup or strip secrets (see §9).
* **Text editing from the glasses**: a single `TextEditSession` object in the app graph
  (settings list, originals, toggle, callbacks) observed by the Main screen; write-through on each
  keystroke without feeding the value back into the `TextField` state.

---

## 9. Gotchas and pitfalls the original hit (or is exposed to)

**Background execution and power**

1. **Deep Doze ignores wake locks** of apps that are not battery-optimization exempt: BLE traffic
   and render timers stall to a few fps with the phone still and screen-off. The exemption is a
   required permission card and a main-screen warning (`app/native/battery-optimization.ts:5-12`).
2. **FGS type rules (API 34+)**: claiming `microphone`/`location` without the matching permission
   makes `startForeground` throw, so types are computed at each (re)start
   (`FaceclawForegroundService.kt:116-130`). Location granted later only takes effect at the next
   UPDATE (≤60 s while connected). Uncertain: the 60 s UPDATE re-issues `startForeground` from the
   background with the `microphone`/`location` types; Android 14's while-in-use rules for those
   types may reject that on some devices — verify on hardware.
3. **`START_STICKY` without self-sufficient logic**: if the process dies, the system restarts the
   service with a null intent, which posts "Connected to glasses" but nothing reconnects (the
   session lives in the JS controller) (`FaceclawForegroundService.kt:40,65`). Behaviour after
   restart is unverified.
4. **Notification channel settings are immutable**: to stop Samsung's launcher counting the
   pinned notification as a badge the channel id had to change (`faceclaw-dashboard` →
   `faceclaw-connection`, old one deleted) (`FaceclawForegroundService.kt:26-32,86`).
5. **OEM task killers** (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Realme, Meizu, Asus) are flagged by
   the alarm self-check; README warns that manufacturer battery software may pause/throttle the
   app (`FaceclawAlarms.kt:340-344`, `README.md` "Additional Caveats").
6. **Phone-lock detection** relies on runtime-registered `SCREEN_ON/SCREEN_OFF/USER_PRESENT`
   broadcasts + `KeyguardManager.isDeviceLocked`, registered only while a session exists
   (`FaceclawBleCommunicator.kt:117-123,144`).
7. **Rendering the mirror while nobody can see it** built 640×480 bitmaps continuously and put
   pressure on the native heap (paused views retained queued images); gate on window focus and
   `PowerManager.isInteractive()` (`dashboard-controller.ts:2729-2740`).
8. **EvenHub WebViews freeze** when the activity stops (Chromium page visibility) and timers are
   throttled with the screen off; needed the occluded overlay, a WebView that always reports a
   visible window, and host-driven timer/rAF ticks (`FaceclawEvenHubWebViewHost.kt:14-57`,
   `FaceclawEvenHubWebView.kt:7-35`; CHANGELOG 0.7.1 notes Hub apps freezing on iOS).

**Bluetooth, pairing, the Even app**

9. **Only one app/phone can hold the glasses.** The stock Even app (`com.even.sg`) must be
   disconnected (or its Nearby-devices permission revoked); detection relies on its persistent
   notification title "Even Notification Service" and on notification access — fragile to
   localisation/app updates (`FaceclawEvenAppDetector.kt:15-47`).
10. **A connected arm stops advertising** — disconnect before scanning/pairing, and treat pairing
    as a detour (lift auto-reconnect suppression) (`main-view-model.ts:915-933`).
11. **Each lens bonds separately**; the OS pairing dialog can appear twice during probe/flash;
    missing bonds put the session into an "unpaired" state where retrying is pointless
    (`onboarding-firmware-check-view-model.ts:202-214`, `dashboard-controller.ts:1650-1685`).
12. **Scan pitfalls**: Android ≤11 returns no results unless Location is on;
    `BluetoothDevice.getName()` can hold a stale cached name (use the scan record); Android strips
    the manufacturer company id (re-insert it) (`pairing-view-model.ts:232-241`,
    `FaceclawDeviceDiscovery.kt` header).
13. **Never pad a one-armed selection with stored addresses** — that "welds together arms of two
    different pairs" (`pairing-view-model.ts:309-312,391-398`).
14. **Flashing needs the glasses to itself** and enough battery (≥30 % both arms); after an
    uninstall do not auto-reconnect (stock firmware is immediately flagged incompatible)
    (`onboarding-flash-view-model.ts:33,61-65,505-512`).

**Permissions and platform policy**

15. **Permission prompts need a visible Activity.** Glasses apps (Weather, Compass, Navigate,
    Calendar, EvenHub location) request permissions via the foreground-or-start activity; with the
    phone locked the prompt cannot be shown (`app/g2/android-permissions.ts:16-20`).
16. **Special-access settings can't be observed**: battery exemption, notification access, exact
    alarms, full-screen intents, all-files access are granted in system screens; re-check on
    resume or poll briefly (`permissions-view-model.ts:138-147`, `dashboard-controller.ts:1067-1078`).
17. **Package visibility**: without `QUERY_ALL_PACKAGES`, `Icon.loadDrawable` of other apps'
    notification icons fails (`AndroidManifest.xml:47-50`). This, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
    `MANAGE_EXTERNAL_STORAGE` and `USE_EXACT_ALARM` are Play-policy-sensitive — the project assumes
    sideloading.
18. **Notification access gates media sessions too**; `addOnActiveSessionsChangedListener` throws
    before the grant, so a `ContentObserver` on `enabled_notification_listeners` re-registers
    after the user grants access (`FaceclawMediaController.kt:73-87,134-156`).

**Notifications and media**

19. Small icons are **alpha templates** (colour channels may contain garbage, e.g. Discord): tint
    white; downscale by repeated halving; one icon per group; skip own package, media-session and
    transport notifications; suppress re-posts of ongoing notifications
    (`FaceclawMediaNotificationListenerService.kt:301-313,337-468`).
20. **Actions can't carry reply text**: `invokeNotificationAction` just fires the PendingIntent;
    RemoteInput-based quick replies are not really supported (`:217-242`).
21. **MediaBrowser**: unbinding right after `playFromMediaId` lets the system kill the player
    before it goes foreground — keep the binding 5 s (`FaceclawMediaBrowser.kt:38-41,246-266`).
    Browsers/social apps publish sessions for embedded clips — ignore them by default
    (`media-apps.ts:5-21`).

**Alarms**

22. Use `setAlarmClock` (exact, Doze-proof, shows the alarm icon) with an inexact fallback when
    exact alarms are revoked; `USE_EXACT_ALARM` on 13+, `SCHEDULE_EXACT_ALARM` (maxSdk 32) on 12
    (`AndroidManifest.xml:28-34`, `FaceclawAlarms.kt:136-155`).
23. **Force-stop clears alarms** but not the stored schedule; the self-check compares against
    `nextAlarmClock` and re-arms (`FaceclawAlarms.kt:185-198,345-348`).
24. The reschedule receiver's code handles `LOCKED_BOOT_COMPLETED`, but the manifest does not
    declare it and the receiver is not `directBootAware`, so alarms are re-armed only after the
    first unlock (`FaceclawAlarmRescheduleReceiver.kt:18`, `AndroidManifest.xml:148-160`) — even
    though the store is device-protected for exactly that case.
25. Alarm notifications must be **silent** with sound played by the service on `USAGE_ALARM`
    (so ringer mode/DND-with-alarms don't silence it); a muted alarm stream is raised to 60 % and
    restored; full-screen intents need `canUseFullScreenIntent()` on 14+
    (`FaceclawAlarmService.kt:358-443`, `FaceclawAlarms.kt:308-310`).

**Settings, storage, security**

26. **Settings import must kill the process** (500 ms after replying), otherwise the running app's
    in-memory SharedPreferences overwrite the imported file on the next `apply()`
    (`FaceclawSettingsPortReceiver.kt:57-69`); the scripts also force-stop first.
27. **Exports contain API keys and tokens**; the release path stages files in app-specific
    external storage (world-readable to `READ_EXTERNAL_STORAGE` holders on Android 7–10) and the
    scripts delete them immediately (`FaceclawSettingsPortReceiver.kt:35-42`, `scripts/pull_config.sh:50-51`).
28. **`allowBackup="true"` with no backup rules**: Auto Backup may upload `faceclaw_settings.xml`
    (API keys, bridge tokens, input-token hashes) and restore it on another device
    (`AndroidManifest.xml:61`). A rebuild should exclude secrets via
    `dataExtractionRules`/`fullBackupContent` or disable backup.
29. **Two preference files**: pairing/onboarding keys live in NativeScript's ApplicationSettings,
    so exports don't carry them, although the receiver's doc comment says "device addresses"
    (`FaceclawSettingsPortReceiver.kt:21-25`) and a code comment mentions "an imported config can
    arrive paired" (`dashboard-controller.ts:1080-1085`).
30. **Two-way binding while typing drops characters** (a pasted 51-char key stored as 46): do not
    echo the stored value back into the field per keystroke (`dashboard-controller.ts:1148-1162`).
31. **`usesCleartextTraffic="true"` app-wide** for `ws://` g2mirror over a tailnet
    (`AndroidManifest.xml:56-65`); a rebuild should scope cleartext with a network-security config
    where possible.

**Build and native code**

32. **arm64-v8a only**; all JNI libs are arm64 and the lc3 library is linked with 16 KB page
    alignment for newer devices (`app.gradle:131-141,271-278`).
33. The vendored `com.k2fsa.sherpa.onnx` Java classes are the JNI ABI of the prebuilt
    `libsherpa-onnx-jni.so` — package, class and field names must match the pinned sherpa-onnx
    version (`native/kotlin/android-java-boundaries.json`).
34. The build downloads sherpa-onnx, liblc3 and llama.cpp at build time and needs the NDK and the
    SDK CMake package; the llama build takes minutes (`app.gradle:24-240`).
35. **Wear routing requires identical package name and signing key** on phone and watch; debug and
    release keys don't interoperate (`scripts/release.sh:1-12`, `wear/PROTOCOL.md`).

**Miscellaneous**

36. Hotspot detection uses the hidden `WifiManager.getWifiApState` via reflection (may be blocked
    by hidden-API restrictions; fails closed); cellular level needs `READ_PHONE_STATE` on most
    versions and is silently skipped (`FaceclawSystemStatusIconProvider.kt:68-103`).
37. Deprecated APIs in use: `LocationManager.requestSingleUpdate`, `Criteria`,
    `WifiManager.getConnectionInfo` — replace with `getCurrentLocation` and
    `NetworkCapabilities.transportInfo` in a rebuild.
38. Stale cached location fixes make navigation start at the previous destination; accept only
    fixes ≤60 s old by the monotonic clock (`FaceclawLocationTracker.kt:26-39`).
39. The main activity declares broad `configChanges` so NativeScript never recreates it; a Compose
    rebuild should instead survive recreation (ViewModels, app-scoped state) and handle share
    intents in both `onCreate` and `onNewIntent` (`AndroidManifest.xml:89,93`, `share-intents.ts:10-23`).

---

## 10. Open questions

1. **ApplicationSettings file** — confirm NativeScript's Android `ApplicationSettings` file name
   (believed `prefs.db`) and whether `setNumber` stores floats (would truncate
   `deviceIdentity.pairedAtMs`, an epoch-ms value, to ~2-minute precision). Matters only for
   migrating existing installs.
2. **Should exports include pairing?** The receiver comment says device addresses are exported but
   they are not. Decide for the rebuild (recommendation: separate "phone-specific" group, excluded
   by default, as the iOS tool does).
3. **`SettingsDocument` schema 2** is unused in this commit; was it meant to replace the Android
   XML path? The iOS tool still speaks schema 1.
4. **Sticky service restart** — what users actually see after the process is killed while
   connected (notification without a session?) and whether auto-reconnect should happen from the
   service.
5. **FGS `microphone`/`location` re-assertion from background** on Android 14/15 — any
   `SecurityException`s in the field?
6. **`LOCKED_BOOT_COMPLETED`** is handled in code but not declared — oversight or intentional?
7. **Quick reply**: README advertises "mark as read or quick reply" actions; without RemoteInput
   results a reply action probably fails or opens the app. Is text reply a requirement?
8. **`voice.enabled`** ("Enable", master voice switch) is defined but not listed in the Settings
   sections shown in `settings-menus.ts`; where (if anywhere) is it exposed?
9. **Storage permissions** `READ/WRITE_EXTERNAL_STORAGE` have no `maxSdkVersion`; confirm they are
   only needed on API ≤29 (with `MANAGE_EXTERNAL_STORAGE` above).
10. **MediaBrowser reachability**: many players restrict `MediaBrowserService` clients to allow-listed
    packages (e.g. Android Auto); how many real players accept Faceclaw?
11. **Welcome sound / onboarding flags** are stored in a different store from everything else; is
    that intentional (so resetting onboarding keeps settings) or historical?
12. **Keyboard panel labels on Android** come straight from the glasses menu's target labels,
    while the shared view-model maps ids to "Send to Agent"/"Type into App"; which wording is
    canonical?
13. **Conversations naming**: the phone "Conversations" UI shows Microphones-app caption sessions,
    not assistant chats (which have no phone UI). Keep the name?
14. **Screen-off preview mode**: in preview-only mode the "screen timeout" also blanks the phone
    mirror (§2.10.3); is that desired on a phone-only demo?
15. **Auto-reconnect suppression is in-memory only** (`app/g2/reconnect-policy.ts:137-149`): a
    restart after a deliberate Disconnect auto-connects again. Intended?
