"""Renders a Minecraft-style cherry blossom (sakura) garden at night with a realistic,
path-traced look, as a title-screen panorama.

Output: panorama_0.png .. panorama_5.png (front, right, back, left, top, bottom) - the
layout Minecraft expects in assets/minecraft/textures/gui/title/background/.

    python render_panorama.py <out_dir> [face_size] [samples_per_pixel]

Needs numpy, numba and pillow. Everything is procedural (no game assets are used).

Rendering:
  * path tracing with one diffuse bounce (global illumination / natural ambient occlusion)
  * soft shadows: the moon and every stone lantern are area lights; light falls through the
    holes of the blossom canopies (dappled moonlight)
  * volumetric ground mist with moonlight shafts and glowing halos around the lanterns
  * rippled, reflective pond (Fresnel) that mirrors moon, lanterns and trees
  * 32x32 procedural textures (cherry bark with lenticels, granite, blossom clusters, grass,
    shoji paper lanterns), grass tufts and flowers as cross-shaped plants
  * night sky with stars, Milky Way, thin clouds and a cratered moon
  * edge-aware denoiser (albedo demodulated), bloom with padding (seamless cube faces),
    filmic tone curve
Nothing floats: every log/leaf/lantern block is connected to the ground (flood fill).
"""
import math
import os
import sys
from collections import deque

import numpy as np
from numba import njit, prange
from PIL import Image

W, H, D = 192, 64, 192  # world size (x, y, z)
(AIR, GRASS, DIRT, STONE, LOG, LEAVES, WATER, PETALS, LANTERN, STONE_LANTERN, PLANT, FLOWER) = range(12)
SEED = 7

# lighting (linear units; the tone curve maps them to the screen)
MOON = np.array([-0.32, 0.40, -0.86])
MOON /= np.linalg.norm(MOON)
MOON_COL = (0.74, 0.77, 1.0)
MOON_E = 0.42          # moonlight on a surface facing the moon
LAN_COL = (1.0, 0.55, 0.22)
LAN_I = 2.6            # lantern intensity
LAN_EMIT = 7.0         # glowing lantern paper
FOG_SIGMA = 0.020      # ground mist density at the ground
FOG_HEIGHT = 5.0       # mist scale height (blocks)
FOG_BASE = 0.0035      # thin haze everywhere
EXPOSURE = 2.4


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


def box_blur(a, r):
    out = a.copy()
    for axis in (0, 1):
        acc = np.zeros_like(out)
        for k in range(-r, r + 1):
            acc += np.roll(out, k, axis=axis)
        out = acc / (2 * r + 1)
    return out


def set_block(world, x, y, z, block, overwrite=(AIR,)):
    if 0 <= x < W and 0 <= y < H and 0 <= z < D and world[x, y, z] in overwrite:
        world[x, y, z] = block


def grow_cherry_tree(world, x, y, z, rng):
    trunk = int(rng.integers(5, 8))
    lean = [(1, 0), (-1, 0), (0, 1), (0, -1)][int(rng.integers(0, 4))]
    px, pz = x, z
    for i in range(trunk):
        if i == trunk - 2:
            # step sideways: keep the log below so the trunk stays connected
            set_block(world, px + lean[0], y + i - 1, pz + lean[1], LOG, (AIR, LEAVES))
            px, pz = px + lean[0], pz + lean[1]
        set_block(world, px, y + i, pz, LOG, (AIR, LEAVES))
    canopies = [(px, y + trunk, pz, rng.uniform(3.6, 5.2))]
    # side branch: a continuous chain of logs from the trunk to the second canopy
    if rng.random() < 0.8:
        by = y + trunk - 3
        bx, bz = x, z
        for i in range(1, 4):
            bx, bz = x - lean[0] * i, z - lean[1] * i
            if i == 2:
                by += 1
                set_block(world, bx, by - 1, bz, LOG, (AIR, LEAVES))
            set_block(world, bx, by, bz, LOG, (AIR, LEAVES))
        canopies.append((bx, by + 1, bz, rng.uniform(2.8, 4.0)))
    for (ox, oy, oz, r) in canopies:
        ri = int(math.ceil(r))
        for dx in range(-ri - 1, ri + 2):
            for dy in range(-2, 3):
                for dz in range(-ri - 1, ri + 2):
                    d = (dx * dx + dz * dz) / (r * r) + ((dy - 0.3) * (dy - 0.3)) / 3.2
                    if d < 1.0 and rng.random() > 0.06 + 0.3 * max(0, d - 0.75):
                        set_block(world, ox + dx, oy + dy, oz + dz, LEAVES)
        # hanging blossoms, only directly below an existing leaf block
        for _ in range(int(r * 5)):
            ang = rng.uniform(0, 2 * math.pi)
            hx, hz = int(round(ox + math.cos(ang) * (r - 0.6))), int(round(oz + math.sin(ang) * (r - 0.6)))
            col_y = None
            for yy in range(oy + 2, oy - 3, -1):
                if 0 <= hx < W and 0 <= hz < D and 0 <= yy < H and world[hx, yy, hz] == LEAVES:
                    col_y = yy
            if col_y is None:
                continue
            for dy in range(1, int(rng.integers(2, 4))):
                set_block(world, hx, col_y - dy, hz, LEAVES)


def remove_floating(world):
    """Keeps only logs/leaves/lanterns/plants connected (6-neighbour) to the terrain."""
    support = np.isin(world, (GRASS, DIRT, STONE, WATER, PETALS))
    keep = support.copy()
    attachable = np.isin(world, (LOG, LEAVES, LANTERN, STONE_LANTERN, PLANT, FLOWER))
    q = deque()
    xs, ys, zs = np.nonzero(attachable)
    for x, y, z in zip(xs, ys, zs):
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            nx, ny, nz = x + dx, y + dy, z + dz
            if 0 <= nx < W and 0 <= ny < H and 0 <= nz < D and support[nx, ny, nz]:
                keep[x, y, z] = True
                q.append((x, y, z))
                break
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            nx, ny, nz = x + dx, y + dy, z + dz
            if 0 <= nx < W and 0 <= ny < H and 0 <= nz < D and attachable[nx, ny, nz] and not keep[nx, ny, nz]:
                keep[nx, ny, nz] = True
                q.append((nx, ny, nz))
    floating = attachable & ~keep
    world[floating] = AIR
    return int(floating.sum())


