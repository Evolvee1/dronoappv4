@echo off
title Drono Lite Control Server
echo Drono Lite Control Server
echo ====================================

:: Set UTF-8 encoding for the console
chcp 65001 >nul

REM Check if Python is installed
where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Python not found. Please install Python 3.8 or higher.
    pause
    exit /b 1
)

REM Check if ADB is installed
where adb >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ADB not found. Please install Android Debug Bridge and add it to your PATH.
    pause
    exit /b 1
)

REM Check if virtual environment exists, create if not
if not exist venv (
    echo Creating virtual environment...
    python -m venv venv
)

REM Activate virtual environment and install dependencies
echo Activating virtual environment...
call venv\Scripts\activate.bat

REM Install dependencies if needed
if not exist venv\Lib\site-packages\fastapi (
    echo Installing dependencies...
    pip install -r requirements.txt
)

:: Check if port is specified
set PORT=8000
set QUIET_MODE=
set LOG_LEVEL=
set NO_BROWSER=

:parse_args
if "%1"=="" goto end_parse_args
if "%1"=="--port" (
    set PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--quiet" (
    set QUIET_MODE=--quiet
    shift
    goto parse_args
)
if "%1"=="--log-level" (
    set LOG_LEVEL=--log-level %2
    shift
    shift
    goto parse_args
)
if "%1"=="--no-browser" (
    set NO_BROWSER=true
    shift
    goto parse_args
)
if "%1"=="--help" (
    echo Usage: start_server.bat [--port PORT] [--quiet] [--log-level LEVEL] [--no-browser]
    echo.
    echo Options:
    echo   --port PORT       Set the server port (default: 8000)
    echo   --quiet           Run in quiet mode (suppresses status messages)
    echo   --log-level LEVEL Set logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
    echo   --no-browser      Do not automatically open the dashboard in browser
    exit /b 0
)
REM Skip other unknown args
shift
goto parse_args
:end_parse_args

REM Check if the specified port is already in use and find a free one if needed
:check_port
netstat -ano | findstr ":%PORT% " | findstr "LISTENING" > nul
if %ERRORLEVEL% equ 0 (
    echo Port %PORT% is already in use. Trying port %PORT%+1...
    set /a PORT+=1
    goto check_port
)

echo Starting server on port %PORT%...
echo Access the dashboard at: http://localhost:%PORT%
echo.
echo Press Ctrl+C to stop the server
echo.

REM Start the server with the specified port and logging options in the background
start /B python -m uvicorn main:app --host 0.0.0.0 --port %PORT% %QUIET_MODE% %LOG_LEVEL%

REM Wait a moment for the server to start
timeout /t 3 /nobreak > nul

REM Open the dashboard in the default browser if --no-browser is not set
if not defined NO_BROWSER (
    echo Opening dashboard in your default browser...
    start http://localhost:%PORT%/static/dashboard.html
)

REM Keep the console window open while server is running
echo Server is running in the background. Close this window to stop the server.
pause

REM Deactivate virtual environment and cleanup
call deactivate

exit /b 0 