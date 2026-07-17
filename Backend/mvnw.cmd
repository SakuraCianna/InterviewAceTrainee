@ECHO OFF
SETLOCAL
SET "MVNW_PROJECTBASEDIR=%~dp0"
SET "MVNW_WRAPPER_DIR=%MVNW_PROJECTBASEDIR%.mvn\wrapper"
SET "MVNW_DIST_DIR=%MVNW_WRAPPER_DIR%\apache-maven-3.9.11"
SET "MVNW_MAVEN_CMD=%MVNW_DIST_DIR%\bin\mvn.cmd"

IF EXIST "%MVNW_MAVEN_CMD%" GOTO run

WHERE powershell.exe >NUL 2>NUL
IF ERRORLEVEL 1 (
  ECHO PowerShell is required to bootstrap Maven Wrapper. 1>&2
  EXIT /B 1
)

IF NOT EXIST "%MVNW_WRAPPER_DIR%" MKDIR "%MVNW_WRAPPER_DIR%"
REM Bootstrap in PowerShell so cmd.exe never parses the download and hash expressions.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%MVNW_WRAPPER_DIR%\bootstrap-maven.ps1" -WrapperDirectory "%MVNW_WRAPPER_DIR%"
IF ERRORLEVEL 1 EXIT /B 1

:run
CALL "%MVNW_MAVEN_CMD%" %*
EXIT /B %ERRORLEVEL%
