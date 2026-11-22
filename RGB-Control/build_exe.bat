@echo off
REM ============================================================
REM  RGB Control 2 - baut die RGBControl.exe (Windows)
REM  Voraussetzung: Python 3.10+ (python.org, Haken bei "Add to PATH")
REM  Fuer Temperaturen zusaetzlich: .NET SDK 8 (dotnet.microsoft.com)
REM ============================================================
cd /d "%~dp0"
echo [1/5] Virtuelle Umgebung anlegen ...
if not exist .venv ( py -3 -m venv .venv 2>nul || python -m venv .venv )
call .venv\Scripts\activate.bat || goto :err

echo [2/5] Pakete installieren ...
python -m pip install --upgrade pip >nul
pip install -r requirements.txt pyinstaller || goto :err

echo [3/5] LibreHardwareMonitor (Temperaturen) bauen ...
where dotnet >nul 2>nul
if %errorlevel%==0 (
  dotnet build tools\lhm\LhmDeps.csproj -c Release -o lhm --nologo || goto :err
  del /q lhm\LhmDeps.* 2>nul
) else (
  echo     HINWEIS: .NET SDK nicht gefunden - Temperaturen werden eingeschraenkt sein.
  if not exist lhm mkdir lhm
)

echo [4/5] EXE bauen ...
pyinstaller --noconfirm --clean --windowed --uac-admin ^
  --name RGBControl --icon assets\icon.ico ^
  --add-data "assets;assets" --add-data "lhm;lhm" ^
  --collect-all pythonnet --collect-all clr_loader --hidden-import clr ^
  main.py || goto :err

echo [5/5] OpenRGB-Dienst dazulegen (falls vorhanden) ...
if exist OpenRGB\OpenRGB.exe (
  xcopy /E /I /Y OpenRGB dist\RGBControl\OpenRGB >nul
  echo     OpenRGB wurde mitkopiert.
) else (
  echo     HINWEIS: Ordner "OpenRGB" nicht gefunden - siehe LIESMICH.md.
)

echo.
echo ===== FERTIG! =====
echo Die App liegt in:  dist\RGBControl\RGBControl.exe
pause
exit /b 0

:err
echo.
echo FEHLER beim Bauen - siehe Meldungen oben.
pause
exit /b 1
