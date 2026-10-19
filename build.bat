@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
    echo Gradle is required. Install Gradle 8.8 and Java 17, then rerun.
    exit /b 1
)
gradle --no-daemon build
if errorlevel 1 exit /b 1
echo Build finished. JAR: build\libs\telegramauth-forge-1.20.1-1.1.0.jar
