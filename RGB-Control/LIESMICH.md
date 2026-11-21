# RGB Control – eine App für alle RGB-Geräte im PC

Steuert die Beleuchtung von **Lüftern, Arbeitsspeicher (RAM), Grafikkarte, Mainboard,
LED-Streifen, AIO-Kühlern, Tastatur, Maus, Headset …** – alles in **einer** App.
Danach können Hersteller-Programme wie iCUE, Armoury Crate, Mystic Light,
RGB Fusion, NZXT CAM, G HUB oder Synapse weg.

## Funktionen

| Seite | Was sie macht |
|---|---|
| **Beleuchtung** | Zeigt alle erkannten RGB-Geräte mit ihren Zonen (z. B. „Lüfter 1–3“). Haken setzen, Effekt + Farbe wählen. Effekte laufen **synchron über alle Geräte** (z. B. eine Regenbogen-Welle von RAM über Grafikkarte bis zu den Lüftern). Live-Vorschau aller LEDs. |
| | Effekte: Statisch, Atmen, Regenbogen, Regenbogen-Welle, Farbwechsel, Farbverlauf, Welle, Komet/Lauflicht, Blinken, Aus. Dazu Helligkeit, Tempo, 2 Farben und Schnellfarben. |
| | **Hardware-Modus**: die eingebauten Modi eines Geräts nutzen und auf Wunsch **im Gerät speichern** (läuft dann auch ohne App). |
| | Rechtsklick auf eine Zone → **LED-Anzahl festlegen** (für ARGB-Anschlüsse am Mainboard). |
| **Hardware-Scan** | Durchsucht den PC (Mainboard, Grafikkarte, RAM-Riegel, USB-Geräte von über 30 RGB-Herstellern) und zeigt, was RGB hat und ob es in der App steuerbar ist. |
| **Andere RGB-Apps** | Findet über 25 RGB-Programme (installiert und/oder laufend). Mit einem Klick **beenden** oder **deinstallieren** (startet den offiziellen Deinstaller, immer mit Rückfrage). Beim Start wird automatisch gewarnt, wenn so ein Programm läuft. |
| **Profile** | Zustände speichern/laden („Gaming“, „Nacht“ …), auch über das Tray-Symbol. |
| **Einstellungen** | Mit Windows starten (ohne UAC-Abfrage), im Tray weiterlaufen, letzte Beleuchtung beim Start wiederherstellen, Demo-Modus. |

## Wie das technisch funktioniert

Jeder Hersteller spricht seine Hardware anders an (SMBus für RAM/Mainboard,
I²C für Grafikkarten, USB-HID für Lüfter-Controller und Peripherie). Diese
Treiber für **hunderte Geräte** gibt es schon im Open-Source-Projekt **OpenRGB**.
RGB Control startet OpenRGB **unsichtbar im Hintergrund** als Dienst und
bringt die eigene Oberfläche, synchrone Effekte, Profile, Hardware-Scan und
das Aufräumen anderer Apps mit. Du siehst OpenRGB selbst nie.

## Einrichten und .exe bauen (Windows)

1. **Python installieren** (3.10 oder neuer) von python.org – beim Setup den Haken
   **„Add python.exe to PATH“** setzen.
2. **OpenRGB besorgen:** auf https://openrgb.org/releases.html die Windows-Version (64-Bit)
   herunterladen, entpacken und den Ordner so umbenennen, dass hier liegt:
   `RGB-Control\OpenRGB\OpenRGB.exe`
   (Alternativ OpenRGB normal installieren – dann wird es unter „Programme“ automatisch gefunden.)
3. Doppelklick auf **`build_exe.bat`**. Nach ein paar Minuten liegt die fertige App in
   `dist\RGBControl\RGBControl.exe` (der OpenRGB-Ordner wird automatisch mitkopiert).
   Immer den **ganzen Ordner** `dist\RGBControl` verschieben, nicht nur die .exe.

Ohne .exe testen: Doppelklick auf `start_ohne_exe.bat`.

Ausprobieren ganz ohne RGB-Hardware: in den Einstellungen **Demo-Modus** einschalten
(oder `python main.py --demo`).

## Wichtig für beste Erkennung

- Die App fragt beim Start nach **Administratorrechten** – die sind nötig, damit
  RAM und Mainboard (SMBus) gefunden werden. Die .exe fordert sie automatisch an.
- **Andere RGB-Programme beenden/deinstallieren** (Seite „Andere RGB-Apps“), sonst
  blockieren sie die Hardware oder überschreiben die Farben. Danach am besten einmal neu starten.
- Für RAM-/Mainboard-Zugriff installiert OpenRGB beim ersten Start ggf. seinen
  SMBus-Treiber (aktuelle Versionen nutzen „PawnIO“) – die Abfrage bestätigen.
- In manchen BIOS-Versionen muss die RGB-Steuerung durch Software erlaubt sein.
- Ein Gerät fehlt? Hardware-Scan → zeigt „nicht erkannt“. Dann prüfen, ob es in der
  Liste unterstützter Geräte auf openrgb.org steht. Komplett ohne Hersteller-Protokoll
  (z. B. einfache 3-Pin-ARGB-Lüfter ohne Controller) geht es nur über den ARGB-Anschluss
  des Mainboards – den steuert die App dann mit (Zone „ARGB Header“).

## Dateien

```
main.py                  Startpunkt
rgbcontrol/effects.py    Effekte
rgbcontrol/engine.py     Effekt-Engine (30 FPS, synchron über alle Geräte)
rgbcontrol/backend.py    Anbindung an den OpenRGB-Dienst + Demo-Geräte
rgbcontrol/openrgb_service.py  OpenRGB finden/unsichtbar starten, Adminrechte
rgbcontrol/hwscan.py     Hardware-Scan
rgbcontrol/conflicts.py  Andere RGB-Apps erkennen/beenden/deinstallieren
rgbcontrol/config.py     Einstellungen, Profile, Autostart
rgbcontrol/ui/           Oberfläche (Qt / PySide6)
tests/                   Tests (python -m pytest tests)
```

Einstellungen und Profile liegen in `%APPDATA%\RGBControl`.

## Lizenzen

RGB Control nutzt PySide6 (LGPL), openrgb-python (GPL-3.0) und psutil (BSD).
OpenRGB steht unter GPL-2.0. Für den privaten Gebrauch ist das kein Problem. Wenn du
die App weitergibst, musst du die Lizenzen beilegen (und den Quellcode anbieten).
