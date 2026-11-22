"""Hardware-Anbindung über das OpenRGB-SDK.

OpenRGB enthält die eigentlichen Treiber für hunderte Geräte (Mainboards,
RAM, Grafikkarten, Lüfter-Controller, AIOs, Tastaturen, Mäuse ...). Diese
App startet OpenRGB unsichtbar als Dienst und steuert es über das SDK.
"""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field

from .colors import RGB

log = logging.getLogger(__name__)

DEVICE_TYPE_DE = {
    "MOTHERBOARD": "Mainboard", "DRAM": "Arbeitsspeicher (RAM)", "GPU": "Grafikkarte",
    "COOLER": "Kühler / Lüfter", "LEDSTRIP": "LED-Streifen", "KEYBOARD": "Tastatur",
    "MOUSE": "Maus", "MOUSEMAT": "Mauspad", "HEADSET": "Headset",
    "HEADSET_STAND": "Headset-Ständer", "GAMEPAD": "Gamepad", "LIGHT": "Leuchte",
    "SPEAKER": "Lautsprecher", "VIRTUAL": "Virtuell", "STORAGE": "Speicher (SSD)",
    "CASE": "Gehäuse", "MICROPHONE": "Mikrofon", "ACCESSORY": "Zubehör",
    "KEYPAD": "Keypad", "UNKNOWN": "Unbekannt",
}

DEVICE_TYPE_ICON = {
    "MOTHERBOARD": "\U0001F5A5", "DRAM": "\U0001F9E0", "GPU": "\U0001F3AE", "COOLER": "\U0001F300",
    "LEDSTRIP": "\U0001F4A1", "KEYBOARD": "⌨", "MOUSE": "\U0001F5B1", "MOUSEMAT": "▭",
    "HEADSET": "\U0001F3A7", "CASE": "\U0001F4E6", "LIGHT": "\U0001F4A1",
}


@dataclass
class LightZone:
    index: int
    name: str
    led_count: int
    leds_min: int = 0
    leds_max: int = 0

    @property
    def resizable(self) -> bool:
        return self.leds_max > self.leds_min


@dataclass
class LightDevice:
    index: int
    name: str
    type_key: str
    vendor: str = ""
    description: str = ""
    location: str = ""
    serial: str = ""
    zones: list[LightZone] = field(default_factory=list)
    modes: list[str] = field(default_factory=list)
    active_mode: int = 0
    per_led: bool = True     # hat einen Modus mit Einzel-LED-Steuerung ("Direct")

    @property
    def type_name(self) -> str:
        return DEVICE_TYPE_DE.get(self.type_key, self.type_key.title())

    @property
    def icon(self) -> str:
        return DEVICE_TYPE_ICON.get(self.type_key, "✨")

    @property
    def led_count(self) -> int:
        return sum(z.led_count for z in self.zones)

    @property
    def key(self) -> str:
        """Stabiler Schlüssel zum Wiederfinden in Profilen."""
        return f"{self.name}|{self.location}"


class BackendError(Exception):
    """Schwerer Fehler: Verbindung zum Hardware-Dienst ist weg."""


class DeviceError(BackendError):
    """Fehler bei einem einzelnen Gerät - die anderen laufen weiter."""


class Backend:
    name = "Backend"

    def connect(self) -> None: ...
    def disconnect(self) -> None: ...
    @property
    def connected(self) -> bool: return False
    def list_devices(self) -> list[LightDevice]: return []
    def rescan(self) -> list[LightDevice]: return self.list_devices()
    def prepare_direct(self, dev: int) -> None: ...
    def set_zone_colors(self, dev: int, zone: int, colors: list[RGB]) -> None: ...
    def set_hardware_mode(self, dev: int, mode_name: str, color: RGB | None = None) -> None: ...
    def save_to_device(self, dev: int) -> None: ...
    def resize_zone(self, dev: int, zone: int, size: int) -> None: ...
    def info(self) -> dict: return {}


PER_LED = 1        # utils.ModeColors.PER_LED
MODE_SPECIFIC = 2  # utils.ModeColors.MODE_SPECIFIC


