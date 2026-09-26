# Architektur

## Überblick

```
┌──────────────────────────── Android-App (app/) ───────────────────────────────┐
│  Compose-UI (Vorschau/Touchpad, Kopplung, Einstellungen)                        │
│  GlassesService (Vordergrunddienst) · ConnectionManager                         │
│  Datenquellen: Benachrichtigungen, Medien, Kalender, Wetter, Akku               │
│  AndroidBleLink (BluetoothGatt, beide Bügel)                                    │
└───────────────┬───────────────────────────────────────────────┬─────────────────┘
                │ Ports (Interfaces)                            │ BleLink
┌───────────────▼────────────────── core/ (reines Kotlin) ──────▼─────────────────┐
│  runtime/  GlassesRuntime + AppCatalog: verdrahtet alles                         │
│  shell/    Fenster-Manager: Apps, Bildschirm-Stapel, Statuszeile, Overlays,      │
│            Display-Energie, Eingabe-Routing, Frame-Erzeugung                     │
│  apps/     Home, Launcher, Benachrichtigungen, Musik, Timer, Agenda, Wetter,     │
│            Kompass, Teleprompter, Einstellungen                                  │
│  ui/       Design-Tokens (Theme), Eingabemodell, Animation, MenuList, TextPager  │
│  gfx/      Software-Rasterizer (8-Bit-Grau, Kantenglättung), Bitmap-Schriften,   │
│            Icons, PNG                                                            │
│  protocol/ G2-Protokoll: Envelope + CRC, Custom-Firmware-Transport (Deflate),    │
│            Zeichenbefehle, Events, GlassesSession, FakeGlasses                   │
│  platform/ Ports, typisierte Einstellungen, Demo-Datenquellen                    │
└──────────────────────────────────────────────────────────────────────────────────┘
        ▲ gleiche Kernbibliothek
┌───────┴──────── simulator/ ────────┐
│ Swing-Fenster, Tastatur als Ring,   │
│ Demo-Daten, Screenshot-Tour         │
└─────────────────────────────────────┘
```

Der Kern kennt Android nicht. Alles, was er von außen braucht (Benachrichtigungen, Medien, Termine,
Wetter, Sensoren, Brillen-Steuerung, Bluetooth), läuft über schmale Interfaces in
`platform/Ports.kt` und `protocol/BleLink.kt`. Dadurch laufen dieselben Apps auf dem Handy, im
Desktop-Simulator und in den Tests.

## Vom Zustand zum Bild auf der Brille

1. **Rendern.** Die Shell zeichnet bei Bedarf (Eingabe, Datenänderung, Animation, Uhrzeit) einen
   kompletten Frame in ein 640 × 480-Graustufenbild: Statuszeile, den obersten Bildschirm der
   Vordergrund-App und Overlays. Alles wird mit dem eigenen Rasterizer gezeichnet
   (`gfx/Canvas.kt`, Formen über Signed-Distance-Functions geglättet, Text aus vorgebackenen
   Schriften). Es wird nur ein Frame weitergegeben, wenn sich Pixel geändert haben.
2. **Kodieren.** `protocol/FrameEncoder` quantisiert auf die 16 Graustufen des Displays, vergleicht
   mit dem zuletzt gesendeten Frame und erzeugt pro geändertem 32-Zeilen-Band ein RLE-Rechteck
   („bbox“-Zeichenbefehl der Custom-Firmware).
3. **Übertragen.** `GlassesSession` packt die Zeichenbefehle als Custom-Firmware-Nachrichten (sid 0xF0,
   Deflate mit persistentem Kontext), schickt sie an den linken Bügel (der sie an den rechten
   weiterreicht), hält höchstens drei Nachrichten gleichzeitig „im Flug“, wiederholt bei NACK oder
   Timeout (Go-Back-N) und schließt jedes Bild mit PRESENT ab.
