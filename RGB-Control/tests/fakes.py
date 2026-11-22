"""Test-Hilfe: simuliertes Backend (nur für automatische Tests, nicht in der App)."""
from rgbcontrol.backend import Backend, DeviceError, LightDevice, LightZone


class FakeBackend(Backend):
    def __init__(self, broken: set[int] = frozenset()):
        self._connected = False
        self.broken = set(broken)
        self.frames = {}
        self.prepared = []
        self._devices = [
            LightDevice(0, "Mainboard", "MOTHERBOARD", zones=[LightZone(0, "Aura", 6), LightZone(1, "ARGB Header 1", 24, 0, 120)]),
            LightDevice(1, "DIMM 1", "DRAM", zones=[LightZone(0, "DRAM", 8)]),
            LightDevice(2, "DIMM 2", "DRAM", zones=[LightZone(0, "DRAM", 8)]),
            LightDevice(3, "GPU", "GPU", zones=[LightZone(0, "GPU", 12)]),
            LightDevice(4, "Lian Li UNI FAN", "COOLER", zones=[LightZone(i, f"Fan {i + 1}", 16) for i in range(3)]),
            LightDevice(5, "K70", "KEYBOARD", zones=[LightZone(0, "Keys", 104)]),
            LightDevice(6, "G502", "MOUSE", zones=[LightZone(0, "Logo", 1)]),
            LightDevice(7, "Galahad II", "COOLER", zones=[LightZone(0, "Pump Ring", 24), LightZone(1, "Fan 1", 16)]),
        ]

    @property
    def connected(self):
        return self._connected

    def connect(self):
        self._connected = True

    def list_devices(self):
        return list(self._devices) if self._connected else []

    def prepare_direct(self, dev):
        if dev in self.broken:
            raise DeviceError("kaputt")
        self.prepared.append(dev)

    def set_zone_colors(self, dev, zone, colors):
        if dev in self.broken:
            raise DeviceError("kaputt")
        self.frames[(dev, zone)] = list(colors)
