@echo off
setlocal

REM Build script — produces a jar that loads on both desktop AND Android.
REM Final jar contains .class files (desktop) AND classes.dex (Android).
REM
REM Requires:
REM   JDK 17+ on PATH (javac / jar / java)
REM   Mindustry.jar in parent dir (or set MINDUSTRY_JAR)
REM   Android SDK build-tools 30+ with lib\d8.jar (set D8_JAR)
REM   Android platform jar (set ANDROID_JAR)

if not defined MINDUSTRY_JAR set MINDUSTRY_JAR=..\Mindustry.jar
if not defined D8_JAR set D8_JAR=%LOCALAPPDATA%\Android\Sdk\build-tools\30.0.2\lib\d8.jar
if not defined ANDROID_JAR set ANDROID_JAR=%LOCALAPPDATA%\Android\Sdk\platforms\android-29\android.jar

set OUT=build\classes
set JAR_NAME=BetterServerBrowser.jar

if not exist "%MINDUSTRY_JAR%" (echo Mindustry.jar not found at %MINDUSTRY_JAR% & exit /b 1)
if not exist "%D8_JAR%"        (echo d8.jar not found at %D8_JAR% & exit /b 1)
if not exist "%ANDROID_JAR%"   (echo android.jar not found at %ANDROID_JAR% & exit /b 1)

if exist build rmdir /s /q build
mkdir %OUT%
mkdir build\dex

echo Compiling...
javac --release 8 -cp "%MINDUSTRY_JAR%" -d %OUT% src\betterserverbrowser\*.java
if errorlevel 1 (echo Compile failed & exit /b 1)

echo Packaging desktop jar...
copy /y mod.hjson %OUT%\mod.hjson >nul
jar cf %JAR_NAME% -C %OUT% .
if errorlevel 1 (echo Jar packaging failed & exit /b 1)

echo Dexing for Android...
java -cp "%D8_JAR%" com.android.tools.r8.D8 --release --min-api 14 --classpath "%ANDROID_JAR%" --classpath "%MINDUSTRY_JAR%" --output build\dex %JAR_NAME%
if errorlevel 1 (echo d8 dexing failed & exit /b 1)

echo Repacking with classes.dex...
copy /y build\dex\classes.dex %OUT%\classes.dex >nul
jar cf %JAR_NAME% -C %OUT% .
if errorlevel 1 (echo Repack failed & exit /b 1)

echo.
echo Built %JAR_NAME%
endlocal
