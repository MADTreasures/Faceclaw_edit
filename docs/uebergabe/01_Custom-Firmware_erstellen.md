# Custom-Firmware für die Even G2 erstellen

Alles, was ich beim Studium von **Faceclaw** (App) und **g2flash** (Firmware-Werkzeug und Patches)
über die Custom-Firmware herausgefunden und selbst überprüft habe. Stand: 27.09.2026.
Detaillierte Spezifikationen mit Quellenangaben liegen in `wissen/` (englisch), vor allem
`wissen/06-firmware.md` und `wissen/02-display.md`.

---

## 1. Das Wichtigste in Kürze

- Die Custom-Firmware (CFW) ist **Evens Original-Firmware 2.3.0.24 plus Binär-Patches**. Es gibt keinen
  eigenen Quelltext der ganzen Firmware: g2flash hängt eigenen, in C geschriebenen Code an das
  Hauptprogramm an und ändert an 31 Stellen einzelne Befehle – meist werden Sprungbefehle (`bl`) auf
  den neuen Code umgebogen.
- Das fertige Image wird **nie verteilt**, sondern jedes Mal selbst gebaut:
  Original-Image von Evens Server laden → SHA-256 prüfen → Patch-Set anwenden → SHA-256 des Ergebnisses
  prüfen. Das Patch-Set ist eine JSON-Liste „an Stelle X stehen die Bytes A, ersetze durch B“.
- Die CFW meldet sich in der Einstellungs-Antwort der Brille (Protobuf-Feld 100) mit `Faceclaw/34`.
  Daran erkennt die App, dass sie mit der Brille „Custom“ sprechen darf.
- **Arbeitsteilung:** Die Brille zeigt an, meldet Eingaben und Sensoren. Alles, was auf dem Display
  erscheint (Menüs, Texte, Apps), berechnet heute der Begleiter (Handy-App, bei dir die Uhr) und schickt
  es als Bild-/Zeichenbefehle. Menüs aus dem Designer werden deshalb normalerweise **App-Code**, nicht
  Firmware – siehe Abschnitt 6.
- **Platz ist knapp:** Unter der sicheren Speichergrenze sind bei Faceclaw/34 nur noch
  **70.976 Bytes** für weiteren Firmware-Code frei.

## 2. Die Bausteine

### 2.1 Original-Firmware (Basis)

| | |
|---|---|
| Version | 2.3.0.24 |
| Download | `https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin` |
| Größe | 4.537.963 Bytes |
| SHA-256 | `187ccf2bcc5c17a212106e8a376745511e8289c4232b634a7ea94b9bf25a0979` |
| MD5 | `1dbdf37b03a1169c384945e94d671371` – der Dateiname auf dem Server ist der MD5 (von mir geprüft) |

### 2.2 Custom-Firmware Faceclaw/34 (Ergebnis)

| | |
|---|---|
| Größe | 4.607.691 Bytes |
| SHA-256 | `7d8764f8b720252354dcd1695d23578b11f9b7cf5df9895d08b9f6668e9d0ee7` |
| Patch-Set | `firmware/cfw_patches.json` (38 Einträge, aus g2flash, Stand 25.09.2026, GPLv3) |
| Kennung | `Faceclaw/34` in `patches/settings_ext.c`: `static const char caps[] = "Faceclaw/34";` |

Selbst nachgebaut und geprüft: mit dem Kotlin-Code der App und mit `firmware/cfw_bauen.py`
(beide unabhängig von g2flash geschrieben) – Ergebnis bitgenau gleicher SHA-256.

### 2.3 Aufbau des Images (EVENOTA-Container)

```
0x00  "EVENOTA\0"                      Kennung
0x08  u32 Anzahl Komponenten (5 oder 6)
0x40  Inhaltsverzeichnis: je 16 Bytes  [id, Offset, Größe = ps+128, CRC-32C]
      danach je Komponente: 128 Bytes Kopf [ps @+8, CRC-32C @+12, Name @+48] + ps Bytes Nutzdaten
```

