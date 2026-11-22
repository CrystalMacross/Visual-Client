"""Notlösungen, falls LibreHardwareMonitor nicht verfügbar ist."""
from __future__ import annotations

import subprocess
import sys

from .model import Reading


class NvmlProvider:
    """NVIDIA-Grafikkarten über den Treiber (nvidia-ml-py)."""

    def __init__(self):
        import pynvml
        pynvml.nvmlInit()
        self.nv = pynvml
        self.handles = [pynvml.nvmlDeviceGetHandleByIndex(i) for i in range(pynvml.nvmlDeviceGetCount())]
        if not self.handles:
            raise RuntimeError("keine NVIDIA-GPU")

    def read(self) -> list[Reading]:
        out = []
        nv = self.nv
        for h in self.handles:
            name = nv.nvmlDeviceGetName(h)
            name = name.decode() if isinstance(name, bytes) else str(name)
            try:
                out.append(Reading("GpuNvidia", name, "Temperature", "GPU Core", float(nv.nvmlDeviceGetTemperature(h, 0))))
            except Exception:
                pass
            try:
                out.append(Reading("GpuNvidia", name, "Load", "GPU Core", float(nv.nvmlDeviceGetUtilizationRates(h).gpu)))
            except Exception:
                pass
            try:
                m = nv.nvmlDeviceGetMemoryInfo(h)
                out.append(Reading("GpuNvidia", name, "SmallData", "GPU Memory Used", m.used / 1048576))
                out.append(Reading("GpuNvidia", name, "SmallData", "GPU Memory Total", m.total / 1048576))
            except Exception:
                pass
        return out

    def close(self):
        try:
            self.nv.nvmlShutdown()
        except Exception:
            pass


def acpi_cpu_temp() -> float | None:
    """Ungefähre CPU-Temperatur über die ACPI-Thermalzone (nicht auf jedem PC)."""
    if sys.platform != "win32":
        return None
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command",
             "(Get-CimInstance -Namespace root/wmi MSAcpi_ThermalZoneTemperature -ErrorAction Stop | "
             "Measure-Object CurrentTemperature -Maximum).Maximum"],
            capture_output=True, text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)  # type: ignore[attr-defined]
        v = float(r.stdout.strip().replace(",", "."))
        c = v / 10 - 273.15
        return c if 5 < c < 120 else None
    except Exception:
        return None
