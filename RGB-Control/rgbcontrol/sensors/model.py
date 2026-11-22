from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Reading:
    hw_type: str      # z.B. "Cpu", "GpuNvidia", "Cooler", "Motherboard", "SuperIO"
    hw_name: str
    sensor_type: str  # "Temperature", "Fan", "Load", "SmallData", ...
    name: str
    value: float | None


@dataclass
class MouseBattery:
    name: str
    percent: int | None       # None = Stand unbekannt
    charging: bool = False
    source: str = ""          # "Bluetooth", "Logitech", "Razer"
    level_text: str = ""      # z.B. "Voll", wenn keine Prozent geliefert werden


@dataclass
class FanReading:
    name: str
    rpm: float


@dataclass
class Snapshot:
    cpu_temp: float | None = None
    cpu_name: str = ""
    cpu_load: float | None = None
    gpu_temp: float | None = None
    gpu_name: str = ""
    gpu_load: float | None = None
    vram_used_mb: float | None = None
    vram_total_mb: float | None = None
    pump_temp: float | None = None
    pump_rpm: float | None = None
    pump_name: str = ""
    fans: list[FanReading] = field(default_factory=list)
    ram_used: int = 0
    ram_total: int = 0
    ram_percent: float = 0.0
    mice: list[MouseBattery] = field(default_factory=list)
    temp_source: str = ""
    notes: list[str] = field(default_factory=list)