Nur Kopf und Nutzdaten jeder Komponente gehen an die Brille; Header und Inhaltsverzeichnis dienen nur
zum Prüfen. Die sechs Komponenten von 2.3.0.24 (in dieser Reihenfolge wird geflasht):

| # | Komponente | Bytes | 4-KB-Blöcke |
|---|---|---|---|
| 0 | `firmware/codec.bin` | 326.092 | 80 |
| 1 | `firmware/ble_em9305.bin` (Bluetooth-Chip) | 211.948 | 52 |
| 2 | `firmware/touch.bin` | 34.720 | 9 |
| 3 | `firmware/box.bin` (vermutlich das Ladeetui) | 55.784 | 14 |
| 4 | `ota/s200_bootloader.bin` (**Bootloader**) | 149.755 | 37 |
| 5 | `ota/s200_firmware_ota.bin` (Hauptprogramm) | 3.758.720 (CFW: 3.828.448) | 918 (CFW: 935) |

Die CFW ändert **nur** Komponente 5; 0–4 bleiben bytegleich.

### 2.4 Prüfsummen – genau so, sonst verweigert die Brille

- **Komponenten-Prüfsumme:** CRC-32C-Polynom `0x1EDC6F41`, aber **MSB-first, Startwert 0, kein
  End-XOR** (nicht die übliche iSCSI-Variante!). Prüfwert für `"123456789"`: `0xC052A8C8`.
  Steht im Inhaltsverzeichnis (+12) **und** im Komponentenkopf (+12).
- **Hauptprogramm-Vorspann (erste 32 Bytes):** `[0]` Länge (untere 24 Bit = ps, oberes Byte 0x04
  beibehalten), `[4]` zlib-CRC-32 über `payload[8:ps]`, `[0x14]` Ladeadresse, muss `0x00438000` sein.
- **Reihenfolge nach Änderungen:** Größen anpassen (ps, Inhaltsverzeichnis-Größe, Vorspann-Länge) →
  Vorspann-CRC-32 → Komponenten-CRC-32C.

### 2.5 Die Speichergrenze – die wichtigste Regel

Der Bootloader schreibt das Hauptprogramm ab `0x00438000` in den internen Speicher (MRAM), **ohne jede
Grenzprüfung**. Ab `0x007FE000` liegt das OTA-Flag, darunter die Kopplungs-/Einstellungsdaten, bei
`0x00800000` ist Schluss. Läuft das Programm darüber, ist das Glas im schlimmsten Fall nur noch per
Hardware-Debugger (SWD) zu retten. Deshalb gilt: `0x438000 + ps − 0x20 ≤ 0x7F0000`.

| | Programmende | Frei bis zur Grenze |
|---|---|---|
| Original 2.3.0.24 | `0x7CDA60` | 140.704 Bytes |
| Faceclaw/34 | `0x7DEAC0` | **70.976 Bytes** |

### 2.6 Was die Patches tun (38 Einträge)

| Einträge | Zweck |
|---|---|
| #0, #6–9 | Ring-Eingaben und das private Übertragungsprotokoll (sid `0xF0`) werden abgefangen, bevor die Original-Firmware sie verarbeitet |
| #1–5 | Weiterleitung von iPhone-Benachrichtigungen (ANCS) |
| #10–12 | Bluetooth schneller: LE 2M, 7,5-ms-Verbindungsintervall, kein automatischer Langsam-Modus |
| #13 | 1 KiB Arbeitsspeicher für den CFW-Kontext reserviert |
| #14–15 | Einstellungs-Kanal: Feld 100 (`Faceclaw/34`) anhängen, Feld 101 (Lease/Steuerung) auswerten |
| #16–18 | Aufwecken des Displays und Kopfheben übernehmen (nur mit aktiver Lease) |
| #19–22 | Gesten (Ring gedrückt, lang, kurz-dann-lang, loslassen) als eigene Ereignisse |
| #23 | Evens KI-Anzeige unterdrücken, solange die App die Brille besitzt |
| #24–25 | **640×480-Vollbild**: eigener Bildpuffer wird direkt ins Display kopiert |
| #26–27 | Tragen/Absetzen-Ereignisse |
| #28–30 | Kompass (Magnetometer) |
| #31 | der angehängte C-Code (69.728 Bytes) |
| #32–37 | Größen und Prüfsummen des Hauptprogramms nachgeführt |

