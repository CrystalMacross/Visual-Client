"""Hauptfenster: verbindet Oberfläche, Hardware-Backend, Effekt-Engine und Sensoren."""
from __future__ import annotations

import logging
import sys
import time
import webbrowser

from PySide6.QtCore import QObject, QPointF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QConicalGradient, QIcon, QPainter, QPixmap, QRadialGradient
from PySide6.QtWidgets import (QApplication, QButtonGroup, QHBoxLayout, QMainWindow, QMenu, QMessageBox,
                               QPushButton, QStackedWidget, QSystemTrayIcon, QVBoxLayout, QWidget)

from .. import APP_NAME, APP_VERSION, config, openrgb_service as svc
from ..backend import Backend, BackendError, LightDevice, OpenRGBBackend
from ..categories import BY_KEY, CATEGORIES, OTHER, group_zones
from ..effects import EFFECTS_BY_KEY, EffectParams
from ..engine import EffectEngine, build_targets
from ..sensors import SensorHub, Snapshot
from . import icons
from .async_task import run_async
from .category_page import CategoryPage
from .components import Card, StatPill, button, icon_label, label
from .dashboard import Dashboard
from .theme import (ACCENT, BG, DANGER, FAINT, MUTED, STYLESHEET, SUCCESS, TEXT, WARN, battery_color,
                    load_color, temp_color)
from .tools_pages import ConflictsPage, ProfilesPage, ScanPage, SettingsPage

log = logging.getLogger(__name__)


def zone_size_key(d: LightDevice, zone_name: str) -> str:
    return f"{d.name}|{d.location}|{zone_name}"

DEFAULT_STATE = {"enabled": True, "effect": "rainbow_wave",
                 "params": EffectParams((0, 120, 255), (255, 0, 160), 1.0, 1.0), "disabled": set()}


def make_icon(size: int = 256) -> QIcon:
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    g = QConicalGradient(QPointF(size / 2, size / 2), 90)
    for i, c in enumerate(["#FF3D7F", "#FFB020", "#2EE6A6", "#00C2FF", "#7C5CFF", "#FF3D7F"]):
        g.setColorAt(i / 5, QColor(c))
    p.setBrush(g)
    p.setPen(Qt.NoPen)
    p.drawRoundedRect(int(size * 0.04), int(size * 0.04), int(size * 0.92), int(size * 0.92), size * 0.26, size * 0.26)
    p.setBrush(QColor(BG))
    p.drawRoundedRect(int(size * 0.14), int(size * 0.14), int(size * 0.72), int(size * 0.72), size * 0.18, size * 0.18)
    rg = QRadialGradient(QPointF(size / 2, size / 2), size * 0.22)
    rg.setColorAt(0, QColor("#FFFFFF"))
    rg.setColorAt(0.45, QColor("#9C85FF"))
    rg.setColorAt(1, QColor(124, 92, 255, 0))
    p.setBrush(rg)
    p.drawEllipse(QPointF(size / 2, size / 2), size * 0.22, size * 0.22)
    p.end()
    return QIcon(pm)


def dark_title_bar(widget: QWidget) -> None:
    if sys.platform != "win32":
        return
    try:
        import ctypes
        hwnd = int(widget.winId())
        val = ctypes.c_int(1)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(val), 4)
        c = QColor(BG)
        colorref = ctypes.c_int(c.red() | (c.green() << 8) | (c.blue() << 16))
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(colorref), 4)
    except Exception:
        pass


class _Bridge(QObject):
    engineError = Signal(str)
    snapshot = Signal(object)


class NavButton(QPushButton):
    def __init__(self, text: str, icon_name: str, accent: str = ACCENT):
        super().__init__("  " + text)
        self.setObjectName("nav")
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setIcon(icons.icon(icon_name, MUTED, active="#FFFFFF", size=18))
        self.setIconSize(QSize(18, 18))
        self.accent = accent


