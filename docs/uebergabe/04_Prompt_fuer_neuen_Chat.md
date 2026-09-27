# Vorlage: Nachricht für den neuen Chat

Kopiere den Text zwischen den Linien in den neuen Chat, hänge dieses ZIP an und füge darunter deinen
Entwurf (JSON aus dem Designer) und die Feature-Liste aus deinem anderen Chat ein. Die Stellen in
`<…>` ersetzen oder löschen.

---

Hallo! Ich arbeite an einer eigenen Begleit-Software und Custom-Firmware für die **Even Realities G2**
(auf Basis von Faceclaw und g2flash). Im angehängten ZIP ist ein Übergabepaket aus einem früheren Chat.
Bitte lies zuerst:

1. `00_LIES_MICH.md` – Überblick
2. `01_Custom-Firmware_erstellen.md` – wie die Custom-Firmware aufgebaut ist und gebaut wird
3. `02_Firmware_flashen.md` – wie sie auf die Brille kommt, mit den Sicherheitsregeln
4. `03_Web-Designer_Anleitung.md` und `05_Entwurfsformat.md` – mein Entwurfswerkzeug und sein Format
5. bei Bedarf die Details in `wissen/` (vor allem `06-firmware.md`, `02-display.md`) und den Code in
   `referenz-code/`

Das vollständige Projekt liegt auf GitHub: `MADTreasures/Faceclaw_edit`, Branch
`claude/faceclaw-app-rebuild-hnrz4y` (Kotlin: Kern ohne Android, Android-App, Simulator, Designer).

**Mein Ziel:** <z. B. „Die Brille soll ohne Handy nutzbar sein: die Uhr (Wear OS) ist der Begleiter, und
eigene Menüs/Features sollen – wo sinnvoll – direkt in die Custom-Firmware.“>

**Mein Entwurf aus dem Designer:** <JSON einfügen – oder: „Lies den Entwurf ‚…‘ aus
https://claude.ai/artifact/1kLLongxpSzP29tkfS6ewb, Collection `designs`.“>

**Features aus meinem anderen Chat:** <Liste einfügen>

**Bitte so vorgehen:**

1. Fasse zusammen, was du verstanden hast, und stell mir Fragen, wo etwas unklar ist.
2. Mach einen Plan: Was davon geht im Begleiter (App/Uhr), was braucht wirklich Firmware? Beachte dabei
   das Feld „Umsetzung“ in meinem Entwurf und das Speicherbudget (≈ 70 KB frei im MRAM).
3. Setze zuerst die Begleiter-Teile um (sicher, jederzeit änderbar) und zeig sie mir (z. B. als
   Screenshots aus dem Simulator).
4. Firmware-Änderungen nur nach meiner Zustimmung und nach den Regeln aus `01_…` und `02_…`:
   - Evens Firmware nie ins Repo, immer vom Server laden und per SHA-256 prüfen
   - Image vollständig prüfen (Prüfsummen, Speichergrenze) – `firmware/cfw_bauen.py --check`
   - nichts, was beim Start der Brille läuft; neue Funktionen nur hinter der Lease bzw. privaten Nachrichten
   - eigene Kennung (z. B. `FaceclawEdit/1`), neue Hashes auf die Erlaubnisliste
   - beim Flashen Blöcke nur nach ausdrücklicher Ablehnung wiederholen; zuerst ein Testlauf ohne Schreiben
5. Code, Kommentare und Commit-Nachrichten auf Englisch, Erklärungen für mich auf Deutsch.

---

## Tipps

- Hängst du das ZIP an, kann der neue Chat alles darin lesen. Arbeitet er mit dem GitHub-Repo, reicht
  auch der Hinweis auf den Branch – die Dokumente liegen dort unter `docs/uebergabe/`.
- Für Menüs, die du später änderst: im Designer bearbeiten und nur den neuen JSON-Export schicken.
