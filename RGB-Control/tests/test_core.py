import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from rgbcontrol import conflicts, hwscan  # noqa: E402
from fakes import FakeBackend  # noqa: E402
from rgbcontrol.colors import blend, hex_to_rgb, rgb_to_hex  # noqa: E402
from rgbcontrol.effects import EFFECTS, EffectParams, render  # noqa: E402
from rgbcontrol.engine import EffectEngine, build_targets  # noqa: E402


def test_colors():
    assert hex_to_rgb("#ff8000") == (255, 128, 0)
    assert rgb_to_hex((255, 128, 0)) == "#FF8000"
    assert blend((0, 0, 0), (255, 255, 255), 0.5) == (128, 128, 128)


def test_all_effects_render_valid_colors():
    p = EffectParams(brightness=0.5)
    for e in EFFECTS:
        for t in (0, 0.33, 7.1):
            cols = render(e.key, t, 50, p)
            assert len(cols) == 50
            assert all(0 <= c <= 128 for rgb in cols for c in rgb), e.key


def test_engine_with_fake_backend():
    b = FakeBackend()
    b.connect()
    devs = b.list_devices()
    targets = build_targets(devs, {(d.index, z.index) for d in devs for z in d.zones})
    eng = EffectEngine(b)
    eng.set_group("all", targets, "static", EffectParams(color=(10, 20, 30)))
    eng.start()
    import time
    time.sleep(0.4)
    eng.stop()
    assert b.frames[(0, 0)][0] == (10, 20, 30)
    assert len(b.frames[(5, 0)]) == 104


def test_conflict_matching():
    entries = [conflicts.InstalledEntry("Corsair iCUE5 Software", "Corsair", "MsiExec.exe /I{ABC}"),
               conflicts.InstalledEntry("ARMOURY CRATE Service", "ASUS", "x.exe"),
               conflicts.InstalledEntry("NVIDIA Graphics Driver", "NVIDIA", "x.exe"),
               conflicts.InstalledEntry("OpenRGB", "", "x.exe")]
    found = conflicts.find_conflicts(entries, [(1, "lghub.exe"), (2, "explorer.exe")])
    names = {f.app.name for f in found}
    assert names == {"Corsair iCUE", "ASUS Armoury Crate / Aura", "Logitech G HUB"}


def test_split_cmd():
    assert conflicts._split_cmd('"C:\\Program Files\\X\\unins000.exe" /SILENT') == ["C:\\Program Files\\X\\unins000.exe", "/SILENT"]
    assert conflicts._split_cmd("C:\\Program Files\\X\\uninstall.exe --x")[0] == "C:\\Program Files\\X\\uninstall.exe"


def test_ram_and_usb_guess():
    assert hwscan.ram_rgb_guess("F4-3600C16-8GTZR")[0] == "Wahrscheinlich"
    assert hwscan.ram_rgb_guess("CMW32GX4M2E3200C16")[0] == "Wahrscheinlich"
    assert hwscan.usb_vendor("USB\\VID_1B1C&PID_0C10\\x") == ("1B1C", "Corsair")
    assert hwscan.usb_vendor("USB\\VID_FFFF&PID_0001") is None
