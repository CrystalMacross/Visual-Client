"""Moderne, selbst gezeichnete UI-Bausteine."""
from __future__ import annotations

import math
from collections import deque

from PySide6.QtCore import (Property, QEasingCurve, QPointF, QPropertyAnimation, QRectF, QSize, Qt,
                            QVariantAnimation, Signal)
from PySide6.QtGui import (QBrush, QColor, QConicalGradient, QFont, QImage, QLinearGradient, QPainter,
                           QPainterPath, QPen, QRadialGradient)
from PySide6.QtWidgets import (QAbstractButton, QButtonGroup, QFrame, QGridLayout, QHBoxLayout, QLabel,
                               QPushButton, QSizePolicy, QVBoxLayout, QWidget)

from ..colors import RGB, rgb_to_hex
from . import icons
from .theme import ACCENT, ACCENT_2, BORDER, FAINT, MUTED, TEXT, battery_color


def label(text: str = "", obj: str = "", wrap: bool = False) -> QLabel:
    lb = QLabel(text)
    if obj:
        lb.setObjectName(obj)
    lb.setWordWrap(wrap)
    return lb


def button(text: str, obj: str = "", icon_name: str = "", tip: str = "") -> QPushButton:
    b = QPushButton(text)
    if obj:
        b.setObjectName(obj)
    if icon_name:
        b.setIcon(icons.icon(icon_name, "#FFFFFF" if obj in ("primary", "danger") else TEXT, size=16))
        b.setIconSize(QSize(16, 16))
    if tip:
        b.setToolTip(tip)
    b.setCursor(Qt.PointingHandCursor)
    return b


def icon_label(name: str, color: str, size: int = 18) -> QLabel:
    lb = QLabel()
    lb.setPixmap(icons.pixmap(name, color, size))
    lb.setFixedSize(size, size)
    return lb


# ===================================================================== Card
class Card(QFrame):
    """Glas-Karte mit weichem Rand und optionalem farbigen Schimmer."""

    clicked = Signal()

    def __init__(self, accent: str | None = None, radius: int = 18, padding: int = 18, clickable: bool = False,
                 parent=None):
        super().__init__(parent)
        self.accent = accent
        self.radius = radius
        self.clickable = clickable
        self._hover = False
        self.setAttribute(Qt.WA_StyledBackground, False)
        self.lay = QVBoxLayout(self)
        self.lay.setContentsMargins(padding, padding, padding, padding)
        self.lay.setSpacing(10)
        if clickable:
            self.setCursor(Qt.PointingHandCursor)

    def enterEvent(self, e):
        self._hover = True
        self.update()

    def leaveEvent(self, e):
        self._hover = False
        self.update()

    def mouseReleaseEvent(self, e):
        if self.clickable and e.button() == Qt.LeftButton and self.rect().contains(e.position().toPoint()):
            self.clicked.emit()
        super().mouseReleaseEvent(e)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, self.radius, self.radius)
        g = QLinearGradient(r.topLeft(), r.bottomRight())
        a = 0.06 if (self._hover and self.clickable) else 0.04
        g.setColorAt(0, QColor(255, 255, 255, int(255 * (a + 0.015))))
        g.setColorAt(1, QColor(255, 255, 255, int(255 * a * 0.5)))
        p.fillPath(path, g)
        if self.accent:
            c = QColor(self.accent)
            rg = QRadialGradient(r.topLeft() + QPointF(30, 20), max(r.width(), r.height()) * 0.8)
            c.setAlpha(46 if self._hover and self.clickable else 30)
            rg.setColorAt(0, c)
            c2 = QColor(self.accent)
            c2.setAlpha(0)
            rg.setColorAt(1, c2)
            p.fillPath(path, rg)
        border = QLinearGradient(r.topLeft(), r.bottomRight())
        border.setColorAt(0, QColor(255, 255, 255, 34 if not self._hover or not self.clickable else 60))
        border.setColorAt(1, QColor(255, 255, 255, 10))
        p.setPen(QPen(QBrush(border), 1))
        p.drawPath(path)


