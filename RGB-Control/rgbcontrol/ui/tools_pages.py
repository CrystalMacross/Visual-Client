"""Werkzeug-Seiten: Hardware-Scan, andere RGB-Apps, Profile, Einstellungen."""
from __future__ import annotations

import webbrowser

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QFileDialog, QFormLayout, QHBoxLayout, QHeaderView,
                               QLineEdit, QListWidget, QMessageBox, QScrollArea, QSpinBox, QTableWidget,
                               QTableWidgetItem, QVBoxLayout, QWidget)

from .. import APP_NAME, APP_VERSION, conflicts, hwscan
from ..backend import LightDevice
from ..openrgb_service import DOWNLOAD_URL
from ..sensors import PAWNIO_URL
from .async_task import run_async
from .components import Card, button, label
from .theme import DANGER, FAINT, MUTED, SUCCESS, WARN


def page_body(parent: QWidget) -> QVBoxLayout:
    outer = QVBoxLayout(parent)
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
    return lay


def table(headers: list[str], stretch: int) -> QTableWidget:
    t = QTableWidget(0, len(headers))
    t.setHorizontalHeaderLabels(headers)
    h = t.horizontalHeader()
    h.setSectionResizeMode(QHeaderView.ResizeToContents)
    h.setSectionResizeMode(stretch, QHeaderView.Stretch)
    t.verticalHeader().hide()
    t.setEditTriggers(QAbstractItemView.NoEditTriggers)
    t.setSelectionMode(QAbstractItemView.NoSelection)
    t.setShowGrid(False)
    t.setMinimumHeight(360)
    return t


def cell(text: str, color: str | None = None) -> QTableWidgetItem:
    it = QTableWidgetItem(text)
    if color:
        it.setForeground(QColor(color))
    return it


# =====================================================================
class ScanPage(QWidget):
    def __init__(self, get_devices, parent=None):
        super().__init__(parent)
        self.get_devices = get_devices
        lay = page_body(self)
        card = Card()
        row = QHBoxLayout()
        row.addWidget(label("Durchsucht Mainboard, Grafikkarte, RAM-Riegel und USB-Geräte von über 30 RGB-Herstellern "
                            "und zeigt, was davon in der App steuerbar ist.", "muted", wrap=True), 1)
        self.b_scan = button("PC scannen", "primary", "scan")
        self.b_scan.clicked.connect(self.scan)
        row.addWidget(self.b_scan)
        card.lay.addLayout(row)
        self.status = label("", "faint")
        card.lay.addWidget(self.status)
        self.table = table(["Kategorie", "Bauteil", "Hersteller", "Details", "Hat RGB?", "In App steuerbar"], 1)
        card.lay.addWidget(self.table)
        lay.addWidget(card)
        lay.addWidget(label("Fehlt ein Gerät? 1) App als Administrator starten (nötig für RAM/Mainboard), 2) andere "
                            "RGB-Programme beenden, 3) „Neu scannen“. Die Liste unterstützter Hardware steht auf "
                            "openrgb.org.", "faint", wrap=True))
        lay.addStretch()

    def scan(self):
        self.b_scan.setEnabled(False)
        self.status.setText("Scanne … (kann ein paar Sekunden dauern)")
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
        colors = {"Wahrscheinlich": SUCCESS, "Ja": SUCCESS, "Möglich": WARN, "Nein": FAINT}
        for r, it in enumerate(items):
            ctl = self.controllable(it, devices)
            vals = [(it.category, MUTED), (it.name, None), (it.vendor, None), (it.detail, MUTED),
                    (it.rgb, colors.get(it.rgb)), (ctl, SUCCESS if ctl.startswith("✔") else DANGER)]
            for c, (v, col) in enumerate(vals):
                self.table.setItem(r, c, cell(v, col))
        self.status.setText(f"{len(items)} Bauteile gefunden, "
                            f"{sum(1 for i in items if i.rgb == 'Wahrscheinlich')} mit wahrscheinlicher RGB-Beleuchtung.")


