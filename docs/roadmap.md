# Roadmap und Vergleich mit dem Original

Stand 0.1. „Original“ = Funktionen von Faceclaw laut dessen README bzw. Analyse.

| Bereich | Original | Faceclaw Edit 0.1 | Nächste Schritte |
|---|---|---|---|
| Verbindung, Pairing, Reconnect | ✅ | ✅ (ungetestet auf Hardware) | Hardware-Test, Feinabstimmung der Timeouts |
| Vollbild 640×480 über Custom-Firmware | ✅ | ✅ Diff-Frames mit RLE + Deflate | Display-Listen für Animationen ohne BLE-Last, Textur-Cache |
| Helligkeit, Display an/aus mit Überblendung | ✅ | ✅ manuell | automatische Helligkeit über den Umgebungslichtsensor |
| **Energiesparen** (Brille schläft bei Display aus, Aufwecken per Doppeltipp) | ✅ | ❌ | **wichtigster nächster Schritt**: EvenHub bei Display aus beenden, Wake-Lease, Wake-Handshake |
| Startbildschirm / Glanceboard | ✅ | ✅ eigenes Design mit Glance-Karten | Anzeige im Schlafmodus per Tippen |
| Multitasking / App-Wechsel | ✅ | ✅ über Halten-Menü | – |
| Benachrichtigungen (Popup, Liste, Aktionen) | ✅ | ✅ inkl. echter Schnellantworten | Stummschalten pro App in der Handy-UI, App-Icons in der Statuszeile |
| Musik-Steuerung | ✅ inkl. Mediathek | ✅ Steuerung + Cover | Mediathek/Playlists (MediaBrowser) |
| Timer, Stoppuhr | ✅ inkl. Wecker | ✅ | Wecker mit AlarmManager |
| Kalender, Wetter | ✅ | ✅ (Open-Meteo ohne API-Schlüssel) | – |
| Kompass | ✅ | ✅ (Magnetometer der Brille) | Missweisung korrigieren |
| Teleprompter | ✅ | ✅ inkl. Teilen-Ziel | – |
| Sperrbildschirm beim Absetzen | ✅ | ⚠️ Display geht aus | echte Sperre bis Handy entsperrt |
| Sprachassistent (Wake-Word, STT, LLM, Tools) | ✅ | ❌ | Mikrofon (LC3), STT, Claude-Anbindung |
| Navigation (Mapbox), Nightscout | ✅ | ❌ | – |
| Terminal-Spiegelung (g2mirror) | ✅ | ❌ | – |
| EvenHub-Apps | ✅ (Emulation) | ❌ | – |
| Wear-OS-Uhr als Fernbedienung | ✅ | ❌ | – |
| Spiele, Rechner, Dateien | ✅ | ❌ | – |
| Firmware installieren/deinstallieren | ✅ | ❌ bewusst noch nicht | erst mit Hardware-Tests; Analyse und Sicherheits-Checkliste liegen in `docs/analysis/06-firmware.md` |
| Handy-Vorschau als Touchpad (auch ohne Brille) | ✅ | ✅ | – |
| Desktop-Simulator | – | ✅ neu | – |
| iOS | Beta | ❌ | Kern ist plattformneutral, eine iOS-Hülle wäre möglich |

## Bekannte Grenzen von 0.1

- Nicht auf echter Hardware getestet. Das Protokoll ist gegen dokumentierte Byte-Vektoren und eine
  simulierte Brille getestet, reale Zeitverhalten (BLE-Durchsatz, Kopplung) können abweichen.
- Solange das Display „aus“ ist, bleibt die Verbindung aktiv (Heartbeats), das kostet Akku auf der Brille.
- Akkuanzeige der Brille zeigt den Wert des rechten Bügels.
