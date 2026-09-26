# Spec 07 — Voice assistant, LLM integration, EvenHub compatibility, remote input, network integrations

Status: reverse-engineered specification for an independent GPLv3 re-implementation (native Android, Kotlin).
Source basis: Faceclaw checkout at commit `a6291cf` ("Fix missing rerender when EvenHub apps finish downloading").

**Citation convention.** `path:line` is relative to the Faceclaw checkout root (`/home/user/jimrandomh/faceclaw`).
`g2kit:path:line` is relative to `/home/user/refs/g2-kit-unofficial` (MIT). Line numbers are for that commit;
ranges are approximate. Where the code only *guesses* at a remote contract, this spec says so.

**Terminology.**

| Term | Meaning |
|---|---|
| Shell | Faceclaw's glasses-side window manager / UI stack (`app/ui/shell/shell.ts`). |
| Layer | A modal/overlay UI element pushed onto the shell's layer stack (paints over what is below). |
| CFW | Faceclaw's custom glasses firmware patch set (g2flash). Several features here need it. |
| Lens | The 640×480 4-bit-gray glasses display (`app/graphics/image.ts:7-8`). |
| EvenHub | Even Realities' third-party app platform: web apps talking to glasses through the phone app. |
| Isolate | A NativeScript JS thread (main isolate = shell; app workers = separate isolates). |
| REPLACE semantics | A transcript event carries the complete best text of the current utterance, not a delta. |

---

## 0. Scope and reading guide

| Id | Subsystem | Main sources |
|---|---|---|
| A | Voice input pipeline: wakeword, capture, DSP, endpointing, on-device + cloud STT, glasses voice UI | `native/kotlin/shared/.../audio/*`, `.../VoiceEndpointDetector.kt`, `App_Resources/.../FaceclawVoiceController.kt`, `AndroidSpeechEngines.kt`, `app/native/{voice-control,asr-model,cloud-stt,reconnecting-stt,openai-stt,elevenlabs-stt,soniox-stt,speech-pause,transcript-format}.ts`, `app/ui/shell/{voice-input,assistant,voice-activity,input-dialog}.ts`, `notes/voice-assistant-design.md` |
| B | LLM assistant: sessions, providers, agent loop, tools, persistence, external agent bridge (OpenClaw) + MCP, local Qwen | `app/assistant/*`, `app/native/{anthropic,openai,llama}.ts`, `app/prompts.ts`, `App_Resources/.../FaceclawLlamaRunner.kt`, `App_Resources/Android/src/main/native/llama/*` |
| C | EvenHub app compatibility: package format, WebView host, JS bridge, container rendering, store client | `app/apps/evenhub/*`, `App_Resources/.../FaceclawEvenHub*.kt`, `native/.../evenhub/EvenHubAssetServer.kt`, `faceclaw-extensions/` |
| D | Remote input (local TCP input API + CLI) and terminal mirroring (g2mirror client) | `app/remote/*`, `app/native/{remote-input,g2mirror-client}.ts`, `native/.../net/RemoteInputSession.kt`, `App_Resources/.../FaceclawRemoteInput.kt`, `scripts/faceclaw-input.{cjs,md}` |
| E | Weather (NWS), Mapbox, Nightscout, calendar | `app/native/{weather,mapbox,nightscout-bridge,calendar}.ts`, `App_Resources/.../FaceclawCalendarProvider.kt` |

Sections 8 and 9 collect design weaknesses and open questions. Appendix A lists existing tests that pin behavior.

### 0.1 Component map

```
 G2 glasses (CFW)                               Phone
 ─────────────────                              ───────────────────────────────────────────────────────────
 "Hey Even" classifier ──sid 0x07 notify──►  GlassesSessionCore ─► RawInputEvent{kind:"even-ai"}
                                               └► dashboard wake barrier ─► shell.receiveInput({type:"wakeword"})
                                                    └► VoiceInputLayer (hands-free) ──Send──► AssistantSession
 Mic: 205-byte LC3 packets / 50 ms                                                      │
   ──render-notify characteristic──►  VoiceCaptureSession (Kotlin worker thread)         │
                                        LC3 decode ► beam gate ► noise suppress          │
                                        ► endpoint detector ► PCM out ► on-device ASR     │
                                        (sherpa-onnx Moonshine / Whisper)                │
                                      JS FaceclawVoiceControlBridge ─► cloud STT WS      │
                                        (OpenAI realtime / ElevenLabs / Soniox)          ▼
                                                              ┌──────── AssistantSession ────────┐
                                                              │ direct: DirectAssistantBackend    │
                                                              │   Anthropic Messages (SSE)        │
                                                              │   OpenAI Responses (SSE)          │
                                                              │   local Qwen3-4B (llama.cpp JNI)  │
                                                              │ external: AssistantBridgeClient   │
                                                              │   ws://host:8790 (OpenClaw plugin)│
                                                              │   + phone-side MCP server         │
                                                              └──────────────┬───────────────────┘
                                                                     ToolRegistry (system + app tools)
 EvenHub app (HTML/JS in a hidden WebView) ◄─JS bridge─► EvenHubSession ─► compositor ─► glasses window
 Remote input: TCP 127.0.0.1/tailnet :8791 ─► token auth ─► shell (gesture / text / assistant)
 Terminal app ─► G2MirrorClient ─► ws(s)://host:8737 g2mirror server
```

### 0.2 End-to-end walkthrough: "Hey Even … set a five minute timer"

1. Glasses classifier fires → right arm sends sid `0x07` `EvenAIDataPackage{commandId:1, ctrl{status:1}}` (§A.2). CFW, holding the wake lease, keeps the stock Even AI app from launching.
2. Phone decodes `even-ai` → if the display is off, the dashboard pre-wakes the shell and runs the wake barrier (screen on, resume EvenHub session, unblank, await ready).
3. Shell maps it to `{type:"wakeword"}`; `voice.wakeWordAction = voice-input` → `VoiceInputLayer(handsFree, default "Send to Assistant")` pushed; `voiceActivity = true` (EvenHub apps lose the mic).
4. `startVoiceCapture(endpointing=true)` → permission check → `voiceControlBridge.startPushToTalk(options)` → native `start("onboard"|"cloud")` → EvenHub Cmd 15 mic enable, wait ACK → LC3 packets stream (50 ms each).
5. Per chunk: decode → (beam) → (suppress) → endpoint detector → PCM to JS (cloud) and/or on-device partial re-decodes every 700 ms; the dialog shows "Voice ●" and REPLACE-semantics text.
6. ≥900 ms of silence after speech → `onSpeechEnd` → `endCapture()`: mic disabled, final transcript ("Set a five minute timer.") arrives; menu shows "Send to Assistant / Type Into App / Continue / Discard" — or, with skip-confirmation, it auto-sends.
7. `shell.sendToAssistant(text)` → `AssistantLayer` pushed ("Assistant ●", "Thinking...") → `AssistantSession.sendUtterance(text, ctx)`.
8. Direct mode: provider request with base prompt + context and ~all live tools (API names like `timer_set`) → model streams a `tool_use timer_set{minutes:5}` → status "→ timer.set" → registry runs the handler (starts the timer, brings the Timers window forward) → `tool_result` "Started a 5 min timer (timer 1), finishing at 3:20 PM." (format per `app/apps/timer/timer-model.ts:290-299`) → second request → model streams "Timer set for five minutes." → `onTurnDone`.
9. Overlay shows the reply with a Follow-up / Done menu; history is persisted to `assistant.conversations`; screen timeout resumes.

(External mode replaces step 8 with `chat utterance` → `text-delta`/`tool-activity`/`turn-done` frames while the agent calls `tools/call timer.set` over the MCP channel.)

iOS-specific paths (Apple Speech dictation, iOS WebView host, KMP iOS actuals) are out of scope for this Android spec.

---

## A. Voice input pipeline

### A.1 Entry points

| Entry point | Trigger | Resulting UI / options | Source |
|---|---|---|---|
| Wakeword "Hey Even" | sid 0x07 `EVEN_AI_WAKE_UP` from glasses (§A.2) | `VoiceInputLayer` with `handsFree=true`, default target "assistant", `autoSend` if the skip-confirmation setting is on | `app/ui/shell/shell.ts:758-776`, `:1039-1079` |
| Wakeword while the assistant overlay is open | same | Follow-up capture (hands-free) whose only target is "Send" into the current conversation | `app/ui/shell/shell.ts:768-770`, `:1337-1365` |
| System menu → "Voice input" | long-press system menu | `VoiceInputLayer` with `finishOnClick=true`, default target "Type Into App" | `app/ui/shell/shell.ts:1460-1468`, `:1123-1131` |
| Worker app asks for voice input | worker message `start-voice-input` | same as system menu | `app/ui/shell/worker-window.ts:326-331` |
| Phone "mic" button | phone UI injects a synthetic wakeword | same as wakeword | `app/phone-ui/main-view-model.ts:1077-1078` |
| Phone keyboard button | typed twin of voice input | `KeyboardInputLayer` (same send targets; assistant highlighted) | `app/ui/shell/shell.ts:1142-1186`, `app/ui/shell/keyboard-input.ts` |
| AI Chat window hold-to-talk | long-press in the AI Chat window | `VoiceDraft` (no dialog; release sends) | `app/apps/ai-chat/ai-chat-app.ts:103-109`, `app/apps/ai-chat/voice-draft.ts` |
| Assistant overlay "Follow-up" | menu click | follow-up capture, click ends utterance | `app/ui/shell/assistant.ts:109-111`, `shell.ts:1337` |
| Wear OS watch | watch's own speech recognizer → text | `shell.sendToAssistant(text)` | `app/g2/wear-remote.ts:335` |
| Remote input `assistant` action | TCP API (§D.1) | `shell.sendToAssistant(text)` | `app/g2/dashboard-controller.ts:423-431` |

Master switch `voice.enabled` (default true) disables voice dialogs (`app/ui/dashboard-settings.ts:520-526`; checked as `voiceInputEnabled` in `shell.ts:1020`, `:1124`).

### A.2 Wakeword ("Hey Even") — how the glasses signal it

* **Detection happens on the glasses.** The stock firmware's on-device classifier fires; the phone does no keyword spotting. An older on-phone sherpa KWS was retired; its files (`filesDir/faceclaw-voice`) are deleted on sight at the start of every capture session (`App_Resources/Android/src/main/java/com/faceclaw/app/FaceclawVoiceController.kt:26-29`, `:278-284`).
* **Wire message.** Service id `0x07` (Even AI) with protobuf `EvenAIDataPackage`:
  `field 1 commandId` (varint) must equal `1` (CTRL); `field 3 ctrl` (message `EvenAIControl`) whose `field 1 status` is
  `1 = EVEN_AI_WAKE_UP` (wakeword), `2 = EVEN_AI_ENTER` (user manually opened the stock assistant), `3 = EVEN_AI_EXIT`
  (`native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/g2protocol/G2Event.kt:16-29`,
  `.../g2protocol/BleProtocol.kt:113-129`; schema in `g2kit:ble/gen/even_ai_pb.ts:293-330`, enum at `:566-590`).
* Events are only decoded from **right-arm** notifications whose envelope flag is `0x01` or `0x06` (notify)
  (`.../g2protocol/session/GlassesSessionCore.kt:1499-1503`).
* Mapping: `G2Event("even-ai", status)` → `RawInputEvent{kind:"even-ai", eventType:status}` → `InputEvent{type:"wakeword"}` **only for status 1**; ENTER/EXIT are ignored (`app/ui/shell/shell.ts:1608-1614`, `app/g2/events.ts:21-40`).
* **CFW wake-takeover lease.** Without intervention the stock firmware would launch its own Even AI foreground app, displacing Faceclaw's EvenHub page. The CFW suppresses that while the phone holds a "wake lease". Lease control message: sid `0x09` (UI settings), request flag `0x20`, magic `0` (fire-and-forget), protobuf `{1: 1, 2: 0, 101: bytes}` where the 6-byte record is `['F'(70), 'C'(67), version=1, op, nonce_lo, nonce_hi]`; ops `ACQUIRE=1, RELEASE=2, CLAIM=3, READY=4` (framebuffer lease `5/6`, wear query `7`). It is sent to both arms; CFW consumes field 101 before the stock decoder discards it (`.../g2protocol/BleProtocol.kt:57-111`, `:519-541`; `.../g2protocol/MessageBuilder.kt:235-247`). Desired lease state = CFW confirmed AND (`developer.suspendEvenHubWhenScreenOff` OR `voice.wakeWordAction != "off"`) (`app/g2/dashboard-controller.ts:763-771`). Full lease/nonce semantics belong to the BLE/CFW spec.
* **Screen-off handling.** The wakeword is processed even with the display asleep. The dashboard controller pre-wakes the shell and runs the *wake barrier* before routing: `setG2ScreenOn(true)` → `resumeEvenHubSession()` → `setScreenBlanked(false)` → `awaitEvenHubSessionReady(timeout)` (`app/g2/dashboard-controller.ts:2164-2181`, `:717-761`).
* **Shell routing** (`app/ui/shell/shell.ts:758-776`):
  1. If the foreground window is itself capturing voice (AI Chat draft) → consume, do nothing.
  2. `voice.wakeWordAction` (`app/ui/dashboard-settings.ts:607-621`): `"off"` → ignore; `"turn-screen-on"` → wake only; `"voice-input"` (default) → wake, and if no voice or keyboard dialog is open: if the assistant overlay is up → hands-free follow-up; else open the voice dialog hands-free with the "Send to Assistant" row highlighted.

### A.3 Capture control layer (`FaceclawVoiceControlBridge`, `app/native/voice-control.ts`)

A process-wide singleton on the main isolate that owns the single glasses mic.

* **Holders.** `"ptt"` (dialogs, AI Chat) and `"continuous"` (Transcribe app). The first holder starts capture and picks provider/options; later holders share the running stream (transcripts broadcast to all listeners); the mic stops when the last holder releases (`voice-control.ts:114-122`, `:268-336`, `:378-405`).
* **"Is the mic live?"** is answered by the native controller (`isCapturing()`), not by bookkeeping, because the mic enable lives in the glasses' EvenHub session and dies silently on disconnect/case/suspend (`voice-control.ts:471-474`; `native/.../audio/VoiceCaptureSession.kt:292-312`).
* **Raw PCM tap** for EvenHub mic apps: controller runs in decode-only `"cloud"` mode with no cloud client; noise suppression, beam filter, recording, endpointing and speaker verification are forced off ("raw means raw"); any STT holder pre-empts it (`voice-control.ts:230-266`, `:272`).
* **Session loss / resume.** `handleSessionEnded()` parks holders in `suspendedHolders` (status "Waiting for the glasses...") and tears down; when a fresh session is ready, `resumeCapture()` re-acquires with the remembered endpointing flag and without re-asking permission (`voice-control.ts:422-463`; callers `app/g2/dashboard-controller.ts:879`, `:1402`, `:1759` (session ended) and `:1968-1975` (`resumeVoiceCapture`)).
* **Provider choice per capture** (`createCloudClient`, `voice-control.ts:343-376`): settings value `onboard` (Moonshine) / `onboard-whisper` → on-device; `elevenlabs` / `soniox` / `whisper` (= OpenAI cloud; legacy persisted name) → cloud, each wrapped in `ReconnectingSttClient`. A cloud provider with an empty key falls back to on-device **Moonshine** with status "No X key set; using on-device voice.".
* **Release ordering** for a push-to-talk commit: stop the native controller first (flushes final PCM), then `cloudClient.finish()`; a non-commit release (continuous stop) calls `cloudClient.stop()` (`voice-control.ts:395-404`). `stopPushToTalk()` returns a promise that resolves on the native `onStopped(captureId)` callback, but only for an on-device capture where "ptt" is the sole holder (`:195-202`, `:333-335`, `:504-507`).
* **Permission.** Android `RECORD_AUDIO` is the consent gate even though audio comes from the glasses. Before prompting, status becomes "Waiting for microphone permission..." with detail "Allow microphone access in the prompt on your phone."; denial → "Microphone access denied." (`voice-control.ts:174-187`; `app/g2/dashboard-controller.ts:1994-2025`).
* **Capture options** assembled per capture (`app/g2/dashboard-controller.ts:1945-1962`): communicator (null ⇒ preview mode ⇒ phone mic), provider, three STT keys, `saveRecording`, `speakerVerification` (§A.11), `noiseSuppression` and `beamFilter` from the Microphones app config, `endpointing` (true for hands-free).

### A.4 Glasses microphone transport

* **Enable/disable.** EvenHub envelope on sid `0xE0`, flag `0x20`: `{1: Cmd=15 (APP_REQUEST_AUDIO_CTR_PACKET), 2: magic, 18: AudioCtrCmd{1: AudoFuncEn = 0|1}}`; the glasses answer with Cmd 16 carrying `AudioResCmd` (field 19) (`.../g2protocol/BleProtocol.kt:137`, `:394-402`; `.../MessageBuilder.kt:115-127`; `g2kit:ble/gen/EvenHub_pb.ts:105-112`, `:238-245`, `:1142-1149`). The phone only enables when the EvenHub display path is ready (session up, fixed layout created) and blocks until the ACK (`GlassesSessionCore.kt:431-449`); disable likewise (`:451-469`).
* **Packets** arrive as notifications on characteristic `00002760-08c2-11e1-9073-0e8ac72e6402` ("render notify") and are tagged with the arm they came from; `"L"` is expected, other arms are counted as `wrongArm` but still processed (`BleProtocol.kt:23`; `GlassesSessionCore.kt:1589-1605`; `VoiceCaptureSession.kt:743-763`).
* **Packet layout (205 bytes)** per `native/.../audio/Lc3PacketFramer.kt:22-38`:

