# Datenschutzhinweis – Visual Launcher

Stand: 21. November 2026

**Kurz gesagt: Der Launcher schickt keine Daten an den Entwickler.** Es gibt keinen Server des Visual-Client-Projekts, keine Telemetrie, keine Analyse-Tools, keine Werbung und keine automatischen Absturzberichte.

## 1. Was lokal auf deinem Gerät gespeichert wird

Im Benutzerdatenordner des Launchers werden gespeichert:

- **Kontodaten:** Minecraft-Name, UUID, Skin-Adresse und das Microsoft-Aktualisierungstoken. Das Token wird mit der Verschlüsselung deines Betriebssystems gesichert (Windows DPAPI, macOS-Schlüsselbund, Linux Secret Service), sofern verfügbar.
- **Einstellungen:** Design, Farben, Hintergrundbild, Arbeitsspeicher, Fenstergröße und dein optionaler CurseForge-API-Schlüssel (ebenfalls verschlüsselt).
- **Spieldaten:** Minecraft, Fabric, Java, Instanzen, Mods und Welten.

Mit „Abmelden“ bzw. dem Löschen des Datenordners entfernst du diese Daten vollständig.

## 2. Mit wem der Launcher direkt verbunden ist

Der Launcher stellt Verbindungen nur zu folgenden Diensten her, und nur, wenn die jeweilige Funktion genutzt wird:

- **Microsoft / Xbox Live** (login.live.com, user.auth.xboxlive.com, xsts.auth.xboxlive.com) – Anmeldung
- **Mojang / Minecraft-Dienste** (api.minecraftservices.com, piston-meta.mojang.com, launchermeta.mojang.com, libraries.minecraft.net, resources.download.minecraft.net, textures.minecraft.net) – Kontoprüfung, Spieldateien, Java, Skins
- **Fabric** (meta.fabricmc.net, maven.fabricmc.net) – Mod-Loader
- **Modrinth** (api.modrinth.com, cdn.modrinth.com) – Suche und Downloads
- **CurseForge** (api.curseforge.com, edge.forgecdn.net, media.forgecdn.net) – nur mit eigenem API-Schlüssel

Bei diesen Verbindungen erhalten die Anbieter technisch notwendige Daten wie deine IP-Adresse und bei der Anmeldung deine Kontodaten. Dafür gelten deren Datenschutzerklärungen, z. B. die Datenschutzerklärung von Microsoft. Wir haben auf diese Daten keinen Zugriff.

## 3. Minecraft selbst

Wenn du das Spiel startest, verarbeitet Minecraft Daten nach den Regeln von Mojang/Microsoft, etwa für Multiplayer, Chat-Meldungen oder Telemetrie des Spiels. Das ist nicht Teil des Launchers.

## 4. Deine Rechte

Da wir keine personenbezogenen Daten von dir erhalten oder speichern, gibt es bei uns nichts, worüber wir Auskunft geben oder was wir löschen könnten. Deine lokalen Daten verwaltest du selbst. Für Daten bei Microsoft, Modrinth oder CurseForge wende dich an den jeweiligen Anbieter.
