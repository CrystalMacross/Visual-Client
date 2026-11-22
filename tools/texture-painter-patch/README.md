# Visual Client patches (1.12.0 -> ... -> 1.20.3 -> 1.20.4)

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

* **Configs tab** (1.17.0): new `Category.CONFIGS` and a 7th tab in `VisualGuiScreen`; the Configs module
  (save/load configs, copy/import config codes) lives there.
* **No Explosion Particles** (1.14.0, category Extra): hides explosion particles; routed through
  the particle filter in `FpsBoostModule.dropParticle`, independent of FPS Boost.
* **Totem Tweaks → No Pop Animation** (1.14.0, complete removal since 1.14.1): a generated mixin
  (`NoPopAnimationMixin`) cancels `GameRenderer.displayItemActivation` (intermediary
  `class_757.method_3189` on 1.21.x), so the pop animation never starts.

* **Sakura garden at night title screen** (1.15.0, night + shader look since 1.16.0, path-traced
  realistic lighting since 1.18.0): `resources/assets/minecraft/textures/gui/title/background/panorama_*.png`
  (rendered with `tools/sakura-panorama`), stored once in the bundle jar.

* **Item Size** (1.19.0, category Extra): size of dropped items on the ground, one size for all
  plus "Per item" overrides (`diamond=2, totem_of_undying=3`). `ItemSizeMixin` is generated per build
  from the mod's `XpOrbRendererMixin` (renderer state capture + scale at the start of submit/render),
  retargeted to `ItemEntityRenderer` (intermediary `class_916.method_62354/method_3936` on 1.21.x).
  Since 1.20.0 "Open item editor" shows a square window in the new menu: every item with its icon
  (`NewGuiScreen.vc$item`, the mod's own GuiGraphics item call), search, a "Custom" filter and a
  size slider with presets per item. Sizes are stored in "Per item", so configs keep them.
  Since 1.20.2 the hooks no longer use method names: `src/.../mixin/VisualMixinPlugin.java` (registered
  as the mixin config plugin) finds ItemEntityRenderer's render/submit (PoseStack as 2nd parameter)
  and extractRenderState (entity, state, float) when the class loads and inserts the calls;
  `ItemSizeMixin` is only an empty @Mixin on that class. Compiled against `lib/asm-*.jar` and the
  stand-in interfaces in `stubs/` (both compile-only). The editor shows whether the hook runs.

* **Music HUD removed** (1.20.3): the module asked lrclib.net for song lyrics. `Modules.<clinit>` no longer
  creates `MusicHudModule` (ConfigsModule.withConfigs skips the null), `VisualClient.onTick` no longer calls
  `CoverArt.tick()`, and `MusicHudModule` plus the whole `net/visualclient/media/` package are left out of every build.

* **Armor HUD in hotbar slots** (1.20.4): the armor sits in hotbar slots drawn with the game's own
  `hud/hotbar` sprite (resource packs restyle it too), each piece with the vanilla durability bar
  (`renderItemDecorations`), and an empty slot shows the inventory's `container/slot/<piece>` sprite.
  The old look (Vertical/Horizontal layout, percent/value text, thin bars, background box) is gone;
  only "Show held items" is left. This is not a Patcher step: `ArmorHudModule` was recompiled for each
  of the 8 builds against that Minecraft version plus the 1.20.3 build of the bundle, and only that
  class was swapped in (everything else is byte-identical to 1.20.3). Sources in `armor-hud/`:
  intermediary names for 1.21.6+ (`blitSprite` = `method_70846` / `method_52706` with
  `RenderPipelines.GUI_TEXTURED`) and for 1.21.4/1.21.5 (`method_52708` / `method_52706` with
  `RenderType::guiTextured`); the 26.x builds come from the 1.21.6+ file via ToMojang + the rename26
  rules (plus `method_70846` / `method_52706` -> `blitSprite`). LinkCheck OK for 1.21.4 - 1.21.11 and
  26.1 - 26.3; Fabric loader test OK on 1.21.4, 1.21.11 and 26.1. Readable version:
  `src/main/java/net/visualclient/modules/combat/ArmorHudModule.java`.

* **New menu UI** (1.18.0): `GUI Style → UI: Old / New` (default New). `GuiOpener.open()` returns
  the generated `NewGuiScreen` when New is selected; the sidebar of the new menu has an "Old UI"
  button. Switching the setting while a menu is open reopens it in the chosen style.
  - `src/.../gui/NewGui.java`: the whole menu (sidebar with categories, global search, module
    cards with switches, settings page with switches, sliders, mode pickers, color picker, text
    fields and buttons). It only uses the mod's own helpers, reached through
    `src/.../gui/Gfx.java` (method handles with the Minecraft types erased to `Object`), so the
    same class works in every Minecraft build.
  - `NewGuiScreen` is generated per build by `Patcher.java` from `VisualGuiScreen`'s own
    bytecode (method names, background, scissor calls), so intermediary (1.21.x) and official
    (26.x) names both work.

```
tools/texture-painter-patch/patch.sh visual-client-1.20.2.jar visual-client-1.20.3.jar 1.20.3
```
Steps that are already in the input jar are skipped.

`src/.../TexturePainterExtras.java` and `src/.../ConfigsModule.java` hold the new logic (no Minecraft classes, works in all builds);
`Patcher.java` wires it into `TexturePainterScreen` and `Modules` of every Minecraft build inside the bundle.
