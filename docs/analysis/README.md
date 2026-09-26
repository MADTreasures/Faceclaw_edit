# Analyse der Originale (faceclaw / g2flash)

Diese Dokumente entstanden beim Neubau durch systematisches Lesen des Quellcodes von
[jimrandomh/faceclaw](https://github.com/jimrandomh/faceclaw),
[jimrandomh/g2flash](https://github.com/jimrandomh/g2flash) und
[Commute773/g2-kit-unofficial](https://github.com/Commute773/g2-kit-unofficial).
Sie beschreiben Protokolle, Formate und Verhalten so, dass man sie unabhängig
nachimplementieren kann. Verweise der Form `pfad:zeile` beziehen sich auf die
jeweiligen Original-Repos (Stand September 2026).

Die Texte wurden automatisiert erstellt und punktuell gegengeprüft. Sie können
Fehler enthalten; bei Widersprüchen gilt der Originalcode.

| Datei | Inhalt |
|---|---|
| `01-ble-link.md` | GATT-UUIDs, Discovery, `aa 21`-Framing, CRC, Session-Ablauf, Custom-Firmware-Transport (sid 0xF0) |
| `02-display.md` | Displayformate, EvenHub-Container, Custom-Firmware-Framebuffer, Texturen, Display-Listen |
| `03-input-sensors-audio.md` | Gesten, Ring, Tragen/Absetzen, Akku, Kompass, Lichtsensor, Buzzer, Mikrofon (LC3) |
| `04-ui-shell-apps.md` | Aufbau der Brillen-Oberfläche, Shell, Menüs, alle Apps, Einstellungen |
| `05-phone-android.md` | Handy-App, Android-Dienste, Berechtigungen, Hintergrundbetrieb |
| `06-firmware.md` | Stock-Firmware, EVENOTA-Format, Patch-Set, OTA-Flash-Protokoll, Sicherheits-Checkliste |
| `07-assistant-evenhub-remote.md` | Sprachassistent, LLM-Anbindung, EvenHub-Kompatibilität, Terminal-Spiegelung |
