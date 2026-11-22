"""Logdatei unter %APPDATA%\\RGBControl\\rgbcontrol.log (für die Fehlersuche)."""
from __future__ import annotations

import logging
import logging.handlers

from .config import data_dir


def log_path():
    return data_dir() / "rgbcontrol.log"


def setup() -> None:
    root = logging.getLogger()
    if any(isinstance(h, logging.handlers.RotatingFileHandler) for h in root.handlers):
        return
    root.setLevel(logging.INFO)
    try:
        h = logging.handlers.RotatingFileHandler(log_path(), maxBytes=1_000_000, backupCount=2, encoding="utf-8")
    except OSError:
        return
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s"))
    root.addHandler(h)
