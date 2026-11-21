"""Hardware-Scan: listet Grafikkarte, RAM, Mainboard und USB-Geräte auf
und markiert alles, was wahrscheinlich RGB-Beleuchtung hat."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from dataclasses import dataclass

# USB-Hersteller-IDs (VID) bekannter RGB-Hersteller
RGB_USB_VENDORS = {
    "1B1C": "Corsair", "0B05": "ASUS", "1462": "MSI", "1044": "Gigabyte", "048D": "ITE (Gigabyte RGB Fusion)",
    "1E71": "NZXT", "1532": "Razer", "046D": "Logitech", "1038": "SteelSeries", "0951": "HyperX",
    "03F0": "HyperX / HP", "0CF2": "Lian Li (ENE)", "264A": "Thermaltake", "2516": "Cooler Master",
    "26CE": "ASRock", "3842": "EVGA", "1E7D": "Roccat", "31E3": "Wooting", "258A": "Glorious / Sinowealth",
    "320F": "Glorious / Evision", "3633": "Deepcool", "1A2C": "Redragon", "0416": "Winbond (Lian Li / Gigabyte)",
    "2109": "Lian Li (Strimer/Hub)", "1E2C": "HyperX", "2F68": "Hator", "3554": "Endgame Gear",
    "2433": "Asetek", "16D0": "MCS (z.B. Aqua Computer)", "0C70": "Aqua Computer", "1D50": "OpenMoko (Diverse DIY)",
    "16C0": "Teensy / DIY (z.B. WLED, Adalight)", "2341": "Arduino (Adalight)", "1A86": "CH340 (Adalight/DIY)",
    "10C4": "Silicon Labs (Phanteks / DIY)", "3434": "Keychron", "0E8D": "MediaTek",
}

RGB_RAM_PATTERNS = [
    (r"^F[45]-.*(TZR|TZN|TZRS|TRS|TZ5RK|TZ5RS|TZ5NR|TRG|GTZR|GTZRX|NR)", "G.Skill Trident Z / Ripjaws RGB"),
    (r"^CMW", "Corsair Vengeance RGB Pro"), (r"^CMH", "Corsair Vengeance RGB"), (r"^CMG", "Corsair Vengeance RGB RS"),
    (r"^CMT", "Corsair Dominator Platinum RGB"), (r"^CMN", "Corsair Vengeance RGB RT"),
    (r"^KF\d.*A[A-Z]?\d?(/|$|-)", "Kingston FURY Beast/Renegade RGB"), (r"^HX4.*A", "HyperX Fury/Predator RGB"),
    (r"^TF[ABCDE]", "TeamGroup T-Force Delta/Xtreem ARGB"), (r"^PVSR|^PVR|^PVBR", "Patriot Viper RGB"),
    (r"^AX4U.*RGB|^AX5U.*RGB|SPECTRIX", "XPG Spectrix"), (r"^BL\d.*R|^BLM", "Crucial Ballistix RGB"),
    (r"RGB|ARGB", "RGB im Namen"),
]

RGB_GPU_HINTS = ["ROG", "STRIX", "TUF", "GAMING X", "SUPRIM", "AORUS", "GAMING OC", "EAGLE", "ZOTAC",
                 "AMP", "FTW", "XC", "RED DEVIL", "NITRO", "PULSE", "TAICHI", "PHANTOM", "OC"]


@dataclass
class HwItem:
    category: str
    name: str
    vendor: str = ""
    detail: str = ""
    rgb: str = "Nein"   # "Ja", "Wahrscheinlich", "Möglich", "Nein"


def _powershell(script: str, timeout: int = 30):
    flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0  # type: ignore[attr-defined]
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, timeout=timeout, creationflags=flags)
    out = r.stdout.strip()
    if not out:
        return []
    data = json.loads(out)
    return data if isinstance(data, list) else [data]


def ram_rgb_guess(part: str) -> tuple[str, str]:
    part = (part or "").strip().upper()
    for pat, label in RGB_RAM_PATTERNS:
        if re.search(pat, part):
            return "Wahrscheinlich", label
    return "Möglich" if part else "Nein", ""


def usb_vendor(pnp_id: str) -> tuple[str, str] | None:
    m = re.search(r"VID_([0-9A-F]{4})", pnp_id or "", re.I)
    if not m:
        return None
    vid = m.group(1).upper()
    return (vid, RGB_USB_VENDORS[vid]) if vid in RGB_USB_VENDORS else None


def scan() -> list[HwItem]:
    if sys.platform != "win32":
        return [HwItem("Info", "Hardware-Scan ist nur unter Windows verfügbar", rgb="-")]
    items: list[HwItem] = []
    try:
        for b in _powershell("Get-CimInstance Win32_BaseBoard | Select Manufacturer,Product | ConvertTo-Json"):
            items.append(HwItem("Mainboard", b.get("Product") or "?", b.get("Manufacturer") or "",
                                rgb="Wahrscheinlich"))
    except Exception as e:
        items.append(HwItem("Mainboard", f"Fehler: {e}", rgb="-"))
    try:
        for g in _powershell("Get-CimInstance Win32_VideoController | Select Name,AdapterCompatibility,PNPDeviceID | ConvertTo-Json"):
            name = g.get("Name") or "?"
            if "basic" in name.lower() or "remote" in name.lower():
                continue
            sub = re.search(r"SUBSYS_([0-9A-F]{4})([0-9A-F]{4})", g.get("PNPDeviceID") or "", re.I)
            board = ""
            if sub:
                board = {"1043": "ASUS", "1462": "MSI", "1458": "Gigabyte", "3842": "EVGA", "19DA": "Zotac",
                         "1569": "Palit", "10B0": "Gainward", "1DA2": "Sapphire", "148C": "PowerColor",
                         "1682": "XFX", "1849": "ASRock", "196E": "PNY", "7377": "Colorful", "1B4C": "KFA2/Galax"
                         }.get(sub.group(2).upper(), "")
            integrated = any(k in name.lower() for k in ("uhd", "iris", "radeon(tm) graphics", "vega"))
            hint = "Nein" if integrated else ("Wahrscheinlich" if any(h in name.upper() for h in RGB_GPU_HINTS) or board else "Möglich")
            items.append(HwItem("Grafikkarte", name, board or g.get("AdapterCompatibility") or "",
                                "Integrierte Grafik" if integrated else (f"Boardpartner: {board}" if board else ""), hint))
    except Exception as e:
        items.append(HwItem("Grafikkarte", f"Fehler: {e}", rgb="-"))
    try:
        for m in _powershell("Get-CimInstance Win32_PhysicalMemory | Select Manufacturer,PartNumber,Capacity,Speed,DeviceLocator | ConvertTo-Json"):
            part = (m.get("PartNumber") or "").strip()
            guess, label = ram_rgb_guess(part)
            cap = int(m.get("Capacity") or 0) // (1024 ** 3)
            items.append(HwItem("Arbeitsspeicher", f"{part or 'RAM'} ({cap} GB, {m.get('Speed')} MT/s)",
                                (m.get("Manufacturer") or "").strip(), f"{m.get('DeviceLocator') or ''} {label}".strip(), guess))
    except Exception as e:
        items.append(HwItem("Arbeitsspeicher", f"Fehler: {e}", rgb="-"))
    try:
        seen = set()
        for d in _powershell("Get-CimInstance Win32_PnPEntity | Where-Object { $_.PNPDeviceID -like 'USB*' -or $_.PNPDeviceID -like 'HID*' } | Select Name,PNPDeviceID | ConvertTo-Json", 60):
            v = usb_vendor(d.get("PNPDeviceID") or "")
            if not v:
                continue
            pid = re.search(r"PID_([0-9A-F]{4})", d.get("PNPDeviceID") or "", re.I)
            key = (v[0], pid.group(1).upper() if pid else "")
            if key in seen:
                continue
            seen.add(key)
            items.append(HwItem("USB / Peripherie / Lüfter-Controller", d.get("Name") or "USB-Gerät", v[1],
                                f"VID {v[0]} PID {key[1]}", "Wahrscheinlich"))
    except Exception as e:
        items.append(HwItem("USB", f"Fehler: {e}", rgb="-"))
    return items
