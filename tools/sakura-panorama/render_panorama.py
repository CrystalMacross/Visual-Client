"""Renders a Minecraft-style cherry blossom (sakura) garden at night, with a shader look,
as a title-screen panorama.

Output: panorama_0.png .. panorama_5.png (front, right, back, left, top, bottom) - the
layout Minecraft expects in assets/minecraft/textures/gui/title/background/.

    python render_panorama.py <out_dir> [face_size]

Needs numpy, numba and pillow. Everything is procedural (no game assets are used).

Shader-style features: moon light with soft shadows, warm stone lanterns as point lights
(with shadows), reflective pond (one reflection bounce, Fresnel), ground mist, stars,
bloom (computed with padding around every face so the cube seams stay invisible) and a
filmic tone curve. Nothing floats: blocks not connected to the ground are removed.
"""
import math
import os
import sys
from collections import deque

import numpy as np
from numba import njit, prange
from PIL import Image

W, H, D = 192, 56, 192  # world size (x, y, z)
AIR, GRASS, DIRT, STONE, LOG, LEAVES, WATER, PETALS, LANTERN, STONE_LANTERN = 0, 1, 2, 3, 4, 5, 6, 7, 8, 9
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
    """Keeps only logs/leaves/lanterns connected (6-neighbour) to the terrain."""
    support = np.isin(world, (GRASS, DIRT, STONE, WATER, PETALS))
    keep = support.copy()
    attachable = np.isin(world, (LOG, LEAVES, LANTERN, STONE_LANTERN))
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

    # Japanese stone lanterns standing on the ground: stone base + glowing lantern block
    lights = []
    def lantern_ok(x, z):
        h = height[x, z]
        return (world[x, h, z] == GRASS and world[x, h + 1, z] == AIR and world[x, h + 2, z] == AIR
                and world[x, h + 3, z] == AIR and all((x - lx) ** 2 + (z - lz) ** 2 > 7 ** 2 for lx, _, lz in lights))
    # a ring around the pond ...
    for i in range(10):
        ang = i / 10 * 2 * math.pi + 0.3
        x = int(round(cx + math.cos(ang) * 11.5))
        z = int(round(cz - 13 + math.sin(ang) * 9.0))
        if 0 < x < W and 0 < z < D and (x - cx) ** 2 + (z - cz) ** 2 >= 10 ** 2 and lantern_ok(x, z):
            lights.append((x, height[x, z], z))
    # ... and scattered through the garden
    tries = 0
    while len(lights) < 70 and tries < 6000:
        tries += 1
        x, z = int(rng.integers(4, W - 4)), int(rng.integers(4, D - 4))
        if (x - cx) ** 2 + (z - cz) ** 2 < 10 ** 2:  # keep the view around the camera open
            continue
        if lantern_ok(x, z):
            lights.append((x, height[x, z], z))
    for (x, h, z) in lights:
        world[x, h + 1, z] = STONE_LANTERN
        world[x, h + 2, z] = LANTERN
        set_block(world, x, h + 3, z, STONE_LANTERN)  # little stone roof

    removed = remove_floating(world)

    canopy = (world == LEAVES).any(axis=1)
    shade = box_blur(canopy.astype(np.float32), 3)
    for x in range(W):
        for z in range(D):
            h = height[x, z]
            if world[x, h, z] == GRASS and world[x, h + 1, z] == AIR and shade[x, z] > 0.10 and rng.random() < 0.30 + 0.7 * shade[x, z]:
                world[x, h + 1, z] = PETALS
    light_pos = np.array([(x + 0.5, h + 2.5, z + 0.5) for (x, h, z) in lights], dtype=np.float64)
    cam_y = height[cx, cz] + 4.6
    print(f"{len(trees)} trees, {len(lights)} lanterns, removed {removed} floating blocks")
    return world, (cx + 0.5, cam_y, cz + 0.5), light_pos


# ------------------------------------------------------------------ renderer