Funktionen der CFW insgesamt (laut g2flash): volle 640×480-Auflösung, Display-Listen (Zeichenprogramme,
die die Brille selbst abspielt, auch animiert), komprimierte Übertragung, schnelleres Bluetooth, Gesten
ohne Evens Beenden-Menü, Summer/Töne, Tragen-Erkennung, Kompass, Umgebungslichtsensor, Mikrofon-Stream,
Übernahme von Wake-Word und Display-Wecken, Eingaben im Schlafmodus.

## 3. Wie g2flash die Custom-Firmware baut (Werkzeugkette)

Repository: https://github.com/jimrandomh/g2flash (GPLv3)

```
patches/*.c  ──clang (thumbv7em, -fropi)──►  ein Code-Block  ──patch_compress.py──►  Hook-Stellen + Anhang
                         build.py prüft: keine externen Adressen            + Größen + Prüfsummen + MRAM-Check
                                                                              │
audit_stock.py + stock_abi_230.json: jede feste Firmware-Adresse geprüft ───┘
                                                                              ▼
                                         gen_patches.py  ──►  patches/cfw_patches.json  (committet)
                                                                              │
                   build_cfw.sh / apply_patches.py (ohne Compiler)  ◄─────────┘
                   Original laden → SHA prüfen → Patches → SHA prüfen → g2_2.3.0.24_cfw.bin
```

- **C-Quellen** (`patches/`): alle werden über `patches_main.c` als **eine** Übersetzungseinheit gebaut.
  Wichtige Dateien: `message_transport.c` (privates Protokoll mit Quittungen), `zlib_glue.c`
  (Dekompression, Nachrichten-Verteiler), `display_list.c`, `draw.c`, `texture_draw.c`,
  `resource_cache.c` (Zeichnen), `settings_ext.c` (Feld 100/101/102, Leases), `gesture_fwd.c`,
  `compass.c`, `als_sensor.c`, `mic_control.c`, `brightness.c`, `panel.c`, `ancs_relay.c`.
- **build.py**: kompiliert positionsunabhängigen Thumb-2-Code (Cortex-M, ARMv7E-M) und ist ein
  Mini-Linker. Erlaubt nur Sprünge innerhalb des Blocks und PC-relative Konstanten. Funktionen der
  Original-Firmware werden über **feste Adressen** aufgerufen, z. B.
  `#define CFW_STOCK_RECEIVE ((uint32_t (*)(uint8_t, const uint8_t *, uint16_t))0x004cf471u)`.
- **patch_compress.py**: hängt den Block ans Ende des Hauptprogramms (landet im MRAM direkt hinter dem
  Original), schreibt die umgebogenen `bl`-Befehle, passt Größen und Prüfsummen an und bricht ab, wenn
  die Speichergrenze überschritten würde.
- **audit_stock.py** + `stock_abi_230.json`: geprüfte Liste aller festen Adressen, die der C-Code
  benutzt. Taucht eine neue Adresse auf, bricht der Build ab, bis sie geprüft ist (z. B. in Ghidra).
- **gen_patches.py** schreibt das Patch-Set; **apply_patches.py** / `build_cfw.sh` spielen es ohne
  Compiler wieder ein. Weil das JSON committet ist, braucht man zum *Bauen* keinen Compiler, nur zum
  *Ändern*.

Befehle:

