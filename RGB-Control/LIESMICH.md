# RGB Control 2

Eine App für die Beleuchtung **und** die wichtigsten Live-Werte deines PCs.

## Was die App zeigt und steuert

| Bereich | Inhalt |
|---|---|
| **Dashboard** | CPU-, GPU- und Wasserpumpen-Temperatur als Anzeigen mit Verlauf · RAM-Auslastung (GB und %) · Maus-Akku · alle Kategorien mit Live-Vorschau und Ein/Aus-Schalter · „Alles synchronisieren“ |
| **GPU** | RGB der Grafikkarte · Temperatur, Auslastung, VRAM |
| **RAM** | RGB der RAM-Riegel · belegter, freier und gesamter Speicher |
| **Mouse** | RGB der Maus · **Akkustand** (mit Lade-Anzeige) |
| **Keyboard** | RGB der Tastatur |
| **Fans** | RGB von Lüftern, Lüfter-Controllern und ARGB-Anschlüssen · Drehzahlen |
| **Water Pump** | RGB der AIO-Pumpe · **Flüssigkeitstemperatur** und Pumpen-Drehzahl |

In jeder Kategorie gibt es eigene Effekte (Statisch, Atmen, Regenbogen, Regenbogen-Welle,
Farbwechsel, Farbverlauf, Welle, Komet, Blinken, Aus), Farbrad, Helligkeit und Tempo.
Einzelne Zonen lassen sich abschalten. Die eingebauten Hardware-Modi eines Geräts kannst
du auch im Gerät speichern.

Werkzeuge: **Andere Apps** findet iCUE, Armoury Crate, Mystic Light & Co. und beendet bzw.
deinstalliert sie · **Hardware-Scan** · **Profile** · **Einstellungen** (Autostart, Tray …).

**Leuchtet nichts?** Einstellungen → **Diagnose**: „Testblitz“ lässt alle erkannten Geräte 2 Sekunden rot
leuchten. Die Diagnose zeigt zudem Verbindung, Adminrechte, PawnIO-Treiber und Fehler pro Gerät. Die
Logdatei liegt in `%APPDATA%\RGBControl\rgbcontrol.log` (OpenRGB-Ausgabe: `openrgb.log`).

## Woher die Daten kommen

- **Beleuchtung:** OpenRGB, unsichtbar im Hintergrund. Es enthält die Treiber für hunderte Geräte.
- **Temperaturen und Drehzahlen:** LibreHardwareMonitor. Wird beim Bauen automatisch eingebunden.
  - CPU: braucht zusätzlich den kostenlosen Treiber **PawnIO** (https://pawnio.eu). Ohne ihn bleibt die CPU-Anzeige leer oder ungenau.
  - GPU: NVIDIA, AMD und Intel.
  - Wasserpumpe: AIOs mit eigenem Controller, z. B. Corsair (iCUE Link, Commander, Hydro), NZXT Kraken, Lian Li, Aqua Computer, oder ein Pumpen-Sensor am Mainboard.
- **Maus-Akku:**
  - Bluetooth-Mäuse über Windows.
  - Logitech über HID++ (Lightspeed, Unifying, Bolt, Kabel).
  - Razer über HyperSpeed, Dock oder Kabel.
  - Mäuse, deren Akku nur über die Hersteller-App lesbar ist (z. B. Corsair Slipstream, SteelSeries), werden nicht angezeigt.
- **RAM-Auslastung:** direkt von Windows.

## .exe bauen

**Am einfachsten über GitHub:** Der Workflow `.github/workflows/…build.yml` baut die .exe auf
einem Windows-Rechner von GitHub. Er bindet LibreHardwareMonitor ein, führt alle Tests aus und
**startet die fertige .exe zur Probe**. Unter *Actions* → letzter Lauf → *Artifacts* →
`RGBControl-Windows` herunterladen.

**Lokal:** Python 3.10+ und das .NET SDK 8 installieren, dann `build_exe.bat` doppelklicken.

Der GitHub-Build legt **OpenRGB** (Ordner `OpenRGB`) und den **PawnIO-Installer** (Ordner `drivers`)
automatisch dazu. Immer die komplette ZIP entpacken, nicht nur die .exe kopieren. Beim lokalen Bauen
OpenRGB selbst von https://openrgb.org/releases.html entpacken, sodass `OpenRGB\OpenRGB.exe` neben
`RGBControl.exe` liegt.

## Wichtig für beste Ergebnisse

- **Als Administrator starten.** Die .exe fragt automatisch danach. Ohne Adminrechte fehlen RAM, Mainboard und Temperaturen.
- **PawnIO installieren** (Knopf in der App). Ohne PawnIO findet OpenRGB keine RAM- und Mainboard-Beleuchtung, und die CPU-Temperatur fehlt. Danach den PC neu starten.
- **Andere RGB-Programme beenden oder deinstallieren** (Seite „Andere Apps“). Sonst überschreiben sie die Farben.
- **ARGB-Lüfter am Mainboard:** Auf der Seite *Fans* bei Anschlüssen mit „0 LEDs“ auf **LEDs festlegen** klicken. Die App merkt sich die Anzahl.
- Einige AIOs werden gleichzeitig von OpenRGB (RGB) und LibreHardwareMonitor (Sensoren) angesprochen. Bei Problemen: Einstellungen → „Wasserkühlung direkt auslesen“ ausschalten.

Einstellungen und Profile liegen in `%APPDATA%\RGBControl`.

## Lizenzen

PySide6 (LGPL) · openrgb-python (GPL-3.0) · psutil (BSD) · hidapi (BSD/GPL) · pythonnet (MIT) ·
nvidia-ml-py (BSD) · LibreHardwareMonitorLib (MPL-2.0) · OpenRGB (GPL-2.0).
