"""Effekt-Engine: berechnet in einem Hintergrund-Thread die Farben für
mehrere Gruppen (eine pro Kategorie) und schickt sie an die Geräte."""
from __future__ import annotations

import copy
import threading
import time
from dataclasses import dataclass

from .backend import Backend, BackendError, LightDevice
from .colors import RGB
from .effects import EFFECTS_BY_KEY, EffectParams, render


@dataclass(frozen=True)
class Target:
    device: int
    zone: int
    led_count: int


@dataclass
class Group:
    targets: list[Target]
    effect: str
    params: EffectParams


def build_targets(devices: list[LightDevice], selected) -> list[Target]:
    selected = set(selected)
    out = []
    for d in devices:
        for z in d.zones:
            if (d.index, z.index) in selected and z.led_count > 0:
                out.append(Target(d.index, z.index, z.led_count))
    return out


class EffectEngine:
    FPS = 30

    def __init__(self, backend: Backend, on_error=None):
        self.backend = backend
        self.on_error = on_error
        self._lock = threading.Lock()
        self._groups: dict[str, Group] = {}
        self._dirty = False
        self._prepared: set[int] = set()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.preview: dict[tuple[int, int], list[RGB]] = {}
        self._t0 = time.monotonic()

    # -- Steuerung -------------------------------------------------------
    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="EffectEngine", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
        self._thread = None

    def set_backend(self, backend: Backend) -> None:
        with self._lock:
            self.backend = backend
            self._prepared.clear()
            self._dirty = True

    def set_group(self, key: str, targets: list[Target], effect: str, params: EffectParams) -> None:
        with self._lock:
            # Eine Zone darf nur von einer Gruppe gesteuert werden
            taken = {(t.device, t.zone) for t in targets}
            for k, g in self._groups.items():
                if k != key:
                    g.targets = [t for t in g.targets if (t.device, t.zone) not in taken]
            self._groups[key] = Group(list(targets), effect, copy.deepcopy(params))
            self._dirty = True

    def remove_group(self, key: str) -> None:
        with self._lock:
            self._groups.pop(key, None)
            self._dirty = True

    def clear(self) -> None:
        with self._lock:
            self._groups.clear()
            self._dirty = True

    def release(self, devices: set[int] | None = None) -> None:
        with self._lock:
            if devices is None:
                self._prepared.clear()
            else:
                self._prepared -= devices

    def remove_devices(self, devices: set[int]) -> None:
        with self._lock:
            for g in self._groups.values():
                g.targets = [t for t in g.targets if t.device not in devices]
            self._prepared -= devices
            self._dirty = True

    def groups(self) -> dict[str, Group]:
        with self._lock:
            return copy.deepcopy(self._groups)

    # -- Berechnung ------------------------------------------------------
    @staticmethod
    def frame(t: float, groups: dict[str, Group]) -> dict[tuple[int, int], list[RGB]]:
        out = {}
        for g in groups.values():
            total = sum(tg.led_count for tg in g.targets)
            if not total:
                continue
            colors = render(g.effect, t, total, g.params)
            pos = 0
            for tg in g.targets:
                out[(tg.device, tg.zone)] = colors[pos:pos + tg.led_count]
                pos += tg.led_count
        return out

    def _run(self) -> None:
        last_push = 0.0
        while not self._stop.is_set():
            start = time.monotonic()
            with self._lock:
                groups = copy.deepcopy(self._groups)
                dirty, self._dirty = self._dirty, False
                backend = self.backend
            animated = any(EFFECTS_BY_KEY.get(g.effect, EFFECTS_BY_KEY["static"]).animated
                           for g in groups.values() if g.targets)
            has_targets = any(g.targets for g in groups.values())
            push = animated or dirty or (has_targets and start - last_push > 10)
            if has_targets and push:
                frame = self.frame(start - self._t0, groups)
                self.preview = frame
                try:
                    for dev in {d for d, _ in frame} - self._prepared:
                        backend.prepare_direct(dev)
                        self._prepared.add(dev)
                    for (dev, zone), cols in frame.items():
                        backend.set_zone_colors(dev, zone, cols)
                    last_push = start
                except BackendError as e:
                    with self._lock:
                        self._prepared.clear()
                    if self.on_error:
                        self.on_error(str(e))
                    self._stop.wait(2)
            elif not has_targets:
                self.preview = {}
            delay = (1 / self.FPS) if animated else 0.1
            self._stop.wait(max(0.0, delay - (time.monotonic() - start)))