def build_world():
    rng = np.random.default_rng(SEED)
    world = np.zeros((W, H, D), dtype=np.uint8)
    height = 18 + 7 * value_noise((W, D), 48, rng) + 3 * value_noise((W, D), 16, rng) + 0.8 * value_noise((W, D), 6, rng)
    cx, cz = W // 2, D // 2
    xx, zz = np.meshgrid(np.arange(W), np.arange(D), indexing="ij")
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

    # Japanese stone lanterns standing on the ground: stone base + glowing lantern + stone roof
    lights = []

    def lantern_ok(x, z):
        h = height[x, z]
        return (world[x, h, z] == GRASS and world[x, h + 1, z] == AIR and world[x, h + 2, z] == AIR
                and world[x, h + 3, z] == AIR and all((x - lx) ** 2 + (z - lz) ** 2 > 7 ** 2 for lx, _, lz in lights))
    for i in range(10):
        ang = i / 10 * 2 * math.pi + 0.3
        x = int(round(cx + math.cos(ang) * 11.5))
        z = int(round(cz - 13 + math.sin(ang) * 9.0))
        if 0 < x < W and 0 < z < D and (x - cx) ** 2 + (z - cz) ** 2 >= 10 ** 2 and lantern_ok(x, z):
            lights.append((x, height[x, z], z))
    tries = 0
    while len(lights) < 70 and tries < 6000:
        tries += 1
        x, z = int(rng.integers(4, W - 4)), int(rng.integers(4, D - 4))
        if (x - cx) ** 2 + (z - cz) ** 2 < 10 ** 2:
            continue
        if lantern_ok(x, z):
            lights.append((x, height[x, z], z))
    for (x, h, z) in lights:
        world[x, h + 1, z] = STONE_LANTERN
        world[x, h + 2, z] = LANTERN
        set_block(world, x, h + 3, z, STONE_LANTERN)

    removed = remove_floating(world)

    # fallen petals under the canopies, grass tufts and a few flowers elsewhere (all on the ground)
    canopy = (world == LEAVES).any(axis=1)
    shade = box_blur(canopy.astype(np.float32), 3)
    plants = 0
    for x in range(W):
        for z in range(D):
            h = height[x, z]
            if world[x, h, z] != GRASS or world[x, h + 1, z] != AIR:
                continue
            if shade[x, z] > 0.10 and rng.random() < 0.30 + 0.7 * shade[x, z]:
                world[x, h + 1, z] = PETALS
            elif (x - cx) ** 2 + (z - cz) ** 2 > 2.5 ** 2 and rng.random() < 0.34:
                world[x, h + 1, z] = FLOWER if rng.random() < 0.10 else PLANT
                plants += 1
    light_pos = np.array([(x + 0.5, h + 2.5, z + 0.5) for (x, h, z) in lights], dtype=np.float64)
    cam_y = height[cx, cz] + 3.1
    print(f"{len(trees)} trees, {len(lights)} lanterns, {plants} plants, removed {removed} floating blocks")
    pink = (world == PETALS).any(axis=1).astype(np.float32)
    return world, (cx + 0.5, cam_y, cz + 0.5), light_pos, pink, float(water_level + 1)


# ------------------------------------------------------------------ helpers

INV32 = 1.0 / 4294967296.0


@njit(cache=True, inline="always")
def xs(s):
    s ^= (s << 13) & 0xFFFFFFFF
    s ^= s >> 17
    s ^= (s << 5) & 0xFFFFFFFF
    return s & 0xFFFFFFFF


@njit(cache=True, inline="always")
def hsh(a, b, c):
    h = (a * 374761393 + b * 668265263 + c * 1274126177 + 1013904223) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1103515245) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFFFF) / 16777215.0


@njit(cache=True, inline="always")
def vnoise(x, y, seed):
    xi = math.floor(x)
    yi = math.floor(y)
    xf = x - xi
    yf = y - yi
    u = xf * xf * (3 - 2 * xf)
    v = yf * yf * (3 - 2 * yf)
    ix = int(xi)
    iy = int(yi)
    a = hsh(ix, iy, seed)
    b = hsh(ix + 1, iy, seed)
    c = hsh(ix, iy + 1, seed)
    d = hsh(ix + 1, iy + 1, seed)
    return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v


@njit(cache=True, inline="always")
def fbm(x, y, seed, octaves):
    s = 0.0
    amp = 0.5
    norm = 0.0
    for i in range(octaves):
        s += amp * vnoise(x, y, seed + i * 31)
        norm += amp
        x *= 2.03
        y *= 2.03
        amp *= 0.5
    return s / norm


@njit(cache=True, inline="always")
def mix3(a0, a1, a2, b0, b1, b2, t):
    return a0 + (b0 - a0) * t, a1 + (b1 - a1) * t, a2 + (b2 - a2) * t


# ------------------------------------------------------------------ materials (32x32 textures)

