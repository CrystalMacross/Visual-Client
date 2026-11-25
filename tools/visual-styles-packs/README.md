# Adding texture packs to Visual Styles

`addpacks.py` puts packs into an unpacked Visual Styles jar:

- **Collection looks**: every texture of a collection (Crystal, Anchor, Glowstone, Obsidian, Armor, Sword,
  Pickaxe, Totem, Ender Pearl, Wind Charge, Mace, Fire, Stone, Deepslate, Snow, Inventory / containers,
  Enchant glint, Crosshair; the paths are the ones `net.visualstyles.style.Coll` uses) that differs pixel by
  pixel from vanilla 1.21.11 and from every look already in goes to `assets/visualstyles/packs/<id>/<path>`
  (with its `.mcmeta`), listed in `packs/index.txt` as `id|Name|path`.
- **Whole packs**: all files under `assets/` go to `packs/<id>/full/`, listed in `packs/full.txt`; the pack icon
  is `full/pack.png` (the pack's own, or its totem / first item texture scaled up).

```
unzip visual-styles-1.6.3+1.21.11.jar -d x
python3 addpacks.py x <minecraft 1.21.11 client.jar> "id|Pack name|pack.zip" ...
# bump fabric.mod.json, zip x -> visual-styles-<new>+1.21.11.jar
```

Visual Styles 1.6.4 added Bauloo Private (15 looks, whole pack 2024 files), Cowarted x Vaunta (samynull4
remake) (13 looks, 1469 files) and Daquavious (35 looks, 2304 files).
