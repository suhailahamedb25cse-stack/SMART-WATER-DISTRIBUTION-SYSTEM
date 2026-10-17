@echo off
echo Deleting old compiled files...
if exist out rmdir /s /q out
mkdir out
echo Compiling...
javac -d out -cp lib\sqlite-jdbc.jar src\*.java
if errorlevel 1 (
  echo.
  echo COMPILE ERROR - fix the error above. The program was NOT started.
  pause
  exit /b 1
)
echo Starting...
java -cp "out;lib\sqlite-jdbc.jar" Main
pause
