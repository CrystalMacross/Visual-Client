"""Temperaturen/Drehzahlen über LibreHardwareMonitorLib (.NET, via pythonnet).

Die DLLs liegen im Ordner 'lhm' neben der App (der GitHub-Workflow lädt sie
automatisch herunter). Braucht Administratorrechte; für die CPU-Temperatur
zusätzlich den PawnIO-Treiber.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from .model import Reading


def find_dll_dir() -> Path | None:
    from ..openrgb_service import resource_dirs
    for base in resource_dirs():
        p = base / "lhm" / "LibreHardwareMonitorLib.dll"
        if p.is_file():
            return p.parent
    return None


def unblock(folder: Path) -> None:
    """Entfernt die 'Aus dem Internet'-Markierung (Zone.Identifier), sonst
    verweigert .NET Framework das Laden der DLLs aus einem entpackten ZIP."""
    if sys.platform != "win32":
        return
    for root, _dirs, files in os.walk(folder):
        for f in files:
            if f.lower().endswith((".dll", ".exe")):
                try:
                    os.remove(os.path.join(root, f) + ":Zone.Identifier")
                except OSError:
                    pass


_loaded = False


class LhmProvider:
    def __init__(self, dll_dir: Path, controllers: bool = True):
        global _loaded
        from ..openrgb_service import resource_dirs
        for d in {dll_dir, *resource_dirs()}:
            unblock(d)
        if not _loaded:
            from pythonnet import load
            try:
                load("netfx")
            except RuntimeError:
                pass  # Runtime bereits geladen
            _loaded = True
        import clr  # noqa: F401  (kommt von pythonnet)
        clr.AddReference(str(dll_dir / "LibreHardwareMonitorLib.dll"))
        from LibreHardwareMonitor.Hardware import Computer  # type: ignore

        c = Computer()
        c.IsCpuEnabled = True
        c.IsGpuEnabled = True
        c.IsMotherboardEnabled = True
        c.IsControllerEnabled = controllers
        c.Open()
        self._c = c

    def read(self) -> list[Reading]:
        out: list[Reading] = []

        def collect(hw, parent_type=None):
            hw.Update()
            htype = str(hw.HardwareType)
            for s in hw.Sensors:
                v = s.Value
                out.append(Reading(parent_type or htype, str(hw.Name), str(s.SensorType), str(s.Name),
                                   float(v) if v is not None else None))
            for sub in hw.SubHardware:
                collect(sub, parent_type or htype)

        for hw in self._c.Hardware:
            try:
                collect(hw)
            except Exception:  # einzelne Hardware darf nicht alles kaputt machen
                continue
        return out

    def close(self) -> None:
        try:
            self._c.Close()
        except Exception:
            pass