class MainWindow(QMainWindow):
    def __init__(self, settings: config.Settings, start_minimized: bool = False):
        super().__init__()
        self.settings = settings
        self.backend: Backend = OpenRGBBackend(settings.host, settings.port)
        self.devices: list[LightDevice] = []
        self.zones_by_cat: dict[str, list[tuple[int, int]]] = {}
        self.cat_states: dict[str, dict] = {c.key: self._default_state() for c in CATEGORIES + [OTHER]}
        self.hw_devices: set[int] = set()
        self.snapshot = Snapshot()
        self._quitting = False
        self._connecting = False
        self._restored = False
        self.bridge = _Bridge()
        self.bridge.engineError.connect(self._engine_error)
        self.bridge.snapshot.connect(self._on_snapshot)
        self.engine = EffectEngine(self.backend, on_error=self.bridge.engineError.emit)
        self.engine.start()
        self.sensors: SensorHub | None = None

        self.setWindowTitle(f"{APP_NAME}")
        self.setWindowIcon(make_icon())
        self.resize(1360, 880)
        self.setMinimumSize(1080, 700)
        self.setStyleSheet(STYLESHEET)
        self._build_ui()

        self._device_sig = ()
        self._watch_count = 0
        self.device_watch = QTimer(self)
        self.device_watch.timeout.connect(self._watch_devices)

        self.preview_timer = QTimer(self)
        self.preview_timer.timeout.connect(self._update_preview)
        self.preview_timer.start(33)

        self._setup_tray()
        self.refresh_profiles()
        if not (start_minimized and self.tray):
            self.show()
            dark_title_bar(self)
        QTimer.singleShot(50, self.connect_backend)
        QTimer.singleShot(200, self.start_sensors)
        QTimer.singleShot(2000, self._startup_conflict_check)

    @staticmethod
    def _default_state() -> dict:
        return {"enabled": True, "effect": DEFAULT_STATE["effect"],
                "params": EffectParams(**vars(DEFAULT_STATE["params"])), "disabled": set()}

    # ================================================================ UI
    def _build_ui(self):
        root = QWidget()
        root.setObjectName("root")
        h = QHBoxLayout(root)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(0)

        # ---------------- Sidebar
        side = QWidget()
        side.setObjectName("sidebar")
        side.setFixedWidth(232)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(14, 18, 14, 14)
        sl.setSpacing(2)
        brand = QHBoxLayout()
        logo = label("")
        logo.setPixmap(make_icon(64).pixmap(30, 30))
        brand.addWidget(logo)
        bcol = QVBoxLayout()
        bcol.setSpacing(0)
        bcol.addWidget(label("RGB Control", "brand"))
        bcol.addWidget(label(f"Version {APP_VERSION}", "brandSub"))
        brand.addLayout(bcol, 1)
        sl.addLayout(brand)
        sl.addSpacing(10)

        self.nav_group = QButtonGroup(self)
        self.nav_group.setExclusive(True)
        self.nav_buttons: dict[str, NavButton] = {}
        self.stack = QStackedWidget()
        self.page_index: dict[str, int] = {}

        def section(title):
            sl.addWidget(label(title, "navSection"))

        def add_page(key, text, icon_name, widget, accent=ACCENT):
            b = NavButton(text, icon_name, accent)
            self.nav_group.addButton(b)
            b.clicked.connect(lambda: self.show_page(key))
            self.nav_buttons[key] = b
            sl.addWidget(b)
            self.page_index[key] = self.stack.addWidget(widget)

        section("ÜBERSICHT")
        self.dashboard = Dashboard()
        add_page("dashboard", "Dashboard", "dashboard", self.dashboard)
        section("BELEUCHTUNG")
        self.cat_pages: dict[str, CategoryPage] = {}
        for cat in CATEGORIES + [OTHER]:
            page = CategoryPage(cat)
            page.stateChanged.connect(self._category_changed)
            page.copyToAll.connect(self._copy_to_all)
            page.hardwareModeRequested.connect(self.hardware_mode)
            page.resizeRequested.connect(self.resize_zone)
            page.rescanRequested.connect(self.rescan)
            page.scanRequested.connect(lambda: (self.show_page("scan"), self.scan_page.scan()))
            self.cat_pages[cat.key] = page
            add_page(cat.key, cat.name, cat.icon, page, cat.accent)
        self.nav_buttons["other"].hide()
        section("WERKZEUGE")
        self.conflicts = ConflictsPage()
        self.scan_page = ScanPage(lambda: self.devices)
        self.profiles = ProfilesPage()
        self.settings_page = SettingsPage(self.settings)
        add_page("apps", "Andere Apps", "shield", self.conflicts)
        add_page("scan", "Hardware-Scan", "scan", self.scan_page)
        add_page("profiles", "Profile", "profiles", self.profiles)
        add_page("settings", "Einstellungen", "settings", self.settings_page)
        sl.addStretch()

        self.conn_card = Card(radius=12, padding=12)
        cr = QHBoxLayout()
        cr.setSpacing(8)
        self.conn_dot = label("●", "")
        self.conn_text = label("Verbinde …", "", wrap=True)
        self.conn_text.setStyleSheet(f"color: {MUTED}; font-size: 8.5pt;")
        cr.addWidget(self.conn_dot, 0, Qt.AlignTop)
        cr.addWidget(self.conn_text, 1)
        self.conn_card.lay.addLayout(cr)
        sl.addWidget(self.conn_card)
        h.addWidget(side)

        # ---------------- Inhalt
        content = QWidget()
        cl = QVBoxLayout(content)
        cl.setContentsMargins(0, 0, 0, 0)
        cl.setSpacing(0)
        top = QHBoxLayout()
        top.setContentsMargins(28, 22, 28, 14)
        tcol = QVBoxLayout()
        tcol.setSpacing(2)
        self.page_title = label("Dashboard", "pageTitle")
        self.page_sub = label("", "pageSub")
        tcol.addWidget(self.page_title)
        tcol.addWidget(self.page_sub)
        top.addLayout(tcol, 1)
        self.pill_cpu = StatPill("cpu", "CPU-Temperatur")
        self.pill_gpu = StatPill("gpu", "GPU-Temperatur")
        self.pill_ram = StatPill("ram", "Arbeitsspeicher belegt")
        self.pill_bat = StatPill("battery", "Maus-Akku")
        self.pill_bat.hide()
        for p in (self.pill_cpu, self.pill_gpu, self.pill_ram, self.pill_bat):
            top.addWidget(p, 0, Qt.AlignVCenter)
            top.addSpacing(6)
        cl.addLayout(top)

        self.banner = Card(accent=WARN, radius=12, padding=12)
        bl = QHBoxLayout()
        bl.addWidget(icon_label("alert", WARN, 18))
        self.banner_text = label("", "", wrap=True)
        bl.addWidget(self.banner_text, 1)
        self.banner_btns = QHBoxLayout()
        bl.addLayout(self.banner_btns)
        x = button("✕", "ghost")
        x.setFixedWidth(34)
        x.clicked.connect(lambda: self.banner_wrap.hide())
        bl.addWidget(x)
        self.banner.lay.addLayout(bl)
        self.banner_wrap = QWidget()
        bw = QVBoxLayout(self.banner_wrap)
        bw.setContentsMargins(28, 0, 28, 12)
        bw.addWidget(self.banner)
        self.banner_wrap.hide()
        cl.addWidget(self.banner_wrap)
        cl.addWidget(self.stack, 1)
        h.addWidget(content, 1)
        self.setCentralWidget(root)

        self.dashboard.openCategory.connect(self.show_page)
        self.dashboard.categoryToggled.connect(self._tile_toggled)
        self.dashboard.syncAll.connect(self.sync_all)
        self.dashboard.refreshBatteries.connect(lambda: self.sensors and self.sensors.refresh_batteries())
        self.profiles.saveRequested.connect(self.save_profile)
        self.profiles.loadRequested.connect(self.load_profile)
        self.profiles.deleteRequested.connect(self.delete_profile)
        self.settings_page.reconnectRequested.connect(self._reconnect_all)
        self.settings_page.changed.connect(self.settings.save)
        self.settings_page.adminRequested.connect(self.restart_as_admin)
        self.settings_page.flashRequested.connect(self.flash_test)
        self.settings_page.logRequested.connect(self.open_log_folder)
        self.settings_page.diagRequested.connect(self.update_diagnostics)
        self.settings_page.restartOpenrgbRequested.connect(lambda: self.connect_backend(kill_foreign=True))
        self.settings_page.pawnioRequested.connect(self.install_pawnio)
        self.settings_page.b_admin.setVisible(sys.platform == "win32" and not svc.is_admin())
        self.show_page("dashboard")

    SUBTITLES = {
        "dashboard": "Temperaturen, Speicher, Akku und Beleuchtung auf einen Blick",
        "apps": "Andere RGB-Programme finden, beenden oder deinstallieren",
        "scan": "Alle Bauteile im PC und ihre RGB-Fähigkeit",
        "profiles": "Beleuchtung speichern und mit einem Klick laden",
        "settings": "Start, Sensoren und Hardware-Dienst",
    }

    def show_page(self, key: str):
        if key not in self.page_index:
            return
        self.stack.setCurrentIndex(self.page_index[key])
        self.nav_buttons[key].setChecked(True)
        titles = {"dashboard": "Dashboard", "apps": "Andere Apps", "scan": "Hardware-Scan", "profiles": "Profile",
                  "settings": "Einstellungen"}
        if key in BY_KEY:
            self.page_title.setText("Beleuchtung")
            self.page_sub.setText(f"{BY_KEY[key].name} · Geräte, Effekte und Live-Werte")
        else:
            self.page_title.setText(titles.get(key, key))
            self.page_sub.setText(self.SUBTITLES.get(key, ""))
        if key == "settings":
            self.update_diagnostics()

    def show_banner(self, text: str, buttons=()):
        while self.banner_btns.count():
            w = self.banner_btns.takeAt(0).widget()
            if w:
                w.deleteLater()
        self.banner_text.setText(text)
        for t, fn in buttons:
            b = button(t, "ghost")
            b.clicked.connect(fn)
            self.banner_btns.addWidget(b)
        self.banner_wrap.show()

    def set_connection(self, state: str, text: str):
        color = {"ok": SUCCESS, "busy": WARN, "error": DANGER}.get(state, FAINT)
        self.conn_dot.setStyleSheet(f"color: {color}; font-size: 12pt;")
        self.conn_text.setText(text)

    # ================================================================ Tray
    def _setup_tray(self):
        self.tray = None
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self.tray = QSystemTrayIcon(make_icon(64), self)
        self.tray.setToolTip(APP_NAME)
        self.tray_menu = QMenu()
        self.tray_menu.setStyleSheet(STYLESHEET)
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
        m.addAction("Alles aus", lambda: self.sync_all("off", EffectParams()))
        m.addAction("Regenbogen-Welle", lambda: self.sync_all("rainbow_wave", EffectParams()))
        m.addSeparator()
        m.addAction("Beenden", self.quit)

    def show_window(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()
        dark_title_bar(self)

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
        if self.sensors:
            self.sensors.stop()
        try:
            self.backend.disconnect()
        except Exception:
            pass
        svc.stop_server()
        if self.tray:
            self.tray.hide()

    # ================================================================ Sensoren
    def start_sensors(self):
        if self.sensors:
            self.sensors.stop()
        self.sensors = SensorHub(self.bridge.snapshot.emit, read_controllers=self.settings.read_aio_sensors)
        self.sensors.start()

    def _on_snapshot(self, s: Snapshot):
        self.snapshot = s
        self.dashboard.update_snapshot(s)
        for p in self.cat_pages.values():
            p.update_snapshot(s)
        self.pill_cpu.set("–" if s.cpu_temp is None else f"{s.cpu_temp:.0f} °C", temp_color(s.cpu_temp))
        self.pill_gpu.set("–" if s.gpu_temp is None else f"{s.gpu_temp:.0f} °C", temp_color(s.gpu_temp))
        if s.ram_total:
            self.pill_ram.set(f"{s.ram_used / 1024 ** 3:.1f} GB · {s.ram_percent:.0f} %", load_color(s.ram_percent))
        if s.mice:
            m = s.mice[0]
            self.pill_bat.set("–" if m.percent is None else f"{m.percent} %{' ⚡' if m.charging else ''}",
                              battery_color(m.percent))
            self.pill_bat.setToolTip(f"Akku: {m.name}")
        self.pill_bat.setVisible(bool(s.mice))

    # ================================================================ Verbindung
    def _reconnect_all(self):
        self.connect_backend()
        self.start_sensors()

    def connect_backend(self, kill_foreign: bool = False):
        if self._connecting:
            return
        self._connecting = True
        self.banner_wrap.hide()
        self.engine.clear()
        try:
            self.backend.disconnect()
        except Exception:
            pass
        s = self.settings
        backend = OpenRGBBackend(s.host, s.port)
        local = s.host in ("127.0.0.1", "localhost")
        self.set_connection("busy", "Starte Hardware-Dienst und suche Geräte …")

        def work():
            started = False
            if kill_foreign:
                svc.stop_server()
                time.sleep(1)
            if local and not svc.server_running(s.host, s.port):
                foreign = svc.foreign_openrgb_pids()
                if foreign and kill_foreign:
                    log.info("Beende fremde OpenRGB-Prozesse %s", foreign)
                    svc.kill_pids(foreign)
                    time.sleep(1)
                elif foreign:
                    raise RuntimeError("openrgb-foreign")
                exe = svc.find_openrgb(s.openrgb_path) if s.autostart_openrgb else None
                if exe is None:
                    raise FileNotFoundError("openrgb-missing")
                svc.stop_server()
                svc.start_server(exe, s.port)
                started = True
            deadline = time.monotonic() + (60 if started else 8)
            last = None
            while time.monotonic() < deadline:
                if started and svc.server_process_alive() is False:
                    raise RuntimeError("openrgb-died")
                if svc.server_running(s.host, s.port):
                    try:
                        backend.connect()
                        break
                    except BackendError as e:
                        last = e
                time.sleep(0.5)
            else:
                raise last or BackendError("OpenRGB-Dienst antwortet nicht (Zeitüberschreitung).")
            # OpenRGB findet Geräte nach und nach -> warten, bis die Anzahl stabil ist
            prev, stable, t_end = -1, 0, time.monotonic() + (45 if started else 4)
            while time.monotonic() < t_end:
                n = len(backend.rescan())
                stable = stable + 1 if n == prev else 0
                prev = n
                if (n > 0 and stable >= 4) or stable >= 12:
                    break
                time.sleep(1)
            # gespeicherte LED-Anzahlen von ARGB-Anschlüssen wieder setzen (OpenRGB merkt sie sich nicht)
            sizes = dict(s.zone_sizes)
            changed = False
            for d in backend.list_devices():
                for z in d.zones:
                    want = sizes.get(zone_size_key(d, z.name))
                    if z.resizable and want and want != z.led_count:
                        try:
                            backend.resize_zone(d.index, z.index, int(want))
                            changed = True
                            log.info("LED-Anzahl %s › %s auf %s gesetzt", d.name, z.name, want)
                        except BackendError as e:
                            log.warning("LED-Anzahl setzen fehlgeschlagen: %s", e)
            if changed:
                backend.rescan()
            return backend

        run_async(work, on_done=self._use_backend, on_error=self._connect_failed)

    def _openrgb_log_tail(self) -> str:
        try:
            lines = (config.data_dir() / "openrgb.log").read_text(encoding="utf-8", errors="replace").splitlines()
            return " | ".join(l.strip() for l in lines[-4:] if l.strip())
        except OSError:
            return ""

    def _connect_failed(self, msg: str):
        self._connecting = False
        log.error("Verbindung fehlgeschlagen: %s", msg)
        self.set_connection("error", "Nicht verbunden")
        retry = ("Neu verbinden", self.connect_backend)
        if "openrgb-missing" in msg:
            self.show_banner("Der Hardware-Dienst OpenRGB fehlt. Er gehört in den Ordner „OpenRGB“ neben "
                             "RGBControl.exe. Bitte die komplette ZIP aus GitHub verwenden (nicht nur die .exe).",
                             [("OpenRGB herunterladen", lambda: webbrowser.open(svc.DOWNLOAD_URL)), retry])
        elif "openrgb-foreign" in msg:
            self.show_banner("OpenRGB läuft bereits, aber ohne SDK-Server – darüber kann diese App nicht steuern. "
                             "Soll RGB Control das laufende OpenRGB beenden und selbst starten?",
                             [("OpenRGB neu starten", lambda: self.connect_backend(kill_foreign=True))])
        elif "openrgb-died" in msg:
            tail = self._openrgb_log_tail()
            self.show_banner("OpenRGB hat sich direkt nach dem Start beendet." + (f" Meldung: {tail}" if tail else "") +
                             " Evtl. fehlt die Microsoft Visual C++ Runtime.",
                             [("VC++ Runtime laden", lambda: webbrowser.open(
                                 "https://aka.ms/vs/17/release/vc_redist.x64.exe")), retry])
        else:
            self.show_banner(f"Keine Verbindung zu OpenRGB: {msg}", [retry])

    def _use_backend(self, backend: Backend):
        self._connecting = False
        self.backend = backend
        self.engine.set_backend(backend)
        self._load_devices(backend.list_devices())
        info = backend.info()
        log.info("Backend bereit: %s, %d Geräte: %s", info, len(self.devices),
                 "; ".join(f"{d.index}:{d.name} [{d.type_key}] zones={[(z.name, z.led_count) for z in d.zones]} "
                           f"per_led={d.per_led}" for d in self.devices))
        self.set_connection("ok", f"Verbunden mit OpenRGB\n{len(self.devices)} Geräte · "
                                  f"{sum(d.led_count for d in self.devices)} LEDs")
        self._check_setup_problems()
        self._device_sig = self._signature(self.devices)
        self._watch_count = 0
        self.device_watch.start(10000)
        if not self._restored:
            self._restored = True
            if self.settings.restore_last_on_start and self.settings.last_state.get("version") == 2:
                self.apply_state(self.settings.last_state)
                return
        self.apply_all()

    def _check_setup_problems(self):
        warn, btns = [], []
        if sys.platform == "win32" and not svc.is_admin():
            warn.append("Die App läuft ohne Administratorrechte – RAM, Mainboard und Temperaturen fehlen dann.")
            btns.append(("Als Admin neu starten", self.restart_as_admin))
        if svc.pawnio_installed() is False:
            warn.append("Der Treiber PawnIO fehlt: ohne ihn findet OpenRGB keine RAM-/Mainboard-Beleuchtung "
                        "und die CPU-Temperatur bleibt leer.")
            btns.append(("PawnIO installieren", self.install_pawnio))
        zero = [f"{d.name} › {z.name}" for d in self.devices for z in d.zones if z.led_count == 0 and z.resizable]
        if zero:
            warn.append(f"{len(zero)} ARGB-Anschluss/Anschlüsse haben 0 LEDs eingestellt (z. B. {zero[0]}) – "
                        "dort angeschlossene Lüfter leuchten erst nach „LEDs festlegen“ auf der Seite Fans.")
            btns.append(("Zu Fans", lambda: self.show_page("fans")))
        if not self.devices:
            warn.append("OpenRGB hat keine Geräte gefunden. Andere RGB-Programme beenden, PC neu starten und "
                        "„Neu scannen“.")
            btns.append(("Andere Apps", lambda: self.show_page("apps")))
        if warn:
            self.show_banner(" ".join(warn), btns)

    def install_pawnio(self):
        if not svc.run_pawnio_installer():
            webbrowser.open("https://pawnio.eu")
        QMessageBox.information(self, "PawnIO", "Nach der Installation von PawnIO bitte den PC neu starten "
                                                "und RGB Control erneut öffnen.")

    @staticmethod
    def _signature(devices: list[LightDevice]):
        return tuple((d.name, d.location, tuple((z.name, z.led_count) for z in d.zones)) for d in devices)

    def _watch_devices(self):
        """Prüft regelmäßig, ob OpenRGB neue Geräte gefunden hat (Erkennung läuft verzögert)."""
        if not self.backend.connected or self._connecting:
            return
        self._watch_count += 1
        if self._watch_count == 12:
            self.device_watch.setInterval(60000)

        def done(devs):
            sig = self._signature(devs)
            if sig != self._device_sig:
                log.info("Geräteliste hat sich geändert (%d -> %d Geräte)", len(self.devices), len(devs))
                self._device_sig = sig
                self._load_devices(devs)
                self.engine.release()
                self.apply_all()
                self.set_connection("ok", f"Verbunden mit OpenRGB\n{len(self.devices)} Geräte · "
                                          f"{sum(d.led_count for d in self.devices)} LEDs")

        run_async(self.backend.rescan, on_done=done, on_error=self._engine_error)

    def _load_devices(self, devices: list[LightDevice]):
        self.devices = devices
        self.zones_by_cat = group_zones(devices)
        idx = {d.index: d for d in devices}
        for key, page in self.cat_pages.items():
            zones = self.zones_by_cat.get(key, [])
            page.set_devices(devices, zones)
            st = self.cat_states[key]
            page.set_state(st["enabled"], st["effect"], st["params"], st["disabled"])
            n_dev = len({d for d, _ in zones})
            n_led = sum(idx[d].zones[z].led_count for d, z in zones)
            if key in self.dashboard.tiles:
                self.dashboard.tiles[key].set_info(n_dev, n_led, EFFECTS_BY_KEY[st["effect"]].name)
                self.dashboard.tiles[key].set_enabled(st["enabled"])
            b = self.nav_buttons[key]
            b.setText(f"  {BY_KEY[key].name}" + (f"   {n_dev}" if n_dev else ""))
        self.nav_buttons["other"].setVisible(bool(self.zones_by_cat.get("other")))

    def rescan(self):
        if not self.backend.connected:
            self.connect_backend()
            return
        self.hw_devices.clear()

        def done(d):
            self._device_sig = self._signature(d)
            self._load_devices(d)
            self.engine.release()
            self.apply_all()

        run_async(self.backend.rescan, on_done=done, on_error=self._engine_error)

    def _engine_error(self, msg: str):
        log.error("Engine/Backend-Fehler: %s", msg)
        if not self.backend.connected:
            self.device_watch.stop()
            self.set_connection("error", "Verbindung verloren")
            self.show_banner(f"Verbindung zum Hardware-Dienst verloren: {msg}",
                             [("Neu verbinden", self.connect_backend)])

    def restart_as_admin(self):
        if svc.relaunch_as_admin():
            self.quit()
        else:
            QMessageBox.warning(self, "Administrator", "Neustart mit Administratorrechten nicht möglich.")

    def _startup_conflict_check(self):
        from ..conflicts import find_conflicts
        run_async(find_conflicts, on_done=self._startup_conflicts)

    def _startup_conflicts(self, found):
        running = [f for f in found if f.running]
        if running:
            log.info("Laufende RGB-Programme: %s", [f.app.name for f in running])
            self.show_banner("Andere RGB-Programme laufen und überschreiben deine Farben: " +
                             ", ".join(f.app.name for f in running) + ". Bitte beenden.",
                             [("Jetzt beenden", lambda: self._kill_conflicts(running)),
                              ("Anzeigen", lambda: (self.show_page("apps"), self.conflicts.show_found(found)))])

    def _kill_conflicts(self, running):
        from ..conflicts import kill_processes
        for f in running:
            kill_processes(f)
        self.banner_wrap.hide()
        self.engine.release()
        self.apply_all()

    # ================================================================ Diagnose
    def flash_test(self):
        """Lässt alle erkannten Zonen 2 Sekunden rot leuchten."""
        zones = [(d.index, z.index) for d in self.devices for z in d.zones]
        targets = build_targets(self.devices, zones)
        self.engine.flash(targets, (255, 0, 0), 2.0)
        log.info("Testblitz an %d Zonen", len(targets))
        QTimer.singleShot(2600, self.update_diagnostics)

    def update_diagnostics(self):
        info = self.backend.info()
        lines = [f"Verbindung: {'verbunden' if info.get('connected') else 'NICHT verbunden'}"
                 + (f" (Port {info.get('port')}, SDK-Protokoll {info.get('protocol')})" if info.get("connected") else "")]
        exe = svc.find_openrgb(self.settings.openrgb_path)
        lines.append(f"OpenRGB: {exe if exe else 'nicht gefunden'}")
        alive = svc.server_process_alive()
        lines.append("OpenRGB-Prozess: " + {None: "nicht von dieser App gestartet", True: "läuft",
                                             False: "beendet!"}[alive])
        lines.append(f"Administrator: {'ja' if svc.is_admin() else 'NEIN'}")
        pawn = svc.pawnio_installed()
        lines.append("PawnIO-Treiber: " + {None: "unbekannt", True: "installiert", False: "FEHLT"}[pawn])
        lines.append(f"Geräte: {len(self.devices)} · LEDs: {sum(d.led_count for d in self.devices)} · "
                     f"gesendete Bilder: {self.engine.frames_sent}")
        for d in self.devices:
            err = self.engine.device_errors.get(d.index)
            mode = "Einzel-LED" if d.per_led else "nur Modusfarbe"
            lines.append(f"  • {d.name} [{d.type_name}] – {d.led_count} LEDs, {mode}" + (f" – FEHLER: {err}" if err else ""))
        lines.append(f"Logdatei: {config.data_dir() / 'rgbcontrol.log'}")
        self.settings_page.set_diagnostics("\n".join(lines))

    def open_log_folder(self):
        from PySide6.QtCore import QUrl
        from PySide6.QtGui import QDesktopServices
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(config.data_dir())))

    # ================================================================ Beleuchtung
    def apply_category(self, key: str):
        st = self.cat_states[key]
        zones = [(d, z) for d, z in self.zones_by_cat.get(key, []) if d not in self.hw_devices]
        idx = {d.index: d for d in self.devices}
        zones = [(d, z) for d, z in zones if (idx[d].key, idx[d].zones[z].name) not in st["disabled"]]
        targets = build_targets(self.devices, zones)
        self.engine.set_group(key, targets, st["effect"] if st["enabled"] else "off", st["params"])
        if key in self.dashboard.tiles:
            self.dashboard.tiles[key].set_info(len({d for d, _ in self.zones_by_cat.get(key, [])}),
                                               sum(idx[d].zones[z].led_count for d, z in self.zones_by_cat.get(key, [])),
                                               EFFECTS_BY_KEY[st["effect"]].name if st["enabled"] else "aus")

    def apply_all(self):
        for key in self.cat_states:
            self.apply_category(key)

    def _category_changed(self, key: str):
        page = self.cat_pages[key]
        s = page.state()
        st = self.cat_states[key]
        st["enabled"], st["effect"], st["params"] = s["enabled"], s["effect"], s["params"]
        st["disabled"] = {tuple(x) for x in s["disabled"]}
        cat_devices = {d for d, _ in self.zones_by_cat.get(key, [])}
        if self.hw_devices & cat_devices:
            self.hw_devices -= cat_devices
            self.engine.release(cat_devices)
        if key in self.dashboard.tiles:
            self.dashboard.tiles[key].set_enabled(st["enabled"])
        self.apply_category(key)

    def _tile_toggled(self, key: str, on: bool):
        st = self.cat_states[key]
        st["enabled"] = on
        self.cat_pages[key].set_state(st["enabled"], st["effect"], st["params"], st["disabled"])
        self.apply_category(key)

    def _copy_to_all(self, key: str):
        st = self.cat_states[key]
        self.sync_all(st["effect"], st["params"])

    def sync_all(self, effect: str, params: EffectParams):
        self.hw_devices.clear()
        self.engine.release()
        for key, st in self.cat_states.items():
            st["effect"], st["params"] = effect, EffectParams(**vars(params))
            st["enabled"] = True
            self.cat_pages[key].set_state(True, effect, st["params"], st["disabled"])
            if key in self.dashboard.tiles:
                self.dashboard.tiles[key].set_enabled(True)
        self.apply_all()

    def hardware_mode(self, dev: int, mode: str, save: bool):
        self.hw_devices.add(dev)
        self.engine.remove_devices({dev})
        try:
            p = next((st["params"] for k, st in self.cat_states.items()
                      if any(d == dev for d, _ in self.zones_by_cat.get(k, []))), EffectParams())
            self.backend.set_hardware_mode(dev, mode, p.color)
            if save:
                self.backend.save_to_device(dev)
            self.show_toast(f"Hardware-Modus „{mode}“ gesetzt" + (" und im Gerät gespeichert" if save else ""))
        except BackendError as e:
            QMessageBox.warning(self, "Hardware-Modus", str(e))

    def resize_zone(self, dev: int, zone: int, size: int):
        try:
            d = next(x for x in self.devices if x.index == dev)
            self.settings.zone_sizes[zone_size_key(d, d.zones[zone].name)] = size
            self.settings.save()
            self.backend.resize_zone(dev, zone, size)
            self.engine.release({dev})
            devs = self.backend.list_devices()
            self._device_sig = self._signature(devs)
            self._load_devices(devs)
            self.apply_all()
        except BackendError as e:
            QMessageBox.warning(self, "LED-Anzahl", str(e))

    def show_toast(self, text: str):
        self.page_sub.setText(text)
        QTimer.singleShot(4000, lambda: self.show_page(self._current_key()))

    def _current_key(self) -> str:
        i = self.stack.currentIndex()
        return next((k for k, v in self.page_index.items() if v == i), "dashboard")

    def _update_preview(self):
        if not self.isVisible():
            return
        frame = self.engine.preview
        w = self.stack.currentWidget()
        if w is self.dashboard:
            self.dashboard.update_preview(frame, self.zones_by_cat)
        elif isinstance(w, CategoryPage):
            w.update_preview(frame)

    # ================================================================ Zustand / Profile
    def current_state(self) -> dict:
        cats = {}
        for key, st in self.cat_states.items():
            p = st["params"]
            cats[key] = {"enabled": st["enabled"], "effect": st["effect"],
                         "params": {"color": list(p.color), "color2": list(p.color2), "speed": p.speed,
                                    "brightness": p.brightness},
                         "disabled": sorted([list(x) for x in st["disabled"]])}
        return {"version": 2, "categories": cats}

    def apply_state(self, data: dict):
        try:
            for key, c in data.get("categories", {}).items():
                if key not in self.cat_states:
                    continue
                pr = c.get("params", {})
                self.cat_states[key] = {
                    "enabled": bool(c.get("enabled", True)),
                    "effect": c.get("effect", "static") if c.get("effect") in EFFECTS_BY_KEY else "static",
                    "params": EffectParams(tuple(pr.get("color", (0, 120, 255))), tuple(pr.get("color2", (255, 0, 160))),
                                           float(pr.get("speed", 1.0)), float(pr.get("brightness", 1.0))),
                    "disabled": {tuple(x) for x in c.get("disabled", [])}}
            for key, page in self.cat_pages.items():
                st = self.cat_states[key]
                page.set_state(st["enabled"], st["effect"], st["params"], st["disabled"])
                if key in self.dashboard.tiles:
                    self.dashboard.tiles[key].set_enabled(st["enabled"])
            self.hw_devices.clear()
            self.apply_all()
        except (TypeError, ValueError, KeyError, AttributeError) as e:
            QMessageBox.warning(self, "Profil", f"Profil fehlerhaft: {e}")

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
        self.show_toast(f"Profil „{n}“ gespeichert")

    def load_profile(self, name: str):
        try:
            data = config.load_profile(name)
        except (OSError, ValueError) as e:
            QMessageBox.warning(self, "Profil", f"Profil konnte nicht geladen werden:\n{e}")
            return
        if data.get("version") != 2:
            QMessageBox.information(self, "Profil", "Dieses Profil stammt aus Version 1 und kann nicht geladen werden.")
            return
        self.apply_state(data)
        self.show_toast(f"Profil „{name}“ geladen")

    def delete_profile(self, name: str):
        config.delete_profile(name)
        self.refresh_profiles()
