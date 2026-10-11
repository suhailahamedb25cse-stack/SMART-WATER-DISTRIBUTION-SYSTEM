Smart Water Distribution Management - Frontend + Backend

HOW TO RUN (needs JDK 11 or newer, nothing else to install)
  Windows:   run.bat
  Mac/Linux: sh run.sh
Then open  http://localhost:8080  in a browser.

Or manually, from this folder:
  javac -d out src/*.java
  java -cp out Server

FILES
  src/Reservoir.java, Pipeline.java, DemandArea.java, WaterNetwork.java  -> backend logic
  src/Server.java                                                       -> backend HTTP API
  web/index.html                                                        -> frontend dashboard

BACKEND (WaterNetwork.java)
  findReachableAreas()   BFS through WORKING pipes only
  findDisconnectedAreas() areas no reservoir can reach
  recalculate()          PriorityQueue: CRITICAL > HIGH > NORMAL, partial allocation,
                         unmet litres kept per area; runs after every pipeline
                         failure/repair or supply change

DEMO IDEAS
  1. Normal day: everything supplied.
  2. Fail P1  -> Gandhi Nagar and City Hospital Zone become disconnected.
  3. Set North Reservoir supply to 0 -> NORMAL areas lose water first, CRITICAL stay supplied.
  4. Set a reservoir to about 9000 -> partial allocation (LOW SUPPLY with unmet litres).
  5. Press Repair -> service restored.
