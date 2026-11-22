"""Zusammengesetzte Bausteine: Effekt-Panel, Temperatur-Karte, RAM-Karte, Akku-Karte."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QGridLayout, QHBoxLayout, QLineEdit, QSlider, QVBoxLayout, QWidget

from ..colors import PRESETS, RGB, hex_to_rgb, rgb_to_hex
from ..effects import EFFECTS, EFFECTS_BY_KEY, EffectParams
from .components import (Bar, BatteryRow, Card, ChipGroup, ColorDot, ColorWheel, Gauge, Sparkline, button,
                         icon_label, label)
from .theme import MUTED, load_color, temp_color


class EffectPanel(QWidget):
    """Effekt-Auswahl, Farbrad, Presets, Helligkeit und Tempo."""
    changed = Signal()

    def __init__(self, compact: bool = False, parent=None):
        super().__init__(parent)
        self._block = False
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(14)

        lay.addWidget(label("Effekt", "h2"))
        self.effects = ChipGroup([(e.key, e.name) for e in EFFECTS], columns=2 if not compact else 5)
        self.effects.changed.connect(self._on_effect)
        lay.addWidget(self.effects)

        self.color_box = QWidget()
        cl = QHBoxLayout(self.color_box)
        cl.setContentsMargins(0, 4, 0, 0)
        cl.setSpacing(18)
        self.wheel = ColorWheel(150 if compact else 176)
        self.wheel.colorChanged.connect(self._on_wheel)
        cl.addWidget(self.wheel, 0, Qt.AlignTop)
        right = QVBoxLayout()
        right.setSpacing(10)
        slot_row = QHBoxLayout()
        slot_row.setSpacing(8)
        self.slot1 = ColorDot((0, 120, 255), 34, "Farbe 1", checkable=True)
        self.slot2 = ColorDot((255, 0, 160), 34, "Farbe 2", checkable=True)
        self.slot1.setChecked(True)
        self.slot1.clicked.connect(lambda: self._select_slot(1))
        self.slot2.clicked.connect(lambda: self._select_slot(2))
        self.l_slot2 = label("Farbe 2", "muted")
        slot_row.addWidget(self.slot1)
        slot_row.addWidget(label("Farbe 1", "muted"))
        slot_row.addSpacing(8)
        slot_row.addWidget(self.slot2)
        slot_row.addWidget(self.l_slot2)
        slot_row.addStretch()
        right.addLayout(slot_row)
        self.hex = QLineEdit()
        self.hex.setMaxLength(7)
        self.hex.setFixedWidth(110)
        self.hex.editingFinished.connect(self._on_hex)
        right.addWidget(self.hex)
        grid = QGridLayout()
        grid.setSpacing(4)
        for i, (name, c) in enumerate(PRESETS):
            d = ColorDot(c, 28, name)
            d.clicked.connect(lambda _=False, c=c: self._set_slot_color(c))
            grid.addWidget(d, i // 6, i % 6)
        right.addLayout(grid)
        right.addStretch()
        cl.addLayout(right, 1)
        lay.addWidget(self.color_box)

        self.bright = QSlider(Qt.Horizontal)
        self.bright.setRange(0, 100)
        self.bright.setValue(100)
        self.speed = QSlider(Qt.Horizontal)
        self.speed.setRange(1, 50)
        self.speed.setValue(10)
        self.l_bright, self.l_speed = label("100 %", "muted"), label("1.0×", "muted")
        self.speed_row = QWidget()
        for title, slider, lab, holder in (("Helligkeit", self.bright, self.l_bright, None),
                                           ("Tempo", self.speed, self.l_speed, self.speed_row)):
            row = QHBoxLayout(holder) if holder else QHBoxLayout()
            row.setContentsMargins(0, 0, 0, 0)
            t = label(title, "")
            t.setFixedWidth(84)
            lab.setFixedWidth(48)
            lab.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            row.addWidget(t)
            row.addWidget(slider, 1)
            row.addWidget(lab)
            if holder:
                lay.addWidget(holder)
            else:
                lay.addLayout(row)
        self.bright.valueChanged.connect(self._on_slider)
        self.speed.valueChanged.connect(self._on_slider)
        self.effects.set_current("static")
        self._select_slot(1)
        self._update_enabled()

    # ---------------------------------------------------------- intern
    def _slot(self) -> ColorDot:
        return self.slot2 if self.slot2.isChecked() else self.slot1

    def _select_slot(self, n: int):
        self.slot1.setChecked(n == 1)
        self.slot2.setChecked(n == 2)
        c = self._slot().c
        self.wheel.set_color(c)
        self.hex.setText(rgb_to_hex(c))

    def _set_slot_color(self, c: RGB, from_wheel: bool = False):
        self._slot().set_color(c)
        if not from_wheel:
            self.wheel.set_color(c)
        self.hex.setText(rgb_to_hex(c))
        self._emit()

    def _on_wheel(self, c):
        self._set_slot_color(c, True)

    def _on_hex(self):
        try:
            self._set_slot_color(hex_to_rgb(self.hex.text()))
        except ValueError:
            self.hex.setText(rgb_to_hex(self._slot().c))

    def _on_slider(self, *_):
        self.l_bright.setText(f"{self.bright.value()} %")
        self.l_speed.setText(f"{self.speed.value() / 10:.1f}×")
        self._emit()

    def _on_effect(self, *_):
        self._update_enabled()
        self._emit()

    def _update_enabled(self):
        e = EFFECTS_BY_KEY.get(self.effects.current() or "static")
        self.color_box.setVisible(e.uses_color or e.uses_color2)
        self.slot2.setVisible(e.uses_color2)
        self.l_slot2.setVisible(e.uses_color2)
        if not e.uses_color2 and self.slot2.isChecked():
            self._select_slot(1)
        self.speed_row.setVisible(e.animated)

    def _emit(self):
        if not self._block:
            self.changed.emit()

    # ---------------------------------------------------------- API
    def effect(self) -> str:
        return self.effects.current() or "static"

    def params(self) -> EffectParams:
        return EffectParams(self.slot1.c, self.slot2.c, self.speed.value() / 10, self.bright.value() / 100)

    def set_state(self, effect: str, p: EffectParams):
        self._block = True
        self.effects.set_current(effect)
        self.slot1.set_color(tuple(p.color))
        self.slot2.set_color(tuple(p.color2))
        self.bright.setValue(int(round(p.brightness * 100)))
        self.speed.setValue(int(round(p.speed * 10)))
        self._select_slot(1)
        self._update_enabled()
        self._on_slider()
        self._block = False


# ===================================================================== Karten
class TempCard(Card):
    def __init__(self, title: str, icon_name: str, accent: str, parent=None):
        super().__init__(accent=accent, parent=parent)
        head = QHBoxLayout()
        head.setSpacing(8)
        head.addWidget(icon_label(icon_name, accent, 18))
        head.addWidget(label(title, "h2"))
        head.addStretch()
        self.lay.addLayout(head)
        self.sub = label("", "faint")
        self.lay.addWidget(self.sub)
        self.gauge = Gauge("°C", 100, 128)
        self.lay.addWidget(self.gauge, 0, Qt.AlignHCenter)
        self.extra = label("", "muted")
        self.extra.setAlignment(Qt.AlignCenter)
        self.lay.addWidget(self.extra)
        self.spark = Sparkline(accent)
        self.lay.addWidget(self.spark)

    def update_value(self, temp: float | None, sub: str = "", extra: str = "", missing: str = "nicht verfügbar"):
        c = temp_color(temp)
        self.gauge.set_value(temp, c, "°C" if temp is not None else missing)
        self.sub.setText(sub or " ")
        self.extra.setText(extra)
        self.spark.add(temp, c if temp is not None else None)


class RamCard(Card):
    def __init__(self, accent: str, parent=None):
        super().__init__(accent=accent, parent=parent)
        head = QHBoxLayout()
        head.setSpacing(8)
        head.addWidget(icon_label("ram", accent, 18))
        head.addWidget(label("Arbeitsspeicher", "h2"))
        head.addStretch()
        self.lay.addLayout(head)
        self.sub = label("in Benutzung", "faint")
        self.lay.addWidget(self.sub)
        self.gauge = Gauge("%", 100, 128)
        self.lay.addWidget(self.gauge, 0, Qt.AlignHCenter)
        self.amount = label("", "")
        self.amount.setAlignment(Qt.AlignCenter)
        self.amount.setStyleSheet("font-weight: 600;")
        self.lay.addWidget(self.amount)
        self.bar = Bar()
        self.lay.addWidget(self.bar)

    def update_value(self, used: int, total: int, pct: float):
        gb = 1024 ** 3
        c = load_color(pct)
        self.gauge.set_value(pct, c, "% belegt")
        self.amount.setText(f"{used / gb:.1f} GB von {total / gb:.1f} GB")
        self.bar.set_value(pct / 100, c)
        self.sub.setText(f"{(total - used) / gb:.1f} GB frei")


class BatteryCard(Card):
    refreshRequested = Signal()

    def __init__(self, accent: str, parent=None):
        super().__init__(accent=accent, parent=parent)
        head = QHBoxLayout()
        head.setSpacing(8)
        head.addWidget(icon_label("mouse", accent, 18))
        head.addWidget(label("Maus-Akku", "h2"))
        head.addStretch()
        b = button("", "ghost", "refresh", "Akkustand jetzt neu lesen")
        b.setFixedSize(34, 30)
        b.clicked.connect(self.refreshRequested.emit)
        head.addWidget(b)
        self.lay.addLayout(head)
        self.rows_box = QVBoxLayout()
        self.rows_box.setSpacing(6)
        self.lay.addLayout(self.rows_box)
        self.empty = label("Keine kabellose Maus mit Akku-Anzeige gefunden.\nUnterstützt: Bluetooth-Mäuse, "
                           "Logitech (Lightspeed/Unifying/Bolt) und Razer (HyperSpeed).", "faint", wrap=True)
        self.lay.addWidget(self.empty)
        self.lay.addStretch()
        self.rows: list[BatteryRow] = []

    def update_mice(self, mice):
        while len(self.rows) < len(mice):
            r = BatteryRow()
            self.rows.append(r)
            self.rows_box.addWidget(r)
        for i, r in enumerate(self.rows):
            r.setVisible(i < len(mice))
            if i < len(mice):
                r.set_mouse(mice[i])
        self.empty.setVisible(not mice)


def stat_line(title: str, value: str = "–") -> tuple[QWidget, object]:
    w = QWidget()
    lay = QHBoxLayout(w)
    lay.setContentsMargins(0, 0, 0, 0)
    t = label(title, "muted")
    v = label(value, "")
    v.setStyleSheet("font-weight: 600;")
    v.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
    lay.addWidget(t)
    lay.addStretch()
    lay.addWidget(v)
    return w, v


def muted_color() -> str:
    return MUTED