@njit(cache=True)
def material(blk, side, ix, iy, iz, u, v, near_pink):
    """Albedo (r, g, b), alpha and emission for a hit. side: 0 = side face, 1 = top, 2 = bottom."""
    tu = min(31, max(0, int(u * 32)))
    tv = min(31, max(0, int(v * 32)))
    n = hsh(ix * 32 + tu, iy * 977 + tv * 7 + side, iz * 32 + tv)
    if blk == GRASS:
        wx = ix + u
        wz = iz + v
        if side == 1:
            patch = fbm(wx * 0.35, wz * 0.35, 11, 3)
            blade = hsh(int(wx * 32), int(wz * 32), 5)
            r, g, b = mix3(0.050, 0.115, 0.032, 0.100, 0.190, 0.050, patch)
            k = 0.70 + 0.55 * blade
            r *= k
            g *= k
            b *= k
            if near_pink and hsh(int(wx * 16), int(wz * 16), 9) > 0.86:
                return 0.64, 0.33, 0.44, 1.0, 0.0
            return r, g, b, 1.0, 0.0
        if side == 2:
            return 0.15 * (0.7 + 0.6 * n), 0.10 * (0.7 + 0.6 * n), 0.07 * (0.7 + 0.6 * n), 1.0, 0.0
        edge = 0.80 - 0.10 * hsh(ix * 32 + tu, iz * 32 + tu, 3)
        if v > edge:
            k = 0.7 + 0.5 * n
            return 0.075 * k, 0.15 * k, 0.04 * k, 1.0, 0.0
        k = 0.65 + 0.6 * n
        if n > 0.94:
            return 0.22, 0.21, 0.20, 1.0, 0.0
        return 0.15 * k, 0.10 * k, 0.068 * k, 1.0, 0.0
    if blk == DIRT:
        k = 0.65 + 0.6 * n
        if n > 0.94:
            return 0.22, 0.21, 0.20, 1.0, 0.0
        return 0.15 * k, 0.10 * k, 0.068 * k, 1.0, 0.0
    if blk == STONE or blk == STONE_LANTERN:
        g0 = 0.26 + 0.10 * fbm((ix * 32 + tu) * 0.25, (iy * 32 + iz * 7 + tv) * 0.25, 21, 3)
        if n > 0.92:
            g0 *= 0.6
        elif n < 0.06:
            g0 *= 1.35
        r, g, b = g0, g0 * 0.99, g0 * 1.03
        if blk == STONE_LANTERN:
            if tu <= 1 or tu >= 30 or tv <= 1 or tv >= 30:
                r *= 0.72
                g *= 0.72
                b *= 0.72
            moss = fbm((ix * 32 + tu) * 0.18, (iz * 32 + tv + iy * 11) * 0.18, 33, 2)
            if side == 1 and moss > 0.55:
                t = min(1.0, (moss - 0.55) * 4.0)
                r, g, b = mix3(r, g, b, 0.10, 0.16, 0.06, t)
        return r, g, b, 1.0, 0.0
    if blk == LOG:
        if side == 0:
            # cherry bark: dark red-brown with pale horizontal lenticels
            k = 0.75 + 0.5 * hsh(ix * 32 + tu, iy * 5 + iz, 77)
            r, g, b = 0.13 * k, 0.065 * k, 0.065 * k
            band = (tv + int(hsh(ix, iy, iz) * 7)) % 7
            if band == 0 and hsh(ix * 32 + tu // 3, iy * 32 + tv, iz) > 0.25:
                r, g, b = 0.27, 0.19, 0.16
            return r, g, b, 1.0, 0.0
        ring = math.sqrt((u - 0.5) ** 2 + (v - 0.5) ** 2)
        rr = 0.5 + 0.5 * math.sin(ring * 60.0)
        return 0.34 + 0.06 * rr, 0.24 + 0.05 * rr, 0.19 + 0.04 * rr, 1.0, 0.0
    if blk == LEAVES:
        cl = vnoise((ix * 32 + tu) * 0.22 + iy * 3.1, (iz * 32 + tv) * 0.22 + side * 7.0, 41)
        hole = hsh(ix * 32 + tu, iy * 32 + tv, iz * 32 + side)
        if hole < 0.035 and cl < 0.45:
            return 0.0, 0.0, 0.0, 0.0, 0.0      # a few small gaps, only in the darker clusters
        if cl < 0.035:
            return 0.20, 0.19, 0.08, 1.0, 0.0   # very few young leaves
        cl2 = vnoise((ix * 32 + tu) * 0.09 - iy * 1.7, (iz * 32 + tv) * 0.09 + side * 3.0, 43)
        r, g, b = mix3(0.56, 0.19, 0.33, 0.86, 0.50, 0.65, 0.55 * cl + 0.45 * cl2)
        k = 0.88 + 0.20 * n
        if n < 0.10:
            k *= 0.78                            # darker blossom pixels like the block texture
        return r * k, g * k, b * k, 1.0, 0.0
    if blk == PETALS:
        c = vnoise((ix * 32 + tu) * 0.16, (iz * 32 + tv) * 0.16, 61)
        if 0.62 * c + 0.38 * n < 0.50:
            return 0.0, 0.0, 0.0, 0.0, 0.0
        k = 0.84 + 0.26 * n
        return 0.72 * k, (0.33 + 0.10 * c) * k, 0.48 * k, 1.0, 0.0
    if blk == LANTERN:
        if side != 0:
            return 0.07, 0.045, 0.03, 1.0, 0.0
        frame = tu <= 1 or tu >= 30 or tv <= 1 or tv >= 30 or tu == 15 or tu == 16 or tv == 10 or tv == 11 or tv == 21 or tv == 22
        if frame:
            return 0.07, 0.045, 0.03, 1.0, 0.0
        paper = 0.85 + 0.25 * fbm((ix * 32 + tu) * 0.5, (iy * 32 + tv) * 0.5, 55, 2)
        glow = 1.0 - 0.35 * abs(v - 0.5)
        return 0.0, 0.0, 0.0, 1.0, LAN_EMIT * paper * glow
    return 0.5, 0.0, 0.5, 1.0, 0.0


@njit(cache=True)
def plant_alpha(blk, ix, iz, plane, s, h):
    """Cross-plant texture: grass blades (and a blossom for flowers). Returns r, g, b, a."""
    seed = ix * 73856093 + iz * 19349663 + plane * 83492791
    if blk == FLOWER:
        dx = s - 0.5
        dy = h - 0.62
        if dx * dx + dy * dy < 0.008:
            c = hsh(ix, iz, 99)
            if c < 0.4:
                return 0.62, 0.60, 0.56, 1.0
            return 0.74, 0.36, 0.52, 1.0
        if abs(s - 0.5) < 0.035 and h < 0.6:
            return 0.07, 0.14, 0.04, 1.0
    for k in range(5):
        c = 0.15 + 0.7 * hsh(seed, k, 1)
        top = 0.22 + 0.40 * hsh(seed, k, 2)
        lean = (hsh(seed, k, 3) - 0.5) * 0.35
        if h > top:
            continue
        x = c + lean * h * h
        w = 0.055 * (1.0 - h / top) + 0.008
        if abs(s - x) < w:
            k2 = 0.8 + 0.5 * h / top
            return 0.075 * k2, 0.16 * k2, 0.045 * k2, 1.0
    return 0.0, 0.0, 0.0, 0.0


# ------------------------------------------------------------------ ray traversal

@njit(cache=True, inline="always")
def block_at(world, x, y, z):
    if x < 0 or y < 0 or z < 0 or x >= world.shape[0] or y >= world.shape[1] or z >= world.shape[2]:
        return 0
    return world[x, y, z]


@njit(cache=True)
def trace(world, pink, ox, oy, oz, dx, dy, dz, max_t):
    """First visible surface: (block, t, nx, ny, nz, u, v, ix, iy, iz, side). block = -1 for a miss."""
    x = int(math.floor(ox))
    y = int(math.floor(oy))
    z = int(math.floor(oz))
    sx = 1 if dx > 0 else -1
    sy = 1 if dy > 0 else -1
    sz = 1 if dz > 0 else -1
    inf = 1e30
    tdx = abs(1.0 / dx) if dx != 0 else inf
    tdy = abs(1.0 / dy) if dy != 0 else inf
    tdz = abs(1.0 / dz) if dz != 0 else inf
    tmx = (((x + 1 - ox) if dx > 0 else (ox - x)) * tdx) if dx != 0 else inf
    tmy = (((y + 1 - oy) if dy > 0 else (oy - y)) * tdy) if dy != 0 else inf
    tmz = (((z + 1 - oz) if dz > 0 else (oz - z)) * tdz) if dz != 0 else inf
    t = 0.0
    axis = -1
    for _ in range(4096):
        if tmx < tmy and tmx < tmz:
            x += sx
            t = tmx
            tmx += tdx
            axis = 0
        elif tmy < tmz:
            y += sy
            t = tmy
            tmy += tdy
            axis = 1
        else:
            z += sz
            t = tmz
            tmz += tdz
            axis = 2
        if t > max_t:
            break
        if y < 0 or (y >= world.shape[1] and dy >= 0):
            break
        b = block_at(world, x, y, z)
        if b == AIR:
            continue
        t_exit = min(tmx, min(tmy, tmz))
        if b == PETALS:
            if dy < 0:
                tp = (y + 0.0625 - oy) / dy
                if tp >= t and tp <= t_exit:
                    u = ox + dx * tp - x
                    v = oz + dz * tp - z
                    ar, ag, ab, aa, ae = material(PETALS, 1, x, y, z, u, v, False)
                    if aa > 0:
                        return PETALS, tp, 0.0, 1.0, 0.0, u, v, x, y, z, 1
            continue
        if b == PLANT or b == FLOWER:
            best = 1e30
            bu = 0.0
            bv = 0.0
            bp = 0
            for plane in range(2):
                if plane == 0:
                    den = dx - dz
                    if abs(den) < 1e-9:
                        continue
                    tp = ((x - ox) - (z - oz)) / den
                else:
                    den = dx + dz
                    if abs(den) < 1e-9:
                        continue
                    tp = (1.0 + x + z - ox - oz) / den
                if tp < t or tp > t_exit or tp >= best:
                    continue
                lx = ox + dx * tp - x
                ly = oy + dy * tp - y
                if lx < 0 or lx > 1 or ly < 0 or ly > 1:
                    continue
                pr, pg, pb, pa = plant_alpha(b, x, z, plane, lx, ly)
                if pa > 0:
                    best = tp
                    bu = lx
                    bv = ly
                    bp = plane
            if best < 1e29:
                return b, best, 0.0, 1.0, 0.0, bu, bv, x, y, z, bp
            continue
        if b == WATER:
            above = block_at(world, x, y + 1, z)
            if above == WATER:
                return WATER, t, 0.0, 1.0, 0.0, 0.0, 0.0, x, y, z, 1
            if dy < 0:
                tp = (y + 0.875 - oy) / dy
                if tp >= t and tp <= t_exit:
                    return WATER, tp, 0.0, 1.0, 0.0, 0.0, 0.0, x, y, z, 1
            continue
        hx = ox + dx * t
        hy = oy + dy * t
        hz = oz + dz * t
        nx = 0.0
        ny = 0.0
        nz = 0.0
        if axis == 0:
            nx = -float(sx)
            u = hz - z
            v = hy - y
            side = 0
        elif axis == 1:
            ny = -float(sy)
            u = hx - x
            v = hz - z
            side = 1 if ny > 0 else 2
        else:
            nz = -float(sz)
            u = hx - x
            v = hy - y
            side = 0
        u = min(0.9999, max(0.0, u))
        v = min(0.9999, max(0.0, v))
        if b == LEAVES:
            ar, ag, ab, aa, ae = material(LEAVES, side, x, y, z, u, v, False)
            if aa == 0:
                continue
        return b, t, nx, ny, nz, u, v, x, y, z, side
    return -1, max_t, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0


@njit(cache=True)
def shadow(world, ox, oy, oz, dx, dy, dz, max_t):
    """Light transmittance along a ray (0 = blocked). Leaves are alpha-tested -> dappled light."""
    x = int(math.floor(ox))
    y = int(math.floor(oy))
    z = int(math.floor(oz))
    sx = 1 if dx > 0 else -1
    sy = 1 if dy > 0 else -1
    sz = 1 if dz > 0 else -1
    inf = 1e30
    tdx = abs(1.0 / dx) if dx != 0 else inf
    tdy = abs(1.0 / dy) if dy != 0 else inf
    tdz = abs(1.0 / dz) if dz != 0 else inf
    tmx = (((x + 1 - ox) if dx > 0 else (ox - x)) * tdx) if dx != 0 else inf
    tmy = (((y + 1 - oy) if dy > 0 else (oy - y)) * tdy) if dy != 0 else inf
    tmz = (((z + 1 - oz) if dz > 0 else (oz - z)) * tdz) if dz != 0 else inf
    t = 0.0
    axis = 0
    for _ in range(2048):
        if tmx < tmy and tmx < tmz:
            x += sx
            t = tmx
            tmx += tdx
            axis = 0
        elif tmy < tmz:
            y += sy
            t = tmy
            tmy += tdy
            axis = 1
        else:
            z += sz
            t = tmz
            tmz += tdz
            axis = 2
        if t > max_t:
            return 1.0
        if y >= world.shape[1] or y < 0:
            return 1.0
        b = block_at(world, x, y, z)
        if b == AIR or b == WATER or b == PETALS or b == PLANT or b == FLOWER or b == LANTERN:
            continue
        if b == LEAVES:
            hx = ox + dx * t - x
            hy = oy + dy * t - y
            hz = oz + dz * t - z
            if axis == 0:
                u, v, side = hz, hy, 0
            elif axis == 1:
                u, v, side = hx, hz, 1
            else:
                u, v, side = hx, hy, 0
            ar, ag, ab, aa, ae = material(LEAVES, side, x, y, z, min(0.9999, max(0.0, u)), min(0.9999, max(0.0, v)), False)
            if aa > 0:
                return 0.0
            continue
        return 0.0
    return 1.0


# ------------------------------------------------------------------ sky

@njit(cache=True)
def sky_ambient(dx, dy, dz, mx, my, mz):
    """Sky light without stars or the moon disc (used for indirect light)."""
    t = max(0.0, dy)
    k = (1.0 - t) ** 3
    r = 0.0045 + 0.020 * k
    g = 0.0075 + 0.022 * k
    b = 0.020 + 0.034 * k
    md = dx * mx + dy * my + dz * mz
    if md > 0:
        glow = md ** 12 * 0.06 + md ** 3 * 0.012
        r += glow * 0.7
        g += glow * 0.82
        b += glow
    if dy < 0:
        f = min(1.0, -dy * 4)
        r = r * (1 - f) + 0.010 * f
        g = g * (1 - f) + 0.012 * f
        b = b * (1 - f) + 0.020 * f
    return r, g, b


@njit(cache=True)
def sky(dx, dy, dz, mx, my, mz):
    r, g, b = sky_ambient(dx, dy, dz, mx, my, mz)
    if dy < -0.02:
        return r, g, b
    # Milky Way: a soft band across the sky with many faint stars
    bx, by, bz = 0.42, 0.55, 0.72
    bl = math.sqrt(bx * bx + by * by + bz * bz)
    band = math.exp(-((dx * bx + dy * by + dz * bz) / bl / 0.20) ** 2)
    hz = min(1.0, max(0.0, dy * 5.0))
    if band > 0.02:
        neb = fbm(dx * 6 + 3.0, dz * 6 + dy * 4, 61, 4)
        mw = band * (0.4 + 0.9 * neb) * 0.016 * hz
        dust = fbm(dx * 14 + 9.0, dz * 14 - dy * 9, 67, 3)
        if dust > 0.6:
            mw *= 0.45
        r += mw * 0.85
        g += mw * 0.88
        b += mw
    # stars
    scale = 360.0
    cx = int(math.floor(dx * scale))
    cy = int(math.floor(dy * scale))
    cz = int(math.floor(dz * scale))
    s = hsh(cx + 11, cy + 22, cz + 33)
    thr = 0.9955 - band * 0.012
    if s > thr and dy > 0.04:
        tw = (s - thr) / (1.0 - thr)
        mag = (0.05 + 1.6 * tw ** 3) * hz
        temp = hsh(cx, cy, cz)
        r += mag * (0.85 + 0.25 * temp)
        g += mag * 0.92
        b += mag * (1.1 - 0.25 * temp)
    # thin clouds lit by the moon
    if dy > 0.03:
        px = dx / (dy + 0.15)
        pz = dz / (dy + 0.15)
        c = fbm(px * 1.6 + 5.0, pz * 1.6 - 2.0, 71, 5)
        cov = max(0.0, (c - 0.52) * 3.2) * min(1.0, dy * 6.0) * (1.0 - min(1.0, dy * 1.4))
        if cov > 0:
            md = max(0.0, dx * mx + dy * my + dz * mz)
            lit = 0.020 + 0.09 * md ** 8
            cov = min(cov, 0.85)
            r = r * (1 - cov) + lit * 0.80 * cov
            g = g * (1 - cov) + lit * 0.86 * cov
            b = b * (1 - cov) + lit * 1.00 * cov
    # the moon
    md = dx * mx + dy * my + dz * mz
    if md > 0.99960:
        ux = dx - mx * md
        uy = dy - my * md
        uz = dz - mz * md
        maria = fbm(ux * 900 + 7.0, (uy + uz) * 900 - 3.0, 81, 4)
        limb = (1.0 - md) / (1.0 - 0.99960)
        v = 14.0 * (0.78 + 0.32 * (1.0 - maria)) * (1.0 - 0.25 * limb)
        return v * 0.96, v * 0.97, v,
    return r, g, b


# ------------------------------------------------------------------ lighting

@njit(cache=True, inline="always")
def basis(nx, ny, nz):
    if abs(nx) > 0.9:
        ax, ay, az = 0.0, 1.0, 0.0
    else:
        ax, ay, az = 1.0, 0.0, 0.0
    tx = ay * nz - az * ny
    ty = az * nx - ax * nz
    tz = ax * ny - ay * nx
    l = math.sqrt(tx * tx + ty * ty + tz * tz)
    tx /= l
    ty /= l
    tz /= l
    bx = ny * tz - nz * ty
    by = nz * tx - nx * tz
    bz = nx * ty - ny * tx
    return tx, ty, tz, bx, by, bz


@njit(cache=True)
def direct(world, lights, px, py, pz, nx, ny, nz, translucent, mx, my, mz, s):
    r = 0.0
    g = 0.0
    b = 0.0
    # moon as a small disc light: jitter the direction for soft shadows
    s = xs(s)
    a1 = s * INV32
    s = xs(s)
    a2 = s * INV32
    tx, ty, tz, bx, by, bz = basis(mx, my, mz)
    rr = math.sqrt(a1) * 0.012
    ph = 6.283185307 * a2
    ldx = mx + (tx * math.cos(ph) + bx * math.sin(ph)) * rr
    ldy = my + (ty * math.cos(ph) + by * math.sin(ph)) * rr
    ldz = mz + (tz * math.cos(ph) + bz * math.sin(ph)) * rr
    ll = math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz)
    ldx /= ll
    ldy /= ll
    ldz /= ll
    ndl = nx * ldx + ny * ldy + nz * ldz
    if translucent:
        ndl = ndl if ndl > 0 else -ndl * 0.55
    if ndl > 0:
        vis = shadow(world, px + nx * 0.02, py + ny * 0.02, pz + nz * 0.02, ldx, ldy, ldz, 72.0)
        e = MOON_E * ndl * vis
        r += e * MOON_COL[0]
        g += e * MOON_COL[1]
        b += e * MOON_COL[2]
    # lanterns as small area lights
    for i in range(lights.shape[0]):
        cx = lights[i, 0] - px
        cy = lights[i, 1] - py
        cz = lights[i, 2] - pz
        d2c = cx * cx + cy * cy + cz * cz
        if d2c > 20.0 * 20.0:
            continue
        s = xs(s)
        jx = (s * INV32 - 0.5) * 0.5
        s = xs(s)
        jy = (s * INV32 - 0.5) * 0.5
        s = xs(s)
        jz = (s * INV32 - 0.5) * 0.5
        lx = cx + jx
        ly = cy + jy
        lz = cz + jz
        d2 = lx * lx + ly * ly + lz * lz
        d = math.sqrt(d2) + 1e-6
        lx /= d
        ly /= d
        lz /= d
        ndl = nx * lx + ny * ly + nz * lz
        if translucent:
            ndl = ndl if ndl > 0 else -ndl * 0.55
        if ndl <= 0:
            continue
        vis = shadow(world, px + nx * 0.02, py + ny * 0.02, pz + nz * 0.02, lx, ly, lz, d - 0.55)
        if vis <= 0:
            continue
        e = LAN_I * ndl * vis / (d2 + 0.35)
        r += e * LAN_COL[0]
        g += e * LAN_COL[1]
        b += e * LAN_COL[2]
    return r, g, b, s


