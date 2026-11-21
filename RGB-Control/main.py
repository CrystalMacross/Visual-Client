"""RGB Control - Startpunkt.

Start:  python main.py            (normal)
        python main.py --minimized (nur im Tray starten)
        python main.py --demo      (Demo-Modus ohne Hardware)
"""
from __future__ import annotations

import os
import sys


def main() -> int:
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
