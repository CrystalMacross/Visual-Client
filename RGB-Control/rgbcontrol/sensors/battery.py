"""Akkustand kabelloser Mäuse.

Quellen:
* Bluetooth: Windows kennt den Akkustand jedes BT-Geräts (Geräteeigenschaft).
* Logitech (Lightspeed/Unifying/Bolt/kabelgebunden): HID++ 2.0 Protokoll.
* Razer (HyperSpeed-Dongle/Dock/kabelgebunden): Razer-Feature-Report.

Die Protokoll-Teile sind reine Funktionen und damit ohne Hardware testbar.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time

from .model import MouseBattery

MOUSE_WORDS = re.compile(
    r"mouse|maus|mx master|mx anywhere|mx vertical|lift|pebble|g30[0-9]|g50[0-9]|g60[0-9]|g70[0-9]|g90[0-9]|"
    r"superlight|pro x|viper|basilisk|deathadder|naga|orochi|cobra|aerox|rival|prime|harpoon|dark core|"
    r"m65|m75|katar|ironclaw|sabre|nightsword|pulsefire|model [odi]|xm[12]|zowie|ec[12]|g-wolves|"
    r"pulsar|lamzu|vaxee|burst|kone|kain|trackball|ergo|magic mouse|arc|surface precision", re.I)


# =============================================================== Bluetooth
_BT_SCRIPT = r"""
$ErrorActionPreference = 'SilentlyContinue'
$bat = '{104EA319-6EE2-4701-BD47-8DDBF425BBE5} 2'
$cont = '{8C7ED206-3F8A-4827-B3AB-AE9E1FAEFC6C} 2'
$mice = @{}
Get-PnpDevice -PresentOnly -Class Mouse | ForEach-Object {
  $c = (Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName $cont).Data
  if ($c) { $mice["$c"] = $true }
}
$out = @()
Get-PnpDevice -PresentOnly -Class Bluetooth,HIDClass,System | ForEach-Object {
  $b = (Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName $bat).Data
  if ($b -ne $null) {
    $c = (Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName $cont).Data
    $out += [pscustomobject]@{ Name = $_.FriendlyName; Battery = [int]$b; IsMouse = [bool]$mice["$c"] }
  }
}
$out | ConvertTo-Json -Compress
"""


def parse_bluetooth_json(text: str) -> list[MouseBattery]:
    text = (text or "").strip()
    if not text:
        return []
    data = json.loads(text)
    if isinstance(data, dict):
        data = [data]
    seen, out = set(), []
    for d in data:
        name = (d.get("Name") or "").strip()
        if not name or name in seen:
            continue
        if not (d.get("IsMouse") or MOUSE_WORDS.search(name)):
            continue
        seen.add(name)
        pct = d.get("Battery")
        out.append(MouseBattery(name, int(pct) if pct is not None else None, False, "Bluetooth"))
    return out


def read_bluetooth() -> list[MouseBattery]:
    if sys.platform != "win32":
        return []
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", _BT_SCRIPT],
                       capture_output=True, text=True, timeout=40,
                       creationflags=subprocess.CREATE_NO_WINDOW)  # type: ignore[attr-defined]
    return parse_bluetooth_json(r.stdout)


# =============================================================== Logitech HID++ 2.0
LOGI_VID = 0x046D
SWID = 0x0B
FEAT_ROOT = 0x0000
FEAT_NAME = 0x0005
FEAT_BATTERY_STATUS = 0x1000
FEAT_BATTERY_VOLTAGE = 0x1001
FEAT_UNIFIED_BATTERY = 0x1004
DEVICE_TYPES_MOUSE = {3, 4, 5}   # Maus, Trackpad, Trackball

# Spannung (mV) -> Prozent (Li-Ion-Kurve wie in Solaar)
_VOLT_TABLE = [(4186, 100), (4067, 90), (3989, 80), (3922, 70), (3859, 60), (3811, 50), (3778, 40),
               (3751, 30), (3717, 20), (3671, 10), (3646, 5), (3579, 2), (3500, 0)]


def hidpp_request(dev_idx: int, feat_idx: int, func: int, params: bytes = b"") -> list[int]:
    msg = [0x11, dev_idx & 0xFF, feat_idx & 0xFF, ((func & 0x0F) << 4) | SWID] + list(params)
    return (msg + [0] * 20)[:20]


def hidpp_match(resp: list[int], dev_idx: int, feat_idx: int, func: int) -> str:
    """'ok', 'error' oder '' (gehört nicht zu dieser Anfrage)."""
    if len(resp) < 5 or resp[0] not in (0x10, 0x11) or resp[1] != dev_idx:
        return ""
    if resp[0] == 0x10 and resp[2] == 0x8F:                 # HID++ 1.0 Fehler (z.B. Gerät aus)
        return "error" if resp[3] == feat_idx else ""
    if resp[2] == 0xFF and resp[3] == feat_idx and resp[4] == (((func & 0x0F) << 4) | SWID):
        return "error"
    if resp[0] == 0x11 and resp[2] == feat_idx and resp[3] == (((func & 0x0F) << 4) | SWID):
        return "ok"
    return ""


def voltage_to_percent(mv: int) -> int:
    if mv >= _VOLT_TABLE[0][0]:
        return 100
    for (v_hi, p_hi), (v_lo, p_lo) in zip(_VOLT_TABLE, _VOLT_TABLE[1:]):
        if v_lo <= mv <= v_hi:
            return int(round(p_lo + (p_hi - p_lo) * (mv - v_lo) / (v_hi - v_lo)))
    return 0


def parse_unified_battery(payload: list[int]) -> tuple[int | None, bool, str]:
    """Feature 0x1004 get_status -> (prozent, lädt, text)."""
    soc, level, charging = payload[0], payload[1], payload[2]
    text = ""
    if soc == 0 and level:
        if level & 8:
            soc, text = 100, "Voll"
        elif level & 4:
            soc, text = 60, "Gut"
        elif level & 2:
            soc, text = 25, "Niedrig"
        elif level & 1:
            soc, text = 5, "Kritisch"
    return soc, charging in (1, 2, 3), text


def parse_battery_status(payload: list[int]) -> tuple[int | None, bool, str]:
    """Feature 0x1000 -> (prozent, lädt, text)."""
    level, status = payload[0], payload[2]
    return (level if 0 < level <= 100 else None), status in (1, 2, 3, 4), ("Voll" if status == 3 else "")


def parse_battery_voltage(payload: list[int]) -> tuple[int | None, bool, str]:
    """Feature 0x1001 -> (prozent, lädt, text)."""
    mv = (payload[0] << 8) | payload[1]
    flags = payload[2]
    return voltage_to_percent(mv), bool(flags & 0x80), ""


class _HidppDevice:
    def __init__(self, handle, dev_idx: int):
        self.h, self.idx = handle, dev_idx

    def call(self, feat_idx: int, func: int, params: bytes = b"", timeout: float = 0.6) -> list[int] | None:
        self.h.write(hidpp_request(self.idx, feat_idx, func, params))
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            r = self.h.read(20, 100)
            if not r:
                continue
            m = hidpp_match(list(r), self.idx, feat_idx, func)
            if m == "ok":
                return list(r)[4:]
            if m == "error":
                return None
        return None

    def feature_index(self, feature_id: int) -> int | None:
        r = self.call(0x00, 0, bytes([feature_id >> 8, feature_id & 0xFF]))
        return r[0] if r and r[0] else None

    def name(self) -> str:
        fi = self.feature_index(FEAT_NAME)
        if fi is None:
            return ""
        r = self.call(fi, 0)
        if not r:
            return ""
        length, chars = r[0], []
        while len(chars) < length:
            part = self.call(fi, 1, bytes([len(chars)]))
            if not part:
                break
            chars += [c for c in part[:16] if c]
            if not any(part[:16]):
                break
        return bytes(chars[:length]).decode("utf-8", "replace").strip()

    def device_type(self) -> int | None:
        fi = self.feature_index(FEAT_NAME)
        r = self.call(fi, 2) if fi is not None else None
        return r[0] if r else None

    def battery(self) -> tuple[int | None, bool, str] | None:
        for feat, func, parser in ((FEAT_UNIFIED_BATTERY, 1, parse_unified_battery),
                                   (FEAT_BATTERY_STATUS, 0, parse_battery_status),
                                   (FEAT_BATTERY_VOLTAGE, 0, parse_battery_voltage)):
            fi = self.feature_index(feat)
            if fi is None:
                continue
            r = self.call(fi, func)
            if r:
                return parser(r)
        return None


def read_logitech() -> list[MouseBattery]:
    try:
        import hid
    except ImportError:
        return []
    out = []
    seen_paths = set()
    for info in hid.enumerate(LOGI_VID, 0):
        # HID++ "lange Reports" laufen über die Vendor-Collection 0xFF00 / Usage 2
        if info.get("usage_page") != 0xFF00 or info.get("usage") != 0x0002:
            continue
        path = info["path"]
        if path in seen_paths:
            continue
        seen_paths.add(path)
        h = hid.device()
        try:
            h.open_path(path)
        except OSError:
            continue
        try:
            receiver = "receiver" in (info.get("product_string") or "").lower()
            indices = range(1, 7) if receiver else [0xFF, 1, 2, 3, 4, 5, 6]
            for idx in indices:
                d = _HidppDevice(h, idx)
                ping = d.call(0x00, 1, bytes([0, 0, 0x5A]), timeout=0.4)
                if not ping or ping[0] < 2:        # kein Gerät / kein HID++ 2.0
                    continue
                dtype = d.device_type()
                name = d.name() or info.get("product_string") or "Logitech-Gerät"
                if dtype is not None and dtype not in DEVICE_TYPES_MOUSE:
                    continue
                if dtype is None and not MOUSE_WORDS.search(name):
                    continue
                bat = d.battery()
                if bat:
                    pct, charging, text = bat
                    out.append(MouseBattery(f"Logitech {name}" if not name.lower().startswith("logi") else name,
                                            pct, charging, "Logitech", text))
                if idx == 0xFF:
                    break                            # direkt verbundenes Gerät gefunden
        finally:
            h.close()
    return out


# =============================================================== Razer
RAZER_VID = 0x1532


def razer_report(command_class: int, command_id: int, data_size: int, transaction_id: int,
                 args: bytes = b"") -> list[int]:
    r = [0] * 90
    r[0] = 0x00                      # Status: neuer Befehl
    r[1] = transaction_id
    r[5] = data_size
    r[6] = command_class
    r[7] = command_id
    for i, b in enumerate(args[:80]):
        r[8 + i] = b
    crc = 0
    for b in r[2:88]:
        crc ^= b
    r[88] = crc
    return r


def razer_parse(resp: list[int], command_class: int, command_id: int) -> list[int] | None:
    """Gibt die Argumente zurück, wenn die Antwort erfolgreich (Status 0x02) war."""
    if len(resp) == 91:              # führende Report-ID
        resp = resp[1:]
    if len(resp) < 90 or resp[0] != 0x02 or resp[6] != command_class or resp[7] != command_id:
        return None
    return resp[8:88]


def _razer_query(h, tid: int, cls: int, cmd: int) -> list[int] | None:
    h.send_feature_report([0x00] + razer_report(cls, cmd, 0x02, tid))
    for _ in range(5):
        time.sleep(0.06)
        try:
            resp = list(h.get_feature_report(0x00, 91))
        except OSError:
            return None
        if resp and (resp[1] if len(resp) == 91 else resp[0]) == 0x01:   # busy -> nochmal lesen
            continue
        return razer_parse(resp, cls, cmd)
    return None


def read_razer() -> list[MouseBattery]:
    try:
        import hid
    except ImportError:
        return []
    out, seen = [], set()
    for info in hid.enumerate(RAZER_VID, 0):
        if info.get("interface_number", 0) != 0 or info.get("usage_page") != 0x0001 or info.get("usage") != 0x0002:
            continue
        pid = info.get("product_id")
        if pid in seen:
            continue
        name = (info.get("product_string") or "Razer-Maus").strip()
        h = hid.device()
        try:
            h.open_path(info["path"])
        except OSError:
            continue
        try:
            for tid in (0x1F, 0x3F, 0xFF):
                a = _razer_query(h, tid, 0x07, 0x80)
                if a is None:
                    continue
                pct = int(round(a[1] / 255 * 100))
                c = _razer_query(h, tid, 0x07, 0x84)
                charging = bool(c and c[1])
                if pct == 0 and not charging and "wireless" not in name.lower() and "pro" not in name.lower():
                    break                     # kabelgebundene Maus ohne Akku
                seen.add(pid)
                out.append(MouseBattery(name if name.lower().startswith("razer") else f"Razer {name}",
                                        pct, charging, "Razer"))
                break
        except OSError:
            pass
        finally:
            h.close()
    return out


# =============================================================== alle Quellen
def read_all() -> tuple[list[MouseBattery], list[str]]:
    mice: list[MouseBattery] = []
    errors: list[str] = []
    for fn in (read_logitech, read_razer, read_bluetooth):
        try:
            found = fn()
        except Exception as e:  # noqa: BLE001
            errors.append(f"{fn.__name__}: {e}")
            continue
        names = {m.name.lower() for m in mice}
        mice += [m for m in found if m.name.lower() not in names]
    return mice, errors
