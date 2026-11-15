@echo off
chcp 65001 >nul
title Actualizar y Compilar Jarvis Accesible en GitHub
cd /d "%~dp0"

echo ========================================================
echo   ACTUALIZAR JARVIS ACCESIBLE EN GITHUB (COMPILADOR APK)
echo ========================================================
echo.

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    git init
    git branch -M main
)

for /f "delims=" %%i in ('git remote get-url origin 2^>nul') do set EXISTING_URL=%%i

if "%EXISTING_URL%"=="" (
    set /p REPO_URL="Pega aca el link de tu repositorio (o presiona ENTER para usar https://github.com/nromeroortiz/jarvis-accesible.git): "
    if "%REPO_URL%"=="" (
        set REPO_URL=https://github.com/nromeroortiz/jarvis-accesible.git
    )
    git remote remove origin >nul 2>&1
    git remote add origin %REPO_URL%
) else (
    echo Repositorio detectado: %EXISTING_URL%
)

echo.
echo Guardando cambios y subiendo a GitHub...
git add .
git commit -m "Fix Android 14/15 ForegroundService startup antes de otorgar permiso de microfono"
git push -u origin main --force

echo.
echo ========================================================
echo   LISTO! Entra a la pestana "Actions" de tu GitHub y
echo   en 1 minuto descarga el nuevo "JarvisAccesible-APK".
echo ========================================================
pause
