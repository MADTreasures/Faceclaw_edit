# Übergabepaket: Custom-Firmware und Web-Designer für die Even G2

Dieses Paket fasst zusammen, was ich (Claude) beim Studium von **Faceclaw** und **g2flash** über die
Custom-Firmware der Even Realities G2 gelernt, selbst nachgebaut und geprüft habe – und wie du mit dem
**Web-Designer** eigene Menüs und Texte entwirfst und sie einem neuen Chat übergibst.

## Inhalt

| Datei / Ordner | Wofür |
|---|---|
| `01_Custom-Firmware_erstellen.md` | **Das Wissen:** Aufbau der Firmware, wie das Custom-Image gebaut wird, wie man eigene Features einbaut, Budget und Risiken |
| `02_Firmware_flashen.md` | Wie ein Image auf die Brille kommt (Protokoll, Sicherheitsregeln, Hinweise für die Uhr) |
| `03_Web-Designer_Anleitung.md` | Menüs und Texte im Designer entwerfen und an den neuen Chat zurückgeben |
| `04_Prompt_fuer_neuen_Chat.md` | fertiger Text zum Einfügen in den neuen Chat |
| `05_Entwurfsformat.md` | das JSON-Format der Entwürfe und wie es auf App-Code abgebildet wird |
| `designer/` | der Designer zum Offline-Öffnen (`index.html`), seine Quelle und ein Beispiel-Entwurf |
| `firmware/` | Patch-Set der Custom-Firmware (`cfw_patches.json`) und `cfw_bauen.py` zum Bauen/Prüfen ohne Flashen |
| `wissen/` | ausführliche technische Analysen mit Quellenangaben (englisch): Bluetooth, Display, Eingaben, UI, Android, Firmware, Assistent |
| `referenz-code/` | der geprüfte Kotlin-Code: Image-Prüfung, Patch-Set, Flash-Ablauf, Bluetooth-Anbindung, Tests |

## So benutzt du das Paket

1. `01_Custom-Firmware_erstellen.md` lesen (wenigstens Abschnitt 1 und 6).
2. Im Designer deine Menüs und Texte entwerfen (`03_…`), Notizen schreiben, exportieren.
3. Neuen Chat öffnen, dieses ZIP anhängen, den Text aus `04_…` einfügen, Entwurf und Feature-Liste dazu.

## Die wichtigsten Fakten

- Basis: Evens Firmware **2.3.0.24** (SHA-256 `187ccf2b…0979`), Custom: **Faceclaw/34**
  (SHA-256 `7d8764f8…0ee7`), gebaut aus Original + 38 Patches.
- Neuer Firmware-Code darf höchstens **70.976 Bytes** groß sein (Speichergrenze, sonst Brick-Gefahr).
- Menüs und Texte laufen normalerweise im **Begleiter** (Handy/Uhr); Firmware nur für Dinge, die die
  Brille selbst können muss.
- Evens Firmware ist nicht enthalten und darf nicht weitergegeben werden – sie wird von Evens Server
  geladen.

## Was geprüft ist – und was nicht

| | Stand |
|---|---|
| Custom-Image aus Original + Patch-Set | selbst nachgebaut, **bitgenau** gleicher SHA-256 wie g2flash (Kotlin und Python) |
| Image-Prüfungen (Prüfsummen, Speichergrenze) | am echten Original und am Custom-Image getestet |
| Flash-Ablauf der App | gegen eine simulierte Brille mit Fehlerfällen getestet (18 Szenarien) |
| Flashen auf echter Hardware mit dieser App | **noch nicht** – zuerst Testlauf ohne Schreiben |
| Web-Designer | läuft online und offline; Speichern online geprüft |

## Quellen

- Projekt: https://github.com/MADTreasures/Faceclaw_edit (Branch `claude/faceclaw-app-rebuild-hnrz4y`)
- Faceclaw: https://github.com/jimrandomh/faceclaw · g2flash: https://github.com/jimrandomh/g2flash
  (beide GPLv3; das Patch-Set in `firmware/` stammt unverändert aus g2flash)
- Designer online (nur dein Konto): https://claude.ai/artifact/1kLLongxpSzP29tkfS6ewb

## Herkunft im Repo

Die Texte liegen im Repo unter `docs/uebergabe/`; das ZIP erzeugt `python3 scripts/make_handoff_zip.py`.
Zuordnung: `05_Entwurfsformat.md` = `docs/design-format.md`, `designer/` = `web/designer/`,
`firmware/cfw_bauen.py` = `scripts/cfw_build.py`, `firmware/cfw_patches.json` =
`core/src/main/resources/firmware/cfw_patches.json`, `wissen/` = `docs/analysis/` (+ `docs/firmware.md`).
