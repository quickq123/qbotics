@echo off
rem Checks Qbotics programs without the game: will they compile, and what do they need to run?
rem   .\check programs\mine.py   one program
rem   .\check .                  every program in this folder
rem It needs Java 25 or newer. Minecraft's launcher brings one: it is looked for there too.
setlocal
set "KIT=%~dp0"
set "JAVA="
if defined QBOTICS_JAVA call :try "%QBOTICS_JAVA%"
if not defined JAVA if defined JAVA_HOME call :try "%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA call :try "%%J"
rem The CurseForge app, the Minecraft Launcher (both kinds), and Prism Launcher.
if not defined JAVA for /d %%R in ("%USERPROFILE%\curseforge\minecraft\Install\java\*") do if not defined JAVA call :try "%%R\bin\java.exe"
if not defined JAVA for /d %%R in ("%APPDATA%\.minecraft\runtime\*") do for /d %%S in ("%%R\windows-x64\*") do if not defined JAVA call :try "%%S\bin\java.exe"
if not defined JAVA for /d %%R in ("%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\*") do for /d %%S in ("%%R\windows-x64\*") do if not defined JAVA call :try "%%S\bin\java.exe"
if not defined JAVA for /d %%R in ("%APPDATA%\PrismLauncher\java\*") do if not defined JAVA call :try "%%R\bin\java.exe"
if not defined JAVA (
    echo The check needs Java 25 or newer, and none was found.
    echo Start Minecraft 26.1 once from its launcher ^(it brings its own Java^), or install one
    echo from https://adoptium.net, or set QBOTICS_JAVA to the java.exe to use.
    exit /b 2
)
"%JAVA%" -jar "%KIT%.qbotics\qcheck.jar" %*
exit /b %errorlevel%

:try
if not exist "%~1" goto :eof
"%~1" -version 2>&1 | findstr /r /c:"version \"2[5-9]" /c:"version \"[3-9][0-9]" >nul && set "JAVA=%~1"
goto :eof
