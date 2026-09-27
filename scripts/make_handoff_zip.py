#!/usr/bin/env python3
"""
Builds the German hand-off package (docs/uebergabe/) as a ZIP for starting a new chat:
knowledge about building and flashing the custom firmware, the web designer and how to hand
designs back, plus the detailed analysis docs and the Kotlin reference code.

  python3 scripts/make_handoff_zip.py [OUT.zip]      (default: build/Faceclaw-Uebergabe.zip)

Never includes Even's firmware.
"""
import glob
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "Faceclaw-Uebergabe/"
KT = "core/src/main/kotlin/com/madtreasures/faceclaw/core/"
APP = "app/src/main/kotlin/com/madtreasures/faceclaw/app/"

REFERENCE_README = """# Referenz-Code (Kotlin)

Auszug aus Faceclaw Edit (GitHub `MADTreasures/Faceclaw_edit`, Branch
`claude/faceclaw-app-rebuild-hnrz4y`, GPLv3). Zum Lesen und Wiederverwenden – das vollständige,
baubare Projekt liegt im Repo.

| Ordner | Inhalt |
|---|---|
| `core/firmware/` | reines Kotlin, ohne Android: `EvenOtaImage` (Image-Prüfung und Brick-Schutz), `PatchSet` + `FirmwareCatalog` (Erlaubnisliste mit SHA-256), `OtaProtocol` + `OtaFlasher` (Flashen), `FirmwarePreflight` (Versionen, Akku, Bestätigung auf der Brille), `FirmwareInstaller` (ganzer Ablauf), `FirmwareLink` (Schnittstelle zur Bluetooth-Anbindung) |
| `core/protocol/` | G2-Protokoll: Rahmen und CRC (`Envelope`, `Bytes`), Protobuf (`Proto`), Nachrichten (`G2Messages`, `G2Events`), Custom-Firmware-Übertragung und Zeichenbefehle (`CfwTransport`, `CfwDraw`, `FrameEncoder`), Verbindungs-Ablauf (`GlassesSession`) |
| `android/` | Android-GATT (`AndroidBleLink`, auch für Wear OS geeignet), Download-Cache, Vordergrund-Dienst, Firmware-Seite (Compose) |
| `tests/` | Byte-Beispiele, Image-Prüfungen, und `FakeOtaGlasses`: eine simulierte Brille mit Fehlerfällen (verlorene Quittung, Abbruch, CRC-Fehler, Neustart) |

Für eine Uhr-App (Wear OS) sind `core/firmware/` und `core/protocol/` unverändert nutzbar; es braucht
nur eine `FirmwareLink`-Implementierung (z. B. `AndroidBleLink`) und eine eigene Oberfläche.
"""

LICENSE_NOTE = """# Lizenz und Herkunft

- `cfw_patches.json` ist eine unveränderte Kopie von `patches/cfw_patches.json` aus
  [g2flash](https://github.com/jimrandomh/g2flash) (Stand 25.09.2026, GPLv3). Es enthält nur
  Byte-Änderungen und den eingefügten Code der Custom-Firmware, **nicht** Evens Firmware.
- `cfw_bauen.py` gehört zu Faceclaw Edit (GPLv3) und ist eine unabhängige Neuimplementierung der
  Bau- und Prüfregeln (im Repo: `scripts/cfw_build.py`).
- Evens Firmware (`g2_2.3.0.24.bin`) ist Eigentum von Even Realities. Sie wird von Evens Server
  geladen und darf nicht weitergegeben werden. Eine Custom-Firmware erlischt die Garantie.

Benutzung:

```bash
python3 cfw_bauen.py                    # Original laden, Faceclaw/34 bauen, alles prüfen
python3 cfw_bauen.py --stock DATEI      # vorhandenes Original verwenden
python3 cfw_bauen.py --check DATEI      # beliebiges Image prüfen (Prüfsummen, Speichergrenze)
```
"""


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "Faceclaw-Uebergabe.zip")
    entries = []

    def add(src, arc, fix=None):
        data = open(os.path.join(ROOT, src), "rb").read()
        if fix:
            data = fix(data.decode("utf-8")).encode("utf-8")
        entries.append((arc, data))

    def add_text(arc, text):
        entries.append((arc, text.encode("utf-8")))

    for f in sorted(glob.glob(os.path.join(ROOT, "docs/uebergabe/*.md"))):
        add(os.path.relpath(f, ROOT), os.path.basename(f))
    add("docs/design-format.md", "05_Entwurfsformat.md",
        lambda t: t.replace("(../web/designer/README.md)", "(designer/README.md)"))

    add("web/designer/index.html", "designer/index.html")
    add("web/designer/designer.html", "designer/designer.html")
    add("web/designer/beispiel-entwurf.json", "designer/beispiel-entwurf.json")
    add("web/designer/README.md", "designer/README.md",
        lambda t: t.replace("(../../docs/design-format.md)", "(../05_Entwurfsformat.md)")
                   .replace("`./build.sh` erzeugt daraus", "Im Repo erzeugt `web/designer/build.sh` daraus"))

    add("core/src/main/resources/firmware/cfw_patches.json", "firmware/cfw_patches.json")
    add("scripts/cfw_build.py", "firmware/cfw_bauen.py")
    add_text("firmware/LIZENZ.md", LICENSE_NOTE)

    for f in sorted(glob.glob(os.path.join(ROOT, "docs/analysis/*.md"))):
        add(os.path.relpath(f, ROOT), "wissen/" + os.path.basename(f))
    add("docs/firmware.md", "wissen/faceclaw-edit-firmware-seite.md",
        lambda t: t.replace("(analysis/06-firmware.md)", "(06-firmware.md)"))

    for f in sorted(glob.glob(os.path.join(ROOT, KT + "firmware/*.kt"))):
        add(os.path.relpath(f, ROOT), "referenz-code/core/firmware/" + os.path.basename(f))
    for name in ["BleLink.kt", "Bytes.kt", "Proto.kt", "Envelope.kt", "G2Messages.kt", "G2Events.kt",
                 "CfwTransport.kt", "CfwDraw.kt", "FrameEncoder.kt", "Advertisement.kt", "GlassesSession.kt"]:
        add(KT + "protocol/" + name, "referenz-code/core/protocol/" + name)
    add(APP + "ble/AndroidBleLink.kt", "referenz-code/android/AndroidBleLink.kt")
    for f in sorted(glob.glob(os.path.join(ROOT, APP + "firmware/*.kt"))):
        add(os.path.relpath(f, ROOT), "referenz-code/android/firmware/" + os.path.basename(f))
    add(APP + "ui/FirmwareScreen.kt", "referenz-code/android/FirmwareScreen.kt")
    for f in sorted(glob.glob(os.path.join(ROOT, "core/src/test/kotlin/com/madtreasures/faceclaw/core/firmware/*.kt"))):
        add(os.path.relpath(f, ROOT), "referenz-code/tests/" + os.path.basename(f))
    add_text("referenz-code/LIES_MICH.md", REFERENCE_README)

    for arc, data in entries:
        if arc.endswith(".bin") or b"EVENOTA\x00" in data:
            raise SystemExit(f"refusing to package firmware data: {arc}")

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for arc, data in entries:
            info = zipfile.ZipInfo(BASE + arc, date_time=(2026, 9, 27, 23, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    print(f"wrote {out}: {len(entries)} files, {os.path.getsize(out):,} bytes")


if __name__ == "__main__":
    main()
