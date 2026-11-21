from __future__ import annotations

import traceback

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal


class _Signals(QObject):
    done = Signal(object)
    failed = Signal(str)


class _Task(QRunnable):
    def __init__(self, fn, args):
        super().__init__()
        self.fn, self.args, self.signals = fn, args, _Signals()

    def run(self):
        try:
            res = self.fn(*self.args)
        except Exception as e:  # noqa: BLE001
            if not isinstance(e, FileNotFoundError):
                traceback.print_exc()
            self._emit(self.signals.failed, str(e))
        else:
            self._emit(self.signals.done, res)

    @staticmethod
    def _emit(sig, value):
        try:
            sig.emit(value)
        except RuntimeError:
            pass  # App wurde inzwischen beendet


_alive: set = set()


def run_async(fn, *args, on_done=None, on_error=None) -> None:
    """Führt fn im Hintergrund aus und ruft danach on_done/on_error im UI-Thread auf."""
    task = _Task(fn, args)
    holder = task.signals
    _alive.add(holder)

    def finish_ok(r):
        _alive.discard(holder)
        if on_done:
            on_done(r)

    def finish_err(m):
        _alive.discard(holder)
        if on_error:
            on_error(m)

    holder.done.connect(finish_ok)
    holder.failed.connect(finish_err)
    task.setAutoDelete(True)
    QThreadPool.globalInstance().start(task)
