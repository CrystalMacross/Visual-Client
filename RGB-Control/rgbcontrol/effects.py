"""Software-Effekte.

Jeder Effekt ist eine reine Funktion: (Zeit, LED-Position, Parameter) -> Farbe.
Die LED-Position ist über ALLE ausgewählten Geräte durchnummeriert, damit
z.B. eine Regenbogenwelle synchron über Lüfter, RAM und Grafikkarte läuft.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from .colors import RGB, blend, hue, scale


@dataclass
class EffectParams:
    color: RGB = (0, 120, 255)
    color2: RGB = (255, 0, 160)
    speed: float = 1.0        # 0.1 .. 5.0
    brightness: float = 1.0   # 0.0 .. 1.0
    extra: dict = field(default_factory=dict)


def _static(t, pos, total, p):
    return p.color


def _breathing(t, pos, total, p):
    k = (math.sin(t * p.speed * 2.0) + 1) / 2
    return scale(p.color, 0.05 + 0.95 * k * k)


def _rainbow(t, pos, total, p):
    return hue(t * 0.1 * p.speed)


def _rainbow_wave(t, pos, total, p):
    return hue(t * 0.15 * p.speed + pos / max(total, 1))


def _color_cycle(t, pos, total, p):
    k = (math.sin(t * p.speed) + 1) / 2
    return blend(p.color, p.color2, k)


def _gradient(t, pos, total, p):
    return blend(p.color, p.color2, pos / max(total - 1, 1))


def _blink(t, pos, total, p):
    return p.color if int(t * p.speed * 2) % 2 == 0 else (0, 0, 0)


def _comet(t, pos, total, p):
    total = max(total, 1)
    head = (t * p.speed * 12) % total
    dist = (head - pos) % total
    tail = max(4, total // 6)
    if dist > tail:
        return scale(p.color2, 0.08)
    return blend(scale(p.color2, 0.08), p.color, 1 - dist / tail)


def _wave(t, pos, total, p):
    k = (math.sin(t * p.speed * 3 - pos * 0.4) + 1) / 2
    return blend(p.color2, p.color, k)


def _off(t, pos, total, p):
    return (0, 0, 0)


@dataclass(frozen=True)
class Effect:
    key: str
    name: str
    func: object
    animated: bool
    uses_color: bool = True
    uses_color2: bool = False


EFFECTS: list[Effect] = [
    Effect("static", "Statisch", _static, False),
    Effect("breathing", "Atmen", _breathing, True),
    Effect("rainbow", "Regenbogen (alle gleich)", _rainbow, True, uses_color=False),
    Effect("rainbow_wave", "Regenbogen-Welle", _rainbow_wave, True, uses_color=False),
    Effect("color_cycle", "Farbwechsel (2 Farben)", _color_cycle, True, uses_color2=True),
    Effect("gradient", "Farbverlauf", _gradient, False, uses_color2=True),
    Effect("wave", "Welle (2 Farben)", _wave, True, uses_color2=True),
    Effect("comet", "Komet / Lauflicht", _comet, True, uses_color2=True),
    Effect("blink", "Blinken", _blink, True),
    Effect("off", "Aus", _off, False, uses_color=False),
]
EFFECTS_BY_KEY = {e.key: e for e in EFFECTS}


def render(effect_key: str, t: float, total: int, params: EffectParams) -> list[RGB]:
    """Berechnet die Farben für `total` LEDs zum Zeitpunkt t (Sekunden)."""
    eff = EFFECTS_BY_KEY.get(effect_key, EFFECTS_BY_KEY["static"])
    b = max(0.0, min(1.0, params.brightness))
    return [scale(eff.func(t, i, total, params), b) for i in range(total)]
