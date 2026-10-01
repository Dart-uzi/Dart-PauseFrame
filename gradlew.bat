@echo off
setlocal
java "%~dp0gradle-bootstrap.java" %*
set ERR=%ERRORLEVEL%
endlocal & exit /b %ERR%
