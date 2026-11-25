# Visual Client 1.20.9 - 1.20.12 patch

`visual-client-1.20.9.jar` is the 1.20.8 bundle with one new module; `visual-client-1.20.10.jar` is 1.20.9 with
the Falling blocks of Aurora Sky (`build.sh visual-client-1.20.9.jar visual-client-1.20.10.jar <work>`); 1.20.11 gives
the cubes and the "Visual" colours the look of the website's sky (visualmod.vercel.app, sky.js).

## New
- **Aurora Sky** (category Extra, off by default): glowing aurora curtains in the night sky. Colors: *Visual*
  (the website's cyan and violet), *Northern Lights* (green and violet) or *Custom* (Lower/Upper color with HEX).
  Settings: Brightness, Curtain height, Speed, Only at night, Hide behind terrain.
- **Falling blocks** (1.20.10, own setting in Aurora Sky, on by default; *Block amount* 4 - 40): glowing 3D cubes
  in the aurora colours tumble down through the sky 24 - 64 blocks away. Each cube is projected corner by corner;
  the faces turned to the camera are filled row by row with a light factor per side (top brightest). Cubes behind
  the terrain horizon are not drawn, they fade in at the top and out near the horizon, then start again.
- **1.20.11, like the website's sky:** the cubes are the voxel cubes of sky.js: see-through faces shaded by a light
  from above, glowing 1px edges (runs of fills), a 4 x 4 block-pixel grid on the bigger cubes, a soft glow around
  near cubes and the site's cube colours (teal, sky, lime, indigo, mint, amber); near cubes are more opaque. The
  *Visual* aurora uses the site's three ribbons (teal/lime, sky/teal, indigo/sky) with its curtain gradient (edge
  colour at the bottom, curtain colour fading out at the top), waves, rays and envelope.

- **1.20.12:** blocks always hide the aurora (the *Hide behind terrain* switch is gone, it is always on). Every frame a
  ray per column direction (720) runs cell by cell through a cached heightmap square of 113 x 113 blocks around the
  camera, starting right at the camera, so walls next to you, trees and buildings cover the curtains; beyond that the
  highest point up to 384 blocks per direction is kept as a world position (48 directions refreshed per frame).
  A column starts above the highest terrain of its own and both neighbouring directions; cubes are skipped while any
  part of them could be behind terrain. Smooth at 60+ fps: drawn in real screen pixels (pose scaled by 1 / GUI scale)
  instead of GUI pixels, animated with `System.nanoTime` (the millisecond clock steps in 16 ms on Windows) and placed
  with the interpolated camera position.
- Windows build: `build-windows.ps1` (PowerShell port of `build.sh`, `MergeMaps.java` replaces `merge.py`); the pose
  calls are filled in per build (`pushPose`/`scale(x, y, 1)`/`popPose` on 1.21.4 - 1.21.5, `pushMatrix`/`scale(x, y)`/
  `popMatrix` on 1.21.6+), and the camera position (`getPosition()` / `position()`).

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
  (310 references, 0 missing in all eight builds) and swaps the classes into the nested jars (`Swap.java`).