| Bytes | Content |
|---|---|
| 0–199 | 5 LC3 frames × 40 bytes; each frame = 10 ms of 16 kHz mono ⇒ 800 samples / 50 ms per packet |
| 200–201 | signed int16 LE: firmware signal-strength ratio (SSR), computed on raw stereo before downmix |
| 202–203 | signed int16 LE: direction of arrival in degrees (0 = straight ahead, positive = wearer's right) |
| 204 | uint8 packet counter |

  > Conflict: `g2kit:ble/docs/audio.md:36-53` describes a 2-byte header + 203-byte LC3 frame per 20 ms. Faceclaw's layout cites the firmware source (`service_audio.c`) and is the implementation that works with the CFW; treat the g2-kit description as stale (see Open questions).
* **Duplicate/late filter.** Both arms may relay the same packet: a counter gap of 0 or ≥128 (mod 256) is a duplicate/late copy and is dropped before touching decoder state; `gap-1` is added to `missingPackets` (`Lc3PacketFramer.kt:65-92`).
* **Decoder.** liblc3 v1.1.3 compiled into `libfaceclaw_lc3.so` (JNI), created with `frameUs=10000, sampleRate=16000` (`App_Resources/.../FaceclawLc3Decoder.kt:12-35`; `App_Resources/Android/app.gradle:25`, `:96-140`).
* **Queue.** BLE thread → `DroppingPacketQueue` capacity 80 packets (≈4 s), drop-oldest with a counter; the worker polls with a 250 ms wait so it can observe `stop()` (`VoiceCaptureSession.kt:62`, `:765-771`; `native/.../audio/VoicePorts.kt:90-137`). Inter-arrival > 90 ms counts as "late". Diagnostics are logged (never shown on glasses) every 5 s (`VoiceCaptureSession.kt:797-821`).
* **Phone mic (preview mode, no glasses).** `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)`, blocking reads of 800 samples (50 ms) to match the glasses cadence; no frame metadata (`App_Resources/.../AndroidSpeechEngines.kt:102-146`; `FaceclawVoiceController.kt:48`).

### A.5 Per-chunk processing order (`VoiceCaptureSession`)

One worker thread per capture (name "FaceclawVoiceController"); all listener callbacks are posted to the Android main looper (`FaceclawVoiceController.kt:84-89`). For each decoded chunk (`VoiceCaptureSession.kt:451-512`):

1. **Beam gate** (glasses only): if enabled, `ssr > 0` and the DoA is outside `center ± halfWidth` (half-width clamped to 5..180°, wrap-safe) → drop the packet (frame metadata still emitted) (`:465-476`, `:229-241`).
2. **Noise suppression** if enabled; on any exception it disables itself and passes audio through (`:243-258`, §A.6).
3. Append to the **recording** buffer (developer option; saved as WAV at session end to `getExternalFilesDir(null)/voice-recordings/voice-yyyyMMdd-HHmmss-SSS.wav`) (`FaceclawVoiceController.kt:235-255`).
4. Append to the **speaker-verification** buffer (≤10 s) (§A.11).
5. **Endpoint detector** (hands-free only) → `onSpeechEnd` once (§A.7).
6. Emit **PCM** (S16LE bytes) to the JS listener — in every mode, so levels/recording/cloud/raw tap all work.
7. Emit **frame metadata** `(angleDegrees, ssr)` (glasses only).
8. If mode is ONBOARD: feed the on-device recognizer (§A.8).

Session end (`:348-419`): speaker-verification verdict is emitted **before** the final transcript (so the bridge can suppress it), then (ONBOARD) one final full-utterance transcript, then `onStopped(captureId)`. `stop()` joins the worker for up to 1.5 s unless called from the worker itself (`:319-335`).

### A.6 Noise suppressor (`native/.../audio/FaceclawNoiseSuppressor.kt`)

Streaming spectral suppressor, platform-free, single-owner.

| Parameter | Value | Line |
|---|---|---|
| FFT size / hop | 256 (16 ms) / 128 (8 ms, 50 % overlap) | `:13-15` |
| Window | sqrt-Hann: `sin(π (i+0.5)/N)` applied at analysis and synthesis | `:122-131` |
| Over-subtraction | 1.6 | `:17` |
| Gain floor | 0.15 (≈ −16 dB) | `:20` |
| Gain smoothing | `g = 0.6·g_prev + 0.4·g_new` per bin | `:22`, `:226` |
| Noise warm-up | first 12 frames (~100 ms): noise = running mean of power | `:25`, `:210-213` |
| Noise tracking | if `power < noise`: `noise += 0.2·(power − noise)` (fast down); else `noise = min(power, noise·1.006 + 1e-10)` (slow up) | `:215-219` |
| Gain | `max(0.15, (power − 1.6·noise)/power)` (0 if negative before floor) | `:221-225` |
| Latency | one hop; output may be one hop shorter/longer per call | `:148-152` |

### A.7 Endpointing and silence rules

**Hands-free end-of-utterance — `VoiceEndpointDetector`** (`native/.../VoiceEndpointDetector.kt:9-66`), driven by the 16 kHz sample clock (not BLE timing), RMS in PCM16 units per chunk:

| Phase | Rule |
|---|---|
| Calibration | While elapsed ≤ 300 ms: average the chunk RMS values (noise estimate). |
| Threshold | `threshold = max(noiseAvg, 220)` (fixed once, after calibration). |
| Waiting for speech | Speech starts when `rms ≥ 3.0 × threshold`. If no speech by 6000 ms elapsed → fire (end). |
| In speech | `rms < 1.8 × threshold` accumulates silence; ≥ 900 ms of continuous silence → fire. Louder chunk resets silence. |
| Hard cap | 30 000 ms elapsed after speech started → fire. |
| One-shot | fires once; `reset()` per session. |

Endpointing runs on the Kotlin side for **all** providers (on-device and cloud) because PCM processing is common (`VoiceCaptureSession.kt:500-502`). A click also ends a hands-free capture early (`app/ui/shell/voice-input.ts:153-155`, `:275-277`).

**Continuous-capture pauses — `SpeechPauseDetector`** (`app/native/speech-pause.ts:1-44`), used only while the Transcribe app holds `"continuous"`:

* noise floor starts at 220; threshold = floor × 3 when not speaking, × 1.8 when speaking;
* loud chunk → speaking; quiet chunk → floor = `max(220, 0.98·floor + 0.02·rms)`; if speaking, accumulate quiet samples; ≥ 1500 ms (`TRANSCRIPT_PAUSE_MS`) → return true once and reset.
* Uses: on-device → emits `onSpeechPause` for Transcribe's paragraphing (`voice-control.ts:511-513`); cloud → `ReconnectingSttClient` calls `commitSegment()` (`app/native/reconnecting-stt.ts:81`).

**Cloud server VAD is disabled**: push-to-talk / endpointing define utterance boundaries (OpenAI `turn_detection:null`, ElevenLabs `commit_strategy=manual`, Soniox finalized by an empty frame).

**Segment cuts** (§A.8) are *not* endpoints; they only keep on-device decode windows below model limits.

### A.7a Speech-to-text options at a glance

| Setting value | Engine | Runs | Streaming? | Live partials | Key needed | Language |
|---|---|---|---|---|---|---|
| `onboard` (default) | Moonshine base EN (quantized) via sherpa-onnx | phone CPU | no — offline recognizer re-run on a growing ≤8 s buffer | yes (every 700 ms) | no (≈141 MB download) | English |
| `onboard-whisper` | Whisper base.en int8 via sherpa-onnx | phone CPU | no — decoded at 8 s segment commits and at the end | no | no (≈161 MB download) | English |
| `whisper` (label "OpenAI (Whisper)") | OpenAI Realtime transcription, model `gpt-realtime-whisper` | cloud | yes — WebSocket, 24 kHz PCM, server deltas | yes | OpenAI | model default |
| `elevenlabs` | ElevenLabs realtime `scribe_v2_realtime` | cloud | yes — WebSocket, 16 kHz PCM base64 | yes | ElevenLabs (STT permission) | model default |
| `soniox` | Soniox realtime `stt-rt-v5` (+ speaker diarization) | cloud | yes — WebSocket, binary 16 kHz PCM | yes (token-level) | Soniox | model default |

Setting source: `app/ui/dashboard-settings.ts:575-605`. None of the cloud clients sets a language; the on-device models are English-only.

### A.8 On-device speech-to-text

* **Engine:** sherpa-onnx 1.13.0 `OfflineRecognizer` (prebuilt `libonnxruntime.so` + `libsherpa-onnx-jni.so` fetched from the sherpa-onnx GitHub release at build time; the `com.k2fsa.sherpa.onnx` Java classes are vendored) (`App_Resources/Android/app.gradle:24`, `:73-90`). Config: `numThreads=1`, feature dim 80, 16 kHz (`AndroidSpeechEngines.kt:25-55`).
  * Moonshine: `encoder_model.ort` + merged decoder `decoder_model_merged.ort` + `tokens.txt`.
  * Whisper: `base.en-encoder.int8.onnx` + `base.en-decoder.int8.onnx` + `base.en-tokens.txt`, `language="en"`, `task="transcribe"`.
  * One recognizer per capture session; each recognition creates/releases an `OfflineStream` (`:57-79`).
* **Transcript state machine** (`VoiceCaptureSession.kt:66-98`, `:535-637`):

| Constant | Value | Meaning |
|---|---|---|
| `TRANSCRIPT_DECODE_INTERVAL_MS` | 700 | Moonshine: re-decode the whole current segment this often (live partial) |
| `TRANSCRIPT_MIN_SAMPLES` | 16000/3 | no partial before ~333 ms of audio |
| `TRANSCRIPT_SEGMENT_MAX_SAMPLES` | 8 s | segment is committed when full (Moonshine v2 fails past ~9.1 s input) |
| `TRANSCRIPT_CUT_SEARCH_SAMPLES` | 2 s | commit cut searched in the last 2 s of the segment |
| `TRANSCRIPT_CUT_WINDOW_SAMPLES` | 30 ms | cut at the centre of the lowest-energy 30 ms window (sliding sum of squares) |
| `TRANSCRIPT_NORMALIZE_TARGET_PEAK` / `MAX_GAIN` | 0.9 / 30× | each decode window is peak-normalized (glasses PCM peaks ≈0.1 FS) |
| `WHISPER_SILENCE_PEAK_THRESHOLD` | 0.01 | Whisper: skip decode if pre-normalization peak is below this (anti-hallucination; documented as UNTESTED) |

  Algorithm: samples append to a fixed 8 s float buffer. On fill → `commitTranscriptSegment()`: cut at `quietestCutPoint` (`native/.../audio/AudioSegmentation.kt:20-50`), recognize the head, append to `committedTranscript` (fallback: last non-empty partial of this segment), shift the tail to the buffer start, emit committed text as a non-final event. Partials (Moonshine only) emit `join(committed, currentSegmentText)` with REPLACE semantics; an empty decode re-uses the previous segment text. Whisper emits nothing live (status stays "Listening..."), decoding only at commits and the end. The final (`isFinal=true`) event is emitted at stop.
* **Joining:** `joinTranscript(prefix, suffix)` inserts a space unless the suffix starts with one of ``.,!?;:%)]}`` (`VoiceCaptureSession.kt:104-114`).
* Missing model → status "Voice model not downloaded (see Settings > Voice)." and the capture ends; no automatic fallback (`:355-358`).

### A.9 On-device model catalogue and download

All model files download on demand through one resumable downloader (`App_Resources/.../FaceclawModelDownloader.kt`; policy in `native/.../net/ResumableDownload.kt:79-176`): write to `<dest>.part`; resume with `Range: bytes=<n>-` (HTTP 206 required, otherwise restart); SHA-256 computed incrementally, including re-hashing an existing partial prefix; size check against the pinned total; rename into place; progress callbacks at most every 500 ms; OkHttp connect 30 s / read 60 s. Cancel keeps the `.part` file. Multi-file models download files sequentially (`app/native/asr-model.ts:185-229`). UI: Settings rows showing "downloaded" / "NN% of X MB" / "not downloaded", with Download / Cancel / Delete actions (`app/ui/dashboard/settings-menus.ts:342-450`).

| Model | Use | Base URL | Files (bytes, sha256) | Local dir |
|---|---|---|---|---|
| Moonshine base EN, quantized (2026-02-27) | STT provider `onboard` | `https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/resolve/main/` | `decoder_model_merged.ort` 109 424 400 `d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d`; `encoder_model.ort` 31 326 816 `7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf`; `tokens.txt` 549 350 `2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049` (total 141 300 566) | `filesDir/faceclaw-voice-asr/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/` |
| Whisper base.en int8 | STT provider `onboard-whisper` | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/` | `base.en-encoder.int8.onnx` 29 120 534 `ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823`; `base.en-decoder.int8.onnx` 130 669 978 `f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41`; `base.en-tokens.txt` 835 554 `306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930` (total 160 626 066) | `filesDir/faceclaw-voice-asr/sherpa-onnx-whisper-base-en-int8/` |
| Qwen3-4B-Instruct-2507 Q4_K_M (GGUF) | local LLM (§B.7) | `https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true` | 2 497 281 120 bytes, `3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597` | `getExternalFilesDir("llm")` (fallback `filesDir`) |
| WeSpeaker EN VoxCeleb CAM++ | speaker verification (§A.11) | `https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/` | remote `wespeaker_en_voxceleb_CAM%2B%2B.onnx` → `speaker-embedding.onnx`, 29 292 684, `c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef` | Microphones app model dir |

Sources: `app/native/asr-model.ts:53-108`, `app/native/llama.ts:20-27`, `app/apps/microphones/mic-models.ts:40-54`, `FaceclawVoiceController.kt:24-45`. A model is "ready" when every file exists with non-zero length (`asr-model.ts:143-155`; `llama.ts:60-68`).

### A.10 Cloud speech-to-text

**Common contract** (`app/native/cloud-stt.ts:9-40`): `start()`, `acceptPcm(pcm16le@16k)`, `finish()` (end of utterance → final), optional `commitSegment()` (pause boundary, keep session), `stop()` (abandon). Events `{text, isFinal, transcribeText?, paragraphBreakAfter?}`; `text` is REPLACE semantics for the *current* uncommitted utterance; each final is appended by consumers. `transcribeText`/`paragraphBreakAfter` are presentation-only for Transcribe.

**Reconnecting wrapper** (`app/native/reconnecting-stt.ts`, every cloud provider is wrapped):

| Aspect | Behavior | Line |
|---|---|---|
| Pre-ready buffering | PCM queued until provider `onReady`, capped at 10 s (320 000 bytes), dropping the oldest; never replays audio already sent | `:6`, `:68-76` |
| Rejected send | a synchronous disconnect during `acceptPcm` re-queues that chunk | `:79-80` |
| Pause commits | when ready, not finishing, and a continuous holder exists: `SpeechPauseDetector` → `commitSegment()` | `:81` |
| Disconnect | bump generation (ignore late events), keep the latest partial as a *final* so words are not lost, then: message containing 401/402/403 → fatal error; finishing → stop; else retry | `:116-140` |
| Backoff | `min(1000·2^min(attempt,5), 30000)` ms (1,2,4,8,16,30 s); status "Connection lost. Reconnecting in Ns..."; attempt resets on any transcript | `:134-139`, `:47` |
| `finish()` | if a retry is pending → preserve partial and stop; else arm a 5 s stop timeout and call provider `finish()` now or on ready | `:84-95` |

**Provider protocols:**

| | OpenAI realtime transcription | ElevenLabs realtime | Soniox realtime |
|---|---|---|---|
| File | `app/native/openai-stt.ts` | `app/native/elevenlabs-stt.ts` | `app/native/soniox-stt.ts` |
| Endpoint | `wss://api.openai.com/v1/realtime?intent=transcription` (`:19`) | `wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&commit_strategy=manual` (`:15-18`, `:38`) | `wss://stt-rt.soniox.com/transcribe-websocket` (`:21`) |
| Auth | header `Authorization: Bearer <key>` (`:64-69`) | header `xi-api-key: <key>` (`:64`) | `api_key` field in first JSON text frame; no header (`:47-56`) |
| Model | `gpt-realtime-whisper` (`:20`) | `scribe_v2_realtime` (only accepted realtime model per comment `:16-18`) | `stt-rt-v5` (`:22`) |
| Session setup | on open send `{type:"session.update", session:{type:"transcription", audio:{input:{format:{type:"audio/pcm", rate:24000}, transcription:{model}, turn_detection:null}}}}` (`:212-227`) | none (query params) | `{api_key, model, audio_format:"pcm_s16le", sample_rate:16000, num_channels:1, enable_speaker_diarization:true}` |
| Audio framing | 16→24 kHz linear-interpolation upsampler (exact 2:3, phase kept across chunks, `:235-268`), base64 in `{type:"input_audio_buffer.append", audio}` | `{message_type:"input_audio_chunk", audio_base_64, commit:false, sample_rate:16000}` | raw binary WS frames of PCM16LE |
| End of utterance | `{type:"input_audio_buffer.commit"}` | same chunk message with `audio_base_64:""`, `commit:true` | empty text frame `""`; server then finalizes and sends `finished:true` |
| Pause commit | commit (marks paragraph break) | commit (marks paragraph break) | `{"type":"finalize"}` |
| Partials | `conversation.item.input_audio_transcription.delta {item_id, delta}` accumulated per item; partial text = non-final items joined by spaces | `partial_transcript {text}` | tokens with `is_final:false` (repeated/revised each message) appended for display only |
| Finals | `...completed {item_id, transcript}`; finals emitted strictly in audio order (map insertion order from `input_audio_buffer.committed`) | `committed_transcript {text}` (the `_with_timestamps` twin is ignored) | `is_final:true` tokens accumulate exactly once; `<end>`/`<fin>` marker tokens flush a final; `finished:true` flushes the last |
| Ready status | `session.created`/`session.updated` → "Listening (OpenAI)..." | `session_started` → "Listening (ElevenLabs)..." | on open → "Listening (Soniox)..." |
| Retryable errors | `error` with code `rate_limit_exceeded` or type `server_error`; socket close/failure | `rate_limited`; close/failure | `error_code` 429 or ≥500; `finished` while not finishing ("session ended; restarting") |
| Fatal errors | any other `error` | `error`, `auth_error`, `quota_exceeded`, `input_error` | other `error_code` |
| Auto-stop | after `finish()`, once every committed item completed | — | on `finished` when finishing |

