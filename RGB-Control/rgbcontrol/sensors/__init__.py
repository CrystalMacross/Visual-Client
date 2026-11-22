"""Sensoren: Temperaturen (CPU, GPU, Wasserpumpe), RAM-Auslastung, Maus-Akkus."""
from __future__ import annotations

import threading
import time
import traceback

from .model import FanReading, MouseBattery, Reading, Snapshot  # noqa: F401
from .select import pick_cpu_temp, pick_fans, pick_gpu, pick_pump

PAWNIO_URL = "https://pawnio.eu"


class SensorHub:
    """Liest im Hintergrund alle Sensoren und meldet neue Werte per Callback."""

    TEMP_INTERVAL = 2.0
    BATTERY_INTERVAL = 60.0

    def __init__(self, on_update=None, read_controllers: bool = True, enable_hardware: bool = True):
        self.on_update = on_update
        self.read_controllers = read_controllers
        self.enable_hardware = enable_hardware
        self.snapshot = Snapshot()
        self._mice: list[MouseBattery] = []
        self._battery_errors: list[str] = []
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self._providers: list = []
        self._source = ""
        self._init_notes: list[str] = []
        self._acpi_next = 0.0
        self._acpi_value: float | None = None
        self._battery_now = threading.Event()

    # ----------------------------------------------------------- Lifecycle
    def start(self) -> None:
        self._stop.clear()
        for fn, name in ((self._temp_loop, "Sensors"), (self._battery_loop, "Battery")):
            t = threading.Thread(target=fn, name=name, daemon=True)
            t.start()
            self._threads.append(t)

    def stop(self) -> None:
        self._stop.set()
        self._battery_now.set()
        for t in self._threads:
            t.join(timeout=3)
        self._threads = []
        for p in self._providers:
            try:
                p.close()
            except Exception:
                pass
        self._providers = []

    def refresh_batteries(self) -> None:
        self._battery_now.set()

    # ----------------------------------------------------------- Provider
    def _init_providers(self) -> None:
        if not self.enable_hardware:
            return
        from .lhm import LhmProvider, find_dll_dir
        dll = find_dll_dir()
        if dll is not None:
            try:
                self._providers.append(LhmProvider(dll, self.read_controllers))
                self._source = "LibreHardwareMonitor"
            except Exception as e:  # noqa: BLE001
                traceback.print_exc()
                self._init_notes.append(f"LibreHardwareMonitor konnte nicht geladen werden: {e}")
        else:
            self._init_notes.append("LibreHardwareMonitor fehlt (Ordner 'lhm') - Temperaturen eingeschränkt.")
        if not self._providers:
            try:
                from .fallback import NvmlProvider
                self._providers.append(NvmlProvider())
                self._source = "NVIDIA-Treiber"
            except Exception:
                pass

    def _read_all(self) -> list[Reading]:
        out: list[Reading] = []
        for p in self._providers:
            try:
                out += p.read()
            except Exception:  # noqa: BLE001
                traceback.print_exc()
        return out

    # ----------------------------------------------------------- Schleifen
    def _temp_loop(self) -> None:
        try:
            self._init_providers()
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        while not self._stop.is_set():
            self.snapshot = self.build_snapshot(self._read_all())
            if self.on_update:
                try:
                    self.on_update(self.snapshot)
                except RuntimeError:
                    return
            self._stop.wait(self.TEMP_INTERVAL)

    def _battery_loop(self) -> None:
        from . import battery
        while not self._stop.is_set():
            if self.enable_hardware:
                try:
                    self._mice, self._battery_errors = battery.read_all()
                except Exception as e:  # noqa: BLE001
                    self._battery_errors = [str(e)]
            self._battery_now.wait(self.BATTERY_INTERVAL)
            self._battery_now.clear()

    def build_snapshot(self, readings: list[Reading]) -> Snapshot:
        s = Snapshot(temp_source=self._source, notes=list(self._init_notes))
        try:
            import psutil
            vm = psutil.virtual_memory()
            s.ram_used, s.ram_total, s.ram_percent = vm.total - vm.available, vm.total, vm.percent
        except Exception:
            pass
        cpu = pick_cpu_temp(readings)
        if cpu:
            s.cpu_temp, s.cpu_name = cpu.value, cpu.hw_name
        else:
            if self.enable_hardware and time.monotonic() > self._acpi_next:
                from .fallback import acpi_cpu_temp
                self._acpi_value = acpi_cpu_temp()
                self._acpi_next = time.monotonic() + 10
            s.cpu_temp = self._acpi_value
            if self._source == "LibreHardwareMonitor":
                s.notes.append("CPU-Temperatur: bitte den PawnIO-Treiber installieren (pawnio.eu) und neu starten.")
        cpu_load = next((r for r in readings if r.hw_type == "Cpu" and r.sensor_type == "Load"
                         and r.name == "CPU Total" and r.value is not None), None)
        if cpu_load:
            s.cpu_load = cpu_load.value
        elif not s.cpu_name:
            try:
                import psutil
                s.cpu_load = psutil.cpu_percent(None)
            except Exception:
                pass
        gt, gl, vu, vt = pick_gpu(readings)
        if gt:
            s.gpu_temp, s.gpu_name = gt.value, gt.hw_name
        if gl:
            s.gpu_load = gl.value
            s.gpu_name = s.gpu_name or gl.hw_name
        s.vram_used_mb, s.vram_total_mb = vu, vt
        pt, pr = pick_pump(readings)
        if pt:
            s.pump_temp, s.pump_name = pt.value, pt.hw_name
        if pr:
            s.pump_rpm = pr.value
            s.pump_name = s.pump_name or pr.hw_name
        s.fans = pick_fans(readings)
        s.mice = list(self._mice)
        return s

