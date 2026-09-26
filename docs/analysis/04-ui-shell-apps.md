# Spec 04 — On-glasses UI: framework, shell / window manager, built-in apps

Reference implementation: Faceclaw (`/home/user/jimrandomh/faceclaw`, commit `a6291cf`).
All citations are `path:line` relative to that checkout. This document describes *behaviour*,
so an engineer can build an equivalent native-Kotlin system with its own visual design.
Where the rebuild is free to differ, that is called out ("rebuild note"). Where the
original behaviour is unclear, it is marked **(uncertain)**.

Out of scope (covered by other specs): BLE transport / firmware protocol, the texture-cache
wire format, the voice-transcription engines, the LLM assistant session itself, EvenHub SDK
emulation internals, phone-side (Android Activity) UI. They are mentioned only where they
touch the on-glasses UI.

---

## 0. Conventions and glossary

| Term | Meaning |
|---|---|
| Panel / lens | The G2 display: 640 × 480 px, one greyscale channel shown green on the glasses. Constants `G2_LENS_WIDTH/HEIGHT` (`app/graphics/image.ts:7-8`). |
| Gray value | 8-bit value 0..255 used by all drawing code; quantized to 16 levels (4 bpp) before transmission: `nibble = min(15, (v+8)>>4)` (`app/graphics/image.ts:660`). So visually distinct steps are 16 apart; values < 8 are black. |
| Transparent vs black | On the **shell** surface (colour-keyed) value **0 = transparent**, value **1 = opaque black** (`SHELL_OPAQUE_BLACK`, `app/ui/shell/geometry.ts:74`). Window surfaces are opaque (0 = black). |
| Gestures | `click` = single tap; `double-click` = double tap; `scroll-up` / `scroll-down` = ring swipe / temple-touchpad swipe (one step per event); `long-press` + later `long-press-release` = press-and-hold; `short-then-long-press` = the firmware-2.2.9 "tap-then-hold"; `ring-press` = raw ring touch-down (no gesture interpretation). `swipe-left/right/up/down` exist only from the Wear OS watch (and the phone mirror). See §4. |
| Gesture glyphs in hints | `●` click, `●●` double-click, `▲▼` scroll, `—` long-press, `●—` tap-then-hold (`app/ui/gestures.ts:126-133`); hint lines are `"<glyph> <action>"` pairs joined by three spaces (`app/ui/gestures.ts:140-142`). |
| Window | A top-level app surface registered with the shell and shown in the sidebar (app switcher). |
| Layer | A paintable/input-handling unit on a `LayerStack` (app pages, menus, dialogs). |
| Shell | The window manager: sidebar, top bar, focus, screen on/off, shell overlays (`app/ui/shell/shell.ts`). |
| Controller | `DashboardController`, the process-wide orchestrator that owns the glasses connection, compositor surfaces, and app launching (`app/g2/dashboard-controller.ts:200`). |
| Band | The vertical strip of the panel a window occupies (see §2.2). |
| Depth | Stereo depth in firmware units: each lens shifts the content horizontally by half the depth in opposite directions; positive = nearer (`app/graphics/display-list.ts:114`, `app/apps/glanceboard/glanceboard-settings.ts:109-125`). |

---

## 1. Architecture

### 1.1 Big picture

```
 Inputs (ring / temple touchpads via glasses, Wear OS watch, phone mirror, CLI tokens)
        │ RawInputEvent (kind, eventType, eventSource, frameId)
        ▼
 DashboardController.handleInputEvent ── lock screen gate ── sleep-time Glanceboard gate ── wake barrier
        │ InputEvent
        ▼
 Shell.receiveInput ──► shell overlays (LayerStack over chrome)  ──► sidebar (app switcher) ──► focused window
                                                                                               │
          ┌────────────────────────────────────────────────────────────────────────────────────┤
          ▼ in-process window (main thread)                          ▼ worker window (one Web Worker per app)
    LayerStack of app layers → paint() → Plane[]            postMessage(input/render/…) → worker paints Plane[]
          │                                                          │ submits pixels straight to native (Android)
          ▼                                                          ▼
 Compositor (native): surfaces by z-order: windows z0 (opaque, only foreground visible), shell z1 (color-key),
 Glanceboard z900 (opaque), lock screen z1000 (opaque)  ──► quantize 4bpp, texture cache, display lists ──► BLE ──► G2
```

The controller is also the rendezvous for everything that is not the UI proper: connection
phases, brightness, EvenHub session suspend/resume, wear/lock state, notifications from Android,
preview (phone mirror) updates, timers for the clock and screen timeout
(`app/g2/dashboard-controller.ts:200-455`).

### 1.2 Components and responsibilities

| Component | File | Responsibility |
|---|---|---|
| DashboardController | `app/g2/dashboard-controller.ts` | Singleton orchestrator. Configures the shell (`:361-389`), runs app `boot` hooks (`:390-392`), registers assistant tools, owns compositor surfaces (`configureWindowSurface` `:2428-2442`), window frame submission (`:2453-2475`), shell render loop (`requestShellRender`/`renderShell` `:2490-2559`), input pre-routing (`handleInputEvent` `:2112-2241`), launching (`launchApp` `:2393`, `launchInProcessApp` `:2251`, `ensureWorkerHost` `:2290`), open-app persistence (`:2343-2391`), lock screen (`:615-715`), Glanceboard host (`:260-275`), phone-mirror touches (`:1830-1879`), synthetic input (`:1799-1821`). |
| Shell | `app/ui/shell/shell.ts` | Window registry & sidebar order, selection (= foreground window), MRU, focus (sidebar vs window), screen on/off & idle timeout, shell overlay stack, system (escape) menu, voice/keyboard/assistant dialogs, alerts, notification modal, battery state and tray icons for the chrome (`:310-1541`). |
| ShellChromeLayer | `app/ui/shell/chrome-layer.ts:202` | Base layer of the shell surface: sidebar (app switcher) + top bar + ambient cards. Never consumes input. |
| LayerStack / Layer | `app/ui/layers.ts:82-300` | Generic retained layer stack used by the shell, by each in-process window, by modals, and by worker-side menus. |
| In-process window | `app/ui/shell/in-process-window.ts:84` | Adapts a main-thread `LayerStack` to the shell's `ShellWindow` interface. |
| Worker host | `app/ui/shell/worker-window.ts:232` | Owns one app Worker, adapts each of its windows to `ShellWindow`, speaks the message protocol. |
| Menu core | `app/ui/menu-core.ts:100` | Headless list/selection/scroll/animation component, used everywhere. |
| MenuLayer | `app/ui/menu.ts:196` | Boxed menu page/popup built on Menu core. |
| Settings model | `app/ui/dashboard-settings.ts` | Typed setting objects (bool/enum/string) and their menu-row builders. |
| GlanceHost | `app/g2/glance-host.ts:48` | Sleep-time dashboard surface + visibility state machine. |
| App registry | `app/apps/all-apps.ts:34`, `app/apps/app-definition.ts:16` | The list of built-in apps and the contract each exports. |

### 1.3 The Layer / LayerStack model

A `Layer` (`app/ui/layers.ts:82-117`) has:

- `paint(ctx, paintBelow) → GrayImage` — returns a full-size canvas (stack base size). Calling
  `paintBelow()` returns a fresh transparent canvas *and declares that the layers below stay visible*;
  a layer that never calls it replaces everything under it ("opaque page") (`:55-67`).
- `handleInput(event, ctx)`; optional `acceptsDirectional` — if false, watch swipes are converted to
  ring vocabulary before delivery (`:244`, `app/ui/gestures.ts:100-114`: up/down→scroll, right→click, left→double-click).
- optional `depth` (stereo), `dimUnderneath` (0..1 brightness factor applied to everything below),
  `paintOverBase` (skip intermediate layers and composite directly over the base),
  `hitTest(x,y)` (phone-mirror touches; only delivered to the base layer when nothing is stacked,
  `:255-260`), `receiveTextInput(text)` (dictation / phone keyboard target), `onRemoved()` (cleanup; fired
  on pop/clearToBase/popThrough).
- `LayerContext = { stack, actions }` where `actions` (`LayerActions`, `:12-39`) are services: `requestRender`,
  `disconnect`, phone text-editor start/end, push-to-talk and continuous voice capture start/stop,
  `playBuzzerSequence`.

`LayerStack` (`:119-300`) holds a base layer plus pushed layers; ops: `push`, `pop` (never pops the
base), `popIfTop(pred)`, `topMatches(pred)`, `clearToBase`, `popThrough(layer)`, `isAtBase`,
`get/setBaseSize`, `isFocused()` (drives highlight style), `paint() → Plane[]`, `handleInput` (to the
top layer only), `receiveTextInput` (top layer), `hitTest`.

Painting produces **planes** (`app/graphics/plane.ts:14-25`): one per layer that participates, bottom
to top. Each plane = image + x/y + optional `shellKey` (stable id per layer for incremental shell
updates, `app/ui/layers.ts:121-125`), `depth`, `dimUnderneath`. A plane's raster covers *everything*
below it including glyphs, while glyphs drawn in the *same* image always render above that image's
raster (`app/ui/layers.ts:63-66`). This is why several apps put overlays (description band, menus)
in their own layer.

Rebuild note: a native rebuild can use a conventional scene graph; the essential contract is
(a) stack-based modal navigation where double-click pops, (b) per-layer "transparent over the
previous" vs "opaque page", (c) optional dim-below and depth per overlay.

### 1.4 Compositor surfaces and z-order

| Surface | Id | z-order | Transparency | Rect | Visible when | Source |
|---|---|---|---|---|---|---|
| App window | `window:<windowId>` | 0 | opaque | the window's app-viewport rect (§2.2) | only the foreground window (selection) | `app/g2/dashboard-controller.ts:2428-2442`, `app/ui/shell/shell.ts:999-1009` |
| Shell | `shell` | 1 | colour-key (0 = transparent) | full panel | screen on (painted empty when asleep) | `app/g2/dashboard-controller.ts:1522-1529`, `app/ui/shell/shell.ts:696` |
| Glanceboard | `glance` | 900 | opaque | full panel | while the board shows (screen "asleep") | `app/g2/glance-host.ts:20-22,153-165` |
| Lock screen | `lock-screen` | 1000 | opaque | full panel | while glasses are locked | `app/g2/dashboard-controller.ts:675-713` |

Screen-off is a compositor-level *blank* flag (all surfaces keep their retained frames) plus a
brightness fade (§2.11); waking unblanks and re-shows retained content (`app/g2/dashboard-controller.ts:581-613,717-761`).

The shell surface is not sent as one bitmap: `Shell.paintScene()` encodes the chrome and each overlay as
separate cropped layers ("shell scene"), each with a stable key, its own depth and an optional
dim-underneath factor (`app/graphics/shell-scene.ts:6-39`, `app/ui/shell/chrome-layer.ts:221-234`).
The chrome contributes: sidebar crop (key 1, depth 0), top-bar crop (key 2, **depth −2**), and one crop per
ambient card. A layer's `dim < 256` darkens everything composited beneath it, including window
surfaces (`app/graphics/surface-compositor.ts:93`).

### 1.5 Shell overlays (z-ordered layers on the shell's LayerStack)

The shell `LayerStack` base is the chrome; overlays are pushed above it (top = receives input):

| Overlay | Class | Opens on | Closes on | Dims below | Depth | Source |
|---|---|---|---|---|---|---|
| System (escape) menu | `ShellOverlayMenuLayer` | long-press; extended hold; app without own menu answering tap-then-hold | double-click, any entry | 0.25 | 4 | `app/ui/shell/shell.ts:214-243,1430-1501` |
| Brightness picker | `BrightnessPickerLayer` | system menu › Brightness | click / double-click | — | 0 | `app/ui/shell/brightness-picker-layer.ts:14-66` |
| Tool debug list/detail | `ToolDebugMenuLayer` | system menu › Debug | double-click | — | 0 | `app/ui/shell/tool-debug-layer.ts:39-158` |
| New-notification modal | `ShellModalLayer(SingleNotificationLayer)` | Android notification posted (enabled source) | Back/Dismiss/double-click | — | 0 | `app/ui/shell/shell.ts:664-683`, `app/ui/shell/modal-layer.ts:40-66` |
| Alert popup | `ShellAlertLayer` | `shell.showAlert(text)` (assistant `show_alert`, errors) | click/double-click, 6 s timeout | — | 0 | `app/ui/shell/shell.ts:246-303,1380-1395` |
| Voice dialog | `VoiceInputLayer` | system menu › Voice input; wakeword; app request | send / discard / double-click | — | 0 | `app/ui/shell/voice-input.ts:68`, `app/ui/shell/shell.ts:1015-1131` |
| Keyboard dialog | `KeyboardInputLayer` | phone "keyboard" button | send / discard | — | 0 | `app/ui/shell/keyboard-input.ts:51`, `app/ui/shell/shell.ts:1142-1186` |
| Assistant overlay | `AssistantLayer` | text sent to assistant (not from AI Chat) | Done / double-click | — | 0 | `app/ui/shell/assistant.ts:32`, `app/ui/shell/shell.ts:1266-1377` |

Window-level context menus (tap-then-hold) are *not* shell overlays: they live on the window's own
stack (`WindowMenuLayer`, `app/ui/window-menu.ts:30`) and are therefore app-drawn; the system menu
is always shell-drawn so a hung app can always be escaped (`app/ui/shell/shell.ts:1422-1429`).
When the system menu opens, the shell sends the pseudo-event `system-menu-opened` to the foreground
window so that an open app menu closes and the two menus never stack (`:1497-1499`,
`app/ui/shell/in-process-window.ts:180-186`, `app/ui/window-menu.ts:144-148`).

### 1.6 Window model

#### 1.6.1 The `ShellWindow` contract (`app/ui/shell/shell.ts:79-161`)

| Field | Meaning |
|---|---|
| `appId`, `windowId` (unique, namespaced, e.g. `terminal:view:3`), `title` | identity |
| `surfaceId` | compositor surface (`window:<windowId>`) |
| `closeable` | whether the system menu offers "Close window" (launcher = false) |
| `heightMode` | `"min"` / `"medium"` / `"max"` (§2.2) |
| `drawIcon(image,x,y,size,inverted)` | sidebar icon painter (inverted = black on white for the focused tab) |
| `handleInput(event, frameId)` | deliver an input; the window takes ownership of the latency-tracking frame id |
| `requestRender()`, `relayout?()` | repaint; re-measure after a display-mode change (absent ⇒ controller closes & relaunches) |
| `hasAppMenu?()` | tap-then-hold currently opens an app menu with entries (controls system-menu footer hint) |
| `claimsLongPress?()` | the window uses long-press itself (games) — shell forwards it, with a 4 s escape timer |
| `holdToTalk?` | AI Chat mode: click = app menu, long-press = microphone, tap-then-hold = system menu, no escape timer |
| `isVoiceCapturing?()` | window owns the mic (suspends screen timeout, swallows wakeword) |
| `acceptsDirectional?` | deliver raw watch swipes (else fallback mapping) |
| `hitTest?(x,y)` | phone-mirror tap in viewport coordinates |
| `receiveTextInput?(text,{submit})` | dictation/phone-keyboard target; absence hides "Type Into App" |
| `setForeground?(bool)` | became/stopped being the visible window |
| `onFocus?(lastInput)` | input focus moved into this window (lastInput lets the window adapt to watch vs ring) |
| `setScreenOn?(bool)` | screen on/off |
| `setInputFocus?(bool)` | receives ordinary input right now (false also under any shell overlay or screen-off) — games pause on false (`:154-160`, diffed in `syncInputFocus` `:581-589`) |
| `close?()` | app-side cleanup when the shell closes it |

#### 1.6.2 In-process windows (main thread)

`createInProcessWindow(options)` (`app/ui/shell/in-process-window.ts:84-232`) builds a `LayerStack`
sized to `appViewportSize(heightMode)` whose `isFocused` is `shell.isWindowFocused(windowId)`
(`:101-106`). It:

- renders on every input and on `requestRender()`: paint stack → planes → `submitFrame` (the native
  side dedupes unchanged frames, so resubmitting is cheap) (`:114-137`); if the paint used stale
  cached data (e.g. notification icons) a follow-up render with fresh data is scheduled (`:108-136`);
- implements tap-then-hold: opens a `WindowMenuLayer` with `menuItems()` (evaluated at open time), or
  asks the shell to open the system menu if the app has no entries (`:143-152,192-197`); for
  `holdToTalk` windows a *click at the base layer* opens it instead;
- closes its own menu on `system-menu-opened` (`:180-186`);
- `close()` clears the stack (firing `onRemoved` so layers release hardware), calls `onClosed`, removes
  the surface (`:168-175`);
- `setHeightMode(mode)` resizes the viewport and the surface at runtime (`:223-230`) — used by EvenHub
  (tall canvas) and the Glanceboard app (2×3 preview).

`YieldAtRootLayer` (`:239-270`) wraps an app root so that double-click at the root yields focus to
the sidebar (the standard "leave app" gesture); apps with multi-level roots (launcher, settings,
files, glanceboard, music) handle double-click themselves instead.

In-process apps: launcher, AI Chat, timers, calculator, files (+ document windows), music, Nightscout,
transcribe, teleprompter, microphones, notifications, calendar, weather, compass, developer, EvenHub
store & EvenHub app windows, Glanceboard (configuration app), settings (all `index.ts` under `app/apps/*`).