# ===================================================================== Toggle
class Toggle(QAbstractButton):
    def __init__(self, checked: bool = True, accent: str = ACCENT, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(44, 24)
        self.accent = accent
        self._pos = 1.0 if checked else 0.0
        self._anim = QPropertyAnimation(self, b"pos", self)
        self._anim.setDuration(160)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self.toggled.connect(self._animate)

    def _get_pos(self):
        return self._pos

    def _set_pos(self, v):
        self._pos = v
        self.update()

    pos = Property(float, _get_pos, _set_pos)

    def _animate(self, on):
        self._anim.stop()
        self._anim.setStartValue(self._pos)
        self._anim.setEndValue(1.0 if on else 0.0)
        self._anim.start()

    def setChecked(self, on: bool):  # noqa: N802
        super().setChecked(on)
        if not self.signalsBlocked():
            return
        self._pos = 1.0 if on else 0.0
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(1, 1, self.width() - 2, self.height() - 2)
        off, on = QColor(255, 255, 255, 30), QColor(self.accent)
        c = QColor(int(off.red() + (on.red() - off.red()) * self._pos),
                   int(off.green() + (on.green() - off.green()) * self._pos),
                   int(off.blue() + (on.blue() - off.blue()) * self._pos),
                   int(off.alpha() + (255 - off.alpha()) * self._pos))
        p.setPen(Qt.NoPen)
        p.setBrush(c)
        p.drawRoundedRect(r, r.height() / 2, r.height() / 2)
        d = r.height() - 6
        x = r.left() + 3 + (r.width() - d - 6) * self._pos
        p.setBrush(QColor("white"))
        p.drawEllipse(QRectF(x, r.top() + 3, d, d))


# ===================================================================== Chips
class ChipGroup(QWidget):
    """Auswahl als Pillen-Buttons (exklusiv)."""
    changed = Signal(str)

    def __init__(self, items: list[tuple[str, str]], columns: int = 0, parent=None):
        super().__init__(parent)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.buttons: dict[str, QPushButton] = {}
        lay = QGridLayout(self) if columns else QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(8)
        for i, (key, text) in enumerate(items):
            b = QPushButton(text)
            b.setObjectName("chip")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            self.group.addButton(b)
            self.buttons[key] = b
            b.clicked.connect(lambda _=False, k=key: self.changed.emit(k))
            if columns:
                lay.addWidget(b, i // columns, i % columns)
            else:
                lay.addWidget(b)
        if not columns:
            lay.addStretch()

    def set_current(self, key: str) -> None:
        if key in self.buttons:
            self.buttons[key].setChecked(True)

    def current(self) -> str:
        for k, b in self.buttons.items():
            if b.isChecked():
                return k
        return ""


# ===================================================================== Sparkline
class Sparkline(QWidget):
    def __init__(self, color: str = ACCENT_2, maxlen: int = 90, parent=None):
        super().__init__(parent)
        self.values: deque = deque(maxlen=maxlen)
        self.color = color
        self.setMinimumHeight(34)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def add(self, v: float | None, color: str | None = None):
        if v is not None:
            self.values.append(v)
        if color:
            self.color = color
        self.update()

    def paintEvent(self, e):
        if len(self.values) < 2:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        lo, hi = min(self.values), max(self.values)
        if hi - lo < 4:
            mid = (hi + lo) / 2
            lo, hi = mid - 2, mid + 2
        w, h = self.width(), self.height() - 4
        n = self.values.maxlen
        pts = [QPointF(w - (len(self.values) - 1 - i) * w / (n - 1), 2 + h - (v - lo) / (hi - lo) * h)
               for i, v in enumerate(self.values)]
        path = QPainterPath(pts[0])
        for a, b in zip(pts, pts[1:]):
            mx = (a.x() + b.x()) / 2
            path.cubicTo(QPointF(mx, a.y()), QPointF(mx, b.y()), b)
        fill = QPainterPath(path)
        fill.lineTo(pts[-1].x(), self.height())
        fill.lineTo(pts[0].x(), self.height())
        fill.closeSubpath()
        g = QLinearGradient(0, 0, 0, self.height())
        c = QColor(self.color)
        c.setAlpha(70)
        g.setColorAt(0, c)
        c.setAlpha(0)
        g.setColorAt(1, c)
        p.fillPath(fill, g)
        p.setPen(QPen(QColor(self.color), 1.8))
        p.drawPath(path)


# ===================================================================== Gauge
class Gauge(QWidget):
    """Runde Anzeige (270°) mit animiertem Bogen."""

    def __init__(self, unit: str = "°C", maximum: float = 100, size: int = 132, thickness: int = 10, parent=None):
        super().__init__(parent)
        self.unit, self.maximum, self.thickness = unit, maximum, thickness
        self.value: float | None = None
        self._shown = 0.0
        self.color = FAINT
        self.caption = ""
        self.setFixedSize(size, size)
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(600)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self._anim.valueChanged.connect(self._on_anim)

    def _on_anim(self, v):
        self._shown = float(v)
        self.update()

    def set_value(self, v: float | None, color: str, caption: str = "") -> None:
        self.value, self.color, self.caption = v, color, caption
        target = 0.0 if v is None else max(0.0, min(1.0, v / self.maximum))
        self._anim.stop()
        self._anim.setStartValue(self._shown)
        self._anim.setEndValue(target)
        self._anim.start()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        t = self.thickness
        r = QRectF(t / 2 + 2, t / 2 + 2, self.width() - t - 4, self.height() - t - 4)
        pen = QPen(QColor(255, 255, 255, 22), t, Qt.SolidLine, Qt.RoundCap)
        p.setPen(pen)
        p.drawArc(r, 225 * 16, -270 * 16)
        if self._shown > 0.003:
            g = QConicalGradient(r.center(), 225)
            c1 = QColor(self.color)
            c0 = QColor(self.color)
            c0.setAlpha(110)
            g.setColorAt(0.0, c1)
            g.setColorAt(0.75, c0)
            g.setColorAt(1.0, c1)
            glow = QColor(self.color)
            glow.setAlpha(40)
            p.setPen(QPen(glow, t + 8, Qt.SolidLine, Qt.RoundCap))
            p.drawArc(r, 225 * 16, int(-270 * 16 * self._shown))
            p.setPen(QPen(QBrush(QColor(self.color)), t, Qt.SolidLine, Qt.RoundCap))
            p.drawArc(r, 225 * 16, int(-270 * 16 * self._shown))
        p.setPen(QColor(TEXT))
        f = QFont(self.font())
        f.setPointSizeF(self.width() / 7.2)
        f.setWeight(QFont.Bold)
        p.setFont(f)
        txt = "–" if self.value is None else f"{self.value:.0f}"
        p.drawText(QRectF(0, self.height() * 0.28, self.width(), self.height() * 0.32), Qt.AlignCenter, txt)
        f.setPointSizeF(self.width() / 14)
        f.setWeight(QFont.Medium)
        p.setFont(f)
        p.setPen(QColor(MUTED))
        p.drawText(QRectF(0, self.height() * 0.58, self.width(), self.height() * 0.14), Qt.AlignCenter,
                   self.caption or self.unit)


# ===================================================================== Battery
class BatteryIcon(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.percent: int | None = None
        self.charging = False
        self.setFixedSize(54, 26)

    def set_state(self, percent: int | None, charging: bool):
        self.percent, self.charging = percent, charging
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        body = QRectF(1, 2, self.width() - 8, self.height() - 4)
        p.setPen(QPen(QColor(255, 255, 255, 120), 1.6))
        p.setBrush(Qt.NoBrush)
        p.drawRoundedRect(body, 6, 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 120))
        p.drawRoundedRect(QRectF(body.right() + 1.5, body.center().y() - 4, 3.5, 8), 1.5, 1.5)
        if self.percent is not None:
            inner = body.adjusted(3, 3, -3, -3)
            inner.setWidth(max(3.0, inner.width() * max(0, min(100, self.percent)) / 100))
            c = QColor(battery_color(self.percent))
            g = QLinearGradient(inner.topLeft(), inner.topRight())
            g.setColorAt(0, c.darker(115))
            g.setColorAt(1, c)
            p.setBrush(g)
            p.drawRoundedRect(inner, 3.5, 3.5)
        if self.charging:
            p.drawPixmap(int(body.center().x() - 8), int(body.center().y() - 8), icons.pixmap("bolt", "#FFFFFF", 16))


class BatteryRow(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 2, 0, 2)
        lay.setSpacing(12)
        self.icon = BatteryIcon()
        col = QVBoxLayout()
        col.setSpacing(0)
        self.name = label("", "")
        self.name.setStyleSheet("font-weight: 600;")
        self.sub = label("", "faint")
        col.addWidget(self.name)
        col.addWidget(self.sub)
        self.value = label("", "")
        self.value.setStyleSheet("font-size: 15pt; font-weight: 700;")
        lay.addWidget(self.icon)
        lay.addLayout(col, 1)
        lay.addWidget(self.value)

    def set_mouse(self, m):
        self.icon.set_state(m.percent, m.charging)
        self.name.setText(m.name)
        state = "lädt" if m.charging else ("" if m.percent is None else "Akku")
        self.sub.setText(" · ".join(x for x in (m.source, state, m.level_text) if x))
        self.value.setText("–" if m.percent is None else f"{m.percent} %")
        self.value.setStyleSheet(f"font-size: 15pt; font-weight: 700; color: {battery_color(m.percent)};")


# ===================================================================== LED-Leiste
class LedStrip(QWidget):
    """Zeigt Farben als leuchtende Punkte; mehrere Zeilen möglich."""

    def __init__(self, rows: int = 1, dot: float = 9, parent=None):
        super().__init__(parent)
        self.rows: list[tuple[str, list[RGB]]] = []
        self.dot = dot
        self.show_labels = rows > 1
        self.setMinimumHeight(int(dot * 2 + 6))
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def set_rows(self, rows: list[tuple[str, list[RGB]]]):
        self.rows = rows
        h = int((self.dot * 2 + 10) * max(1, len(rows))) if self.show_labels else int(self.dot * 2 + 6)
        if self.minimumHeight() != h:
            self.setMinimumHeight(h)
            self.setMaximumHeight(h)
        self.update()

    def set_colors(self, colors: list[RGB]):
        self.set_rows([("", colors)])

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        if not self.rows or not any(c for _, c in self.rows):
            p.setPen(QColor(FAINT))
            p.drawText(self.rect(), Qt.AlignVCenter | Qt.AlignLeft, "aus")
            return
        row_h = self.dot * 2 + 10 if self.show_labels else self.height()
        label_w = min(170, self.width() // 3) if self.show_labels else 0
        for ri, (name, colors) in enumerate(self.rows):
            y = ri * row_h + row_h / 2
            if self.show_labels:
                p.setPen(QColor(MUTED))
                p.drawText(QRectF(0, y - 9, label_w - 8, 18), Qt.AlignVCenter | Qt.AlignLeft,
                           p.fontMetrics().elidedText(name, Qt.ElideMiddle, label_w - 8))
            n = max(1, len(colors))
            avail = self.width() - label_w
            step = min(self.dot * 2.2, avail / n)
            rad = max(1.5, min(self.dot, step * 0.42))
            for i, c in enumerate(colors):
                cx = label_w + i * step + step / 2
                col = QColor(*c)
                if c != (0, 0, 0):
                    g = QRadialGradient(QPointF(cx, y), rad * 2.4)
                    gc = QColor(col)
                    gc.setAlpha(90)
                    g.setColorAt(0, gc)
                    gc.setAlpha(0)
                    g.setColorAt(1, gc)
                    p.setPen(Qt.NoPen)
                    p.setBrush(g)
                    p.drawEllipse(QPointF(cx, y), rad * 2.4, rad * 2.4)
                p.setBrush(col if c != (0, 0, 0) else QColor(255, 255, 255, 18))
                p.setPen(Qt.NoPen)
                p.drawEllipse(QPointF(cx, y), rad, rad)


# ===================================================================== Farbrad
class ColorWheel(QWidget):
    colorChanged = Signal(tuple)

    def __init__(self, size: int = 200, parent=None):
        super().__init__(parent)
        self.setFixedSize(size, size)
        self.setCursor(Qt.CrossCursor)
        self.h, self.s = 0.7, 1.0
        self._img: QImage | None = None

    def color(self) -> RGB:
        c = QColor.fromHsvF(self.h % 1.0, self.s, 1.0)
        return c.red(), c.green(), c.blue()

    def set_color(self, rgb: RGB):
        c = QColor(*rgb)
        h, s, _v, _a = c.getHsvF()
        self.h = h if h >= 0 else self.h
        self.s = s
        self.update()

    def _wheel(self) -> QImage:
        if self._img is not None and self._img.width() == self.width() * 2:
            return self._img
        n = self.width() * 2
        img = QImage(n, n, QImage.Format_ARGB32_Premultiplied)
        img.fill(Qt.transparent)
        p = QPainter(img)
        p.setRenderHint(QPainter.Antialiasing)
        g = QConicalGradient(n / 2, n / 2, 0)
        for i in range(13):
            g.setColorAt(i / 12, QColor.fromHsvF((i / 12) % 1.0, 1, 1))
        p.setPen(Qt.NoPen)
        p.setBrush(g)
        p.drawEllipse(QRectF(4, 4, n - 8, n - 8))
        rg = QRadialGradient(n / 2, n / 2, n / 2 - 4)
        rg.setColorAt(0, QColor(255, 255, 255, 255))
        rg.setColorAt(1, QColor(255, 255, 255, 0))
        p.setBrush(rg)
        p.drawEllipse(QRectF(4, 4, n - 8, n - 8))
        p.end()
        img.setDevicePixelRatio(2)
        self._img = img
        return img

    def _pick(self, pos):
        c = QPointF(self.width() / 2, self.height() / 2)
        dx, dy = pos.x() - c.x(), c.y() - pos.y()
        rad = self.width() / 2 - 2
        self.h = (math.degrees(math.atan2(dy, dx)) / 360) % 1.0
        self.s = min(1.0, math.hypot(dx, dy) / rad)
        self.update()
        self.colorChanged.emit(self.color())

    def mousePressEvent(self, e):
        self._pick(e.position())

    def mouseMoveEvent(self, e):
        if e.buttons() & Qt.LeftButton:
            self._pick(e.position())

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.drawImage(0, 0, self._wheel())
        p.setPen(QPen(QColor(255, 255, 255, 30), 1))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(QRectF(2, 2, self.width() - 4, self.height() - 4))
        rad = self.width() / 2 - 2
        ang = self.h * 2 * math.pi
        x = self.width() / 2 + math.cos(ang) * self.s * rad
        y = self.height() / 2 - math.sin(ang) * self.s * rad
        p.setPen(QPen(QColor(0, 0, 0, 90), 4))
        p.setBrush(QColor(*self.color()))
        p.drawEllipse(QPointF(x, y), 10, 10)
        p.setPen(QPen(QColor("white"), 2.5))
        p.drawEllipse(QPointF(x, y), 10, 10)


class ColorDot(QAbstractButton):
    """Runde Farbfläche (Preset oder Farbe 1/2)."""

    def __init__(self, color: RGB, size: int = 26, tip: str = "", checkable: bool = False, parent=None):
        super().__init__(parent)
        self.c = color
        self.setFixedSize(size, size)
        self.setCheckable(checkable)
        self.setCursor(Qt.PointingHandCursor)
        if tip:
            self.setToolTip(tip)

    def set_color(self, c: RGB):
        self.c = c
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(3, 3, self.width() - 6, self.height() - 6)
        if self.isChecked() or self.underMouse():
            p.setPen(QPen(QColor("white") if self.isChecked() else QColor(255, 255, 255, 120), 2))
            p.setBrush(Qt.NoBrush)
            p.drawEllipse(QRectF(1, 1, self.width() - 2, self.height() - 2))
        p.setPen(QPen(QColor(255, 255, 255, 40), 1) if self.c == (0, 0, 0) else Qt.NoPen)
        p.setBrush(QColor(*self.c))
        p.drawEllipse(r)


class IconBadge(QWidget):
    """Icon in einem farbig leuchtenden, abgerundeten Quadrat."""

    def __init__(self, name: str, accent: str, size: int = 44, parent=None):
        super().__init__(parent)
        self.name, self.accent = name, accent
        self.setFixedSize(size, size)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        g = QLinearGradient(r.topLeft(), r.bottomRight())
        c1, c2 = QColor(self.accent), QColor(self.accent)
        c1.setAlpha(90)
        c2.setAlpha(28)
        g.setColorAt(0, c1)
        g.setColorAt(1, c2)
        p.setPen(QPen(QColor(self.accent).lighter(130), 1))
        p.setBrush(g)
        p.drawRoundedRect(r.adjusted(0.5, 0.5, -0.5, -0.5), r.width() * 0.3, r.width() * 0.3)
        s = int(r.width() * 0.52)
        p.drawPixmap(int((r.width() - s) / 2), int((r.height() - s) / 2), icons.pixmap(self.name, "#FFFFFF", s))


class Bar(QWidget):
    """Schmaler Fortschrittsbalken mit Verlauf."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.frac = 0.0
        self.color = ACCENT_2
        self.setFixedHeight(8)

    def set_value(self, frac: float, color: str):
        self.frac, self.color = max(0.0, min(1.0, frac)), color
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 20))
        p.drawRoundedRect(r, 4, 4)
        if self.frac > 0:
            fr = QRectF(r.left(), r.top(), max(8.0, r.width() * self.frac), r.height())
            g = QLinearGradient(fr.topLeft(), fr.topRight())
            g.setColorAt(0, QColor(ACCENT))
            g.setColorAt(1, QColor(self.color))
            p.setBrush(g)
            p.drawRoundedRect(fr, 4, 4)


class StatPill(QWidget):
    """Kleine Anzeige in der Kopfzeile: Icon + Wert."""

    def __init__(self, icon_name: str, tip: str = "", parent=None):
        super().__init__(parent)
        self.icon_name = icon_name
        self.text = "–"
        self.color = MUTED
        self.setToolTip(tip)
        self.setFixedHeight(32)
        self.setMinimumWidth(78)

    def set(self, text: str, color: str):
        self.text, self.color = text, color
        fm = self.fontMetrics()
        self.setMinimumWidth(fm.horizontalAdvance(text) + 46)
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        p.setPen(QPen(QColor(255, 255, 255, 22), 1))
        p.setBrush(QColor(255, 255, 255, 10))
        p.drawRoundedRect(r, r.height() / 2, r.height() / 2)
        p.drawPixmap(12, int(r.center().y() - 8), icons.pixmap(self.icon_name, self.color, 16))
        p.setPen(QColor(TEXT))
        f = QFont(self.font())
        f.setWeight(QFont.DemiBold)
        p.setFont(f)
        p.drawText(r.adjusted(34, 0, -10, 0), Qt.AlignVCenter | Qt.AlignLeft, self.text)


def hex_of(c: RGB) -> str:
    return rgb_to_hex(c)


def hline() -> QFrame:
    f = QFrame()
    f.setFixedHeight(1)
    f.setStyleSheet(f"background: {BORDER};")
    return f
