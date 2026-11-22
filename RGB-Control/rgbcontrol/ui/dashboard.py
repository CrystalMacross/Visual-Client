"""Dashboard: Temperaturen, RAM, Maus-Akku und alle Kategorien auf einen Blick."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QGridLayout, QHBoxLayout, QScrollArea, QVBoxLayout, QWidget

from ..categories import CATEGORIES, Category
from ..colors import PRESETS
from ..effects import EffectParams
from .components import Card, ChipGroup, ColorDot, IconBadge, LedStrip, Toggle, button, icon_label, label
from .panels import BatteryCard, RamCard, TempCard
from .theme import ACCENT, WARN

QUICK_EFFECTS = [("static", "Statisch"), ("breathing", "Atmen"), ("rainbow_wave", "Regenbogen-Welle"),
                 ("rainbow", "Regenbogen"), ("wave", "Welle"), ("comet", "Komet"), ("off", "Aus")]


class CategoryTile(Card):
    toggled = Signal(str, bool)

    def __init__(self, cat: Category, parent=None):
        super().__init__(accent=cat.accent, radius=18, padding=16, clickable=True, parent=parent)
        self.cat = cat
        head = QHBoxLayout()
        head.setSpacing(12)
        head.addWidget(IconBadge(cat.icon, cat.accent, 40))
        col = QVBoxLayout()
        col.setSpacing(0)
        t = label(cat.name, "h2")
        self.info = label("", "faint")
        col.addWidget(t)
        col.addWidget(self.info)
        head.addLayout(col, 1)
        self.toggle = Toggle(True, cat.accent)
        self.toggle.toggled.connect(lambda on: self.toggled.emit(cat.key, on))
        head.addWidget(self.toggle, 0, Qt.AlignTop)
        self.lay.addLayout(head)
        self.strip = LedStrip(1, 5)
        self.lay.addWidget(self.strip)

    def set_info(self, devices: int, leds: int, effect_name: str):
        if devices:
            self.info.setText(f"{devices} Gerät{'e' if devices != 1 else ''} · {leds} LEDs · {effect_name}")
        else:
            self.info.setText("keine Geräte gefunden")
        self.toggle.setVisible(devices > 0)
        self.strip.setVisible(devices > 0)

    def set_enabled(self, on: bool):
        self.toggle.blockSignals(True)
        self.toggle.setChecked(on)
        self.toggle.blockSignals(False)


class Dashboard(QWidget):
    openCategory = Signal(str)
    categoryToggled = Signal(str, bool)
    syncAll = Signal(str, object)       # effect, EffectParams
    refreshBatteries = Signal()
    noteAction = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
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

        # Hinweise (z.B. PawnIO fehlt)
        self.note = Card(accent=WARN, radius=14, padding=12)
        nl = QHBoxLayout()
        nl.addWidget(icon_label("alert", WARN, 18))
        self.note_text = label("", "", wrap=True)
        nl.addWidget(self.note_text, 1)
        self.note.lay.addLayout(nl)
        self.note.hide()
        lay.addWidget(self.note)

        # Sensoren
        row = QGridLayout()
        row.setSpacing(16)
        self.cpu = TempCard("CPU", "cpu", "#FF7A45")
        self.gpu = TempCard("GPU", "gpu", "#7C5CFF")
        self.pump = TempCard("Water Pump", "pump", "#3D8BFF")
        self.ram = RamCard("#00C2FF")
        for i, w in enumerate((self.cpu, self.gpu, self.pump, self.ram)):
            row.addWidget(w, 0, i)
            row.setColumnStretch(i, 1)
        lay.addLayout(row)

        # Akku + Schnell-Sync
        row2 = QGridLayout()
        row2.setSpacing(16)
        self.battery = BatteryCard("#FF4D9D")
        self.battery.refreshRequested.connect(self.refreshBatteries.emit)
        row2.addWidget(self.battery, 0, 0)

        sync = Card(accent=ACCENT)
        sh = QHBoxLayout()
        sh.setSpacing(8)
        sh.addWidget(icon_label("sync", ACCENT, 18))
        sh.addWidget(label("Alles synchronisieren", "h2"))
        sh.addStretch()
        sync.lay.addLayout(sh)
        sync.lay.addWidget(label("Ein Effekt für alle Kategorien – GPU, RAM, Maus, Tastatur, Lüfter und Pumpe.", "faint",
                                 wrap=True))
        self.quick = ChipGroup(QUICK_EFFECTS, columns=4)
        self.quick.set_current("rainbow_wave")
        sync.lay.addWidget(self.quick)
        cr = QHBoxLayout()
        cr.setSpacing(4)
        self.quick_color = (0, 120, 255)
        self.dots: list[ColorDot] = []
        for name, c in PRESETS[:-1]:
            d = ColorDot(c, 28, name, checkable=True)
            d.clicked.connect(lambda _=False, c=c, d=d: self._pick_quick(c, d))
            self.dots.append(d)
            cr.addWidget(d)
        cr.addStretch()
        sync.lay.addLayout(cr)
        b = button("Auf alles anwenden", "primary", "check")
        b.clicked.connect(self._sync)
        sync.lay.addWidget(b, 0, Qt.AlignLeft)
        row2.addWidget(sync, 0, 1)
        row2.setColumnStretch(0, 2)
        row2.setColumnStretch(1, 3)
        lay.addLayout(row2)

        # Kategorien
        lay.addWidget(label("Beleuchtung", "h2"))
        tiles = QGridLayout()
        tiles.setSpacing(16)
        self.tiles: dict[str, CategoryTile] = {}
        for i, cat in enumerate(CATEGORIES):
            t = CategoryTile(cat)
            t.clicked.connect(lambda k=cat.key: self.openCategory.emit(k))
            t.toggled.connect(self.categoryToggled.emit)
            self.tiles[cat.key] = t
            tiles.addWidget(t, i // 3, i % 3)
        for c in range(3):
            tiles.setColumnStretch(c, 1)
        lay.addLayout(tiles)
        lay.addStretch()

    def _pick_quick(self, c, d):
        self.quick_color = c
        for x in self.dots:
            x.setChecked(x is d)

    def _sync(self):
        self.syncAll.emit(self.quick.current() or "static",
                          EffectParams(color=self.quick_color, color2=(255, 0, 160)))

    def update_snapshot(self, s):
        self.cpu.update_value(s.cpu_temp, s.cpu_name,
                              "" if s.cpu_load is None else f"Auslastung {s.cpu_load:.0f} %")
        gpu_extra = []
        if s.gpu_load is not None:
            gpu_extra.append(f"Auslastung {s.gpu_load:.0f} %")
        if s.vram_used_mb is not None:
            gpu_extra.append(f"VRAM {s.vram_used_mb / 1024:.1f} GB")
        self.gpu.update_value(s.gpu_temp, s.gpu_name, " · ".join(gpu_extra))
        self.pump.update_value(s.pump_temp, s.pump_name or "Flüssigkeitstemperatur",
                               "" if s.pump_rpm is None else f"Pumpe {s.pump_rpm:.0f} U/min",
                               "keine AIO erkannt")
        if s.ram_total:
            self.ram.update_value(s.ram_used, s.ram_total, s.ram_percent)
        self.battery.update_mice(s.mice)
        notes = list(dict.fromkeys(s.notes))
        self.note.setVisible(bool(notes))
        self.note_text.setText("  ·  ".join(notes))

    def update_preview(self, frame, zones_by_cat):
        for key, tile in self.tiles.items():
            cols = []
            for dz in zones_by_cat.get(key, []):
                cols += frame.get(dz, [])
            if len(cols) > 60:
                step = len(cols) / 60
                cols = [cols[int(i * step)] for i in range(60)]
            tile.strip.set_colors(cols)