@njit(cache=True, inline="always")
def fog_trans(oy, dy, t, ground):
    """Transmittance through exponential ground mist + thin haze."""
    h0 = (oy - ground) / FOG_HEIGHT
    if abs(dy) < 1e-4:
        od = FOG_SIGMA * math.exp(-h0) * t
    else:
        k = dy / FOG_HEIGHT
        od = FOG_SIGMA * math.exp(-h0) * (1.0 - math.exp(-k * t)) / k
    od += FOG_BASE * t
    return math.exp(-od)


@njit(cache=True)
def surface_albedo(blk, side, ix, iy, iz, u, v, pink):
    if blk == PLANT or blk == FLOWER:
        r, g, b, a = plant_alpha(blk, ix, iz, side, u, v)
        return r, g, b, 0.0
    near = pink[min(max(ix, 0), pink.shape[0] - 1), min(max(iz, 0), pink.shape[1] - 1)] > 0.1
    r, g, b, a, e = material(blk, side, ix, iy, iz, u, v, near)
    return r, g, b, e


@njit(cache=True)
def shade_simple(world, lights, pink, ox, oy, oz, dx, dy, dz, mx, my, mz, ground, s):
    """Radiance along a secondary ray (reflections): direct light + ambient, no further bounces."""
    blk, t, nx, ny, nz, u, v, ix, iy, iz, side = trace(world, pink, ox, oy, oz, dx, dy, dz, 160.0)
    T = fog_trans(oy, dy, t, ground)
    if blk < 0:
        r, g, b = sky(dx, dy, dz, mx, my, mz)
        return r * T + 0.012 * (1 - T), g * T + 0.014 * (1 - T), b * T + 0.024 * (1 - T), s
    ar, ag, ab, e = surface_albedo(blk, side, ix, iy, iz, u, v, pink)
    if e > 0:
        return e * LAN_COL[0] * T, e * LAN_COL[1] * T, e * LAN_COL[2] * T, s
    if blk == WATER:
        return 0.004 * T, 0.008 * T, 0.012 * T, s
    px = ox + dx * t
    py = oy + dy * t
    pz = oz + dz * t
    tr = blk == LEAVES or blk == PLANT or blk == FLOWER
    if blk == PLANT or blk == FLOWER:
        nx, ny, nz = 0.0, 1.0, 0.0
    er, eg, eb, s = direct(world, lights, px, py, pz, nx, ny, nz, tr, mx, my, mz, s)
    er += 0.012
    eg += 0.015
    eb += 0.028
    return ar * er * T + 0.012 * (1 - T), ag * eg * T + 0.014 * (1 - T), ab * eb * T + 0.024 * (1 - T), s


