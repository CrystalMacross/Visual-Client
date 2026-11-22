"""Design-Tokens und globales Stylesheet."""

BG = "#08090E"
BG_ELEV = "#0F1119"
SURFACE = "rgba(255,255,255,0.035)"
SURFACE_HI = "rgba(255,255,255,0.06)"
BORDER = "rgba(255,255,255,0.07)"
TEXT = "#F3F4FA"
MUTED = "#8A8FA8"
FAINT = "#5B6078"
ACCENT = "#7C5CFF"
ACCENT_2 = "#00C2FF"
SUCCESS = "#2EE6A6"
WARN = "#FFB020"
DANGER = "#FF5470"
FONT = "'Segoe UI Variable Display', 'Segoe UI', 'Inter', 'Helvetica Neue', sans-serif"


def temp_color(t: float | None) -> str:
    if t is None:
        return FAINT
    if t < 50:
        return ACCENT_2
    if t < 68:
        return SUCCESS
    if t < 82:
        return WARN
    return DANGER


def load_color(p: float | None) -> str:
    if p is None:
        return FAINT
    if p < 60:
        return ACCENT_2
    if p < 85:
        return WARN
    return DANGER


def battery_color(p: int | None) -> str:
    if p is None:
        return FAINT
    if p <= 15:
        return DANGER
    if p <= 35:
        return WARN
    return SUCCESS


STYLESHEET = f"""
* {{ font-family: {FONT}; font-size: 10pt; color: {TEXT}; }}
QMainWindow, #root {{ background: {BG}; }}
QToolTip {{ background: {BG_ELEV}; color: {TEXT}; border: 1px solid {BORDER}; padding: 6px 8px; border-radius: 6px; }}

#sidebar {{ background: {BG_ELEV}; border-right: 1px solid {BORDER}; }}
#brand {{ font-size: 13pt; font-weight: 700; letter-spacing: 0.3px; }}
#brandSub {{ color: {FAINT}; font-size: 8.5pt; }}
#navSection {{ color: {FAINT}; font-size: 8pt; font-weight: 600; letter-spacing: 1.2px; padding: 14px 14px 4px 14px; }}

QPushButton#nav {{ text-align: left; padding: 9px 12px; border: none; border-radius: 10px; color: {MUTED};
    font-weight: 500; background: transparent; }}
QPushButton#nav:hover {{ background: {SURFACE_HI}; color: {TEXT}; }}
QPushButton#nav:checked {{ color: white; font-weight: 600;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 rgba(124,92,255,0.35), stop:1 rgba(0,194,255,0.10)); }}

#pageTitle {{ font-size: 21pt; font-weight: 700; }}
#pageSub {{ color: {MUTED}; }}
#h2 {{ font-size: 12pt; font-weight: 650; }}
#muted {{ color: {MUTED}; }}
#faint {{ color: {FAINT}; font-size: 8.5pt; }}
#bigValue {{ font-size: 22pt; font-weight: 700; }}

QPushButton {{ background: {SURFACE_HI}; border: 1px solid {BORDER}; border-radius: 10px; padding: 8px 14px; font-weight: 500; }}
QPushButton:hover {{ background: rgba(255,255,255,0.10); }}
QPushButton:pressed {{ background: rgba(255,255,255,0.04); }}
QPushButton:disabled {{ color: {FAINT}; }}
QPushButton#primary {{ border: none; color: white; font-weight: 600;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 {ACCENT}, stop:1 #4F7BFF); }}
QPushButton#primary:hover {{ background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #8F73FF, stop:1 #6A90FF); }}
QPushButton#danger {{ border: none; color: white; background: rgba(255,84,112,0.85); }}
QPushButton#danger:hover {{ background: {DANGER}; }}
QPushButton#ghost {{ background: transparent; border: 1px solid {BORDER}; }}
QPushButton#chip {{ border-radius: 16px; padding: 7px 14px; background: {SURFACE}; color: {MUTED}; }}
QPushButton#chip:hover {{ color: {TEXT}; background: {SURFACE_HI}; }}
QPushButton#chip:checked {{ color: white; border: 1px solid rgba(124,92,255,0.9);
    background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 rgba(124,92,255,0.55), stop:1 rgba(0,194,255,0.25)); }}

QComboBox, QLineEdit, QSpinBox {{ background: rgba(255,255,255,0.05); border: 1px solid {BORDER}; border-radius: 9px;
    padding: 7px 10px; selection-background-color: {ACCENT}; }}
QComboBox:focus, QLineEdit:focus, QSpinBox:focus {{ border: 1px solid rgba(124,92,255,0.8); }}
QComboBox::drop-down {{ border: none; width: 22px; }}
QComboBox QAbstractItemView {{ background: {BG_ELEV}; border: 1px solid {BORDER}; selection-background-color: rgba(124,92,255,0.5);
    outline: 0; padding: 4px; }}

QSlider::groove:horizontal {{ height: 6px; background: rgba(255,255,255,0.08); border-radius: 3px; }}
QSlider::sub-page:horizontal {{ border-radius: 3px;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 {ACCENT}, stop:1 {ACCENT_2}); }}
QSlider::handle:horizontal {{ background: white; width: 18px; height: 18px; margin: -6px 0; border-radius: 9px; }}

QCheckBox {{ spacing: 10px; }}
QCheckBox::indicator {{ width: 18px; height: 18px; border-radius: 6px; border: 1px solid rgba(255,255,255,0.2);
    background: rgba(255,255,255,0.04); }}
QCheckBox::indicator:checked {{ background: {ACCENT}; border: 1px solid {ACCENT}; }}

QTableWidget, QListWidget {{ background: transparent; border: none; gridline-color: transparent;
    alternate-background-color: rgba(255,255,255,0.02); outline: 0; }}
QTableWidget::item, QListWidget::item {{ padding: 8px 6px; border-bottom: 1px solid rgba(255,255,255,0.04); }}
QListWidget::item:selected, QTableWidget::item:selected {{ background: rgba(124,92,255,0.22); border-radius: 8px; }}
QHeaderView::section {{ background: transparent; border: none; border-bottom: 1px solid {BORDER}; padding: 8px 6px;
    color: {MUTED}; font-weight: 600; }}

QScrollArea {{ background: transparent; border: none; }}
QScrollArea > QWidget > QWidget {{ background: transparent; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 4px 2px; }}
QScrollBar::handle:vertical {{ background: rgba(255,255,255,0.12); border-radius: 3px; min-height: 30px; }}
QScrollBar::handle:vertical:hover {{ background: rgba(255,255,255,0.22); }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{ height: 0; background: none; }}
QScrollBar:horizontal {{ height: 0; }}
QMenu {{ background: {BG_ELEV}; border: 1px solid {BORDER}; border-radius: 10px; padding: 6px; }}
QMenu::item {{ padding: 7px 18px; border-radius: 6px; }}
QMenu::item:selected {{ background: rgba(124,92,255,0.35); }}
QMessageBox, QInputDialog, QColorDialog, QFileDialog {{ background: {BG_ELEV}; }}
"""
