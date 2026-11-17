@echo off
rem p1228-java full build script for Windows (requires JDK 21)
rem Usage: build.bat              -^> clean build + unit tests + p1228.jar
rem        build.bat --skip-tests -^> skip tests (or: build.bat notest)
setlocal
cd /d %~dp0

rem ===== Locate JDK 21 (JAVA_HOME -> PATH -> common install dirs) =====
set "JAVAC="
set "JAR="
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  set "JAR=%JAVA_HOME%\bin\jar.exe"
  set "JAVA=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVAC (
  where javac >nul 2>&1
  if not errorlevel 1 (
    set "JAVAC=javac"
    set "JAR=jar"
    set "JAVA=java"
  )
)
if not defined JAVAC (
  for /d %%D in ("C:\Program Files\Java\jdk-21*" "C:\Program Files\Eclipse Adoptium\jdk-21*") do (
    if exist "%%D\bin\javac.exe" (
      set "JAVAC=%%D\bin\javac.exe"
      set "JAR=%%D\bin\jar.exe"
      set "JAVA=%%D\bin\java.exe"
    )
  )
)
if not defined JAVAC (
  echo javac not found. Install JDK 21 or set JAVA_HOME. 1>&2
  exit /b 1
)
echo Using javac: %JAVAC%
"%JAVAC%" -version
if errorlevel 1 exit /b 1

set "SKIP_TESTS=0"
if /i "%~1"=="--skip-tests" set "SKIP_TESTS=1"
if /i "%~1"=="notest" set "SKIP_TESTS=1"

echo == Clean ==
if exist out rmdir /s /q out
if exist p1228.jar del /q p1228.jar
mkdir out
if errorlevel 1 exit /b 1

echo == Compile ==
dir /s /b src\main\java\*.java > out\sources.txt
if errorlevel 1 exit /b 1
dir /s /b src\test\java\*.java >> out\sources.txt
if errorlevel 1 exit /b 1
"%JAVAC%" -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 exit /b 1
del /q out\sources.txt

if "%SKIP_TESTS%"=="0" (
  echo == Test: p1228.P1228Test ==
  "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp out p1228.P1228Test
  if errorlevel 1 exit /b 1
) else (
  echo == Test skipped ==
)

echo == Package ==
"%JAR%" cfe p1228.jar p1228.Main -C out .
if errorlevel 1 exit /b 1

for %%F in (p1228.jar) do echo Artifact: %CD%\p1228.jar (%%~zF bytes)
echo Build OK
endlocal
