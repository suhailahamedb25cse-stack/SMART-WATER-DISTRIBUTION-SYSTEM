rm -rf out_test
mkdir -p out_test
javac -d out_test src/DemandArea.java src/CriticalInstitution.java src/ResidentialArea.java src/Reservoir.java src/Pipeline.java \
  src/WaterRequest.java src/WaterAllocationPolicy.java src/PriorityAllocationPolicy.java src/WaterNetwork.java src/SampleData.java \
  src/MaintenanceRequest.java src/MaintenanceQueue.java test/TestRunner.java || { echo "COMPILE ERROR"; exit 1; }
java -cp out_test TestRunner