@njit(cache=True, inline="always")
def hash3(a, b, c):
    h = (a * 374761393 + b * 668265263 + c * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


@njit(cache=True)
def sky(dx, dy, dz, mx, my, mz):
    t = max(0.0, dy)
    # night sky: violet-blue haze at the horizon, deep navy above
    k = (1 - t) ** 4
    r = 0.020 + 0.075 * k
    g = 0.030 + 0.060 * k
    b = 0.085 + 0.120 * k
    if dy < 0:
        return 0.05, 0.05, 0.10
    # stars
    sx, sy, sz = int(math.floor(dx * 420)), int(math.floor(dy * 420)), int(math.floor(dz * 420))
    s = hash3(sx + 1000, sy + 2000, sz + 3000)
    if s > 0.9965 and dy > 0.06:
        tw = (s - 0.9965) / 0.0035
        star = (0.6 + 1.8 * tw) * min(1.0, (dy - 0.06) * 6)
        r += star * 0.9
        g += star * 0.92
        b += star
    # moon + halo
    md = dx * mx + dy * my + dz * mz
    if md > 0:
        halo = md ** 300 * 0.35 + md ** 24 * 0.10
        r += halo * 0.75
        g += halo * 0.85
        b += halo * 1.0
        if md > 0.99955:
            spot = hash3(int(dx * 2200), int(dy * 2200), int(dz * 2200))  # craters
            v = 2.6 - 0.5 * (spot > 0.75)
            r, g, b = v * 0.95, v * 0.97, v
    return r, g, b


@njit(cache=True)
def texel(block, face, ix, iy, iz, tu, tv, near_pink):
    n = hash3(ix * 16 + tu, iy * 16 + tv, iz * 16 + face * 7)
    n2 = hash3(ix * 31 + tu * 3, iz * 17 + tv, iy + face)
    if block == GRASS:
        if face == 2:
            r, g, b = 0.30 + 0.08 * n, 0.55 + 0.10 * n, 0.25 + 0.06 * n
            if near_pink and n2 > 0.78:
                r, g, b = 0.98, 0.70 + 0.1 * n, 0.82
            return r, g, b, 1.0
        if face == 3:
            return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
        if tv >= 13 or (tv == 12 and n2 > 0.5):
            return 0.30 + 0.08 * n, 0.55 + 0.10 * n, 0.25 + 0.06 * n, 1.0
        return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
    if block == DIRT:
        return 0.47 + 0.08 * n, 0.33 + 0.06 * n, 0.22 + 0.05 * n, 1.0
    if block == STONE or block == STONE_LANTERN:
        v = 0.50 + 0.14 * n
        if block == STONE_LANTERN and (tu == 0 or tu == 15 or tv == 0 or tv == 15):
            v *= 0.75
        return v, v, v * 1.03, 1.0
    if block == LOG:
        if face == 2 or face == 3:
            ring = (abs(tu - 7.5) + abs(tv - 7.5)) % 4 < 1.6
            return (0.55, 0.36, 0.40, 1.0) if ring else (0.70, 0.48, 0.50, 1.0)
        stripe = (tu * 5 + int(n2 * 3)) % 7 < 2
        v = 0.24 + 0.06 * n
        return (v * 1.25, v * 0.85, v * 1.0, 1.0) if stripe else (v * 1.55, v * 1.05, v * 1.15, 1.0)
    if block == LEAVES:
        if n2 < 0.13:
            return 0.0, 0.0, 0.0, 0.0
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
        return 0.06, 0.12, 0.22, 0.75
    if block == LANTERN:
        # paper lantern: glowing panels with a dark frame
        if tu <= 1 or tu >= 14 or tv <= 1 or tv >= 14 or tu == 7 or tu == 8:
            return 0.18, 0.12, 0.10, 1.0
        return 1.0, 1.0, 1.0, 1.0
    return 1.0, 0.0, 1.0, 1.0


@njit(cache=True)
def block_at(world, x, y, z):
    if x < 0 or y < 0 or z < 0 or x >= world.shape[0] or y >= world.shape[1] or z >= world.shape[2]:
        return 0
    return world[x, y, z]


@njit(cache=True)
def occlusion(world, ox, oy, oz, dx, dy, dz, max_t, step):
    """1 = free, 0 = blocked; leaves let some light through."""
    t = step * 0.5
    vis = 1.0
    while t < max_t:
        x, y, z = ox + dx * t, oy + dy * t, oz + dz * t
        if y >= world.shape[1]:
            break
        b = block_at(world, int(math.floor(x)), int(math.floor(y)), int(math.floor(z)))
        if b == LEAVES:
            vis *= 0.78
        elif b != AIR and b != WATER and b != PETALS and b != LANTERN:
            return 0.0
        if vis < 0.2:
            return vis
        t += step
    return vis


@njit(cache=True)
def lantern_light(world, lights, px, py, pz, nx, ny, nz):
    r = g = b = 0.0
    for i in range(lights.shape[0]):
        lx, ly, lz = lights[i, 0] - px, lights[i, 1] - py, lights[i, 2] - pz
        d2 = lx * lx + ly * ly + lz * lz
        if d2 > 18.0 * 18.0:
            continue
        d = math.sqrt(d2) + 1e-6
        lx, ly, lz = lx / d, ly / d, lz / d
        ndl = nx * lx + ny * ly + nz * lz
        if ndl <= 0:
            continue
        vis = occlusion(world, px, py, pz, lx, ly, lz, d - 0.6, 0.3)
        if vis <= 0:
            continue
        att = 4.2 / (1.0 + 0.45 * d2)
        e = att * ndl * vis
        r += e * 1.00
        g += e * 0.55
        b += e * 0.22
    return r, g, b


@njit(cache=True)
def march(world, lights, ox, oy, oz, dx, dy, dz, mx, my, mz, pink_map, allow_reflect, out):
    """Returns color; when allow_reflect, out[0..6] receives the first water hit for a reflection ray:
    (weight, px, py, pz, rdx, rdy, rdz)."""
    out[0] = 0.0
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
    fog_r, fog_g, fog_b = 0.045, 0.050, 0.095
    max_t = 150.0
    in_water = False
    while t < max_t and trans > 0.02:
        if tmx < tmy and tmx < tmz:
            x += step_x; t = tmx; tmx += tdx; face = 0 if step_x < 0 else 1
        elif tmy < tmz:
            y += step_y; t = tmy; tmy += tdy; face = 2 if step_y < 0 else 3
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
        if b == PETALS and face != 2:
            continue
        tu = min(15, max(0, int(u * 16)))
        tv = min(15, max(0, int(v * 16)))
        cr, cg, cb, ca = texel(b, face, x, y, z, tu, tv, pink_map[min(max(x, 0), pink_map.shape[0] - 1), min(max(z, 0), pink_map.shape[1] - 1)] > 0.1)
        if ca == 0.0:
            continue
        nx = 1.0 if face == 1 else (-1.0 if face == 0 else 0.0)
        ny = 1.0 if face == 2 else (-1.0 if face == 3 else 0.0)
        nz = 1.0 if face == 5 else (-1.0 if face == 4 else 0.0)
        if b == LANTERN and cr > 0.9:
            # emissive paper: warm glow, brighter than 1 so bloom picks it up
            lr, lg, lb = 4.2, 2.6, 1.15
        else:
            px, py, pz = hx + nx * 0.02, hy + ny * 0.02, hz + nz * 0.02
            # moonlight (cool) with shadows
            ndl = max(0.0, nx * mx + ny * my + nz * mz)
            if b == LEAVES:
                ndl = 0.35 + 0.65 * ndl  # thin leaves are lit from both sides
            moon = 0.0
            if ndl > 0:
                moon = ndl * occlusion(world, px, py, pz, mx, my, mz, 60.0, 0.35)
            ar = 0.022 + 0.13 * moon
            ag = 0.026 + 0.15 * moon
            ab = 0.050 + 0.24 * moon
            wr, wg, wb = lantern_light(world, lights, px, py, pz, nx, ny, nz)
            lr = cr * (ar + wr)
            lg = cg * (ag + wg)
            lb = cb * (ab + wb)
        if b == WATER:
            # Fresnel reflection, traced by the caller
            cosi = abs(dy)
            fres = 0.06 + 0.94 * (1 - cosi) ** 5
            fres = min(0.85, fres + 0.25)
            if allow_reflect and face == 2:
                out[0] = trans * fres
                out[1], out[2], out[3] = hx, hy + 0.01, hz
                out[4], out[5], out[6] = dx, -dy, dz
            lr *= (1 - fres)
            lg *= (1 - fres)
            lb *= (1 - fres)
            in_water = True
        # ground mist: denser low over the terrain
        fog = 1.0 - math.exp(-((t / 70.0) ** 2))
        lr = lr * (1 - fog) + fog_r * fog
        lg = lg * (1 - fog) + fog_g * fog
        lb = lb * (1 - fog) + fog_b * fog
        r_acc += trans * ca * lr
        g_acc += trans * ca * lg
        b_acc += trans * ca * lb
        trans *= (1 - ca)
    if trans > 0.0:
        r, g, b = sky(dx, dy, dz, mx, my, mz)
        if t >= max_t:
            r, g, b = fog_r, fog_g, fog_b
        r_acc += trans * r
        g_acc += trans * g
        b_acc += trans * b
    return r_acc, g_acc, b_acc


@njit(cache=True)
def shade_pixel(world, lights, cam, dx, dy, dz, moon, pink_map):
    out = np.zeros(7)
    r, g, b = march(world, lights, cam[0], cam[1], cam[2], dx, dy, dz, moon[0], moon[1], moon[2], pink_map, True, out)
    if out[0] > 0:
        out2 = np.zeros(7)
        rr, rg, rb = march(world, lights, out[1], out[2], out[3], out[4], out[5], out[6], moon[0], moon[1], moon[2], pink_map, False, out2)
        r += out[0] * rr
        g += out[0] * rg
        b += out[0] * rb
    return r, g, b


@njit(parallel=True, cache=True)
def render_face(world, lights, cam, fwd, right, up, size, pad, moon, pink_map, ss):
    """Renders size+2*pad pixels; the inner size x size area is exactly the 90 degree face."""
    full = size + 2 * pad
    img = np.zeros((full, full, 3), dtype=np.float32)
    for py in prange(full):
        for px in range(full):
            r = g = b = 0.0
            for sy_ in range(ss):
                for sx_ in range(ss):
                    a = ((px - pad + (sx_ + 0.5) / ss) / size) * 2 - 1
                    c = 1 - ((py - pad + (sy_ + 0.5) / ss) / size) * 2
                    dx = fwd[0] + right[0] * a + up[0] * c
                    dy = fwd[1] + right[1] * a + up[1] * c
                    dz = fwd[2] + right[2] * a + up[2] * c
                    l = math.sqrt(dx * dx + dy * dy + dz * dz)
                    cr, cg, cb = shade_pixel(world, lights, cam, dx / l, dy / l, dz / l, moon, pink_map)
                    r += cr; g += cg; b += cb
            n = ss * ss
            img[py, px, 0] = r / n
            img[py, px, 1] = g / n
            img[py, px, 2] = b / n
    return img


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
    r = int(sigma * 3)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    out = img
    for axis in (0, 1):
        pad = [(0, 0)] * 3
        pad[axis] = (r, r)
        p = np.pad(out, pad, mode="edge")
        acc = np.zeros_like(out)
        for i, w in enumerate(k):
            sl = [slice(None)] * 3
            sl[axis] = slice(i, i + out.shape[axis])
            acc += w * p[tuple(sl)]
        out = acc
    return out


def post(img, size, pad):
    # bloom: bright parts glow (computed on the padded image -> no seams between faces)
    lum = img @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    bright = img * np.clip((lum - 0.75) / 1.5, 0, 1)[..., None]
    s = size / 1024
    bloom = 0.55 * gaussian_blur(bright, 6 * s) + 0.45 * gaussian_blur(bright, 22 * s) + 0.35 * gaussian_blur(bright, 60 * s)
    img = img + 0.9 * bloom
    img = img[pad:pad + size, pad:pad + size]
    # filmic (ACES approximation) + slight lift of the shadows
    img = img * 1.45
    img = (img * (2.51 * img + 0.03)) / (img * (2.43 * img + 0.59) + 0.14)
    img = np.clip(img, 0, 1) ** (1 / 1.12)
    return (img * 255 + 0.5).astype(np.uint8)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "panorama"
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    ss = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    os.makedirs(out, exist_ok=True)
    world, cam, lights = build_world()
    pink = (world == PETALS).any(axis=1).astype(np.float32)
    moon = np.array([-0.30, 0.48, -0.82])
    moon /= np.linalg.norm(moon)
    pad = size // 8
    for i, (f, r, u) in enumerate(FACES):
        img = render_face(world, lights, np.array(cam), np.array(f, float), np.array(r, float), np.array(u, float), size, pad, moon, pink, ss)
        Image.fromarray(post(img, size, pad)).save(os.path.join(out, f"panorama_{i}.png"), optimize=True)
        print("wrote", f"panorama_{i}.png")


if __name__ == "__main__":
    main()