4. **Eingabe.** Gesten kommen als EvenHub-Events vom rechten Bügel zurück, werden dekodiert
   (inkl. Ring-Entprellung), in `InputEvent`s übersetzt und von der Shell an Overlays bzw. den
   aktiven Bildschirm verteilt (Gesten → Aktionen: Previous/Next/Select/Back/Menu/Quick).

## Verbindungsablauf (GlassesSession)

1. Beide Bügel verbinden (MTU 512, 2M-PHY, Benachrichtigungen an), 800 ms warten.
2. Authentifizierung nacheinander rechts/links; ohne Bonding wartet die App bis zu 90 s auf den
   Android-Kopplungsdialog.
3. Prelude (sid 0x01), dann Einstellungen lesen: nur mit `Faceclaw/<n>`, n ≥ 34, geht es weiter.
4. Framebuffer-Lease auf beiden Bügeln (alle 45 s erneuert), Eingabeseite anlegen, Heartbeats,
   Helligkeit, erstes Bild (Keyframe), danach nur Änderungen.
5. Bei Verbindungsverlust: Neuaufbau mit wachsendem Abstand (2 s … 30 s), anschließend Keyframe.
6. Beim Trennen: CLEANUP senden, damit die Brille sofort wieder die Stock-Oberfläche zeigt.

Alle Protokolldetails mit Quellenangaben: [`docs/analysis/`](analysis/).

## Threading

- Shell und Apps laufen auf **einem** logischen Thread (`limitedParallelism(1)`); App-Code muss nie
  synchronisieren. Andere Threads sprechen die Shell über `dispatch()`/`post()` an.
- Die Session hat ihren eigenen sequentiellen Dispatcher; GATT-Rückrufe werden dorthin gereicht.
- `AndroidBleLink` serialisiert alle GATT-Operationen beider Bügel mit einem Mutex, damit sich
  Fragmente verschiedener Nachrichten nie vermischen.

## Tests

- **Protokoll-Vektoren:** Envelope, CRC, Custom-Firmware-Pakete, Kompression, Zeichenbefehle,
  ACK-Parser, Events und Advertisements werden byte-genau gegen die in der Analyse dokumentierten
  Beispiele geprüft.
- **Ende-zu-Ende mit simulierter Brille:** `FakeGlasses` implementiert die Brillenseite (Auth,
  Prelude, Settings, Lease, Seite, Heartbeat, Kompression, Zeichnen, ACK/NACK, Bonding). Die Tests
  prüfen mit virtueller Zeit u. a. pixelgenaue Bildübertragung, Gesten, Heartbeats,
  NACK-Wiederholung, verlorene ACKs, Reconnect, Erstkopplung und die Ablehnung falscher Firmware.
- **Visuell:** `./gradlew :simulator:screenshots` rendert eine Tour durch alle Bildschirme.

## Design-System

`ui/Theme.kt` enthält alle Entscheidungen: Helligkeitsstufen (Vielfache von 17 → exakt die 16
Stufen des Panels), Schriftrollen (Inter in mehreren Schnitten, JetBrains Mono, Material Icons),
Abstände, Radien und Animationsdauern. Schwarz ist auf der Brille durchsichtig, darum arbeitet
das Design mit wenig Fläche, Umrissen und Helligkeit statt Farbe; die Auswahl ist eine gleitende
Kapsel mit Kontur.

## Eine App anlegen

1. Klasse von `GlassApp` ableiten und in `createRootScreen()` einen `Screen` liefern.
2. `render(g, bounds)` zeichnet, `onAction(action)` reagiert auf Eingaben, `menuItems()` ergänzt das
   Halten-Menü, `refreshIntervalMs`/`keepAwake`/`fullscreen` steuern Verhalten.
3. Über `ui` stehen Navigation (`push`, `pop`, `openApp`), Rückmeldungen (`toast`, `confirm`,
   `showMenu`), Daten (`ui.services`) und ein Coroutine-Scope (`ui.scope`) bereit.
4. In `AppCatalog.build()` registrieren; optional einen `GlanceProvider` für den Startbildschirm.
