@ECHO OFF

SET BUILD_DIR=%1
SET PLATFORM=%2
SET CMAKE=%CMAKE_PATH%\bin\cmake

IF EXIST "%BUILD_DIR%" RMDIR /S /Q "%BUILD_DIR%"
MKDIR "%BUILD_DIR%" & CD "%BUILD_DIR%"

:: todo "%CMAKE%" -G "Visual Studio 12 2013" -T v120_xp -A "%PLATFORM%" ..
"%CMAKE%" -G "Visual Studio 14 2015" -A "%PLATFORM%" ..
IF ERRORLEVEL 1 EXIT 1

"%CMAKE%" --build . --config Release
IF ERRORLEVEL 1 EXIT 2