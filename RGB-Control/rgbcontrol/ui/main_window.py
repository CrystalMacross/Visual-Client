"""Hauptfenster: verbindet Oberfläche, Backend und Effekt-Engine."""
from __future__ import annotations

import sys
import time

from PySide6.QtCore import QObject, QPointF, Qt, QTimer, Signal
from PySide6.QtGui import QAction, QColor, QConicalGradient, QIcon, QPainter, QPixmap, QRadialGradient
from PySide6.QtWidgets import (QApplication, QHBoxLayout, QLabel, QListWidget, QMainWindow, QMenu, QMessageBox,
                               QPushButton, QStackedWidget, QSystemTrayIcon, QVBoxLayout, QWidget)

from .. import APP_NAME, APP_VERSION, config, openrgb_service as svc
from ..backend import Backend, BackendError, DemoBackend, LightDevice, OpenRGBBackend
from ..effects import EffectParams
from ..engine import EffectEngine, build_targets
from .async_task import run_async
from .pages import ConflictsPage, LightingPage, ProfilesPage, ScanPage, SettingsPage
from .style import STYLESHEET


def make_icon(size: int = 256) -> QIcon:
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    g = QConicalGradient(QPointF(size / 2, size / 2), 90)
    for i, c in enumerate(["#FF0040", "#FFB000", "#40FF40", "#00D4FF", "#7C4DFF", "#FF00C8", "#FF0040"]):
        g.setColorAt(i / 6, QColor(c))
    p.setBrush(g)
    p.setPen(Qt.NoPen)
    p.drawEllipse(int(size * 0.04), int(size * 0.04), int(size * 0.92), int(size * 0.92))
    p.setBrush(QColor("#121218"))
    p.drawEllipse(int(size * 0.24), int(size * 0.24), int(size * 0.52), int(size * 0.52))
    rg = QRadialGradient(QPointF(size / 2, size / 2), size * 0.16)
    rg.setColorAt(0, QColor("#FFFFFF"))
    rg.setColorAt(1, QColor("#7C4DFF"))
    p.setBrush(rg)
    p.drawEllipse(int(size * 0.36), int(size * 0.36), int(size * 0.28), int(size * 0.28))
    p.end()
    return QIcon(pm)


class _Bridge(QObject):
    engineError = Signal(str)


