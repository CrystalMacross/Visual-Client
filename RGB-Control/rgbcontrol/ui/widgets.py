from __future__ import annotations

from PySide6.QtCore import QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QColorDialog, QPushButton, QWidget

from ..colors import RGB, rgb_to_hex


class ColorButton(QPushButton):
    colorChanged = Signal(tuple)

    def __init__(self, color: RGB = (0, 120, 255), parent=None):
        super().__init__(parent)
        self.setMinimumSize(90, 34)
        self.setCursor(Qt.PointingHandCursor)
        self._color = color
        self.clicked.connect(self._pick)
        self._refresh()

    def color(self) -> RGB:
        return self._color

    def setColor(self, color: RGB, emit: bool = True) -> None:
        self._color = tuple(int(c) for c in color)  # type: ignore[assignment]
        self._refresh()
        if emit:
            self.colorChanged.emit(self._color)

    def _refresh(self):
        r, g, b = self._color
        fg = "#000" if (r * 0.299 + g * 0.587 + b * 0.114) > 150 else "#fff"
        self.setText(rgb_to_hex(self._color))
        self.setStyleSheet(f"QPushButton {{ background: {rgb_to_hex(self._color)}; color: {fg};"
                           f" border: 2px solid #3A3A50; border-radius: 8px; font-weight: 600; }}")

    def _pick(self):
        c = QColorDialog.getColor(QColor(*self._color), self, "Farbe wählen")
        if c.isValid():
            self.setColor((c.red(), c.green(), c.blue()))


class Swatch(QPushButton):
    picked = Signal(tuple)

    def __init__(self, name: str, color: RGB, parent=None):
        super().__init__(parent)
        self.setFixedSize(28, 28)
        self.setToolTip(name)
        self.setCursor(Qt.PointingHandCursor)
        border = "#555" if color == (0, 0, 0) else rgb_to_hex(color)
        self.setStyleSheet(f"QPushButton {{ background: {rgb_to_hex(color)}; border: 2px solid {border};"
                           f" border-radius: 14px; }} QPushButton:hover {{ border: 2px solid white; }}")
        self.clicked.connect(lambda: self.picked.emit(color))


class LedPreview(QWidget):
    """Zeigt live die Farben aller ausgewählten Zonen als leuchtende Punkte."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.rows: list[tuple[str, list[RGB]]] = []
        self.setMinimumHeight(120)

    def set_rows(self, rows: list[tuple[str, list[RGB]]]) -> None:
        self.rows = rows
        self.setMinimumHeight(max(120, 26 * len(rows) + 16))
        self.update()

    def paintEvent(self, ev):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.fillRect(self.rect(), QColor("#101016"))
        if not self.rows:
            p.setPen(QColor("#6A6A80"))
            p.drawText(self.rect(), Qt.AlignCenter, "Keine Zone ausgewählt - links Geräte anhaken")
            return
        label_w = min(220, self.width() // 3)
        y = 8
        for label, colors in self.rows:
            p.setPen(QColor("#B9B9D0"))
            p.drawText(QRectF(8, y, label_w - 12, 20), Qt.AlignVCenter | Qt.AlignLeft,
                       p.fontMetrics().elidedText(label, Qt.ElideMiddle, label_w - 12))
            n = max(len(colors), 1)
            avail = self.width() - label_w - 10
            step = min(18.0, avail / n)
            r = max(2.0, step * 0.4)
            for i, c in enumerate(colors):
                cx = label_w + i * step + step / 2
                col = QColor(*c)
                glow = QColor(col)
                glow.setAlpha(70)
                p.setPen(Qt.NoPen)
                p.setBrush(glow)
                p.drawEllipse(QRectF(cx - r * 1.6, y + 10 - r * 1.6, r * 3.2, r * 3.2))
                p.setBrush(col)
                p.setPen(QPen(QColor("#2A2A38"), 0.5))
                p.drawEllipse(QRectF(cx - r, y + 10 - r, r * 2, r * 2))
            y += 26
