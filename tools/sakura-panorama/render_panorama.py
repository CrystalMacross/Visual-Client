"""Renders a Minecraft-style cherry blossom (sakura) forest as a title-screen panorama.

Output: panorama_0.png .. panorama_5.png (front, right, back, left, top, bottom) - the
layout Minecraft expects in assets/minecraft/textures/gui/title/background/.

    python render_panorama.py <out_dir> [face_size]

Needs numpy, numba and pillow. Everything is procedural (no game assets are used).
"""
import math
import os
import sys

import numpy as np
from numba import njit, prange
from PIL import Image

W, H, D = 192, 56, 192  # world size (x, y, z)
AIR, GRASS, DIRT, STONE, LOG, LEAVES, WATER, PETALS = 0, 1, 2, 3, 4, 5, 6, 7
SEED = 7


# ------------------------------------------------------------------ world generation

def value_noise(shape, scale, rng):
    gx, gz = shape[0] // scale + 2, shape[1] // scale + 2
    grid = rng.random((gx, gz))
    xs = np.arange(shape[0]) / scale
    zs = np.arange(shape[1]) / scale
    x0, z0 = xs.astype(int), zs.astype(int)
    tx, tz = xs - x0, zs - z0
    tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
    a = grid[x0][:, z0]
    b = grid[x0 + 1][:, z0]
    c = grid[x0][:, z0 + 1]
    d = grid[x0 + 1][:, z0 + 1]
    tx, tz = tx[:, None], tz[None, :]
    return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz


def build_world():
    rng = np.random.default_rng(SEED)
    world = np.zeros((W, H, D), dtype=np.uint8)
    height = 18 + 7 * value_noise((W, D), 48, rng) + 3 * value_noise((W, D), 16, rng) + 0.8 * value_noise((W, D), 6, rng)
    cx, cz = W // 2, D // 2
    xx, zz = np.meshgrid(np.arange(W), np.arange(D), indexing="ij")
    # gentle valley around the camera and a small pond in front of it
    dist = np.sqrt((xx - cx) ** 2 + (zz - cz) ** 2)
    pond = np.sqrt((xx - cx) ** 2 * 0.6 + (zz - (cz - 13)) ** 2)
    height -= np.clip(7 - pond, 0, 7) * 0.75
    height = np.round(height).astype(int)
    water_level = int(height[cx, cz]) - 1

    for x in range(W):
        for z in range(D):
            h = height[x, z]
            world[x, : h - 3, z] = STONE
            world[x, h - 3 : h, z] = DIRT
            world[x, h, z] = GRASS
            if h < water_level:
                world[x, h, z] = DIRT
                world[x, h + 1 : water_level + 1, z] = WATER

    # cherry trees (keep the area right around the camera and the pond free)
    trees = []
    tries = 0
    while len(trees) < 320 and tries < 40000:
        tries += 1
        x, z = int(rng.integers(6, W - 6)), int(rng.integers(6, D - 6))
        if (x - cx) ** 2 + (z - cz) ** 2 < 8.5 ** 2 or ((x - cx) ** 2 * 0.6 + (z - (cz - 13)) ** 2) < 8 ** 2:
            continue
        if height[x, z] < water_level + 1:
            continue
        if any((x - tx) ** 2 + (z - tz) ** 2 < 7.5 ** 2 for tx, tz in trees):
            continue
        trees.append((x, z))
        grow_cherry_tree(world, x, height[x, z] + 1, z, rng)

    # petal carpet under the canopies
    canopy = (world == LEAVES).any(axis=1)
    shade = box_blur(canopy.astype(np.float32), 3)
    for x in range(W):
        for z in range(D):
            h = height[x, z]
            if world[x, h, z] == GRASS and world[x, h + 1, z] == AIR and shade[x, z] > 0.10 and rng.random() < 0.30 + 0.7 * shade[x, z]:
                world[x, h + 1, z] = PETALS
    cam_y = height[cx, cz] + 4.6  # a little above the ground, like vanilla panoramas
    return world, (cx + 0.5, cam_y, cz + 0.5)


def box_blur(a, r):
    out = a.copy()
    for axis in (0, 1):
        acc = np.zeros_like(out)
        for k in range(-r, r + 1):
            acc += np.roll(out, k, axis=axis)
        out = acc / (2 * r + 1)
    return out