# =====================================================================
class ConflictsPage(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.found: list[conflicts.FoundApp] = []
        lay = page_body(self)
        card = Card(accent=DANGER)
        row = QHBoxLayout()
        row.addWidget(label("iCUE, Armoury Crate, Mystic Light, RGB Fusion, NZXT CAM, G HUB, Synapse & Co. steuern "
                            "dieselbe Hardware und verursachen Flackern. Hier beendest oder deinstallierst du sie – "
                            "danach brauchst du nur noch diese App.", "muted", wrap=True), 1)
        b = button("Suchen", "primary", "scan")
        b.clicked.connect(self.scan)
        self.b_kill_all = button("Alle beenden", "ghost", "power")
        self.b_kill_all.clicked.connect(self.kill_all)
        self.b_kill_all.setEnabled(False)
        row.addWidget(b)
        row.addWidget(self.b_kill_all)
        card.lay.addLayout(row)
        self.status = label("", "faint")
        card.lay.addWidget(self.status)
        self.table = table(["Programm", "Status", "Gefunden als", "", ""], 2)
        card.lay.addWidget(self.table)
        lay.addWidget(card)
        lay.addWidget(label("Deinstallieren startet den offiziellen Deinstaller des Herstellers – du bestätigst dort "
                            "selbst. Manche Programme hinterlassen Dienste, die erst nach einem Neustart weg sind.",
                            "faint", wrap=True))
        lay.addStretch()

    def scan(self):
        self.status.setText("Suche …")
        run_async(conflicts.find_conflicts, on_done=self.show_found,
                  on_error=lambda m: self.status.setText(f"Fehler: {m}"))

    def show_found(self, found: list[conflicts.FoundApp]):
        self.found = found
        rows = [(f, e) for f in found for e in (f.installed or [None])]
        self.table.setRowCount(len(rows))
        for r, (f, e) in enumerate(rows):
            st = (["● läuft"] if f.running else []) + (["installiert"] if f.installed else [])
            self.table.setItem(r, 0, cell(f.app.name))
            self.table.setItem(r, 1, cell(", ".join(st), DANGER if f.running else WARN))
            detail = e.display_name if e else ", ".join(sorted({n for _, n in f.running}))
            if e and e.publisher:
                detail += f"  ({e.publisher})"
            self.table.setItem(r, 2, cell(detail, MUTED))
            bk = button("Beenden", "ghost")
            bk.setEnabled(bool(f.running))
            bk.clicked.connect(lambda _=False, f=f: self.kill(f))
            self.table.setCellWidget(r, 3, bk)
            bu = button("Deinstallieren", "danger")
            bu.setEnabled(e is not None and bool(e.uninstall_cmd or e.quiet_cmd))
            bu.clicked.connect(lambda _=False, f=f, e=e: self.uninstall(f, e))
            self.table.setCellWidget(r, 4, bu)
        self.b_kill_all.setEnabled(any(f.running for f in found))
        self.status.setText("Keine anderen RGB-Programme gefunden 🎉" if not found else
                            f"{len(found)} RGB-Programm(e) gefunden.")

    def kill(self, f):
        if QMessageBox.question(self, "Beenden", f"„{f.app.name}“ jetzt beenden?") != QMessageBox.Yes:
            return
        errs = conflicts.kill_processes(f)
        if errs:
            QMessageBox.warning(self, "Beenden", "Nicht alles konnte beendet werden:\n" + "\n".join(errs))
        self.scan()

    def kill_all(self):
        running = [f for f in self.found if f.running]
        if not running or QMessageBox.question(
                self, "Alle beenden", "Diese Programme beenden?\n\n" + "\n".join(f.app.name for f in running)) \
                != QMessageBox.Yes:
            return
        for f in running:
            conflicts.kill_processes(f)
        self.scan()

    def uninstall(self, f, e):
        if QMessageBox.question(self, "Deinstallieren",
                                f"„{e.display_name}“ deinstallieren?\n\nEs wird der offizielle Deinstaller gestartet. "
                                "Laufende Prozesse werden vorher beendet.") != QMessageBox.Yes:
            return
        if f.running:
            conflicts.kill_processes(f)
        try:
            conflicts.run_uninstaller(e)
        except Exception as ex:  # noqa: BLE001
            QMessageBox.warning(self, "Deinstallieren", f"Deinstaller konnte nicht gestartet werden:\n{ex}\n\n"
                                "Alternativ: Windows-Einstellungen › Apps › Installierte Apps.")


# =====================================================================
class ProfilesPage(QWidget):
    saveRequested = Signal(str)
    loadRequested = Signal(str)
    deleteRequested = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        lay = page_body(self)
        card = Card()
        card.lay.addWidget(label("Speichert Effekte und Farben aller Kategorien, z. B. „Gaming“, „Arbeiten“, „Nacht“. "
                                 "Profile sind auch über das Tray-Symbol erreichbar.", "muted", wrap=True))
        row = QHBoxLayout()
        self.name = QLineEdit()
        self.name.setPlaceholderText("Name des neuen Profils")
        b = button("Aktuellen Zustand speichern", "primary", "profiles")
        b.clicked.connect(self._save)
        self.name.returnPressed.connect(self._save)
        row.addWidget(self.name, 1)
        row.addWidget(b)
        card.lay.addLayout(row)
        self.list = QListWidget()
        self.list.setMinimumHeight(260)
        self.list.itemDoubleClicked.connect(lambda it: self.loadRequested.emit(it.text()))
        card.lay.addWidget(self.list)
        row2 = QHBoxLayout()
        bl, bd = button("Laden", "primary", "check"), button("Löschen", "danger")
        bl.clicked.connect(lambda: self.list.currentItem() and self.loadRequested.emit(self.list.currentItem().text()))
        bd.clicked.connect(self._delete)
        row2.addWidget(bl)
        row2.addWidget(bd)
        row2.addStretch()
        card.lay.addLayout(row2)
        lay.addWidget(card)
        lay.addStretch()

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
        if it and QMessageBox.question(self, "Löschen", f"Profil „{it.text()}“ löschen?") == QMessageBox.Yes:
            self.deleteRequested.emit(it.text())


# =====================================================================
class SettingsPage(QWidget):
    reconnectRequested = Signal()
    changed = Signal()
    adminRequested = Signal()

    def __init__(self, settings, parent=None):
        super().__init__(parent)
        self.s = settings
        lay = page_body(self)

        gen = Card()
        gen.lay.addWidget(label("Allgemein", "h2"))
        self.c_autostart = QCheckBox("Mit Windows starten (ohne UAC-Abfrage, über die Aufgabenplanung)")
        self.c_min = QCheckBox("Minimiert im Tray starten")
        self.c_tray = QCheckBox("Beim Schließen im Tray weiterlaufen (Effekte laufen weiter)")
        self.c_restore = QCheckBox("Beim Start die letzte Beleuchtung wiederherstellen")
        for c in (self.c_autostart, self.c_min, self.c_tray, self.c_restore):
            gen.lay.addWidget(c)
        lay.addWidget(gen)

        sen = Card()
        sen.lay.addWidget(label("Sensoren", "h2"))
        self.c_aio = QCheckBox("Wasserkühlung (AIO-Controller) direkt auslesen – für Pumpen-Temperatur und -Drehzahl")
        sen.lay.addWidget(self.c_aio)
        sen.lay.addWidget(label("Die CPU-Temperatur braucht den kostenlosen PawnIO-Treiber. Falls die CPU-Anzeige leer "
                                "bleibt: PawnIO installieren und die App neu starten.", "faint", wrap=True))
        b_paw = button("PawnIO herunterladen", "ghost")
        b_paw.clicked.connect(lambda: webbrowser.open(PAWNIO_URL))
        sen.lay.addWidget(b_paw, 0, Qt.AlignLeft)
        lay.addWidget(sen)

        con = Card()
        con.lay.addWidget(label("Hardware-Dienst (OpenRGB)", "h2"))
        f = QFormLayout()
        f.setHorizontalSpacing(16)
        f.setVerticalSpacing(10)
        self.c_openrgb = QCheckBox("OpenRGB automatisch unsichtbar im Hintergrund starten")
        f.addRow(self.c_openrgb)
        prow = QHBoxLayout()
        self.path = QLineEdit()
        self.path.setPlaceholderText("automatisch (Ordner „OpenRGB“ neben der App oder Programme)")
        bp = button("…", "ghost")
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
        con.lay.addLayout(f)
        brow = QHBoxLayout()
        b_re = button("Neu verbinden", "primary", "refresh")
        b_re.clicked.connect(self._reconnect)
        b_dl = button("OpenRGB herunterladen", "ghost")
        b_dl.clicked.connect(lambda: webbrowser.open(DOWNLOAD_URL))
        self.b_admin = button("Als Administrator neu starten", "ghost", "shield")
        self.b_admin.clicked.connect(self.adminRequested.emit)
        brow.addWidget(b_re)
        brow.addWidget(b_dl)
        brow.addWidget(self.b_admin)
        brow.addStretch()
        con.lay.addLayout(brow)
        lay.addWidget(con)

        lay.addWidget(label(f"{APP_NAME} {APP_VERSION} · Hardware-Treiber: OpenRGB · Sensoren: LibreHardwareMonitor",
                            "faint"))
        lay.addStretch()
        self.load()
        for c in (self.c_autostart, self.c_min, self.c_tray, self.c_restore, self.c_openrgb, self.c_aio):
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
                     (self.c_demo, s.demo_mode), (self.c_aio, s.read_aio_sensors)):
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
        s.read_aio_sensors = self.c_aio.isChecked()
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
