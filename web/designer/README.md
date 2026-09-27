# Faceclaw Designer

Ein Werkzeug für den Browser am PC: Menüs und Bildschirme für die G2-Brille gestalten, in einer
pixelgenauen Vorschau ausprobieren und als Entwurf an Claude übergeben, der daraus App-Code macht.

- **In claude.ai:** als Artifact „Faceclaw Designer“ in deinem Konto. Entwürfe werden dort in der
  Datenbank der Seite gespeichert – Claude kann sie direkt lesen („Schau dir meinen Entwurf … an“).
- **Lokal:** `index.html` im Browser öffnen. Dann wird nur im Browser gespeichert; übergeben per
  Export → „JSON kopieren“ (im Chat einfügen) oder als Datei nach `designs/` im Repo.

## Bedienung

**Bildschirme** (links): „Menü“ ist eine Liste wie in der App, „Freie Fläche“ ein leerer Bildschirm für
eigene Anordnungen. Ein Bildschirm ist der Start.

**Vorschau** (Mitte): 640 × 480 Pixel, 16 Helligkeitsstufen, grün – so, wie es die Brille zeigt.

- *Bedienen*: wie mit dem Ring – `↑`/`↓` oder Mausrad scrollen, `Enter` oder Klick tippt, `Esc` oder
  Doppelklick geht zurück. Untermenüs öffnen sich, Schalter und Auswahlen reagieren (nur zur Probe,
  der Entwurf bleibt unverändert).
- *Bearbeiten*: Einträge oder Elemente anklicken; Elemente ziehen, am Quadrat unten rechts die Größe
  ändern, Pfeiltasten verschieben (mit `Shift` um 8 px), `Entf` löscht. Gestrichelt: Anzeigebereich
  und Statuszeile.

**Eigenschaften** (rechts): Name, Statuszeile, Notizen; bei Menüs die Einträge (Untermenü, Aktion,
Schalter, Auswahl, Zahl, Überschrift, Info) mit Text, Icon, Wert und Ziel; bei freien Flächen die
Elemente (Text, Rechteck, Kreis, Linie, Icon, Fortschritt) mit Position, Schrift und Helligkeit.

**Notizen** sind der wichtigste Teil der Übergabe: Schreib zu Bildschirmen und Einträgen, was passieren
soll („zeigt die nächsten drei Termine“, „startet die Sprachaufnahme“). Daraus wird die Logik.

Das JSON-Format ist in [docs/design-format.md](../../docs/design-format.md) beschrieben.

## Entwicklung

`designer.html` ist die Quelle (so wird sie als Artifact veröffentlicht, ohne `<html>`-Gerüst).
`./build.sh` erzeugt daraus `index.html` für den direkten Aufruf im Browser. Keine Abhängigkeiten
außer den Web-Schriften (Inter, JetBrains Mono, IBM Plex, Material Icons von Google Fonts); ohne
Internet fällt die Vorschau auf Systemschriften zurück.
