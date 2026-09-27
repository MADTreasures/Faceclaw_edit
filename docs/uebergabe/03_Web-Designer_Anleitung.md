# Web-Designer: Menüs entwerfen und an den Chat zurückgeben

Mit dem **Faceclaw Designer** entwirfst du am PC, was auf der Brille erscheinen soll – Menütexte,
Bildschirme, Icons – und beschreibst in Notizen, was passieren soll. Den fertigen Entwurf gibst du dem
neuen Chat, der daraus App- oder Firmware-Code macht.

## 1. Öffnen

- **Online (empfohlen):** https://claude.ai/artifact/1kLLongxpSzP29tkfS6ewb
  – nur mit deinem claude.ai-Konto sichtbar. Entwürfe werden dort automatisch gespeichert
  (Anzeige oben rechts: „Gespeichert“).
- **Offline:** `designer/index.html` aus diesem Paket im Browser öffnen (Chrome, Edge, Firefox).
  Gespeichert wird dann nur in diesem Browser – vor dem Schließen exportieren (siehe 4.).

## 2. Aufbau der Seite

| Bereich | Was du dort machst |
|---|---|
| oben | Name des Entwurfs, Entwurf wechseln, **Neu**, **Export**, **Import** |
| links | Liste der Bildschirme, neue anlegen (**Menü** oder **Freie Fläche**), **Notizen zum Entwurf** |
| Mitte | die Brille: 640 × 480 Pixel, grün, 16 Helligkeitsstufen – so sieht es später aus |
| rechts | Eigenschaften des gewählten Bildschirms, Eintrags oder Elements |

## 3. Schritt für Schritt einen Entwurf machen

1. **Neu** klicken und oben einen Namen geben, z. B. „Mein Brillenmenü“.
2. Unter **Notizen zum Entwurf** deine Ziele aufschreiben (z. B. „ohne Handy nutzbar“, „Uhr als
   Fernbedienung“, welche Features du willst).
3. **Menü-Bildschirm** anlegen → rechts unter „Hinzufügen“ Einträge wählen:
   - **Untermenü** – öffnet einen anderen Bildschirm („Öffnet“ wählen oder „Neues Untermenü dafür anlegen“)
   - **Aktion** – tut etwas; in die Notiz schreiben *was*
   - **Schalter** – Ein/Aus
   - **Auswahl** – Optionen, eine pro Zeile
   - **Zahl** – Min/Max/Schritt/Einheit
   - **Überschrift** und **Info** – nur zur Anzeige
   Für jeden Eintrag: **Text** eintippen, **Icon** wählen (Suche, z. B. „music“, „timer“), optional
   Wert rechts und Untertitel.
4. **Freie Fläche** für eigene Anordnungen: Text, Rechteck, Kreis, Linie, Icon, Fortschrittsbalken
   hinzufügen und im Modus **Bearbeiten** in der Vorschau an die richtige Stelle ziehen. Rechts
   Schriftgröße, Ausrichtung und Helligkeit (Stufe 0–15) einstellen.
5. Bei jedem Bildschirm **„Umsetzung“** wählen: *Offen* (Claude schlägt vor), *App* (Begleiter
   zeichnet) oder *Firmware* (soll auf der Brille selbst laufen).
6. **Notizen** ausfüllen – das ist der wichtigste Teil. Beispiele:
   - Bildschirm: „Zeigt die nächsten 3 Termine. Scrollen blättert, Tippen öffnet Details.“
   - Eintrag „Aufnahme“: „Startet das Mikrofon, zeigt den erkannten Text live an.“
   - Element „Akku“: „soll den echten Akkustand der Brille zeigen“
7. Den Startbildschirm festlegen (**Als Start**).
8. Im Modus **Bedienen** durchklicken: `↑`/`↓` bzw. Mausrad = scrollen, `Enter`/Klick = tippen,
   `Esc`/Doppelklick = zurück. So prüfst du, ob die Navigation stimmt.

Tipp: Lieber mehrere kleine, klare Bildschirme als einen überladenen. Texte kurz halten – die Brille
zeigt pro Zeile etwa 30–40 Zeichen in normaler Schrift.

## 4. Den Entwurf an den neuen Chat geben

**Weg 1 – JSON kopieren (geht immer):**
**Export** → **JSON kopieren** → im neuen Chat einfügen, zusammen mit dem Text aus
`04_Prompt_fuer_neuen_Chat.md`.

**Weg 2 – als Datei:**
**Export** → **JSON-Datei** speichern (oder beim Offline-Designer den kopierten Text als `.json`
speichern) → im neuen Chat anhängen. Zusätzlich hilft **PNG dieses Bildschirms** als Bild.

**Weg 3 – direkt lesen lassen (nur Claude Code mit deinem Konto):**
Dem neuen Chat den Link des Designers geben. Er kann die Entwürfe selbst lesen:
`ArtifactData list` mit `url = https://claude.ai/artifact/1kLLongxpSzP29tkfS6ewb`,
`collection = designs`. Sag ihm, welchen Entwurf (Name) er nehmen soll.

Zurück in den Designer kommt ein Entwurf über **Import** (JSON einfügen oder Datei wählen) – er wird
als neuer Entwurf angelegt.

## 5. Was der neue Chat mit dem Entwurf macht

Das Format ist in `05_Entwurfsformat.md` beschrieben. Jeder Menü-Eintrag entspricht einem Baustein der
Kotlin-App (`MenuItem.Link`, `Toggle`, `Choice` …), jedes Element einem Zeichenbefehl. Der Chat sollte:

1. den Entwurf lesen und bei Unklarheiten nachfragen,
2. pro Bildschirm entscheiden (bzw. deinem Feld „Umsetzung“ folgen): **App** oder **Firmware**
   (siehe `01_Custom-Firmware_erstellen.md` Abschnitt 6),
3. App-Teile als Bildschirme in der App umsetzen und im Simulator als Screenshot zeigen,
4. Firmware-Teile nur nach den Regeln aus `01_…` und `02_…` planen und erst nach deiner Zustimmung bauen.

## 6. Häufige Fragen

- **Wird mein Entwurf gespeichert?** Online: automatisch („Gespeichert“). Offline: nur im Browser –
  exportieren!
- **Kann ich Umlaute schreiben?** Ja. Die Schriften der App enthalten ä, ö, ü, ß (und mehr). Ob Evens
  eingebaute Schrift auf der Brille alle Umlaute hat, ist noch nicht geprüft – relevant nur, wenn Text
  später direkt von der Firmware gezeichnet werden soll.
- **Warum sieht die Vorschau grün aus?** Das G2-Display zeigt nur Grün in 16 Helligkeitsstufen.
  Schwarz ist auf der Brille durchsichtig.
