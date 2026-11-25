# Adds texture packs to Visual Styles: collection looks (only textures that differ from vanilla and from every
# look already in) + the whole pack under "Whole packs".  python addpacks.py <jar-dir> <vanilla-client.jar> id|Name|zip ...
import sys, os, io, zipfile, hashlib
from PIL import Image
root, vanilla_jar = sys.argv[1], sys.argv[2]
P = os.path.join(root, 'assets/visualstyles/packs')
GUI = 'inventory generic_54 shulker_box crafting_table furnace blast_furnace smoker dispenser hopper anvil grindstone smithing enchanting_table brewing_stand beacon stonecutter cartography_table loom villager horse crafter nautilus'.split()
PATHS = ['textures/entity/end_crystal/end_crystal.png'] + ['textures/block/respawn_anchor_%s.png' % s for s in ['bottom', 'side0', 'side1', 'side2', 'side3', 'side4', 'top', 'top_off']] + [
    'textures/block/glowstone.png', 'textures/block/obsidian.png',
    'textures/entity/equipment/humanoid/diamond.png', 'textures/entity/equipment/humanoid_leggings/diamond.png',
    'textures/entity/equipment/humanoid/netherite.png', 'textures/entity/equipment/humanoid_leggings/netherite.png'] + [
    'textures/item/%s_%s.png' % (m, p) for m in ['diamond', 'netherite'] for p in ['helmet', 'chestplate', 'leggings', 'boots', 'sword', 'pickaxe']] + [
    'textures/item/totem_of_undying.png', 'textures/item/ender_pearl.png', 'textures/item/wind_charge.png', 'textures/entity/projectiles/wind_charge.png',
    'textures/item/mace.png', 'textures/block/fire_0.png', 'textures/block/fire_1.png', 'textures/block/stone.png', 'textures/block/deepslate.png',
    'textures/block/deepslate_top.png', 'textures/block/snow.png', 'textures/misc/enchanted_glint_item.png', 'textures/misc/enchanted_glint_armor.png',
    'textures/gui/sprites/hud/crosshair.png'] + ['textures/gui/container/%s.png' % g for g in GUI]
def pix(data):
    try:
        im = Image.open(io.BytesIO(data)).convert('RGBA')
        return im.size, hashlib.sha1(im.tobytes()).hexdigest()
    except Exception:
        return None
van = zipfile.ZipFile(vanilla_jar)
def vanilla(path):
    try: return pix(van.read('assets/minecraft/' + path))
    except KeyError: return None
existing = {}
for line in open(os.path.join(P, 'index.txt'), encoding='utf8'):
    pid, name, path = line.rstrip('\n').split('|')
    f = os.path.join(P, pid, path)
    if os.path.exists(f): existing.setdefault(path, set()).add(pix(open(f, 'rb').read()))
index_add, full_add, report = [], [], []
for spec in sys.argv[3:]:
    pid, name, zpath = spec.split('|')
    z = zipfile.ZipFile(zpath)
    names = {n: n for n in z.namelist() if not n.endswith('/')}
    looks = 0
    for path in PATHS:
        n = 'assets/minecraft/' + path
        if n not in names: continue
        data = z.read(n)
        p = pix(data)
        if p is None or p == vanilla(path) or p in existing.get(path, set()): continue
        existing.setdefault(path, set()).add(p)
        dst = os.path.join(P, pid, path)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'wb').write(data)
        if n + '.mcmeta' in names: open(dst + '.mcmeta', 'wb').write(z.read(n + '.mcmeta'))
        index_add.append(f'{pid}|{name}|{path}')
        looks += 1
    # whole pack: every file under assets/ (textures, models, sounds, fonts, language ...)
    files = 0
    for n in sorted(names):
        if not n.startswith('assets/') or '..' in n: continue
        dst = os.path.join(P, pid, 'full', n)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'wb').write(z.read(n))
        full_add.append(f'{pid}|{name}|{n}')
        files += 1
    icon = os.path.join(P, pid, 'full', 'pack.png')
    if 'pack.png' in names:
        open(icon, 'wb').write(z.read('pack.png'))
    else:  # no pack icon: the pack's own totem (or its first item texture), scaled up
        pref = ['assets/minecraft/textures/item/totem_of_undying.png', 'assets/minecraft/textures/item/diamond_sword.png', 'assets/minecraft/textures/item/netherite_sword.png', 'assets/minecraft/textures/item/ender_pearl.png']
        src = next((k for k in pref if k in names), None) or next(k for k in sorted(names) if k.startswith('assets/minecraft/textures/item/') and k.endswith('.png'))
        im = Image.open(io.BytesIO(z.read(src))).convert('RGBA').resize((128, 128), Image.NEAREST)
        im.save(icon)
    report.append(f'{name}: {looks} collection looks, whole pack {files} files')
open(os.path.join(P, 'index.txt'), 'a', encoding='utf8').write(''.join(l + '\n' for l in index_add))
open(os.path.join(P, 'full.txt'), 'a', encoding='utf8').write(''.join(l + '\n' for l in full_add))
print('\n'.join(report))
for l in index_add: print('  ' + l)