class MainWindow(QMainWindow):
    def __init__(self, settings: config.Settings, start_minimized: bool = False):
        super().__init__()
        self.settings = settings
        self.backend: Backend = DemoBackend()
        self.devices: list[LightDevice] = []
        self._quitting = False
        self._connecting = False
        self._restored = False
        self.bridge = _Bridge()
        self.bridge.engineError.connect(self._engine_error)
        self.engine = EffectEngine(self.backend, on_error=self.bridge.engineError.emit)
        self.engine.start()

        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self.setWindowIcon(make_icon())
        self.resize(1180, 760)
        self.setMinimumSize(900, 600)
        self.setStyleSheet(STYLESHEET)

        central = QWidget()
        central.setObjectName("central")
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        side = QWidget()
        side.setFixedWidth(220)
        side.setStyleSheet("background:#0E0E13;")
        sl = QVBoxLayout(side)
        sl.setContentsMargins(0, 0, 0, 12)
        brand = QLabel("◉ RGB Control")
        brand.setObjectName("brand")
        sl.addWidget(brand)
        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        for t in ("\U0001F308  Beleuchtung", "\U0001F50D  Hardware-Scan", "\U0001F9F9  Andere RGB-Apps",
                  "\U0001F4C1  Profile", "⚙  Einstellungen"):
            self.nav.addItem(t)
        sl.addWidget(self.nav, 1)
        self.conn_label = QLabel("")
        self.conn_label.setWordWrap(True)
        self.conn_label.setStyleSheet("padding: 8px 16px; color:#9A9AB0;")
        sl.addWidget(self.conn_label)
        root.addWidget(side)

        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.setSpacing(0)
        self.banner = QWidget()
        self.banner.setStyleSheet("background:#3A2E10;")
        bl = QHBoxLayout(self.banner)
        bl.setContentsMargins(16, 8, 16, 8)
        self.banner_text = QLabel("")
        self.banner_text.setWordWrap(True)
        self.banner_text.setStyleSheet("color:#FFD166;")
        bl.addWidget(self.banner_text, 1)
        self.banner_buttons: list[QPushButton] = []
        self.banner_layout = bl
        self.banner.hide()
        rl.addWidget(self.banner)

        self.stack = QStackedWidget()
        self.lighting = LightingPage()
        self.scan_page = ScanPage(lambda: self.devices)
        self.conflicts = ConflictsPage()
        self.profiles = ProfilesPage()
        self.settings_page = SettingsPage(settings)
        for w in (self.lighting, self.scan_page, self.conflicts, self.profiles, self.settings_page):
            self.stack.addWidget(w)
        rl.addWidget(self.stack, 1)
        root.addWidget(right, 1)
        self.setCentralWidget(central)
        self.nav.currentRowChanged.connect(self.stack.setCurrentIndex)
        self.nav.setCurrentRow(0)

        # Signale
        self.lighting.applyRequested.connect(self.apply)
        self.lighting.rescanRequested.connect(self.rescan)
        self.lighting.hardwareModeRequested.connect(self.hardware_mode)
        self.lighting.resizeRequested.connect(self.resize_zone)
        self.profiles.saveRequested.connect(self.save_profile)
        self.profiles.loadRequested.connect(self.load_profile)
        self.profiles.deleteRequested.connect(self.delete_profile)
        self.settings_page.reconnectRequested.connect(self.connect_backend)
        self.settings_page.changed.connect(self.settings.save)
        self.settings_page.adminRequested.connect(self.restart_as_admin)
        self.settings_page.b_admin.setVisible(sys.platform == "win32" and not svc.is_admin())

        self.preview_timer = QTimer(self)
        self.preview_timer.timeout.connect(self._update_preview)
        self.preview_timer.start(40)

        self._setup_tray()
        self.refresh_profiles()
        self.statusBar().showMessage("Bereit")
        if not (start_minimized and self.tray):
            self.show()
        QTimer.singleShot(50, self.connect_backend)
        QTimer.singleShot(1500, self._startup_conflict_check)

    # ------------------------------------------------------------- Tray
    def _setup_tray(self):
        self.tray = None
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self.tray = QSystemTrayIcon(make_icon(64), self)
        self.tray.setToolTip(APP_NAME)
        self.tray_menu = QMenu()
        self.tray.setContextMenu(self.tray_menu)
        self.tray.activated.connect(lambda r: self.show_window() if r in (QSystemTrayIcon.Trigger,
                                                                          QSystemTrayIcon.DoubleClick) else None)
        self.tray.show()
        self._build_tray_menu()

    def _build_tray_menu(self):
        if not self.tray:
            return
        m = self.tray_menu
        m.clear()
        m.addAction("Öffnen", self.show_window)
        pm = m.addMenu("Profile")
        names = config.list_profiles()
        for n in names:
            pm.addAction(n, lambda n=n: self.load_profile(n))
        pm.setEnabled(bool(names))
        m.addAction("Alles aus", self.lighting._all_off)
        m.addSeparator()
        m.addAction("Beenden", self.quit)

    def show_window(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def closeEvent(self, ev):
        if not self._quitting and self.settings.minimize_to_tray and self.tray:
            ev.ignore()
            self.hide()
            self.tray.showMessage(APP_NAME, "Läuft im Hintergrund weiter. Rechtsklick auf das Symbol zum Beenden.",
                                  QSystemTrayIcon.Information, 2500)
            return
        self._shutdown()
        ev.accept()
        QApplication.quit()

    def quit(self):
        self._quitting = True
        self.close()

    def _shutdown(self):
        self.save_last_state()
        self.engine.stop()
        try:
            self.backend.disconnect()
        except Exception:
            pass
        svc.stop_server()
        if self.tray:
            self.tray.hide()

    # ------------------------------------------------------------- Banner
    def show_banner(self, text: str, buttons: list[tuple[str, object]] = ()):
        for b in self.banner_buttons:
            b.deleteLater()
        self.banner_buttons = []
        self.banner_text.setText(text)
        for label, fn in buttons:
            b = QPushButton(label)
            b.clicked.connect(fn)
            self.banner_layout.addWidget(b)
            self.banner_buttons.append(b)
        close = QPushButton("✕")
        close.setFixedWidth(32)
        close.clicked.connect(self.banner.hide)
        self.banner_layout.addWidget(close)
        self.banner_buttons.append(close)
        self.banner.show()

    # ------------------------------------------------------------- Verbindung
    def connect_backend(self):
        if self._connecting:
            return
        self._connecting = True
        self.banner.hide()
        try:
            self.backend.disconnect()
        except Exception:
            pass
        s = self.settings
        if s.demo_mode:
            self._use_backend(DemoBackend())
            return
        backend = OpenRGBBackend(s.host, s.port)
        local = s.host in ("127.0.0.1", "localhost")
        self.conn_label.setText("● Verbinde ...")

        def work():
            started = False
            if local and not svc.server_running(s.host, s.port):
                exe = svc.find_openrgb(s.openrgb_path) if s.autostart_openrgb else None
                if exe is None:
                    raise FileNotFoundError("openrgb-missing")
                svc.start_server(exe, s.port)
                started = True
            deadline = time.monotonic() + (40 if started else 5)
            last = None
            while time.monotonic() < deadline:
                if svc.server_running(s.host, s.port):
                    try:
                        backend.connect()
                        break
                    except BackendError as e:
                        last = e
                time.sleep(0.5)
            else:
                raise last or BackendError("OpenRGB-Dienst antwortet nicht.")
            # OpenRGB erkennt Geräte nach dem Start nach und nach -> kurz warten bis stabil
            prev, stable, t_end = -1, 0, time.monotonic() + (25 if started else 3)
            while time.monotonic() < t_end:
                n = len(backend.rescan())
                stable = stable + 1 if n == prev and n > 0 else 0
                prev = n
                if stable >= 3:
                    break
                time.sleep(1)
            return backend

        run_async(work, on_done=self._use_backend, on_error=self._connect_failed)

    def _connect_failed(self, msg: str):
        self._connecting = False
        self.conn_label.setText("● <span style='color:#FF8A80'>Nicht verbunden</span>")
        demo = ("Demo-Modus testen", self._enable_demo)
        if "openrgb-missing" in msg:
            self.show_banner("Der Hardware-Dienst OpenRGB wurde nicht gefunden. Lege den Ordner 'OpenRGB' "
                             "(mit OpenRGB.exe) neben diese App oder installiere OpenRGB - dann 'Neu verbinden'.",
                             [("OpenRGB herunterladen", lambda: __import__("webbrowser").open(svc.DOWNLOAD_URL)),
                              ("Neu verbinden", self.connect_backend), demo])
        else:
            self.show_banner(f"Keine Verbindung zu OpenRGB: {msg}", [("Neu verbinden", self.connect_backend), demo])

    def _enable_demo(self):
        self.settings.demo_mode = True
        self.settings.save()
        self.settings_page.load()
        self.connect_backend()

    def _use_backend(self, backend: Backend):
        self._connecting = False
        try:
            backend.connect() if not backend.connected else None
        except BackendError as e:
            self._connect_failed(str(e))
            return
        self.backend = backend
        self.engine.set_backend(backend)
        self._load_devices(backend.list_devices())
        mode = "Demo-Modus" if isinstance(backend, DemoBackend) else "OpenRGB"
        self.conn_label.setText(f"● <span style='color:#7CFC9A'>Verbunden</span> ({mode})<br>"
                                f"{len(self.devices)} Geräte")
        warn = []
        if sys.platform == "win32" and not svc.is_admin() and not isinstance(backend, DemoBackend):
            warn.append("Ohne Administratorrechte werden RAM und Mainboard oft nicht erkannt.")
        if not self.devices:
            warn.append("Keine RGB-Geräte gefunden. Andere RGB-Programme beenden und 'Neu scannen'.")
        if warn:
            btns = []
            if sys.platform == "win32" and not svc.is_admin():
                btns.append(("Als Admin neu starten", self.restart_as_admin))
            btns.append(("Andere RGB-Apps", lambda: self.nav.setCurrentRow(2)))
            self.show_banner(" ".join(warn), btns)
        if not self._restored:
            self._restored = True
            if self.settings.restore_last_on_start and self.settings.last_state:
                self.apply_state(self.settings.last_state)
                return
        self.apply()

    def _load_devices(self, devices: list[LightDevice]):
        self.devices = devices
        self.lighting.set_devices(devices)
        self.statusBar().showMessage(f"{len(devices)} Geräte mit {sum(d.led_count for d in devices)} LEDs erkannt")

    def rescan(self):
        if not self.backend.connected:
            self.connect_backend()
            return
        self.statusBar().showMessage("Scanne Geräte ...")
        run_async(self.backend.rescan, on_done=lambda d: (self._load_devices(d), self.engine.release(), self.apply()),
                  on_error=self._engine_error)

    def _engine_error(self, msg: str):
        self.statusBar().showMessage(f"Fehler: {msg}", 10000)
        if not self.backend.connected:
            self.conn_label.setText("● <span style='color:#FF8A80'>Verbindung verloren</span>")
            self.show_banner(f"Verbindung zum Hardware-Dienst verloren: {msg}",
                             [("Neu verbinden", self.connect_backend)])

    def restart_as_admin(self):
        if svc.relaunch_as_admin():
            self.quit()
        else:
            QMessageBox.warning(self, "Administrator", "Neustart mit Administratorrechten nicht möglich.")

    def _startup_conflict_check(self):
        try:
            from ..conflicts import find_conflicts
        except Exception:
            return
        run_async(find_conflicts, on_done=self._startup_conflicts)

    def _startup_conflicts(self, found):
        running = [f for f in found if f.running]
        if running and not self.banner.isVisible():
            self.show_banner("Andere RGB-Programme laufen und können Flackern verursachen: " +
                             ", ".join(f.app.name for f in running),
                             [("Anzeigen", lambda: (self.nav.setCurrentRow(2), self.conflicts._show(found)))])

    # ------------------------------------------------------------- Beleuchtung
    def apply(self):
        targets = build_targets(self.devices, self.lighting.selected_zones())
        self.engine.apply(targets, self.lighting.effect_key(), self.lighting.params())

    def hardware_mode(self, dev: int, mode: str, save: bool):
        self.engine.remove_devices({dev})
        # Gerät aus der Software-Steuerung nehmen, damit der Hardware-Modus nicht überschrieben wird
        for i in range(self.lighting.tree.topLevelItemCount()):
            top = self.lighting.tree.topLevelItem(i)
            if top.data(0, Qt.UserRole)[0] == dev:
                self.lighting._building = True
                top.setCheckState(0, Qt.Unchecked)
                self.lighting._building = False
        self.apply()
        try:
            self.backend.set_hardware_mode(dev, mode, self.lighting.color1.color())
            if save:
                self.backend.save_to_device(dev)
            self.statusBar().showMessage(f"Hardware-Modus '{mode}' gesetzt" + (" und gespeichert" if save else ""), 5000)
        except BackendError as e:
            QMessageBox.warning(self, "Hardware-Modus", str(e))

    def resize_zone(self, dev: int, zone: int, size: int):
        try:
            self.backend.resize_zone(dev, zone, size)
            self.engine.release({dev})
            self._load_devices(self.backend.list_devices())
            self.apply()
        except BackendError as e:
            QMessageBox.warning(self, "LED-Anzahl", str(e))

    def _update_preview(self):
        if not self.isVisible():
            return
        names = {}
        for d in self.devices:
            for z in d.zones:
                names[(d.index, z.index)] = f"{d.name} - {z.name}" if len(d.zones) > 1 else d.name
        frame = self.engine.preview
        self.lighting.preview.set_rows([(names.get(k, "?"), v) for k, v in frame.items()])

    # ------------------------------------------------------------- Zustand / Profile
    def current_state(self) -> dict:
        p = self.lighting.params()
        return {"effect": self.lighting.effect_key(),
                "params": {"color": list(p.color), "color2": list(p.color2), "speed": p.speed,
                           "brightness": p.brightness},
                "selected": sorted([list(k) for k in self.lighting.selected_keys()])}

    def apply_state(self, st: dict):
        try:
            pr = st.get("params", {})
            p = EffectParams(tuple(pr.get("color", (0, 120, 255))), tuple(pr.get("color2", (255, 0, 160))),
                             float(pr.get("speed", 1.0)), float(pr.get("brightness", 1.0)))
            self.lighting.set_state(st.get("effect", "static"), p)
            if "selected" in st:
                self.lighting.select_keys({tuple(x) for x in st["selected"]})
            self.apply()
        except (TypeError, ValueError, KeyError) as e:
            self.statusBar().showMessage(f"Profil fehlerhaft: {e}", 8000)

    def save_last_state(self):
        if self.devices:
            self.settings.last_state = self.current_state()
        self.settings.save()

    def refresh_profiles(self):
        self.profiles.set_profiles(config.list_profiles())
        self._build_tray_menu()

    def save_profile(self, name: str):
        n = config.save_profile(name, self.current_state())
        self.refresh_profiles()
        self.statusBar().showMessage(f"Profil '{n}' gespeichert", 4000)

    def load_profile(self, name: str):
        try:
            self.apply_state(config.load_profile(name))
            self.statusBar().showMessage(f"Profil '{name}' geladen", 4000)
        except (OSError, ValueError) as e:
            QMessageBox.warning(self, "Profil", f"Profil konnte nicht geladen werden:\n{e}")

    def delete_profile(self, name: str):
        config.delete_profile(name)
        self.refresh_profiles()
