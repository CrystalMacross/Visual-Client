"""RGB Control - Startpunkt.

Start:  python main.py            (normal)
        python main.py --minimized (nur im Tray starten)
        python main.py --demo      (Demo-Modus ohne Hardware)
        python main.py --selftest ergebnis.json  (Sensor-/UI-Selbsttest, z.B. im CI)
"""
from __future__ import annotations

import os
import sys


def selftest(out_path: str) -> int:
    """Prüft Sensoren, Akku-Abfrage und Oberfläche ohne Benutzer und schreibt ein JSON-Protokoll."""
    import json
    import traceback
    from dataclasses import asdict

    result: dict = {"ok": True, "errors": []}

    def step(name, fn):
        try:
            result[name] = fn()
        except Exception:  # noqa: BLE001
            result["ok"] = False
            result["errors"].append(f"{name}: {traceback.format_exc()}")

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
        import tempfile
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        os.environ["APPDATA"] = tempfile.mkdtemp()  # echte Einstellungen nicht anfassen
        from PySide6.QtCore import QTimer
        from PySide6.QtWidgets import QApplication

        from rgbcontrol import config
        from rgbcontrol.ui.main_window import MainWindow
        app = QApplication.instance() or QApplication(sys.argv[:1])
        w = MainWindow(config.Settings(demo_mode=True, restore_last_on_start=False))
        QTimer.singleShot(2500, w.quit)
        app.exec()
        return {"devices": len(w.devices), "pages": len(w.page_index)}

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

    from rgbcontrol import APP_NAME, config
    from rgbcontrol.ui.main_window import MainWindow

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
    if "--demo" in sys.argv:
        settings.demo_mode = True
    minimized = "--minimized" in sys.argv or settings.start_minimized
    win = MainWindow(settings, start_minimized=minimized)
    app.aboutToQuit.connect(lambda: lock.unlock())
    rc = app.exec()
    del win
    return rc


if __name__ == "__main__":
    sys.exit(main())
