"""Die einzelnen Seiten der App."""
from __future__ import annotations

import webbrowser

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QComboBox, QFileDialog, QFormLayout, QGridLayout,
                               QGroupBox, QHBoxLayout, QHeaderView, QInputDialog, QLabel, QLineEdit, QListWidget,
                               QMenu, QMessageBox, QPushButton, QScrollArea, QSlider, QSpinBox, QSplitter,
                               QTableWidget, QTableWidgetItem, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget)

from .. import APP_NAME, APP_VERSION, conflicts, hwscan
from ..backend import LightDevice
from ..colors import PRESETS
from ..effects import EFFECTS, EFFECTS_BY_KEY, EffectParams
from ..openrgb_service import DOWNLOAD_URL
from .async_task import run_async
from .widgets import ColorButton, LedPreview, Swatch


def page_frame(title: str, subtitle: str) -> tuple[QWidget, QVBoxLayout]:
    w = QWidget()
    w.setObjectName("page")
    lay = QVBoxLayout(w)
    lay.setContentsMargins(24, 20, 24, 16)
    t = QLabel(title)
    t.setObjectName("title")
    s = QLabel(subtitle)
    s.setObjectName("subtitle")
    s.setWordWrap(True)
    lay.addWidget(t)
    lay.addWidget(s)
    return w, lay


def button(text: str, obj: str = "", tip: str = "") -> QPushButton:
    b = QPushButton(text)
    if obj:
        b.setObjectName(obj)
    if tip:
        b.setToolTip(tip)
    b.setCursor(Qt.PointingHandCursor)
    return b