#### 1.6.3 Worker windows (one Web Worker per app)

Apps whose logic is heavy or long-running run in a dedicated JS Worker: **blocks, flappy, freecell,
minesweeper, pinball, navigate, roam, terminal** (`app/apps/*/index.ts` using
`launchWorkerAppWindow`, `app/apps/app-definition.ts:128-168`). Why (inferred from code comments):
isolation of CPU-heavy/real-time work (game physics at 60–120 Hz, xterm emulation with websocket
streams, route following + map fetching) from the main thread that runs the shell and input
routing, so input to the shell stays responsive and "the shell keeps working when a window's handler
hangs" (`app/ui/shell/shell.ts:67-73`); workers also submit frames directly to native on Android,
bypassing the main thread (`app/ui/shell/worker-window.ts:12-16,211-217`). **(uncertain: no explicit
design note states the motivation.)**

One worker hosts all windows of its app (e.g. terminal hub + session views), routed by `windowId`.
Messages are small JSON; pixels never cross (except iOS and tray icons).

**Shell → worker (`WorkerAppMessage`, `app/ui/shell/worker-window.ts:17-38`)**

| Message | Fields | Purpose |
|---|---|---|
| `open-window` | windowId, surfaceId, title, viewport{w,h} | create per-window state; worker paints at this size |
| `resize-window` | windowId, viewport | display-mode change (navigate, terminal only; others are closed+relaunched, `:461-463`) |
| `close-window` | windowId | release window state |
| `input` | windowId, event, frameId, focused | a gesture (after directional fallback if the window didn't opt in) |
| `text-input` | windowId, text, submit? | dictated/typed text |
| `render` | windowId, focused | repaint request |
| `foreground` | windowId, foreground, focused | visibility change |
| `input-focus` | windowId, focused | on change only; pause games on false |
| `screen` | on | screen on/off (per app) |
| `navigation-sensors` | event | location/compass events relayed from main thread (navigate) |
| `tool-call` | callId, windowId, name, args | assistant tool invocation → reply `tool-result` |
| `check-idle` | — | last window closed; reply `worker-idle` unless background work continues |
| `shutdown` | — | release resources, reply `worker-stopped` |

**Worker → shell (`WorkerAppReply`, `:40-165`)**

| Reply | Effect in host |
|---|---|
| `worker-ready` | host flushes its queue; everything posted before is queued, because messages to a still-loading worker can be dropped (`:239-246,286-291`) |
| `yield-focus` | shell.yieldFocusToSidebar (only if still foreground) |
| `focus-window` | foreground+focus one of the app's windows |
| `wake-window` | only if screen off: focus the window and wake (terminal bell) |
| `open-window-request` | open another window of this app (title, icon, iconGlyph, focus, heightMode) |
| `close-window-request` | shell closes the window |
| `open-system-menu` | app has no context menu: open the system menu instead |
| `set-window-gestures` | {hasAppMenu, claimsLongPress} — reported on change |
| `set-attention` | sidebar attention dot on/off |
| `set-icon-activity` | `idle`/`on`/`off` animation phase of the sidebar icon (terminal blinking cursor) |
| `set-title` | informational only (ignored) |
| `set-tray-icon` | small top-bar image {w,h,pixels[]} or null |
| `start-voice-input` | open the shell voice dialog aimed at this window |
| `open-settings` | open Settings app at a section |
| `start-text-setting-edit` / `end-text-setting-edit` | open/close the phone text editor bound to a string setting id |
| `publish-state` | key/value JSON mirrored on main thread for widgets (`app/ui/shell/worker-state.ts:11-38`) |
| `set-tools` / `tool-result` | declare assistant tools (prefixed `app.<appId>.`) and answer calls (15 s host timeout, `:230`) |
| `buzzer-sequence`, `open-url` (http/https only), `navigation-sensors` (request) | platform services |
| `surface-frame` | iOS only: base64 pixels (Android workers call native directly) |
| `worker-idle`, `worker-stopped` | lifecycle |

**Worker lifecycle state machine** (`app/ui/shell/worker-window.ts:252-436`, `app/ui/shell/worker-lifecycle.ts:4-7`):

```
 (none) --launch--> SPAWNED(queueing) --worker-ready--> READY
 READY --open-window--> READY(n windows)
 READY(last window closed) --check-idle--> worker replies worker-idle? ─ no (background work) → stays alive
                                                                     └ yes → STOPPING
 STOPPING: host drops from app cache, clears queue, stops nav sensors, clears tray icon & published state,
           posts shutdown, arms 5 s kill timer --worker-stopped or timeout--> TERMINATED
```

Worker windows are always `closeable`; window ids are namespaced by app. A worker window only gets
the *foreground*/*focused* flags pushed with each message and infers "foreground" from any focused
input/render (`app/apps/minesweeper/minesweeper-app.worker.ts:257-266`).

Worker-side UI toolkit: workers import the same `GrayImage`, fonts, `Menu`, `MenuLayer`,
`WindowMenu` (a short-lived `LayerStack` hosting a `WindowMenuLayer` over the worker's own painted
content, `app/ui/window-menu.ts:62-154`) and paint `Plane[]`, then flatten + fingerprint-dedupe +
submit (`app/apps/minesweeper/minesweeper-app.worker.ts:788-825`).

### 1.7 App definitions and registry

`AppDefinition` (`app/apps/app-definition.ts:16-41`): `appId`, `title` (launcher label and window
title), `icon` (named vector icon), optional `renderIcon(size)` (dynamic artwork, e.g. calendar date),
`launch(ctx, params?)` (open or focus), optional `boot(ctx)` (runs once at shell start), optional
`showInLauncher=false`, optional `openSharedText` (Android share-intent handler; Files), optional
`glanceboard` provider (at most one app; the Glanceboard app, §1.12).

`AppContext` (`:82-112`) is the only channel from apps back into the controller: app list,
shared actions, `launchApp(appId, params)`, `uninstallApp`, `launchInProcessApp(windowId, surfaceId,
factory)` (launch-or-focus singleton), `ensureWorkerHost(createWorker)`, frame submission & surface
visibility, `requestShellRender`, `appendLog`, `setTextEditorHost` (Settings app registers the
glasses-side editor so phone edits can close it).

Registry: `ALL_APPS` in launcher-grid order (`app/apps/all-apps.ts:34-61`):
launcher (hidden), ai-chat, timer, calculator, terminal, files, music, nightscout, transcribe,
teleprompter, microphones, notifications, calendar, weather, navigate, compass, roam, blocks,
minesweeper, freecell, pinball, flappy, developer, evenhub, glanceboard, settings. Installed EvenHub
packages are appended dynamically as extra launcher entries (`app/apps/launcher/index.ts:11-30`).
(The launcher sorts alphabetically anyway, §6.1.)

| appId | Title | Icon | Host | Boot hook | Window id(s) | Height |
|---|---|---|---|---|---|---|
| launcher | Apps | layout-grid | in-process | registers pinned window | `launcher` | min |
| ai-chat | AI Chat | message-circle | in-process | — | `ai-chat` | min |
| timer | Timers | timer | in-process | timer engine, tray icon, tools | `timer` | min |
| calculator | Calculator | calculator | in-process | — | `calculator` | min |
| terminal | Terminal | terminal | worker | — | `terminal:hub`, `terminal:view:N` | hub min, views max |
| files | Files | folder | in-process | — | `files`, `files:doc:N` | min |
| music | Music | music | in-process | — | `music` | min |
| nightscout | Nightscout | nightscout | in-process | tray icon | `nightscout` | min |
| transcribe | Transcribe | mic | in-process | — | `transcribe` | min |
| teleprompter | Teleprompter | scroll-text | in-process | — | `teleprompter` | min |
| microphones | Microphones | mic | in-process | retention sweep | `microphones` | min |
| notifications | Notifications | bell | in-process | — | `notifications` | min |
| calendar | Calendar | calendar (+date) | in-process | — | `calendar` | min |
| weather | Weather | cloud-sun | in-process | — | `weather` | min |
| navigate | Navigate | map | worker | — | `navigate:main` | min (per-app setting) |
| compass | Compass | compass | in-process | — | `compass` | min |
| roam | Roam | roam | worker | — | `roam:main` | min |
| blocks / minesweeper / freecell / pinball / flappy | … | l-piece / bomb / spade / pinball / bird | worker | — | `<id>:main` | min |
| developer | Developer | wrench | in-process | — | `developer` | min |
| evenhub | EvenHub | package (+logo) | in-process | — | `evenhub:store`, one per running package | store min; apps medium/max |
| glanceboard | Glanceboard | eye | in-process | — | `glanceboard` | medium (max for 2×3 preview) |
| settings | Settings | settings | in-process | — | `settings` | min |

### 1.8 App lifecycle

**Launch** (`app/g2/dashboard-controller.ts:2393-2413`): look up `ALL_APPS`; if found call its
`launch`. Otherwise try installed EvenHub packages (missing package ⇒ open the store's reinstall
page). All entry points (launcher grid, assistant `apps.launch` tool, Settings deep link, restore,
timer ring, share intent) funnel through here.

- In-process singleton (`launchInProcessApp`, `:2251-2288`): if a window with that id exists, focus it;
  otherwise create it, register with the shell (appended to the sidebar), configure its surface
  (initially invisible), then `shell.focusWindow` (foreground + input focus).
- Worker app (`launchWorkerAppWindow`, `app/apps/app-definition.ts:128-168`): if any window of the app
  (or, with `matchExistingBy:"windowId"`, that exact window) exists, focus it; else spawn/reuse the
  worker and `openWindow({focus:true})`. Terminal uses `windowId` matching so the hub reopens even
  while session windows exist.
- Settings with `params.section` deep-links a section even when already open (`app/apps/settings/index.ts:12-44`).

**Foreground / background.** Exactly one window is "selected" = foreground (visible surface). Moving
the sidebar selection immediately switches the foreground window (`setSelectedIndex`,
`app/ui/shell/shell.ts:999-1009`), calling `setForeground(false/true)` and `requestRender` on the new
one. Background windows keep their state; worker windows typically stop timers/painting when not
foreground (`app/apps/minesweeper/minesweeper-app.worker.ts:221-232`). There is no separate
"suspended" state for built-in apps; EvenHub apps receive FOREGROUND_ENTER/EXIT and screen on/off
(`app/apps/evenhub/evenhub-window.ts:118-131`). The whole EvenHub BLE session is suspended 5 s after
screen-off to save battery (§1.10).

**Focus.** `focus ∈ {sidebar, window}` (`app/ui/shell/shell.ts:201,315`). Entering a window (click /
swipe-right in sidebar) calls `onFocus(lastInput)` and repaints so the highlight style changes the
same frame (`:975-986`). `yieldFocusToSidebar()` (double-click at app root) repaints the window in
unfocused style (`:686-693`).

**Close** (`:442-489`): only if `closeable`. Calls `window.close()`, removes it; if it was the
foreground, the most-recently-visible remaining window becomes foreground (MRU list, `:313-314,428-440`)
and focus returns to the sidebar. Close paths: system menu "Close window", worker
`close-window-request`, assistant `apps.close_window`, display-mode relaunch.

**Display-mode / position changes** (`app/g2/dashboard-controller.ts:500-579`): windows with `relayout`
re-measure; other closeable windows are closed and relaunched at the new size; all surfaces are
reconfigured; foreground is restored.

**Persistence of open apps** (`app/ui/shell/open-apps-persistence.ts:9-67`): file
`<documents>/open-apps.json` = `{version:1, open:[appId…] (sidebar order, launcher excluded, one entry
per app), foreground: appId|null}`, written with 1 s debounce whenever windows change
(`app/g2/dashboard-controller.ts:2343-2352`). On first reaching the phone's main page,
`restoreOpenApps` relaunches each (fresh — no in-app state, multi-window apps only their primary
window), then focuses the saved foreground (`:2360-2391`). Described as a development convenience.

### 1.9 How an app renders

**Drawing API** (`GrayImage`, `app/graphics/image.ts:143-658`) — an 8-bit greyscale raster plus a list
of *deferred draws* that are kept symbolic so native code can replay them from a glasses-side
texture cache:

| Method | Notes |
|---|---|
| `new GrayImage(w,h,fill)`, `clear`, `setPixel/getPixel`, `fillRect`, `drawRect`, `drawLine` | raster primitives |
| `fillRoundedRect`, `drawRoundedRect` (default radius 8) | `:312,339` |
| `drawText(font,x,y,text,value)`, `drawTextWrapped` | y = top of line box; glyphs are deferred draws, always above this image's raster (`:242-258`) |
| `drawImage(src,x,y)` | deferred colour-keyed blit of an immutable asset (icons) (`:492`) |
| `bitBlt(src,dx,dy,{sx,sy,width,height,transparentZero})` | immediate raster copy (`:260`) |
| `drawMenuSelection(row,…,bg,border,radius,depth,anim)` | retained "presentation": selected-row image + highlight box, animatable on-glasses (`:514`) |
| `drawDisplayList(list,x,y,w,h,depth)` | retained on-glasses display list (animations) (`:521`) |
| `drawDepthImage(src,x,y,depth)` | image shown at a stereo depth (`:527`) |
| `dimmed(f)`, `composeInto`, `withDrawsBaked`, `bakeDeferredDrawsInPlace`, `fingerprint()`, `to8bppBuffer()` | composition helpers (`:456-620`) |

Apps receive a viewport-sized canvas via their stack (`ctx.stack.getBaseSize()`) and must lay out
relative to it (it changes with display mode). Fonts come from `getDefaultSmall/Medium/LargeFont()`
(§2.7) and fixed BDF faces via `getFont()`. Icons from `renderIcon(name,size)` (§2.10).

**Frame pipeline.** An in-process window renders on input and on `requestRender` (coalescing is
done natively by dedupe; a window renders under a fresh "frame id" for latency accounting). Planes are
flattened with their deferred draws (`flattenPlanesWithDraws`, `app/graphics/plane.ts:78-133`),
converted to 8 bpp and submitted with a fingerprint; unchanged fingerprints are dropped
(`app/g2/dashboard-controller.ts:2453-2475`). The shell render loop keeps one render in flight and at
most one queued, and waits for the previous frame to reach the glasses (6 s backpressure timeout)
(`:2490-2559`). Data sources may serve stale cached data during a paint (notification icons); the
paint then reports it and a follow-up render with fresh data is scheduled (`app/ui/shell/in-process-window.ts:108-136`).

**Glasses-side animation** is expressed as *display lists*: a small bytecode of `ROUNDED_RECT`,
`IMAGE`, `RECT_COPY` calls whose coordinates can be expressions of time (`progress(duration)`,
`ease()`, `lerp`) executed on the glasses between frames (`app/graphics/display-list.md:1-92`). Menus
use this for sliding highlights, scroll and bounce (§3.5). Rebuild note: this is an optimisation for a
slow link; a native rebuild can keep the same idea (send one list, let the glasses animate) or send
frames if bandwidth permits.

**How input reaches an app.** In-process: `ShellWindow.handleInput` → (app/system menu gestures
intercepted) → `LayerStack.handleInput` → top layer (`app/ui/shell/in-process-window.ts:177-205`).
Worker: host posts `input` with `focused` flag; the worker routes to its open `WindowMenu` first,
then to app logic, and must eventually submit a frame or finish the frame id.

### 1.10 Shell state machine: screen, focus, overlays

State (`app/ui/shell/shell.ts:310-366`): `windows[]` (sidebar order), `selectedIndex`, `mruWindowIds`,
`focus`, `screenOn`, `lastInputAtMs`, `lastInput`, overlay `stack`, `activeVoiceLayer`,
`activeKeyboardLayer`, `assistantLayer`, `escapeMenuTimer`, battery levels, tray icons, attention flags.

```
                 double-click (sidebar focus) / idle timeout / notification-modal close (if it woke us)
   SCREEN_ON  ───────────────────────────────────────────────────────────────►  SCREEN_OFF
   (focus: sidebar|window,                                                       (overlays cleared,
    overlays 0..n)         ◄───────────────────────────────────────────────────  selection kept)
                 double-click, display-wake (stock double-tap / head-tilt when Glanceboard off),
                 wakeword (unless action=off), notification posted, timer ring, terminal bell,
                 phone keyboard, alert, assistant send
```

- `wake(focus)` sets `screenOn`, notifies the controller (unblank, un-suspend EvenHub), tells every
  window `setScreenOn(true)`, and re-renders the foreground window (retained frame may be stale)
  (`:592-612`). Most wake paths land focus on the **sidebar**; timer ring and terminal bell land on
  the window (`app/apps/timer/index.ts:28`, `app/ui/shell/worker-window.ts:304-311`).
- `sleep()` cancels the escape timer, pops all overlays (their `onRemoved` stop mics etc.), tells windows,
  notifies the controller (blank + brightness fade + schedule EvenHub suspend) (`:615-625`).
- Idle timeout (`applyScreenTimeout`, `:640-657`), polled every 1 s while connected
  (`app/g2/dashboard-controller.ts:1556-1561`): sleep when `now − lastInput ≥ timeout` (default 30 s;
  `never` disables). Suspended (and `lastInput` slid forward) while a voice dialog, keyboard dialog,
  assistant turn, or window-owned mic capture is active.
- Controller wake barrier (`ensureEvenHubSessionActive`, `app/g2/dashboard-controller.ts:717-761`):
  G2 screen on → resume EvenHub session → unblank → await ready (≤ 4.5 s). Shared by concurrent wakers.
- EvenHub suspend (setting "Suspend EvenHub when screen off", default on): 5 s after screen-off, unless
  the Glanceboard is showing or a mic capture is held (`:816-895`). Improves battery, costs wake latency.

### 1.11 Lock screen

Enabled by `display.lockScreenEnabled` (default true). The glasses lock when they are taken off
(wear sensor) while the phone is locked, or when the phone locks while they are off-head; they unlock
when the phone unlocks (or from the watch if allowed) (`app/g2/dashboard-controller.ts:615-672`).
Locked: the full-screen opaque lock surface (z 1000) is visible; its image is a rounded box
480×150 centred (stroke 150, radius 12) with medium-font text (230) "Glasses locked; unlock the phone
to unlock the glasses." (`app/g2/lock-screen.ts:5-24`). Input while locked: only a ring/watch
double-tap toggles the (locked) screen on/off and a firmware display-wake wakes it; everything else is
swallowed (`app/g2/dashboard-controller.ts:2125-2149`); the phone mirror likewise only passes double-tap
(`:1832-1836`). The Glanceboard cannot show while locked (`:263`).

### 1.12 Glanceboard host (sleep-time dashboard)

The Glanceboard is an alternate display shown *while the shell is asleep*; it is not a window. The
host (`app/g2/glance-host.ts:48-293`) owns a full-screen opaque surface at z 900, a visibility reducer
and an auto-hide timer; the board content comes from the Glanceboard app's provider
(`app/apps/glanceboard/index.ts`).

Visibility reducer (`app/g2/glance-state.ts:38-60`):

| Event (while screen off) | From gesture | New state |
|---|---|---|
| `press` | click (if "Show on tap" ≠ Disabled) or head-tilt display-wake (if "Show on head tilt") | visible, hide at now + tap duration (restart timer); ignored while holding |
| `hold` | long-press or tap-then-hold (if "Show on long press") | visible, holding, no timer |
| `release` | long-press-release | hidden if holding |
| `timeout` | timer | hidden |
| `dismiss` | double-click while visible, or any regular-UI wake | hidden; double-click then continues to wake the regular UI |

Mapping of gestures to events (`app/g2/glance-state.ts:68-86`, `app/g2/glance-host.ts:82-89`);
when the board is disabled, none apply (sleep-time taps do nothing; head-tilt wakes the regular UI).

Show sequence (`app/g2/glance-host.ts:167-195`): configure surface (hidden) → create board and `start()`
widgets → render first frame → make surface visible while still blanked → run the wake barrier (unblank)
so the board appears in one step without flashing the app. Hide-while-asleep: blank first, then hide the
surface (avoids flashing the sleeping UI), stop widgets, re-blank/re-arm suspend (`:197-217`). Render:
board image centred horizontally on the 640-wide panel, top at the min band's top (clamped so a
432-px board fits) (`:249-250`); surface stereo depth from the "Depth" setting (`:263-267`).

---

## 2. Screen geometry and visual design language

### 2.1 Panel, coordinates and colour

- Panel 640 × 480, origin top-left, y down (`app/graphics/image.ts:7-8`).
- Each window paints into its own **viewport** coordinate space (origin = top-left of the app content
  area, below the top bar). The shell paints in panel coordinates.
- All drawing is 8-bit grey; the glasses show 16 levels (see §0). "Black" in a colour-keyed layer must
  be value 1 (`app/ui/shell/geometry.ts:74`, `app/ui/menu.ts:294-297`).
- `screenCenterInViewportX()` = 320 − sidebarWidth: UI the wearer aims physically (compass rose,
  calibration crosshair) centres on the *panel* centre, not the viewport centre (`app/ui/shell/geometry.ts:65-67`).

### 2.2 Display modes, bands and viewports

Constants: `TOP_BAR_HEIGHT = 28`, `SIDEBAR_WIDTH = 64`, `MIN_WINDOW_HEIGHT = 288` (band incl. top bar)
(`app/ui/shell/geometry.ts:13,21,86`). Height modes (`:76-83,91-100`):

| Height mode | Band height | Content height | Used by |
|---|---|---|---|
| `min` | 288 | 260 | default for every window |
| `medium` | 316 (= 288 + top bar) | 288 | EvenHub apps (their surface is 576×288), Glanceboard app |
| `max` | 480 | 452 | terminal session views, EvenHub "tall canvas", Glanceboard 2×3 preview; all windows in Tall/Full modes |

Global **Display mode** setting (`display.mode`, `app/ui/dashboard-settings.ts:311-333`):

| Mode | Label | Sidebar | Window heights | Viewport width |
|---|---|---|---|---|
| `576x288` (default) | "Band · 576×288" | 64 px strip always reserved | as requested by each window | 576 |
| `576x480` | "Tall · 576×480" | 64 px strip reserved | every window forced to `max` | 576 |
| `640x480` | "Full panel · 640×480" | none reserved; strip is painted *over* the window only while the sidebar has focus | every window `max` | 640 |

Effective height = requested mode when the app's display mode is Band and the app has no override,
`min` when an app override selects Band explicitly, else `max` (`app/ui/shell/geometry.ts:55-57`). Per-app
overrides exist only for Navigate (default "Use global") and Terminal (default "Tall sessions" = hub at
global size, sessions max) (`app/ui/dashboard-settings.ts:489-518`, `app/ui/shell/geometry.ts:24-41`).

**Vertical position** (`display.verticalPosition`: top / upper / middle (default) / lower / bottom =
fraction 0 / .25 / .5 / .75 / 1 of the free space above the band; per-app override for Navigate and
Terminal) (`app/ui/shell/geometry.ts:107-135`): `windowTop = round((480 − bandHeight) × fraction)`.

| Position | min band top | medium band top | max |
|---|---|---|---|
| top | 0 | 0 | 0 |
| upper | 48 | 41 | 0 |
| middle (default) | 96 | 82 | 0 |
| lower | 144 | 123 | 0 |
| bottom | 192 | 164 | 0 |

Viewport rect = `(sidebarWidth, windowTop + 28, 640 − sidebarWidth, bandHeight − 28)`
(`app/ui/shell/geometry.ts:138-157`). Default (Band, middle, min): top bar y 96..123, content
(64, 124, 576, 260), sidebar (0, 96, 64, 288).

**Invariant:** the sidebar, all shell overlays, the notification modal, dialogs and the Glanceboard
always align to the **min band** at the current vertical position, even while a taller window is in
front (`app/ui/shell/geometry.ts:124-130`, `app/ui/shell/chrome-layer.ts:262-266`). Only the top bar
moves to the foreground window's band top (`app/ui/shell/chrome-layer.ts:341-351`).

Rebuild note: the 288-px band exists because only part of the 480-px panel is comfortably visible
through the optics for a given fit; vertical position lets the user place it (`notes/apps.txt`
"Distance/Viewport Configuration").

### 2.3 Top bar (status bar)

Painted by the chrome on the shell surface at `(barLeft, windowTop(fg), 640 − barLeft, 28)`, where
`barLeft = 64` while the sidebar strip is visible else 0 (`app/ui/shell/chrome-layer.ts:341-374`):

| Element | Placement | Style | Source |
|---|---|---|---|
| Background | full bar | opaque black (1); bottom hairline at y+27, value 40 | `:350-351` |
| Clock | x = barLeft + 10, vertically centred | medium font, value 210, text `"<Wkd> <d> <Mon> <time>"`, e.g. "Thu 11 Sep 14:05" / "2:05 PM" (12 h setting) | `:353-357`, `app/ui/clock-format.ts:12-25` |
| Notification icons | from clock end + 16, 24×24 each, pitch 28, as many as fit before the tray (8 px margin) | Android small icons rendered grey natively; one per notification group, excluding group summaries, own app, transport-category, media sessions, importance ≤ MIN | `:362-373`, `FaceclawMediaNotificationListenerService.kt:91-131,337-391` |
| Tray icons | right-to-left, ending at the battery block, 10 px gaps, vertically centred | app-provided small images, ordered by owner id | `:566-574`, `app/ui/shell/shell.ts:510-517,1536-1538` |
| Battery block | right-aligned, 8 px right margin; items in order Phone, Watch, G2, R1 | see below | `:384-440` |

Battery indicators: per-device visibility Always / Below 50 % / Never; watch only while a watch is
reachable; style setting (`dashboard.systemCard.batteryDisplayMode`, default **stacked**):

| Style | Rendering |
|---|---|
| `icon` | small-font label (value 150) + 5 px + gauge icon; items 12 px apart |
| `percentage` | label + "NN%" (value 200); charging = inverted (white box, black text) |
| `stacked` | fixed 12-px TerminusV label centred over the gauge; label ink rows 3–10, gauge rows 13–22 of the bar; 10 px item gap |
| `stacked-percentage` | as stacked but percentage digits (charging inverted, box hugs 8-px digits) |

(`app/ui/shell/chrome-layer.ts:461-509`). Gauge icon (`app/graphics/battery.ts:11-72`): 21×10 px, 1-px
outline (120) with a 2×6 nub, four 3-px bars (190) separated by 1-px dim columns (70), filled
continuously; charging bolt (7×7) overlaid white if ≤ 50 %, cut out (transparent) if > 50 %.

Tray icon examples: Timers "⏲ 12m"/"45s" countdown (dial + terminus12 text, `app/apps/timer/timer-app.ts:1352-1385`);
Nightscout BG value + 48-px 2-hour sparkline + warning triangle (`app/apps/nightscout/nightscout-app.ts:78-132`);
Transcribe and Microphones mic glyphs while open (`app/apps/transcribe/transcribe-app.ts:17-36`).

The top bar repaints when any of its settings change (`app/ui/shell/shell.ts:305-308,402-412`), when
battery reports arrive, every 60 s for the clock (`app/g2/dashboard-controller.ts:147,1551-1555`), and
when notifications change.

### 2.4 Sidebar (app switcher)

Painted in the min band: `(0, minWindowTop, 64, 288)` filled opaque black (`app/ui/shell/chrome-layer.ts:261-339`).

- **Layout variants** (`:37-62`): up to 6 windows → one column, 36-px slots with 32-px icons,
  right-aligned against the separator (column x 28..63). More → two columns of 32-px slots with 28-px
  icons (x 0..31 and 32..63); the right column fills first, overflow goes to the left column. Icon pitch
  = icon + 8; list area = band − top bar − 2×10 margins = 240 px → 6 rows per column.
- **Scrolling:** first visible row adjusts minimally to keep the selection visible; chevrons (5-px half
  width, value 140) above/below mark hidden windows (`:268-282,330-338,652-657`).
- **Separator:** vertical line x = 63, value 40, from the content top (never beside the top bar) to the
  band bottom, with a gap where the selection tab bulges (`:284-301`).
- **Selection tab** (right column): rounded on the left (radius 6), open to the right, from y−2 to
  y+icon+2. Sidebar focused → filled white (255) and the icon drawn inverted (black on white, black = 1);
  window focused → outline only (value 150). Overflow-column selection → self-contained rounded box
  (`:588-635`).
- **Unselected icons** are drawn at stereo depth −2 (they recede) (`:317-327`).
- **Attention dot:** 8×8 round dot at the icon's top-right (white; black on the white tab) — set by
  worker apps (terminal bell) and cleared when the window comes to the foreground
  (`:321-326,576-586`, `app/ui/shell/worker-window.ts:497-501`).
- Icons: app vector icon or a letter fallback (rounded outline 120 + medium-font letter 210)
  (`:127-178`); per-window glyph/activity variants for terminal sessions.
- Phone-mirror taps hit-test slots with the same geometry (`:246-259`).

### 2.5 Shell overlay geometry

All positions below are panel coordinates at the default Band/middle layout (bandTop = 96); formulas
cite their source.

| Overlay | Rect / placement | Style |
|---|---|---|
| System menu | x = sidebarWidth + (640 − sidebarWidth − 272)/2 = 216, y = bandTop + 28 + 8 = 132, w 272, min h 150, grows with items | square corners, fill 1, border 72, title "System" (220), footer hint (110), dims below to 25 %, depth +4 (`app/ui/shell/shell.ts:222-237`) |
| Window (app) menu | centred in the viewport, y = 8 (viewport) → same screen position as the system menu, w 272, min h 150 | rounded (r 8), footer "— system menu" (or "●— system menu" for hold-to-talk), dim 25 %, depth +4 (`app/ui/window-menu.ts:20-35`) |
| Notification modal | viewport(min) inset 14: (78, 138, 548, 232); inner stack inset 4 more | fill 1, border 110 (`app/ui/shell/modal-layer.ts:11-40,47-60`) |
| Voice / keyboard / assistant dialog | x 40, w 560, y = bandTop + 28, h = 288 − 56 = 232 (overlaps the sidebar) | fill 1, border 90; title (220) at +16,+12; status (130) at +30; body from +56 with a fixed 16-px pitch showing the *tail* of the text (235); menu rows at the bottom (`app/ui/shell/input-dialog.ts:15-118`) |
| Alert | x 40, w 560, y = bandTop + 96, h 96 | fill 1, border 90, "Assistant" (200), wrapped text (235); 6 s auto-dismiss (`app/ui/shell/shell.ts:246-303`) |
| Brightness picker | w 272, h = 100 + 3·lh + 56, centred in the min viewport | vertical 12×100 bar (outline 150, fill 255), "NN%"/"Auto" (235), hints (150) (`app/ui/shell/brightness-picker-layer.ts:9-47`) |
| Tool debug | x = sidebarWidth + 8, y = bandTop + 36; list w 420; detail box w = 640 − x − 8, h 208 | square menu; detail fill 1 border 72 (`app/ui/shell/tool-debug-layer.ts:19-121`) |
| Ambient cards | right-aligned (x = 640 − 230 − 6), stacking up from the band bottom (−6), 4-px gaps; dropped if they would cross into the top bar | 230 wide, fill 1, border 90, padding 8×4, title (220) + ≤ 3 detail lines (160); never take input; used for "you know this person" encounter popups (`app/ui/shell/chrome-layer.ts:511-560`, `app/ui/shell/ambient-cards.ts:1-91`) |
| Lock screen | own surface; box 480×150 centred | stroke 150, r 12, medium text 230 (`app/g2/lock-screen.ts:8-24`) |
| Glanceboard | own surface; board centred horizontally on the panel, top = min band top | §2.12 |

### 2.6 Layout metrics (font-derived)

`app/ui/metrics.ts:17-110` — all list UIs derive spacing from the current small font:

| Metric | Formula | At 12-px bitmap default |
|---|---|---|
| `listRowHeight` (menu row pitch) | lineHeight + 8 | 20 |
| `LIST_ROW_TEXT_INSET` (text y inside row) | 4 | 4 |
| `tightRowHeight` (dense lists: files, music tracks, weather) | ink height + 4 | 16 |
| `lineStep` (paragraph line pitch) | lineHeight + 2 | 14 |
| `menuTitleHeight` | lineHeight + 4 | 16 |
| `iconGridMinRowHeight(font, icon, gap)` | icon + gap + lineHeight + 8 | 66 for 44-px icons |
| `centeredTextY(font, top, h)` | centres measured *ink* in a box | — |

Common page conventions: page/list title at (18, 10) in viewport, list top at 38
(`app/ui/notifications.ts:27-29`, `app/apps/calendar/calendar.ts:12-15`); page menus at (8, 8) width 272
(or viewport − 16) (`app/ui/menu.ts:10-12`); hint footers ~16–36 px above the viewport bottom.

### 2.7 Fonts

User-selectable UI font (`display.uiFont2`, JSON `{kind:"bitmap",face}` or `{kind:"ttf",file,size}`),
default **Roboto Light 14 px** (TTF) (`app/graphics/ui-fonts.ts:23,76`). Three roles derived from it
(`:151-191`):

| Role | TTF selection | Bitmap selection | Guarantee | Typical uses |
|---|---|---|---|---|
| small | the picked size | Terminus 12 or TerminusV 12 | line height 8..21 px (sizes outside are hidden in the picker; out-of-range falls back to bitmap) | nearly all text: menus, lists, titles, dialogs, hints, notification cards |
| medium | same file, size grown until line height ≥ 20 | Terminus 16 | 16..29 px | top-bar clock, lock screen, sidebar letter icons, weather description, system-card date, volume value, teleprompter body, compass calibration values |
| large | grown until ≥ 26 | Terminus 24 | — | compass heading, weather temperature, Nightscout glucose, system-card time |

Fixed faces (`app/graphics/bdffont.ts:207-222`): `terminus12/16/24/32`, `terminusv12` (proportional,
Latin-1, falls back to terminus12). Used for: stacked battery labels (terminusv12), timer tray icon
(terminus12), terminal default cell font 6×12 (`app/graphics/ui-fonts.ts:209-228`), games and Navigate
panels (terminus24/32). Timers' big digits use bundled **Roboto Bold** TTF at 96/80/64/52/40 px (largest
that fits) and 56 px in pickers (`app/apps/timer/timer-app.ts:100-102,158-178`); the calendar launcher icon
draws today's date in Roboto Regular at size/2 (`app/apps/calendar/calendar-icon.ts`). EvenHub apps
render with the firmware's built-in 20-px font model (`app/graphics/evenhub-font.ts`).

Bundled TTFs (preinstalled into an app-private fonts dir on first use): Inter, Montserrat, Roboto,
Roboto Mono in Light/Regular/Bold (`app/graphics/installed-fonts.ts:19-32`); more can be installed from
the Files app. Font picker (`app/ui/font-picker.ts:63-351`): rows Font / Weight / Size / Save with a live
preview line ("The quick brown fox jumps over 0123456789") and its line height; sizes
8–18, 20–28 (even) (`:31`); changes apply only on Save; double-click cancels. The terminal font picker
offers only monospace faces and applies to newly opened sessions.

Text layout helpers: `wrapText` (word wrap, optional long-word breaking) and `truncateText` which
appends "..." (`app/graphics/textwrap.ts:46-75`).

### 2.8 Grey palette (de-facto design tokens)

| Value | Meaning / where |
|---|---|
| 255 | selected row text; focused sidebar tab; bright markers, cursors |
| 245–250 | big readouts (weather temp, compass heading 255, glucose 230) |
| 230–235 | primary body text (dialog text 235, notification title 230, text viewer 230) |
| 220 | titles (menu/page titles, top-bar tray text) |
| 210 | top-bar clock, launcher labels, letter icons |
| 200 | normal list/menu text; battery percentage |
| 185–190 | secondary body text |
| 150–170 | tertiary text, labels (battery labels 150, day headers 150, status 130–170) |
| 110–120 | gesture hint footers, dim status |
| 90 / 70 | dimmed rows (90) / disabled rows (70) |
| 110 / 90 / 72 | borders: notification modal / dialogs & alerts / menus |
| 150 | selection-tab outline, lock-screen box |
| 45 | focused selection stroke (`MENU_HIGHLIGHT_STROKE`) |
| 38–40 | separators, hairlines (top-bar bottom line, sidebar separator, settings divider) |
| 30 / 120 | scrollbar track / thumb |
| 20 | dimmest visible line (Glanceboard dividers) |
| 15 | focused selection fill (`MENU_HIGHLIGHT_FILL`) — barely visible (level 1) |
| 1 | opaque black on colour-keyed layers |

(`app/ui/menu-core.ts:9-14`, `app/ui/menu.ts:238-306`, `app/ui/shell/chrome-layer.ts:96-97,446,591`,
`app/apps/glanceboard/board.ts:9`.)

### 2.9 Selection, controls and hints

- **Selection highlight** (`app/ui/menu.ts:80-93`, `app/ui/menu-core.ts:266-282`): a rounded rectangle
  around the row (radius 8 default; many lists use 4–6). Focused list: fill 15 + stroke 45 and selected
  text 255; unfocused (window not focused, or an inactive column): outline only, so the selection stays
  visible without implying it receives input. Text: normal 200, selected 255, disabled 70.
- **Two-level grids** (launcher, file icons): a full-width *row band* highlight in row mode, a single-cell
  box in item mode (`app/apps/launcher/launcher-app.ts:289-300`).
- **Toggle switch** (`app/ui/menu.ts:155-177`): 34×16 pill at the row's right; on = fill 70, border 130,
  knob (12 px) right, 230; off = fill 18 (1 when selected), border 55, knob left, 90 (170 selected).
- **Right value** (`:179-191`): label left (200), value right-aligned (220). Enum pickers mark the current
  value with a trailing " *" (`app/ui/dashboard-settings.ts:1099-1134`).
- **Submenu indicator:** ">" at the right edge of the highlight box (`app/ui/menu.ts:140-153`).
- **Scrollbar:** 3-px track (30) with proportional thumb (120, min 8 px), right of the list
  (`app/ui/menu-core.ts:297-307`, `app/ui/menu.ts:119-133`).
- **Gesture hint footers:** dim (≈110) one-liners built from glyph/action pairs, e.g.
  "▲▼ adjust" / "● / ●● confirm" (brightness), "— system menu" (window menu footer), "●— app menu" (system
  menu footer when the app has one) (`app/ui/shell/shell.ts:1488-1493`).
- **Modal box styling:** fill black, 1-px border, title at top-left.

### 2.10 Icons

Vector icons are Lucide SVGs (plus custom l-piece, pinball, Roam and Nightscout logos) rasterised
natively at the requested pixel size with stroke width 2 (viewBox units) and cached per (name, size)
(`app/graphics/icons.ts:13-198`). Names: message-circle, eye, layout-grid, timer, calculator, l-piece,
bomb, pinball, bird, spade, terminal, scroll-text, file-text, file, folder, folder-filled, image, film,
type, hard-drive, music, package, activity, bell, calendar, cloud-sun, flask-conical, wrench, settings,
mic, map, compass, roam, nightscout (`:18-93`). Sizes: launcher & file grid 44, sidebar 32/28,
notification icons 24. Special variants: terminal icons with a session glyph ("1"–"9","0","A"–"Z") and a
blinking cursor activity phase (`:99-183`); calendar icon showing today's date; filled folder for launcher
folders; EvenHub app icons from package artwork. Inverted variants for the focused sidebar tab are
derived by mapping coverage to darkness (`app/ui/shell/chrome-layer.ts:148-162`).

### 2.11 Animation, fades and depth

| Effect | Behaviour | Source |
|---|---|---|
| Menu highlight slide | on single-step (non-wrapping) navigation the highlight slides from the old row to the new one over **240 ms**, smoothstep; restarts from the current interpolated position if interrupted | `app/ui/menu-highlight-motion.ts:1-41` |
| Menu scroll | a navigation-caused scroll of ≤ 96 px animates over 240 ms (smoothstep); larger jumps, programmatic scrolls and item edits snap | `app/ui/menu-scroll-motion.ts:5,56-104` |
| End bounce | scrolling past a non-wrapping end with no exit callback overshoots by min(24, round(0.4 × row pitch)) px and returns: **320 ms**, first 35 % ease-out-quad out, rest smoothstep back; the highlight rides with its row | `app/ui/menu-core.ts:18-19,378-385`, `app/ui/menu-scroll-motion.ts:6-33` |
| Partial next row | boxes that are not a whole number of rows show the clipped top of the next row as a "more below" hint | `app/ui/menu-core.ts:86-91` |
| Screen on/off fade | brightness fades in/out over **280 ms** when the display becomes visible/invisible; level changes fade 1.2 s (auto) or 120 ms (manual) | `app/ui/dashboard-settings.ts:362`, `native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/session/GlassesSessionBrightness.kt:13-14` |
| Context-menu dim | everything beneath a context menu drops to 25 % brightness | `app/ui/menu.ts:194` |
| Timer "rung" blink | the finished timer's digits alternate bright/soft every second | `app/apps/timer/timer-app.ts:931` |
| Sidebar activity | terminal icons blink a cursor while the session outputs | `app/ui/shell/worker-window.ts:345-350` |
| Depth: top bar | −2 (slightly farther than content) | `app/ui/shell/chrome-layer.ts:231` |
| Depth: unselected sidebar icons | −2 (selected icon stays at 0, so it "pops") | `app/ui/shell/chrome-layer.ts:327` |
| Depth: context menus | +4 (nearer) | `app/ui/shell/shell.ts:236`, `app/ui/window-menu.ts:27` |
| Depth: Glanceboard | user setting −64…+64 | `app/apps/glanceboard/glanceboard-settings.ts:106-129` |

There are no window-open/close or app-switch transitions (**uncertain**; none found). Animations run
on the glasses via display lists so they are smooth despite a ~250 ms input-to-pixel pipeline (comment
in `app/apps/flappy/flappy-app.worker.ts:7-9`).

### 2.12 Glanceboard design

- Board sizes (`app/apps/glanceboard/layout.ts:7-44`): **2×2** = 576×288 with four 288×144 slots (TL, TR,
  BL, BR); **2×3** = 576×432 with six 288×144 slots. Both share storage keys for the first four slots.
- A widget flagged `tall` (calendar, terminal) chosen for two vertically adjacent slots merges into one
  double-height region (`:71-100`); each widget otherwise appears at most once (choosing it elsewhere clears
  the other slot) (`app/apps/glanceboard/glanceboard-settings.ts:167-183`).
- Optional hairline dividers at value 20 (`app/apps/glanceboard/board.ts:9,116-127`).
- Default slots: TL System card, TR+BR Calendar (merged), BL Music (`glanceboard-settings.ts:28`).
- Widgets (each paints into a fresh canvas of its region, 8-px padding, start/stop their own data
  subscriptions only while shown) (`app/apps/glanceboard/widget.ts:13-43`, `widgets/index.ts:10-26`):

| Widget | Content | Refresh |
|---|---|---|
| System card | time (large if it fits else medium, 230) and date (medium, 170) top-left; battery block top-right (same style/visibility settings as the top bar); running timers ("12:34 name", finished = inverted "Done", paused dim) between; notification icons (24 px) along the bottom | minute tick, notifications, settings, 1 s while a timer runs (`widgets/system-card.ts:41-233`) |
| Calendar (tall-capable) | current/next event large with countdown/"ends in", then following events under day headers; permission/empty states | minute tick + provider change (`widgets/calendar-widget.ts:23`) |
| Music | 96-px album art, title/artist/album, 5-px progress bar; idle state = dim note icon (value 20) | player state, 1 s while playing (`widgets/music-widget.ts:47`) |
| Compass | heading readout over a small tilted rose; turns the magnetometer on while shown; true north only if declination already known | sensor events (`widgets/compass-widget.ts:25`) |
| Nightscout | glucose (struck through when stale) with delta/trend/age/IOB, 2-h graph, breached thresholds inverted | bridge + minute tick (`widgets/nightscout-widget.ts:30`) |
| Terminal (tall-capable) | the Terminal hub's session list (hosts, sessions, activity indicators) from state the terminal worker publishes | worker-state changes, activity animation (`widgets/terminal-widget.ts:28-126`) |

---

## 3. The menu / list component

Everything list-like (menus, settings, launcher sub-menus, timers list, music actions, forecast table,
notification actions, dialogs) is built on one headless component, `Menu<T>` (`app/ui/menu-core.ts:100-500`),
plus the boxed page/popup `MenuLayer` (`app/ui/menu.ts:196-339`). Several older screens hand-roll their
own selection (calendar, notification cards, launcher grid) using the helper
`scrollToKeepSelectionVisible` (`app/ui/menu.ts:100-112`).

### 3.1 Data model (`MenuOptions<T>`, `app/ui/menu-core.ts:51-74`)

| Option | Meaning |
|---|---|
| `items: T[]` | rows (any type) |
| `selectedIndex` | initial selection; `null` = deliberately none; omitted = first selectable |
| `getHeight(item, width)` | row pitch in px *including* `rowGap`; rows may differ in height (wrapped text) |
| `isSelectable(item)` | non-selectable rows (section headers) are skipped by navigation |
| `draw({image,item,index,x,y,width,height,selected,focused})` | row painter; must stay inside its rect (the selected row is drawn into a scratch image of exactly the row size) (`:24-42`) |
| `onSelect(item, index)` | invoked by `activate()` / click |
| `wrap` | scrolling past an end wraps to the other end |
| `onExitTop` / `onExitBottom` | when not wrapping: called instead of bouncing (e.g. hand focus to another pane) |
| `rowGap` | pixels between rows, excluded from the row's box and highlight |
| `highlight` | `{radius (default 8), depth (default 0)}` or `false` (row painter shows selection itself) |

### 3.2 Selection rules

- Selection is an index, re-validated ("reconciled") before every paint/input because items may be
  replaced in place: an out-of-range index is clamped; a non-selectable index moves to the nearest
  selectable row below, else above (`:402-418`).
- `setItems(items, selectedIndex?)` keeps the same index (clamped) unless told otherwise and keeps the
  selected row at the same on-screen offset (`:156-167`). Apps that need identity-stable selection keep a
  key and map it back (timers `selectionKey`, `app/apps/timer/timer-app.ts:240-257`; notification filter keeps the package,
  `app/ui/notification-filter.ts:31-45`).
- `select(null)` clears the selection until the user scrolls — used to show a *preview* column without a
  cursor (Settings right column) (`app/ui/menu-core.ts:174-177`, `app/ui/dashboard/settings-panel.ts:233-248`).
- `moveSelection(±1)` (`app/ui/menu-core.ts:180-207`): next selectable in that direction → select it
  (and mark the move as animatable). If none: with no selection, scroll the content by one row; else if
  `wrap`, jump to the first/last selectable (not animated); else call the exit callback or **bounce**.
- `handleInput`: scroll-up/down move; click activates; other events return false so the host can use them
  (`:218-231`). `indexAt(y)` maps a touch to a selectable row (`:234-245`).

### 3.3 Scrolling rules (`ensureVisible`, `app/ui/menu-core.ts:439-481`)

1. Scroll is in pixels (`scrollTop`), not rows.
2. Keep the selected row **and both neighbours** fully visible when all three fit; otherwise just the
   selected row (a row taller than the viewport shows from its top).
3. When scrolling down, snap the top to the first row boundary at or below the minimum needed offset,
   so the list never starts with a partial row.
4. Never scroll past the end; align the end position to a row top.
5. A box whose height is not a whole number of rows shows the clipped top of the next row — a deliberate
   "more below" affordance (`:86-91`).
6. With nothing selectable, scroll-up/down page the content by rows (`:484-499`).

### 3.4 Painting and focus

`paint(image, box, focused)` (`:247-294`): rows outside the box are skipped; rows cut by the box edge are
drawn clipped (`drawRow`, `:314-328`). The selected row (when `highlight !== false`) is rendered into a
scratch image and emitted with `drawMenuSelection`, i.e. as a retained "presentation" = row image +
rounded highlight box (fill 15 when `focused`, else transparent fill; stroke 45) that can slide on the
glasses (§2.11). `focused` comes from the hosting stack (`ctx.stack.isFocused()`) so a visible but
unfocused list shows an outline-only highlight. `drawScrollbar(image, x, y, h)` draws only when content
overflows (`:297-307`).

**Animated scroll strip** (`:338-375`): when a navigation scroll animates, every row visible at any
moment of the motion is drawn into one "strip" image; a display list copies a viewport-sized window of
the strip at an animated source-y and draws the highlight on the same timeline
(`app/graphics/menu-scroll-list.ts:32-45`). It falls back to snapping when: the highlight has non-zero
depth, the selected row doesn't fit, the strip would exceed 64 KiB (5 + ⌈w/2⌉·h bytes), or anything
is already painted under the strip's columns (it is copied opaquely).

Rebuild note: the essential UX is "highlight glides between rows; list glides when it scrolls; list
bounces at a hard end; neighbours stay visible". How it is transported is an implementation detail.

### 3.5 `MenuLayer` — the boxed menu page / popup (`app/ui/menu.ts:24-339`)

`MenuItem = {label, description?, disabled?: bool | () => bool, onSelect(ctx, menu), render?(args)}`
(`:65-73`). Layout options (`MenuLayout`, `:24-51`):

| Option | Default | Effect |
|---|---|---|
| `x` (px or `"center"`), `y`, `width` | 8, 8, 272 | box position (viewport/stack coordinates) |
| `showBorder` | true | 1-px border value 72 |
| `squareCorners` | false | rectangle instead of radius-8 box |
| `minHeight` | 224 (half screen − 16) | box never shorter |
| `maxHeight` | stack height − y − 8 | beyond this the list scrolls (also capped by the 64 KiB resource limit for depth/square menus, `:281-285`) |
| `footer` | — | dim hint line pinned at the box bottom |
| `dimUnderneath` | none | brightness factor for everything below |
| `opaque` | false | page (replaces what's below) vs popup (drawn over it) |
| `depth` | 0 | stereo depth of the whole menu |

Anatomy (`:270-320`): box fill 1; title at (x+12, y+8) value 220; body starts after `menuTitleHeight + 8`;
rows inset 12 px from the box sides, text inset 10 px inside the row box and 4 px from its top; row pitch
`listRowHeight`, gap 1; footer at (x+22, bottom − 8 − lineHeight) value 110; scrollbar 7 px from the
right edge. Row text: normal 200, selected 255, disabled 70. MenuLayer lists **wrap** (`:219`).

Input (`:322-338`): double-click pops the layer; click runs the selected item's `onSelect(ctx, menu)` if
not disabled (disabled rows stay selectable but inert); scroll moves. Items close themselves by
`ctx.stack.pop()` (convention).

Helpers: `openModalMenu(ctx, title, items, initial)` pushes a centred popup of width
min(320, W − 40) sized to its items up to H − 40 (`:342-361`) — used for enum pickers and third-level
settings. `TextPageLayer` is a static full-page text with a "●● back" footer (`:367-396`).

### 3.6 Context menus (long-press vs tap-then-hold)

Two context menus exist (gesture assignment changed in v0.6.5: "— for system, ●— for app context menu",
`CHANGELOG:118`):

| | System menu | App (window) menu |
|---|---|---|
| Gesture | long-press (anywhere, over the app's own menu too) | tap-then-hold (`short-then-long-press`) |
| Owner / drawn by | shell (`app/ui/shell/shell.ts:1430-1501`) | the app (`app/ui/window-menu.ts`) |
| Entries | [Close window (if closeable)], **Focus app switcher** (initially selected so a reflexive tap never closes the window), Voice input (if voice enabled), Brightness (only if brightness ≠ Auto), Debug | app-specific entries built at open time; empty → the system menu opens instead |
| Footer | "●— app menu" when the app has entries (except hold-to-talk windows) | "— system menu" ("●— system menu" for hold-to-talk) |
| Closing | double-click / entry; closing yields focus to the sidebar unless the entry targets the window (Voice input) | pop |
| Special | opening it sends `system-menu-opened` to the window so its own menu closes; tap-then-hold over the open system menu switches to the app menu when the app has one | a window that *claims* long-press (games while playing) receives long-press instead; holding 4 s more opens the system menu anyway (`app/ui/shell/shell.ts:205-211,803-823,1397-1410`) |

For worker apps, `WindowMenu` (`app/ui/window-menu.ts:73-154`) hosts a transient `LayerStack` whose base
paints the app content, pushes a `WindowMenuLayer`, reports `set-window-gestures` on change, and
routes input to the menu while open; `open(items)` can also show an ad-hoc per-row action menu
(terminal connection actions, `app/apps/terminal/terminal-app.worker.ts:1355-1392`).

### 3.7 Settings menus

**Setting objects** (`app/ui/dashboard-settings.ts:75-229`): `ConfigSettingBoolean`, `ConfigSettingEnum`
(values list, formatter, optional normaliser, `isDisabled(value)` for unavailable choices, `next()`),
`ConfigSettingString` (editor title for the phone, glasses edit title, input kind text/email/password,
normaliser, validator; an invalid draft is stored but the last valid value is kept under `<key>.valid`
for consumers, `:188-229`). Storage is a process-wide native key/value store shared by the main thread
and all workers, with change broadcasts to every isolate, delivered one tick later
(`app/native/settings-store.ts:1-80`, `app/ui/dashboard-settings.ts:49-73`). The on-disk format is a
versioned JSON document (schema 2) where a fixed set of keys hold structured JSON
(`native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/SettingsDocument.kt:3-60`).

**Row builders** (`app/ui/dashboard-settings.ts:1099-1207`):

| Builder | Row look | Click |
|---|---|---|
| `enumSettingMenuItem(setting, {onChange})` | label left, formatted value right | centred modal list of all values; current marked " *"; disabled values drawn 70 and inert; pick → set, `onChange`, pop |
| `toggleSettingMenuItem(setting)` | label + toggle switch | flip immediately |
| `textSettingMenuItem(setting)` | label + value (formatter masks secrets as `pk_ab...`/"(not set)"; truncated to 22 chars) | start the **phone text editor** for this setting and push `EditTextSettingLayer`: title (220), "Look at the phone app to type a value.", live value, validation error, "●● back" |

Phone-editor flow (`app/g2/dashboard-controller.ts:1148-1162,1895-1911,2027-2090`): the glasses show the
edit page; the phone shows 1–2 text fields (+ optional toggle) bound to the setting(s) and writes through
on every keystroke; phone *Done* validates (alert on error), ends the edit and closes the glasses page;
*Cancel* restores the original values; double-click on the glasses ends the edit. Workers request the same
flow by setting id (`start-text-setting-edit`) and paint their own "type on the phone" page.

**Settings panel** (`SettingsPanelLayer`, `app/ui/dashboard/settings-panel.ts:48-249`) — master/detail:

- Left column (x 6, width 150): section labels; right column (x 178 → viewport right − 8) previews the
  highlighted section's items *without* a selection; a 1-px divider (value 40) between them.
- Scroll in the left column changes section and resets the right column to its top.
- Click / swipe-right on a section with items moves focus right (first item selected); click on an item
  runs it; double-click / swipe-left moves focus back left (right keeps scroll, loses selection); from the
  left column it yields to the sidebar.
- When an item with a `description` is selected in the right column, up to 3 wrapped lines (value 150)
  appear in a band at the bottom of the right column over an opaque background with a separator; the list
  shrinks so the selected row never hides under it. The band is painted by a companion layer that always
  sits above the panel (`SettingsDescriptionOverlayLayer`, `:264-278`) so its raster covers list glyphs.
- A section may render custom content above its items (`renderDetail`: Watch connection status; About
  logo/version/licence) (`app/ui/dashboard/settings-menus.ts:479-515`).
- Third-level menus (Auto-brightness, Battery indicators, model downloads, input tokens) open as centred
  modals (`openSettingsSubMenu` = `openModalMenu`, `settings-panel.ts:284-286`).

Other apps build their settings from the same row builders inside their app menus (Nightscout, Music,
Timers, Calculator, Glanceboard, Microphones, Navigate).

---

## 4. Input routing

### 4.1 Gesture vocabulary

Normalised event type: `InputEvent` (`app/ui/gestures.ts:24-75`), each stamped with `timestampMs`, most
with a `source ∈ {ring, left-arm, right-arm, watch}`. Mapping from device reports
(`app/ui/shell/shell.ts:1564-1654`, event ids `app/g2/events.ts:61-81`):

| Physical action | Event | Notes |
|---|---|---|
| Ring or temple tap | `click` | source ring / left-arm / right-arm |
| Ring or temple double tap | `double-click` | outside an EvenHub page the stock firmware consumes it and only reports `display-wake` (wake-only) |
| Ring / temple swipe (one notch) | `scroll-up` / `scroll-down` | stock reports often omit the source |
| Press and hold | `long-press`, later `long-press-release` | forwarded by the custom firmware (replaces the stock force-quit dialog) |
| Tap then hold | `short-then-long-press` | firmware 2.2.9 gesture; its trailing generic release is ignored |
| Ring touch-down | `ring-press` | raw, uninterpreted; only games use it |
| Head tilt up while asleep | `display-wake` (HEAD_UP) | used for the Glanceboard |
| "Hey Even" | `wakeword` | acted on even with the screen off |
| Watch swipe left/right/up/down | `swipe-*` (source watch) | spatial input; watch tap/double/hold/crown map to click/double-click/long-press/scroll with source watch |
| Phone mirror touch | synthetic, source watch | §4.5 |
| — | `system-menu-opened` | not a gesture: shell → window notification (§3.6) |

Duplicate/bouncy ring reports closer than 100 ring ticks are dropped (press/release types excepted)
before any routing (`app/ui/input-monitor.ts:28-56`).

**Directional fallback** for components that don't opt into spatial input: swipe-up/down → scroll-up/down,
swipe-right → click ("select"), swipe-left → double-click ("back") (`app/ui/gestures.ts:96-114`).

### 4.2 Pre-routing in the controller (`app/g2/dashboard-controller.ts:2112-2241`)

1. Filter (`acceptInput`).
2. **Locked:** only a ring/watch double-click (toggles the locked screen off/on) and `display-wake`
   (wake) are acted on; everything else is dropped (`:2125-2149`).
3. **Screen off:** ask the Glanceboard which event (if any) the gesture means; if one, the board handles it
   and routing stops; a double-click while the board is visible dismisses it and continues to the wake below
   (`:2150-2162,2243-2249`).
4. `wakeword` (action ≠ off) or `display-wake`: pre-wake the shell with **sidebar focus** and await the wake
   barrier before the event reaches the shell (`:2164-2182`).
5. `shell.receiveInput(event)`; the outcome says whether the shell and/or the window needs a repaint.

### 4.3 Shell routing algorithm (`app/ui/shell/shell.ts:716-990`)

Evaluated in order:

1. `ring-press` → the foreground window only if screen on, focus = window and no overlay; else dropped.
2. `display-wake` → wake (sidebar focus) if asleep; else nothing.
3. `wakeword` → swallowed if the window owns the mic; action "off" → ignore; else wake (sidebar); for
   "voice-input": continue the assistant conversation if its overlay is up, else open the voice dialog
   hands-free with *Send to Assistant* highlighted.
4. Any other event resets the idle timer; any event except `long-press` cancels the 4 s escape timer.
5. Screen off → `double-click` wakes (sidebar focus); everything else ignored.
6. `long-press`: overlay open → consumed. Foreground window neither hold-to-talk nor claiming long-press →
   **system menu**. Otherwise (claiming) start the 4 s escape timer (not for hold-to-talk), move focus into the
   window if it was on the sidebar, forward.
7. `short-then-long-press`: hold-to-talk window → system menu. If the system menu is open and the window has an
   app menu → close the system menu. Any other overlay → consumed. Else focus the window and forward (the window
   opens its app menu or requests the system menu).
8. `long-press-release`: ends a voice capture if one is running; forwarded only to a focused window with no
   overlay.
9. Overlay open → top overlay.
10. Focus = sidebar → sidebar handler.
11. Else → foreground window (with directional fallback unless it accepts directional input).

### 4.4 Gesture × context table

"Menu keys" = scroll moves the selection, click activates the selected row, double-click closes/backs.

| Context | click | double-click | scroll up/down | long-press | tap-then-hold | watch swipes |
|---|---|---|---|---|---|---|
| Screen off, Glanceboard disabled | — | **wake** → sidebar focus | — | — | — | — (watch scroll/tap-hold explicitly ignored) |
| Screen off, Glanceboard enabled | show board for tap duration (unless "Show on tap" = Disabled) | wake regular UI | — | show board while held (if enabled); release hides | same as long-press | — |
| Glanceboard showing | restart hide timer | dismiss board and wake regular UI | — | hold | hold | — |
| Head tilt while asleep | — | — | — | — | — | board for tap duration if "Show on head tilt", else wake regular UI |
| Locked (screen on / off) | — | ring/watch: screen off / on | — | — | — | — |
| Sidebar focused | enter foreground window | **screen off** | previous / next window (wraps; foreground switches live) | system menu (or, for claiming / hold-to-talk windows, focus moves in and the window gets it) | focus moves into the window, which opens its app menu | up/down move, right enters, left ignored |
| Window, app root | app-defined (usually activate) | **yield focus to sidebar** | app-defined (usually move) | system menu | app menu (system menu if none) | fallback unless the layer opts in |
| Window, pushed page/menu | activate | pop one level | move | system menu | app menu | fallback / opt-in |
| System menu | run entry | close (focus → sidebar) | move (wraps) | stays open | switch to app menu if the app has one | fallback |
| App (window) menu | run entry | close | move | system menu (closes the app menu) | no-op | fallback |
| Notification modal | run action (Back / action / Dismiss / Don't show) | close (re-sleep if it woke the screen) | move in action column | consumed | consumed | fallback |
| Voice dialog: capturing | end capture (menu- or wakeword-opened) | discard | — | consumed | consumed | fallback |
| Voice dialog: menu | Send to Assistant / Type Into App / Continue / Discard | discard | move | consumed | consumed | fallback |
| Voice dialog: continuing / refining | done / — | cancel continuation / cancel refine | — | consumed | consumed | fallback |
| Keyboard dialog | send to selected target / discard | discard | move | consumed | consumed | fallback |
| Assistant overlay: thinking | — | cancel turn | — | consumed | consumed | fallback |
| Assistant overlay: done/error | Follow-up (voice) / Done | close | move | consumed | consumed | fallback |
| Alert | dismiss | dismiss | — | consumed | consumed | — |
| Brightness picker | close | close | ±10 % (2..100; inert on Auto) | consumed | consumed | fallback |
| Hold-to-talk window (AI Chat) | app menu (at base) | cancel draft, yield | scroll transcript 3 lines | hold = talk, release = send | **system menu** | up/down scroll |
| Window claiming long-press (Blocks, Minesweeper while playing) | game | pause / game | game | game move; hold 4 s more → system menu | app menu (pauses first) | spatial game controls |

Sources: `app/ui/shell/shell.ts:716-990,1430-1501`, `app/g2/glance-state.ts:38-86`,
`app/g2/dashboard-controller.ts:2112-2249`, `app/ui/menu.ts:322-338`, `app/ui/window-menu.ts:139-153`,
`app/ui/shell/voice-input.ts:270-314`, `app/ui/shell/keyboard-input.ts:129-152`,
`app/ui/shell/assistant.ts:91-122`, `app/ui/shell/brightness-picker-layer.ts:49-66`,
`app/apps/ai-chat/ai-chat-app.ts:95-112`, `app/apps/minesweeper/minesweeper-app.worker.ts:1-19`.

Voice-dialog note: the dialog still supports push-to-talk (`long-press-release` ends capture,
`app/ui/shell/shell.ts:860-862`), but since long-press became the system-menu gesture every current entry
point opens it in click-to-finish or hands-free mode (`:1015-1131`). **(uncertain whether push-to-talk is
still reachable.)**

### 4.5 Watch, phone mirror and other input sources

- **Wear OS watch** (`app/g2/wear-remote.ts:45-74`): sends click, double-click, scroll (crown; direction
  configurable), long-press (+ explicit start/release), tap-then-hold, wakeword and the four swipes, all
  tagged source `watch`, plus app-launch and lock commands. A synthetic long-press is immediately followed by
  its release so the escape timer never fires (`app/g2/dashboard-controller.ts:1811-1817`). While the display
  is off, watch scroll and tap-then-hold are ignored (`:1804-1810`). Components may give the watch a richer
  spatial scheme (launcher, files, games, timers pickers) without changing the ring's.
- **Phone mirror** (setting "Touch mirror", `app/g2/dashboard-controller.ts:1830-1879`): coordinates are
  fractions of the mirror. Locked → only double-tap. Asleep → double-tap wakes, tap/hold act as a click
  (Glanceboard). Awake: non-tap gestures → the watch scheme (double-tap = back, hold = long-press, swipes);
  a tap on a sidebar slot focuses that window; a tap inside the viewport focuses the window and offers the
  point to the app's `hitTest` (launcher opens the tapped cell, timers selects/activates a row); otherwise it
  becomes a click. Taps are ignored as positions while an overlay is open.
- **Input tokens / CLI** (Settings › API Keys › Input tokens): a local HTTP API (localhost or Tailscale, port
  8791) that can inject input, type into the foreground window or message the assistant, per-token permissions
  (`app/ui/dashboard/remote-input-menu.ts:1-90`, `app/remote/protocol.ts:2`).
- **Phone keyboard button** opens the keyboard dialog (wakes the screen) (`app/ui/shell/shell.ts:1142-1186`).

### 4.6 Per-app input conventions (rules every app follows)

1. **Double-click = back**: pops the top layer; at the app root yields focus to the sidebar; from the sidebar
   turns the screen off; from off, wakes. This is the universal escape ladder.
2. **Long-press is reserved** for the system menu; apps may claim it only while it means an in-app action and
   must report that (`claimsLongPress`); the system menu remains reachable by holding longer.
3. **Tap-then-hold** is the app's context menu; apps without one delegate to the system menu.
4. Scroll moves selection/cursor; click activates. Two-level navigation (row, then item) is used for grids
   to halve the number of scroll steps (launcher, file icons, minesweeper).
5. Apps that accept dictation implement `receiveTextInput`; the voice dialog then offers "Type Into App"
   (`app/ui/shell/shell.ts:1085-1110`).
6. Games pause whenever they lose input focus (overlay, sidebar focus, screen off, background).

---

## 5. Notifications UX

### 5.1 Data source and filtering

Android `NotificationListenerService` (`App_Resources/Android/src/main/java/com/faceclaw/app/FaceclawMediaNotificationListenerService.kt`).
A notification is *eligible* for the glasses when it is not from Faceclaw itself, not category
`transport`, not a media-session notification, and its channel importance is above MIN (`:348-377`).
Model per notification: key, package, app name, title, text, bigText, subText, infoText, summaryText,
category, lines[], postTime, actions[{index, title, enabled}], optional custom dismiss label
(`app/native/notification-types.ts:1-10`).

**Per-app popup filter** ("Notification filter"): every package that has ever posted is remembered in
`notifications.sources` (`[{packageName, appName, showOnGlasses}]`, default shown) so muted apps can be
re-enabled later (`app/native/notification-sources.ts:1-60`). The filter only affects *popups*; the list and
top-bar icons still include all eligible notifications.

### 5.2 Top-bar icons

Up to as many 24×24 grey icons as fit between the clock and the tray/battery block (§2.3); one icon per
notification *group*; group summaries skipped (`FaceclawMediaNotificationListenerService.kt:91-131,337-391`).
Icons are cached with eager invalidation on post/remove and a 60 s backstop TTL; a paint may use stale icons
and then schedules a fresh repaint (`app/native/notification-icons.ts:1-90`).

### 5.3 Popup (new-notification modal)

Trigger: a newly posted eligible notification (re-posts of *ongoing/no-clear* notifications don't re-pop;
updates to ordinary ones do, `FaceclawMediaNotificationListenerService.kt:301-335`) whose source is enabled
(`app/g2/dashboard-controller.ts:2561-2576`):

- Screen **off** → wake (sidebar focus) and open the modal; closing the modal puts the screen back to sleep
  ("sleep-popup" behaviour) (`app/ui/shell/shell.ts:659-683`).
- Screen **on** → the modal opens over whatever is showing without stealing focus from it; input goes to
  the modal while it is up. Several notifications stack as several modals **(uncertain intended behaviour)**.
- No auto-dismiss timer; the normal screen timeout eventually sleeps (which clears overlays).

Layout: `ShellModalLayer` box over the min viewport (inset 14; §2.5) hosting a `SingleNotificationLayer`
sized to the box interior (`app/ui/shell/modal-layer.ts:40-66`, `app/ui/notifications.ts:184-327`):

| Region | Content |
|---|---|
| Header | "Notification" (220) at top-left |
| App line | 24-px app icon + "App name  5m" (150); relative time is now / Nm / Nh / Nd (`app/util/date-util.ts:22-31`) |
| Body (left, width = box − 148 − ~68) | title wrapped (first line 230), blank line, bigText/text + lines (190), blank line, sub/info/summary text; "..." when truncated (`app/ui/notifications.ts:405-447`) |
| Action column (right, 148 wide, from y 22 to bottom − 17) | Menu (no wrap, gap 3, radius 6): **Back**, one row per Android action (" (unavailable)" if it has no intent), **Dismiss** (or the app's dismiss label), and in the popup only **Don't show on glasses again** (wraps to several lines) (`:450-477`) |

Actions: an action row fires the notification action's PendingIntent (there is **no text-reply support** —
RemoteInput actions fire without text; `FaceclawMediaNotificationListenerService.kt:218-242`); Dismiss cancels
the notification; if the notification disappears (acted on, cancelled on the phone) the view closes itself
(`app/ui/notifications.ts:226-286,322-326`). "Don't show on glasses again" dismisses the notification and
switches to a confirmation: menu **Cancel / Turn off popups** above an explanation ("Turn off notification popups
from X? You can turn them back on in the Notifications app's Notification filter.") (`:264-311`).

### 5.4 Notifications app

List of active notifications (max 50) as cards (`app/ui/notifications.ts:88-177,330-403`): card = rounded
rect r 8 at x 20, full width − 40, 6-px gaps; 24-px icon at (10, 8); text at x 42. Unselected: line 1
"title  5m" (140) + first body line (185) + "N quick actions". Selected: expands to title, app name, up to 4
wrapped body lines (235), actions count; border 110 and fill 15 when focused (else 38 border, black fill).
Scrolling centres the selected card; the "Notifications" title scrolls away with the list. Empty state:
"No current Android notifications." or a permission hint; launching the app while the listener permission is
missing opens the Android settings prompt (`app/apps/notifications/index.ts`). Click → detail view (same
`SingleNotificationLayer`, without the "Don't show…" row; Back/double-click pops). App menu:
"Notification filter" → full-page list of remembered sources with toggles, footer "On = show popups on
glasses" (`app/ui/notification-filter.ts:11-56`, `app/apps/notifications/notifications-app.ts:19-48`). The
list repaints on every posted notification.

Assistant tools `notifications.list` / `notifications.dismiss` exist (`app/assistant/system-tools.ts:132-160`).

---

## 6. Built-in apps

Common facts unless stated: in-process window in the `min` band, closeable, root wrapped so double-click
at the root yields to the sidebar, tap-then-hold opens the listed app-menu entries (else the system menu).

### 6.1 Launcher ("Apps") — `app/apps/launcher/`

- **Purpose:** app grid; the pinned first sidebar entry and the boot foreground; not closeable, never
  listed in its own grid (`app/apps/launcher/index.ts:33-60`).
- **Entries:** every app with `showInLauncher ≠ false` plus installed EvenHub packages, merged with
  **folders**; everything sorted alphabetically by label (folders and apps interleaved)
  (`app/apps/launcher/launcher-app.ts:110-151`). Folder state is one JSON map appId → folder name
  (`launcher.folders`); a folder exists while ≥ 1 known app is in it; defaults: *Games* (blocks, freecell,
  minesweeper, pinball, flappy), *Prototypes* (calculator, microphones, nightscout, roam), applied only while
  the setting is unset (`app/apps/launcher/launcher-folders.ts:11-123`).
- **Layout** (`launcher-app.ts:256-336`): 5 columns (column width = viewport/5), 44-px icons, label below
  (small font, 210, truncated to column − 8, centred), row height = `iconGridMinRowHeight` (≈ 71 px at the
  default font), grid top 6 px (below a folder-name header inside a folder), bottom margin 4; rows scroll to
  keep the selection among the fully visible rows; a partially visible next row peeks (its icon clipped).
  Folders use a filled-folder icon.
- **Ring navigation (two-level):** entering the window starts in **row mode** (full-width row band highlight);
  scroll picks a row; click enters **item mode** on the *middle* column (clamped); scroll then moves across
  items linearly, continuing onto the adjacent row at row ends; click launches the app or opens the folder
  (same grid restricted to the folder's apps, sorted); double-click: item → row mode; row mode inside a folder
  → back to the top grid with the folder selected; top level → yield to sidebar (`:338-393`).
- **Watch navigation:** always cell mode; swipes move spatially; left from the first column leaves the folder,
  then the grid; click opens (`:403-452`). While unfocused and the watch was the last input, the cell outline
  (not the row band) previews what a click would open (`:289-300`).
- **Mirror tap:** selects and opens the cell under the finger (`:459-477`).
- **App menu (item mode on an app only):** *Move to folder* → submenu of existing folders + *New folder*
  (auto-named "New folder", "New folder 2" …) + *Remove from folder*; *Uninstall* (EvenHub packages) with
  confirmation (`:176-254`). Assistant tools can also launch apps and (re)group folders (`app/assistant/window-tools.ts`).
- Repaints when folder state or the app list changes (`:526-537`).

### 6.2 Glanceboard (app + sleep-time board) — `app/apps/glanceboard/`

- **Purpose:** configure the sleep-time dashboard (§1.12, §2.12); also supplies the board itself via the
  `glanceboard` provider (`app/apps/glanceboard/index.ts`).
- **Home page** (`glanceboard-app.ts:68-280`): left half = title, intro paragraph ("Your Glanceboard is a
  display for things you want to look at quickly…"), then a menu: **Enable Glanceboard** (toggle),
  **Preview** (shows the live board in the window; any click/double-click returns; the window grows to `max`
  for the 2×3 layout), **Select Contents** (moves focus into the preview), **Settings**. Right half = scaled
  preview of the layout: dividers (dotted at the dimmest level when lines are off), each region labelled with
  its widget name ("Empty" otherwise), the selected slot highlighted.
- **Select Contents:** scroll cycles slots (wraps), click opens a modal list of widgets (current marked " *";
  choosing one clears duplicates, except a tall widget merging into the adjacent slot), double-click returns
  to the menu (`:282-300`, `glanceboard-settings.ts:167-183`).
- **Settings page:** Layout (2×2 / 2×3), Show on tap (Disabled / 3 / 5 / 7 / 10 s, default 5 s), Show on long
  press (on), Show on head tilt (on), Show lines (on), Depth (−64…+64, default 0) (`:302-313`).
- Window height is `medium` (content exactly 576×288 so the preview is 1:1) (`:344-376`).

### 6.3 Settings — `app/apps/settings/`, `app/ui/dashboard/`

- In-process because the glasses-side text editor is synchronised with the phone editor via the controller
  (`app/apps/settings/settings-app.ts:33-39`). Singleton; relaunch with a `section` deep-links (used by app
  menus: Terminal → "Settings" opens the Terminal section).
- UI = the two-column panel of §3.7. Sections and rows (Android; `app/ui/dashboard/settings-menus.ts:95-249`):
  **Display** (Brightness, Auto-brightness ›, Screen timeout, Enable lock screen, Vertical position, Display
  mode, Battery indicators ›, Time format, Font ›), **Voice** (Wakeword action, Transcription provider,
  on-device model downloads ×2), **Assistant** (backend, model, on-phone model download, send without
  confirming, bridge host/port/token, allow proactive), **API Keys** (Input tokens ›, ElevenLabs, OpenAI, Soniox,
  Anthropic, Mapbox), **Terminal** (display mode, vertical position, font ›, launch presets, auto-reconnect,
  wake on bell), **Navigate** (display mode, vertical position, home, work, remember recent), **Phone display**
  (rotation, preview colour, touch mirror), **Watch** (status text; remote enabled, crown direction, can unlock,
  mirror assistant), **Developer** (ring connection, save voice recordings, firmware debug flags, suspend EvenHub
  when screen off, use mic control, show BLE bandwidth), **About** (logo, version, licence blurb; README, License,
  Privacy policy, Acknowledgements in a paged text viewer), **Quit** (Disconnect from glasses).
- Download rows show live status ("downloaded", "37% of 1.2GB") and open a one-item modal (Download / Cancel
  download / Delete model) (`:343-457`).

### 6.4 Notifications — `app/apps/notifications/`

See §5.4. Launching without notification-listener access triggers the Android permission screen and shows a
hint on the glasses.

### 6.5 Music (media controls) — `app/apps/music/`

- **Data sources:** the active Android media session via a notification-listener-backed media controller
  (metadata, playback state, position, album art, queue, transport, media volume); library browsing through
  other apps' `MediaBrowserService` (the Android Auto mechanism) — works without an active session
  (`app/native/media-controller.ts`, `app/native/media-browser.ts`).
- **Layout** (`app/apps/music/music-app.ts:19-160`): 112-px album art at (8, 8) ("no art" box otherwise);
  metadata column at x 134: title (2 lines if they fit, 230), artist (180), album (150), player app (110);
  elapsed/total times at y 108 and a 5-px progress bar at y 124. From y 136: left **actions** list
  (dense rows) and, right of a divider at x 190, the player's **queue** (active track marked ">") with a
  scrollbar, or "Playlist unavailable".
- **Actions:** Play/Pause, Playlist ›, Browse library ›, Volume (NN), Next track, Previous track — unavailable
  ones dimmed (`:288-299`). Playlist moves focus into the queue column (click jumps to that track;
  double-click returns to the actions). Volume opens a centred 340×150 modal: "Media volume", "NN / 100", bar;
  scroll ±2; double-click done (`:358-410`). Browse opens the library browser (picker first if several
  browsable players): folders/playables, click opens/plays, double-click goes up (`app/apps/music/media-browse.ts`).
- **Empty states:** no notification access ("…Tap to open settings", click opens Android settings); no session
  ("Click to browse a music app's library, or start playback on the phone").
- **Watch:** swipe right/left = next/previous track (`:207-213`).
- **App menu:** Music settings → list of discovered media apps with On (enabled) / Off (ignored) toggles
  (`app/apps/music/music-settings.ts:10-63`).
- Repaints on player state changes and every 1 s while playing.

### 6.6 Timers (timers, stopwatch, alarms) — `app/apps/timer/`

- **Engine** (`timer-engine.ts`): main-thread singleton booted at shell start, so timers ring and assistant
  `timer.*`/`alarm.*` tools work with no window open. Persists `timers.state` (timers, stopwatch with laps,
  alarms with repeat-day bitmask Mon..Sun, snooze, recent durations). Each expiry is backed by an Android
  alarm-clock alarm that rings on the phone if the glasses can't (not connected/worn, charging, not shown within
  seconds, or unacknowledged for 30 s). On ring: the Timers window is launched/focused and the glasses woken
  (setting), buzzer repeats every 3 s for up to 2 min; missed expiries > 5 min old are marked rung silently
  (`timer-engine.ts:1-100`, `app/apps/timer/index.ts:16-37`).
- **Layout** (`timer-app.ts:1-15,88-102`): right ~40 % (190–240 px) is a list with headers TIMERS / STOPWATCH /
  ALARMS: timer rows (remaining time, name; paused mark; rung blinks), "New timer", stopwatch row, alarm rows
  (time, days, on/off dot), "New alarm". The left **stage** shows the selected row large: title, big digits
  (Roboto Bold 96→40 px, largest that fits), progress bar / info line ("10 min timer · ends 14:32"), and hints.
- **Click** does the obvious thing: pause/resume a timer, dismiss/again a rung timer (two stage buttons chosen
  with scroll), start/stop the stopwatch, toggle an alarm, dismiss/snooze a ringing alarm, "New timer" → duration
  **dial** (scroll through a ladder 10 s … 24 h; click starts; double-click cancels), "New alarm" → time picker
  (hour, minute-tens, minute-ones [, AM/PM]; click advances fields, double-click steps back/cancels)
  (`timer-app.ts:320-560`, ladder `timer-model.ts:114-123`).
- **Ringing:** the selection jumps to a newly ringing item; scroll picks between the two buttons.
- **App menu per row:** timer (Dismiss/Again when rung, +1 minute, Pause/Resume, Restart, Label, Delete); New timer
  (Start <recent durations>, Fine-tune h:mm:ss); stopwatch (Lap, Start/Stop, Reset); alarm (Dismiss/Snooze, Turn
  on/off, Change time, Repeat: presets + per-day toggles, Label, Delete); always **Settings** (Sound on glasses,
  Wake glasses when ringing, Snooze length 5/10/15 min, Phone alarm check) (`:617-860`, settings `:820-856`).
- **Labels** are entered via the shell voice dialog ("Type Into App") (`:587-612`).
- **Tray icon:** "⏲ 12m" (minutes) or "45s" in the last minute while a timer runs (`:1352-1385`).
- Repaints once per second only while something visible is moving.

### 6.7 Calendar — `app/apps/calendar/`

- **Data:** Android Calendar provider (all visible calendars), next 14 days, max 50 events, 30 s cache
  (`app/native/calendar.ts:18-70`). Permission requested on launch and on any tap while missing.
- **Screen** (`calendar.ts:42-203`): title "Calendar" (scrolls away with the list); events grouped under day
  headers ("Today  Thu 11 Sep", "Tomorrow  …", "Fri 12 Sep") (150); each event row "14:30  Title" (205, selected
  235) and a location line (160); the selected row gets a filled rounded box; scroll moves the selection and the
  list scrolls to centre it. Click does nothing (no event detail).
- Launcher/sidebar icon shows today's date (`calendar-icon.ts`). Repaints every 30 s and on provider changes.
- Also a Glanceboard widget and an assistant tool `calendar.list_events`.

### 6.8 Weather — `app/apps/weather/`

- **Data:** US National Weather Service API (`https://api.weather.gov`): point lookup from the phone's
  approximate location → current observation (nearest station) + forecast; refresh every 30 min; up to 14
  forecast periods (`app/native/weather.ts:89-97`). US-only in practice **(inferred)**.
- **Screen** (`weather.ts:29-230`): header "Weather" + location name (right); current temperature in the large
  font (°F), description (medium), "Humidity NN%   Wind NW 12 mph", "Observed 12m ago"; a divider; a forecast
  table (name / temp / rain % / short forecast) as a scrollable non-wrapping menu (selection only, click does
  nothing). States: permission-required ("Allow approximate location…", click requests), locating/loading,
  error (click retries).
- **App menu:** Refresh.

### 6.9 Compass — `app/apps/compass/`

- **Data:** the glasses' magnetometer (right arm) via the custom firmware compass mode, enabled only while the
  window is visible (reconciled every 400 ms) and ref-counted with other users (Glanceboard widget, Navigate);
  optional declination from the phone location for true north (`compass-app.ts:34-150`).
- **Screen:** heading "43° NE" (large font; small if it doesn't fit), status line ("True heading",
  "Magnetic heading - location permission needed for true north", "Uncalibrated - Tap to calibrate",
  "Waiting for compass data…"), and a perspective-tilted compass rose (disc with side wall, 8 points, fixed
  facing tick, fading polar grid) centred on the **panel** centre, hanging off the bottom
  (`compass-rose.ts:1-60`). Screenshot: `website/screenshots/compass.png`.
- **Click:** calibration page — instructions, crosshair at the panel centre, readouts Raw / Offset / Declination
  / Calibrated; scroll adjusts the offset ±1°; double-click returns (`calibration-layer.ts:1-126`).
- **App menu:** Calibrate, North: True/Magnetic, Debug information: On/Off.

### 6.10 Navigate (Mapbox) — `app/apps/navigate/` (worker)

- **Data:** phone GPS (1 s fixes, via main-thread sensor relay), glasses magnetometer for a head-direction
  chevron, Mapbox Search Box geocoding, Directions (driving default; walking/cycling via assistant), Static Images
  for a greyscale map (`app/native/mapbox.ts:1-40`). Requires a Mapbox public token (Settings › API Keys, or the
  in-app setup page with "Open mapbox.com" / "Edit token").
- **Phases:** idle → acquiring (first fix ≤ 10 s) → routing → navigating ↔ arrived; separate "map" phase
  ("Map around me", no destination) (`navigate-app.worker.ts:116`).
- **Idle page:** title, hint text, status, and a list: Map around me, saved destinations (Home, Work and custom
  names with addresses), recent destinations; footer "▲▼ select   ● go   ●— app menu   ●● back"
  (`:1370-1436`). Dictated or typed text (voice dialog "Type Into App") starts navigation to that text; saved
  names ("home") resolve to their address (`:333-342,389-395`).
- **Navigating** (`:1438-1505`): left 260×260 map pane (follow mode = heading-up around the user with a chevron
  showing where the head points; overview mode = whole route), refreshed only when stale (bearing bucket 15°,
  moved 15 % of the pane, or 5 s idle with ≥ 10 m movement); right panel: maneuver glyph (56 px), distance to the
  maneuver (terminus32), instruction (terminus24, wrapped), "4.2 km · 12 min · 14:32" ETA line, status; footer
  "▲▼ zoom   ● overview/follow   ●● back". Rerouting at most every 20 s. Arrived: "Arrived" + destination; click done.
- **Map phase:** full viewport map + chevron; scroll zoom (±0.5 steps, −3…+2); click opens destinations.
- **App menu:** Stop navigation / Close map, Saved destinations › (Home/Work addresses, custom destinations with
  Navigate there / Set address / Rename / Remove, Add destination…), Remember recent (toggle), forget individual
  recents, Clear recent destinations, display settings (`:917-1070`). Name/address edits use the phone editor with
  a glasses "type on the phone" page (voice text also accepted).
- **Assistant tools:** `start_route`, `stop_route`, `route_status` (window tools) plus `nav.*` system tools.
- Supports live resize (display-mode change keeps the route) (`:299-311`).

### 6.11 Nightscout (glucose monitor) — `app/apps/nightscout/`

- **Data:** a Nightscout site (URL + API token) polled by a native bridge for the whole session: latest SGV,
  history, delta, direction, IOB/COB, cannula age, reservoir, pump battery, loop time (`app/native/nightscout-bridge.ts`).
- **Screen** (`nightscout.ts:301-400`): "Nightscout" title; big glucose value (large font; struck through when
  older than 15 min) + units; right column: "Delta +3  Trend →" (unicode arrows with fallbacks), "IOB … COB …
  Updated …", "CAGE 2d3h  Loop 5m", "Pump 120U 1.35V"; values that breach a configured threshold are drawn inverted;
  a 2-hour graph below y 128. Unconfigured/no-data states explain where to configure.
- **Tray icon** while any Nightscout window is open (or always, by setting): BG number, 48-px sparkline, warning
  triangle on breach (`nightscout-app.ts:18-132`).
- **App menu:** Refresh; Settings (site URL, API token, Always show in top bar, four thresholds: max cannula age h,
  cartridge low U, battery low V, max loop age min; 0 = off) (`nightscout.ts:405-432`).

### 6.12 Terminal (g2mirror) — `app/apps/terminal/` (worker)

- **Data:** one or more **g2mirror** servers (`g2mirror://token@host[:port]`, `g2mirrors://` for TLS) reached over
  websockets. Each enabled connection keeps a control socket (session list, bell and title notifications) for the
  worker's lifetime; each open session view has its own socket and an xterm emulator; history pages are fetched on
  demand (`terminal-app.worker.ts:1-31`).
- **Hub window** (`terminal:hub`, min band): "Manage Connections" row, then live sessions (grouped under host
  headings when several hosts are connected) with an activity indicator and the number glyph of the view window
  showing it; "Connect <host>" rows for disconnected hosts. Click opens/focuses a session view. Manage Connections:
  one row per connection with status (connected / connecting / retrying / failed / off / bad connection string) →
  per-connection menu (Connect/Disconnect, Remove, Cancel); "Add connection" (phone editor or dictation fills
  the draft; click confirms); "Done" (`:1272-1392`).
- **Session views** (`terminal:view:N`, **max** height by default, sidebar icon "›N" with a blinking cursor while
  output flows): the terminal grid in the terminal font (default Terminus 12, 6×12 cells → 96 × 37 in 576×452);
  scroll pages through scrollback (page = rows − 1; returning to the bottom re-locks follow mode); a right-edge
  scroll indicator; status banner; dictated text is typed into the session and submitted with Enter unless told
  otherwise (`:368-383,1143-1155,1747-1765`).
- **App menu:** Settings (Terminal section); hub: "Launch <preset> [@ host]" for each preset listed in Settings;
  view: Reconnect (when failed), Send <Enter>, Send <Esc> (`:1028-1091`).
- **Bells:** set the window's sidebar attention dot; with "Wake glasses on terminal bell" a bell while asleep wakes and
  focuses that window. Auto-reconnect with exponential backoff (1 s → 60 s) while any terminal window is open.
- Publishes the session list for the Glanceboard terminal widget (`publish-state`).
- Renders coalesced to ~30 fps (33 ms) (`:88,1757-1764`).

### 6.13 Files — `app/apps/files/`

- **Purpose:** browse phone storage; view text, images and fonts; install/run EvenHub packages; install fonts.
- **Browser** (`file-browser.ts:114-620`): header "Files  <path>" (path left-truncated, 130). Top level
  **Places** = [Grant file access (if missing)], bookmarks, "Internal storage", "/" (`:588-599`). Directories
  list folders first as "name/"; unsupported files are listed dimmed. Two views (`files.viewMode`): **icons**
  (5-column grid of 44-px icons by file type, launcher-style two-level row/item navigation; the watch moves one
  cell spatially) or **list** (dense rows). Click descends / picks a file; double-click backs out one level
  (item → row → parent → Places → leave the app).
- **Picked file:** info dialog (272 wide at (8, 8)): full name (≤ 3 lines), size, modified date, then actions by
  type (`files-app.ts:172-263`): text (.txt/.md/.log) → *View here* / *Open in new window*; images → same; fonts →
  *Preview here* / *Open in new window* / *Install* (row relabels to the result); .ehpk → *Run app* / *Install*
  (after a permission/privacy confirmation dialog when the package declares permissions); others → metadata only.
- **Viewers:** text viewer = title + paged body (UI font), footer "Page n/m", scroll = page (one line overlap),
  double-click closes (`text-viewer.ts`); image viewer = greyscale, dithered to 16 levels, fit without upscaling;
  font previewer = pangram, character set, paragraph at an adjustable size (scroll) and gamma (click).
  "Open in new window" creates a separate closeable document window (`files:doc:N`); Android share-intent text
  also opens as a document window (`app/apps/files/index.ts`).
- **App menu (browser on top only):** View as list/icons; Bookmark / Remove bookmark: <name>.

### 6.14 Teleprompter — `app/apps/teleprompter/`

- **Home:** page menu "Teleprompter": "Browse for a script..." + recent scripts (name left, parent folder right,
  dim); lands on the most recent script; app menu "Clear recent scripts" (`teleprompter-app.ts:31-184`).
  Browse pushes the Files browser restricted to text files.
- **Reader** (`reader.ts:45-300`): script laid out word by word in the **medium** font; continuous speech
  recognition tracks the reader's position (`script-tracker.ts`) and auto-scrolls so the next word sits about a
  third of the way down; word shades: spoken 90, upcoming 200, next word 255 (highlighted). Scroll moves the view
  2 lines and re-anchors tracking to what is now at the anchor row; click pauses/resumes tracking; double-click
  returns to the list. Footer: title (left) and "Listening/Paused  NN%" (right), value 110. App menu: Pause/Resume
  voice tracking, Restart from the top. Position is saved per recent file.
- Holds continuous mic capture while tracking.

### 6.15 Transcribe — `app/apps/transcribe/`

Live speech-to-text while the window is open: holds continuous mic capture and shows a mic tray icon
(`transcribe-app.ts:17-83`). Screen: "Transcribe" (200), status line (110), transcript body (230) following the
tail, 2-px scrollbar; scroll moves 3 lines (manual scroll detaches from the tail); app menu **Save** writes
`transcript-YYYY-MM-DD-HHMMSS.txt` to Downloads and shows the result in the status line (`transcribe.ts:15-109`).

### 6.16 AI Chat — `app/apps/ai-chat/`

- **Purpose:** conversational front-end to the shared assistant conversations (the same ones the wakeword
  overlay uses) (`ai-chat-app.ts:16-166`).
- **Gestures (hold-to-talk window):** long-press = record (status "Listening... release to send"), release =
  send; click = app menu; tap-then-hold = system menu; double-click = cancel draft and leave; scroll = transcript
  (3 lines; scrolling to the end re-follows the stream).
- **Screen:** conversation title; transcript lines "You: …" (175) / "AI: …" (245) wrapped, following the
  streaming tail, thin scrollbar; separator; status line; last two lines of the pending utterance; footer with the
  model label ("Sonnet · default reasoning" or "External agent") and hints.
- **App menu:** New session, Switch session › (✓ current), Model › (disabled if no key), Reasoning › (default /
  low / medium / high where supported), Cancel response (while streaming); disabled when an external agent
  bridge manages sessions.
- Typed/dictated text from the shell is sent as a message.

### 6.17 Calculator — `app/apps/calculator/`

- **Purpose:** spoken (or typed) maths problem in, exact answer out; modes **Solve / Explain / Graph** shown as
  tabs (active 245 with underline) plus status chips ("deg", "listening…", "mic on") (`calculator.ts:366-422`).
- **Input:** click toggles dictation of one problem (or, with Listening = Continuous, the mic stays on while the
  calculator is in front and answers anything that sounds like maths); scroll cycles the mode (re-applying it to
  the standing problem) or, in Explain, walks the step sequence; double-click cancels dictation/leaves
  (`:305-360`). Shows what it heard next to the answer so mishearing is distinguishable from a wrong answer.
- **Engine:** a local symbolic/numeric maths library (`math/`) with a coordinator that keeps one standing problem
  across window close/reopen and assistant calls; long computations are offered and run with a progress panel;
  optional LLM rewrite of the spoken problem (`calculator-app.ts:1-150`).
- **App menu:** Solve it / Explain it / Graph it, Clear, Calculator settings (Default mode, Listening, Trigonometry
  in degrees). Assistant tool `calculate`.

### 6.18 Microphones (prototype) — `app/apps/microphones/`

Page menu (`microphones-app.ts:449-515`): **Sonic Radar** › (top-down view around the head: listening beam wedge
rotated by scroll, click locks onto the talker, live direction-of-arrival tick, speaker dots, world-anchored with
the compass), **Levels** › (per-mic meters), **Captions view** › (speaker-prefixed live captions with inline
translations; scroll reviews), **People** › (recognised speakers, last heard, notes/recaps), toggles Captions,
Translate, Beam filter, Noise cancellation, **Microphone setup** ›, **Voice & speakers** ›, **Storage** ›
(save captions/recordings, retention, "Review on phone"). Opening starts the mic session (multi-mic via the custom
firmware's mic-control channel when enabled) and shows a dual-mic tray icon; closing stops it. Familiar voices
trigger ambient "encounter" cards (§2.5).

### 6.19 Roam (prototype, worker) — `app/apps/roam/`

Shows a Roam Research page (today's daily note by default) as a selectable outline (document model:
text/todo/heading/code nodes with nesting, `app/ui/document/document-model.ts`): scroll moves the selection, click
toggles a todo or follows a `[[page]]` link, double-click walks back through visited pages then yields. App menu:
Today's page, Refresh, Back, Set graph name, Set API token (phone editor). Edits go through the Roam backend API;
also exposed as assistant tools (`read_page`, `add_todo`, `set_todo_done`, `edit_block`; `roam.*`)
(`roam-app.worker.ts:1-9,82-130,350-392`).

### 6.20 EvenHub — `app/apps/evenhub/`

Compatibility host for third-party apps built for Even Realities' EvenHub SDK (web apps running in a phone
WebView that drive the glasses through the host) (`app/apps/evenhub/index.ts:1-10`, `session.ts:1-20`).
- **Store window** (`store-layer.ts:45-803`): tabs Top / New / Search / Updates (tab row is its own focus level),
  two-line rows (name, detail), paging; Search's first row edits the query via the phone editor or dictation; app
  pages with install/update/reinstall; optional login.
- **Running app window:** content area exactly 576×288 (`medium` band), or the "tall canvas" 576×452 (`max`) when
  the app requests it; centred when the viewport is 640 wide; all gestures go to the app except long-press
  (system menu) and tap-then-hold (window menu = the app's own declared menu items + "Show phone UI"); the app
  also hears tap-then-hold as its long-press; foreground and screen state are forwarded
  (`evenhub-window.ts:1-134`). Installed packages appear as launcher entries (uninstallable).

### 6.21 Games (worker apps; all have Sound on/off persisted as `<app>.soundOn`, pause on focus loss)

| Game | Summary | Controls (ring) | Source |
|---|---|---|---|
| Blocks | falling-blocks (10×20 board, 12-px cells) with levels/score | scroll moves, click rotates, long-press hard-drops (claims long-press while playing), double-click pauses; watch: left/right move, up rotate, down drop; paused: click resumes | `blocks/blocks-app.worker.ts:1-12,43-63` |
| Minesweeper | 12×9 board, Easy/Medium/Hard (14/20/26 mines); mines placed after the first reveal; clock runs only while live | row-select then column-select (scroll picks row, click → cells, scroll moves, click reveals/chords, long-press flags, double-click back to rows/pause); watch moves the cursor spatially; menu: New game, Difficulty, Sound | `minesweeper/minesweeper-app.worker.ts:1-19,53-66,300-330` |
| Freecell | standard 52-card Freecell; red suits marked since there's no colour | scroll moves a cursor over 16 locations (skipping ineligible ones), click selects source then destination (supermoves), double-click sends to foundation / cancels; auto-play of safe cards; menu: undo, new game, restart | `freecell/freecell-app.worker.ts:1-19` |
| Pinball | table left, score right; latency-tolerant physics (120 Hz step, ~9 fps render, motion ghost trail) | scroll sets launch power, click launches, **ring-press** flips both flippers for 300 ms, double-click pauses | `pinball/pinball-app.worker.ts:1-22,54-81` |
| Flappy | side-scroller tuned for ~250 ms latency; sprites drawn as cached images for bandwidth | **ring-press** (or watch swipe/click) flaps, double-click pauses; high score persisted | `flappy/flappy-app.worker.ts:1-26,74-113` |

### 6.22 Developer — `app/apps/developer/`

Page menu (`developer-app.ts:108-171`): **Load app from URL** (phone editor or dictation; launches an EvenHub app
straight from a dev server), **Load app from QR code** (phone scanner), **Show resource usage** (1-minute process
monitor), **Debug tests** › Input events (live log of every input incl. raw ring metadata; its menu has
back/clear/pause), Dither test, Buzzer demo (plays the sound-effect catalogue on the glasses piezo), Accelerometer
demo, Light sensor (ALS trace), BLE bandwidth benchmark, Unicode test (rendering stress samples). Also: the system
menu's **Debug** entry lists the assistant tools registered by the foreground app (§1.5).

---

## 7. Settings reference

All settings live in one process-wide key/value store shared by the main thread and workers, with
cross-isolate change notifications (`app/native/settings-store.ts:1-80`); on disk a versioned JSON document
(schema 2) where selected keys hold structured JSON rather than strings
(`native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/SettingsDocument.kt:28-35`). Types: B = boolean,
E = enum (string), S = string, J = JSON string. "Where" = UI location; "(state)" = not user-facing.
Declarations: `app/ui/dashboard-settings.ts` unless another file is named.

### 7.1 Global settings (Settings app)

| Key | T | Default | Values / notes | Where | Decl. line |
|---|---|---|---|---|---|
| `display.brightness` | E | `auto` | auto, 2, 10, 20 … 100 (0 normalised to 2) | Display; system menu › Brightness (hidden when Auto) | `:335` |
| `display.autoBrightnessMin` | E | `20` | 2, 5, 10, 20, 25, 30, 40 … 100 | Display › Auto-brightness | `:347` |
| `display.autoBrightnessMax` | E | `100` | same | Display › Auto-brightness | `:352` |
| `display.autoBrightnessCurve` | S | `0:0,0.2:7,1.5:33,5:100` | 2–16 lux:percent pairs, validated (`app/g2/brightness-curve.ts:1-40`) | Display › Auto-brightness | `:357` |
| `display.screenTimeout` | E | `30s` | 15s, 30s, 1m, 3m, never | Display | `:371` |
| `display.lockScreenEnabled` | B | true | | Display | `:381` |
| `display.verticalPosition` | E | `middle` | top, upper, middle, lower, bottom | Display | `:475` |
| `display.mode` | E | `576x288` | 576x288 (Band), 576x480 (Tall), 640x480 (Full panel) | Display | `:324` |
| `dashboard.systemCard.batteryDisplayMode` | E | `stacked` | icon, percentage, stacked, stacked-percentage | Display › Battery indicators | `:231` |
| `display.battery.{phone,watch,glasses,ring}Visibility` | E | `always` | always, low (< 50 %), never | Display › Battery indicators | `:244-273` |
| `display.timeFormat` | E | `24h` | 24h, 12h | Display | `:295` |
| `display.uiFont2` | J | `{"kind":"ttf","file":"Roboto-Light.ttf","size":14}` | bitmap terminus/terminusv or TTF file+size (legacy `display.uiFont`) | Display › Font | `app/graphics/ui-fonts.ts:23,76` |
| `voice.wakeWordAction` | E | `voice-input` | voice-input, off (Ignore), turn-screen-on | Voice | `:613` |
| `voice.provider` | E | `onboard` | onboard (Moonshine), onboard-whisper, elevenlabs, whisper (OpenAI), soniox — cloud ones disabled without key | Voice | `:591` |
| `voice.enabled` | B | true | declared but not referenced by any UI (dead) | — | `:520` |
| `assistant.backend` | E | `direct` | direct, external (agent bridge) | Assistant | `:646` |
| `assistant.model` | E | `auto` | auto + provider models (disabled without key/download) | Assistant; AI Chat per conversation | `:742` |
| `assistant.skipConfirmationAfterWakeword` | B | false | wakeword utterances go straight to the assistant | Assistant | `:631` |
| `assistant.bridgeHost` / `assistant.bridgePort` / `assistant.bridgeToken` | S | "" / `8790` / "" | token masked | Assistant | `:657-687` |
| `assistant.allowProactive` | B | true | external agent may use glasses tools outside a turn | Assistant | `:689` |
| `voice.elevenLabsApiKey`, `voice.openAiApiKey`, `voice.sonioxApiKey`, `llm.anthropicApiKey`, `maps.mapboxApiKey` | S | "" | masked ("abcdef...") | API Keys | `:698-770` |
| `remoteInput.newName` | S | `My app` | draft for new input tokens | API Keys › Input tokens | `app/ui/dashboard/remote-input-menu.ts:13` |
| `terminal.displayMode` | E | `default` | default (tall sessions), global, 576x288, 576x480, 640x480 | Terminal | `:489-517` |
| `terminal.verticalPosition` | E | `global` | global, top … bottom | Terminal | `:518` |
| `terminal.font` | J | bitmap Terminus | monospace faces only | Terminal › Font | `app/graphics/ui-fonts.ts:24,94-96` |
| `terminal.launchPresets` | S | `shell` | comma-separated preset names | Terminal | `:805` |
| `terminal.autoReconnect` | B | true | | Terminal | `:816` |
| `terminal.wakeOnBell` | B | false | | Terminal | `:825` |
| `navigate.displayMode` | E | `global` | global, 576x288, 576x480, 640x480 | Navigate | `:515` |
| `navigate.verticalPosition` | E | `global` | | Navigate | `:516` |
| `navigate.homeAddress` / `navigate.workAddress` | S | "" | | Navigate; Navigate app menu | `:930,942` |
| `navigate.rememberRecent` | B | true | turning off clears the list | Navigate; Navigate app menu | `:954` |
| `phone.rotation` | E | `auto` | auto, portrait, landscape | Phone display | `:396` |
| `phone.previewColor` | E | `white` | white, green (mirror rendering) | Phone display | `:406` |
| `phone.mirrorTouch` | B | true | mirror touches drive the glasses | Phone display | `:417` |
| `watch.remoteEnabled` | B | true | | Watch | `:430` |
| `watch.crownClockwiseNext` | B | false | | Watch | `:448` |
| `watch.canUnlock` | B | true | | Watch | `:439` |
| `watch.mirrorAssistant` | B | true | | Watch | `:457` |
| `developer.ringConnectionMode` | E | `glasses` | glasses (relay), direct | Developer | `:564` |
| `developer.saveVoiceRecordings` | B | false | | Developer | `:623` |
| `developer.firmwareDebugFlags` | B | false | | Developer | `:528` |
| `developer.suspendEvenHubWhenScreenOff` | B | true | battery vs wake latency | Developer | `:536` |
| `developer.useMicControl` | B | true | multi-mic channel for Microphones | Developer | `:544` |
| `developer.showBleBandwidth` | B | false | phone overlay | Developer | `:553` |

### 7.2 App-scoped settings (edited inside apps)

| Key | T | Default | Values / notes | Where |
|---|---|---|---|---|
| `glanceboard.enabled` | B | false | | Glanceboard app home (`app/apps/glanceboard/glanceboard-settings.ts:48`) |
| `glanceboard.layout` | E | `2x2` | 2x2, 2x3 | Glanceboard › Settings (`:30`) |
| `glanceboard.tapDuration` | E | `5s` | off (Disabled), 3s, 5s, 7s, 10s | Glanceboard › Settings (`:66`) |
| `glanceboard.showOnLongPress` / `glanceboard.showOnHeadTilt` / `glanceboard.showLines` | B | true / true / true | | Glanceboard › Settings (`:89,98,132`) |
| `glanceboard.depth` | E | `0` | −64 … +64 step 16 | Glanceboard › Settings (`:106-125`) |
| `glanceboard.quadrants.slot.0..5` | E | system-card, calendar, music, calendar, none, none | none, system-card, calendar, terminal, nightscout, compass, music | Glanceboard › Select Contents (`:8,28,144-159`) |
| `timers.soundOnGlasses` / `timers.wakeGlasses` | B | true / true | | Timers app menu › Settings (`app/apps/timer/timer-engine.ts:76-91`) |
| `timers.snoozeMinutes` | E | `10` | 5, 10, 15 | Timers app menu › Settings (`:93-99`) |
| `calculator.defaultMode` / `calculator.listening` / `calculator.usesDegrees` | E/E/B | solve / tap / false | solve, explain, graph / tap, continuous | Calculator app menu (`app/apps/calculator/calculator.ts:29-56`) |
| `integrations.nightscout.siteUrl` / `.apiToken` | S | "" | token masked | Nightscout app menu › Settings (`:858-880`) |
| `integrations.nightscout.alwaysShowInTopBar` | B | false | tray icon even with no window | same (`:907`) |
| `integrations.nightscout.{max-cannula-age-hours, cartridge-low-units, battery-low-voltage, max-loop-age-minutes}` | S | `0` (off) | non-negative number | same (`:882-906`) |
| `integrations.roam.graphName` / `.apiToken` | S | "" | | Roam app menu (`:834-857`) |
| `microphones.captions-enabled`, `.translate-enabled`, `.save-captions`, `.save-recordings`, `.beam-filter`, `.anc-enabled`, `.wearer-commands-only` | B | false, false, true, false, false, true, false | | Microphones pages (`app/apps/microphones/mic-settings.ts:51-130`) |
| `microphones.captions-retention` / `.recordings-retention` | E | `1m` / `1w` | none, 1d, 1w, 1m, 1q, 1y, forever | Microphones › Storage |
| `files.viewMode` | E | `icons` | icons, list | Files app menu (`app/apps/files/file-browser.ts:38-45`) |
| `compass.northReference` | S | `true` | true, magnetic | Compass app menu (`app/apps/compass/heading.ts:15,34`) |
| `compass.debugInfo` | B | false | | Compass app menu |
| `<game>.soundOn` | B | true | per game | game app menus (`app/ui/sound-setting.ts:1-26`) |
| `minesweeper.difficulty` | S | Easy | Easy, Medium, Hard | Minesweeper app menu |

### 7.3 Persistent UI state (not presented as settings)

| Key | Content |
|---|---|
| `launcher.folders` (J) | appId → folder name (`app/apps/launcher/launcher-folders.ts:11-24`) |
| `notifications.sources` (J) | per-package popup toggle (`app/native/notification-sources.ts:3`) |
| `music.apps` (J) | enabled/ignored media apps (`app/native/media-apps.ts:3`) |
| `files.bookmarks` (J) | bookmarked paths (`app/apps/files/file-browser.ts:48`) |
| `teleprompter.recents` (J) | recent scripts + reading positions |
| `terminal.connections` (J), `terminal.newConnectionDraft` (S) | g2mirror connections; add-connection draft |
| `navigate.savedDestinations` / `navigate.recentDestinations` (J), name/address drafts (S) | Navigate destinations (`app/ui/dashboard-settings.ts:968-1012`) |
| `timers.state` (J) | timers, stopwatch, alarms, recent durations |
| `assistant.conversations` (J) | chat sessions (`app/ui/shell/shell.ts:336-346`) |
| `compass.calibrationOffsetDegrees`, `compass.calibrated`, `compass.declination.*` | calibration and cached declination |
| `flappy.highScore`, `pinball.highScore` | high scores |
| `evenhub.installedApps.v1` (J), `evenhub.store.searchQuery`, `integrations.evenhub.email/token` | EvenHub |
| `developer.appUrl` | last "Load app from URL" |
| `onboarding.complete`, `onboarding.previewOnly`, `onboarding.welcomeSoundPending` | phone onboarding (`app/phone-ui/onboarding-state.ts:3-5`) |
| file `open-apps.json` (documents dir, not the store) | open windows + foreground (§1.8) |

---

## 8. Behavioural invariants worth preserving

1. **Universal escape ladder:** double-click always goes "back one level" → app root → sidebar → screen off;
   double-click from off wakes. No app can trap the user: long-press opens a shell-drawn system menu whose
   *Focus app switcher* and *Close window* work even if the app is hung (§3.6, §4.6).
2. **Selection = foreground:** moving through the sidebar immediately shows each app; entering is a click.
3. **Focus is visible:** focused lists show a filled highlight, unfocused ones an outline; the sidebar tab is
   white when the sidebar has focus.
4. **Stable geometry for the eyes:** everything the shell draws aligns to one calibrated band (vertical
   position setting), and physically-aimed UI centres on the panel centre, not the viewport.
5. **Waking lands on the switcher** (sidebar focus) except for events that want to show a specific window
   (ringing timer, terminal bell).
6. **Glanceboard is a sleep-time surface**, never interfering with the regular UI; double-tap always reaches the
   real UI.
7. **Gesture hints** are shown in the UI using the glyph vocabulary so the two context-menu gestures are
   discoverable.
8. **Every screen tolerates the user's font size** (row heights and pitches derive from font metrics).

---

## 9. Design weaknesses / opportunities for a rebuild

| # | Weakness in the reference | Opportunity for the Kotlin rebuild |
|---|---|---|
| 1 | `DashboardController` is a 2 900-line god object mixing connection lifecycle, input pre-routing, surfaces, app launching, lock/wear, brightness, EvenHub suspend, phone mirror, text-editor sync, watch remote (`app/g2/dashboard-controller.ts`). | Split into services: `DisplayPowerManager` (screen/wake/fades/suspend), `InputRouter`, `WindowManager` (registry, focus, surfaces), `AppLauncher`, `LockService`, `PhoneTextInputBridge`, `GlanceService`, each with a small interface and tests. |
| 2 | Two hosting models with different APIs: in-process `LayerStack` apps vs Web-Worker apps that re-implement message loops, fingerprint dedupe, foreground inference, menus (`WindowMenu`) and a 13 + 25-message JSON protocol with ready-queue and idle/shutdown handshakes (`app/ui/shell/worker-window.ts`). | One app API. Run each app on its own coroutine dispatcher / single-thread executor for isolation; enforce a watchdog on input handlers instead of process boundaries. The system menu remains shell-owned so a stuck app is always escapable. |
| 3 | Duplicated list logic: calendar and notifications have identical `scrollForSelected` centering code; the launcher, file icons and sidebar hand-roll grids and scroll (`app/apps/calendar/calendar.ts:166-176`, `app/ui/notifications.ts:392-403`). | A single List/Grid component (variable row heights, optional two-level row/item mode, header rows, "minimal" vs "centred" scroll policy, animated highlight/scroll/bounce). |
| 4 | Gesture model has many special cases: long-press vs tap-then-hold, `claimsLongPress` + 4 s escape timer, `holdToTalk` inversion, `system-menu-opened` pseudo-event, switching between the two open menus, synthetic long-press+release from the watch, a vestigial push-to-talk path in the voice dialog (`app/ui/shell/shell.ts:795-871`). | Model input as an explicit state machine per focus target. Consider one context menu per window with a fixed "System" section appended (Focus switcher, Voice, Brightness, Close), keeping long-press as a shell-reserved escape only. Remove push-to-talk remnants or give it a dedicated gesture. |
| 5 | Settings are declared ad hoc across modules with stringly-typed keys; JSON stored inside strings with a hard-coded "structured keys" list in the storage layer; transient drafts (new terminal connection, navigate name/address, token name) are stored as settings just to reach the phone editor; `voice.enabled` is dead. | Typed settings registry (e.g. Proto DataStore) with one schema; a separate `TextInputRequest` channel for phone/voice text entry; per-app namespaced state. |
| 6 | Three different "enter text" UIs: `EditTextSettingLayer`, the keyboard dialog, and worker-painted "type on the phone" pages (Navigate, Terminal, Developer, Roam). | One shell-owned text-entry overlay (sources: phone keyboard, dictation, CLI) returning the text to the requester. |
| 7 | Many hard-coded coordinates despite user-scalable fonts (music `LIST_TOP 136`, weather `FORECAST_TOP 124`, input dialog fixed 16-px line pitch, calendar/notification title offsets). Large fonts can overflow (`app/apps/music/music-app.ts:19-32`, `app/ui/shell/input-dialog.ts:101-108`). | A small layout toolkit (rows/columns/stack with font-metric units) so every screen reflows. |
| 8 | Renderer constraints leak into app code: 0 vs 1 black on the colour-keyed shell, "glyphs always above raster of the same image" (forcing extra layers such as `SettingsDescriptionOverlayLayer` and titles that scroll with lists), 64 KiB per-resource caps on menu height, scroll-strip preconditions, stale-data follow-up renders (`app/ui/layers.ts:63-66`, `app/ui/menu.ts:281-285`). | Give apps a normal retained scene graph (z-ordered nodes including text); let the compositor/transport handle quantisation, caching and resource limits. |
| 9 | Geometry is a matrix of 3 height modes × 3 display modes × 5 positions × per-app overrides with special meanings ("default" for terminal); the sidebar and overlays stay in the min band while windows can be taller, so overlays and the voice dialog (which even overlaps the sidebar) look misplaced in Tall/Full modes. | A single calibrated *safe area* plus an optional per-app "full screen" flag; overlays positioned relative to the safe area or the foreground window consistently. |
| 10 | Screen/focus state is spread across many flags (`screenOn`, `focus`, overlay stack, `activeVoiceLayer`, `activeKeyboardLayer`, `assistantLayer`, `voiceDialogPending`, escape timer, glance state, lock, EvenHub suspended). | Sealed-class state machines: `DisplayState {Off, Glance, On(focus, overlays), Locked}`. |
| 11 | Worker windows always advertise `receiveTextInput`, so the voice dialog offers "Type Into App" for games that ignore it (`app/ui/shell/worker-window.ts:494-496`). | Capability flags declared by the app. |
| 12 | Notifications: no text reply (RemoteInput actions fire empty), no popup timeout, multiple popups stack as independent modals, popups capture input without any indication of what is behind, filter affects popups only. | A popup queue with auto-dismiss and "+N more", voice reply for RemoteInput actions (fits the existing voice dialog), grouping by conversation. |
| 13 | Battery-block rendering duplicated between top bar and the System-card widget; per-app tray icons are raw bitmaps shipped as JSON pixel arrays from workers (`app/ui/shell/chrome-layer.ts:384-509`, `app/apps/glanceboard/widgets/system-card.ts:131-206`). | Shared status components; tray items as structured (icon + short text) models rendered by the shell. |
| 14 | The Glanceboard is split between a controller-owned host, an app that doubles as a provider, and widgets that re-implement app views (music, calendar, compass, nightscout, terminal via a worker-state side channel). | A "sleep screen" system service where apps register widget providers; widgets share view components with their apps. |
| 15 | Scrolling the sidebar switches the foreground window at every step, each costing a full window render + transmission over a slow link. | Keep the live-preview UX but debounce the foreground switch (e.g. 150–250 ms dwell) or show a lightweight preview. |
| 16 | Latency instrumentation (frame ids whose ownership passes through every API) is threaded through app code (`frameId` params everywhere). | Use a tracing library with context propagation; keep it out of app signatures. |
| 17 | Open-app persistence is a dev convenience (fresh relaunch, primary window only) (`app/ui/shell/open-apps-persistence.ts:1-15`). | Proper state restoration via per-app saved-state bundles. |
| 18 | Launcher sorts apps and folders alphabetically together with a default "Prototypes" folder; no favourites/MRU; long lists need many scroll steps despite two-level navigation. | Consider pinned favourites / recents row; keep the row→item two-level scheme. |
| 19 | Weather uses the US National Weather Service only. | Pluggable provider (e.g. Open-Meteo) for non-US users. |
| 20 | iOS forks (`global.isIOS`) are interleaved in shared UI code. | Android-only rebuild can drop them. |

---

## 10. Open questions

1. **Physical direction of scroll:** which ring/temple swipe direction produces `SCROLL_TOP_EVENT` ("scroll-up")
   vs `SCROLL_BOTTOM_EVENT`? The code only uses the event ids (`app/ui/shell/shell.ts:1582-1585`).
2. **Push-to-talk:** is the hold-to-dictate voice dialog still reachable anywhere (long-press now opens the system
   menu)? Only AI Chat uses hold-to-talk today.
3. **Multiple notifications:** is stacking several notification modals intended, and should a popup auto-dismiss?
4. **Default font metrics:** the exact line height of Roboto Light 14 px from the native renderer (≈17 px assumed)
   drives every row height; confirm on device.
5. **Transport coupling:** must the rebuild keep the glasses-side display-list VM, texture cache and colour-keyed
   shell-scene protocol (custom firmware), or can it send plain frames? This decides whether animations and depth
   effects are feasible (see transport spec).
6. **Stock double-tap handling:** outside an EvenHub page the stock firmware consumes double-tap and only reports
   `display-wake`; how the rebuild's session keeps an "EvenHub page" alive (and the 5 s suspend policy) belongs to the
   BLE spec but affects wake latency and gesture availability.
7. **Head-tilt:** the head-up angle/trigger configuration lives in firmware; is it user-configurable in scope?
8. **Scope of EvenHub compatibility** (store, WebView apps, tall canvas) for the rebuild.
9. **Worker choice rationale:** no benchmark or note explains why Roam and Navigate are workers while heavier in-process
   apps (Calculator, Microphones) are not.
10. **Lock semantics:** lock requires both "glasses off-head" (wear sensor, custom firmware) and "phone locked"; is
    that the desired policy (e.g. no PIN/gesture unlock on the glasses themselves)?
11. **Charging / disconnected states:** frames are discarded while charging; windows persist across reconnects and
    their surfaces are reconfigured on connect — confirm expected UX when the glasses reconnect mid-session.
12. **Notification filter scope:** muted sources still appear in the top-bar icons and the Notifications list — intended?
