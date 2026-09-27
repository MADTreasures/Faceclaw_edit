# Firmware installieren

Faceclaw Edit braucht auf der Brille die **Faceclaw-Custom-Firmware, Revision 34** (`Faceclaw/34`,
gebaut auf Evens Firmware 2.3.0.24). Die App kann sie selbst installieren – und genauso Evens
**Original-Firmware 2.3.0.24** wieder aufspielen.

> **Bitte zuerst lesen**
>
> - Eine Custom-Firmware **erlischt die Garantie**. Ein fehlgeschlagenes Update kann die Brille in
>   seltenen Fällen unbrauchbar machen.
> - Der Installer dieser App ist **noch nicht auf echter Hardware erprobt**. Er spricht exakt das
>   Protokoll der beiden erprobten Vorbilder (Faceclaw und g2flash) und ist gegen eine simulierte
>   Brille mit Fehlerfällen getestet. Das erzeugte Image ist nachweislich bitgenau identisch mit dem
>   geprüften Original-Image von g2flash (gleicher SHA-256).
> - Mach zuerst einen **Testlauf** (siehe unten). Er schreibt nichts auf die Brille.

## So geht's

1. Brille laden (beide Bügel mindestens 30 %), die **Even-App trennen** (dort: Home → Brille →
   Verbindung → Trennen), Brille und Handy nah beieinander.
2. In der App: **Settings → Glasses firmware…** (oder auf dem Startbildschirm „Install firmware…“,
   wenn die App die Firmware nicht unterstützt).
3. **Check glasses** zeigt die installierte Firmware beider Bügel und die Akkustände.
4. Den Garantie-Hinweis bestätigen und wählen:
   - **Install custom firmware** – installiert Faceclaw/34.
   - **Reinstall original firmware 2.3.0.24** – stellt Evens Firmware wieder her.
   - **Dry run** – Testlauf ohne Schreiben.
5. Die App lädt Evens Firmware (ca. 4,5 MB) von `cdn.evenreal.co`, prüft sie und baut daraus auf dem
   Handy die Custom-Firmware. Die Datei bleibt nur im privaten App-Speicher (für spätere Installationen)
   und wird nie weitergegeben.
6. Die App verbindet sich mit beiden Bügeln, liest Version und Akku und zeigt dann **auf der Brille**
   eine Frage an: mit dem Ring zu **„Yes, install“** scrollen und tippen. „No, cancel“ bricht ab,
   ohne etwas zu ändern.
7. Zuerst wird das **linke**, dann das **rechte** Glas beschrieben, je einige Minuten. Dazwischen startet
   die Brille neu. Die App lässt den Bildschirm an, Zurück ist gesperrt – bitte die App nicht schließen.
8. Danach verbindet sich die App erneut und prüft, ob die Brille die erwartete Firmware meldet
   („Done“). Bei der Custom-Firmware kannst du direkt verbinden.

Unter **Details** gibt es ein Protokoll mit Zeitstempeln („Copy“). Wenn etwas schiefgeht, füge es
einfach im Chat ein – daraus lässt sich fast immer ablesen, was passiert ist.

## Was die App vor dem Schreiben prüft

- Evens Firmware muss den festgelegten SHA-256 haben (`187ccf2b…0979`), die gebaute Custom-Firmware
  ebenfalls (`7d8764f8…0ee7`). Andere Images werden nie geschrieben – auch keine selbst gewählten Dateien.
- Das Image wird komplett geprüft: Aufbau, Prüfsumme jeder Komponente, Prüfsumme und Ladeadresse des
  Hauptprogramms und die Speichergrenze (die wichtigste Schutzregel gegen ein „totes“ Glas).
- Beide Bügel müssen erreichbar und angemeldet sein, jeder mit mindestens 30 % Akku.
- Neuere Original-Firmware als 2.3.0.24, unbekannte Versionen oder „schon installiert“ brauchen eine
  ausdrückliche Bestätigung (die App fragt nach).
- Die Bluetooth-Verbindung muss groß genug für die Update-Pakete sein.
- Die Bestätigung auf der Brille beweist, dass die richtige Brille verbunden ist.

Während des Schreibens: nur Update-Daten, keine anderen Befehle; ein Block wird nur nach einer
ausdrücklichen Ablehnung durch die Brille wiederholt. Bei einer Zeitüberschreitung oder einem
Verbindungsabbruch verbindet sich die App neu und beginnt das Glas von vorne – denn ein doppelt
angekommener Block würde das Image verschieben. Jede Komponente prüft die Brille selbst per Prüfsumme.

## Wenn etwas schiefgeht

| Meldung | Bedeutung | Was tun |
|---|---|---|
| Not started / Charge the glasses first | Akku unter 30 % oder unbekannt | laden, erneut starten |
| Could not connect / did not accept the connection | Bügel nicht erreichbar oder Kopplung nicht bestätigt | Brille einschalten, Even-App trennen, Kopplungsanfrage bestätigen |
| You declined on the glasses | „No, cancel“ gewählt | – nichts wurde geändert |
| Could not prepare the firmware | Download oder Prüfung fehlgeschlagen | Internetverbindung prüfen, erneut versuchen – nichts wurde geändert |
| Update failed | Schreiben eines Glases gescheitert | Brille laden, **erneut starten** – das Update beginnt für beide Gläser von vorne. Startet die Brille nicht mehr normal: die offizielle Even-App kann ihre Firmware neu installieren |
| Written, but not confirmed | Geschrieben, aber die Kontrolle danach klappte nicht | Brille kurz ins Etui und wieder heraus, dann „Check glasses“ |

Zurück zur Original-Firmware geht es jederzeit mit **Reinstall original firmware** – oder indem die
offizielle Even-App ein Update installiert (das entfernt die Custom-Firmware ebenfalls).

## Eigene Firmware-Änderungen

Die meisten Funktionen gehören **in die App**, nicht in die Firmware: Die Brille zeigt nur an, was das
Handy zeichnet, und meldet Eingaben. Firmware-Änderungen sind nur nötig, wenn die Brille selbst etwas
Neues können muss (z. B. einen Sensor auslesen, den die Custom-Firmware noch nicht weitergibt).

Solche Änderungen entstehen mit der Werkzeugkette von [g2flash](https://github.com/jimrandomh/g2flash)
(C-Quellen → neues Patch-Set). Für diese App heißt das dann: neues Patch-Set in
`core/src/main/resources/firmware/`, neuer festgelegter SHA-256 in `FirmwareCatalog`, eine eigene
Revisionskennung – und vor allem Tests auf echter Hardware, bevor jemand anderes es installiert.

## Technik

- Code: `core/src/main/kotlin/…/core/firmware/` (Image-Prüfung, Patch-Set, Flash-Protokoll,
  Vorabprüfung, Ablauf), `app/src/main/kotlin/…/app/firmware/` (Download, Dienst) und
  `app/…/ui/FirmwareScreen.kt`.
- Protokoll und Formate im Detail, mit Quellenangaben: [analysis/06-firmware.md](analysis/06-firmware.md).
- Test gegen die echte Original-Firmware (lokal, die Datei kommt nie ins Repo):
  `FACECLAW_STOCK_IMAGE=/pfad/g2_2.3.0.24.bin ./gradlew :core:test`
