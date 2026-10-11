@echo off
if not exist out mkdir out
javac -d out src\*.java
java -cp out Server