```bash
git clone https://github.com/jimrandomh/g2flash && cd g2flash
./build_cfw.sh                    # venv, Original laden, Patches anwenden, beide SHA-256 prüfen
# nach Änderungen an patches/*.c (braucht clang mit Ziel thumbv7em-none-eabi):
./build_cfw.sh --update-patches   # erzeugt patches/cfw_patches.json neu
# danach OUT_SHA256 in build_cfw.sh auf den neuen Wert setzen und committen
python3 g2flash.py --recompute-checksums BILD.bin   # Prüfsummen nach eigenem Binär-Patch reparieren
```

Ohne g2flash geht das Bauen und Prüfen auch mit dem Skript aus diesem Paket (im Repo:
`scripts/cfw_build.py`):

```bash
python3 firmware/cfw_bauen.py                 # lädt das Original, baut und prüft Faceclaw/34
python3 firmware/cfw_bauen.py --check BILD    # prüft ein beliebiges Image (inkl. Speichergrenze)
```

## 4. Wie App und CFW miteinander sprechen (Kurzfassung)

| Kanal | Inhalt |
|---|---|
| Einstellungen (sid `0x09`), Feld 100 | Kennung `Faceclaw/<n>` |
| Feld 101 | Steuerung `['F','C',1,op,nonce]`: 1/2 Wake-Lease, 3/4 Wake-Handshake, 5/6 **Framebuffer-Lease** (90 s, alle 45 s erneuern), 7 Tragen-Status |
| Feld 102 | Ereignisse der Brille (Doppeltipp/Kopfheben im Schlaf, Gesten im Schlafmodus) |
| Felder 103–105 | Mikrofon-Konfiguration/-Stream, Umgebungslicht |
| sid `0xF0` | privates Protokoll: Datensätze mit CRC, Deflate-komprimiert, Quittung pro Glas, Fenster 3 |
| Nachrichtentypen | 26 Zeichenbefehle, 28 Anzeigen (PRESENT), 30 Helligkeit, 11 Aufräumen, 5 Summer, 10 Kompass, 17 Ring-Akku, 21/22/29 Ressourcen-Cache, 27 Wurzel-Display-Liste |

Zeichenbefehle, die die Brille **selbst** ausführen kann (auch in Display-Listen): Pixel-Rechteck mit
RLE, Rechteck kopieren, Text in Evens 20-px-Schrift (UTF-8), Text mit hochgeladener Schrift, Bild aus
dem Cache, Farben umrechnen, verschachtelte Display-Liste, abgerundetes Rechteck, Löschen. Dazu
Ausdrücke für Animationen und ein Ressourcen-Cache von 192 KiB (512 Einträge), solange die Lease läuft.
Details: `wissen/02-display.md` §3.

## 5. Eigene Features in die Firmware einbauen – so gehen wir vor

1. **Erst prüfen, ob es ohne Firmware geht.** Fast alles, was man *sieht*, geht in der App (oder auf
   der Uhr): Menüs, Texte, Animationen (Display-Listen gibt es schon), Benachrichtigungen, Logik.
   Firmware braucht man nur, wenn die Brille selbst etwas Neues können muss: einen Sensor weitergeben,
   Hardware anders steuern, ohne Begleiter reagieren.
2. **Neuen Code in die bestehenden C-Erweiterungen**, nicht neue Stellen in Evens Code überschreiben.
   Am sichersten: ein neuer Nachrichtentyp im privaten Protokoll oder ein neues Einstellungs-Feld.
3. **Nichts, was beim Start läuft.** Alles hinter der Framebuffer-Lease bzw. erst nach einer privaten
   Nachricht. Stürzt die Brille beim Start ab, kann sie kein Update mehr annehmen.
4. **Budget einhalten:** ≤ 70.976 Bytes zusätzlicher Code+Daten im MRAM; Arbeitsspeicher sparsam (der
   CFW-Kontext hat 1 KiB fest, alles andere aus Evens Heaps). Große Daten (Texte, Bilder) zur Laufzeit
   hochladen (Ressourcen-Cache), nicht in die Firmware einbauen.
