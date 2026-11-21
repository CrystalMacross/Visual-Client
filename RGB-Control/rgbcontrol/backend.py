"""Geräte-Backends.

* OpenRGBBackend: spricht über das offene OpenRGB-SDK-Protokoll mit dem
  OpenRGB-Dienst. OpenRGB enthält die eigentlichen Hardware-Treiber für
  hunderte Geräte (Mainboards, RAM, Grafikkarten, Lüfter-Controller,
  Tastaturen, Mäuse, LED-Streifen ...).
* DemoBackend: simulierte Geräte, damit die Oberfläche auch ohne
  Hardware ausprobiert werden kann.
"""
from __future__ import annotations

import threading
from dataclasses import dataclass, field

from .colors import RGB

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
    pass


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


class OpenRGBBackend(Backend):
    name = "OpenRGB"

    def __init__(self, host: str = "127.0.0.1", port: int = 6742):
        self.host, self.port = host, port
        self._client = None
        self._lock = threading.RLock()

    @property
    def connected(self) -> bool:
        return self._client is not None

    def connect(self) -> None:
        try:
            from openrgb import OpenRGBClient
        except ImportError as e:  # pragma: no cover
            raise BackendError("Python-Paket 'openrgb-python' fehlt (pip install openrgb-python).") from e
        with self._lock:
            try:
                self._client = OpenRGBClient(self.host, self.port, name="RGB Control")
            except Exception as e:
                self._client = None
                raise BackendError(f"Keine Verbindung zum OpenRGB-Dienst ({self.host}:{self.port}): {e}") from e

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
        return self._client.devices[i]

    def _guard(self, fn, *a):
        with self._lock:
            try:
                return fn(*a)
            except BackendError:
                raise
            except (ConnectionError, OSError, BrokenPipeError) as e:
                self._client = None
                raise BackendError(f"Verbindung zu OpenRGB verloren: {e}") from e
            except Exception as e:
                raise BackendError(str(e)) from e

    def list_devices(self) -> list[LightDevice]:
        def run():
            if self._client is None:
                return []
            out = []
            for i, d in enumerate(self._client.devices):
                zones = []
                for zi, z in enumerate(d.zones):
                    zd = d.data.zones[zi] if hasattr(d, "data") else None
                    zones.append(LightZone(zi, z.name, len(z.leds),
                                           getattr(zd, "leds_min", 0), getattr(zd, "leds_max", 0)))
                md = d.metadata
                out.append(LightDevice(
                    index=i, name=d.name, type_key=getattr(d.type, "name", str(d.type)),
                    vendor=getattr(md, "vendor", ""), description=getattr(md, "description", ""),
                    location=getattr(md, "location", ""), serial=getattr(md, "serial", ""),
                    zones=zones, modes=[m.name for m in d.modes], active_mode=d.active_mode))
            return out
        return self._guard(run)

    def rescan(self) -> list[LightDevice]:
        def run():
            if self._client is None:
                raise BackendError("Nicht verbunden")
            self._client.update()  # Geräteliste vom OpenRGB-Dienst neu laden
        self._guard(run)
        return self.list_devices()

    def prepare_direct(self, dev: int) -> None:
        def run():
            d = self._dev(dev)
            names = [m.name.lower() for m in d.modes]
            for wanted in ("direct", "custom", "static"):
                if wanted in names:
                    d.set_mode(names.index(wanted))
                    return
            d.set_custom_mode()
        self._guard(run)

    def set_zone_colors(self, dev: int, zone: int, colors: list[RGB]) -> None:
        from openrgb.utils import RGBColor

        def run():
            z = self._dev(dev).zones[zone]
            if not colors or not z.leds:
                return
            z.set_colors([RGBColor(*c) for c in colors[:len(z.leds)]], fast=True)
        self._guard(run)

    def set_hardware_mode(self, dev: int, mode_name: str, color: RGB | None = None) -> None:
        from openrgb.utils import RGBColor

        def run():
            d = self._dev(dev)
            idx = [m.name for m in d.modes].index(mode_name)
            mode = d.modes[idx]
            if color is not None and getattr(mode, "colors", None):
                mode.colors = [RGBColor(*color) for _ in mode.colors]
            d.set_mode(mode)
            if color is not None and mode.name.lower() in ("static", "direct", "custom"):
                d.set_color(RGBColor(*color))
        self._guard(run)

    def save_to_device(self, dev: int) -> None:
        self._guard(lambda: self._dev(dev).save_mode())

    def resize_zone(self, dev: int, zone: int, size: int) -> None:
        def run():
            self._dev(dev).zones[zone].resize(size)
            self._client.update()
        self._guard(run)


class DemoBackend(Backend):
    """Simulierte Hardware zum Ausprobieren."""
    name = "Demo"

    def __init__(self):
        self._connected = False
        self.frames: dict[tuple[int, int], list[RGB]] = {}
        self._devices = [
            LightDevice(0, "ASUS ROG STRIX B650-A (Demo)", "MOTHERBOARD", "ASUS", location="SMBus",
                        zones=[LightZone(0, "Aura Mainboard", 6), LightZone(1, "ARGB Header 1", 24, 0, 120)],
                        modes=["Direct", "Static", "Breathing", "Spectrum Cycle", "Rainbow"]),
            LightDevice(1, "G.Skill Trident Z5 RGB DIMM 1 (Demo)", "DRAM", "G.Skill", location="SMBus 0x5A",
                        zones=[LightZone(0, "DRAM", 8)], modes=["Direct", "Static", "Breathing", "Rainbow"]),
            LightDevice(2, "G.Skill Trident Z5 RGB DIMM 2 (Demo)", "DRAM", "G.Skill", location="SMBus 0x5B",
                        zones=[LightZone(0, "DRAM", 8)], modes=["Direct", "Static", "Breathing", "Rainbow"]),
            LightDevice(3, "MSI GeForce RTX 4070 Gaming X (Demo)", "GPU", "MSI", location="I2C",
                        zones=[LightZone(0, "GPU", 12)], modes=["Direct", "Static", "Breathing", "Rainbow", "Off"]),
            LightDevice(4, "Lian Li UNI FAN SL120 (Demo)", "COOLER", "Lian Li", location="HID",
                        zones=[LightZone(i, f"Lüfter {i + 1}", 16) for i in range(3)],
                        modes=["Direct", "Static", "Rainbow", "Breathing"]),
            LightDevice(5, "Corsair K70 RGB (Demo)", "KEYBOARD", "Corsair", location="HID",
                        zones=[LightZone(0, "Tastatur", 104)], modes=["Direct", "Static", "Wave"]),
        ]

    @property
    def connected(self) -> bool:
        return self._connected

    def connect(self) -> None:
        self._connected = True

    def disconnect(self) -> None:
        self._connected = False

    def list_devices(self) -> list[LightDevice]:
        return list(self._devices) if self._connected else []

    def set_zone_colors(self, dev: int, zone: int, colors: list[RGB]) -> None:
        n = self._devices[dev].zones[zone].led_count
        self.frames[(dev, zone)] = list(colors[:n])

    def set_hardware_mode(self, dev: int, mode_name: str, color: RGB | None = None) -> None:
        self._devices[dev].active_mode = self._devices[dev].modes.index(mode_name)

    def resize_zone(self, dev: int, zone: int, size: int) -> None:
        self._devices[dev].zones[zone].led_count = size