@njit(cache=True)
def lantern_halo(lights, ox, oy, oz, dx, dy, dz, t_hit):
    r = 0.0
    for i in range(lights.shape[0]):
        lx = lights[i, 0] - ox
        ly = lights[i, 1] - oy
        lz = lights[i, 2] - oz
        t0 = lx * dx + ly * dy + lz * dz
        qx = lx - dx * t0
        qy = ly - dy * t0
        qz = lz - dz * t0
        h2 = qx * qx + qy * qy + qz * qz
        if h2 > 49.0:
            continue
        h = math.sqrt(h2 + 0.09)
        a = (math.atan((t_hit - t0) / h) - math.atan((0.0 - t0) / h)) / h
        r += a
    return r


@njit(cache=True)
def path(world, lights, pink, ox, oy, oz, dx, dy, dz, mx, my, mz, ground, s, vol_steps):
    """One camera sample. Returns albedo, lighting, extra radiance, volumetric light, depth, normal."""
    blk, t, nx, ny, nz, u, v, ix, iy, iz, side = trace(world, pink, ox, oy, oz, dx, dy, dz, 260.0)
    T = fog_trans(oy, dy, t, ground)
    # warm glow in the mist around every lantern along the visible part of the ray
    halo = lantern_halo(lights, ox, oy, oz, dx, dy, dz, t) * 0.010
    xr = halo * LAN_COL[0] + 0.010 * (1 - T)
    xg = halo * LAN_COL[1] + 0.012 * (1 - T)
    xb = halo * LAN_COL[2] + 0.022 * (1 - T)
    ar = ag = ab = 0.0
    er = eg = eb = 0.0
    # moonlight shafts through the canopies (volumetric)
    vr = 0.0
    if vol_steps > 0:
        tmax = min(t, 70.0)
        dt = tmax / vol_steps
        s = xs(s)
        j = s * INV32
        cosang = dx * mx + dy * my + dz * mz
        g_hg = 0.55
        phase = (1 - g_hg * g_hg) / (4 * math.pi * (1 + g_hg * g_hg - 2 * g_hg * cosang) ** 1.5)
        for k in range(vol_steps):
            tt = (k + j) * dt
            py_ = oy + dy * tt
            if py_ > ground + 12.0:
                break
            sig = FOG_SIGMA * math.exp(-(py_ - ground) / FOG_HEIGHT) + FOG_BASE
            vis = shadow(world, ox + dx * tt, py_, oz + dz * tt, mx, my, mz, 60.0)
            if vis > 0:
                vr += sig * vis * phase * dt * fog_trans(oy, dy, tt, ground)
        vr *= 0.10
    if blk < 0:
        r, g, b = sky(dx, dy, dz, mx, my, mz)
        xr += r * T
        xg += g * T
        xb += b * T
        return ar, ag, ab, er, eg, eb, xr, xg, xb, vr, 1e9, 0.0, 0.0, 0.0, s
    px = ox + dx * t
    py = oy + dy * t
    pz = oz + dz * t
    if blk == WATER:
        # rippled water: perturbed normal, Fresnel reflection of the scene
        e = 0.06
        hgt = 0.0
        wx = px
        wz = pz
        def_x = (vnoise((wx + e) * 2.1, wz * 2.1, 91) - vnoise((wx - e) * 2.1, wz * 2.1, 91)) / (2 * e)
        def_z = (vnoise(wx * 2.1, (wz + e) * 2.1, 91) - vnoise(wx * 2.1, (wz - e) * 2.1, 91)) / (2 * e)
        def_x += 0.6 * math.cos(wx * 1.3 + wz * 0.4) + 0.4 * math.cos(wx * 0.7 - wz * 1.9)
        def_z += 0.6 * 0.4 * math.cos(wx * 1.3 + wz * 0.4) - 0.4 * 1.9 * math.cos(wx * 0.7 - wz * 1.9)
        wnx = -def_x * 0.035
        wnz = -def_z * 0.035
        wny = 1.0
        wl = math.sqrt(wnx * wnx + wny * wny + wnz * wnz)
        wnx /= wl
        wny /= wl
        wnz /= wl
        dn = dx * wnx + dy * wny + dz * wnz
        rx = dx - 2 * dn * wnx
        ry = dy - 2 * dn * wny
        rz = dz - 2 * dn * wnz
        if ry < 0.01:
            ry = 0.01
        rl = math.sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rl
        ry /= rl
        rz /= rl
        cosi = min(1.0, abs(dn))
        fres = 0.02 + 0.98 * (1 - cosi) ** 5
        rr, rg, rb, s = shade_simple(world, lights, pink, px, py + 0.01, pz, rx, ry, rz, mx, my, mz, ground, s)
        deep_r, deep_g, deep_b = 0.0025, 0.0055, 0.0065
        xr += (fres * rr + (1 - fres) * deep_r) * T
        xg += (fres * rg + (1 - fres) * deep_g) * T
        xb += (fres * rb + (1 - fres) * deep_b) * T
        return ar, ag, ab, er, eg, eb, xr, xg, xb, vr, t, 0.0, 1.0, 0.0, s
    albr, albg, albb, emit = surface_albedo(blk, side, ix, iy, iz, u, v, pink)
    if emit > 0:
        xr += emit * LAN_COL[0] * T
        xg += emit * LAN_COL[1] * T
        xb += emit * LAN_COL[2] * T
        return ar, ag, ab, er, eg, eb, xr, xg, xb, vr, t, nx, ny, nz, s
    translucent = blk == LEAVES or blk == PLANT or blk == FLOWER
    if blk == PLANT or blk == FLOWER:
        nx, ny, nz = 0.0, 1.0, 0.0
    dr, dg, db, s = direct(world, lights, px, py, pz, nx, ny, nz, translucent, mx, my, mz, s)
    # one diffuse bounce (cosine-weighted)
    s = xs(s)
    u1 = s * INV32
    s = xs(s)
    u2 = s * INV32
    tx, ty, tz, bx, by, bz = basis(nx, ny, nz)
    rad = math.sqrt(u1)
    ph = 6.283185307 * u2
    lx = rad * math.cos(ph)
    lz = rad * math.sin(ph)
    ly = math.sqrt(max(0.0, 1.0 - u1))
    gdx = tx * lx + bx * lz + nx * ly
    gdy = ty * lx + by * lz + ny * ly
    gdz = tz * lx + bz * lz + nz * ly
    ir = ig = ib = 0.0
    b2, t2, n2x, n2y, n2z, u2_, v2_, jx, jy, jz, side2 = trace(world, pink, px + nx * 0.02, py + ny * 0.02, pz + nz * 0.02, gdx, gdy, gdz, 48.0)
    if b2 < 0:
        ir, ig, ib = sky_ambient(gdx, gdy, gdz, mx, my, mz)
    elif b2 != WATER:
        a2r, a2g, a2b, e2 = surface_albedo(b2, side2, jx, jy, jz, u2_, v2_, pink)
        if e2 <= 0:
            qx = px + gdx * t2
            qy = py + gdy * t2
            qz = pz + gdz * t2
            tr2 = b2 == LEAVES or b2 == PLANT or b2 == FLOWER
            if b2 == PLANT or b2 == FLOWER:
                n2x, n2y, n2z = 0.0, 1.0, 0.0
            q_r, q_g, q_b, s = direct(world, lights, qx, qy, qz, n2x, n2y, n2z, tr2, mx, my, mz, s)
            ir = a2r * (q_r + 0.010)
            ig = a2g * (q_g + 0.012)
            ib = a2b * (q_b + 0.022)
    er = (dr + ir + 0.0015) * T
    eg = (dg + ig + 0.0018) * T
    eb = (db + ib + 0.0030) * T
    return albr, albg, albb, er, eg, eb, xr, xg, xb, vr, t, nx, ny, nz, s