5. **Jede neue feste Adresse** aus Evens Code prüfen und in `stock_abi_230.json` aufnehmen.
6. **Eigene Kennung:** `caps[]` in `settings_ext.c` z. B. auf `FaceclawEdit/1` ändern (g2flash rät:
   eigene Firmware = eigenes Präfix). Die App muss diese Kennung dann akzeptieren.
7. **Bauen und prüfen:** `./build_cfw.sh --update-patches`, neues Patch-Set, neuer Ausgabe-SHA-256,
   `cfw_bauen.py --check` (Struktur, Prüfsummen, Speichergrenze).
8. **In der App freischalten:** neues Patch-Set nach `core/src/main/resources/firmware/`, neue SHA-256-
   Werte und Revision in `FirmwareCatalog.kt`, Erkennung in `GlassesSession.kt` anpassen. Die App
   flasht nur Images auf ihrer Erlaubnisliste.
9. **Auf echter Hardware testen:** Testlauf → flashen → Kennung prüfen → Feature testen → einmal zurück
   zur Original-Firmware flashen (beweist, dass der Rückweg funktioniert).

**Was schiefgehen kann:** Absturz beim Start (kein Update mehr möglich), Speichergrenze überschritten
(Bootloader-Endlosschleife), falsche Prüfsumme (Brille lehnt die Komponente ab – harmlos, dauert nur),
Zustand nach abgebrochenem Update unbekannt. Zurück zum Original geht es, solange die Brille startet
und Bluetooth funktioniert: mit der App („Reinstall original firmware“) oder g2flash das Original
2.3.0.24 aufspielen – oder die offizielle Even-App installiert ein Update, sobald es eine neuere
Version als 2.3.0.24 gibt.

## 6. Menüs aus dem Designer – App oder Firmware?

**Weg A – im Begleiter (empfohlen):** Die App (Handy oder Uhr) zeichnet die Menüs und schickt Bilder.
Das Designer-JSON bildet sich direkt auf `MenuItem`/Zeichenbefehle der Kotlin-App ab
(`05_Entwurfsformat.md`). Kein Flash-Risiko, beliebig änderbar.

**Weg B – ohne Firmware-Änderung, aber schneller:** Der Begleiter lädt Menü-Grafiken und Texte einmal
in den Ressourcen-Cache der CFW und schickt beim Scrollen nur kleine Befehle (Display-Listen, Text in
Evens Schrift). Das kann Faceclaw/34 schon heute.

**Weg C – Menü komplett in der Firmware (ohne Begleiter):** Möglich, aber ein großes Projekt: Die
Brille bräuchte eigene Menü-Logik (Auswahl, Eingaben, Zustände), eine Menübeschreibung im Speicher und
Zeichencode – alles in ≤ 70 KB, ohne Start-Risiko, mit Tests auf Hardware. Wenn du das willst, dann
schrittweise: zuerst ein kleines Menü, das der Begleiter als Ressource hochlädt und das die Firmware
nur mit dem Ring durchblättert.

Im Designer kannst du pro Bildschirm unter **„Umsetzung“** angeben, was du dir vorstellst (Offen / App /
Firmware). Das hilft beim Planen im neuen Chat.

## 7. Fallstricke (die teuersten zuerst)

1. Falsche CRC-32C-Variante → Fehler erst nach Minuten Übertragung (Status 7).
2. Vorspann-CRC muss **vor** der Komponenten-CRC berechnet werden (liegt in den Nutzdaten).
3. Speichergrenze nicht prüfen → Glas kann dauerhaft hängen.
4. Beim Flashen einen Block nach einer bloßen Zeitüberschreitung wiederholen → Image verschoben
   (das Protokoll hat keine Blocknummer). Nur nach ausdrücklicher Ablehnung wiederholen.
5. Adressen in Evens Code verschieben sich bei jeder neuen Original-Version → Umbau auf eine neue
   Basis („Rebase“) ist die gefährlichste Arbeit überhaupt.
6. Evens Firmware nie ins Repo und nie weitergeben – immer von Evens Server laden.
