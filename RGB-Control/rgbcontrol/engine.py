"""Effekt-Engine: berechnet in einem Hintergrund-Thread die Farben und
schickt sie an die ausgewählten Geräte/Zonen."""
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


def build_targets(devices: list[LightDevice], selected: set[tuple[int, int]]) -> list[Target]:
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
        self._targets: list[Target] = []
        self._effect = "static"
        self._params = EffectParams()
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

    def apply(self, targets: list[Target], effect: str, params: EffectParams) -> None:
        with self._lock:
            self._targets = list(targets)
            self._effect = effect
            self._params = copy.deepcopy(params)
            self._dirty = True

    def release(self, devices: set[int] | None = None) -> None:
        """Vergisst Geräte, damit sie erneut in den Direkt-Modus gesetzt werden."""
        with self._lock:
            if devices is None:
                self._prepared.clear()
            else:
                self._prepared -= devices

    def remove_devices(self, devices: set[int]) -> None:
        """Entfernt Geräte aus der Software-Steuerung (z.B. für Hardware-Modus)."""
        with self._lock:
            self._targets = [t for t in self._targets if t.device not in devices]
            self._prepared -= devices
            self._dirty = True

    @property
    def state(self) -> tuple[list[Target], str, EffectParams]:
        with self._lock:
            return list(self._targets), self._effect, copy.deepcopy(self._params)

    # -- Thread ----------------------------------------------------------
    def frame(self, t: float, targets: list[Target], effect: str, params: EffectParams):
        total = sum(tg.led_count for tg in targets)
        colors = render(effect, t, total, params)
        out, pos = {}, 0
        for tg in targets:
            out[(tg.device, tg.zone)] = colors[pos:pos + tg.led_count]
            pos += tg.led_count
        return out

    def _run(self) -> None:
        last_static_push = 0.0
        while not self._stop.is_set():
            start = time.monotonic()
            with self._lock:
                targets, effect, params = self._targets, self._effect, self._params
                dirty, self._dirty = self._dirty, False
                backend = self.backend
            animated = EFFECTS_BY_KEY.get(effect, EFFECTS_BY_KEY["static"]).animated
            # statische Effekte nur bei Aenderung (und alle 10 s zur Sicherheit) senden
            push = animated or dirty or (targets and start - last_static_push > 10)
            if targets and push:
                frame = self.frame(start - self._t0, targets, effect, params)
                self.preview = frame
                try:
                    for dev in {t.device for t in targets} - self._prepared:
                        backend.prepare_direct(dev)
                        self._prepared.add(dev)
                    for (dev, zone), cols in frame.items():
                        backend.set_zone_colors(dev, zone, cols)
                    last_static_push = start
                except BackendError as e:
                    with self._lock:
                        self._targets = []
                        self._prepared.clear()
                    if self.on_error:
                        self.on_error(str(e))
            elif not targets:
                self.preview = {}
            delay = (1 / self.FPS) if animated else 0.1
            self._stop.wait(max(0.0, delay - (time.monotonic() - start)))