# =====================================================================
class LightingPage(QWidget):
    applyRequested = Signal()
    rescanRequested = Signal()
    hardwareModeRequested = Signal(int, str, bool)   # device, mode, im Gerät speichern
    resizeRequested = Signal(int, int, int)          # device, zone, größe

    def __init__(self, parent=None):
        super().__init__(parent)
        self.devices: list[LightDevice] = []
        self._building = False
        page, lay = page_frame("Beleuchtung", "Alle erkannten RGB-Geräte an einem Ort. Geräte/Zonen anhaken, "
                                              "Effekt und Farbe wählen - fertig.")
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(page)

        split = QSplitter(Qt.Horizontal)
        lay.addWidget(split, 1)

        # ---------- links: Geräte
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 8, 8, 0)
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["Gerät / Zone", "LEDs", "Typ"])
        self.tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tree.header().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(2, QHeaderView.Interactive)
        self.tree.setColumnWidth(2, 110)
        self.tree.setContextMenuPolicy(Qt.CustomContextMenu)
        self.tree.customContextMenuRequested.connect(self._context_menu)
        self.tree.itemChanged.connect(self._item_changed)
        self.tree.currentItemChanged.connect(lambda *_: self._update_hw_box())
        ll.addWidget(self.tree, 1)
        row = QHBoxLayout()
        b_all, b_none = button("Alle"), button("Keine")
        b_scan = button("⟳ Neu scannen", tip="Geräteliste vom OpenRGB-Dienst neu laden")
        b_all.clicked.connect(lambda: self.set_all_checked(True))
        b_none.clicked.connect(lambda: self.set_all_checked(False))
        b_scan.clicked.connect(self.rescanRequested.emit)
        for b in (b_all, b_none, b_scan):
            row.addWidget(b)
        ll.addLayout(row)
        self.count_label = QLabel("")
        self.count_label.setObjectName("subtitle")
        ll.addWidget(self.count_label)
        split.addWidget(left)

        # ---------- rechts: Steuerung
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        right = QWidget()
        right.setObjectName("page")
        rl = QVBoxLayout(right)
        rl.setContentsMargins(8, 0, 4, 0)
        scroll.setWidget(right)
        split.addWidget(scroll)
        split.setSizes([460, 600])

        eff_box = QGroupBox("Effekt (Software-Sync über alle ausgewählten Geräte)")
        g = QGridLayout(eff_box)
        self.effect = QComboBox()
        for e in EFFECTS:
            self.effect.addItem(e.name, e.key)
        self.effect.currentIndexChanged.connect(self._effect_changed)
        g.addWidget(QLabel("Effekt"), 0, 0)
        g.addWidget(self.effect, 0, 1, 1, 3)

        self.color1, self.color2 = ColorButton((0, 120, 255)), ColorButton((255, 0, 160))
        self.l_color1, self.l_color2 = QLabel("Farbe 1"), QLabel("Farbe 2")
        g.addWidget(self.l_color1, 1, 0)
        g.addWidget(self.color1, 1, 1)
        g.addWidget(self.l_color2, 1, 2)
        g.addWidget(self.color2, 1, 3)

        sw = QHBoxLayout()
        sw.setSpacing(6)
        for name, c in PRESETS:
            s = Swatch(name, c)
            s.picked.connect(lambda col: self.color1.setColor(col))
            sw.addWidget(s)
        sw.addStretch()
        g.addWidget(QLabel("Schnell"), 2, 0)
        g.addLayout(sw, 2, 1, 1, 3)

        self.brightness = QSlider(Qt.Horizontal)
        self.brightness.setRange(0, 100)
        self.brightness.setValue(100)
        self.l_bright = QLabel("100 %")
        self.brightness.valueChanged.connect(lambda v: self.l_bright.setText(f"{v} %"))
        g.addWidget(QLabel("Helligkeit"), 3, 0)
        g.addWidget(self.brightness, 3, 1, 1, 2)
        g.addWidget(self.l_bright, 3, 3)

        self.speed = QSlider(Qt.Horizontal)
        self.speed.setRange(1, 50)
        self.speed.setValue(10)
        self.l_speed = QLabel("1.0x")
        self.speed.valueChanged.connect(lambda v: self.l_speed.setText(f"{v / 10:.1f}x"))
        self.l_speed_title = QLabel("Tempo")
        g.addWidget(self.l_speed_title, 4, 0)
        g.addWidget(self.speed, 4, 1, 1, 2)
        g.addWidget(self.l_speed, 4, 3)

        self.live = QCheckBox("Änderungen sofort übernehmen (Live)")
        self.live.setChecked(True)
        g.addWidget(self.live, 5, 0, 1, 4)
        brow = QHBoxLayout()
        self.b_apply = button("✔ Anwenden", "primary")
        self.b_off = button("⏻ Alles aus")
        self.b_apply.clicked.connect(self.applyRequested.emit)
        self.b_off.clicked.connect(self._all_off)
        brow.addWidget(self.b_apply, 2)
        brow.addWidget(self.b_off, 1)
        g.addLayout(brow, 6, 0, 1, 4)
        rl.addWidget(eff_box)

        for sig in (self.color1.colorChanged, self.color2.colorChanged, self.brightness.valueChanged,
                    self.speed.valueChanged, self.effect.currentIndexChanged):
            sig.connect(self._maybe_live)

        prev_box = QGroupBox("Live-Vorschau")
        pl = QVBoxLayout(prev_box)
        self.preview = LedPreview()
        pl.addWidget(self.preview)
        rl.addWidget(prev_box)

        self.hw_box = QGroupBox("Hardware-Modus des markierten Geräts")
        hl = QGridLayout(self.hw_box)
        self.hw_device = QLabel("Links ein Gerät anklicken")
        self.hw_device.setWordWrap(True)
        self.hw_mode = QComboBox()
        self.b_hw = button("Modus setzen")
        self.b_hw_save = button("Im Gerät speichern",
                                tip="Speichert den Modus im Gerät selbst - bleibt dann auch ohne App/PC-Neustart erhalten "
                                    "(nicht jedes Gerät unterstützt das)")
        self.b_hw.clicked.connect(lambda: self._hw(False))
        self.b_hw_save.clicked.connect(lambda: self._hw(True))
        info = QLabel("Hardware-Modi laufen direkt auf dem Gerät (auch ohne diese App). Software-Effekte oben "
                      "sind dafür über alle Geräte synchron.")
        info.setObjectName("subtitle")
        info.setWordWrap(True)
        hl.addWidget(self.hw_device, 0, 0, 1, 3)
        hl.addWidget(self.hw_mode, 1, 0)
        hl.addWidget(self.b_hw, 1, 1)
        hl.addWidget(self.b_hw_save, 1, 2)
        hl.addWidget(info, 2, 0, 1, 3)
        rl.addWidget(self.hw_box)
        rl.addStretch()

        self._effect_changed()
        self._update_hw_box()

    # ---------------------------------------------------------- Geräte
    def set_devices(self, devices: list[LightDevice], keep_selection: bool = True) -> None:
        old = self.selected_keys() if keep_selection else set()
        first = not self.devices
        self.devices = devices
        self._building = True
        self.tree.clear()
        for d in devices:
            it = QTreeWidgetItem([f"{d.icon}  {d.name}", str(d.led_count), d.type_name])
            it.setFlags(it.flags() | Qt.ItemIsUserCheckable | Qt.ItemIsAutoTristate)
            it.setData(0, Qt.UserRole, (d.index, -1))
            tip = "\n".join(x for x in (d.name, f"Hersteller: {d.vendor}" if d.vendor else "",
                                        d.description, f"Anschluss: {d.location}" if d.location else "") if x)
            it.setToolTip(0, tip)
            for z in d.zones:
                zi = QTreeWidgetItem([z.name, str(z.led_count), "anpassbar" if z.resizable else ""])
                zi.setFlags(zi.flags() | Qt.ItemIsUserCheckable)
                zi.setData(0, Qt.UserRole, (d.index, z.index))
                checked = (first and not old) or (d.key, z.name) in old
                zi.setCheckState(0, Qt.Checked if checked else Qt.Unchecked)
                if z.led_count == 0:
                    zi.setToolTip(0, "Diese Zone hat 0 LEDs - Rechtsklick > 'LED-Anzahl festlegen' "
                                     "(z.B. bei ARGB-Anschlüssen am Mainboard)")
                    zi.setForeground(0, Qt.gray)
                it.addChild(zi)
            self.tree.addTopLevelItem(it)
            it.setExpanded(len(d.zones) > 1)
        self._building = False
        self._update_count()
        self._update_hw_box()

    def selected_zones(self) -> set[tuple[int, int]]:
        out = set()
        for i in range(self.tree.topLevelItemCount()):
            top = self.tree.topLevelItem(i)
            for j in range(top.childCount()):
                c = top.child(j)
                if c.checkState(0) == Qt.Checked:
                    out.add(tuple(c.data(0, Qt.UserRole)))
        return out

    def selected_keys(self) -> set[tuple[str, str]]:
        by_idx = {d.index: d for d in self.devices}
        out = set()
        for dev, zone in self.selected_zones():
            d = by_idx.get(dev)
            if d and zone < len(d.zones):
                out.add((d.key, d.zones[zone].name))
        return out

    def select_keys(self, keys: set[tuple[str, str]]) -> None:
        self._building = True
        by_idx = {d.index: d for d in self.devices}
        for i in range(self.tree.topLevelItemCount()):
            top = self.tree.topLevelItem(i)
            for j in range(top.childCount()):
                c = top.child(j)
                dev, zone = c.data(0, Qt.UserRole)
                d = by_idx[dev]
                c.setCheckState(0, Qt.Checked if (d.key, d.zones[zone].name) in keys else Qt.Unchecked)
        self._building = False
        self._update_count()

    def set_all_checked(self, on: bool) -> None:
        self._building = True
        for i in range(self.tree.topLevelItemCount()):
            self.tree.topLevelItem(i).setCheckState(0, Qt.Checked if on else Qt.Unchecked)
        self._building = False
        self._update_count()
        self._maybe_live()

    def _item_changed(self, *_):
        if not self._building:
            self._update_count()
            self._maybe_live()

    def _update_count(self):
        n_dev = len(self.devices)
        n_led = sum(d.led_count for d in self.devices)
        self.count_label.setText(f"{n_dev} Geräte, {n_led} LEDs erkannt - {len(self.selected_zones())} Zonen ausgewählt")

    def _context_menu(self, pos):
        it = self.tree.itemAt(pos)
        if not it:
            return
        dev, zone = it.data(0, Qt.UserRole)
        if zone < 0:
            return
        d = next(x for x in self.devices if x.index == dev)
        z = d.zones[zone]
        menu = QMenu(self)
        act = menu.addAction("LED-Anzahl festlegen ...")
        act.setEnabled(z.resizable)
        if menu.exec(self.tree.viewport().mapToGlobal(pos)) == act:
            n, ok = QInputDialog.getInt(self, "LED-Anzahl", f"Wie viele LEDs hängen an '{z.name}'?\n"
                                        "(z.B. 3 Lüfter x 16 LEDs = 48)", max(z.led_count, z.leds_min),
                                        z.leds_min, z.leds_max)
            if ok:
                self.resizeRequested.emit(dev, zone, n)

    # ---------------------------------------------------------- Effekt
    def params(self) -> EffectParams:
        return EffectParams(self.color1.color(), self.color2.color(), self.speed.value() / 10,
                            self.brightness.value() / 100)

    def effect_key(self) -> str:
        return self.effect.currentData()

    def set_state(self, effect: str, p: EffectParams) -> None:
        widgets = (self.effect, self.color1, self.color2, self.speed, self.brightness)
        for w in widgets:
            w.blockSignals(True)
        i = self.effect.findData(effect)
        self.effect.setCurrentIndex(max(i, 0))
        self.color1.setColor(p.color, emit=False)
        self.color2.setColor(p.color2, emit=False)
        self.speed.setValue(int(round(p.speed * 10)))
        self.brightness.setValue(int(round(p.brightness * 100)))
        for w in widgets:
            w.blockSignals(False)
        self.l_bright.setText(f"{self.brightness.value()} %")
        self.l_speed.setText(f"{self.speed.value() / 10:.1f}x")
        self._effect_changed()

    def _effect_changed(self):
        e = EFFECTS_BY_KEY[self.effect_key()]
        self.color1.setEnabled(e.uses_color)
        self.l_color1.setEnabled(e.uses_color)
        self.color2.setEnabled(e.uses_color2)
        self.l_color2.setEnabled(e.uses_color2)
        self.speed.setEnabled(e.animated)
        self.l_speed_title.setEnabled(e.animated)

    def _maybe_live(self, *_):
        if self.live.isChecked() and not self._building:
            self.applyRequested.emit()

    def _all_off(self):
        self.effect.setCurrentIndex(self.effect.findData("off"))
        self.applyRequested.emit()

    # ---------------------------------------------------------- Hardware-Modus
    def _current_device(self) -> LightDevice | None:
        it = self.tree.currentItem()
        if not it:
            return None
        dev = it.data(0, Qt.UserRole)[0]
        return next((d for d in self.devices if d.index == dev), None)

    def _update_hw_box(self):
        d = self._current_device()
        self.hw_mode.clear()
        enabled = d is not None and bool(d.modes)
        if d:
            self.hw_device.setText(f"<b>{d.name}</b><br>{d.type_name}{' - ' + d.vendor if d.vendor else ''}")
            self.hw_mode.addItems(d.modes)
            if 0 <= d.active_mode < len(d.modes):
                self.hw_mode.setCurrentIndex(d.active_mode)
        else:
            self.hw_device.setText("Links ein Gerät anklicken")
        for w in (self.hw_mode, self.b_hw, self.b_hw_save):
            w.setEnabled(enabled)

    def _hw(self, save: bool):
        d = self._current_device()
        if d and self.hw_mode.currentText():
            self.hardwareModeRequested.emit(d.index, self.hw_mode.currentText(), save)


