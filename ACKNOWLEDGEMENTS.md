# Danksagungen

- **Faceclaw** von James Babcock und Mitwirkenden — https://github.com/jimrandomh/faceclaw (GPLv3).
  Protokoll-Wissen, Abläufe und die Custom-Firmware-Schnittstelle stammen aus der Analyse dieses
  Projekts (siehe `docs/analysis/`).
- **g2flash** — https://github.com/jimrandomh/g2flash (GPLv3): Custom-Firmware, mit der diese App spricht.
  Das Patch-Set `core/src/main/resources/firmware/cfw_patches.json` ist eine unveränderte Kopie von
  `g2flash/patches/cfw_patches.json` (Stand 25.09.2026, SHA-256 `81c5148d…e5f2`); der Flash-Ablauf folgt
  `g2flash.py` und Faceclaws `OtaFlashFlow.kt`. Evens Firmware selbst ist nicht enthalten, sie wird zur
  Laufzeit von Evens Server geladen.
- **g2-kit-unofficial** von Commute773 — https://github.com/Commute773/g2-kit-unofficial (MIT):
  unabhängige Dokumentation des Stock-Protokolls.
- **evenRealities-openCFW** — https://github.com/kalanihelekunihi/evenRealities-openCFW/

Schriften:
- **Inter** von Rasmus Andersson — SIL Open Font License 1.1 (`tools/fonts/inter/LICENSE.txt`).
- **JetBrains Mono** — SIL Open Font License 1.1 (`tools/fonts/jetbrains-mono/OFL.txt`).
- **Material Icons** von Google — Apache License 2.0 (`tools/fonts/material-icons/LICENSE`).

Designer (`web/designer/`, nur im Browser geladen, nicht im Repo):
- **Inter**, **JetBrains Mono**, **IBM Plex Sans/Mono** über Google Fonts — SIL Open Font License 1.1.
- **Material Icons** über Google Fonts — Apache License 2.0.
