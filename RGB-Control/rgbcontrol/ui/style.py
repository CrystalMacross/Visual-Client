ACCENT = "#7C4DFF"

STYLESHEET = f"""
* {{ font-family: 'Segoe UI', 'Inter', sans-serif; font-size: 10pt; color: #E6E6EF; }}
QMainWindow, QWidget#central {{ background: #121218; }}
QWidget#page {{ background: #17171F; }}
QListWidget#nav {{ background: #0E0E13; border: none; padding-top: 8px; outline: 0; }}
QListWidget#nav::item {{ padding: 12px 18px; border-radius: 8px; margin: 2px 8px; }}
QListWidget#nav::item:selected {{ background: {ACCENT}; color: white; }}
QListWidget#nav::item:hover:!selected {{ background: #22222D; }}
QLabel#title {{ font-size: 18pt; font-weight: 600; }}
QLabel#subtitle {{ color: #9A9AB0; }}
QLabel#brand {{ font-size: 14pt; font-weight: 700; padding: 18px 18px 6px 18px;
               color: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #FF4D8D, stop:0.5 #7C4DFF, stop:1 #00D4FF); }}
QGroupBox {{ background: #1E1E28; border: 1px solid #2A2A38; border-radius: 10px; margin-top: 14px; padding: 12px; }}
QGroupBox::title {{ subcontrol-origin: margin; left: 12px; padding: 0 4px; color: #B9B9D0; font-weight: 600; }}
QPushButton {{ background: #2A2A38; border: 1px solid #34344A; border-radius: 8px; padding: 7px 14px; }}
QPushButton:hover {{ background: #34344A; }}
QPushButton:pressed {{ background: #22222D; }}
QPushButton:disabled {{ color: #5A5A6E; }}
QPushButton#primary {{ background: {ACCENT}; border: none; color: white; font-weight: 600; }}
QPushButton#primary:hover {{ background: #8F66FF; }}
QPushButton#danger {{ background: #B3261E; border: none; color: white; }}
QPushButton#danger:hover {{ background: #CF3A31; }}
QComboBox, QLineEdit, QSpinBox {{ background: #24242F; border: 1px solid #34344A; border-radius: 6px; padding: 5px 8px; }}
QComboBox QAbstractItemView {{ background: #24242F; selection-background-color: {ACCENT}; }}
QTreeWidget, QTableWidget, QListWidget {{ background: #1A1A23; border: 1px solid #2A2A38; border-radius: 8px;
    alternate-background-color: #1E1E28; gridline-color: #2A2A38; }}
QTreeWidget::item, QTableWidget::item {{ padding: 4px; }}
QTreeWidget::item:selected, QTableWidget::item:selected, QListWidget::item:selected {{ background: #3A2E66; }}
QHeaderView::section {{ background: #22222D; border: none; padding: 6px; color: #B9B9D0; }}
QSlider::groove:horizontal {{ height: 6px; background: #2A2A38; border-radius: 3px; }}
QSlider::sub-page:horizontal {{ background: {ACCENT}; border-radius: 3px; }}
QSlider::handle:horizontal {{ background: white; width: 16px; margin: -6px 0; border-radius: 8px; }}
QCheckBox::indicator {{ width: 16px; height: 16px; }}
QStatusBar {{ background: #0E0E13; color: #9A9AB0; }}
QScrollArea {{ border: none; background: transparent; }}
QToolTip {{ background: #24242F; border: 1px solid {ACCENT}; padding: 4px; }}
"""
