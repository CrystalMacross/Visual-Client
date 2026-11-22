"""Eigene Linien-Icons als SVG (24x24, Farbe wird beim Rendern eingesetzt)."""
from __future__ import annotations

from functools import lru_cache

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QIcon, QPainter, QPixmap
from PySide6.QtSvg import QSvgRenderer

_HEAD = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{c}" '
         'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">')

PATHS = {
    "dashboard": '<rect x="3" y="3" width="7.5" height="9" rx="2"/><rect x="13.5" y="3" width="7.5" height="5.5" rx="2"/>'
                 '<rect x="13.5" y="11.5" width="7.5" height="9.5" rx="2"/><rect x="3" y="15" width="7.5" height="6" rx="2"/>',
    "gpu": '<path d="M2 6h17a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6"/><path d="M2 4v16"/><circle cx="9.5" cy="12" r="3"/>'
           '<circle cx="16" cy="12" r="2"/><path d="M6 18v2.5M10 18v2.5"/>',
    "ram": '<rect x="2" y="6" width="20" height="10" rx="1.5"/><path d="M6 9.5v3M10 9.5v3M14 9.5v3M18 9.5v3"/>'
           '<path d="M5 16v3M9 16v3M15 16v3M19 16v3"/>',
    "mouse": '<rect x="6" y="2.5" width="12" height="19" rx="6"/><path d="M12 6.5v3.5"/><path d="M6 11h12" opacity=".35"/>',
    "keyboard": '<rect x="2" y="5.5" width="20" height="13" rx="2.5"/><path d="M6 9.5h.01M10 9.5h.01M14 9.5h.01M18 9.5h.01'
                'M6 12.5h.01M10 12.5h.01M14 12.5h.01M18 12.5h.01"/><path d="M8 15.5h8"/>',
    "fan": '<circle cx="12" cy="12" r="9.5"/><circle cx="12" cy="12" r="1.6"/>'
           '<path d="M12 10.4c-.4-2.6.4-5 3-5.4 1.6-.2 2.4 1.6 1.2 2.9-1.1 1.3-2.6 2-4.2 2.5"/>'
           '<path d="M13.4 12.8c2.4 1 4 3 3 5.4-.6 1.5-2.6 1.4-3.1-.3-.5-1.6-.4-3.3.1-5.1"/>'
           '<path d="M10.6 12.6c-2 1.6-4.5 2.1-6 0-.9-1.3.2-3 1.9-2.6 1.6.4 2.9 1.4 4.1 2.6"/>',
    "pump": '<path d="M12 2.8s6.5 7 6.5 11.4A6.5 6.5 0 0 1 5.5 14.2C5.5 9.8 12 2.8 12 2.8z"/>'
            '<path d="M9 14.5a3 3 0 0 0 3 3"/>',
    "spark": '<path d="M12 3l2.2 6.3L20.5 12l-6.3 2.2L12 20.5l-2.2-6.3L3.5 12l6.3-2.7z"/>',
    "shield": '<path d="M12 2.8l7.5 3v6c0 4.6-3.2 8.2-7.5 9.4-4.3-1.2-7.5-4.8-7.5-9.4v-6z"/><path d="M9 12l2 2 4-4"/>',
    "scan": '<circle cx="11" cy="11" r="6.5"/><path d="M20.5 20.5L16 16"/>',
    "profiles": '<path d="M6 3h12a1 1 0 0 1 1 1v17l-7-4.5L5 21V4a1 1 0 0 1 1-1z"/>',
    "settings": '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0"/><circle cx="16" cy="6" r="2"/>'
                '<circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
    "thermo": '<path d="M14 14.8V4.5a2 2 0 0 0-4 0v10.3a4 4 0 1 0 4 0z"/><path d="M12 9v7"/>',
    "cpu": '<rect x="5" y="5" width="14" height="14" rx="2.5"/><rect x="9" y="9" width="6" height="6" rx="1"/>'
           '<path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/>',
    "battery": '<rect x="2.5" y="7" width="17" height="10" rx="2.5"/><path d="M22 10.5v3"/>',
    "bolt": '<path d="M13 2.5L5 13.5h6l-1 8 8-11h-6z"/>',
    "power": '<path d="M12 3v8"/><path d="M6.4 6.6a8 8 0 1 0 11.2 0"/>',
    "refresh": '<path d="M20 11a8 8 0 0 0-14.6-4.5L3.5 9"/><path d="M3.5 4v5h5"/><path d="M4 13a8 8 0 0 0 14.6 4.5l1.9-2.5"/>'
               '<path d="M20.5 20v-5h-5"/>',
    "sync": '<path d="M9 15l6-6"/><path d="M10.5 6.5l1.8-1.8a4.2 4.2 0 0 1 6 6l-1.8 1.8"/>'
            '<path d="M13.5 17.5l-1.8 1.8a4.2 4.2 0 0 1-6-6l1.8-1.8"/>',
    "palette": '<path d="M12 3a9 9 0 1 0 0 18c1.2 0 1.8-.8 1.8-1.7 0-1.3-1-1.6-1-2.8 0-1 .8-1.6 1.9-1.6H17a4 4 0 0 0 4-4'
               'C21 6.6 17 3 12 3z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7" r="1"/>',
    "chip": '<rect x="4" y="4" width="16" height="16" rx="3"/>',
    "plug": '<path d="M9 2v5M15 2v5"/><path d="M6 7h12v4a6 6 0 0 1-12 0z"/><path d="M12 17v5"/>',
    "check": '<path d="M5 12.5l4.5 4.5L19 7"/>',
    "alert": '<path d="M12 3l9.5 17h-19z"/><path d="M12 10v4.5M12 17.5h.01"/>',
    "bluetooth": '<path d="M7 7l10 10-5 4.5V2.5L17 7 7 17"/>',
}


def svg(name: str, color: str) -> bytes:
    return (_HEAD.format(c=color) + PATHS.get(name, PATHS["spark"]) + "</svg>").encode()


@lru_cache(maxsize=512)
def pixmap(name: str, color: str = "#F3F4FA", size: int = 20, dpr: float = 2.0) -> QPixmap:
    pm = QPixmap(int(size * dpr), int(size * dpr))
    pm.fill(Qt.transparent)
    r = QSvgRenderer(QByteArray(svg(name, color)))
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    r.render(p, QRectF(0, 0, size * dpr, size * dpr))
    p.end()
    pm.setDevicePixelRatio(dpr)
    return pm


def icon(name: str, color: str = "#F3F4FA", active: str | None = None, size: int = 20) -> QIcon:
    ic = QIcon(pixmap(name, color, size))
    if active:
        ic.addPixmap(pixmap(name, active, size), QIcon.Normal, QIcon.On)
        ic.addPixmap(pixmap(name, active, size), QIcon.Active, QIcon.On)
    return ic
