@echo off
rem Runs install.ps1 without changing the PowerShell execution policy.
rem Usage: install.cmd [-Yes] [-SkipModel] [-SkipBuild] [-Credentials] [-Help]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %ERRORLEVEL%
