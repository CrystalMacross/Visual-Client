"""Erkennt andere RGB-Programme (iCUE, Armoury Crate, Mystic Light ...),
die mit dieser App um die Hardware streiten, und hilft beim Beenden
bzw. Deinstallieren. Es wird NIE etwas ohne Rückfrage deinstalliert."""
from __future__ import annotations

import re
import shlex
import subprocess
import sys
from dataclasses import dataclass, field


@dataclass(frozen=True)
class KnownApp:
    name: str
    processes: tuple[str, ...]
    registry_patterns: tuple[str, ...]


KNOWN_APPS: list[KnownApp] = [
    KnownApp("Corsair iCUE", ("icue.exe", "corsair.service.exe", "icueDevicePluginHost.exe".lower()), (r"\bicue",)),
    KnownApp("ASUS Armoury Crate / Aura", ("armourycrate.exe", "armourycrate.service.exe", "lightingservice.exe",
                                          "aurawallpaperservice.exe", "armourycratecontrolinterface.exe"),
             (r"armoury ?crate", r"\baura\b", r"rog live service")),
    KnownApp("MSI Center / Mystic Light / Dragon Center", ("msi.centralserver.exe", "msi center.exe", "mysticlight.exe",
                                                          "dragon center.exe", "msi_central_service.exe"),
             (r"msi center", r"mystic ?light", r"dragon ?center", r"msi sdk")),
    KnownApp("Gigabyte RGB Fusion / Control Center", ("rgbfusion.exe", "gcc.exe", "gigabytecontrolcenter.exe", "rgbfusion2.exe"),
             (r"rgb ?fusion", r"gigabyte control center", r"\bgcc\b")),
    KnownApp("ASRock Polychrome", ("polychrome.exe", "asrpolychromergb.exe", "polychromesync.exe"), (r"polychrome",)),
    KnownApp("NZXT CAM", ("nzxt cam.exe", "nzxt cam-service.exe"), (r"nzxt ?cam",)),
    KnownApp("Razer Synapse / Chroma", ("razer synapse 3.exe", "razercentralservice.exe", "razer synapse service.exe",
                                       "razerchromasdkserver.exe", "razer synapse.exe"), (r"razer synapse", r"razer chroma")),
    KnownApp("Logitech G HUB", ("lghub.exe", "lghub_agent.exe", "lghub_updater.exe", "lghub_system_tray.exe"), (r"g ?hub",)),
    KnownApp("SteelSeries GG / Engine", ("steelseriesgg.exe", "steelseriesengine.exe", "steelseriesgglcdservice.exe"),
             (r"steelseries (gg|engine)",)),
    KnownApp("HyperX NGENUITY", ("ngenuity.exe", "hyperx ngenuity.exe"), (r"ngenuity",)),
    KnownApp("Lian Li L-Connect", ("l-connect 3.exe", "l-connect.exe", "lconnectservice.exe"), (r"l-connect",)),
    KnownApp("Thermaltake TT RGB Plus", ("tt rgb plus.exe", "ttrgbplus.exe"), (r"tt rgb plus",)),
    KnownApp("Cooler Master MasterPlus", ("masterplus.exe", "masterplusapp.exe"), (r"masterplus",)),
    KnownApp("G.Skill Trident Z Lighting Control", ("tridentzlightingcontrol.exe", "gskill lighting control.exe"),
             (r"trident z lighting", r"g\.?skill.*lighting")),
    KnownApp("Kingston FURY CTRL", ("furyctrl.exe", "kingston fury ctrl.exe"), (r"fury ctrl",)),
    KnownApp("SignalRGB", ("signalrgb.exe", "signalrgbservice.exe"), (r"signal ?rgb",)),
    KnownApp("EVGA Precision X1 / LED Sync", ("precisionx_x64.exe", "evga led sync.exe"), (r"precision x1", r"evga led sync")),
    KnownApp("XPG Prime / RGB Sync", ("xpg prime.exe", "xpgprime.exe"), (r"xpg (prime|rgb)",)),
    KnownApp("T-Force Blitz", ("t-force blitz.exe", "tforceblitz.exe"), (r"t-force blitz",)),
    KnownApp("Patriot Viper RGB", ("viper rgb.exe",), (r"viper rgb",)),
    KnownApp("Alienware Command Center / AlienFX", ("awcc.exe", "alienfx.exe"), (r"alienware command center", r"alienfx")),
    KnownApp("Deepcool / Phanteks / Montech RGB", ("deepcool-digital.exe", "phanteks.exe"), (r"deepcool", r"phanteks")),
    KnownApp("Corsair Link (alt)", ("corsairlink4.exe",), (r"corsair link",)),
    KnownApp("ROCCAT Swarm", ("roccat_swarm.exe",), (r"roccat swarm",)),
    KnownApp("Wallpaper Engine RGB-Plugin", (), (r"wallpaper engine",)),
]


