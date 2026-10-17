rm -rf out
mkdir -p out
echo "Compiling..."
javac -d out -cp lib/sqlite-jdbc.jar src/*.java || { echo "COMPILE ERROR - program not started"; exit 1; }
echo "Starting..."
java -cp "out:lib/sqlite-jdbc.jar" Main
