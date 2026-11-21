@echo off
REM ============================================================
REM  RGB Control - baut die RGBControl.exe (Windows)
REM  Voraussetzung: Python 3.10+ von python.org (Haken bei "Add to PATH")
REM ============================================================
cd /d "%~dp0"
echo [1/4] Virtuelle Umgebung anlegen ...
if not exist .venv ( py -3 -m venv .venv 2>nul || python -m venv .venv )
call .venv\Scripts\activate.bat || goto :err

echo [2/4] Pakete installieren ...
python -m pip install --upgrade pip >nul
pip install -r requirements.txt pyinstaller || goto :err

echo [3/4] EXE bauen ...
pyinstaller --noconfirm --clean --windowed --uac-admin ^
  --name RGBControl --icon assets\icon.ico ^
  --add-data "assets;assets" ^
  main.py || goto :err

echo [4/4] OpenRGB-Dienst dazulegen (falls vorhanden) ...
if exist OpenRGB\OpenRGB.exe (
  xcopy /E /I /Y OpenRGB dist\RGBControl\OpenRGB >nul
  echo     OpenRGB wurde mitkopiert.
) else (
  echo     HINWEIS: Ordner "OpenRGB" nicht gefunden - siehe LIESMICH.md, Schritt 2.
)

echo.
echo ===== FERTIG! =====
echo Die App liegt in:  dist\RGBControl\RGBControl.exe
echo (Den ganzen Ordner dist\RGBControl weitergeben/verschieben, nicht nur die .exe)
pause
exit /b 0

:err
echo.
echo FEHLER beim Bauen - siehe Meldungen oben.
pause
exit /b 1
