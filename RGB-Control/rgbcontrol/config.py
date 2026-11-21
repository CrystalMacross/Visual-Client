"""Einstellungen, Profile und Windows-Autostart."""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path


def data_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home() / ".config")
    d = Path(base) / "RGBControl"
    (d / "profiles").mkdir(parents=True, exist_ok=True)
    return d


@dataclass
class Settings:
    host: str = "127.0.0.1"
    port: int = 6742
    openrgb_path: str = ""
    autostart_openrgb: bool = True
    start_minimized: bool = False
    minimize_to_tray: bool = True
    restore_last_on_start: bool = True
    demo_mode: bool = False
    last_state: dict = field(default_factory=dict)

    @classmethod
    def load(cls) -> "Settings":
        p = data_dir() / "settings.json"
        try:
            raw = json.loads(p.read_text(encoding="utf-8"))
            known = {k: v for k, v in raw.items() if k in cls.__dataclass_fields__}
            return cls(**known)
        except (OSError, ValueError, TypeError):
            return cls()

    def save(self) -> None:
        p = data_dir() / "settings.json"
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(p)


# ---------------------------------------------------------------- Profile
def _safe_name(name: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name or "Profil"


def list_profiles() -> list[str]:
    return sorted(p.stem for p in (data_dir() / "profiles").glob("*.json"))


def save_profile(name: str, state: dict) -> str:
    name = _safe_name(name)
    (data_dir() / "profiles" / f"{name}.json").write_text(
        json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")
    return name


def load_profile(name: str) -> dict:
    return json.loads((data_dir() / "profiles" / f"{_safe_name(name)}.json").read_text(encoding="utf-8"))


def delete_profile(name: str) -> None:
    (data_dir() / "profiles" / f"{_safe_name(name)}.json").unlink(missing_ok=True)


# ---------------------------------------------------------------- Autostart
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "RGBControl"
TASK_NAME = "RGBControl"


def _launch_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --minimized'
    main = Path(__file__).resolve().parent.parent / "main.py"
    pyw = Path(sys.executable).with_name("pythonw.exe")
    return f'"{pyw if pyw.exists() else sys.executable}" "{main}" --minimized'


def autostart_enabled() -> bool:
    if sys.platform != "win32":
        return False
    import subprocess
    r = subprocess.run(["schtasks", "/Query", "/TN", TASK_NAME], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW)  # type: ignore[attr-defined]
    if r.returncode == 0:
        return True
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, RUN_NAME)
            return True
    except OSError:
        return False


def set_autostart(enabled: bool) -> None:
    """Autostart beim Anmelden.

    Da die App Administratorrechte braucht (RAM/Mainboard-Zugriff), wird eine
    Aufgabenplanung mit höchsten Rechten angelegt - so kommt beim Hochfahren
    keine UAC-Abfrage. Fallback: normaler Run-Eintrag in der Registry.
    """
    if sys.platform != "win32":
        raise OSError("Autostart wird nur unter Windows unterstützt.")
    import subprocess
    import winreg
    nw = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    if enabled:
        r = subprocess.run(["schtasks", "/Create", "/F", "/TN", TASK_NAME, "/SC", "ONLOGON",
                            "/RL", "HIGHEST", "/TR", _launch_command()],
                           capture_output=True, creationflags=nw)
        if r.returncode != 0:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
                winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, _launch_command())
    else:
        subprocess.run(["schtasks", "/Delete", "/F", "/TN", TASK_NAME], capture_output=True, creationflags=nw)
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
                winreg.DeleteValue(k, RUN_NAME)
        except OSError:
            pass
