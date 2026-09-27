# Entwurfsformat des Designers (`faceclaw-edit/design@1`)

Der [Designer](../web/designer/README.md) speichert Entwürfe als JSON. Dieses Dokument beschreibt das
Format und wie es auf die Kotlin-Oberfläche der App abgebildet wird – damit aus einem Entwurf echter
App-Code werden kann.

## Aufbau

```json
{
  "format": "faceclaw-edit/design@1",
  "id": "beispiel",
  "name": "Beispiel: Brillenmenü",
  "notes": "Ziele und Features des ganzen Entwurfs",
  "displayArea": "full",
  "start": "s_start",
  "updatedAt": "2026-09-27T21:00:00.000Z",
  "screens": [ … ]
}
```

| Feld | Bedeutung |
|---|---|
| `notes` | Ziele und Feature-Wünsche für den ganzen Entwurf |
| `displayArea` | `full` (640 × 480), `comfort` (600 × 400) oder `compact` (576 × 288) – entspricht `Prefs.DisplayArea` |
| `start` | ID des Bildschirms, der zuerst erscheint |

### Bildschirm

| Feld | Bedeutung |
|---|---|
| `id`, `name` | `name` steht in der Statuszeile (wie `Screen.title`) |
| `kind` | `menu` (Liste von Einträgen) oder `free` (frei platzierte Elemente) |
| `statusBar` | Statuszeile oben (Uhrzeit, Titel, Akku); `false` = Vollbild (`Screen.fullscreen`) |
| `runsOn` | Wunsch zur Umsetzung: `open` (Claude schlägt vor), `app` (Handy/Uhr zeichnet), `firmware` (läuft auf der Brille selbst) |
| `notes` | Beschreibung, was der Bildschirm tun soll – der wichtigste Teil für die Umsetzung |
| `tapTarget` | nur `free`: Bildschirm, den Tippen öffnet |
| `items` | nur `menu`: Einträge (siehe unten) |
| `elements` | nur `free`: Elemente (siehe unten) |

### Menü-Einträge (`items`) → `MenuItem`

Alle Einträge haben `id`, `type`, `label`, optional `icon` (Name aus dem Icon-Katalog der App, z. B.
`music_note` → `Icons.MusicNote`), `subtitle` (zweite Zeile) und `notes` (was beim Antippen passieren soll).

| `type` | Zusatzfelder | Kotlin |
|---|---|---|
| `link` | `target` (Bildschirm-ID), `detail` | `MenuItem.Link(label, icon, detail, subtitle) { Zielbildschirm }` |
| `action` | `detail`, `dot` | `MenuItem.Action(label, icon, detail, subtitle, trailingDot = dot) { … }` |
| `toggle` | `on` (Anfangswert) | `MenuItem.Toggle(label, icon, subtitle, get, set)` |
| `choice` | `options` (Texte), `selected` (Index) | `MenuItem.Choice(label, icon, options, get, set)` – bis 4 Optionen wechseln beim Tippen, mehr öffnen `ChoicePickerScreen` |
| `stepper` | `min`, `max`, `step`, `value`, `unit` | `MenuItem.Stepper(label, icon, min..max, step, format = { "$it$unit" }, get, set)` |
| `header` | – | `MenuItem.Header(label)` |
| `info` | `detail` | `MenuItem.Info(label, detail, icon)` |

Darstellung wie `MenuList`: Zeilen 52 px (mit Untertitel 70 px, Überschrift 36 px), Icon 28 px,
Auswahl-Kapsel mit Rand.

### Elemente freier Flächen (`elements`)

Koordinaten in Pixeln des ganzen Panels (0–640 × 0–480), unabhängig vom Anzeigebereich.
Helligkeiten sind **Stufen 0–15** (die 16 Stufen des Displays); in Kotlin `stufe * 17` (0–255),
passend zu `Theme.levels` (z. B. 13 = `text` 221, 9 = `textDim` 153, 6 = `textFaint` 102, 2 = `surface` 34).

| `type` | Felder | Kotlin-Zeichenbefehl |
|---|---|---|
| `text` | `x`, `y` (Oberkante), `w` (Umbruchbreite, 0 = kein Umbruch), `text`, `font`, `level`, `align` (`left`/`center`/`right`) | `drawText` / `drawTextIn` mit `theme.type.<font>` |
| `rect` | `x`, `y`, `w`, `h`, `radius`, `fill` (Stufe oder `null`), `stroke` (Stufe oder `null`), `strokeWidth` | `fillRoundRect` / `strokeRoundRect` |
| `circle` | `x`, `y` (Mitte), `r`, `fill`, `stroke`, `strokeWidth` | `fillCircle` / `strokeCircle` |
| `line` | `x`, `y`, `x2`, `y2`, `width`, `level` | `drawLine` |
| `icon` | `x`, `y`, `size` (16, 20, 24, 28, 32, 40, 48, 64), `name`, `level` | `drawIcon(Icons.…, x, y, size, theme.type.icons(size), level)` |
| `progress` | `x`, `y`, `w`, `h`, `value` (0–1), `level` | `Drawing.progressBar` |

`font` ist eine Rolle aus `Typography`: `caption` (16), `status` (18), `body` (22), `bodyStrong` (22 fett),
`title` (28), `headline` (40), `displaySmall` (48), `displayMedium` (80), `display` (128), `mono` (18).

Die Vorschau im Designer rechnet wie die App: Graustufen zeichnen, auf 16 Stufen runden, grün einfärben.
