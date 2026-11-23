@echo off
rem SPDX-License-Identifier: GPL-3.0-only
rem Builds hyperdrive-vfs.exe and runs the native unit tests (Windows x64).
rem Requires: Visual Studio 2022 (Build Tools) with the C++ workload, its bundled CMake/Ninja,
rem and WinFsp with the Developer feature. Nothing is installed by this script.
setlocal
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (echo vswhere.exe not found: install Visual Studio 2022 Build Tools & exit /b 1)
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if not defined VSPATH (echo MSVC x64 tools not found & exit /b 1)
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release || exit /b 1
cmake --build build || exit /b 1
ctest --test-dir build --output-on-failure || exit /b 1
echo Built: %~dp0build\hyperdrive-vfs.exe
