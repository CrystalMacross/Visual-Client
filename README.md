# Visual Client (Minecraft 1.21.11, Fabric)

Ein clientseitiger Visual-Mod mit modernem Menü und HUD. Alle Module sind in vier Kategorien sortiert:
**Combat, Visuals, Player, Misc**. Im Spiel öffnest du das Menü mit **Rechts-Shift**.

> **Wichtiger Hinweis zum Stand:** Dieser Quellcode wurde ohne laufendes Minecraft geschrieben. Die API-Aufrufe
> wurden gegen die dokumentierten 1.21.11-Signaturen (Mojang-Namen) geprüft und der Code läuft in einem Simulator durch,
> aber er wurde nie mit dem echten Spiel gebaut oder gestartet. Meldet der Build einen Fehler oder tut ein Modul nichts,
> schick mir die Fehlermeldung bzw. die `logs/latest.log` – kleine Namensfehler sind dann schnell behoben.
> Die Mixins sind absichtlich „nicht erforderlich“: Passt ein Hook nicht, fehlt nur dieses eine Feature und das Spiel startet trotzdem.

## Installieren
1. **Fabric Loader** (0.18 oder neuer) und **Fabric API** für Minecraft 1.21.11 installieren.
2. `visual-client-1.0.0.jar` in den Ordner `.minecraft/mods` legen.
3. Spiel starten, in einer Welt **Rechts-Shift** drücken. Die Taste kannst du unter *Steuerung → Visual Client* ändern.

## Bauen (die Jar selbst erzeugen)
Es liegt kein `gradle-wrapper.jar` bei (ich kann keine Binärdatei herunterladen). Drei Wege:

* **Ohne Installation, per GitHub:** Neues Repository anlegen, diesen Ordner hochladen, Reiter *Actions* → *Build Visual Client*
  laufen lassen → unter *Artifacts* `visual-client-1.21.11` herunterladen.
* **IntelliJ IDEA:** Ordner als Gradle-Projekt öffnen (JDK 21), Task `build` ausführen.
* **Konsole:** JDK 21 und Gradle 9.2.1 installieren, dann `gradle build`. Die fertige Datei liegt in `build/libs/`
  (nimm die ohne `-sources`). Einmalig `gradle wrapper` erzeugt auf Wunsch `gradlew`.

## Bedienung
* **Linksklick** auf ein Modul schaltet es ein/aus, **Rechtsklick** öffnet seine Einstellungen. Panels lassen sich am Kopf ziehen.
* **Farben per HEX:** Jede Farbeinstellung hat ein Feld mit dem HEX-Code (z. B. `#C77DFF`). Reintippen oder mit Strg+V einfügen,
  ab 6 Ziffern wird es sofort live übernommen. Ein Klick auf das Farbfeld öffnet zusätzlich den Picker.
  Das gilt für **Sky Color**, **Damage Tint**, **Armor Glint**, das **Theme** und alle HUD-Farben.
* **Edit HUD layout** (unten im Menü): HUD-Boxen per Maus verschieben, sie rasten an den Bildschirmrändern ein.
* Die Einstellungen liegen in `config/visualclient.json`.

## Module
| Kategorie | Modul | Was es macht |
|---|---|---|
| Combat | Damage Tint | Farbe (HEX) und Stärke des Treffer-Flashs bei Spielern und Mobs |
| Combat | Armor HUD | Rüstung (optional Hände) mit Haltbarkeit in % oder Wert, Balken, vertikal/horizontal |
| Combat | Totem Counter | Box mit Totem-Icon und Anzahl (Inventar und Off-Hand) |
| Combat | Potion HUD | Restdauer unter den Effekt-Kästen (Modus „Auto“ setzt sie in die Box, wenn eine zweite Reihe überdecken würde) |
| Visuals | Weather Changer | Klar/Regen/Gewitter, nur auf deinem Bildschirm, mit weichem Übergang |
| Visuals | Sky Color | Himmelsfarbe per HEX, optional Nebel und Wolken mitfärben, Tag/Nacht bleibt erhalten |
| Visuals | Armor Glint | Farbe des Verzauberungs-Glanzes (HEX oder Regenbogen), Rüstung und/oder Items |
| Visuals | Pack Mixer | Ressourcenpakete aus deinem Ordner mischen, Reihenfolge, Teile pro Pack (Blöcke, Items, Rüstung, Interface, Sounds, …), Presets |
| Visuals | Fullbright | Alles hell (Gamma oder Nachtsicht-Methode) |
| Visuals | Hide Armor | Rüstung ausblenden (bei dir, anderen Spielern oder allen), Elytra optional sichtbar |
| Player | Auto Sprint | Hält die Sprint-Taste, solange du vorwärts läufst |
| Player | Saturation Overlay | Sättigung auf der Hungerleiste (auf den Icons oder als Balken) |
| Player | Shulker Tooltips | Alle Items und Mengen einer Shulkerbox im Tooltip |
| Misc | FPS Counter | FPS-Anzeige |
| Misc | Ping Display | Latenz zum Server |
| Misc | Music HUD | Aktueller Song vom PC mit Fortschritt und optional synchronen Lyrics |
| Misc | Discord RPC | „Spielt Minecraft“ im Discord-Profil |
| Misc | Theme | Akzent-, Hintergrund- und Textfarbe, Rundung, HUD-Deckkraft |

## Discord RPC einrichten
1. Auf <https://discord.com/developers/applications> eine neue Application anlegen (Name z. B. „Minecraft“).
2. Die **Application ID** kopieren und im Modul *Discord RPC* bei „Application ID“ einfügen.
3. Optional unter *Rich Presence → Art Assets* ein Bild namens `logo` hochladen.
Der Mod spricht nur mit der Discord-App auf deinem PC, es wird keine Bibliothek nachgeladen.

## Music HUD und Datenschutz
* **Windows:** liest die System-Mediensitzung über ein kleines PowerShell-Skript (Spotify, YouTube Music im Browser usw.).
  **Linux:** braucht `playerctl`. **macOS:** Spotify oder Music.
* Der Songtitel bleibt auf deinem PC. **Nur** wenn *Lyrics* an ist, werden Titel, Interpret, Album und Länge an `lrclib.net` gesendet, um die Lyrics zu laden.

## Pack Mixer
Oben in der Liste gewinnt. Klick auf ein Pack im Mix zeigt Chips für die Teile, die es beisteuern darf (z. B. nur Items und Rüstung).
*Apply and reload* speichert die Auswahl wie das normale Ressourcenpaket-Menü und lädt neu. Die Teile-Filter gelten nur, solange das Modul an ist.

## 60 FPS
Alle Animationen laufen zeitbasiert (flüssig bei jeder Bildrate). Ein kompletter HUD-Frame mit allen sieben HUD-Modulen kostet in meinem Test
etwa 7 µs eigene Rechenzeit und rund 120 Zeichenbefehle. Musik, Lyrics und Discord laufen in Hintergrund-Threads.

## Aufbau
`src/main/java/net/visualclient`: `core` (Module, Einstellungen, Config), `modules` (alle Features), `hud`, `gui`, `mixin`, `media`, `discord`, `pack`, `glint`.
Gebaut mit Loom `net.fabricmc.fabric-loom-remap` und den offiziellen Mojang-Namen. Lizenz: MIT.

## Vorschau
In `docs/` liegen Bilder von HUD, Menü, Pack Mixer und HUD-Editor. Sie wurden aus den echten Zeichenbefehlen dieses Codes gerendert,
sind aber **kein Screenshot aus Minecraft**: Item-Icons sind Platzhalter-Kacheln, die Schrift ist nur ähnlich.
