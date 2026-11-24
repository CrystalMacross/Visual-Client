# Visual Styles (Minecraft 1.21.11, Fabric)

Pick looks for crystals, anchors, armor, swords, pickaxes, axes, totems, mace + wind charge + pearl, fire,
blocks, the inventory, the enchant glint and the crosshair from one menu (key **K**).

Build: JDK 21 and Gradle 9.2+, then `gradle build` in this folder -> `build/libs/visual-styles-<version>+1.21.11.jar`.
The source uses the intermediary names (`class_310`, `method_1507`, ...), so Loom runs with intermediary mappings.

## 1.7.0
* **Import ZIP**: button in the menu footer (native file dialog, several zips at once), drag & drop of `.zip` files
  onto the menu, or drop them into `config/visualstyles/zips/`. Packs are unpacked to `config/visualstyles/imported/<id>/`
  and show up in every category they have textures for (preview built from the pack's own textures, tag "YOUR ZIP")
  and under *Whole packs* (with the pack's `pack.png`). Older paths (1.12 `items/`/`blocks/`, `models/armor/*_layer_N`,
  `enchanted_item_glint`, ...) are copied to their 1.21.11 paths. Hover an imported pack and press **Delete** to remove it.
* New category **Mace + Wind + Pearl** (replaces the three separate ones; old picks, favourites, presets and
  custom folders are carried over).
* New category **Axe** (netherite + diamond axe).
