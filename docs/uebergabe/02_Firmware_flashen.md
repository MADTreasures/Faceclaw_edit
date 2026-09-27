# Firmware auf die Brille bringen (OTA)

Wie ein Image auf die beiden Gläser kommt, welche Prüfungen vorher nötig sind und worauf ein eigener
Flasher (z. B. auf der Uhr) achten muss. Protokoll-Details mit Quellen: `wissen/06-firmware.md` §6–§12.

## 1. Möglichkeiten

| Werkzeug | Wie | Stand |
|---|---|---|
| **Faceclaw Edit (Android)** | Settings → Glasses firmware… → Install custom / Reinstall original / Dry run | gebaut und gegen eine simulierte Brille getestet, **noch nie auf echter Hardware** |
| **g2flash.py** (PC) | `python g2flash.py -c 'g2://local?left=…&right=…' -f g2_2.3.0.24_cfw.bin` | vom Autor auf Hardware erprobt; `--stop-before flash` = Testlauf |
| **Faceclaw (Original-App)** | Onboarding „Install Firmware“ | erprobt |
| **Offizielle Even-App** | installiert Evens Updates (entfernt damit die CFW) | – |

Die Brille nimmt immer nur **eine** Verbindung an: Even-App vorher trennen.

## 2. Ablauf eines Updates

1. **Image vorbereiten:** bauen bzw. laden, vollständig prüfen (Struktur, alle Prüfsummen,
   Speichergrenze) und den SHA-256 gegen die Erlaubnisliste halten – direkt vor dem Schreiben noch einmal.
2. **Vorabprüfung:** beide Bügel verbinden und anmelden (Kopplungsdialog kommt jetzt, nicht mitten im
   Update), Versionen und Akku lesen (≥ 30 % pro Glas), auf der Brille bestätigen lassen.
3. **Linkes Glas, dann rechtes**, nie gleichzeitig:
   - verbinden, Benachrichtigungen auf dem Steuer- **und** dem Update-Kanal einschalten, anmelden,
     2,5 s warten
   - `BEGIN` → für jede Komponente: `FILE_CHECK` (128-Byte-Kopf) → Nutzdaten in 4-KB-Blöcken →
     `END` (die Brille prüft die CRC-32C)
   - trennen; die Brille startet neu (dabei auch das andere Glas) → 5 s warten, bis zu 120 s auf das
     zweite Glas warten
4. **Kontrolle:** nach dem Neustart verbinden und die Kennung lesen (`Faceclaw/34` bzw. keine Kennung
   beim Original).

## 3. Das Update-Protokoll

| | |
|---|---|
| GATT | Steuerung schreiben `…5401`, Benachrichtigung `…5402`; Update schreiben `…0001`, Quittungen `…0002` (Basis `00002760-08c2-11e1-9073-0e8ac72e____`) |
| Rahmen | `AA 21 seq len gesamt index sid flag daten…`, CRC-16/CCITT-FALSE (LE) hinter den Daten, max. 232 Datenbytes pro Rahmen → **MTU ≥ 243** nötig |
| Anmeldung | sid `0x80`: `08 04 10 <magic> 1a 04 08 01 10 04`; erst die Antwort `08 04 10 <magic> 1a 00` ist Erfolg (davor kommt oft eine Nicht-Erfolg-Antwort – weiter warten) |
| BEGIN | sid `0xC0`, Daten `00` → Quittung Opcode 0 |
| FILE_CHECK | sid `0xC0`, `01` + 128-Byte-Kopf → Quittung Opcode 1, Status muss 0 sein |
| Block | sid `0xC0` `02` (Markierung) **und direkt danach** sid `0xC1` mit bis zu 4096 Bytes, **beide mit derselben seq** → Quittung Opcode 2 |
| END | sid `0xC0` `03` → Quittung Opcode 3, Status 0, 8 (UPDATING, der Normalfall) oder 9 = geprüft |
| Quittung | `AA 12 …` auf `…0002`, Nutzdaten `[opcode, status]` |
| seq | beginnt nach jeder Anmeldung bei 1, +1 pro Nachricht (Markierung+Block zählen einmal), 255 → 0 |

Beispiel-Bytes: BEGIN mit seq 1 = `aa 21 01 03 01 01 c0 00 00 f0 e1`.

Status-Codes: 0 OK, 1 Header, 2 Pfad, 3 CRC, 4 Timeout, 5 keine Ressourcen, 6 Schreibfehler,
7 **Prüfung fehlgeschlagen**, 8 UPDATING, 9 Neustart, 10 Fehler.

## 4. Die Regeln, die eine Brille retten

- Zwischen BEGIN und dem letzten END **nichts anderes** senden (keine Heartbeats, keine Abfragen).
- Einen Block **nur nach einer ausdrücklichen Ablehnung** (Status ≠ 0) wiederholen, höchstens 3-mal.
  Bei einer bloßen Zeitüberschreitung weiß man nicht, ob die Brille ihn schon geschrieben hat –
  eine Wiederholung würde alles Folgende verschieben. Stattdessen: neu verbinden, anmelden, frisches
  BEGIN und das Glas **von vorne** (so macht es Faceclaw Edit).
- END mit Status 7 → Komponente bis zu 3-mal neu senden (ab FILE_CHECK).
- Scheitert ein Glas, **aufhören** und den Nutzer neu starten lassen – nie automatisch in Schleife flashen.
- Nur Images von der Erlaubnisliste; nie „schnell mal“ Prüfsummen eines fremden Images reparieren.

Zeiten (bewährt): Verbindungsaufbau 5 s je Schritt, Quittungen 8 s (Steuerung) bzw. 4 s (Block),
1,5 s Pause vor einer Komponenten-Wiederholung, 10 s vor einem Neuverbinden.

## 5. Hinweise für einen Flasher auf der Uhr (Wear OS)

Der Firmware-Teil von Faceclaw Edit ist **reines Kotlin ohne Android-Abhängigkeit**
(`referenz-code/firmware/`) und läuft so auch auf Wear OS; die Bluetooth-Anbindung
(`referenz-code/android/AndroidBleLink.kt`) ist normales Android-GATT und passt ebenfalls.
Worauf es auf der Uhr ankommt:

- **MTU ≥ 243** aushandeln und prüfen – sonst passen die 240-Byte-Rahmen nicht. Manche Uhren liefern
  weniger; dann nicht flashen.
- **Wach bleiben:** Vordergrund-Dienst + Wake-Lock während des ganzen Updates, Bildschirm an lassen,
  Zurück sperren. Ein Update dauert mehrere Minuten pro Glas.
- **Akku der Uhr** vorher prüfen (Laden empfohlen), nicht nur den der Brille.
- **Image besorgen:** über WLAN/LTE von Evens Server laden (4,5 MB) oder vom Handy übertragen – in
  jedem Fall auf der Uhr erneut per SHA-256 prüfen.
- **Nur ein Begleiter:** Handy-App und Even-App dürfen während des Updates nicht verbunden sein.
- Durchsatz und Stabilität von Uhr-Bluetooth sind unbekannt → zuerst einen Testlauf ohne Schreiben
  (verbinden, anmelden, MTU prüfen), dann das Protokoll ansehen.

## 6. Testlauf zuerst

Ein Testlauf baut und prüft das Image, verbindet beide Gläser über den Update-Kanal (Anmeldung, MTU)
und **schreibt nichts**. Erst wenn der sauber durchläuft, das echte Update starten. Das Protokoll
(„Details → Copy“ in der App) in den Chat kopieren, wenn etwas unklar ist.
