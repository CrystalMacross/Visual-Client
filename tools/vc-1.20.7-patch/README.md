# Visual Client 1.20.7 patch

Everything that changed since Visual Client 1.20.4 (1.20.5 to 1.20.7). The release jar is the 1.20.4 bundle with these
classes compiled per Minecraft build (`vc-build.ps1`) and swapped into the eight nested jars (`vc-patch.ps1`).

## 1.20.7
- **Discord Status module** (`modules/misc/PresenceModule.java`, registered in `modules/Modules.java`, category Misc,
  on by default). Once a second it checks the current state and writes it to `<game dir>/visualclient/presence.json`
  when something changed (at least every 15 seconds): menu / singleplayer / multiplayer, server address and name,
  world name, dimension and biome. Visual Launcher 1.10.11 reads the file and shows the server, biome and dimension in
  the Discord status. Settings: Share biome, Share world name. Turning the module off deletes the file. Nothing is
  sent anywhere by the mod.
- `rename-extra26.txt`: `method_27728()` -> `getWorldData()` for the 26.x builds.

## 1.20.6
- **Three modern menus** (`gui/ModernGuiScreen.java`): GUI Style -> UI now has Old, New, Glass, Neon and Spotlight.
  Glass = light frosted panel with category pills and module cards; Neon = dark neon panel with a category sidebar
  and settings that open in place; Spotlight = command palette (search first, grouped results, arrow keys, Enter
  toggles, right arrow opens settings). All show every module and every setting type.
  `core/ExtraSettings.java` and `gui/GuiOpener.java` pick the screen.
- **Nicer sakura title panorama**: `SakuraPanorama.java` re-renders the six cube faces in direction space (seamless):
  deep indigo sky with a milky way and stars, moon halo, bloom on lanterns, richer sakura pinks, falling petals and
  fireflies.

## 1.20.5
- Crash with coloured fireworks fixed (`mixin/ParticleMixin.java`, `hooks/ParticleGate.java`).
- Texture Painter fixes (`gui/TexturePainterScreen.java`, `gui/TexturePainterExtras.java`).
- Pins module (`modules/misc/PinModule.java`), key J.