@dataclass
class InstalledEntry:
    display_name: str
    publisher: str
    uninstall_cmd: str
    quiet_cmd: str = ""


@dataclass
class FoundApp:
    app: KnownApp
    installed: list[InstalledEntry] = field(default_factory=list)
    running: list[tuple[int, str]] = field(default_factory=list)  # (pid, prozessname)


def _registry_entries() -> list[InstalledEntry]:
    if sys.platform != "win32":
        return []
    import winreg
    roots = [
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
        (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
    ]
    out = []
    for hive, path in roots:
        try:
            root = winreg.OpenKey(hive, path)
        except OSError:
            continue
        with root:
            for i in range(winreg.QueryInfoKey(root)[0]):
                try:
                    with winreg.OpenKey(root, winreg.EnumKey(root, i)) as k:
                        def val(n):
                            try:
                                return str(winreg.QueryValueEx(k, n)[0])
                            except OSError:
                                return ""
                        name = val("DisplayName")
                        if name and val("SystemComponent") != "1":
                            out.append(InstalledEntry(name, val("Publisher"), val("UninstallString"),
                                                      val("QuietUninstallString")))
                except OSError:
                    continue
    return out


def _running_processes() -> list[tuple[int, str]]:
    try:
        import psutil
        return [(p.pid, (p.info["name"] or "")) for p in psutil.process_iter(["name"])]
    except ImportError:
        return []


# Microsoft-/Treiberpakete, die nie als Konflikt angezeigt werden sollen
_IGNORE = re.compile(r"(driver|treiber|chipset|firmware|bios|vga|graphics|audio|realtek|intel|nvidia|amd software|"
                     r"microsoft|visual c\+\+|\.net|openrgb|rgb control)", re.I)


def match_installed(entries: list[InstalledEntry], app: KnownApp) -> list[InstalledEntry]:
    res = []
    for e in entries:
        if _IGNORE.search(e.display_name):
            continue
        if any(re.search(p, e.display_name, re.I) for p in app.registry_patterns):
            res.append(e)
    return res


def find_conflicts(entries: list[InstalledEntry] | None = None,
                   procs: list[tuple[int, str]] | None = None) -> list[FoundApp]:
    entries = _registry_entries() if entries is None else entries
    procs = _running_processes() if procs is None else procs
    found = []
    for app in KNOWN_APPS:
        inst = match_installed(entries, app)
        run = [(pid, n) for pid, n in procs if n.lower() in app.processes]
        if inst or run:
            found.append(FoundApp(app, inst, run))
    return found


def kill_processes(found: FoundApp) -> list[str]:
    errors = []
    try:
        import psutil
    except ImportError:
        return ["psutil fehlt"]
    for pid, name in found.running:
        try:
            p = psutil.Process(pid)
            p.terminate()
            try:
                p.wait(timeout=3)
            except psutil.TimeoutExpired:
                p.kill()
        except psutil.NoSuchProcess:
            pass
        except Exception as e:
            errors.append(f"{name}: {e}")
    return errors


def _split_cmd(cmd: str) -> list[str]:
    cmd = cmd.strip()
    if cmd.startswith('"'):
        end = cmd.find('"', 1)
        return [cmd[1:end]] + shlex.split(cmd[end + 1:], posix=False)
    m = re.match(r"(.+?\.exe)(\s+.*)?$", cmd, re.I)
    if m:
        return [m.group(1)] + shlex.split(m.group(2) or "", posix=False)
    return shlex.split(cmd, posix=False)


def run_uninstaller(entry: InstalledEntry) -> None:
    """Startet den offiziellen Deinstaller des Herstellers (mit dessen Fenster)."""
    cmd = entry.uninstall_cmd or entry.quiet_cmd
    if not cmd:
        raise RuntimeError("Kein Deinstallations-Befehl in der Registry gefunden.")
    if re.match(r"^\s*msiexec", cmd, re.I):
        # MsiExec /I{GUID} öffnet sonst den Reparatur-Dialog -> /X erzwingt Deinstallation
        cmd = re.sub(r"/I\s*\{", "/X{", cmd, flags=re.I)
    subprocess.Popen(_split_cmd(cmd))
