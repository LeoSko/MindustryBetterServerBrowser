@echo off
setlocal

REM Build script for the Better Server Browser Mindustry mod.
REM Requires JDK 17+ on PATH and Mindustry.jar in the parent dir.

set MINDUSTRY_JAR=..\Mindustry.jar
set OUT=build\classes
set JAR_NAME=BetterServerBrowser.jar

if not exist "%MINDUSTRY_JAR%" (
    echo Mindustry.jar not found at %MINDUSTRY_JAR%
    exit /b 1
)

if exist build rmdir /s /q build
mkdir %OUT%

echo Compiling...
javac --release 17 -cp "%MINDUSTRY_JAR%" -d %OUT% src\betterserverbrowser\*.java
if errorlevel 1 (
    echo Compile failed.
    exit /b 1
)

echo Packaging %JAR_NAME%...
copy /y mod.hjson %OUT%\mod.hjson >nul
jar cf %JAR_NAME% -C %OUT% .
if errorlevel 1 (
    echo Jar packaging failed.
    exit /b 1
)

echo.
echo Built %JAR_NAME%
echo Copy it to: %%AppData%%\Mindustry\mods\
endlocal
