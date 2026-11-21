@echo off
REM Startet RGB Control direkt mit Python (ohne .exe zu bauen)
cd /d "%~dp0"
if not exist .venv ( py -3 -m venv .venv 2>nul || python -m venv .venv )
call .venv\Scripts\activate.bat
pip install -q -r requirements.txt
REM Adminrechte anfordern (noetig fuer RAM/Mainboard)
powershell -Command "Start-Process '%~dp0.venv\Scripts\pythonw.exe' -ArgumentList '\"%~dp0main.py\"' -WorkingDirectory '%~dp0' -Verb RunAs"
