# Visual Client 1.20.6 patch

Everything that changed since Visual Client 1.20.4 (1.20.5 + 1.20.6). The release jar is the 1.20.4 bundle with these
classes compiled per Minecraft build (`vc-build.ps1`) and swapped into the eight nested jars (`vc-patch.ps1`).

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