"""RGB Control - Startpunkt.

Start:  python main.py              (normal)
        python main.py --minimized  (nur im Tray starten)
        python main.py --selftest ergebnis.json  (Selbsttest, z.B. im GitHub-Build)
"""
from __future__ import annotations

import os
import sys


def selftest(out_path: str) -> int:
    """Prüft OpenRGB-Verbindung, Sensoren, Akku-Abfrage und Oberfläche und schreibt ein JSON-Protokoll."""
    import json
    import tempfile
    import time
    import traceback
    from dataclasses import asdict
    from pathlib import Path

    os.environ["APPDATA"] = tempfile.mkdtemp()  # echte Einstellungen nicht anfassen
    from rgbcontrol import logsetup, openrgb_service as svc
    logsetup.setup()
    svc.unblock_app_files()
    result: dict = {"ok": True, "errors": []}

    def step(name, fn):
        try:
            result[name] = fn()
        except Exception:  # noqa: BLE001
            result["ok"] = False
            result["errors"].append(f"{name}: {traceback.format_exc()}")

    def openrgb():
        from rgbcontrol.backend import OpenRGBBackend
        exe = svc.find_openrgb()
        if exe is None:
            raise RuntimeError("OpenRGB.exe nicht gefunden (Ordner 'OpenRGB' fehlt)")
        port = 6799
        # OpenRGB-eigene Testgeräte einschalten (Debug-Geräte), damit der Farbweg komplett geprüft werden kann
        ocfg = Path(os.environ["APPDATA"]) / "OpenRGB"
        ocfg.mkdir(parents=True, exist_ok=True)
        (ocfg / "OpenRGB.json").write_text(json.dumps({"Debug": {
            "devices": [{"type": t} for t in ("motherboard", "dram", "gpu", "keyboard", "argb")],
            "dummy_motherboard": True, "dummy_dram": True, "dummy_gpu": True, "dummy_keyboard": True}}),
            encoding="utf-8")
        svc.start_server(exe, port)
        b = OpenRGBBackend("127.0.0.1", port)
        try:
            t_end = time.monotonic() + 90
            while time.monotonic() < t_end:
                if svc.server_process_alive() is False:
                    from rgbcontrol.config import data_dir
                    tail = (data_dir() / "openrgb.log").read_text(encoding="utf-8", errors="replace")[-2000:]
                    raise RuntimeError("OpenRGB hat sich beendet. Ausgabe:\n" + tail)
                if svc.server_running("127.0.0.1", port):
                    try:
                        b.connect()
                        break
                    except Exception:
                        pass
                time.sleep(1)
            if not b.connected:
                raise RuntimeError("Keine Verbindung zum OpenRGB-SDK-Server")
            devs = b.rescan()
            info = b.info()
            # Ende-zu-Ende: Farben über denselben Weg wie die App setzen und aus OpenRGB zurücklesen
            verified, failed = [], []
            for d in devs:
                try:
                    b.prepare_direct(d.index)
                    for z in d.zones:
                        if z.led_count:
                            b.set_zone_colors(d.index, z.index, [(255, 0, 0)] * z.led_count)
                except Exception as e:  # noqa: BLE001
                    failed.append(f"{d.name}: {e}")
            time.sleep(0.5)
            b.rescan()
            for i, d in enumerate(b._client.devices):
                cols = [(c.red, c.green, c.blue) for c in d.colors]
                (verified if cols and all(c == (255, 0, 0) for c in cols) else failed).append(
                    d.name if cols and all(c == (255, 0, 0) for c in cols) else f"{d.name}: Farben {cols[:3]}")
            from rgbcontrol.config import data_dir
            log_tail = (data_dir() / "openrgb.log").read_text(encoding="utf-8", errors="replace")[-1500:]
            return {"exe": str(exe), "protocol": info.get("protocol"), "devices": [d.name for d in devs],
                    "verified": verified, "failed": failed, "openrgb_log": log_tail}
        finally:
            b.disconnect()
            svc.stop_server()

    def sensors():
        from rgbcontrol.sensors import SensorHub
        hub = SensorHub(enable_hardware=True)
        hub._init_providers()
        readings = hub._read_all()
        snap = hub.build_snapshot(readings)
        for p in hub._providers:
            p.close()
        return {"source": hub._source, "notes": hub._init_notes, "readings": len(readings),
                "snapshot": asdict(snap)}

    def batteries():
        from rgbcontrol.sensors import battery
        mice, errors = battery.read_all()
        return {"mice": [asdict(m) for m in mice], "errors": errors}

    def ui():
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from PySide6.QtCore import QTimer
        from PySide6.QtWidgets import QApplication

        from rgbcontrol import config
        from rgbcontrol.ui.main_window import MainWindow
        app = QApplication.instance() or QApplication(sys.argv[:1])
        w = MainWindow(config.Settings(restore_last_on_start=False, port=6798))
        state = {}

        def finish():
            state["connected"] = w.backend.connected
            state["connection_text"] = w.conn_text.text()
            w.update_diagnostics()
            state["diagnostics"] = w.settings_page.diag_text.text()
            w.quit()

        QTimer.singleShot(int(os.environ.get("SELFTEST_UI_MS", "40000")), finish)
        app.exec()
        state["pages"] = len(w.page_index)
        return state

    step("openrgb", openrgb)
    step("sensors", sensors)
    step("batteries", batteries)
    step("ui", ui)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False, default=str)
    return 0 if result["ok"] else 1


def main() -> int:
    if "--selftest" in sys.argv:
        i = sys.argv.index("--selftest")
        return selftest(sys.argv[i + 1] if i + 1 < len(sys.argv) else "selftest.json")

    from PySide6.QtCore import QLockFile
    from PySide6.QtWidgets import QApplication, QMessageBox

    from rgbcontrol import APP_NAME, APP_VERSION, config, logsetup, openrgb_service as svc
    from rgbcontrol.ui.main_window import MainWindow

    logsetup.setup()
    import logging
    logging.getLogger("main").info("===== %s %s startet (Admin=%s) =====", APP_NAME, APP_VERSION, svc.is_admin())
    svc.unblock_app_files()

    if sys.platform == "win32":
        try:  # eigenes Taskleisten-Symbol statt Python-Symbol
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("RGBControl.App")
        except Exception:
            pass

    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setQuitOnLastWindowClosed(False)

    lock = QLockFile(os.path.join(str(config.data_dir()), "app.lock"))
    if not lock.tryLock(100):
        QMessageBox.information(None, APP_NAME, f"{APP_NAME} läuft bereits (siehe Tray-Symbol unten rechts).")
        return 0

    settings = config.Settings.load()
    minimized = "--minimized" in sys.argv or settings.start_minimized
    win = MainWindow(settings, start_minimized=minimized)
    app.aboutToQuit.connect(lambda: lock.unlock())
    rc = app.exec()
    del win
    return rc


if __name__ == "__main__":
    sys.exit(main())