All three queue audio/commits until the socket opens (`openai-stt.ts:30-31`, `elevenlabs-stt.ts:29-30`, `soniox-stt.ts:31-33`). Soniox paragraphs for Transcribe use `TimedTranscript`: a newline is inserted when the speaker label changes or the gap between tokens is ≥1500 ms (`app/native/transcript-format.ts:6-32`). Transport is OkHttp WebSocket with a 20 s ping interval; callbacks are delivered on the constructing thread's looper (`App_Resources/.../FaceclawWebSocket.kt:23-156`).

### A.11 Speaker verification ("My voice only")

Enabled by `microphones.wearer-commands-only` (default false) when a wearer voice-print is enrolled and the embedding model is downloaded (`app/apps/microphones/mic-settings.ts:123-129`; `app/apps/microphones/speakers.ts:300-312`). Per capture (`VoiceCaptureSession.kt:96-98`, `:674-710`): buffer up to 10 s of processed PCM; < 1 s → pass; else embed (sherpa speaker model, cached across sessions, `FaceclawVoiceController.kt:64-80`) and compare `dot(embedding, wearerCentroid) ≥ threshold` (0.8, `speakers.ts:19`). **Fails open** (model error / size mismatch ⇒ wearer). The verdict precedes the final transcript; if rejected, the bridge empties the final text and shows "Ignored — not your enrolled voice (match NN%)." (`voice-control.ts:528-553`). Cloud finals arriving later are also blanked.

### A.12 Status strings and the "listening" flag

The bridge exposes `{status, listening, detail}`; `listening = status.startsWith("Listening")` (`voice-control.ts:556-570`). Statuses in use: "Starting microphone...", "Loading transcription model...", "Listening...", "Listening (cloud)...", "Listening (OpenAI|ElevenLabs|Soniox)...", "Connecting to X...", "Voice control needs an active G2 connection.", "Could not start G2 microphone input.", "Could not start the phone microphone.", "Voice model not downloaded (see Settings > Voice).", "Waiting for the glasses...", "Connection lost. Reconnecting in Ns...", "Voice control failed: …", the permission and verification messages above. Statuses ending in "..." are progress; other statuses are treated as errors by the dialog (`app/ui/shell/voice-input.ts:171-175`).

### A.13 Glasses UI while listening / thinking / answering

**Shared dialog painter** (`app/ui/shell/input-dialog.ts`): solid box `x=40, w=560`, height `288−56=232`, top = window-band top + 28; filled with gray 1 (not 0, which is transparent on the shell surface), border gray 90. Row 1 title (gray 220), row 2 status (gray 130 or caller-supplied, truncated), body from y+56 in 16 px lines — **only the tail** of long text is shown (the last lines that fit), then either a bottom menu (one row per item) or a gesture hint (`:15-20`, `:82-118`).

**`VoiceInputLayer`** (`app/ui/shell/voice-input.ts`) — phases:

| Phase | Shows | Gestures | Exit |
|---|---|---|---|
| `capturing` | title "Voice ●" while capturing and bridge says listening, else "Voice"; status from bridge; body = `finalized + " " + live` or placeholder ("Listening..." only if actually listening, else bridge detail) | double-click → close (discard); click ends capture if hands-free or opened from a menu (`finishOnClick`) | end of capture (button release, click, endpoint) → `menu` |
| `menu` | send targets, then "Continue", then "Discard"; send rows dim when no text | scroll moves (wrapping); click selects; double-click closes | send → dialog dismissed and target invoked |
| `continuing` | placeholder "Say more, or describe an edit..." | click ends; double-click cancels ("Continuation cancelled") | → `refining` |
| `refining` | streamed merged text; status "Refining..." | double-click cancels ("Refinement cancelled") | → `menu` with merged text or original + error |

Details (`voice-input.ts`):
* Transcript accumulation: final events append to `finalizedText` (fallback to the last live text if the final is empty); partials replace `liveText` (`:469-495`).
* Ending capture (`:158-206`): phase → `menu`; status "Send, continue, or discard?" unless the status is an error; while the native stop is pending the status reads "Finishing transcription..." and send rows are inert. **Auto-send** (wakeword + setting `assistant.skipConfirmationAfterWakeword`, default false): status "Sending...", then send as soon as a final transcript arrives, or 1200 ms after the native stop resolves; if no text, fall back to the menu (`:166-170`, `:209-226`; `FOLLOWUP_FINALIZE_TIMEOUT_MS=1200` `:10`).
* **Send targets** (`app/ui/shell/shell.ts:1085-1111`): "Send to Assistant" when an assistant configuration resolves (§B.2); "Type Into App" when the foreground window implements `receiveTextInput`; at least one target always ("Type Into App"). Default highlight: "assistant" for wakeword, "app" for the menu entry (`:1039-1049`).
* **Continue / refine** (`:316-377`): record a follow-up utterance, then merge with an LLM: Anthropic Messages, model `claude-sonnet-5`, `effort:"low"`, system prompt "You edit dictated text…" (applies spoken edits or appends content; outputs only the final text) and user message `Original dictation:\n…\n\nFollow-up dictation:\n…` (`app/native/anthropic.ts:242-269`; `app/prompts.ts:44-54`). The row reads "Continue (Needs LLM API key)" and is inert without an **Anthropic** key, even if an OpenAI key or local model is configured (`voice-input.ts:232`, `:245-251`).
* Closing the layer (any path, including screen sleep) stops capture and cancels any refine (`:405-432`); the shell clears `voiceActivity` (`shell.ts:1055-1064`).

**`AssistantLayer`** overlay (`app/ui/shell/assistant.ts:32-127`) — same painter:

| Phase | Title | Status line | Body | Input |
|---|---|---|---|---|
| thinking | "Assistant ●" | "Thinking..." or "→ <canonical tool name>" while a tool runs | streamed reply text (tail) | hint "<double-click glyph> cancel"; double-click cancels (status "Cancelled") |
| done | "Assistant" | "" or "(no reply)" | full reply (tail only) | menu Follow-up / Done; double-click = Done |
| error | "Assistant" | error message (gray 200) | partial reply | same menu |

The shell owns the conversation and drives the layer (`app/ui/shell/shell.ts:1266-1335`): `sendToAssistant(text)` wakes the screen if needed, refuses with an alert if no configuration ("Configure the agent bridge host and token in Settings." / "Set an API key or download the on-phone model in Settings.") or if a turn is active; pushes the overlay once and reuses it for follow-ups. If the AI Chat window is foreground, no overlay is used (the chat window renders the conversation). The overlay's removal by any path (Done, screen sleep clearing the stack) **cancels the in-flight turn** but keeps history (`:1293-1299`; `shell.ts:615-625`).

**Alerts** (`glasses.show_alert` and assistant notices): `ShellAlertLayer` titled "Assistant", box `x=40, y=band+96, 560×96`, wrapped body gray 235, auto-dismiss after 6 s, click/double-click dismisses; wakes the screen (`shell.ts:246-300`, `:1380-1397`).

**AI Chat window** (`app/apps/ai-chat/ai-chat-app.ts`): full-window transcript ("You: …" gray 175, "AI: …" gray 245), follows the streaming tail unless scrolled (3-line steps), status/draft area and footer showing model and reasoning; hold-to-talk via `VoiceDraft` (release → finish; final or 1500 ms after stop → send) (`voice-draft.ts:82-116`); window menu: New session, Switch session, Model, Reasoning, Cancel response (disabled/limited in external mode: "Sessions and model managed by bridge") (`ai-chat-app.ts:113-145`).

**Screen timeout** is suspended while a voice or keyboard dialog is open, an assistant turn is in flight, or the foreground window is capturing voice (`shell.ts:640-655`).

**`voiceActivity`** (`app/ui/shell/voice-activity.ts`) is a global boolean "a voice modal is open", set by the voice dialogs and AI Chat drafts; the EvenHub mic router reads it so EvenHub apps receive no audio while the assistant listens (§C.10).

**Watch mirroring:** the shell emits `AssistantActivityEvent{phase: thinking|streaming|done|error|closed, text}` and alert texts to observers; the Wear OS bridge forwards them (`shell.ts:196-199`, `:1201-1217`; `app/g2/wear-remote.ts:174-176`, `:526`).

### A.14 Voice settings

| Key | Default | Values / meaning | Source |
|---|---|---|---|
| `voice.enabled` | true | master switch for wakeword + voice input | `app/ui/dashboard-settings.ts:520-526` |
| `voice.provider` | `onboard` | `onboard` (Moonshine), `onboard-whisper`, `elevenlabs`, `whisper` (= OpenAI cloud), `soniox`; cloud options disabled in the picker without a key | `:591-605` |
| `voice.wakeWordAction` | `voice-input` | `voice-input`, `off`, `turn-screen-on` | `:607-621` |
| `assistant.skipConfirmationAfterWakeword` | false | wakeword utterances auto-send to the assistant | `:631-637` |
| `developer.saveVoiceRecordings` | false | save each capture as WAV | `:623-629` |
| `voice.elevenLabsApiKey`, `voice.openAiApiKey`, `voice.sonioxApiKey` | "" | STT keys (OpenAI key also used for OpenAI LLMs) | `:698-728` |
| `microphones.wearer-commands-only` | false | speaker verification | `app/apps/microphones/mic-settings.ts:123-129` |

All settings live in one Android `SharedPreferences` file shared across isolates (`App_Resources/.../FaceclawSettings.kt:24-26`; `app/native/settings-store.ts`), in plaintext.

---

## B. LLM integration (assistant)

### B.1 Architecture

```
 shell (main isolate)
   AssistantConversations ── owns N Conversation records {id, model, reasoning, session|history}
        └ AssistantSession (one per record, created lazily)
             ├ kind "direct":  DirectAssistantBackend.runTurn()  ── LLM stream clients
             │                                                    anthropic.ts / openai.ts / llama.ts
             └ kind "external": assistantBridge.sendUtterance()   ── bridge-client.ts (WebSocket)
   ToolRegistry (process-wide) ◄── registerSystemTools / registerWindowTools / registerNavigateTools /
                                    registerRoamTools / registerTimerTools / app windows (set-tools) /
                                    EvenHub apps (extension setAssistantTools)
   AssistantMcpServer ── serves ToolRegistry to the external agent over the bridge's "mcp" channel
```

* Everything runs on the main isolate (tool handlers need shell state; the loop is I/O-bound) (`notes/voice-assistant-design.md:87-91`).
* Backend-agnostic turn callbacks: `onTextDelta(delta, textSoFar)`, `onToolActivity(label)`, `onTurnDone({stopReason})`, `onError(message)`; a turn handle has `cancel()` (`app/assistant/types.ts:20-32`).
* Provider-neutral message model (`app/assistant/llm-protocol.ts:6-52`): `LlmMessage{role: user|assistant, content: string | LlmContentBlock[]}`; blocks `text`, `tool_use{id, name, input, provider_item?}`, `tool_result{tool_use_id, content, is_error?}`, `provider_item{provider:"openai", item}` (opaque state for stateless OpenAI continuation). Tool definitions `{name, description, input_schema}`. Stream options `{apiKey, model, system?, messages, tools?, maxTokens?, effort?: low|medium|high, onTextDelta?, onDone(result{text, content, stopReason}), onError}`.
* Per-turn context `AssistantContext{foregroundApp, foregroundTitle, screenOn, localTime ("Thu Sep 25, 3:15 PM"), headsetBattery}` (`app/assistant/types.ts:9-18`; built at `app/ui/shell/shell.ts:1250-1259`, time format `:1549-1557`).
* **Design-note status** (`notes/voice-assistant-design.md:1-17`, `:381-448`): phase 1 (direct assistant + system tools), phase 2 (app tools over the worker protocol) and phase 3 (external bridge; the bridge server *is* the OpenClaw plugin) are implemented; phase 4 (Hermes/generic-agent adapters, session persistence across reconnects) is open; external mode was explicitly "not yet hardware-tested" at the time of the note. Later code added OpenAI and local providers, conversations, window/nav/roam/timer tools and EvenHub-contributed tools beyond what the note describes.

### B.2 Providers, model ids and resolution (`app/assistant/models.ts`)

| Selection value (persisted) | Label | Provider | Wire model id | Default effort |
|---|---|---|---|---|
| `auto` | Auto | resolved | — | — |
| `hauku` (sic; persisted spelling) | Haiku | anthropic | `claude-haiku-4-5` | none |
| `sonnet` | Sonnet | anthropic | `claude-sonnet-5` | low |
| `opus` | Opus | anthropic | `claude-opus-5` | low |
| `fable` | Fable | anthropic | `claude-fable-5` | low |
| `luna` | Luna | openai | `gpt-5.6-luna` | low |
| `terra` | Terra | openai | `gpt-5.6-terra` | low |
| `sol` | Sol | openai | `gpt-5.6-sol` | low |
| `qwen` | "Qwen3 4B (on-phone)" | local | `qwen3-4b-instruct-2507` | none |

Source `models.ts:3-58`. Resolution (`:74-115`): keys are trimmed; `auto` → `terra` if an OpenAI key exists, else `sonnet` if an Anthropic key exists, else `qwen` if the local model file is present, else **unavailable** (null). A concrete selection is unavailable if its key (or local model) is missing. iOS hides `qwen` and maps it to `auto` (`:17-21`). The per-conversation **reasoning** override (`default|low|medium|high`) replaces `effort` only when the model defines one (so it has no effect on Haiku/Qwen) (`app/ui/shell/shell.ts:1231-1248`). The global default comes from `assistant.model` (default `auto`, `app/ui/dashboard-settings.ts:742-758`). Backend selection: `assistant.backend = direct|external` (§B.12); external needs host and token.

> The model ids above are what the code sends; verify against current provider catalogues at implementation time and keep them data-driven (Open questions).

### B.3 System prompt and context (`app/prompts.ts`)

* **Base prompt** (`:9-14`), four instructions: (1) identity — the voice assistant built into Even Realities G2 glasses; (2) output is shown on a 640×480 monochrome HUD "and may also be read aloud", so 1–3 plain sentences, no markdown, no bullet lists unless asked; (3) prefer acting with tools over describing how the user could; (4) if a tool fails or a capability is missing, say so briefly rather than inventing a result.
* **Context line** (`:28-41`): `Context: Current time: <localTime>. The glasses display is currently on|off. The foreground app is <appId> ("<title>").` — or `No app is in the foreground (the launcher is showing).` — plus `Glasses battery: N%.` when known.
* Cloud providers: `system = base + "\n\n" + context` (`:17-19`). Local provider: `system = base` only, and the context rides on the user message as `"<utterance>\n\n(<context>)"` so the system prompt + tool declarations stay byte-stable for KV-prefix reuse (`app/assistant/session.ts:158-176`).
* Other prompts in the same file (not part of the assistant loop): dictation refine (§A.13) and calculator rewrite ("rewrite the spoken request as ONE mathematical expression … reply exactly 'none' if not a calculation") (`app/prompts.ts:44-67`).

### B.4 Session and agent loop

**`AssistantSession`** (`app/assistant/session.ts`):
* One turn at a time; a second `sendUtterance` while busy → `onError("The assistant is still working on the previous request")` (`:101-105`). Text is trimmed; empty is ignored.
* At turn start it appends `{user, text}` and an empty `{assistant, ""}` to the **transcript** (the display history), sets status "Thinking...", and installs a sentinel turn handle before calling providers (synchronous failures are possible) (`:108-114`). A monotonically increasing `turnGeneration` discards callbacks from cancelled/finished turns (`:116-147`).
* Status mirrors activity: "Thinking...", `→ <label>` during tools, error text, "Cancelled".
* Direct mode appends the user message to `messages`, trims history, and runs the loop on a **copy** of the array (`turnMessages`); on success the copy replaces `messages` (so orphan tool results from cancelled tools never leak into later turns); on error or cancel only the visible partial reply is appended as a plain assistant message (`:157-210`).
* **History trimming** (`:32-33`, `:233-252`): if more than 40 messages, drop from the head, then drop any leading user message that starts with a `tool_result` block.
* **Tool-name sanitizing** (`:212-231`): provider APIs restrict names, so canonical dotted names (`timer.set`) become API names by replacing `[^a-zA-Z0-9_-]` with `_` (`timer_set`), with `_2`, `_3`… suffixes on collision; a per-session map converts back on calls. Rebuilt every loop iteration.
* **Engine identity** = `"<provider>:<model>"` or `"external"`. When a session is reconfigured to a different engine, or restored from history saved under another engine, `messages` is rebuilt as plain text from the transcript (tool blocks and opaque OpenAI items are only replayable on their original model) (`:47-94`).

**`DirectAssistantBackend.runTurn`** (`app/assistant/direct-backend.ts:48-175`):

