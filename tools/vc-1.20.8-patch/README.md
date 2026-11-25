# Visual Client 1.20.8 patch

`visual-client-1.20.8.jar` is the 1.20.7 bundle with one new module. Build: `build.sh visual-client-1.20.7.jar visual-client-1.20.8.jar`.

## New
- **No Equipment Animation** (category Extra, off by default): items in the first-person hands appear at once
  instead of dropping out of view and sliding back up (hotbar switch, picking up, offhand swap), and the hand no
  longer dips after an attack.

## How it works
- `NoEquipAnimationMixin` is an empty `@Mixin` on the class that holds the shown hand items: the class the mod's
  own ShieldMixin targets (`class_759` / `ItemInHandRenderer`), or `FirstPersonHandsAndItems` in 26.3, where the
  items and heights moved out of `FirstPersonHandsAndItemsRenderer`.
- `VisualMixinPlugin.patchHandRenderer` (sources in `tools/texture-painter-patch/src`) finds everything by shape,
  so no per-build names are needed: the hand heights are the class's only instance `float` fields; tick() is the
  `void` method with at most one argument that copies the held stacks (locals stored once, before the first jump)
  into the item fields. Before each return of every method that writes a height (tick, itemUsed) the heights go
  through `NoEquipAnimationModule.height` (1.0 while on) and, in tick, the shown items through
  `NoEquipAnimationModule.stack` (the held item while on). The inserted code has no branches.
- Checked against the real Minecraft classes of 1.21.4, 1.21.11, 26.1, 26.2 and 26.3 (ASM analyzer, and the JVM
  verifier for 1.21.11 after Mixin-style frame computation).
- `ConfigsModule.withConfigs` adds the module after Item Size. VisualMixinPlugin and ConfigsModule were unchanged
  since 1.20.3, so `Swap.java` replaces just those two classes, adds the module and the mixin, registers the
  mixin in `visualclient.mixins.json` and sets the versions; every other entry is copied unchanged.
