"""Findet und startet den OpenRGB-Dienst (Hardware-Treiber) im Hintergrund."""
from __future__ import annotations

import os
import socket
import subprocess
import sys
from pathlib import Path

DOWNLOAD_URL = "https://openrgb.org/releases.html"


def app_dir() -> Path:
    """Ordner der App (bei der .exe der Ordner, in dem die .exe liegt)."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def candidate_paths(custom: str = "") -> list[Path]:
    paths: list[Path] = []
    if custom:
        paths.append(Path(custom))
    base = app_dir()
    paths += [base / "OpenRGB" / "OpenRGB.exe", base / "OpenRGB.exe"]
    for env in ("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"):
        if os.environ.get(env):
            paths.append(Path(os.environ[env]) / "OpenRGB" / "OpenRGB.exe")
    if os.environ.get("LOCALAPPDATA"):
        paths.append(Path(os.environ["LOCALAPPDATA"]) / "Programs" / "OpenRGB" / "OpenRGB.exe")
    if sys.platform != "win32":
        paths += [Path("/usr/bin/openrgb"), Path("/usr/local/bin/openrgb")]
    return paths


def find_openrgb(custom: str = "") -> Path | None:
    for p in candidate_paths(custom):
        try:
            if p.is_file():
                return p
        except OSError:
            continue
    return None


def server_running(host: str = "127.0.0.1", port: int = 6742, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


_proc: subprocess.Popen | None = None


def start_server(exe: Path, port: int = 6742) -> subprocess.Popen:
    """Startet OpenRGB unsichtbar als SDK-Server (ohne eigenes Fenster)."""
    global _proc
    flags = 0
    if sys.platform == "win32":
        flags = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    _proc = subprocess.Popen(
        [str(exe), "--server", "--server-port", str(port)],
        cwd=str(exe.parent), creationflags=flags,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL)
    return _proc


def stop_server() -> None:
    """Beendet nur den OpenRGB-Prozess, den diese App selbst gestartet hat."""
    global _proc
    if _proc and _proc.poll() is None:
        _proc.terminate()
        try:
            _proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            _proc.kill()
    _proc = None


def is_admin() -> bool:
    if sys.platform != "win32":
        return hasattr(os, "geteuid") and os.geteuid() == 0
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_as_admin() -> bool:
    """Startet die App mit Administratorrechten neu (Windows-UAC-Abfrage)."""
    if sys.platform != "win32":
        return False
    import ctypes
    if getattr(sys, "frozen", False):
        exe, params = sys.executable, " ".join(f'"{a}"' for a in sys.argv[1:])
    else:
        exe, params = sys.executable, " ".join(f'"{a}"' for a in sys.argv)
    rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, None, 1)
    return rc > 32