| Rule | Value |
|---|---|
| Max loop iterations | 8 (cloud), 4 (local) → error "Assistant stopped: too many tool steps" (`:23-25`, `:64-70`) |
| Max turn duration | 2 min, enforced by a timer and per iteration → "Assistant stopped: turn took too long" (`:26`, `:71-74`, `:138`) |
| Tools per request | re-listed from the registry every iteration (`buildTools`) so apps opening/closing mid-turn are reflected |
| Streaming text | `onTextDelta(delta, priorText + textSoFar)` where text of earlier iterations is kept, joined with a blank line (`:83-96`) |
| Refusal | provider stop reason `refusal` → error "The assistant declined this request" (`:90-93`) |
| Continue condition | stop reason `tool_use` **and** at least one tool_use block; otherwise `onTurnDone({stopReason})` (`:103-111`) |
| Tool execution | sequential; for each tool: `onToolActivity(canonicalName)`, `registry.callTool(name, input)`, append `tool_result{content = result or error text, is_error}`; if cancelled mid-way, remaining calls get `"Cancelled"` error results; results are always appended (to keep tool_use/tool_result paired) before checking cancellation (`:112-174`) |

### B.5 Anthropic Messages client (`app/native/anthropic.ts`)

* `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, JSON body (`:20-21`, `:153`).
* Body: `{model, max_tokens: 8192, stream: true, messages, system?, tools? (as {name, description, input_schema}), output_config?: {effort}}` (`:73-81`). Messages are sent in the neutral format, which *is* Anthropic's block format (`provider_item` blocks never occur for Anthropic engines because of the engine-identity rebuild rule).
* SSE handling (`:83-150`): `content_block_start` creates a text or tool_use accumulator keyed by `index`; `content_block_delta` → `text_delta` (append + `onTextDelta`) or `input_json_delta` (append `partial_json`); `message_delta.delta.stop_reason` recorded; `message_stop` → done; `error` event → fail. Ping/other events ignored. **Transport EOF without `message_stop` is an error** (never execute a half-streamed tool call) (`:143-146`). Tool JSON that fails to parse becomes `{}` (`:171-193`).
* HTTP errors (`:207-227`): 401 "invalid API key", 403 "lacks permission", 429 "rate limited", 500/529 "service overloaded", else `HTTP <code> (<error.message>)`.
* Default model constant `claude-sonnet-5` (`:24`).

### B.6 OpenAI Responses client (`app/native/openai.ts`)

* `POST https://api.openai.com/v1/responses`, `Authorization: Bearer` (`:15`, `:174`).
* Body (`:55-72`): `{model, input, stream: true, store: false, include: ["reasoning.encrypted_content"], max_output_tokens: 8192, instructions?: system, tools?: [{type:"function", name, description, parameters}], reasoning?: {effort}}`.
* **Stateless continuation:** with `store:false`, reasoning items (with encrypted content) and function-call items are captured verbatim (`provider_item`) and replayed as input items on the next request; `tool_use` blocks carry their original `function_call` item (`:81-139`, `:239-268`).
* Input conversion (`:193-237`): string content → `{role, content}`; text blocks are coalesced into `{role, content}` messages; `tool_use` → replayed item or `{type:"function_call", call_id, name, arguments: JSON}`; `tool_result` → `{type:"function_call_output", call_id, output}` (errors prefixed `"Error: "`); `provider_item` → replayed item.
* Events: `response.output_item.added/done` (function_call / message / reasoning), `response.output_text.delta`, `response.function_call_arguments.delta` (matched by `output_index` or `item_id`), `response.refusal.delta|done` (sets refusal), `response.completed` → done, `response.incomplete` → done with `stopReason = incomplete_details.reason`, `response.failed` / `error` → fail (`:80-159`).
* Normalized stop reason: refusal → `refusal`; any tool_use → `tool_use`; else recorded reason or `end_turn` (`:37-48`). Same EOF rule as Anthropic. HTTP errors: 401/403/429 and 500/502/503/504 mapped to friendly strings (`:302-324`).

### B.7 Local model: Qwen3-4B via llama.cpp

**Model & lifecycle** (`app/native/llama.ts`): file per §A.9; `isLocalModelReady()` = file exists, length > 0. Runner is created lazily; model stays loaded between generations (KV reuse) and is freed 2 min after the last generation/cancel (`:39`, `:141-162`). Download/cancel/delete as in §A.9 (`:91-139`). Android only.

**Inference parameters** (`llama.ts:29-37`, `:224-236`): `n_ctx = 4096`, threads = 4 (capped to the number of "fast" CPU cores and pinned to them via a ggml threadpool with strict CPU mask), `max_tokens = 512`, `temperature 0.7`, `top_p 0.8`, `top_k 20` (Qwen3-Instruct-2507 model-card values), `n_batch 512`, `n_gpu_layers 0` (CPU only) (`App_Resources/Android/src/main/native/llama/faceclaw_llama.cpp:168-230`).

**JNI runner** (`App_Resources/.../FaceclawLlamaRunner.kt:23-133`): single background executor serializes load/generate/free; listener callbacks posted to the constructing thread's looper; `cancel()` from any thread sets an atomic flag checked at token boundaries (and during prefill batches) → `onDone("cancelled")`. `nativeGenerate` (`faceclaw_llama.cpp:256-391`):
1. tokenize the prompt (with special tokens); if `tokens + 16 > n_ctx` → error "Conversation too long for the on-phone model";
2. **KV prefix reuse:** find the longest common prefix with the tokens already in the KV cache, drop the rest (always re-decode at least the last prompt token);
3. sampler chain: [lazy grammar] → top-k → top-p → temperature → dist (default seed);
4. prefill remaining tokens in 512-token batches; generate up to `min(max_tokens, n_ctx − prompt)`; emit UTF-8-complete pieces; stop reasons `stop` (EOG), `length`, `cancelled`; on failure the cache past the reused prefix is invalidated.

Build: llama.cpp release tag `b10333` fetched at build time, statically linked into `libfaceclaw_llama.so`, arm64 `-march=armv8.2-a+dotprod+fp16`, no OpenMP/Vulkan/curl (`App_Resources/Android/app.gradle:146-215`; `App_Resources/Android/src/main/native/llama/CMakeLists.txt:1-31`).

**Prompt format** (`app/assistant/qwen-prompt.ts:15-81`) — ChatML with Hermes-style tools:
* `<|im_start|>system\n{system}{tools block}<|im_end|>\n`, then each message as `<|im_start|>user|assistant\n…<|im_end|>\n`, ending with `<|im_start|>assistant\n`.
* Tools block (only if tools exist): `\n\n# Tools\n\nYou may call one or more functions…` followed by `<tools>` with one JSON object per line `{"type":"function","function":{"name","description","parameters"}}`, `</tools>`, and the instruction to answer calls as `<tool_call>\n{"name": …, "arguments": …}\n</tool_call>` (standard Qwen template).
* Assistant history: trimmed text blocks plus `<tool_call>\n{"name","arguments"}\n</tool_call>` per tool use; `provider_item` skipped. User history: text plus `<tool_response>\n<content or "Error: …" or "(no output)">\n</tool_response>` per tool result.

