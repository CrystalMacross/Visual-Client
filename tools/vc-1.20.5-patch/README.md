# Visual Client 1.20.5 patch

Sources of everything that changed between Visual Client 1.20.4 and 1.20.5. The release jar
`visual-client-1.20.5.jar` is the 1.20.4 bundle with these classes compiled per Minecraft build
(`vc-build.ps1`) and swapped into the eight nested jars (`vc-patch.ps1`).

## Fixes
- **Crash with coloured fireworks** (`mixin/ParticleMixin.java`, `hooks/ParticleGate.java`):
  FPS Boost answered `ParticleEngine.createParticle` with `null` for dropped particles. The firework
  explosion (`FireworkParticles.Starter`) casts the result and calls `setColor`/`setTrail` on it, so every
  rocket with a firework star crashed. Now the particle is always returned; a dropped one is only kept out of
  `ParticleEngine.add`, so it is never ticked or drawn.
- **Texture Painter** (`gui/TexturePainterScreen.java`, `gui/TexturePainterExtras.java`):
  right click picks colours again (the grid registered a drag-only hit last, which swallowed right clicks),
  dragging in the colour picker changes the colour instead of painting on the canvas, strokes are drawn as
  lines, block items load their real texture (item model definition -> model parents -> texture), animated
  textures keep their `.mcmeta` and are edited frame by frame, `pack.mcmeta` matches the running Minecraft
  version (was pack_format 48), and the pack stays enabled after a restart (options.updateResourcePacks).
  New: fill tool, recent colours, alpha slider, undo (Ctrl+Z), Ctrl+S, "Remove edit".

## New
- **Pins** (`modules/misc/PinModule.java`): Fortnite-style 2D markers. Pin key (default J) drops a marker on
  the spot under the crosshair; double tap = danger pin; pressing on a pin removes it; off-screen pins stay at
  the screen edge with an arrow. `mixin/ZoomMixin.java` also stores the world FOV for the projection
  (`src26/` holds the 26.x variant, where the FOV lives in `Camera.getFov()`).

## Layout
- `src-base/` intermediary names (Minecraft 1.21.4 - 1.21.11)
- `src26/` hand-written 26.x files that replace the converted ones
- `rename-extra26*.txt` extra 26.x renames on top of the shared rules