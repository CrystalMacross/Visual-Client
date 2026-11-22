"""Ordnet jede LED-Zone einer der Kategorien zu.

Die Zuordnung passiert pro ZONE, weil manche Controller (z. B. Corsair
Commander Core, NZXT Kraken) Pumpe UND Lüfter in einem Gerät steuern.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .backend import LightDevice


@dataclass(frozen=True)
class Category:
    key: str
    name: str
    icon: str          # Name des Icons in ui/icons.py
    accent: str        # Akzentfarbe der Kategorie


CATEGORIES: list[Category] = [
    Category("gpu", "GPU", "gpu", "#7C5CFF"),
    Category("ram", "RAM", "ram", "#00C2FF"),
    Category("mouse", "Mouse", "mouse", "#FF4D9D"),
    Category("keyboard", "Keyboard", "keyboard", "#FFB020"),
    Category("fans", "Fans", "fan", "#2EE6A6"),
    Category("pump", "Water Pump", "pump", "#3D8BFF"),
]
OTHER = Category("other", "Sonstige", "spark", "#9AA0B4")
BY_KEY = {c.key: c for c in CATEGORIES + [OTHER]}

_PUMP_ZONE = re.compile(r"pump|pumpe|aio|block|kopf|head|cpu ?cooler|ring", re.I)
_FAN_ZONE = re.compile(r"\bfan|lüfter|luefter|luefter|argb|rgb header|addressable|header|channel|kanal|strip", re.I)
_PUMP_DEVICE = re.compile(
    r"pump|aio|kraken|capellix|h1[0-5]0i|h60i|h80i|h100|h115|h150|elite lcd|liquid|hydro|ryujin|ryuo|"
    r"galahad|ls7[0-9]0|ls5[0-9]0|lt7|ml2[0-9]0|ml3[0-9]0|masterliquid|castle|lcd|z[567]3|x[567]3|"
    r"floe|water|wasser|cooler master pl|ek-|ekwb|d5|ddc|reservoir", re.I)

_TYPE_MAP = {
    "GPU": "gpu", "DRAM": "ram", "MOUSE": "mouse", "MOUSEMAT": "mouse", "KEYBOARD": "keyboard",
    "KEYPAD": "keyboard", "COOLER": "fans", "LEDSTRIP": "fans", "MOTHERBOARD": "fans", "CASE": "fans",
    "LIGHT": "fans",
}


def classify_zone(dev: LightDevice, zone_name: str) -> str:
    t = dev.type_key
    if t in ("GPU", "DRAM", "MOUSE", "MOUSEMAT", "KEYBOARD", "KEYPAD"):
        return _TYPE_MAP[t]
    if t == "COOLER":
        if _PUMP_ZONE.search(zone_name):
            return "pump"
        if _FAN_ZONE.search(zone_name):
            return "fans"
        return "pump" if _PUMP_DEVICE.search(dev.name) else "fans"
    if t in ("LEDSTRIP", "MOTHERBOARD", "CASE", "LIGHT"):
        if _PUMP_ZONE.search(zone_name) and re.search(r"pump|pumpe|aio", zone_name, re.I):
            return "pump"
        return "fans"
    return "other"


def group_zones(devices: list[LightDevice]) -> dict[str, list[tuple[int, int]]]:
    """Liefert {kategorie: [(device_index, zone_index), ...]} für alle Kategorien."""
    out: dict[str, list[tuple[int, int]]] = {c.key: [] for c in CATEGORIES + [OTHER]}
    for d in devices:
        for z in d.zones:
            out[classify_zone(d, z.name)].append((d.index, z.index))
    return out