**GBNF tool-call grammar** (`app/assistant/gbnf.ts`) — applied as a *lazy* grammar that activates only when the model emits `<tool_call>` (single special token in Qwen's vocabulary; otherwise a regex trigger `(<tool_call>[\s\S]*)`) so free-text answers are unconstrained (`faceclaw_llama.cpp:296-314`):
* `root ::= tc0 | tc1 | …`; each `tcN ::= "<tool_call>" nl "{" sp "\"name\"" sp ":" sp "\"<tool>\"" sp "," sp "\"arguments\"" sp ":" sp <args> sp "}" nl "</tool_call>"` (`gbnf.ts:28-48`).
* `<args>`: for ≤5 properties, an alternation of every subset that includes all required properties, in declaration order; for >5 properties with any optional → generic `jobj`; no properties → `"{" sp "}"` (`:51-85`).
* Values: string enum → literal alternation; number/boolean enum → literals; `string`→`jstring`, `number`→`jnumber`, `integer`→`jint`, `boolean`→`jbool`, anything else (arrays, objects) → generic `jvalue` (`:88-118`). Shared terminal rules mirror llama.cpp's json.gbnf (`:132-145`).
* Once the block closes, the grammar is complete, so the model can only end the message: **one tool call per iteration** (deliberate; small models are more reliable) (`:3-12`).

**Streaming** (`llama.ts:173-321`): raw output accumulates; the visible text excludes complete `<tool_call>…</tool_call>` spans, text inside an unfinished call, and any trailing partial `<tool_call>` prefix, so tool calls never flash on the lens (`visibleText`, `:260-284`). At completion, calls are parsed as JSON `{name, arguments}` into `tool_use` blocks with ids `local_<ms>_<n>` (unparseable calls are logged and dropped); stop reason `tool_use` if any call, `max_tokens` if the native reason was `length`, else `end_turn` (`:212-220`, `:287-321`).

Other features also use the local model directly (Microphones app insights, name verification, conversation Q&A; calculator fallback) — outside this spec.

### B.8 Tool registry (`app/assistant/tool-registry.ts`)

* `ToolSpec{name, description, inputSchema (JSON Schema), availability, proactive?, timeoutMs?}`; `ToolResult{ok, content?, error?}` (flat, not a union) (`:21-47`).
* **Availability tiers** (`:8-19`; design table `notes/voice-assistant-design.md:156-196`):

| Tier | Live when | Handler | Registered by |
|---|---|---|---|
| `always` | always | in-process shell function | `registerSystemTool` (`:108-113`) |
| `installed` | app installed, no window needed | (designed; realized as `always` wrappers that launch-on-call) | — |
| `open` | the declaring window exists | proxied to the window | `setAppTools` |
| `foreground` | declaring window is foreground (checked at list **and** call time) | proxied | `setAppTools` with `isForeground` predicate |

* `setAppTools(provider)` replaces a window's tool set; names are prefixed `app.<appId>.<name>`; only `open`/`foreground` accepted; `removeAppTools(windowId)` on close (`:127-161`). A later registration with the same name silently replaces the earlier (Map by name).
* `listTools({proactiveOnly?})` returns live specs (`:168-176`); `listToolsForDebug()` includes non-live entries and the owning window (shell debug dialog, `app/ui/shell/tool-debug-layer.ts`).
* `callTool(name, args, {proactive?})` never throws: unknown → `Unknown tool: X`; not live → `Tool X is not currently available`; proactive call to a non-proactive tool → `Tool X cannot be called outside a conversation`; default timeout 10 s → `Tool X timed out after Nms`; exceptions → `Tool X failed: …` (`:203-271`).
* `onToolsChanged` listeners fire on register/unregister/app-tool changes and on explicit `fireToolsChanged()` (e.g., EvenHub foreground flips) (`:223-237`).
* **Launch-on-call** helper (`app/assistant/launch-on-call.ts:13-36`): if `app.<appId>.<tool>` is not live, `launchApp(appId)`, poll every 150 ms up to 5 s for the tool to appear ("The <app> app did not start in time."), then forward the call.

### B.9 Tool catalogue

API names sent to providers are the sanitized forms (`glasses_get_state`); MCP clients see canonical names. "P" = proactive-capable (callable by the external agent outside a turn). Timeout default 10 s unless noted.

**System tools** (`app/assistant/system-tools.ts`; registered at startup, `app/g2/dashboard-controller.ts:350`):

| Name | Parameters | P | Behavior / result text | Line |
|---|---|---|---|---|
| `glasses.get_state` | — | ✓ | "Display: on/off. Foreground app: id ("title")./Foreground: launcher (no app open). Headset battery: N%[, charging]. Local time: …" | `:26-35`, `:168-180` |
| `glasses.show_alert` | `text: string` (required) | ✓ | shows the 6 s alert popup (wakes screen); "Displayed." | `:37-56` |
| `calendar.list_events` | `within_hours?: number` (clamped 1..1440, default 168), `max_events?: number` (1..50, default 10) | | lines `- <title> — <Wed Sep 24, 3:15 PM \| Wed Sep 24 (all day)> @ <location>`; permission error text if calendar not granted | `:58-87`, `:195-199` |
| `media.now_playing` | — | ✓ | "Playing/Paused: title by artist in App." / "Nothing is currently playing." (Android only) | `:92-100`, `:182-193` |
| `media.play_pause` | — | | toggles if the session can (Android only) | `:102-114` |
| `media.next` | — | | skip next if supported (Android only) | `:116-128` |
| `notifications.list` | `max?: number` (1..50, default 10) | | `- [App] Title: text (key: K)` lines (Android only) | `:130-146`, `:201-205` |
| `notifications.dismiss` | `key: string` (required) | | dismiss by key (Android only) | `:148-165` |

**Window/launcher tools** (`app/assistant/window-tools.ts`; re-registered whenever the set of installed EvenHub apps changes, so enums stay current, `:43-57`):

| Name | Parameters | P | Behavior | Line |
|---|---|---|---|---|
| `apps.launch` | `app_id: enum` of launcher app ids + installed EvenHub ids (`evenhub-installed:<pkg>`); description lists installed EvenHub apps | | wake display, launch or focus | `:77-101` |
| `apps.list_windows` | — | ✓ | `- <windowId> — "<title>" (app: <appId>) [foreground, pinned]` | `:103-124` |
| `apps.focus_window` | `window_id: string` (window id, or an app id with exactly one window) | | wake + focus | `:126-152`, `:298-313` |
| `apps.close_window` | `window_id: string` | | close unless pinned (launcher) | `:154-180` |
| `apps.list_folders` | — | ✓ | folders with members + "Ungrouped: …" | `:182-201` |
| `apps.move_to_folder` | `app_id: enum`, `folder: string` (case-insensitive match; length-limited) | | create folder if needed | `:203-234` |
| `apps.remove_from_folder` | `app_id: enum` | | move to top level | `:236-260` |
| `apps.disband_folder` | `folder: string` | | move all apps out | `:262-290` |

**Navigation wrappers** (`app/assistant/navigate-tools.ts`, launch-on-call into the Navigate app):

| Name | Parameters | P | Behavior | Line |
|---|---|---|---|---|
| `nav.start_navigation` | `destination: string` (req), `profile?: driving\|walking\|cycling` | | prompts location permission early; forwards to `app.navigate.start_route{query, profile}`; timeout 25 s | `:24-55` |
| `nav.stop_navigation` | — | | forwards to `app.navigate.stop_route` if live, else "Navigation is not active." | `:57-69` |
| `nav.route_status` | — | ✓ | forwards to `app.navigate.route_status` if live, else "Navigation is not active." | `:71-85` |

**Roam wrappers** (`app/assistant/roam-tools.ts`; require `integrations.roam.graphName`/`apiToken`):

| Name | Parameters | Behavior | Line |
|---|---|---|---|
| `roam.add_todo` | `text: string` (req) | → `app.roam.add_todo`; timeout 25 s | `:22-38` |
| `roam.read_todos` | — | → `app.roam.read_page`; timeout 25 s | `:40-49` |

**Timers/stopwatch/alarms** (`app/assistant/timer-tools.ts`, registered when the Timers app boots with the shell, `app/apps/timer/index.ts:16-38`; all proactive). Timer/alarm numbers are 1-based in the app's sort order and may be omitted when exactly one exists (otherwise the error lists them) (`timer-tools.ts:71-86`):

| Name | Parameters | Behavior | Line |
|---|---|---|---|
| `timer.set` | `hours?`, `minutes?`, `seconds?` (numbers ≥0, total >0, ≤24 h), `label?` | start + bring Timers forward; returns number and end time | `:114-148` |
| `timer.list` | — | per timer: remaining / paused / finished-ringing, end time | `:150-164` |
| `timer.cancel` | `timer?: number`, `all?: boolean` | cancel or dismiss | `:166-195` |
| `timer.pause` | `timer?`, `resume?: boolean` | pause/resume (paused timers resume) | `:197-224` |
| `timer.add_time` | `timer?`, `minutes?` (default 1) | extend (a finished timer restarts) | `:226-251` |
| `timer.dismiss` | — | clear finished timers and dismiss ringing alarms | `:253-264` |
| `stopwatch.control` | `action: start\|stop\|lap\|reset\|read` (req) | returns state, elapsed, laps | `:268-311` |
| `alarm.set` | `time: string` (req; 12/24 h), `days?: string[]` (names, weekdays, weekends, every day), `label?` | add alarm + show app | `:315-347` |
| `alarm.list` | — | number, time, days, label, on/off, next ring | `:349-362` |
| `alarm.enable` | `alarm?`, `enabled: boolean` (req) | toggle | `:364-388` |
| `alarm.delete` | `alarm?` | delete | `:390-410` |
| `alarm.snooze` | `minutes?` | snooze all ringing alarms (default = app setting) | `:412-432` |

**App (window) tools** — declared by running app windows, prefixed `app.<appId>.`:

| Canonical name | Tier | Parameters | Timeout | Source |
|---|---|---|---|---|
| `app.terminal.list_sessions` | open | — | 10 s | `app/apps/terminal/terminal-app.worker.ts:206-212` |
| `app.terminal.send_input` | open | `text: string` (typed + Enter into the active session) | 10 s | `:213-224` |
| `app.terminal.read_screen` | open | — (visible grid text of active session) | 10 s | `:225-230` |
| `app.terminal.list_launch_presets` | open | — | 10 s | `:231-237` |
| `app.terminal.launch_session` | open | `preset: string` (req), `host?: string` | 15 s | `:238-255` |
| `app.navigate.start_route` | open | `query: string` (req), `profile?` enum | 14 s | `app/apps/navigate/navigate-app.worker.ts:131-152` |
| `app.navigate.stop_route` / `route_status` | open | — | 10 s | `:153-165` |
| `app.roam.read_page` / `add_todo{text}` / `set_todo_done{match, done?}` / `edit_block{match, new_text}` | open | as listed | 14 s | `app/apps/roam/roam-app.worker.ts:80-133` |
| `app.calculator.calculate` | open | `problem: string` (verbatim words; engine solves exactly and shows the result) | 10 s | `app/apps/calculator/calculator-app.ts:39-58`, `:130-150` |
| `app.<packageId>.<name>` | open or foreground (default) | app-declared JSON schema | 10 s | EvenHub extension (§C.11) |

The MCP server file (`app/assistant/mcp-server.ts`) defines **no tools of its own**; it exposes the registry.

### B.10 App-tool plumbing

* **Worker apps** (postMessage protocol, `app/ui/shell/worker-window.ts`): worker → shell `{type:"set-tools", windowId, tools: ToolSpec[]}` (accepted only for windows the host has open) and `{type:"tool-result", callId, result}`; shell → worker `{type:"tool-call", callId:"<appId>:<serial>", windowId, name (unprefixed), args}` (`:27-38`, `:147-155`, `:382-403`, `:537-550`). Host backstop timeout 15 s ("App X did not respond to T"); window close fails pending calls with "The target window was closed" (`:218-230`, `:552-560`).
* **In-process windows** call `toolRegistry.setAppTools` directly (calculator) (`app/apps/calculator/calculator-app.ts:130-150`).
* **EvenHub apps** use the extension API (§C.11): specs are registered under the app's **package id** as appId; calls dispatch into the WebView (`window.__fcExtInvokeTool(id, name, args)`) and resolve when the page posts `faceclawExtToolResult [id, ok, value]`; non-string values are JSON-stringified; `foreground` tools follow the session's shell-foreground flag (`app/apps/evenhub/session.ts:1067-1128`).

### B.11 Conversation persistence (`app/assistant/conversations.ts`)

* Stored as one JSON string in settings key `assistant.conversations` (`app/ui/shell/shell.ts:336-345`):
  `{selectedId, conversations: [{id: "<ms>-<random36>", model: AssistantModel, reasoning: "default"|"low"|"medium"|"high", history: {messages: LlmMessage[], transcript: {role, text}[], engine?: string}}]}`.
* No credentials are saved. Records with unknown model/reasoning or missing arrays are dropped on load; `qwen` maps to `auto` on iOS (`:27-39`). An empty list gets one fresh conversation.
* Persist on create/select/configure and whenever a session changes **while no turn is active** (i.e., after each turn completes/errors/cancels) (`:52-57`, `:91`).
* Title = first user utterance, whitespace-collapsed, 60 chars, else "New session" (`:44-47`).
* `create()`/`select()`/`configure()` are refused while the current session has an active turn (`:61-83`); `configure()` is also refused if the model/reasoning does not resolve.
* In external mode the phone keeps only the transcript for display; the agent owns real history (`app/assistant/session.ts:149-155`).
* No retention limit, pruning or deletion UI for old conversations exists.

### B.12 External agent bridge (OpenClaw) and phone-side MCP server

**Topology.** The phone *dials out* to a bridge co-located with the user's long-running agent (in practice the `faceclaw-agent-bridge` OpenClaw plugin, a separate repo not present here). The plugin runs utterances as OpenClaw agent turns in a dedicated session (default key `faceclaw:glasses`) and exposes the glasses tools to the agent via two fixed meta-tools `glasses_list_tools` / `glasses_call` (`notes/voice-assistant-design.md:3-17`, `:287-351`). Expected transport is a tailnet; TLS is not used.

**Settings** (`app/ui/dashboard-settings.ts:643-696`): `assistant.backend` (`direct` default | `external`), `assistant.bridgeHost` (e.g. Tailscale IP), `assistant.bridgePort` (default `8790`), `assistant.bridgeToken`, `assistant.allowProactive` (default true). The connection is (re)configured whenever these change and runs for the life of the app while configured (`app/g2/dashboard-controller.ts:470-486`); `deviceName` is `"faceclaw"`.

**Wire protocol** (`app/assistant/bridge-client.ts`): URL `ws://<host>:<port>` (`:158`); one JSON object per text frame; every phone frame carries `v: 1` (`:310-317`). Frames are multiplexed by `chan`:

| chan | Direction | Frame | Notes |
|---|---|---|---|
| `ctl` | phone→bridge | `{type:"hello", version:1, token, deviceName, capabilities:["chat","mcp"]}` | sent on socket open (`:161-172`) |
| `ctl` | bridge→phone | `{type:"hello-ack", serverName?}` | → connected; backoff reset (`:222-226`) |
| `ctl` | bridge→phone | `{type:"ping", ts}` | phone replies `{type:"pong", ts}` (`:227-230`) |
| `ctl` | bridge→phone | `{type:"error", message}` | status "Bridge error: …"; server then closes (`:231-237`) |
| `chat` | phone→bridge | `{type:"utterance", turnId:"t<n>", text, ctx: AssistantContext}` | new turn supersedes (fails) any active one (`:125-153`) |
| `chat` | phone→bridge | `{type:"cancel", turnId}` | local turn cleared immediately |
| `chat` | bridge→phone | `{type:"text-delta", turnId, text, replace?: true}` | append, or replace the whole text when `replace` (`:244-249`) |
| `chat` | bridge→phone | `{type:"tool-activity", turnId, label}` | status line |
| `chat` | bridge→phone | `{type:"turn-done", turnId, stopReason?}` / `{type:"turn-error", turnId, message}` | ends the turn |
| `mcp` | both | `{chan:"mcp", msg: <raw JSON-RPC 2.0 message>}` | phone is the MCP **server** |

Chat frames for a non-current `turnId` are ignored. Phone-side turn backstop 3 min ("The agent took too long to reply"; server-side cap stated as 2 min) (`:25-26`, `:140-144`). Reconnect with exponential backoff 1 s → 60 s, doubling, reset on `hello-ack`; a lost connection fails the active turn ("Bridge connection lost") (`:23-24`, `:269-295`). Sending while not connected → immediate error "Agent bridge is not connected (<status>)" (`:130-133`). State phases: `idle | connecting | connected | failed` with a status string for Settings.

**MCP server** (`app/assistant/mcp-server.ts`, transport-agnostic):

| Method | Response |
|---|---|
| `initialize` | `{protocolVersion: <client's, else "2025-06-18">, capabilities:{tools:{listChanged:true}}, serverInfo:{name:"faceclaw", version}}` (`:48-57`) |
| `notifications/initialized` | ignored |
| `ping` | `{}` |
| `tools/list` | `{tools:[{name (canonical dotted), description, inputSchema}]}` for live tools (no pagination) (`:63-71`) |
| `tools/call {name, arguments}` | `{content:[{type:"text", text}], isError}` (`:87-106`) |
| other with id | JSON-RPC error `-32601 "Method not supported: <m>"` |
| server→client | `{jsonrpc:"2.0", method:"notifications/tools/list_changed"}` whenever the registry changes while connected (`bridge-client.ts:92-94`) |

**Proactive gating** (enforced on the phone, never trusted to the bridge): a tool call while no turn is active is *proactive*; it requires `assistant.allowProactive`, passes a sliding-window limiter of 6 calls/minute, and the registry additionally requires `spec.proactive` (`mcp-server.ts:87-117`; `tool-registry.ts:211-213`). The design note that proactive display actions "never turn the screen on" is **not** implemented: `glasses.show_alert` wakes the screen (`app/ui/shell/shell.ts:1380-1381`).

### B.13 How answers reach the glasses

* **Overlay:** `AssistantLayer` in the shared input dialog (§A.13) — plain wrapped text, tail-anchored, no scrolling, no markdown rendering (the prompt asks for plain sentences).
* **AI Chat window:** full scrollable transcript (§A.13).
* **Alerts:** `glasses.show_alert` popups.
* **Watch:** reply mirrored via `AssistantActivityEvent`.
* **`app/ui/document/*` (DocumentView / DocumentRoot)** is *not* used for assistant answers in this commit. It is a small structured-text renderer used by the Roam app: nodes `text|todo|heading(level 1-3)|code` with inline `text|link(target, external?)|code`, nesting for indentation (18 px step, 16 px marker gutter), todo checkboxes, underlined links, shaded code blocks, node-granular selection highlight and scroll-follow (`app/ui/document/document-model.ts:11-70`; `app/ui/document/document-view.ts:1-120`; used at `app/apps/roam/roam-app.worker.ts:30-31`, `:185`). A rebuild could reuse such a renderer to show long or structured assistant answers with scrolling.
* There is **no text-to-speech**, despite the prompt's "may also be read aloud".

---

## C. EvenHub app compatibility

### C.1 What EvenHub apps are

EvenHub apps are web apps (HTML/JS/CSS, typically built with `@evenrealities/even_hub_sdk`) that run in a phone WebView inside the official Even app and drive the glasses through a JS bridge (`window.flutter_inappwebview.callHandler(...)`, the Flutter InAppWebView convention). The official host forwards page "containers" (text, list, image) to the glasses firmware, which renders them into a **576×288** app area; input arrives back as events. Faceclaw re-hosts these apps: the WebView runs hidden on the phone, and Faceclaw **renders the containers itself** into its own glasses window (a phone-side reimplementation of the firmware compositor), rather than sending EvenHub container commands to the firmware (`app/apps/evenhub/index.ts:1-9`; `app/apps/evenhub/compositor.ts:1-12`). Wire shapes were captured empirically from `even_hub_sdk` 0.0.12, with 0.0.14 additions (text brightness, context menu, long-press events) (`app/apps/evenhub/session.ts:1-18`). (The design note `notes/evenhub_compatibility.txt` referenced by comments is not in this checkout.)

### C.2 Package format (`.ehpk`) and manifest

Produced by `evenhub pack` (`@evenrealities/evenhub-cli`). Parser: `app/apps/evenhub/ehpk.ts:80-122` (a port of an external `ehpk_unpack.py`).

| Offset / part | Meaning |
|---|---|
| bytes 0–3 | magic `"EHPK"` (`45 48 50 4B`); file must be ≥ 20 bytes |
| bytes 8–11 | uint32 LE: offset of the first record (must be ≥ 20); other header bytes unused by the parser |
| record header | 1 byte type + 3 bytes magic `BA A9 BA` |
| type `0xE4` FILE | `+4` uint32 LE compressed size; `+8` uint32 LE uncompressed size; `+14` uint16 LE name length; `+16` name (XOR-obfuscated UTF-8); then compressed data (XOR-obfuscated **zstd** frame). Bytes `+12..+13` unused |
| type `0xE5` DIR | `+6` uint16 LE length; skip `8 + length` bytes |
| type `0xE3` FOOTER | end of records; trailer is a SHA-512 over everything before it (integrity only, **not verified**) |

* Obfuscation: XOR with the repeating ASCII key `"EVEN REALITIES"`; the key index restarts at 0 for each field (name, data) (`ehpk.ts:15-19`, `:47-53`).
* Paths must be relative, without `\`, NUL, `:`, empty, `.` or `..` segments (`:22-24`); decompressed size must equal the declared size.
* Layout: `app.json` at the root; the app tree under `dist/`; entrypoint resolved under `dist/` (`app/apps/evenhub/package-extraction.ts:5-25`).

**Manifest `app.json`** (`ehpk.ts:135-171`; `app/apps/evenhub/permissions.ts:109-146`):

| Field (accepted spellings) | Default | Use |
|---|---|---|
| `package_id` / `packageId` | `unknown.package` (rejected at install) | identity, storage keys, tool namespace, WebView origin |
| `name` | package id | window title, launcher label |
| `version` | `"0"` | update comparison |
| `entrypoint` | `index.html` (relative to `dist/`) | first page |
| `permissions` | [] | current shape: array of `{name, desc|description?, whitelist?}` or bare strings; legacy shape: map `{name: [whitelist...]}`. Known names: `network`, `location`, `g2-microphone`, `phone-microphone`, `album`, `camera`, `fs` (unknown names pass through to the consent dialog) |
| `privacy_link` / `privacyLink` / `privacy_policy_url` / `privacyPolicyUrl` | "" | https-only; shown in the consent dialog |
| `icon`, `icon_path`, `iconPath`, `app_icon`, `appIcon` | — | launcher icon lookup; else `<link rel=*icon*>` in the entry HTML; else `icon.svg/png`, `favicon.svg/png` (root or `dist/`) (`app/apps/evenhub/installed-apps.ts:245-275`) |

`networkWhitelist` is collected from `network` permissions but **not enforced** (`ehpk.ts:32-45`; `App_Resources/.../FaceclawEvenHubWebViewClient.kt:27-29`).

### C.3 Install, registry, launch

* **Sources:** (1) Files app: an `.ehpk` offers "Run app" (after the consent dialog if the manifest declares permissions or a privacy policy) and install (`app/apps/files/files-app.ts:147-165`, `:190-199`); (2) the EvenHub store window (§C.13); (3) Developer app "Load app from URL / QR code" for live dev servers (`app/apps/evenhub/manager.ts:142-185`).
* **Registry:** settings key `evenhub.installedApps.v1` = JSON array of `{packageId, name, version, installedAt (ISO), iconFile?}`, deduplicated, sorted by name (`installed-apps.ts:21-80`). Package stored at `<appFilesDir>/evenhub-installed/<safeId>/package.ehpk` plus `icon.<ext>`; `safeId` replaces `[^A-Za-z0-9._-]` with `_` (`:91-137`, `:230-236`). Launcher app id `evenhub-installed:<encodeURIComponent(packageId)>` (`:22`, `:40-52`). A settings-fingerprint change re-registers the `apps.*` tools (§B.9).
* **Launch** (`manager.ts:62-130`): re-unpack the stored EHPK on **every** launch into `<appFilesDir>/evenhub-apps/<safeId>/` (whole archive; directory deleted first), build an `EvenHubSession` + WebView, open an in-process glasses window `evenhub:app:<serial>`. Installed apps launch as **singletons** (a second launch focuses the running window, `:188-193`); `.ehpk` files run from Files are not singletons. Running apps are concurrent and keep running in the background until closed; there is no memory eviction (`:1-12`).
* **Dev URL apps** (`manager.ts:142-185`): URL normalized (bare host → `https://`, only http/https); a manifest is synthesized: `packageId = "dev.url." + sanitized(authority+path)[:96]`, name = authority, version `dev`, permissions `network, location, g2-microphone, phone-microphone` (the user pointed Faceclaw at it); reloading the same URL replaces the running instance.
* **Phone UI on demand:** window menu "Show phone UI" raises that app's WebView over the dashboard; Android Back hides it again (`manager.ts:47-56`, `:204-217`; `app/apps/evenhub/evenhub-window.ts:78-80`).

### C.4 WebView hosting (Android)

* Raw `FaceclawEvenHubWebView` (subclass of `android.webkit.WebView`) created per app on the main thread with the foreground activity as context; JS on, DOM storage on, file access off, media autoplay allowed; `WebChromeClient` routes console messages to logcat tag `FaceclawEvenHubConsole` (`app/apps/evenhub/webview.ts:54-106`).
* **Offline asset serving** from a fake per-app origin `https://<lowercased package id with [^a-z0-9.-]→->.evenhub.invalid/` (isolates localStorage per app) via `shouldInterceptRequest`; other hosts go to the network normally (`webview.ts:36-39`, `:110`; `FaceclawEvenHubWebViewClient.kt:46-79`). Path resolution (`native/.../evenhub/EvenHubAssetServer.kt:25-44`): empty or `/` → `index.html`; lexical `.`/`..` normalization (escaping the root → 404); then canonical-path containment check to defeat symlinks; MIME by extension (html, js/mjs, css, json, wasm, svg, png, jpg/jpeg, gif, webp, ico, woff/woff2/ttf/otf, txt, map, xml, mp3, wav, ogg, mp4, webm; default `application/octet-stream`) (`:71-93`).
* **Shim injection:** for HTML responses the bridge script is spliced as `<script>…</script>` immediately after the first `<head…>` tag (or prepended) so `window.flutter_inappwebview` exists before any app script — apps race `waitForEvenAppBridge` against 4–6 s timeouts and fall back to demo modes (`EvenHubAssetServer.kt:46-59`; `FaceclawEvenHubWebViewClient.kt:19-26`). Remote (dev URL) apps get the same script through `WebViewCompat.addDocumentStartJavaScript` restricted to the page's origin (WebView ≥ 83), falling back to `evaluateJavascript` in `onPageStarted` (`App_Resources/.../FaceclawEvenHubDocumentStart.kt:26-49`; `webview.ts:45-48`, `:79-93`).
* **Bridge object:** `addJavascriptInterface(FaceclawEvenHubJsBridge, "__faceclawEvenHub")` with one method `postMessage(handlerName, argsJson, callId)`, bounced to the main thread (`App_Resources/.../FaceclawEvenHubJsBridge.kt:17-26`). `onPageFinished` → `session.webViewLoaded()`.
* **Keep-alive tricks** (`App_Resources/.../FaceclawEvenHubWebViewHost.kt`):
  1. All app WebViews live full-size and `VISIBLE` inside one `FrameLayout` inserted as child 0 of the activity content view — *behind* the opaque NativeScript UI — so Chromium keeps rendering them while occluded (`:89-125`). "Show on phone" brings the overlay to front; hide re-inserts it at index 0 (`:128-144`).
  2. The WebView subclass always reports window visibility `VISIBLE`, so the page is never frozen when the activity is backgrounded (`App_Resources/.../FaceclawEvenHubWebView.kt:28-35`).
  3. `resumeTimers()`, `onResume()`, and renderer priority `IMPORTANT` not waived when invisible (`FaceclawEvenHubWebViewHost.kt:112-119`).
  4. **Host-driven timers:** the shim replaces `setTimeout/setInterval/clearTimeout/clearInterval` and `requestAnimationFrame/cancelAnimationFrame` with JS queues; a main-looper ticker calls `window.__fcTimerTick(); window.__fcRafTick()` in every WebView every 16 ms (~60 Hz) while any app is attached, escaping Chromium's background/screen-off throttling (`FaceclawEvenHubWebViewHost.kt:62-83`, `:120-124`; `app/apps/evenhub/session.ts:111-192`). Timer semantics: due timers fire sorted by due time; an interval advances one period, and if it has fallen behind it is rescheduled to `now + period` (no catch-up bursts); rAF callbacks requested during a frame run on the next frame; exceptions are caught and logged.

### C.5 JS bridge protocol

**Call path (web → host):** `flutter_inappwebview.callHandler(name, ...args)` returns a Promise; the shim assigns an id, stores `[resolve, reject]`, and calls `__faceclawEvenHub.postMessage(name, JSON.stringify(args), id)`. The host answers with `evaluateJavascript("window.__fcResolve(id, ok, <JSON value>)")` (`session.ts:89-110`, `:927-931`). Handler names: `evenAppMessage` (SDK), `faceclawExt` and `faceclawExtToolResult` (extensions, §C.11). For `evenAppMessage`, `args[0]` is a JSON string (a bare object is also accepted) of the form `{type:"call_even_app_method", method, data}`; the handler's return value resolves the app promise; exceptions reject it with the error string (`session.ts:729-755`).

**Push path (host → web):** `window._listenEvenAppMessage({type:"listen_even_app_data", method, data})` (`session.ts:933-938`).

**Methods** (`session.ts:757-807`):

| Method | Params (`data`) | Return | Faceclaw behavior |
|---|---|---|---|
| `createStartUpPageContainer` | page (§C.7) | int: 0 success, 1 invalid, (2 oversize, 3 OOM never returned) | builds the page; emits the deferred launch `FOREGROUND_ENTER` if focused. A **second** call waits 2 s then returns 1 (emulates stock one-shot behavior) (`:297`, `:810-827`) |
| `rebuildPageContainer` | page | `true` | replaces the whole page (also clears/replaces the context menu); also acts as "create" (`:829-837`) |
| `textContainerUpgrade` | `containerID`, `containerName?`, `content`, `contentOffset?`, `contentLength?` (ignored), `textColor?` | boolean | match text container by id (and by name only if a non-empty name is supplied); `offset>0` ⇒ `content = old[0:offset] + new` (truncate the rest — stock strcpy semantics); else replace; `textColor` 0..4 sets brightness (`:856-886`) |
| `updateImageRawData` | `containerID`, `imageData` / `rawData` / `mapRawData` | int: 0 ok, 1 image exception, 3 container not found (2 never) | decode PNG/BMP/raw gray (§C.9) (`:889-902`) |
| `shutDownPageContainer` | `exitMode` | `true` | `exitMode==1` ("ask to quit"): **declined** — focus moves to the app switcher, the app keeps running, no FOREGROUND_EXIT. Other modes: close after 200 ms (`:904-919`) |
| `setLocalStorage` / `getLocalStorage` | `key`, `value` | `true` / string ("" default) | stored in app settings under `evenhub:<packageId>:ls:<key>` (`:921-923`) |
| `getUserInfo` | — | `{uid:0, name:"Faceclaw", avatar:"", country:""}` | static |
| `getGlassesInfo` | — | `{model:"g2", sn:"FACECLAW-G2", status:{sn, connectType:"connected", isWearing:true, batteryLevel:100, isCharging:false, isInCase:false}}` | static fake |
| `audioControl` | enable flag (field name unknown; see below) | boolean | requires a declared mic permission (`g2-microphone`/`phone-microphone`/`microphone`); registers intent with the mic router (§C.10); Android only (`:612-635`) |
| `imuControl` | enable flag, `reportFrq` (100..1000, snapped to 100s) | boolean | registers with the IMU router; no permission gate (`:706-722`, `:1269-1272`) |
| `getAppLocation` | — | `AppLocation` or `null` | requires declared `location` + Android fine-location grant; one fix (`:643-660`) |
| `startAppLocationUpdates` | `intervalMs?` (default 1000, min 200) | boolean | continuous tracker, pushes `appLocationChanged`; runs in background until stopped/closed (`:666-685`) |
| `stopAppLocationUpdates` | — | `true` | |
| `pickImageFromAlbum`, `captureImageFromCamera` | — | `null` | unsupported |
| any other | — | `null` | logged "unhandled method" |

Enable-flag parsing is a guess: the first present key among `enable, open, isOpen, on, status, value, start, iMUReportEn, IMU_ReportEn`, accepting boolean, number (≠0) or strings `"true" "1" "on" "open"`; **default true** when none present, and each payload is logged so hardware captures can pin it down (`session.ts:1250-1266`).

`AppLocation = {latitude, longitude, accuracy?, altitude?, speed?, heading?, timestamp?}` (camelCase; absent fields omitted) (`:1231-1250`).

**Pushes:**

| `method` | `data` | When |
|---|---|---|
| `evenAppLaunchSource` | `{launchSource:"glassesMenu"}` | once, on first page load (`:392-409`) |
| `deviceStatusChanged` | `{sn:"FACECLAW-G2", connectType:"connected", isWearing:true, batteryLevel:100, isCharging:false, isInCase:false}` | once, on first page load (static) |
| `evenHubEvent` | `{type:"sysEvent"\|"listEvent"\|"textEvent"\|"audioEvent"\|"menuItemClickEvent", jsonData}` | input, lifecycle, IMU, audio, menu |
| `appLocationChanged` | `AppLocation` | location subscription |

### C.6 Event emulation

`OsEventTypeList` values: `CLICK 0, SCROLL_TOP 1, SCROLL_BOTTOM 2, DOUBLE_CLICK 3, FOREGROUND_ENTER 4, FOREGROUND_EXIT 5, ABNORMAL_EXIT 6 (unused), SYSTEM_EXIT 7, IMU_DATA_REPORT 8, LONG_PRESS 9, LONG_PRESS_RELEASE 10` (`session.ts:67-79`). `eventSource`: 1 right arm, 2 ring (the Wear OS watch is reported as ring), 3 left arm (`:1412-1423`). **Zero-valued fields are elided** from `jsonData` exactly like protobuf JSON (apps test `eventType === 0 || eventType === undefined`) (`:1428-1435`).

| Glasses gesture | Event capture container is a list | otherwise | Line |
|---|---|---|---|
| click | `listEvent{containerID, containerName, currentSelectItemIndex, currentSelectItemName, eventType:0}` | `sysEvent{eventType:0, eventSource}` | `:441-455` |
| double-click | `sysEvent{eventType:3, eventSource}` | same | |
| tap-then-hold (window-menu gesture) | `listEvent{…, eventType:9}` | `sysEvent{9, source}` | `:456-472` |
| hold release | `listEvent{…, 10}` | `sysEvent{10, source}` | |
| scroll up/down | selection moves **locally** (up = previous item); only at a list boundary is `listEvent{…, 1|2}` sent | `sysEvent{1|2}` (source 0, elided) | `:473-481`, `:502-514` |

* A plain long-press never reaches the app (the shell reserves it for the system menu); tap-then-hold opens the window's context menu **and** is also reported to the app as LONG_PRESS (the window wraps `handleInput` for this) (`app/apps/evenhub/evenhub-window.ts:105-117`). Because EvenHub apps own double-click, the window menu is the guaranteed way out.
* `textEvent` is never generated (stock hardware reports clicks as `sysEvent`).
* **Lifecycle:** `FOREGROUND_ENTER` is deferred until the page exists, then sent once if the window is focused; afterwards shell foreground changes send ENTER/EXIT; close sends `SYSTEM_EXIT` best-effort before the WebView is destroyed 100 ms later (`session.ts:520-540`, `:810-827`, `:1182-1218`).
* **Context menu (SDK 0.0.14 `menuObject`):** the page's `menuItems` appear first in the window menu, followed by "Show phone UI"; choosing one pushes `evenHubEvent{type:"menuItemClickEvent", jsonData:{itemID}}` (`evenhub-window.ts:66-81`; `session.ts:493-500`).
* **IMU:** `sysEvent{eventType:8, imuData:{x,y,z}}` (`session.ts:693-699`).
* **Audio:** `evenHubEvent{type:"audioEvent", jsonData:{audioPcm: number[] (raw S16LE bytes of 16 kHz mono), direction:null, speakerRole:"unknown"}}` per decoded chunk (`:598-604`).

### C.7 Page model (`app/apps/evenhub/containers.ts`)

All reads are **loose**: exact key, else case-insensitive with underscores ignored (so `Container_ID` == `containerID`); numbers may arrive as strings (`:93-130`).

| Container | Fields (defaults 0/"" ) | Source |
|---|---|---|
| text (`textObject[]`) | `containerID, containerName, xPosition, yPosition, width, height, borderWidth, borderRadius, paddingLength, isEventCapture (≠0), zOrderIndex?, preserve (≠0), content, textColor? (int 0..4; out of range ⇒ default)` | `:132-163` |
| image (`imageObject[]`) | `containerID, containerName, xPosition, yPosition, width, height, zOrderIndex?, preserve`; pixels null until the first image update | `:204-219` |
| list (`listObject[]`) | `containerID, containerName, x/y/width/height, borderWidth, borderRadius, paddingLength, isEventCapture, zOrderIndex?, preserve, itemContainer.itemName[] (strings), itemContainer.itemWidth, itemContainer.isItemSelectBorderEn`; host-local `selectedIndex` starts at 0 | `:221-244` |
| menu (`menuObject.menuItems[]`) | `{itemName, itemID}`; max 10 items, `itemID` integer 1..2^32−1 and unique, `itemName` ≤ 32 UTF-8 bytes; offending entries dropped (not the page) | `:165-202` |

* Containers keep declaration order: lists, then images, then texts (`:246-270`). The **event-capture container** is the first non-image with `isEventCapture` (`:272-277`).
* Stock validation is only logged, not enforced: exactly one event-capture container; ≤ 8 text and ≤ 4 image containers; dropped menu items (`session.ts:839-854`).

### C.8 Rendering (`app/apps/evenhub/compositor.ts`)

* Canvas 576×288 (stock band, window height mode "medium"); the extended layout switches the window to "max" height, 576×452 (§C.11). In the 640-wide full-panel display mode the 576-wide content is centred (`app/apps/evenhub/evenhub-window.ts:20-35`, `:94-103`). Background black; shows "Loading <name>..." / "Could not run <name>..." until a page exists (`session.ts:420-432`).
* **Paint order:** if *any* container has `zOrderIndex`, sort ascending by it (missing = 0); else images first, then lists/texts in declaration order (`compositor.ts:133-147`). Before painting an image container, pending deferred text draws are baked so a later image covers earlier text (`:153-170`).
* **Text:** border first (full white regardless of text brightness; rounded if `borderRadius>0`, one outline per border pixel), then text wrapped at `width − 2·(borderWidth+paddingLength)` from `(x+inset, y+inset)`; brightness levels 0..4 → gray `[0, 64, 128, 191, 255]`, default 255 (`:26-56`). Font: Even's 20 px firmware UI font (glyph bitmaps extracted from the stock firmware during CFW preparation, not shipped; fallback: bundled Source Han Sans SC Light + Roboto for Latin), with widths/kerning/wrapping from `@evenrealities/pretext` so line breaks match what apps measure; unknown glyphs are skipped silently (`app/graphics/evenhub-font.ts:1-40`).
* **Image:** decoded 8-bit gray; if the bitmap is smaller than the container it is **tiled** (stock quirk) (`compositor.ts:78-91`).
* **List:** border; rows of `lineHeight + 4` px; the selected row gets a rounded (r = 8) outline sized to the item text width + 16 px (not full width), white when the window is focused else gray 130; the view scrolls to keep the selection visible; `itemWidth` horizontal layouts are **not implemented** (`:93-131`).

### C.9 Image payload decoding (`session.ts:1337-1530`)

Payload forms accepted: JSON number array, JSON-ified typed array (`{"0":…,"1":…}`), or base64 string (`:1439-1485`). Formats, detected by content:
1. **BMP** (`BM`): uncompressed `BI_RGB` only; 1/4/8-bit paletted (palette entries → luminance) and 24/32-bit BGR(A); top-down or bottom-up; ≤ 4096 px per side; rows 4-byte aligned (`:1337-1400`).
2. **PNG** (signature `89 50 4E 47`): UPNG decode → first frame RGBA → luminance `0.2126R + 0.7152G + 0.0722B`, multiplied by alpha (`:1500-1515`).
3. **Raw** sized to the container: `w·h` bytes = 8-bit gray; `ceil(w·h/2)` bytes = 4-bit packed, high nibble first, scaled ×17 (`:1516-1530`).
Anything else → result 1. (Real apps mix formats, e.g. EvenChess sends a 1-bit BMP for full refreshes and PNGs for moves.)

### C.10 Sensors and microphone routing

* **Microphone** (`app/apps/evenhub/mic-router.ts:1-93`): apps register intent with `audioControl(true)`; audio flows to at most one app — the requesting app that is foreground **and** screen on **and** no voice modal (`voiceActivity`) is active. The router runs the bridge's raw-PCM tap (§A.3) while such an app exists and forwards each decoded chunk as an `audioEvent`; STT captures pre-empt it; eligibility is re-evaluated on every foreground/screen/voice-activity change (self-healing). Ineligible apps simply receive nothing (matching stock). iOS: unsupported (`session.ts:613`).
* **IMU** (`app/apps/evenhub/imu-router.ts`): foreground-only (not screen-gated); one global report rate; readings forwarded as IMU sysEvents; stream is best-effort (firmware may not populate it).
* **Compass** (extension): foreground-only; readings carry raw heading, wearer-calibrated magnetic heading, calibration flag, and declination/true heading when the phone has a location (`app/apps/evenhub/compass-router.ts:1-60`).
* **Location**: see §C.5; subscription continues in the background.

### C.11 Faceclaw extension API (`window.getFaceclawExtensions()`)

Injected after the stock shim (`session.ts:195-293`; documented for app authors in `faceclaw-extensions/README.md`, types in `faceclaw-extensions/src/index.ts`). Absent in the stock app, so apps feature-detect. RPC: `postMessage("faceclawExt", JSON [method, ...params], id)`; id 0 = fire-and-forget; results via `window.__fcExtResolve(id, ok, value)`; events via `window.__fcExtEvent(name, data)` (`:973-989`, `:941-951`).

| Method | Behavior | Host line |
|---|---|---|
| `getVersion()` | `"Faceclaw/<version>"` (synchronous, from the injected script) | `webview.ts:79` |
| `returnToAppSwitcher()` | focus the sidebar; app keeps running | `session.ts:993-995` |
| `quit()` | real close (bypasses the shutDown(1) decline) | `:996-999` |
| `addWindowLifecycleListener(fn)` | events `visible`/`hidden` (foreground ∧ screen on) and `focused`/`blurred` (visible ∧ shell input focus, sampled at paint) | `:551-574`, `:278-290` (JS side) |
| `getConfiguredApiKeys()` | names of configured services among `openai, anthropic, soniox, elevenlabs, mapbox` (no values) | `:1295-1316` |
| `requestApiKeyAccess(services)` | glasses consent dialog listing requested services (marks unconfigured ones); Allow ⇒ map service→key value for configured ones; grants remembered for this app session only; Deny/double-click ⇒ `{}` | `:1132-1160`; `app/apps/evenhub/api-key-dialog.ts` |
| `playBuzzer(steps)` | `[{freq, ms, duty?}]` piezo sequence, chunked to the firmware's 48-step cap and paced by phrase duration | `:1163-1178` |
| `addCompassListener(fn)` | enables compass on first listener (`setCompass(true)`), disables after last; `compass` events | `:1024-1040`, `:238-250` (JS side) |
| `createLayout(layout)` / `replaceLayout(layout)` | same container shape as the stock page, but full **576×452** canvas, no container-count limit, and `preserve: true` inherits content (text / pixels / list items + clamped selection) from the same-named, same-kind container of the previous page | `:1047-1063`, `:1273-1292` |
| `setAssistantTools(tools)` | `[{name, description, parameters (JSON Schema), availability?: "open"\|"foreground" (default), handler}]`; handlers stay in the page; registered as `app.<packageId>.<name>` (§B.10) | `:263-277`, `:1067-1128` |

### C.12 Permissions and consent

* Consent dialog on install / first run of an uninstalled package when the manifest declares any permission or a privacy policy: lists each permission (label + app description or default detail), optional "Privacy policy" action (opens https HTML in an isolated bridge-free phone WebView, or downloads a PDF ≤ 10 MB to a native viewer), Allow / Cancel; the actions are inert until the whole list has been scrolled into view (`app/apps/evenhub/permission-dialog.ts:17-40`; `app/apps/evenhub/privacy-policy.ts:1-40`). After confirmation the running app is treated as having every declared permission; runtime checks only test *declared* permissions (mic, location) plus Android runtime permissions (`permissions.ts:1-15`).
* Store updates re-prompt only if the new manifest declares something not previously declared (`app/apps/evenhub/store-install.ts:35-95`) — but see the identity-comparison bug in §8.

### C.13 EvenHub store client (login, browse, download)

> This client talks to Even Realities' **private** storefront API by imitating the official Android app (static signing key, spoofed device profile). A rebuild should decide deliberately whether to include it (terms-of-service / legal risk). The signing key value lives at `app/apps/evenhub/even-api.ts:22` and is intentionally not reproduced here.

* Hosts: API `https://api.evenrealities.com`; public CDN `https://cdn-pub.evenhub.evenrealities.com` (`even-api.ts:20-21`). Request timeout 30 s; page size 40.
* **Request signing** (`even-api.ts:222-356`): headers `common`, `sign`, `Content-Type: application/json`, and `token` when signed in.
  * `common` = form-encoded `key=value&…` in a **fixed order** of an official-client device profile: `platform=16, package=com.even.sg, versionName=2.2.8, build=122, brand=Google, model=Pixel 7a, osVersion=16, carrier="", mcc=310, mnc=260, buildTime=26060821, appId=1001, v=<1 for login | 3 otherwise>, openUdid=<Android SSAID (ANDROID_ID)>, os=1, sn, verL, verR, ringSn, ringVer (empty), channel=googlePlay, sttLanguage="", sysLanguage=US, ts=<ISO now>, language=en, tzName=America/Los_Angeles, dateFmt=yyyy/MM/dd, timeFmt=24, unit=metrics, region=US` (`:275-321`; `app/apps/evenhub/even-platform.ts:12-17`).
  * `sign` = Base64(HMAC-SHA256(key, message)) where message = the lines `[METHOD, path, common, sorted query?, token?, body?]` **sorted lexicographically** and joined with `\n`; query pairs are form-encoded (space→`+`) and sorted by key (`:260-273`, `:323-344`).
* **Envelope** `{code, msg, data}`; `code 0` = success; HTTP 401 or `code 401` ⇒ authentication error (the stored token is dropped unless replaced meanwhile) (`:179-196`, `:358-372`).

| Operation | Request | Response use | Line |
|---|---|---|---|
| Login | `POST /v2/g/login` body `{email, passwd}` (common v=1) | `data.token`; saved if "Remember me", else kept in memory only; password never persisted | `:205-220`; `app/apps/evenhub/credentials.ts:99-121` |
| Top apps | `GET /v2/evenhub/leaderboard?page&page_size=40[&category]` | `{list, total, page, page_size}` | `:84-89` |
| New apps | `GET /v2/evenhub/ranking?sort_by=first_published_at&page&page_size` | paging unverified | `:97-102` |
| Search | `GET /v2/evenhub/search?q&page&page_size` | paging unverified | `:105-110` |
| Detail | `GET /v2/evenhub/app/detail?package_id&branch_name=public` | app record | `:112-121` |
| Download | `POST /v2/evenhub/app/download` body `{package_id, branch:"public"}` | `data.url` (https, pre-signed), `size`, `public_key` (unused), `privacy_link` (root or `meta.`; CDN-relative `prod/...` allowed) | `:124-152`, `:429-450` |
| Package fetch | `GET <url>` (60 s) | must match `size` and start with `EHPK` | `:132-141` |
| Icon | `GET https://cdn-pub.evenhub.evenrealities.com/prod/<path>.(svg|png|webp|jpg|jpeg)` | ≤ 1 MB | `:155-168` |

* App record fields parsed: `id, package_id, name, creator_name, tagline, description, category[], install_count, like_count, first_published_at|created_at, icon, version.{version, changelog, file_size} | latest_version` (`:393-414`); list arrays may be under `list|apps|items|data|results` or bare (`:380-391`).
* **Store window** (`app/apps/evenhub/store-layer.ts`): tabs Top / New / Search / Updates (`:43-50`); login pane asks the user to enter email/password in the phone app's text editor (phone keyboard), with a transient "Remember me" toggle (`:664-740`); search query typed on the phone (`evenhub.store.searchQuery`); detail page with Install / Update / Reinstall / Launch (`app/apps/evenhub/store-detail-layer.ts:181-188`).
* **Install pipeline** (`store-install.ts:35-82`): download EHPK and icon in parallel → read manifest → consent if needed → close running instances → validate & store (package id must match the requested one).
* **Updates** (`app/apps/evenhub/updates.ts:41-100`): for each installed app, fetch the store detail (4 concurrent); flag `updateAvailable` when the store version is newer by dotted-numeric comparison (non-numeric/suffix differences never count as newer) and `packageMissing` when the registry entry has no EHPK on disk (e.g., settings import after reinstall).

### C.14 Known incompatibilities and deviations (as implemented)

| Area | Deviation | Source |
|---|---|---|
| Quit | `shutDownPageContainer(exitMode=1)` does not quit; app is backgrounded to the switcher | `session.ts:904-913` |
| Device info | battery, wearing, serial, user info are static fakes | `session.ts:392-409`, `:774-797` |
| Media | album/camera pickers return `null` | `:800-802` |
| Audio events | `direction` always null, `speakerRole` "unknown"; audio only for foreground app with screen on and no voice modal; not on iOS | `:586-604`; `mic-router.ts` |
| Payload guesses | `audioControl`/`imuControl` field names unconfirmed; opaque payload ⇒ *enable* | `:1250-1266` |
| Lists | no `itemWidth` horizontal layouts; selection host-local | `compositor.ts:9-11` |
| Validation | stock limits (1 capture container, ≤8 text, ≤4 image) not enforced | `:839-854` |
| Events | `textEvent` never emitted; plain long-press never delivered; tap-then-hold both opens window menu and reaches the app | §C.6 |
| Network | manifest whitelist not enforced | `FaceclawEvenHubWebViewClient.kt:27-29` |
| Canvas | extended layouts use 576×452; stock apps 576×288 centred in 640-wide mode | §C.8 |
| Fonts | depend on glyphs extracted from stock firmware; fallback fonts differ visually | `evenhub-font.ts` |
| Timers | page `setTimeout`/rAF replaced by host-ticked queues (string callbacks unsupported, `this` is null, ~16 ms granularity) | `session.ts:117-192` |
| Store | private API; ranking/search paging unverified | §C.13 |

`notes/mentraos-compat-assessment.md` is a separate feasibility study (MentraOS cloud "TPA" apps vs the upcoming phone-local Miniapp SDK); it recommends targeting the Miniapp model later and **nothing from it is implemented**. It notes Faceclaw has no WebSocket server and would need one for cloud-SDK emulation (`:1-14`, `:95-133`).

---

## D. Remote input and terminal mirroring

### D.1 Local input API (port 8791)

**Purpose.** Let other programs — a CLI on a tailnet computer, an app on the same phone, a script over `adb forward` — act as an input device for the glasses: send ring/watch gestures, type text into the foreground window, or submit a query to the assistant (`scripts/faceclaw-input.md:1-20`).

**Tokens** (`app/remote/protocol.ts:21-61`; stored under settings key `remoteInput.tokens.v1`, `app/remote/service.ts:5-7`):

| Property | Value |
|---|---|
| Format | `fc1_` + 64 lowercase hex chars (32 bytes from `SecureRandom`) (`protocol.ts:38-40`; `native/.../net/RemoteInputSession.kt:132`) |
| Stored form | record `{id = first 16 hex of hash, name (1–80 chars), hash = lowercase hex SHA-256 of the full token string, permissions[], createdAt}`; the secret itself is shown once and never stored (`protocol.ts:33-45`; `RemoteInputSession.kt:231-232`) |
| Permissions | independent: `input` (gestures), `text` (type into foreground window), `assistant` (submit a query); editable later; empty = disabled |
| Limits | at most 32 tokens (`protocol.ts:37`) |
| Authentication | regex check, hash, then constant-time comparison against every stored hash (`:51-60`) |
| UI | Settings → API Keys → Input tokens: create (name + permissions → token shown with copy-to-clipboard), change permissions, revoke; listening-interface picker; connection info (`scripts/faceclaw-input.md:3-8`, `:22-43`; `app/native/remote-input.ts:16-19`) |

**Listening addresses** (`app/remote/listeners.ts:1-70`; `app/remote/service.ts:14-57`):
* The listener runs only while at least one token exists; it always binds `127.0.0.1:8791`, and never a wildcard, Wi-Fi, Ethernet, or cellular address.
* Interface selection (settings `remoteInput.interface`, default `tailscale`): `localhost` only; `tailscale` = addresses of tunnel interfaces identified as Tailscale (interface name `tailscale*` or an address in `fd7a:115c:a1e0::/48`) whose address is a tailnet address (IPv4 `100.64.0.0/10` or that IPv6 prefix); or `interface:<name>` for a specific tunnel. A tunnel interface is point-to-point or named `tailscale*`, `utun\d`, `tun\d`, `wg\d`, and never `wlan|wifi|wl|en\d|eth\d|rmnet|ccmni|pdp_ip|ap\d|p2p*`. Link-local and wildcard addresses are excluded (`listeners.ts:4-27`; `RemoteInputSession.kt:219-229`).
* One socket per address (a pool), so localhost keeps working while a VPN comes and goes; addresses re-reconciled every 3 s while running; status text lists the actual `address:port` set and warns when the tunnel is missing (`listeners.ts:36-70`; `service.ts:37-53`).
* Only numeric addresses assigned to a local interface are bound (no hostname resolution) (`RemoteInputSession.kt:80-95`, `:214-217`; `App_Resources/.../FaceclawRemoteInput.kt:67-89`).

**Transport** (`RemoteInputSession.kt:50-204`): TCP, **one request per connection**, one connection served at a time per address (backlog 8, `TCP_NODELAY`). Client sends one UTF-8 JSON object terminated by `\n` (≤ 64 KiB; invalid UTF-8 or overflow or a 5 s read stall closes the connection); the server replies with one JSON line and closes. The serving thread publishes the request as pending JSON `{id, expiresAt, body}`, notifies the JS side (main thread), and blocks until the JS reply or a 5 s deadline (reply `{"ok":false,"error":"timeout","message":"Faceclaw did not respond in time."}`). Request bodies and tokens are never logged (`:177-179`). JS side drains pending requests in a loop, skipping expired ones (`app/remote/service.ts:21-36`).

**Requests** (`app/remote/protocol.ts:63-99`; reference `scripts/faceclaw-input.md:132-162`):

```json
{"version":1,"token":"fc1_…","action":"input","gesture":"click","source":"watch"}
{"version":1,"token":"fc1_…","action":"text","text":"hello","submit":false}
{"version":1,"token":"fc1_…","action":"assistant","text":"What is on my calendar?"}
{"version":1,"token":"fc1_…","action":"ping","permission":"input"}
```

Validation order: JSON object → token authentic → `version == 1` and action ∈ {`ping`,`input`,`text`,`assistant`} → `ping` (optional `permission` check; needs no glasses) → `submit` is boolean if present → token has the action's permission → `input`: gesture ∈ {`click`, `double-click`, `long-press`, `short-then-long-press`, `scroll-up`, `scroll-down`, `swipe-up|down|left|right`}, source `watch` (default) or `ring` (ring cannot send swipes); text actions: 1–8000 UTF-16 units, not blank, no NUL → host ready (glasses connected or charging) → **text and assistant are refused while the glasses are locked**; gestures go through the normal lock/sleep path → dispatch.

Replies: `{"ok":true}` or `{"ok":false,"error":<code>,"message":…}` with codes `bad_request`, `unauthorized`, `forbidden`, `locked`, `unavailable` (not ready / foreground window doesn't accept text / assistant not configured), `failed`, `timeout`.

**Host actions** (`app/g2/dashboard-controller.ts:423-431`): `input` → the same synthetic-input path the Wear OS watch and phone mirror use (`injectSyntheticRingInput(gesture, source)`); `text` → wake the screen if off, `shell.sendTextToForegroundWindow(text, {submit})` (terminal windows append Enter unless `submit:false`); `assistant` → `shell.sendToAssistant(text)` (acknowledged when submitted, not when answered).

**CLI** (`scripts/faceclaw-input.cjs`, Node ≥ 18; docs `scripts/faceclaw-input.md:64-130`): commands `input <gesture> [--source]` (aliases tap, double-tap, up/down/left/right), `text [-n] <message>`, `assistant <message>`, `interactive` (arrows = watch swipes, Enter = tap, Esc = double-tap with a short disambiguation delay, Tab = long-press, `i`/`a` open a single-line composer for text/assistant); token from `FACECLAW_TOKEN` or `--token-file`; host/port via flags or `FACECLAW_HOST`/`FACECLAW_PORT` (default `127.0.0.1:8791`); 6.5 s client timeout; never auto-replays a possibly-delivered request; interactive mode validates with `ping` and re-checks connectivity while idle.

### D.2 Terminal mirroring client (g2mirror)

**Purpose.** The Terminal app mirrors CLI programs (e.g., Claude Code, Codex CLI) running on the user's computer under the external `g2mirror` wrapper/server, renders them on the glasses with a VT emulator, and sends keystrokes (including voice/assistant input). The server protocol document (`../experiments/g2mirror/PROTOCOL.md`) is not in this checkout; the following is the client's view (`app/native/g2mirror-client.ts`).

* **Connection string:** `g2mirror://<token>@<host>[:port]` (plain `ws`, default port 8737) or `g2mirrors://…` (TLS `wss`, default 443) (`app/apps/terminal/connections.ts:8-62`). One control client per configured connection (session lists, bell/title/activity notifications) plus one client per viewing window.
* **Client → server** (all JSON text frames): `init {version:1, auth_token, device, width: cols, height: rows}` on open (`:190`); `list`; `launch {command: <preset name>}` (presets only, never a command line); `connect {socket}`; `view`; `unview`; `disconnect`; `input {data: base64(UTF-8 bytes), delays?: [{at: byteOffset, ms}]}`; `history {before, limit: 200}`.
* **Server → client:** `init {server_name?}` (handshake accepted → list + 3 s periodic list refresh while not attached); `error {message}` (during handshake ⇒ rejected; messages starting "launch failed" settle the oldest pending launch); `sessions {sessions:[{socket, pid, cwd_hint, last_bell_at?, last_output_at?, title?}]}`; `bell {socket, last_bell_at}`; `activity {socket, last_output_at}` (≤ 1 per 2 s per terminal); `title {socket, title}`; `launched {socket}` (launches matched FIFO, 10 s timeout); `connect {command, history:{next, oldest}}` (attached); `snapshot {data: base64 VT bytes, history_next}` (reset-then-apply); `output {data: base64}`; `history_lines {start, oldest, next, lines:[{data: base64}]}` (ANSI stripped for display); `exit {status|null}`; `disconnected {reason}` (`:344-500`).
* **Submit:** `submitInput(text)` sends `text + "\r"` in one `input` with `delays:[{at: utf8Len(text), ms:150}]` so apps that detect pastes see Enter as a separate key (`:21`, `:327-339`). `sendInput` sends raw bytes.
* Phases `idle → connecting → connected → attached`, `failed`; the per-window client does not auto-reconnect (the Terminal worker manages reconnection of control connections) (`:40-47`, `:521-537`).
* Assistant tools exposed by the Terminal app are listed in §B.9.

---

## E. Other network integrations

| Integration | Provider & endpoints | Auth | Cadence / limits | Source |
|---|---|---|---|---|
| Weather | US National Weather Service: `GET https://api.weather.gov/points/{lat},{lon}` (4 decimals) → `properties.forecast`, `forecastHourly`, `observationStations`, `relativeLocation`; then forecast + hourly (optional) + `…/stations` → first station `…/observations/latest` | none; headers `Accept: application/geo+json`, `User-Agent: <Faceclaw UA> (https://github.com/jimrandomh/faceclaw)` | refresh every 30 min while the Weather app is open; 20 s timeout; ≤ 14 forecast periods; 404 on `/points` ⇒ "outside National Weather Service coverage" (US-only) | `app/native/weather.ts:89-97`, `:195-262` |
| Mapbox | Search Box forward geocoding `GET https://api.mapbox.com/search/searchbox/v1/forward?q&access_token&limit=5&language=en[&proximity=lon,lat]`; Directions `GET /directions/v5/mapbox/{driving|walking|cycling}/{lon,lat;lon,lat}?steps=true&geometries=geojson&overview=full&language=en`; Static Images `GET /styles/v1/mapbox/dark-v11/static/[path-6+ffffff-0.85(<polyline>)/]{lon,lat,zoom,bearing|auto}/{w}x{h}?logo=false&attribution=false&addlayer=<black background layer>&before_layer=tunnel-path-trail|building` (falls back layer-by-layer, then no override, if rejected) | public token (`pk.…`) in setting `maps.mapboxApiKey`, sent as `access_token` query param | 15 s timeout; used by the Navigate app (turn-by-turn, reroutes) | `app/native/mapbox.ts:13-36`, `:81-235` |
| Nightscout | user's own server: `GET {site}/api/v1/entries.json?count=30`, `/api/v1/devicestatus.json?count=1`, `/api/v1/status.json`, `/api/v1/treatments.json?find[created_at][$gte]=<now−3 h>`, and a Site Change query with `find[eventType]=Site Change&find[created_at][$gte]=<now−62 d>&count=1` (the v1 API silently limits undated queries to 4 days) | access token as `token=` query parameter (settings `integrations.nightscout.siteUrl`, `.apiToken`) | poll every 60 s; 2 h graph window | `app/native/nightscout-bridge.ts:107-115`, `:214-230` |
| Calendar | on-device Android `CalendarContract.Instances` (expands recurrences) between now and now+window, sorted by `BEGIN ASC`, limit `min(200, max)`; fields event id, title, begin, end, all-day, location, calendar display name | `READ_CALENDAR` permission | JS-side cache 30 s; default window 14 days / 50 events (assistant tool: 168 h / 10) | `App_Resources/.../FaceclawCalendarProvider.kt:15-96`; `app/native/calendar.ts` |
| Roam Research (assistant tools only) | via the Roam app worker (not analysed here) | `integrations.roam.graphName`, `.apiToken` | 14 s tool timeout | `app/ui/dashboard-settings.ts:837-849` |

Third-party data flows are summarized for users in `PRIVACY`.

---

## 7. Cross-cutting transport and threading notes

* **SSE/streaming HTTP** (`App_Resources/.../FaceclawSseRequest.kt`; shared parser `native/.../net/SseStream.kt`): POST with a JSON body and a flat header list; OkHttp connect timeout 15 s, read timeout 180 s (the model may pause), **redirects disabled** so API keys are never forwarded; response delivered line-by-line on the constructing thread's looper; non-2xx → `onHttpError(code, body ≤ 64 KiB)`; line limit 2 MiB, queue limits 8 MiB / 16 384 events (`FaceclawSseRequest.kt:156-176`; `SseStream.kt:172-175`). JS wrapper `app/native/sse.ts`.
* **WebSocket** (`App_Resources/.../FaceclawWebSocket.kt`): single constructor `(url, listener, headerName?, headerValue?)`; OkHttp with 20 s ping interval; text and binary sends return a boolean; `close()` falls back to `cancel()`; failures after a requested close are suppressed; callbacks on the constructing thread's looper (`:23-156`). Used by cloud STT, the agent bridge and g2mirror.
* **User-Agent**: every native request gets `HttpIdentity.userAgent` (pushed from the app version at startup; default "Faceclaw") unless one is set (`App_Resources/.../FaceclawHttp.kt:15-38`; `native/.../net/HttpIdentity.kt`).
* **Threads**: JS isolates are single-threaded; every native helper (voice controller, llama runner, model downloader, SSE, WebSocket, EvenHub bridge, remote input) marshals callbacks onto the looper of the thread that created it, which is the invariant a Kotlin rebuild should replace with coroutines/structured concurrency.

---

## 8. Design weaknesses / opportunities for a rebuild

### 8.1 Voice pipeline

1. **Microphone ownership is spread over three layers** (Kotlin `VoiceCaptureSession`, JS `FaceclawVoiceControlBridge` holder sets + suspended holders + raw tap, and the EvenHub `mic-router`), each with its own bookkeeping and "is it really live?" re-checks (`app/native/voice-control.ts:114-139`, `:422-474`; `app/apps/evenhub/mic-router.ts`). A rebuild should have one Kotlin `MicrophoneArbiter` service with explicit clients, priorities (assistant > transcription > EvenHub app), a single state machine that survives BLE session loss, and a flow of PCM frames (`SharedFlow`) to subscribers.
2. **Energy-only endpointing with magic numbers** (220 floor, ×3 start, ×1.8 continue, 900 ms silence, 6 s no-speech, 30 s cap) duplicated in Kotlin and TS (`VoiceEndpointDetector.kt:40-65`, `app/native/speech-pause.ts:1-44`). A neural VAD (e.g., the Silero VAD shipped with sherpa-onnx) would be more robust in noise and could also gate on-device decoding and cloud upload.
3. **Quadratic on-device partials**: Moonshine re-decodes the whole ≤8 s segment every 700 ms (`VoiceCaptureSession.kt:66-77`, `:535-552`); Whisper gives no live preview at all and its silence gate is an untested constant (`:86-92`). Streaming ASR models (sherpa-onnx online transducers) or VAD-chunked decoding would cut CPU and latency.
4. **Two nearly identical capture/finalize flows** (`VoiceInputLayer` with a 1200 ms trailing-final timeout vs `VoiceDraft` with 1500 ms) (`voice-input.ts:10`, `voice-draft.ts:95`) — unify into one "utterance capture" component with a single finalization policy.
5. **"Continue/refine" is hard-wired to Anthropic Sonnet** and hidden behind "Needs LLM API key" even when an OpenAI key or the local model is configured (`app/ui/shell/voice-input.ts:232-251`; `app/native/anthropic.ts:242-269`). Route it through the same provider abstraction as the assistant.
6. **Secrets in plaintext SharedPreferences** (STT/LLM/Mapbox/bridge/EvenHub tokens) (`App_Resources/.../FaceclawSettings.kt:24-26`); the ElevenLabs client logs the key length and first 4 characters (`app/native/elevenlabs-stt.ts:62-63`). Use Android Keystore-backed storage and redact logs.
7. **Per-frame JS work**: every 50 ms PCM chunk crosses into JS (Base64 for cloud, number arrays for EvenHub `audioEvent`) (`voice-control.ts:508-517`; `app/apps/evenhub/session.ts:598-604`). In Kotlin, keep audio native end-to-end; send compact encodings to WebViews.
8. Cloud providers' 16→24 kHz resampling uses linear interpolation (`openai-stt.ts:235-268`) — acceptable, but a proper polyphase resampler is cheap in Kotlin.
9. Wakeword depends on the stock classifier plus the CFW lease; there is no way to customize the wake phrase and no on-phone fallback.

### 8.2 Assistant / LLM

1. **Answers are tail-truncated with no scrolling** in the overlay (`app/ui/shell/input-dialog.ts:103-109`); long answers cannot be read on the glasses except in the AI Chat window. Use a scrollable/pageable document renderer (the existing `DocumentView` model is a good fit) and optional TTS through the phone.
2. **Screen sleep cancels an in-flight turn** because sleeping clears the layer stack and the overlay's removal cancels (`shell.ts:615-625`, `:1293-1299`). Decouple turn lifetime from overlay visibility; notify on completion.
3. **History bounded by message count (40), not tokens** (`app/assistant/session.ts:32-33`, `:233-243`), while every request carries ~33+ tool schemas. With the local model's 4096-token context the prompt alone is ~2–3k tokens (`app/prompts.ts:20-27`), so multi-turn local conversations quickly hit "Conversation too long". Add token counting, tool subsetting (relevance/embedding retrieval or per-app activation), and summarization.
4. **Hard-coded model catalogue** including a persisted typo key (`hauku`), fixed effort values, and `max_tokens 8192` (`app/assistant/models.ts:45-58`; `anthropic.ts:75`; `openai.ts:61`). Make the catalogue data-driven (remote config or a settings JSON), with migration for `hauku`.
5. **Conversation persistence as one growing JSON string** in SharedPreferences, including opaque OpenAI encrypted reasoning items, with no retention limit or delete UI (`app/assistant/conversations.ts:52-57`). Use Room with per-message rows, pruning, and export/delete.
6. **Tool registry concurrency gaps**: registry timeouts do not cancel the underlying call; EvenHub tool calls keep a pending promise until the app closes (`app/apps/evenhub/session.ts:1094-1128`); name collisions silently replace (`tool-registry.ts:163-165`). Add cancellation tokens, collision errors, and per-call ids end-to-end.
7. **The designed `installed` tier is simulated** by `always` wrappers with launch-and-poll (150 ms poll, 5 s) (`app/assistant/launch-on-call.ts:13-36`). A rebuild can make "installed" first-class with statically declared tools and an explicit "await app ready" handshake.
8. **Proactive gating is coarse**: 6 calls/min global, and the design's "never turn the screen on" rule is not implemented (`glasses.show_alert` wakes the display) (`mcp-server.ts:17`; `shell.ts:1380-1381`). Add per-tool budgets and a "quiet" policy.
9. **External bridge is plaintext `ws://`** with the bearer token in the first frame (`bridge-client.ts:158-171`); fine on a tailnet, risky elsewhere. Support `wss://` (optionally with certificate pinning), and put the token in the handshake.
10. MCP surface is minimal (no pagination, no tool annotations such as read-only/destructive hints, `list_changed` fired on every foreground flip) (`mcp-server.ts:41-85`). Debounce change notifications.
11. **Design doc drift**: the notes describe `glasses.list_windows` / `glasses.open_app` etc., while the code uses `apps.*`; the doc says the overlay is in `app/ui/apps/…` (`notes/voice-assistant-design.md:198-212`, `:383-396`). Keep a single authoritative tool manifest.
12. The Anthropic client passes neutral messages through verbatim (relies on the neutral format equalling Anthropic's) and the OpenAI client drops `provider_item` fidelity when switching models by rebuilding text-only history — both reasonable, but a rebuild should model provider continuation state explicitly.
13. Local inference: fixed 4 threads, CPU-only, and a 2.5 GB download to external storage with no free-space check; consider NNAPI/GPU backends where stable and a storage precheck.

### 8.3 EvenHub

1. **Permission re-prompt bug**: `alreadyGranted` builds a `Set` of permission *objects* and tests freshly parsed objects with `has()`, which compares identity — so any update of an app that declares permissions always re-prompts; `permissionsSignature()` exists but is unused (`app/apps/evenhub/store-install.ts:90-95`; `app/apps/evenhub/permissions.ts:152-160`).
2. **Network whitelist not enforced**, and `requestApiKeyAccess` hands raw provider keys to third-party web code (with consent) (`FaceclawEvenHubWebViewClient.kt:27-29`; `session.ts:1132-1160`). Consider enforcing the whitelist via request interception and offering *proxied* capabilities instead of raw keys.
3. **Private store API impersonation** (static HMAC key, spoofed official-app/device profile, device `ANDROID_ID` sent as `openUdid`) is brittle and legally sensitive (`app/apps/evenhub/even-api.ts:20-24`, `:275-321`). Keep it optional/pluggable or omit it; sideloading `.ehpk` and dev URLs cover the open ecosystem.
4. **EHPK integrity is not verified** (SHA-512 trailer skipped; the download's `public_key` unused) (`app/apps/evenhub/ehpk.ts:1-11` header comment; `even-api.ts:57-63`). Verify at least the trailer.
5. **Always-on 60 Hz `evaluateJavascript` ticking** of every running app plus "always visible" renderer tricks cost battery even when an app is hidden and the screen is off (`FaceclawEvenHubWebViewHost.kt:62-83`). Tick adaptively (next-due timer, foreground/visibility), and give hidden apps a reduced rate.
6. **Timer shim semantics differ from the web platform** (no string callbacks, `this` = null, no minimum-delay clamping, coarse 16 ms granularity) (`session.ts:111-192`).
7. **Heavy decoding on the UI isolate** (PNG via UPNG, BMP, base64 in JS) (`session.ts:1337-1530`); move to Kotlin (`BitmapFactory` + gray conversion) off the main thread.
8. **Re-unpacking the whole package on every launch** (`app/apps/evenhub/manager.ts:62-90`) — cache the unpacked tree keyed by package version/hash.
9. Static fake device/user info (battery 100 %, always worn) (`session.ts:392-409`, `:774-797`) — real values are available on the phone.
10. The compositor re-implements firmware rendering heuristically (list layout, fonts extracted from firmware); a rebuild should keep a golden-image test corpus from real apps (existing test: `tests/evenhub-containers.test.cjs`).

### 8.4 Remote input and terminal

1. One request per TCP connection and one connection at a time per address is simple and safe but adds latency for interactive use; a persistent authenticated channel (still line-delimited JSON) would help the interactive CLI.
2. g2mirror defaults to plaintext `ws://` with the token in the `init` frame (`app/apps/terminal/connections.ts:8-62`; `g2mirror-client.ts:185-195`).

### 8.5 Other integrations

1. Weather is **US-only** (NWS) with no fallback provider (`app/native/weather.ts:244-246`); Open-Meteo or similar would cover other regions.
2. Nightscout token travels in URL query strings (logged by proxies) (`nightscout-bridge.ts:214`); prefer the `api-secret`/Bearer header forms Nightscout supports.
3. Mapbox token also in query strings (required by Mapbox) — acceptable for public `pk.` tokens only; warn if a secret `sk.` token is entered.

---

## 9. Open questions

1. **Mic packet layout.** Faceclaw (`Lc3PacketFramer.kt:22-38`: 5×40-byte LC3 frames + SSR + DoA + counter per 205-byte packet, 50 ms) vs g2-kit (`g2kit:ble/docs/audio.md:36-53`: 2-byte header + 203-byte frame, 20 ms). Is the difference firmware-version or CFW dependent, or is the g2-kit doc simply wrong? Verify with captures on the target firmware.
2. **EvenHub SDK payload names** for `audioControl` and `imuControl` are guessed (`session.ts:1250-1266`); the exact `even_hub_sdk` 0.0.12+/0.0.14 wire shapes should be confirmed from the SDK source or traffic captures, along with whether stock ever emits `textEvent`.
3. **Stock-app behaviors emulated from observation** (2 s block then failure on a duplicate `createStartUpPageContainer`; `textContainerUpgrade` truncation semantics; image tiling; z-order defaults) — need a reference capture set to validate.
4. **Provider model ids and parameters** (`claude-sonnet-5`, `claude-opus-5`, `claude-fable-5`, `claude-haiku-4-5`, `gpt-5.6-luna|terra|sol`, Anthropic `output_config.effort`, OpenAI `reasoning.effort`, realtime STT model `gpt-realtime-whisper`, ElevenLabs `scribe_v2_realtime`, Soniox `stt-rt-v5`) are whatever the code sends at this commit; they must be re-validated against current provider documentation when implementing.
5. **OpenClaw bridge server side** (repo `faceclaw-agent-bridge`, not available here): ping cadence, close codes after `ctl error`, whether `text-delta.replace` is actually used, the exact `hello-ack` fields, and how `glasses_list_tools` / `glasses_call` map to the phone's MCP calls.
6. **g2mirror server protocol** (`PROTOCOL.md` not in this checkout): semantics of `view`/`unview`, `delays`, history indices, and launch-grant authorization are inferred from the client only.
7. **Wake lease details** (CLAIM/READY nonces, what happens if the phone dies holding a lease — the code calls the policy "fail-open") belong to the BLE/CFW spec; this subsystem only needs "wakeword arrives as sid 0x07 status 1 and the stock assistant stays suppressed while the lease is held".
8. **Missing design note** `notes/evenhub_compatibility.txt` is referenced by several comments but absent from the checkout.
9. **Intended behavior when the screen sleeps mid-turn** (currently cancels) and whether follow-up conversations should continue hands-free without a new wakeword (listed as an open question in `notes/voice-assistant-design.md:450-465`).
10. **EvenHub store paging**: whether `/v2/evenhub/ranking` and `/v2/evenhub/search` page at all, and the meaning of `public_key` in download metadata (`even-api.ts:91-110`, `:57-63`).
11. **Whisper silence gate** threshold (0.01 peak) and Moonshine segment length were never validated on hardware per their own comments (`VoiceCaptureSession.kt:86-92`).
12. Whether the rebuild should keep iOS-motivated abstractions (shared KMP code) or go Android-only; much of `native/kotlin/shared` exists to serve an iOS port.

---

## Appendix A — Tests that pin behavior (useful as acceptance references)

| Area | Test file |
|---|---|
| Endpoint detector | `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/VoiceEndpointDetectorTest.kt` |
| Capture session (segmentation, verification ordering, stats) | `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/VoiceCaptureSessionTest.kt`, `AudioHelpersTest.kt`, `AudioRecordingTest.kt` |
| LC3 framing / duplicate filter | `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/Lc3PacketFramerTest.kt` |
| Resumable model download | `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/ResumableDownloadTest.kt` |
| Voice dialog finalization / auto-send / continue | `tests/voice-finalization.test.cjs` |
| Cloud STT wrappers | `tests/cloud-stt.test.cjs` |
| AI Chat | `tests/ai-chat.test.cjs` |
| EvenHub containers / asset server / sign-in / install perf | `tests/evenhub-containers.test.cjs`, `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/EvenHubAssetServerTest.kt`, `tests/evenhub-signin.test.cjs`, `tests/evenhub-install-performance.test.cjs` |
| Remote input protocol / listeners / service / compose | `tests/remote-input.test.cjs`, `tests/remote-listeners.test.cjs`, `tests/remote-service.test.cjs`, `tests/remote-text.test.cjs`, `tests/remote-input-compose.test.cjs`, `tests/kotlin/src/commonTest/kotlin/com/faceclaw/app/RemoteInputSessionTest.kt` |
| Nightscout | `tests/nightscout.test.cjs` |
| Terminal activity/header | `tests/terminal-activity.test.cjs`, `tests/terminal-header.test.cjs` |
| Timer model (assistant timer tools) | `tests/timer-model.test.cjs` |

## Appendix B — Settings keys touched by these subsystems

| Key | Subsystem | Notes |
|---|---|---|
| `voice.enabled`, `voice.provider`, `voice.wakeWordAction`, `voice.elevenLabsApiKey`, `voice.openAiApiKey`, `voice.sonioxApiKey` | A | see §A.14 |
| `developer.saveVoiceRecordings`, `developer.suspendEvenHubWhenScreenOff` | A | recordings; wake lease condition |
| `microphones.wearer-commands-only` | A | speaker verification |
| `assistant.model`, `assistant.backend`, `assistant.bridgeHost`, `assistant.bridgePort`, `assistant.bridgeToken`, `assistant.allowProactive`, `assistant.skipConfirmationAfterWakeword`, `assistant.conversations`, `llm.anthropicApiKey` | B | `app/ui/dashboard-settings.ts:631-758`; conversations JSON §B.11 |
| `evenhub.installedApps.v1`, `evenhub:<pkg>:ls:<key>`, `evenhub.store.searchQuery`, `integrations.evenhub.email`, `integrations.evenhub.token` | C | registry, app localStorage, store (`app/apps/evenhub/credentials.ts:12-28`) |
| `remoteInput.tokens.v1`, `remoteInput.interface` | D | token hashes; interface choice |
| `terminal.newConnectionDraft` (+ Terminal connection list) | D | g2mirror connection strings |
| `maps.mapboxApiKey`, `integrations.nightscout.siteUrl`, `integrations.nightscout.apiToken`, `integrations.roam.graphName`, `integrations.roam.apiToken` | E | third-party credentials |