def grow_cherry_tree(world, x, y, z, rng):
    trunk = int(rng.integers(5, 8))
    lean = [(1, 0), (-1, 0), (0, 1), (0, -1)][int(rng.integers(0, 4))]
    px, pz = x, z
    for i in range(trunk):
        if i == trunk - 2:
            px, pz = px + lean[0], pz + lean[1]
        set_block(world, px, y + i, pz, LOG)
    top = y + trunk
    canopies = [(px, top, pz, rng.uniform(3.6, 5.2))]
    # a side branch with a second canopy
    if rng.random() < 0.8:
        bx, bz = x - lean[0] * 2, z - lean[1] * 2
        by = y + trunk - 2
        for i in range(3):
            set_block(world, x - lean[0] * i, y + trunk - 3 + i // 2, z - lean[1] * i, LOG)
        canopies.append((bx, by + 1, bz, rng.uniform(2.8, 4.0)))
    for (ox, oy, oz, r) in canopies:
        ri = int(math.ceil(r))
        for dx in range(-ri - 1, ri + 2):
            for dy in range(-2, 3):
                for dz in range(-ri - 1, ri + 2):
                    # flattened, slightly drooping dome like vanilla cherry trees
                    d = (dx * dx + dz * dz) / (r * r) + ((dy - 0.3) * (dy - 0.3)) / 3.2
                    if d < 1.0 and rng.random() > 0.06 + 0.3 * max(0, d - 0.75):
                        set_block(world, ox + dx, oy + dy, oz + dz, LEAVES, replace_log=False)
        # hanging blossoms
        for _ in range(int(r * 5)):
            ang = rng.uniform(0, 2 * math.pi)
            hx, hz = int(round(ox + math.cos(ang) * (r - 0.4))), int(round(oz + math.sin(ang) * (r - 0.4)))
            for dy in range(1, int(rng.integers(2, 4))):
                set_block(world, hx, oy - 1 - dy, hz, LEAVES, replace_log=False)


def set_block(world, x, y, z, block, replace_log=True):
    if 0 <= x < W and 0 <= y < H and 0 <= z < D:
        cur = world[x, y, z]
        if cur == AIR or (replace_log and cur in (LEAVES,)):
            world[x, y, z] = block


# ------------------------------------------------------------------ renderer

@njit(cache=True, inline="always")
def hash3(a, b, c):
    h = (a * 374761393 + b * 668265263 + c * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


@njit(cache=True)
def sky(dx, dy, dz, sx, sy, sz):
    t = max(0.0, dy)
    # pastel morning sky: peach horizon -> soft blue
    r = 0.98 * (1 - t) ** 3 + 0.48 * (1 - (1 - t) ** 3)
    g = 0.80 * (1 - t) ** 3 + 0.66 * (1 - (1 - t) ** 3)
    b = 0.80 * (1 - t) ** 3 + 0.95 * (1 - (1 - t) ** 3)
    if dy < 0:
        r, g, b = 0.93, 0.78, 0.80
    sd = dx * sx + dy * sy + dz * sz
    if sd > 0:
        glow = sd ** 64 * 0.9 + sd ** 8 * 0.18
        r += glow
        g += glow * 0.85
        b += glow * 0.6
        if sd > 0.9992:
            r, g, b = 1.6, 1.5, 1.3
    return r, g, b


@njit(cache=True)
def texel(block, face, ix, iy, iz, tu, tv, near_pink):
    n = hash3(ix * 16 + tu, iy * 16 + tv, iz * 16 + face * 7)
    n2 = hash3(ix * 31 + tu * 3, iz * 17 + tv, iy + face)
    if block == GRASS:
        if face == 2:  # top
            r, g, b = 0.36 + 0.10 * n, 0.62 + 0.12 * n, 0.27 + 0.06 * n
            if near_pink and n2 > 0.78:
                r, g, b = 0.98, 0.70 + 0.1 * n, 0.82
            return r, g, b, 1.0
        if face == 3:  # bottom
            return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
        if tv >= 13 or (tv == 12 and n2 > 0.5):
            return 0.36 + 0.10 * n, 0.62 + 0.12 * n, 0.27 + 0.06 * n, 1.0
        return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
    if block == DIRT:
        return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
    if block == STONE:
        v = 0.48 + 0.12 * n
        return v, v, v * 1.02, 1.0
    if block == LOG:
        if face == 2 or face == 3:
            ring = (abs(tu - 7.5) + abs(tv - 7.5)) % 4 < 1.6
            return (0.55, 0.36, 0.40, 1.0) if ring else (0.70, 0.48, 0.50, 1.0)
        stripe = (tu * 5 + int(n2 * 3)) % 7 < 2
        v = 0.24 + 0.06 * n
        return (v * 1.25, v * 0.85, v * 1.0, 1.0) if stripe else (v * 1.55, v * 1.05, v * 1.15, 1.0)
    if block == LEAVES:
        if n2 < 0.13:
            return 0.0, 0.0, 0.0, 0.0  # cut-out hole
        if n < 0.30:
            return 0.92, 0.47, 0.68, 1.0
        if n < 0.72:
            return 1.0, 0.64, 0.80, 1.0
        return 1.0, 0.80, 0.90, 1.0
    if block == PETALS:
        if n2 < 0.45:
            return 0.0, 0.0, 0.0, 0.0
        return 0.99, 0.72 + 0.12 * n, 0.86, 1.0
    if block == WATER:
        return 0.25 + 0.05 * n, 0.45 + 0.05 * n, 0.85, 0.62
    return 1.0, 0.0, 1.0, 1.0


@njit(cache=True)
def block_at(world, x, y, z):
    if x < 0 or y < 0 or z < 0 or x >= world.shape[0] or y >= world.shape[1] or z >= world.shape[2]:
        return 0
    return world[x, y, z]


@njit(cache=True)
def solid_for_shadow(b):
    return b != AIR and b != WATER and b != PETALS


@njit(cache=True)
def in_shadow(world, ox, oy, oz, sx, sy, sz):
    # cheap shadow ray: fixed steps toward the sun, leaves cast partial shade
    t = 0.05
    shade = 1.0
    while t < 48.0:
        x, y, z = ox + sx * t, oy + sy * t, oz + sz * t
        if y >= world.shape[1]:
            break
        b = block_at(world, int(math.floor(x)), int(math.floor(y)), int(math.floor(z)))
        if b == LEAVES:
            shade *= 0.80
        elif solid_for_shadow(b):
            return 0.0
        if shade < 0.3:
            return shade
        t += 0.35
    return shade


@njit(cache=True)
def trace(world, ox, oy, oz, dx, dy, dz, sx, sy, sz, pink_map):
    r_acc, g_acc, b_acc, trans = 0.0, 0.0, 0.0, 1.0
    x, y, z = int(math.floor(ox)), int(math.floor(oy)), int(math.floor(oz))
    step_x = 1 if dx > 0 else -1
    step_y = 1 if dy > 0 else -1
    step_z = 1 if dz > 0 else -1
    inf = 1e30
    tdx = abs(1.0 / dx) if dx != 0 else inf
    tdy = abs(1.0 / dy) if dy != 0 else inf
    tdz = abs(1.0 / dz) if dz != 0 else inf
    tmx = ((x + 1 - ox) if dx > 0 else (ox - x)) * tdx if dx != 0 else inf
    tmy = ((y + 1 - oy) if dy > 0 else (oy - y)) * tdy if dy != 0 else inf
    tmz = ((z + 1 - oz) if dz > 0 else (oz - z)) * tdz if dz != 0 else inf
    t = 0.0
    face = -1
    fog_r, fog_g, fog_b = 0.95, 0.83, 0.86
    max_t = 150.0
    in_water = False
    while t < max_t and trans > 0.02:
        if tmx < tmy and tmx < tmz:
            x += step_x; t = tmx; tmx += tdx; face = 0 if step_x < 0 else 1  # hit +x / -x side
        elif tmy < tmz:
            y += step_y; t = tmy; tmy += tdy; face = 2 if step_y < 0 else 3  # top / bottom
        else:
            z += step_z; t = tmz; tmz += tdz; face = 4 if step_z < 0 else 5
        if y < 0 or (y >= world.shape[1] and dy > 0):
            break
        b = block_at(world, x, y, z)
        if b == AIR:
            in_water = False
            continue
        if b == WATER and in_water:
            continue
        hx, hy, hz = ox + dx * t, oy + dy * t, oz + dz * t
        fx, fy, fz = hx - x, hy - y, hz - z
        if face <= 1:
            u, v = fz, fy
        elif face <= 3:
            u, v = fx, fz
        else:
            u, v = fx, fy
        if b == PETALS:
            # thin carpet: only the lowest 1/16 of the block
            if face != 2 and fy > 0.0625:
                continue
            if face != 2:
                continue
        tu = min(15, max(0, int(u * 16)))
        tv = min(15, max(0, int(v * 16)))
        cr, cg, cb, ca = texel(b, face, x, y, z, tu, tv, pink_map[min(max(x, 0), pink_map.shape[0] - 1), min(max(z, 0), pink_map.shape[1] - 1)] > 0.1)
        if ca == 0.0:
            continue
        # lighting
        if face == 2:
            light = 1.0
        elif face == 3:
            light = 0.55
        elif face <= 1:
            light = 0.78
        else:
            light = 0.86
        nx = 1.0 if face == 1 else (-1.0 if face == 0 else 0.0)
        ny = 1.0 if face == 2 else (-1.0 if face == 3 else 0.0)
        nz = 1.0 if face == 5 else (-1.0 if face == 4 else 0.0)
        # step the hit point slightly outside the block
        sh = in_shadow(world, hx + nx * 0.02, hy + ny * 0.02, hz + nz * 0.02, sx, sy, sz)
        ndl = max(0.0, nx * sx + ny * sy + nz * sz)
        lit = 0.55 + 0.55 * sh * ndl
        if b == LEAVES:
            lit = 0.80 + 0.35 * sh * ndl  # sunlight shines through thin cherry leaves
        cr *= light * lit
        cg *= light * lit
        cb *= light * lit * 0.98
        if b == WATER:
            # reflect the sky a bit
            rr, rg, rb = sky(dx, -dy, dz, sx, sy, sz)
            cr = cr * 0.6 + rr * 0.4
            cg = cg * 0.6 + rg * 0.4
            cb = cb * 0.6 + rb * 0.4
            in_water = True
        fog = 1.0 - math.exp(-((t / 95.0) ** 2))
        cr = cr * (1 - fog) + fog_r * fog
        cg = cg * (1 - fog) + fog_g * fog
        cb = cb * (1 - fog) + fog_b * fog
        r_acc += trans * ca * cr
        g_acc += trans * ca * cg
        b_acc += trans * ca * cb
        trans *= (1 - ca)
    if trans > 0.0:
        r, g, b = sky(dx, dy, dz, sx, sy, sz)
        if t >= max_t:
            r, g, b = fog_r, fog_g, fog_b
        r_acc += trans * r
        g_acc += trans * g
        b_acc += trans * b
    return r_acc, g_acc, b_acc


@njit(parallel=True, cache=True)
def render_face(world, cam, fwd, right, up, size, sun, pink_map, ss):
    img = np.zeros((size, size, 3), dtype=np.float32)
    for py in prange(size):
        for px in range(size):
            r = g = b = 0.0
            for sy_ in range(ss):
                for sx_ in range(ss):
                    a = ((px + (sx_ + 0.5) / ss) / size) * 2 - 1
                    c = 1 - ((py + (sy_ + 0.5) / ss) / size) * 2
                    dx = fwd[0] + right[0] * a + up[0] * c
                    dy = fwd[1] + right[1] * a + up[1] * c
                    dz = fwd[2] + right[2] * a + up[2] * c
                    l = math.sqrt(dx * dx + dy * dy + dz * dz)
                    cr, cg, cb = trace(world, cam[0], cam[1], cam[2], dx / l, dy / l, dz / l, sun[0], sun[1], sun[2], pink_map)
                    r += cr; g += cg; b += cb
            n = ss * ss
            img[py, px, 0] = r / n
            img[py, px, 1] = g / n
            img[py, px, 2] = b / n
    return img


# Minecraft panorama layout: 0 front (-Z), 1 right (+X), 2 back (+Z), 3 left (-X), 4 top, 5 bottom.
# The top of panorama_0 meets the bottom of panorama_4; the bottom of panorama_0 meets the top of panorama_5.
FACES = [
    ((0, 0, -1), (1, 0, 0), (0, 1, 0)),
    ((1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 0, 1), (-1, 0, 0), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, -1, 0), (1, 0, 0), (0, 0, -1)),
]


def tonemap(img):
    img = np.clip(img, 0, None)
    img = img / (1 + img * 0.12)  # soft highlight roll-off
    img = np.clip(img ** (1 / 1.05), 0, 1)
    return (img * 255 + 0.5).astype(np.uint8)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "panorama"
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    os.makedirs(out, exist_ok=True)
    world, cam = build_world()
    pink = (world == PETALS).any(axis=1).astype(np.float32)
    sun = np.array([0.45, 0.42, -0.79])
    sun /= np.linalg.norm(sun)
    for i, (f, r, u) in enumerate(FACES):
        img = render_face(world, np.array(cam), np.array(f, float), np.array(r, float), np.array(u, float), size, sun, pink, 2)
        Image.fromarray(tonemap(img)).save(os.path.join(out, f"panorama_{i}.png"), optimize=True)
        print("wrote", f"panorama_{i}.png")


if __name__ == "__main__":
    main()
