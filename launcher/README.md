# Visual Launcher

Moderner Minecraft-Launcher mit eingebautem **Visual Client**.

- **Microsoft-Login** (offizielles Microsoft-Anmeldefenster, mehrere Konten)
- **Instanzen** mit Fabric – Visual Client + Fabric API werden automatisch installiert (MC 1.21.4 – 26.3)
- **Modrinth-Bibliothek**: Mods, Ressourcenpakete, Shader, Modpacks (.mrpack) inkl. Abhängigkeiten
- **Design**: Hintergrund als Verlauf / Farbe per Hex-Code oder eigener Screenshot, Akzentfarbe, Abdunkeln, Unschärfe
- **Discord Rich Presence** (abschaltbar): „Playing: <Server-IP>“ / Einzelspieler, erkannt aus dem Spielprotokoll – ohne Mod
- **Java automatisch**: lädt die offizielle Mojang-Java-Laufzeit passend zur Version
- **Schnellstart**: Anmeldung wird bis kurz vor Ablauf wiederverwendet (und im Hintergrund erneuert), Fabric-/Versionsdaten
  und die Java-Prüfung werden zwischengespeichert, Assets werden über Größe statt per Prüfsumme geprüft, Java-Klassen-Cache
  (AppCDS) ab Java 21 – abschaltbar in den Einstellungen
- **Keine Daten an den Entwickler**: keine Telemetrie, kein eigener Server – siehe `legal/PRIVACY.md`

## Entwickeln

```bash
cd launcher
npm install
npm start          # kopiert die neueste visual-client-*.jar aus dem Repo und startet den Launcher
```

## Installer bauen

```bash
npm run dist:win     # Windows (NSIS-Installer + portable .exe)
npm run dist:mac     # macOS (.dmg)
npm run dist:linux   # Linux (.AppImage)
```

Am einfachsten über GitHub Actions: einen Tag `launcher-v1.0.0` pushen → der Workflow
`.github/workflows/launcher.yml` baut alle drei Systeme und erstellt ein GitHub-Release mit den Dateien.

## Neue Visual-Client-Version einbauen

Neue `visual-client-<version>.jar` ins Repo-Hauptverzeichnis legen. `npm start` / `npm run dist`
nimmt automatisch die höchste Version und installiert sie beim nächsten Spielstart in alle Instanzen.

## Aufbau

| Datei | Zweck |
| --- | --- |
| `main.js` | Fenster, IPC, Sicherheit (Sandbox, nur erlaubte externe Links) |
| `preload.js` | schmale API für die Oberfläche (`window.vl`) |
| `src/main/auth.js` | Microsoft → Xbox → Minecraft Login (msmc), Token verschlüsselt lokal |
| `src/main/launch.js` | Spielstart (minecraft-launcher-core) mit Fabric + Visual Client |
| `src/main/discord.js` | Discord Rich Presence (lokale Discord-IPC, Server-Erkennung aus dem Log) |
| `src/main/java.js` | Download der Mojang-Java-Laufzeit |
| `src/main/versions.js` | Minecraft-/Fabric-Versionen, Visual Client, Fabric API |
| `src/main/modrinth.js` | Bibliothek, Installation, Modpacks |
| `renderer/` | Oberfläche (HTML/CSS/JS) |
| `legal/` | Terms of Service, Terms of Use, Datenschutz |