# =====================================================================
class ScanPage(QWidget):
    def __init__(self, get_devices, parent=None):
        super().__init__(parent)
        self.get_devices = get_devices
        page, lay = page_frame("Hardware-Scan", "Durchsucht den PC nach Mainboard, Grafikkarte, RAM und "
                                                "USB-Geräten von RGB-Herstellern und zeigt, was davon in der App "
                                                "steuerbar ist.")
        QVBoxLayout(self).addWidget(page)
        self.layout().setContentsMargins(0, 0, 0, 0)
        row = QHBoxLayout()
        self.b_scan = button("\U0001F50D PC scannen", "primary")
        self.b_scan.clicked.connect(self.scan)
        self.status = QLabel("")
        self.status.setObjectName("subtitle")
        row.addWidget(self.b_scan)
        row.addWidget(self.status, 1)
        lay.addLayout(row)
        self.table = QTableWidget(0, 6)
        self.table.setHorizontalHeaderLabels(["Kategorie", "Bauteil", "Hersteller", "Details", "Hat RGB?",
                                              "In App steuerbar"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.table.setAlternatingRowColors(True)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.verticalHeader().hide()
        lay.addWidget(self.table, 1)
        hint = QLabel("Fehlt ein Gerät in der Beleuchtung? 1) App als Administrator starten (nötig für RAM und "
                      "Mainboard), 2) andere RGB-Programme beenden (Seite 'Andere RGB-Apps'), 3) 'Neu scannen'. "
                      "Die Liste unterstützter Hardware steht auf openrgb.org.")
        hint.setObjectName("subtitle")
        hint.setWordWrap(True)
        lay.addWidget(hint)

    def scan(self):
        self.b_scan.setEnabled(False)
        self.status.setText("Scanne ... (kann ein paar Sekunden dauern)")
        run_async(hwscan.scan, on_done=self._show, on_error=self._fail)

    def _fail(self, msg):
        self.b_scan.setEnabled(True)
        self.status.setText(f"Fehler: {msg}")

    @staticmethod
    def controllable(item: hwscan.HwItem, devices: list[LightDevice]) -> str:
        type_map = {"Mainboard": "MOTHERBOARD", "Grafikkarte": "GPU", "Arbeitsspeicher": "DRAM"}
        if item.rgb in ("Nein", "-"):
            return ""
        t = type_map.get(item.category)
        if t:
            return "✔ Ja" if any(d.type_key == t for d in devices) else "✖ nicht erkannt"
        vendor = item.vendor.split()[0].lower() if item.vendor else ""
        if vendor and any(vendor in (d.vendor + " " + d.name).lower() for d in devices):
            return "✔ Ja"
        return "✖ nicht erkannt"

    def _show(self, items: list[hwscan.HwItem]):
        self.b_scan.setEnabled(True)
        devices = self.get_devices()
        self.table.setRowCount(len(items))
        colors = {"Wahrscheinlich": "#7CFC9A", "Ja": "#7CFC9A", "Möglich": "#FFD166", "Nein": "#8A8AA0"}
        for r, it in enumerate(items):
            vals = [it.category, it.name, it.vendor, it.detail, it.rgb, self.controllable(it, devices)]
            for c, v in enumerate(vals):
                cell = QTableWidgetItem(v)
                if c == 4:
                    cell.setForeground(colors_q(colors.get(it.rgb, "#E6E6EF")))
                if c == 5 and v.startswith("✔"):
                    cell.setForeground(colors_q("#7CFC9A"))
                elif c == 5 and v:
                    cell.setForeground(colors_q("#FF8A80"))
                self.table.setItem(r, c, cell)
        self.status.setText(f"{len(items)} Bauteile gefunden, {sum(1 for i in items if i.rgb == 'Wahrscheinlich')} "
                            f"mit wahrscheinlicher RGB-Beleuchtung.")


def colors_q(hexstr: str):
    from PySide6.QtGui import QColor
    return QColor(hexstr)


# =====================================================================
class ConflictsPage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.found: list[conflicts.FoundApp] = []
        page, lay = page_frame("Andere RGB-Apps", "Hersteller-Programme wie iCUE, Armoury Crate, Mystic Light, "
                                                  "RGB Fusion, NZXT CAM, G HUB, Synapse ... steuern dieselbe "
                                                  "Hardware und verursachen Flackern oder Konflikte. Hier kannst du "
                                                  "sie beenden oder deinstallieren - danach brauchst du nur noch "
                                                  "diese eine App.")
        QVBoxLayout(self).addWidget(page)
        self.layout().setContentsMargins(0, 0, 0, 0)
        row = QHBoxLayout()
        b = button("\U0001F50D Nach RGB-Apps suchen", "primary")
        b.clicked.connect(self.scan)
        self.b_kill_all = button("Alle laufenden beenden")
        self.b_kill_all.clicked.connect(self.kill_all)
        self.status = QLabel("")
        self.status.setObjectName("subtitle")
        row.addWidget(b)
        row.addWidget(self.b_kill_all)
        row.addWidget(self.status, 1)
        lay.addLayout(row)
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["Programm", "Status", "Gefunden als", "", ""])
        h = self.table.horizontalHeader()
        h.setSectionResizeMode(QHeaderView.ResizeToContents)
        h.setSectionResizeMode(2, QHeaderView.Stretch)
        self.table.verticalHeader().hide()
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setSelectionMode(QAbstractItemView.NoSelection)
        lay.addWidget(self.table, 1)
        tip = QLabel("Tipp: Deinstallieren startet den offiziellen Deinstaller des Herstellers - du bestätigst "
                     "dort selbst. Einige Programme installieren Treiber/Dienste mit, die erst nach einem "
                     "Neustart ganz weg sind. Danach in der Beleuchtung 'Neu scannen'.")
        tip.setObjectName("subtitle")
        tip.setWordWrap(True)
        lay.addWidget(tip)

    def scan(self):
        self.status.setText("Suche ...")
        run_async(conflicts.find_conflicts, on_done=self._show,
                  on_error=lambda m: self.status.setText(f"Fehler: {m}"))

    def _show(self, found: list[conflicts.FoundApp]):
        self.found = found
        rows = []
        for f in found:
            for e in f.installed or [None]:
                rows.append((f, e))
        self.table.setRowCount(len(rows))
        for r, (f, e) in enumerate(rows):
            st = []
            if f.running:
                st.append("● läuft")
            if f.installed:
                st.append("installiert")
            self.table.setItem(r, 0, QTableWidgetItem(f.app.name))
            s = QTableWidgetItem(", ".join(st))
            s.setForeground(colors_q("#FF8A80" if f.running else "#FFD166"))
            self.table.setItem(r, 1, s)
            detail = e.display_name if e else ", ".join(sorted({n for _, n in f.running}))
            if e and e.publisher:
                detail += f"  ({e.publisher})"
            self.table.setItem(r, 2, QTableWidgetItem(detail))
            bk = button("Beenden")
            bk.setEnabled(bool(f.running))
            bk.clicked.connect(lambda _=False, f=f: self.kill(f))
            self.table.setCellWidget(r, 3, bk)
            bu = button("Deinstallieren", "danger")
            bu.setEnabled(e is not None and bool(e.uninstall_cmd or e.quiet_cmd))
            bu.clicked.connect(lambda _=False, f=f, e=e: self.uninstall(f, e))
            self.table.setCellWidget(r, 4, bu)
        self.b_kill_all.setEnabled(any(f.running for f in found))
        self.status.setText("Keine anderen RGB-Programme gefunden \U0001F389" if not found else
                            f"{len(found)} RGB-Programm(e) gefunden.")

    def kill(self, f: conflicts.FoundApp, ask: bool = True):
        if ask and QMessageBox.question(self, "Beenden", f"'{f.app.name}' jetzt beenden?") != QMessageBox.Yes:
            return
        errs = conflicts.kill_processes(f)
        if errs:
            QMessageBox.warning(self, "Beenden", "Nicht alles konnte beendet werden (Adminrechte?):\n" + "\n".join(errs))
        self.scan()

    def kill_all(self):
        running = [f for f in self.found if f.running]
        if not running or QMessageBox.question(
                self, "Alle beenden", "Diese Programme beenden?\n\n" + "\n".join(f.app.name for f in running)) != QMessageBox.Yes:
            return
        for f in running:
            conflicts.kill_processes(f)
        self.scan()

    def uninstall(self, f: conflicts.FoundApp, e: conflicts.InstalledEntry):
        if QMessageBox.question(
                self, "Deinstallieren",
                f"'{e.display_name}' deinstallieren?\n\nEs wird der offizielle Deinstaller gestartet. "
                "Laufende Prozesse werden vorher beendet.") != QMessageBox.Yes:
            return
        if f.running:
            conflicts.kill_processes(f)
        try:
            conflicts.run_uninstaller(e)
        except Exception as ex:  # noqa: BLE001
            QMessageBox.warning(self, "Deinstallieren", f"Deinstaller konnte nicht gestartet werden:\n{ex}\n\n"
                                "Alternativ: Windows-Einstellungen > Apps > Installierte Apps.")


# =====================================================================
class ProfilesPage(QWidget):
    saveRequested = Signal(str)
    loadRequested = Signal(str)
    deleteRequested = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        page, lay = page_frame("Profile", "Speichere Farben + Effekt + ausgewählte Geräte als Profil, z.B. "
                                          "'Gaming', 'Arbeiten', 'Nacht'. Profile sind auch über das Tray-Symbol "
                                          "erreichbar.")
        QVBoxLayout(self).addWidget(page)
        self.layout().setContentsMargins(0, 0, 0, 0)
        row = QHBoxLayout()
        self.name = QLineEdit()
        self.name.setPlaceholderText("Name des neuen Profils")
        b = button("\U0001F4BE Aktuellen Zustand speichern", "primary")
        b.clicked.connect(self._save)
        self.name.returnPressed.connect(self._save)
        row.addWidget(self.name, 1)
        row.addWidget(b)
        lay.addLayout(row)
        self.list = QListWidget()
        self.list.itemDoubleClicked.connect(lambda it: self.loadRequested.emit(it.text()))
        lay.addWidget(self.list, 1)
        row2 = QHBoxLayout()
        bl, bd = button("Laden", "primary"), button("Löschen", "danger")
        bl.clicked.connect(lambda: self.list.currentItem() and self.loadRequested.emit(self.list.currentItem().text()))
        bd.clicked.connect(self._delete)
        row2.addWidget(bl)
        row2.addWidget(bd)
        row2.addStretch()
        lay.addLayout(row2)

    def set_profiles(self, names: list[str]):
        self.list.clear()
        self.list.addItems(names)

    def _save(self):
        n = self.name.text().strip()
        if not n:
            QMessageBox.information(self, "Profil", "Bitte einen Namen eingeben.")
            return
        self.saveRequested.emit(n)
        self.name.clear()

    def _delete(self):
        it = self.list.currentItem()
        if it and QMessageBox.question(self, "Löschen", f"Profil '{it.text()}' löschen?") == QMessageBox.Yes:
            self.deleteRequested.emit(it.text())


# =====================================================================
class SettingsPage(QWidget):
    reconnectRequested = Signal()
    changed = Signal()
    adminRequested = Signal()

    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.s = settings
        page, lay = page_frame("Einstellungen", f"{APP_NAME} {APP_VERSION}")
        QVBoxLayout(self).addWidget(page)
        self.layout().setContentsMargins(0, 0, 0, 0)

        gen = QGroupBox("Allgemein")
        gl = QVBoxLayout(gen)
        self.c_autostart = QCheckBox("Mit Windows starten (ohne UAC-Abfrage, über Aufgabenplanung)")
        self.c_min = QCheckBox("Minimiert (im Tray) starten")
        self.c_tray = QCheckBox("Beim Schließen in den Tray minimieren (Effekte laufen weiter)")
        self.c_restore = QCheckBox("Beim Start letzte Beleuchtung wiederherstellen")
        for c in (self.c_autostart, self.c_min, self.c_tray, self.c_restore):
            gl.addWidget(c)
        lay.addWidget(gen)

        con = QGroupBox("Hardware-Dienst (OpenRGB)")
        f = QFormLayout(con)
        self.c_openrgb = QCheckBox("OpenRGB-Dienst automatisch unsichtbar im Hintergrund starten")
        f.addRow(self.c_openrgb)
        prow = QHBoxLayout()
        self.path = QLineEdit()
        self.path.setPlaceholderText("automatisch suchen (Ordner 'OpenRGB' neben der App oder Programme)")
        bp = button("...")
        bp.clicked.connect(self._browse)
        prow.addWidget(self.path, 1)
        prow.addWidget(bp)
        f.addRow("OpenRGB.exe", prow)
        self.host = QLineEdit()
        self.port = QSpinBox()
        self.port.setRange(1, 65535)
        f.addRow("Host", self.host)
        f.addRow("Port", self.port)
        self.c_demo = QCheckBox("Demo-Modus (simulierte Geräte, zum Ausprobieren ohne Hardware)")
        f.addRow(self.c_demo)
        brow = QHBoxLayout()
        b_re = button("⟳ Neu verbinden", "primary")
        b_re.clicked.connect(self._reconnect)
        b_dl = button("OpenRGB herunterladen")
        b_dl.clicked.connect(lambda: webbrowser.open(DOWNLOAD_URL))
        self.b_admin = button("\U0001F6E1 Als Administrator neu starten")
        self.b_admin.clicked.connect(self.adminRequested.emit)
        brow.addWidget(b_re)
        brow.addWidget(b_dl)
        brow.addWidget(self.b_admin)
        brow.addStretch()
        f.addRow(brow)
        lay.addWidget(con)

        about = QLabel(
            "<b>So funktioniert's:</b> Die eigentliche Hardware-Kommunikation (SMBus für RAM/Mainboard, I2C für "
            "Grafikkarten, USB/HID für Lüfter-Controller, Tastaturen usw.) übernimmt der Open-Source-Dienst "
            "<b>OpenRGB</b>, der hunderte Geräte aller großen Hersteller unterstützt. Diese App startet ihn "
            "unsichtbar im Hintergrund, bietet die Oberfläche, synchrone Effekte, Profile und räumt andere "
            "RGB-Programme auf.")
        about.setWordWrap(True)
        about.setObjectName("subtitle")
        lay.addWidget(about)
        lay.addStretch()
        self.load()
        for c in (self.c_autostart, self.c_min, self.c_tray, self.c_restore, self.c_openrgb):
            c.toggled.connect(self._store)

    def load(self):
        s = self.s
        try:
            from ..config import autostart_enabled
            auto = autostart_enabled()
        except Exception:
            auto = False
        for w, v in ((self.c_autostart, auto), (self.c_min, s.start_minimized), (self.c_tray, s.minimize_to_tray),
                     (self.c_restore, s.restore_last_on_start), (self.c_openrgb, s.autostart_openrgb),
                     (self.c_demo, s.demo_mode)):
            w.blockSignals(True)
            w.setChecked(v)
            w.blockSignals(False)
        self.path.setText(s.openrgb_path)
        self.host.setText(s.host)
        self.port.setValue(s.port)

    def _browse(self):
        p, _ = QFileDialog.getOpenFileName(self, "OpenRGB.exe wählen", "", "OpenRGB (OpenRGB.exe openrgb);;Alle (*)")
        if p:
            self.path.setText(p)
            self._store()

    def _store(self, *_):
        s = self.s
        s.start_minimized = self.c_min.isChecked()
        s.minimize_to_tray = self.c_tray.isChecked()
        s.restore_last_on_start = self.c_restore.isChecked()
        s.autostart_openrgb = self.c_openrgb.isChecked()
        s.demo_mode = self.c_demo.isChecked()
        s.openrgb_path = self.path.text().strip()
        s.host = self.host.text().strip() or "127.0.0.1"
        s.port = self.port.value()
        try:
            from ..config import autostart_enabled, set_autostart
            if self.c_autostart.isChecked() != autostart_enabled():
                set_autostart(self.c_autostart.isChecked())
        except Exception as e:  # noqa: BLE001
            self.c_autostart.blockSignals(True)
            self.c_autostart.setChecked(False)
            self.c_autostart.blockSignals(False)
            if self.isVisible():
                QMessageBox.warning(self, "Autostart", f"Autostart konnte nicht geändert werden:\n{e}")
        self.changed.emit()

    def _reconnect(self):
        self._store()
        self.reconnectRequested.emit()
