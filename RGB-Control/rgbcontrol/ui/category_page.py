"""Seite einer Kategorie (GPU, RAM, Mouse, Keyboard, Fans, Water Pump)."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QComboBox, QGridLayout, QHBoxLayout, QInputDialog, QMenu, QPushButton, QScrollArea,
                               QSizePolicy, QVBoxLayout, QWidget)

from ..backend import LightDevice
from ..categories import Category
from ..effects import EffectParams
from .components import (Bar, BatteryRow, Card, Gauge, IconBadge, LedStrip, Sparkline, Toggle, button, label)
from .panels import EffectPanel, stat_line
from .theme import load_color, temp_color

EMPTY_HINTS = {
    "gpu": "Keine Grafikkarte mit steuerbarer Beleuchtung gefunden.",
    "ram": "Kein RGB-Arbeitsspeicher gefunden. RAM wird nur mit Administratorrechten erkannt.",
    "mouse": "Keine RGB-Maus gefunden.",
    "keyboard": "Keine RGB-Tastatur gefunden.",
    "fans": "Keine Lüfter oder ARGB-Anschlüsse gefunden.",
    "pump": "Keine Wasserkühlung (AIO-Pumpe) mit Beleuchtung gefunden.",
    "other": "Keine weiteren Geräte.",
}


class DeviceCard(Card):
    zonesChanged = Signal()
    hardwareModeRequested = Signal(int, str, bool)
    resizeRequested = Signal(int, int, int)

    def __init__(self, dev: LightDevice, zones: list[int], accent: str, parent=None):
        super().__init__(radius=14, padding=14, parent=parent)
        self.dev, self.zone_ids = dev, zones
        head = QHBoxLayout()
        head.setSpacing(10)
        col = QVBoxLayout()
        col.setSpacing(0)
        t = label(dev.name, "")
        t.setStyleSheet("font-weight: 650; font-size: 10.5pt;")
        col.addWidget(t)
        leds = sum(dev.zones[z].led_count for z in zones)
        col.addWidget(label(" · ".join(x for x in (dev.vendor, dev.type_name, f"{leds} LEDs") if x), "faint"))
        head.addLayout(col, 1)
        self.lay.addLayout(head)

        self.zone_buttons: dict[int, QPushButton] = {}
        if len(zones) > 1 or any(dev.zones[z].resizable or dev.zones[z].led_count == 0 for z in zones):
            zl = QHBoxLayout()
            zl.setSpacing(6)
            for z in zones:
                zone = dev.zones[z]
                b = QPushButton(f"{zone.name}  {zone.led_count}")
                b.setObjectName("chip")
                b.setCheckable(True)
                b.setChecked(True)
                b.setCursor(Qt.PointingHandCursor)
                b.setToolTip("Zone an/aus. Rechtsklick: LED-Anzahl festlegen" if zone.resizable else "Zone an/aus")
                b.toggled.connect(self.zonesChanged.emit)
                b.setContextMenuPolicy(Qt.CustomContextMenu)
                b.customContextMenuRequested.connect(lambda pos, z=z, b=b: self._zone_menu(z, b, pos))
                self.zone_buttons[z] = b
                zl.addWidget(b)
            zl.addStretch()
            self.lay.addLayout(zl)

        self.strip = LedStrip(1, 6)
        self.lay.addWidget(self.strip)

        if dev.modes:
            hw = QHBoxLayout()
            hw.setSpacing(6)
            self.mode = QComboBox()
            self.mode.addItems(dev.modes)
            if 0 <= dev.active_mode < len(dev.modes):
                self.mode.setCurrentIndex(dev.active_mode)
            self.mode.setToolTip("Eingebaute Modi des Geräts. Sie laufen auch ohne diese App.")
            b_set = button("Hardware-Modus", "ghost")
            b_set.setToolTip("Gerät auf seinen eigenen Modus umschalten (wird aus der Software-Steuerung genommen)")
            b_save = button("Im Gerät speichern", "ghost")
            b_save.setToolTip("Modus dauerhaft im Gerät speichern (nicht jedes Gerät kann das)")
            b_set.clicked.connect(lambda: self.hardwareModeRequested.emit(dev.index, self.mode.currentText(), False))
            b_save.clicked.connect(lambda: self.hardwareModeRequested.emit(dev.index, self.mode.currentText(), True))
            hw.addWidget(self.mode, 1)
            hw.addWidget(b_set)
            hw.addWidget(b_save)
            self.lay.addLayout(hw)

    def _zone_menu(self, z, b, pos):
        zone = self.dev.zones[z]
        m = QMenu(self)
        act = m.addAction("LED-Anzahl festlegen …")
        act.setEnabled(zone.resizable)
        if m.exec(b.mapToGlobal(pos)) == act:
            n, ok = QInputDialog.getInt(self, "LED-Anzahl", f"Wie viele LEDs hängen an „{zone.name}“?\n"
                                        "(z. B. 3 Lüfter × 16 LEDs = 48)", max(zone.led_count, zone.leds_min),
                                        zone.leds_min, zone.leds_max)
            if ok:
                self.resizeRequested.emit(self.dev.index, z, n)

    def disabled_zones(self) -> set[int]:
        return {z for z, b in self.zone_buttons.items() if not b.isChecked()}

    def set_disabled_zones(self, zones: set[int]):
        for z, b in self.zone_buttons.items():
            b.blockSignals(True)
            b.setChecked(z not in zones)
            b.blockSignals(False)


class CategoryPage(QWidget):
    stateChanged = Signal(str)
    copyToAll = Signal(str)
    hardwareModeRequested = Signal(int, str, bool)
    resizeRequested = Signal(int, int, int)
    rescanRequested = Signal()
    scanRequested = Signal()

    def __init__(self, cat: Category, parent=None):
        super().__init__(parent)
        self.cat = cat
        self.devices: list[LightDevice] = []
        self.cards: list[DeviceCard] = []
        self._block = False

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        outer.addWidget(scroll)
        body = QWidget()
        scroll.setWidget(body)
        lay = QVBoxLayout(body)
        lay.setContentsMargins(28, 4, 28, 28)
        lay.setSpacing(16)

        # ------------------------------------------------ Hero
        hero = Card(accent=cat.accent, radius=22, padding=22)
        hl = QHBoxLayout()
        hl.setSpacing(18)
        hl.addWidget(IconBadge(cat.icon, cat.accent, 64), 0, Qt.AlignTop)
        tcol = QVBoxLayout()
        tcol.setSpacing(4)
        self.title = label(cat.name, "pageTitle")
        self.subtitle = label("", "muted", wrap=True)
        tcol.addWidget(self.title)
        tcol.addWidget(self.subtitle)
        trow = QHBoxLayout()
        trow.setSpacing(10)
        self.toggle = Toggle(True, cat.accent)
        self.toggle.toggled.connect(self._changed)
        trow.addWidget(self.toggle)
        self.toggle_label = label("Beleuchtung an", "")
        trow.addWidget(self.toggle_label)
        trow.addSpacing(14)
        b_all = button("Auf alle Kategorien übertragen", "ghost", "sync")
        b_all.clicked.connect(lambda: self.copyToAll.emit(cat.key))
        trow.addWidget(b_all)
        trow.addStretch()
        tcol.addSpacing(10)
        tcol.addLayout(trow)
        tcol.addStretch()
        hl.addLayout(tcol, 1)
        self.stats = QWidget()
        self.stats.setMinimumWidth(260)
        self.stats_lay = QVBoxLayout(self.stats)
        self.stats_lay.setContentsMargins(0, 0, 0, 0)
        self.stats_lay.setSpacing(6)
        self._build_stats()
        hl.addWidget(self.stats)
        hero.lay.addLayout(hl)
        lay.addWidget(hero)

        # ------------------------------------------------ Inhalt
        grid = QGridLayout()
        grid.setHorizontalSpacing(16)
        grid.setVerticalSpacing(16)
        left = QVBoxLayout()
        left.setSpacing(12)
        dh = QHBoxLayout()
        dh.addWidget(label("Geräte", "h2"))
        dh.addStretch()
        b_scan = button("Neu scannen", "ghost", "refresh")
        b_scan.clicked.connect(self.rescanRequested.emit)
        dh.addWidget(b_scan)
        left.addLayout(dh)
        self.dev_box = QVBoxLayout()
        self.dev_box.setSpacing(12)
        left.addLayout(self.dev_box)
        self.empty = Card(radius=14)
        self.empty_text = label(EMPTY_HINTS.get(cat.key, ""), "muted", wrap=True)
        self.empty.lay.addWidget(self.empty_text)
        self.empty.lay.addWidget(label("Tipps: App als Administrator starten · andere RGB-Programme beenden "
                                       "(Seite „Andere Apps“) · danach „Neu scannen“.", "faint", wrap=True))
        er = QHBoxLayout()
        b1 = button("Neu scannen", "primary", "refresh")
        b1.clicked.connect(self.rescanRequested.emit)
        b2 = button("Hardware-Scan", "ghost", "scan")
        b2.clicked.connect(self.scanRequested.emit)
        er.addWidget(b1)
        er.addWidget(b2)
        er.addStretch()
        self.empty.lay.addLayout(er)
        left.addWidget(self.empty)
        left.addStretch()
        leftw = QWidget()
        leftw.setLayout(left)
        leftw.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        self.effect_card = Card(radius=18)
        self.panel = EffectPanel()
        self.panel.changed.connect(self._changed)
        self.effect_card.lay.addWidget(self.panel)
        self.effect_card.setMinimumWidth(390)
        self.effect_card.setMaximumWidth(470)

        grid.addWidget(leftw, 0, 0, Qt.AlignTop)
        grid.addWidget(self.effect_card, 0, 1, Qt.AlignTop)
        grid.setColumnStretch(0, 1)
        lay.addLayout(grid)
        lay.addStretch()

    # ------------------------------------------------ Statistik im Kopf
    def _build_stats(self):
        k = self.cat.key
        sl = self.stats_lay
        self.gauge = self.spark = self.bar = None
        self.lines = {}
        if k in ("gpu", "pump", "ram"):
            row = QHBoxLayout()
            self.gauge = Gauge("%" if k == "ram" else "°C", 100, 112, 9)
            row.addWidget(self.gauge)
            col = QVBoxLayout()
            col.setSpacing(4)
            names = {"gpu": ["Temperatur", "Auslastung", "VRAM"], "pump": ["Flüssigkeit", "Pumpe", "Gerät"],
                     "ram": ["Belegt", "Frei", "Gesamt"]}[k]
            for n in names:
                w, v = stat_line(n)
                self.lines[n] = v
                col.addWidget(w)
            col.addStretch()
            row.addLayout(col, 1)
            sl.addLayout(row)
            if k == "ram":
                self.bar = Bar()
                sl.addWidget(self.bar)
            else:
                self.spark = Sparkline(self.cat.accent)
                sl.addWidget(self.spark)
        elif k == "mouse":
            sl.addWidget(label("Akku", "muted"))
            self.battery_rows: list[BatteryRow] = []
            self.battery_box = QVBoxLayout()
            sl.addLayout(self.battery_box)
            self.no_battery = label("Keine kabellose Maus erkannt", "faint")
            sl.addWidget(self.no_battery)
        elif k == "fans":
            sl.addWidget(label("Drehzahlen", "muted"))
            self.fan_box = QVBoxLayout()
            self.fan_box.setSpacing(2)
            sl.addLayout(self.fan_box)
            self.fan_lines: list = []
            self.no_fans = label("Keine Lüfter-Sensoren", "faint")
            sl.addWidget(self.no_fans)
        else:
            for n in ("Geräte", "LEDs"):
                w, v = stat_line(n)
                self.lines[n] = v
                sl.addWidget(w)
        sl.addStretch()

    def update_snapshot(self, s):
        k = self.cat.key
        if k == "gpu":
            self.gauge.set_value(s.gpu_temp, temp_color(s.gpu_temp), "°C" if s.gpu_temp is not None else "keine Daten")
            self.spark.add(s.gpu_temp, temp_color(s.gpu_temp) if s.gpu_temp is not None else None)
            self.lines["Temperatur"].setText("–" if s.gpu_temp is None else f"{s.gpu_temp:.0f} °C")
            self.lines["Auslastung"].setText("–" if s.gpu_load is None else f"{s.gpu_load:.0f} %")
            if s.vram_used_mb is not None and s.vram_total_mb:
                self.lines["VRAM"].setText(f"{s.vram_used_mb / 1024:.1f} / {s.vram_total_mb / 1024:.0f} GB")
            elif s.vram_used_mb is not None:
                self.lines["VRAM"].setText(f"{s.vram_used_mb / 1024:.1f} GB")
        elif k == "pump":
            self.gauge.set_value(s.pump_temp, temp_color(s.pump_temp), "°C" if s.pump_temp is not None else "keine Daten")
            self.spark.add(s.pump_temp, temp_color(s.pump_temp) if s.pump_temp is not None else None)
            self.lines["Flüssigkeit"].setText("–" if s.pump_temp is None else f"{s.pump_temp:.1f} °C")
            self.lines["Pumpe"].setText("–" if s.pump_rpm is None else f"{s.pump_rpm:.0f} U/min")
            self.lines["Gerät"].setText(s.pump_name or "–")
        elif k == "ram":
            gb = 1024 ** 3
            self.gauge.set_value(s.ram_percent, load_color(s.ram_percent), "% belegt")
            self.lines["Belegt"].setText(f"{s.ram_used / gb:.1f} GB")
            self.lines["Frei"].setText(f"{(s.ram_total - s.ram_used) / gb:.1f} GB")
            self.lines["Gesamt"].setText(f"{s.ram_total / gb:.1f} GB")
            self.bar.set_value(s.ram_percent / 100, load_color(s.ram_percent))
        elif k == "mouse":
            while len(self.battery_rows) < len(s.mice):
                r = BatteryRow()
                self.battery_rows.append(r)
                self.battery_box.addWidget(r)
            for i, r in enumerate(self.battery_rows):
                r.setVisible(i < len(s.mice))
                if i < len(s.mice):
                    r.set_mouse(s.mice[i])
            self.no_battery.setVisible(not s.mice)
        elif k == "fans":
            fans = s.fans[:6]
            while len(self.fan_lines) < len(fans):
                w, v = stat_line("")
                self.fan_lines.append((w, v))
                self.fan_box.addWidget(w)
            for i, (w, v) in enumerate(self.fan_lines):
                w.setVisible(i < len(fans))
                if i < len(fans):
                    w.layout().itemAt(0).widget().setText(fans[i].name)
                    v.setText(f"{fans[i].rpm:.0f} U/min")
            self.no_fans.setVisible(not fans)

    # ------------------------------------------------ Geräte
    def set_devices(self, devices: list[LightDevice], zones: list[tuple[int, int]]):
        self.devices = devices
        for c in self.cards:
            c.setParent(None)
            c.deleteLater()
        self.cards = []
        by_dev: dict[int, list[int]] = {}
        for d, z in zones:
            by_dev.setdefault(d, []).append(z)
        idx = {d.index: d for d in devices}
        for d, zs in by_dev.items():
            card = DeviceCard(idx[d], zs, self.cat.accent)
            card.zonesChanged.connect(self._changed)
            card.hardwareModeRequested.connect(self.hardwareModeRequested.emit)
            card.resizeRequested.connect(self.resizeRequested.emit)
            self.dev_box.addWidget(card)
            self.cards.append(card)
        has = bool(self.cards)
        self.empty.setVisible(not has)
        self.effect_card.setVisible(has)
        n_led = sum(idx[d].zones[z].led_count for d, z in zones)
        names = sorted({idx[d].name for d, _ in zones})
        self.subtitle.setText(f"{len(by_dev)} Gerät{'e' if len(by_dev) != 1 else ''} · {n_led} LEDs"
                              + (f"  —  {', '.join(names[:3])}{' …' if len(names) > 3 else ''}" if names else "")
                              if has else EMPTY_HINTS.get(self.cat.key, ""))
        if "Geräte" in self.lines:
            self.lines["Geräte"].setText(str(len(by_dev)))
            self.lines["LEDs"].setText(str(n_led))

    def update_preview(self, frame):
        for c in self.cards:
            c.strip.set_colors([col for z in c.zone_ids for col in frame.get((c.dev.index, z), [])])

    # ------------------------------------------------ Zustand
    def _changed(self, *_):
        self.toggle_label.setText("Beleuchtung an" if self.toggle.isChecked() else "Beleuchtung aus")
        if not self._block:
            self.stateChanged.emit(self.cat.key)

    def state(self) -> dict:
        disabled = []
        for c in self.cards:
            for z in c.disabled_zones():
                disabled.append([c.dev.key, c.dev.zones[z].name])
        return {"enabled": self.toggle.isChecked(), "effect": self.panel.effect(), "params": self.panel.params(),
                "disabled": disabled}

    def set_state(self, enabled: bool, effect: str, params: EffectParams, disabled: set[tuple[str, str]]):
        self._block = True
        self.toggle.blockSignals(True)
        self.toggle.setChecked(enabled)
        self.toggle.blockSignals(False)
        self.toggle_label.setText("Beleuchtung an" if enabled else "Beleuchtung aus")
        self.panel.set_state(effect, params)
        for c in self.cards:
            c.set_disabled_zones({z for z in c.zone_ids if (c.dev.key, c.dev.zones[z].name) in disabled})
        self._block = False
