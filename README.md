# Faceclaw Edit

Eigenständiger Neubau einer Begleit-App für die **Even Realities G2**-Brille, inspiriert von
[Faceclaw](https://github.com/jimrandomh/faceclaw), mit eigenem Design und eigener Architektur.

> **Status: in Arbeit.** Grafik-Engine, Schriften, UI-Baukasten, Shell und erste Apps
> stehen; Bluetooth-Protokoll, Android-App und Simulator folgen.

## Aufbau

| Modul | Inhalt |
|---|---|
| `core/` | Reines Kotlin (ohne Android): Grafik-Engine, Schriften, UI-Baukasten, Shell, Apps, Protokoll |
| `app/` | Android-App (Jetpack Compose, Bluetooth, Hintergrunddienst) |
| `simulator/` | Desktop-Simulator der Brillenanzeige |
| `tools/` | Werkzeuge, z. B. `./gradlew :tools:bakeFonts` (TTF → Bitmap-Schriften) |
| `docs/analysis/` | Technische Analyse der Originale (Protokolle, Formate) |

## Bauen

```bash
./gradlew :core:test            # Kern + Tests (JVM)
./gradlew :app:assembleDebug    # Android-APK (braucht Android SDK, ANDROID_HOME)
```

## Lizenz

GPLv3 (siehe `LICENSE`), wie die Originale. Schriften: Inter und JetBrains Mono (SIL OFL 1.1),
Material Icons (Apache 2.0); Lizenztexte liegen unter `tools/fonts/`.
