import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from rgbcontrol.backend import DemoBackend, LightDevice, LightZone  # noqa: E402
from rgbcontrol.categories import classify_zone, group_zones  # noqa: E402
from rgbcontrol.effects import EffectParams  # noqa: E402
from rgbcontrol.engine import EffectEngine, build_targets  # noqa: E402
from rgbcontrol.sensors import SensorHub  # noqa: E402
from rgbcontrol.sensors import battery as bat  # noqa: E402
from rgbcontrol.sensors.model import Reading  # noqa: E402
from rgbcontrol.sensors.select import pick_cpu_temp, pick_fans, pick_gpu, pick_pump  # noqa: E402


def dev(name, t, zones):
    return LightDevice(0, name, t, zones=[LightZone(i, z, 8) for i, z in enumerate(zones)])


def test_categories():
    assert classify_zone(dev("MSI RTX 4070", "GPU", ["GPU"]), "GPU") == "gpu"
    assert classify_zone(dev("Trident Z5", "DRAM", ["DRAM"]), "DRAM") == "ram"
    assert classify_zone(dev("G502", "MOUSE", ["Logo"]), "Logo") == "mouse"
    assert classify_zone(dev("K70", "KEYBOARD", ["Keys"]), "Keys") == "keyboard"
    cc = dev("Corsair Commander Core", "COOLER", ["Pump", "Fan 1", "Fan 2"])
    assert [classify_zone(cc, z.name) for z in cc.zones] == ["pump", "fans", "fans"]
    assert classify_zone(dev("NZXT Kraken Elite", "COOLER", ["Logo"]), "Logo") == "pump"
    assert classify_zone(dev("Lian Li UNI FAN SL", "COOLER", ["Channel 1"]), "Channel 1") == "fans"
    assert classify_zone(dev("ASUS B650", "MOTHERBOARD", ["Aura Addressable 1"]), "Aura Addressable 1") == "fans"
    assert classify_zone(dev("HyperX Cloud", "HEADSET", ["Logo"]), "Logo") == "other"


def test_groups_cover_every_zone():
    b = DemoBackend()
    b.connect()
    devs = b.list_devices()
    g = group_zones(devs)
    assert sum(len(v) for v in g.values()) == sum(len(d.zones) for d in devs)
    assert g["gpu"] and g["ram"] and g["keyboard"] and g["fans"]


def test_engine_groups_independent():
    b = DemoBackend()
    b.connect()
    devs = b.list_devices()
    g = group_zones(devs)
    eng = EffectEngine(b)
    eng.set_group("gpu", build_targets(devs, g["gpu"]), "static", EffectParams(color=(1, 2, 3)))
    eng.set_group("ram", build_targets(devs, g["ram"]), "static", EffectParams(color=(9, 9, 9)))
    frame = eng.frame(0, eng.groups())
    assert frame[g["gpu"][0]][0] == (1, 2, 3)
    assert frame[g["ram"][0]][0] == (9, 9, 9)
    # gleiche Zone in neuer Gruppe -> aus der alten entfernt
    eng.set_group("x", build_targets(devs, g["gpu"]), "off", EffectParams())
    assert not eng.groups()["gpu"].targets


R = Reading


def test_sensor_selection():
    rs = [R("Cpu", "Ryzen", "Temperature", "Core (Tctl/Tdie)", 55.0), R("Cpu", "Ryzen", "Temperature", "CCD1 (Tdie)", 50.0),
          R("GpuIntel", "UHD", "Temperature", "GPU Core", 40.0),
          R("GpuNvidia", "RTX", "Temperature", "GPU Hot Spot", 75.0), R("GpuNvidia", "RTX", "Temperature", "GPU Core", 65.0),
          R("GpuNvidia", "RTX", "Load", "GPU Core", 33.0), R("GpuNvidia", "RTX", "SmallData", "GPU Memory Used", 4000.0),
          R("Cooler", "Kraken", "Temperature", "Liquid", 31.0), R("Cooler", "Kraken", "Fan", "Pump", 2400.0),
          R("SuperIO", "NCT", "Fan", "Fan #1", 900.0), R("SuperIO", "NCT", "Fan", "Fan #2", 0.0)]
    assert pick_cpu_temp(rs).value == 55.0
    t, load, used, total = pick_gpu(rs)
    assert (t.value, t.hw_name, load.value, used, total) == (65.0, "RTX", 33.0, 4000.0, None)
    pt, pr = pick_pump(rs)
    assert pt.value == 31.0 and pr.value == 2400.0
    assert [f.name for f in pick_fans(rs)] == ["Fan #1"]


def test_snapshot_ram():
    s = SensorHub(enable_hardware=False).build_snapshot([])
    assert s.ram_total > 0 and 0 <= s.ram_percent <= 100


def test_hidpp():
    req = bat.hidpp_request(1, 0, 0, bytes([0x10, 0x04]))
    assert req[:6] == [0x11, 1, 0, 0x0B, 0x10, 0x04] and len(req) == 20
    resp = [0x11, 1, 0, 0x0B, 7] + [0] * 15
    assert bat.hidpp_match(resp, 1, 0, 0) == "ok"
    assert bat.hidpp_match([0x11, 1, 0xFF, 0, 0x0B, 5] + [0] * 14, 1, 0, 0) == "error"
    assert bat.hidpp_match([0x10, 2, 0x8F, 0, 0x0B, 9, 0], 2, 0, 0) == "error"
    assert bat.hidpp_match([0x11, 2, 0, 0x0B, 7] + [0] * 15, 1, 0, 0) == ""
    assert bat.parse_unified_battery([87, 4, 0, 0]) == (87, False, "")
    assert bat.parse_unified_battery([0, 8, 1, 1]) == (100, True, "Voll")
    assert bat.parse_battery_status([55, 50, 0]) == (55, False, "")
    assert bat.parse_battery_voltage([0x0F, 0xA0, 0x00])[0] == 81   # 4000 mV
    assert bat.voltage_to_percent(4300) == 100 and bat.voltage_to_percent(3400) == 0


def test_razer():
    r = bat.razer_report(0x07, 0x80, 0x02, 0x1F)
    assert len(r) == 90 and r[1] == 0x1F and r[6:8] == [0x07, 0x80]
    crc = 0
    for x in r[2:88]:
        crc ^= x
    assert r[88] == crc
    resp = [0x02, 0x1F, 0, 0, 0, 2, 0x07, 0x80, 0, 191] + [0] * 80
    assert bat.razer_parse(resp, 0x07, 0x80)[1] == 191
    assert bat.razer_parse([0] + resp, 0x07, 0x80)[1] == 191
    assert bat.razer_parse([0x05] + resp[1:], 0x07, 0x80) is None


def test_bluetooth_json():
    js = ('[{"Name":"MX Master 3S","Battery":80,"IsMouse":false},{"Name":"WH-1000XM4","Battery":40,"IsMouse":false},'
          '{"Name":"Generic BT","Battery":60,"IsMouse":true}]')
    names = [m.name for m in bat.parse_bluetooth_json(js)]
    assert names == ["MX Master 3S", "Generic BT"]
    assert bat.parse_bluetooth_json("") == []
    assert bat.parse_bluetooth_json('{"Name":"Viper V2 Pro","Battery":9,"IsMouse":false}')[0].percent == 9