@njit(parallel=True, cache=True)
def render_face(world, lights, pink, cam, fwd, right, up, size, pad, moon, ground, spp, vol_spp, vol_steps, seed,
                A, E, X, V, Z, N):
    full = size + 2 * pad
    for py in prange(full):
        for px in range(full):
            s = (seed * 9781 + py * 6271 + px * 7919 + 12345) & 0xFFFFFFFF
            if s == 0:
                s = 1
            for i in range(4):
                s = xs(s)
            sar = sag = sab = 0.0
            ser = seg = seb = 0.0
            sxr = sxg = sxb = 0.0
            svr = 0.0
            for k in range(spp):
                s = xs(s)
                jx = s * INV32
                s = xs(s)
                jy = s * INV32
                a = ((px - pad + jx) / size) * 2 - 1
                c = 1 - ((py - pad + jy) / size) * 2
                dx = fwd[0] + right[0] * a + up[0] * c
                dy = fwd[1] + right[1] * a + up[1] * c
                dz = fwd[2] + right[2] * a + up[2] * c
                l = math.sqrt(dx * dx + dy * dy + dz * dz)
                vs = vol_steps if k < vol_spp else 0
                (ar, ag, ab, er, eg, eb, xr, xg, xb, vr, t, nx, ny, nz, s) = path(
                    world, lights, pink, cam[0], cam[1], cam[2], dx / l, dy / l, dz / l,
                    moon[0], moon[1], moon[2], ground, s, vs)
                sar += ar
                sag += ag
                sab += ab
                ser += er
                seg += eg
                seb += eb
                sxr += xr
                sxg += xg
                sxb += xb
                svr += vr
                if k == 0:
                    Z[py, px] = t
                    N[py, px, 0] = nx
                    N[py, px, 1] = ny
                    N[py, px, 2] = nz
            inv = 1.0 / spp
            A[py, px, 0] = sar * inv
            A[py, px, 1] = sag * inv
            A[py, px, 2] = sab * inv
            E[py, px, 0] = ser * inv
            E[py, px, 1] = seg * inv
            E[py, px, 2] = seb * inv
            X[py, px, 0] = sxr * inv
            X[py, px, 1] = sxg * inv
            X[py, px, 2] = sxb * inv
            V[py, px] = svr / max(1, min(spp, vol_spp))


