# Visual Client 1.20.9 patch

`visual-client-1.20.9.jar` is the 1.20.8 bundle with one new module.

## New
- **Aurora Sky** (category Extra, off by default): glowing aurora curtains in the night sky. Colors: *Visual*
  (the website's cyan and violet), *Northern Lights* (green and violet) or *Custom* (Lower/Upper color with HEX).
  Settings: Brightness, Curtain height, Speed, Only at night, Hide behind terrain.

## How it works
- `src/.../AuroraSkyModule.java` is an `Overlay` (drawn from the HUD layer like the Pins module). The curtains
  hang at fixed sky directions (720 columns of 0.5°, two layers) and are projected every frame with the camera's
  yaw, pitch and the world FOV that ZoomMixin stores in `PinModule.worldFov`, so they stay in place when you
  look around. Each column is three vertical gradients (bright lower part, fading upper part, glowing rim).
- Terrain: twice a second (or after moving 2 blocks) the MOTION_BLOCKING heightmap of the loaded chunks is
  sampled up to 160 blocks in 240 directions; the curtains start above that horizon, so hills, trees and
  buildings hide them. Under a roof, underwater, in rain, by day (*Only at night*) and outside the Overworld
  the aurora fades out. The hotbar area stays free.
- Build: `prepare-mc.sh <work>` downloads the eight Minecraft clients, Mojang mappings and Fabric intermediary,
  merges them (`merge.py`) and remaps 1.21.x to intermediary and to Mojang names (tiny-remapper).
  `build.sh visual-client-1.20.8.jar visual-client-1.20.9.jar <work>` fills in the few names that differ per
  build (GuiGraphics / GuiGraphicsExtractor, getMainCamera / mainCamera, getYRot / yRot, getDayTime /
  getOverworldClockTime), compiles 1.21.x against Mojang names and remaps the class to intermediary, compiles 26.x
  directly (JDK 25 reads the Java 25 classes), recompiles ConfigsModule (registers the module after
  No Equipment Animation), checks every reference with `LinkCheck` against the real Minecraft jar and the mod
  (204 references, 0 missing in all eight builds) and swaps the classes into the nested jars (`Swap.java`).
