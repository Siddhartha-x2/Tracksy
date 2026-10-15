@echo off
setlocal
title Tracksy server - keep this window open
cd /d "%~dp0"
echo Tracksy will check PostgreSQL and start the website.
echo Keep this window open while you use Tracksy.
echo.
where node.exe >nul 2>&1
if errorlevel 1 (
  echo Node.js 24 or newer is required. Install Node.js and try again.
  pause
  exit /b 1
)
node.exe scripts\local.mjs start
echo.
echo The launcher has finished. Any startup error is shown above.
echo Application log: %~dp0work\runtime\application.log
echo Launcher errors: %~dp0work\runtime\launcher-error.log
pause