@njit(parallel=True, cache=True)
def atrous(src, Z, N, step, out):
    h = (1.0 / 16, 1.0 / 4, 3.0 / 8, 1.0 / 4, 1.0 / 16)
    hgt, wid = Z.shape
    for y in prange(hgt):
        for x in range(wid):
            zc = Z[y, x]
            if zc > 1e8:
                out[y, x, 0] = src[y, x, 0]
                out[y, x, 1] = src[y, x, 1]
                out[y, x, 2] = src[y, x, 2]
                continue
            ncx = N[y, x, 0]
            ncy = N[y, x, 1]
            ncz = N[y, x, 2]
            sr = sg = sb = 0.0
            sw = 0.0
            for j in range(5):
                yy = y + (j - 2) * step
                if yy < 0 or yy >= hgt:
                    continue
                for i in range(5):
                    xx = x + (i - 2) * step
                    if xx < 0 or xx >= wid:
                        continue
                    z = Z[yy, xx]
                    if z > 1e8:
                        continue
                    wz = math.exp(-abs(z - zc) / (0.03 * zc * step + 0.05))
                    nd = ncx * N[yy, xx, 0] + ncy * N[yy, xx, 1] + ncz * N[yy, xx, 2]
                    if nd <= 0:
                        continue
                    wn = nd ** 32
                    w = h[i] * h[j] * wz * wn
                    sr += src[yy, xx, 0] * w
                    sg += src[yy, xx, 1] * w
                    sb += src[yy, xx, 2] * w
                    sw += w
            if sw > 0:
                out[y, x, 0] = sr / sw
                out[y, x, 1] = sg / sw
                out[y, x, 2] = sb / sw
            else:
                out[y, x, 0] = src[y, x, 0]
                out[y, x, 1] = src[y, x, 1]
                out[y, x, 2] = src[y, x, 2]


