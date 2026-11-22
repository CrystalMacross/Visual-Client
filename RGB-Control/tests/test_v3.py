"""Tests für die Fehlerbehebungen: defekte Geräte, Modus-Auswahl, Geräte ohne Einzel-LED-Modus."""
import sys
import time
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fakes import FakeBackend  # noqa: E402
from rgbcontrol.backend import OpenRGBBackend, average, choose_mode  # noqa: E402
from rgbcontrol.effects import EffectParams  # noqa: E402
from rgbcontrol.engine import EffectEngine, build_targets  # noqa: E402


def M(name, color_mode, colors=0):
    return SimpleNamespace(name=name, color_mode=color_mode, colors=[None] * colors)


def test_broken_device_does_not_block_others():
    b = FakeBackend(broken={1})
    b.connect()
    devs = b.list_devices()
    eng = EffectEngine(b)
    eng.set_group("all", build_targets(devs, {(d.index, z.index) for d in devs for z in d.zones}),
                  "static", EffectParams(color=(5, 6, 7)))
    eng.start()
    time.sleep(0.5)
    eng.stop()
    assert b.frames[(3, 0)][0] == (5, 6, 7)       # GPU bekommt trotzdem Farben
    assert (1, 0) not in b.frames                  # defektes Gerät übersprungen
    assert 1 in eng.device_errors


def test_flash_reaches_all_and_ends():
    b = FakeBackend()
    b.connect()
    devs = b.list_devices()
    eng = EffectEngine(b)
    eng.start()
    eng.flash(build_targets(devs, {(d.index, z.index) for d in devs for z in d.zones}), (255, 0, 0), 0.3)
    time.sleep(0.2)
    assert b.frames[(5, 0)][0] == (255, 0, 0)
    time.sleep(0.4)
    eng.stop()
    assert eng._flash is None


def test_choose_mode():
    assert choose_mode([M("Static", 2, 1), M("Direct", 1)]) == (1, True)
    assert choose_mode([M("Static", 2, 1), M("Custom", 1)]) == (1, True)
    assert choose_mode([M("Breathing", 2, 1), M("Static", 2, 1)]) == (1, False)
    assert choose_mode([M("Rainbow", 0)]) == (None, False)


def test_average():
    assert average([(0, 0, 0), (255, 255, 255)]) == (127, 127, 127)
    assert average([]) == (0, 0, 0)


class _Comms:
    _protocol_version = 4

    def __init__(self):
        self.sent = []

    def send_header(self, dev, ptype, size):
        self.sent.append(("h", dev, int(ptype)))

    def send_data(self, data, *a):
        self.sent.append(("d", len(data)))


def test_mode_specific_device_gets_mode_color():
    """Geräte ohne Direct-Modus: Farbe muss über den Modus gesetzt werden (sonst passiert nichts)."""
    comms = _Comms()
    mode = SimpleNamespace(name="Static", color_mode=2, colors=[None], pack=lambda v: b"x" * 10)
    zone = SimpleNamespace(leds=[1, 2, 3], set_colors=lambda *a, **k: (_ for _ in ()).throw(AssertionError("per-LED")))
    dev = SimpleNamespace(id=0, name="RAM", modes=[mode], zones=[zone], comms=comms, active_mode=0)
    b = OpenRGBBackend()
    b._client = SimpleNamespace(devices=[dev])
    b.prepare_direct(0)
    assert b._per_led[0] is False
    comms.sent.clear()
    b.set_zone_colors(0, 0, [(255, 0, 0)] * 3)
    assert comms.sent and comms.sent[0][0] == "h"     # UPDATEMODE gesendet
    assert mode.colors[0].red == 255


def test_per_led_device_pads_colors():
    got = {}
    zone = SimpleNamespace(leds=[1, 2, 3, 4], set_colors=lambda cols, fast=False: got.setdefault("c", cols))
    mode = SimpleNamespace(name="Direct", color_mode=1, colors=[], pack=lambda v: b"x")
    dev = SimpleNamespace(id=0, name="Strip", modes=[mode], zones=[zone], comms=_Comms(), active_mode=0)
    b = OpenRGBBackend()
    b._client = SimpleNamespace(devices=[dev])
    b.prepare_direct(0)
    b.set_zone_colors(0, 0, [(1, 2, 3)] * 2)
    assert len(got["c"]) == 4
