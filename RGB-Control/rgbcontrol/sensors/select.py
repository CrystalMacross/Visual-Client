"""Wählt aus allen Sensorwerten die richtigen aus (reine Funktionen, testbar)."""
from __future__ import annotations

import re

from .model import FanReading, Reading

_CPU_PRIORITY = ["core (tctl/tdie)", "cpu package", "tdie", "core (tctl)", "package", "cpu", "core average",
                 "core max", "ccd1 (tdie)", "ccd 1"]
_GPU_PRIORITY = ["gpu core", "gpu", "gpu hot spot", "gpu memory junction"]
_LIQUID = re.compile(r"liquid|coolant|water|wasser|fluid|flüssig|pump|aio|kühlmittel", re.I)
_PUMP_FAN = re.compile(r"pump|pumpe|aio|w_pump|wpump", re.I)


def _valid(r: Reading) -> bool:
    return r.value is not None and r.value == r.value  # nicht NaN


def _prio_pick(cands: list[Reading], priority: list[str]) -> Reading | None:
    cands = [c for c in cands if _valid(c)]
    if not cands:
        return None
    low = {c: c.name.lower() for c in cands}
    for p in priority:
        for c in cands:
            if low[c] == p:
                return c
    for p in priority:
        for c in cands:
            if p in low[c]:
                return c
    return cands[0]


def pick_cpu_temp(readings: list[Reading]) -> Reading | None:
    temps = [r for r in readings if r.hw_type == "Cpu" and r.sensor_type == "Temperature"
             and 5 < (r.value or 0) < 130 and "distance" not in r.name.lower()]
    return _prio_pick(temps, _CPU_PRIORITY)


def _gpu_rank(hw_type: str) -> int:
    return {"GpuNvidia": 0, "GpuAmd": 1, "GpuIntel": 2}.get(hw_type, 3)


def pick_gpu(readings: list[Reading]) -> tuple[Reading | None, Reading | None, float | None, float | None]:
    """-> (temperatur, last, vram_benutzt_mb, vram_gesamt_mb) der stärksten Grafikkarte."""
    gpus = sorted({r.hw_name: r.hw_type for r in readings if r.hw_type.startswith("Gpu")}.items(),
                  key=lambda kv: _gpu_rank(kv[1]))
    for name, _ in gpus:
        mine = [r for r in readings if r.hw_name == name]
        temp = _prio_pick([r for r in mine if r.sensor_type == "Temperature" and 5 < (r.value or 0) < 130],
                          _GPU_PRIORITY)
        load = _prio_pick([r for r in mine if r.sensor_type == "Load"], ["gpu core", "d3d 3d", "gpu"])
        used = next((r.value for r in mine if r.sensor_type == "SmallData" and _valid(r)
                     and r.name.lower() in ("gpu memory used", "d3d dedicated memory used")), None)
        total = next((r.value for r in mine if r.sensor_type == "SmallData" and _valid(r)
                      and r.name.lower() == "gpu memory total"), None)
        if temp or load:
            return temp, load, used, total
    return None, None, None, None


def pick_pump(readings: list[Reading]) -> tuple[Reading | None, Reading | None]:
    """-> (Flüssigkeitstemperatur, Pumpendrehzahl)."""
    temps = [r for r in readings if r.sensor_type == "Temperature" and _LIQUID.search(r.name)
             and 5 < (r.value or 0) < 90 and _valid(r)]
    temps.sort(key=lambda r: 0 if r.hw_type == "Cooler" else 1)
    rpms = [r for r in readings if r.sensor_type == "Fan" and _PUMP_FAN.search(r.name) and _valid(r)
            and (r.value or 0) > 0]
    rpms.sort(key=lambda r: 0 if r.hw_type == "Cooler" else 1)
    if not rpms:  # AIO-Controller ohne "Pump" im Namen: erster Fan-Sensor des Coolers
        rpms = [r for r in readings if r.hw_type == "Cooler" and r.sensor_type == "Fan" and _valid(r)
                and (r.value or 0) > 0 and "fan" not in r.name.lower()]
    return (temps[0] if temps else None), (rpms[0] if rpms else None)


def pick_fans(readings: list[Reading]) -> list[FanReading]:
    out = []
    for r in readings:
        if r.sensor_type == "Fan" and _valid(r) and (r.value or 0) > 0 and not _PUMP_FAN.search(r.name):
            out.append(FanReading(r.name if r.hw_type != "Cooler" else f"{r.hw_name} {r.name}", float(r.value)))
    return out
