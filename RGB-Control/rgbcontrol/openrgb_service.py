"""Findet und startet den OpenRGB-Dienst (Hardware-Treiber) im Hintergrund."""
from __future__ import annotations

import logging
import os
import socket
import subprocess
import sys
from pathlib import Path

DOWNLOAD_URL = "https://openrgb.org/releases.html"
log = logging.getLogger(__name__)


def app_dir() -> Path:
    """Ordner der App (bei der .exe der Ordner, in dem die .exe liegt)."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def resource_dirs() -> list[Path]:
    """Ordner, in denen mitgelieferte Dateien liegen können (App-Ordner, PyInstaller-Ordner)."""
    dirs = [app_dir()]
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass and Path(meipass) not in dirs:
        dirs.append(Path(meipass))
    return dirs


def candidate_paths(custom: str = "") -> list[Path]:
    paths: list[Path] = []
    if custom:
        paths.append(Path(custom))
    for d in resource_dirs():
        paths += [d / "OpenRGB" / "OpenRGB.exe", d / "OpenRGB.exe"]
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
_log_file = None


def start_server(exe: Path, port: int = 6742) -> subprocess.Popen:
    """Startet OpenRGB unsichtbar als SDK-Server (ohne eigenes Fenster).
    Die Ausgabe von OpenRGB landet in %APPDATA%\\RGBControl\\openrgb.log."""
    global _proc, _log_file
    flags = 0
    if sys.platform == "win32":
        flags = subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
    from .config import data_dir
    try:
        _log_file = open(data_dir() / "openrgb.log", "w", encoding="utf-8", errors="replace")
        out = _log_file
    except OSError:
        out = subprocess.DEVNULL
    args = [str(exe), "--server", "--server-port", str(port)]
    log.info("Starte OpenRGB: %s", " ".join(args))
    _proc = subprocess.Popen(args, cwd=str(exe.parent), creationflags=flags,
                             stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    return _proc


def server_process_alive() -> bool | None:
    """True/False für den selbst gestarteten OpenRGB-Prozess, None wenn keiner gestartet wurde."""
    if _proc is None:
        return None
    return _proc.poll() is None


def foreign_openrgb_pids() -> list[int]:
    """OpenRGB-Prozesse, die NICHT von dieser App gestartet wurden."""
    try:
        import psutil
    except ImportError:
        return []
    own = _proc.pid if _proc and _proc.poll() is None else None
    out = []
    for p in psutil.process_iter(["name"]):
        if (p.info.get("name") or "").lower() in ("openrgb.exe", "openrgb") and p.pid != own:
            out.append(p.pid)
    return out


def kill_pids(pids: list[int]) -> None:
    try:
        import psutil
    except ImportError:
        return
    for pid in pids:
        try:
            p = psutil.Process(pid)
            p.terminate()
            p.wait(timeout=5)
        except Exception:
            pass


def pawnio_installed() -> bool | None:
    """PawnIO = Treiber für SMBus (RAM/Mainboard-RGB) und CPU-Temperatur. None = unbekannt."""
    if sys.platform != "win32":
        return None
    try:
        r = subprocess.run(["sc", "query", "PawnIO"], capture_output=True, text=True, timeout=10,
                           creationflags=subprocess.CREATE_NO_WINDOW)  # type: ignore[attr-defined]
        return r.returncode == 0
    except Exception:
        return None


def pawnio_installer() -> Path | None:
    for d in resource_dirs():
        for name in ("PawnIO_setup.exe", "PawnIO.exe"):
            p = d / "drivers" / name
            if p.is_file():
                return p
    return None


def run_pawnio_installer() -> bool:
    p = pawnio_installer()
    if not p:
        return False
    subprocess.Popen([str(p)], cwd=str(p.parent))
    return True


def unblock_app_files() -> None:
    """Entfernt die 'Aus dem Internet'-Markierung von allen DLL/EXE-Dateien der App
    (sonst blockiert Windows u.U. das Laden von .NET-DLLs aus einem entpackten ZIP)."""
    if sys.platform != "win32":
        return
    for base in resource_dirs():
        for root, _dirs, files in os.walk(base):
            for f in files:
                if f.lower().endswith((".dll", ".exe", ".sys")):
                    try:
                        os.remove(os.path.join(root, f) + ":Zone.Identifier")
                    except OSError:
                        pass


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
    global _log_file
    if _log_file:
        try:
            _log_file.close()
        except OSError:
            pass
        _log_file = None


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