# Minecraft panorama layout: 0 front (-Z), 1 right (+X), 2 back (+Z), 3 left (-X), 4 top, 5 bottom.
FACES = [
    ((0, 0, -1), (1, 0, 0), (0, 1, 0)),
    ((1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 0, 1), (-1, 0, 0), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, -1, 0), (1, 0, 0), (0, 0, -1)),
]


def gaussian_blur(img, sigma):
    if sigma <= 0.3:
        return img
    r = int(sigma * 3)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    out = img
    for axis in (0, 1):
        pad = [(0, 0)] * img.ndim
        pad[axis] = (r, r)
        p = np.pad(out, pad, mode="edge")
        acc = np.zeros_like(out)
        for i, w in enumerate(k):
            sl = [slice(None)] * img.ndim
            sl[axis] = slice(i, i + out.shape[axis])
            acc += w * p[tuple(sl)]
        out = acc
    return out


def post(A, E, X, V, Z, N, size, pad):
    tmp = np.empty_like(E)
    for step in (1, 2, 4):
        atrous(E, Z, N, step, tmp)
        E, tmp = tmp, E
    s = size / 1024
    vol = gaussian_blur(V[..., None] * np.array(MOON_COL, dtype=np.float32), 6.0 * s)
    img = A * E + X + vol
    img *= EXPOSURE
    lum = img @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    bright = img * np.clip((lum - 0.9) / 2.0, 0, 1)[..., None]
    bloom = 0.50 * gaussian_blur(bright, 5 * s) + 0.35 * gaussian_blur(bright, 18 * s) + 0.30 * gaussian_blur(bright, 55 * s)
    img = img + 0.75 * bloom
    img = img[pad:pad + size, pad:pad + size]
    # ACES filmic curve
    img = (img * (2.51 * img + 0.03)) / (img * (2.43 * img + 0.59) + 0.14)
    img = np.clip(img, 0, 1) ** (1 / 2.2)
    return (img * 255 + 0.5).astype(np.uint8)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "panorama"
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    spp = int(sys.argv[3]) if len(sys.argv) > 3 else 12
    only = [int(f) for f in sys.argv[4].split(",")] if len(sys.argv) > 4 else list(range(6))
    os.makedirs(out, exist_ok=True)
    world, cam, lights, pink, ground = build_world()
    pad = size // 8
    full = size + 2 * pad
    for i in only:
        f, r, u = FACES[i]
        A = np.zeros((full, full, 3), np.float32)
        E = np.zeros((full, full, 3), np.float32)
        X = np.zeros((full, full, 3), np.float32)
        V = np.zeros((full, full), np.float32)
        Z = np.zeros((full, full), np.float32)
        N = np.zeros((full, full, 3), np.float32)
        render_face(world, lights, pink, np.array(cam), np.array(f, float), np.array(r, float), np.array(u, float),
                    size, pad, MOON, ground, spp, 2, 14, 1000 + i, A, E, X, V, Z, N)
        Image.fromarray(post(A, E, X, V, Z, N, size, pad)).save(os.path.join(out, f"panorama_{i}.png"), optimize=True)
        print("wrote", f"panorama_{i}.png", flush=True)


if __name__ == "__main__":
    main()
