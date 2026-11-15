@echo off
chcp 65001 >nul
title Subir Jarvis Accesible a GitHub para compilar APK
cd /d "%~dp0"

echo ========================================================
echo   SUBIR JARVIS ACCESIBLE A GITHUB (COMPILADOR DE APK)
echo ========================================================
echo.
echo Paso 1: Entra en tu navegador a: https://github.com/new
echo Paso 2: En "Repository name" escribi: jarvis-accesible
echo Paso 3: Baja y toca el boton verde "Create repository"
echo.
set /p REPO_URL="Pega aca el link de tu repositorio (o presiona ENTER si lo llamaste jarvis-accesible en tu cuenta nromeroortiz): "

if "%REPO_URL%"=="" (
    set REPO_URL=https://github.com/nromeroortiz/jarvis-accesible.git
)

echo.
echo Inicializando Git y preparando archivos...
git init
git branch -M main
git add .
git commit -m "Version completa de Jarvis Accesible con lector de billetes argentinos"
git remote remove origin >nul 2>&1
git remote add origin %REPO_URL%

echo.
echo Subiendo archivos a GitHub...
git push -u origin main --force

echo.
echo ========================================================
echo   LISTO! Ahora entra a la pestana "Actions" de tu
echo   repositorio en GitHub y en 2 minutos vas a poder
echo   descargar "JarvisAccesible-APK" abajo de todo.
echo ========================================================
pause
