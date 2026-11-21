# Visual Client patches (1.12.0 -> ... -> 1.14.1 -> 1.15.0)

The 1.12.x source of the Texture Painter is not in this repository, so these features
are added to the released bundle jar with a bytecode patch (JDK 21 built-in ASM):

* **Export ZIP** button in the painter footer: packs `resourcepacks/Visual Client Texture Edits`
  into a ready-to-use resource pack `.zip`. A native save dialog opens; if none is available,
  the zip goes to `<game dir>/visualclient/texture-exports/Visual Client Texture Edits.zip`.
* **Placed End Crystal**: new list entry `minecraft:placed_end_crystal` (search "crystal")
  that edits `textures/entity/end_crystal/end_crystal.png`, the texture of placed crystals.

* **Configs module** (1.13.0, menu category Misc): save / load / delete named configs
  (`config/visualclient/configs/<name>.json`) and share them: *Copy config code* puts a
  `VC1:...` code on the clipboard, *Import config code* applies a code from the text field or
  the clipboard. Codes contain all module settings and the HUD layout.

* **No Explosion Particles** (1.14.0, category Extra): hides explosion particles; routed through
  the particle filter in `FpsBoostModule.dropParticle`, independent of FPS Boost.
* **Totem Tweaks → No Pop Animation** (1.14.0, complete removal since 1.14.1): a generated mixin
  (`NoPopAnimationMixin`) cancels `GameRenderer.displayItemActivation` (intermediary
  `class_757.method_3189` on 1.21.x), so the pop animation never starts.

* **Sakura forest title screen** (1.15.0): `resources/assets/minecraft/textures/gui/title/background/panorama_*.png`
  (rendered with `tools/sakura-panorama`), stored once in the bundle jar.

```
tools/texture-painter-patch/patch.sh visual-client-1.14.1.jar visual-client-1.15.0.jar 1.15.0
```
Steps that are already in the input jar are skipped.

`src/.../TexturePainterExtras.java` and `src/.../ConfigsModule.java` hold the new logic (no Minecraft classes, works in all builds);
`Patcher.java` wires it into `TexturePainterScreen` and `Modules` of every Minecraft build inside the bundle.
