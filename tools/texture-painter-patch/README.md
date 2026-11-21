# Texture Painter patch (1.12.0 -> 1.12.1)

The 1.12.x source of the Texture Painter is not in this repository, so these features
are added to the released bundle jar with a bytecode patch (JDK 21 built-in ASM):

* **Export ZIP** button in the painter footer: packs `resourcepacks/Visual Client Texture Edits`
  into a ready-to-use resource pack `.zip`. A native save dialog opens; if none is available,
  the zip goes to `<game dir>/visualclient/texture-exports/Visual Client Texture Edits.zip`.
* **Placed End Crystal**: new list entry `minecraft:placed_end_crystal` (search "crystal")
  that edits `textures/entity/end_crystal/end_crystal.png`, the texture of placed crystals.

```
tools/texture-painter-patch/patch.sh visual-client-1.12.0.jar visual-client-1.12.1.jar 1.12.1
```

`src/.../TexturePainterExtras.java` holds the new logic (no Minecraft classes, works in all builds);
`Patcher.java` wires it into `TexturePainterScreen` of every Minecraft build inside the bundle.