def choose_mode(modes) -> tuple[int | None, bool]:
    """Wählt den besten Modus zum Steuern durch die App.

    -> (index, per_led). Bevorzugt "Direct" (Einzel-LED, ohne Flash-Schreiben),
    sonst irgendeinen Einzel-LED-Modus, sonst "Static" mit Modusfarbe.
    """
    names = [str(m.name).lower() for m in modes]
    if "direct" in names:
        return names.index("direct"), True
    for i, m in enumerate(modes):
        if int(getattr(m, "color_mode", 0)) == PER_LED:
            return i, True
    for wanted in ("static", "custom"):
        if wanted in names:
            i = names.index(wanted)
            return i, int(getattr(modes[i], "color_mode", 0)) == PER_LED
    for i, m in enumerate(modes):
        if int(getattr(m, "color_mode", 0)) == MODE_SPECIFIC:
            return i, False
    return None, False


def average(colors: list[RGB]) -> RGB:
    if not colors:
        return (0, 0, 0)
    n = len(colors)
    return (sum(c[0] for c in colors) // n, sum(c[1] for c in colors) // n, sum(c[2] for c in colors) // n)


class OpenRGBBackend(Backend):
    name = "OpenRGB"

    def __init__(self, host: str = "127.0.0.1", port: int = 6742):
        self.host, self.port = host, port
        self._client = None
        self._lock = threading.RLock()
        self._per_led: dict[int, bool] = {}
        self._mode_idx: dict[int, int] = {}
        self._last_mode_color: dict[int, tuple[RGB, float]] = {}

    @property
    def connected(self) -> bool:
        return self._client is not None

    def info(self) -> dict:
        c = self._client
        if c is None:
            return {"connected": False}
        try:
            proto = c.comms._protocol_version
        except Exception:
            proto = "?"
        return {"connected": True, "host": self.host, "port": self.port, "protocol": proto,
                "devices": len(c.devices)}

    def connect(self) -> None:
        try:
            from openrgb import OpenRGBClient
        except ImportError as e:  # pragma: no cover
            raise BackendError("Python-Paket 'openrgb-python' fehlt.") from e
        with self._lock:
            try:
                self._client = OpenRGBClient(self.host, self.port, name="RGB Control")
                log.info("Mit OpenRGB verbunden (%s:%s), Protokoll %s, %d Geräte", self.host, self.port,
                         self._client.comms._protocol_version, len(self._client.devices))
            except Exception as e:
                self._client = None
                raise BackendError(f"Keine Verbindung zum OpenRGB-Dienst ({self.host}:{self.port}): {e}") from e
            self._per_led.clear()
            self._mode_idx.clear()

    def disconnect(self) -> None:
        with self._lock:
            if self._client is not None:
                try:
                    self._client.disconnect()
                except Exception:
                    pass
            self._client = None

    def _dev(self, i: int):
        if self._client is None:
            raise BackendError("Nicht verbunden")
        try:
            return self._client.devices[i]
        except IndexError as e:
            raise DeviceError(f"Gerät {i} existiert nicht mehr") from e

    def _guard(self, fn, *a):
        from openrgb.utils import OpenRGBDisconnected
        with self._lock:
            try:
                return fn(*a)
            except BackendError:
                raise
            except (OpenRGBDisconnected, ConnectionError, BrokenPipeError, TimeoutError) as e:
                self._client = None
                log.error("Verbindung zu OpenRGB verloren: %r", e)
                raise BackendError(f"Verbindung zu OpenRGB verloren: {e}") from e
            except OSError as e:
                self._client = None
                log.error("Socket-Fehler: %r", e)
                raise BackendError(f"Verbindung zu OpenRGB verloren: {e}") from e
            except Exception as e:
                log.warning("Gerätefehler: %r", e)
                raise DeviceError(str(e) or e.__class__.__name__) from e

    def list_devices(self) -> list[LightDevice]:
        def run():
            if self._client is None:
                return []
            out = []
            for i, d in enumerate(self._client.devices):
                if d is None:
                    continue
                zones = []
                for zi, z in enumerate(d.zones):
                    zd = d.data.zones[zi] if hasattr(d, "data") else None
                    zones.append(LightZone(zi, z.name, len(z.leds),
                                           getattr(zd, "leds_min", 0), getattr(zd, "leds_max", 0)))
                md = d.metadata
                _idx, per_led = choose_mode(d.modes)
                out.append(LightDevice(
                    index=i, name=d.name, type_key=getattr(d.type, "name", str(d.type)),
                    vendor=getattr(md, "vendor", ""), description=getattr(md, "description", ""),
                    location=getattr(md, "location", ""), serial=getattr(md, "serial", ""),
                    zones=zones, modes=[m.name for m in d.modes], active_mode=d.active_mode, per_led=per_led))
            return out
        return self._guard(run)

    def rescan(self) -> list[LightDevice]:
        def run():
            if self._client is None:
                raise BackendError("Nicht verbunden")
            self._client.update()  # Geräteliste vom OpenRGB-Dienst neu laden
            self._per_led.clear()
            self._mode_idx.clear()
        self._guard(run)
        return self.list_devices()

    def _send_mode(self, d, mode) -> None:
        """Modus senden, ohne auf Bestätigung zu warten (schnell, für Effekte)."""
        from openrgb.utils import PacketType
        data = mode.pack(d.comms._protocol_version)
        d.comms.send_header(d.id, PacketType.RGBCONTROLLER_UPDATEMODE, len(data))
        d.comms.send_data(data)

    def prepare_direct(self, dev: int) -> None:
        def run():
            d = self._dev(dev)
            idx, per_led = choose_mode(d.modes)
            if idx is None:
                d.set_custom_mode()
                per_led = True
                idx = d.active_mode
            else:
                self._send_mode(d, d.modes[idx])
                d.active_mode = idx
            self._per_led[dev] = per_led
            self._mode_idx[dev] = idx
            log.info("Gerät %d (%s): Modus '%s', Einzel-LED=%s", dev, d.name, d.modes[idx].name, per_led)
        self._guard(run)

    def set_zone_colors(self, dev: int, zone: int, colors: list[RGB]) -> None:
        from openrgb.utils import RGBColor

        def run():
            d = self._dev(dev)
            z = d.zones[zone]
            n = len(z.leds)
            if not colors or not n:
                return
            if self._per_led.get(dev, True):
                cols = list(colors[:n]) + [colors[-1]] * max(0, n - len(colors))
                z.set_colors([RGBColor(*c) for c in cols], fast=True)
                return
            # Gerät kann keine Einzel-LEDs: Modusfarbe setzen (Durchschnitt, max. 8x pro Sekunde)
            c = average(list(colors))
            last = self._last_mode_color.get(dev)
            now = time.monotonic()
            if last and (last[0] == c or now - last[1] < 0.12):
                return
            mode = d.modes[self._mode_idx.get(dev, d.active_mode)]
            if getattr(mode, "colors", None):
                mode.colors = [RGBColor(*c) for _ in mode.colors]
                self._send_mode(d, mode)
            self._last_mode_color[dev] = (c, now)
        self._guard(run)

    def set_hardware_mode(self, dev: int, mode_name: str, color: RGB | None = None) -> None:
        from openrgb.utils import RGBColor

        def run():
            d = self._dev(dev)
            idx = [m.name for m in d.modes].index(mode_name)
            mode = d.modes[idx]
            if color is not None and getattr(mode, "colors", None):
                mode.colors = [RGBColor(*color) for _ in mode.colors]
            d.set_mode(mode, force=True)
            if color is not None and int(getattr(mode, "color_mode", 0)) == PER_LED:
                d.set_color(RGBColor(*color), fast=True)
            self._per_led.pop(dev, None)
        self._guard(run)

    def save_to_device(self, dev: int) -> None:
        self._guard(lambda: self._dev(dev).save_mode())

    def resize_zone(self, dev: int, zone: int, size: int) -> None:
        def run():
            self._dev(dev).zones[zone].resize(size)
            self._client.update()
        self._guard(run)
