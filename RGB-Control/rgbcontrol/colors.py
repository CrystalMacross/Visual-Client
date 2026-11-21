"""Farb-Hilfsfunktionen (ohne Abhängigkeiten, gut testbar)."""
from __future__ import annotations

import colorsys

RGB = tuple[int, int, int]


def clamp(v: float, lo: int = 0, hi: int = 255) -> int:
    return int(max(lo, min(hi, round(v))))


def hex_to_rgb(value: str) -> RGB:
    value = value.strip().lstrip("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    if len(value) != 6:
        raise ValueError(f"Ungültige Farbe: {value!r}")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def rgb_to_hex(rgb: RGB) -> str:
    return "#{:02X}{:02X}{:02X}".format(*(clamp(c) for c in rgb))


def scale(rgb: RGB, factor: float) -> RGB:
    return clamp(rgb[0] * factor), clamp(rgb[1] * factor), clamp(rgb[2] * factor)


def blend(a: RGB, b: RGB, t: float) -> RGB:
    t = max(0.0, min(1.0, t))
    return (clamp(a[0] + (b[0] - a[0]) * t),
            clamp(a[1] + (b[1] - a[1]) * t),
            clamp(a[2] + (b[2] - a[2]) * t))


def hue(h: float, s: float = 1.0, v: float = 1.0) -> RGB:
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return clamp(r * 255), clamp(g * 255), clamp(b * 255)


PRESETS: list[tuple[str, RGB]] = [
    ("Rot", (255, 0, 0)), ("Orange", (255, 100, 0)), ("Gelb", (255, 220, 0)),
    ("Grün", (0, 255, 0)), ("Türkis", (0, 255, 180)), ("Cyan", (0, 200, 255)),
    ("Blau", (0, 0, 255)), ("Lila", (140, 0, 255)), ("Pink", (255, 0, 160)),
    ("Weiß", (255, 255, 255)), ("Warmweiß", (255, 170, 80)), ("Aus", (0, 0, 0)),
]
