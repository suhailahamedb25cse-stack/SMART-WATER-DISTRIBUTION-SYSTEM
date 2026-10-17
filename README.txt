SMART WATER DISTRIBUTION - COMPLETE WEBSITE
===========================================
Login, database, subclasses, repair queue, network map, supply routes,
allocation history, simulated days, manual readings, CSV export, tests, UML.

HOW TO RUN  (needs JDK 11 or newer; nothing else to install)
  Windows:    double-click run.bat
  Mac/Linux:  sh run.sh
Wait for "Website running at http://localhost:8080" (the very first start takes a few
seconds because it creates the database), then open that address in a browser.
Press Ctrl+C in the terminal to stop. Your data stays in waterdb.sqlite.

RUN THE TESTS
  Windows:    double-click run_tests.bat
  Mac/Linux:  sh run_tests.sh
56 checks (connectivity, supply paths, priority allocation, inheritance, repair queue and
300 random scenarios). The result of the last run is saved in docs/test-results.txt.

LOGIN ACCOUNTS (created the first time the program runs)
  operator / operator123   can look at everything AND change data, run the repair queue
  viewer   / viewer123     can only look; every button that changes data is hidden
Change the passwords on the Account page after the first login.

ALREADY HAVE A waterdb.sqlite FROM AN OLDER VERSION?
  Copy it into this folder before you start. It is upgraded automatically and your data
  is kept. Your old network keeps its old pipelines; to get the new 14-pipeline sample
  town (with loops), press "Reset to sample data" on the History page, or delete the file.

PAGES
  Dashboard    simulated day + "Start next day", totals, alerts, repair-queue summary, reservoir levels,
               priority-wise status, area-wise allocation with the supply ROUTE of every area, pipeline status
  Map          the network drawn as a picture. Click an area to see its status and its supply route
               (the blue line), a reservoir to see what it supplies, a pipe to fail it.
               Ring colours: green supplied, amber low supply, red shortage, purple disconnected.
  Reservoirs   add / edit / delete, and "Record today's supply reading"
  Areas        add / edit / delete a CRITICAL INSTITUTION or a RESIDENTIAL area, and "Record today's demand reading"
  Pipelines    add / fail / delete (a failed pipeline is repaired from Maintenance)
  Maintenance  the repair queue: Start next job / Mark repair as complete
  History      allocation history (a snapshot of who got how much after EVERY change, with details)
               and the event log (every change with time and user). Both can be downloaded as CSV.
  Account      who you are, change password

THE SAMPLE TOWN (src/SampleData.java)
  2 reservoirs, 8 areas (2 critical institutions, 6 residential), 14 pipelines with loops.
  Because of the loops one failed pipe usually does not cut an area off, but two can:
  fail P3 -> the hospital is still supplied, but its route changes to R1, A2, A4, A3.
  fail P3 and P10 -> the hospital (A3) is disconnected.

OOP CONCEPTS (where to find them)
  Inheritance   DemandArea (parent)  ->  CriticalInstitution, ResidentialArea (children)
  Overriding    each child writes its own allocationPriority(), getAreaType(), getDetails(), withDemand()
  Polymorphism  WaterNetwork.recalculate() ranks every area by calling allocationPriority()
                without asking what kind of area it is
  Abstraction   DemandArea and WaterAllocationPolicy are abstract; PriorityAllocationPolicy is the rule used
  Encapsulation private fields; changed only through methods such as allocateWater(),
                markFailed(), receiveAllocation()
  Composition   WaterNetwork contains Reservoir, DemandArea and Pipeline objects

DATA STRUCTURES (where to find them)
  Adjacency list (HashMap of node -> pipelines)   WaterNetwork  - the graph
  Queue + HashSet                                  WaterNetwork.findReachableAreas() - BFS reachability
  Queue + HashMap (came-from)                      WaterNetwork.bfsPath() - BFS that remembers the route
  PriorityQueue of WaterRequest                    WaterNetwork.recalculate() - who gets water first
  ArrayDeque                                       MaintenanceQueue - FIFO repair jobs
  ArrayList                                        AllocationHistory, MaintenanceQueue history, many lists
  TreeMap                                          reservoirs and areas kept in id order
  HashMap                                          DemandArea.sources (which reservoir gave how much)

REPAIR QUEUE (first in, first out)
  1. Press Fail on a pipeline: water is re-allocated at once and a repair job joins the BACK of the queue.
  2. Maintenance page -> "Start next job" takes the OLDEST job (only one job at a time).
  3. "Mark repair as complete": the pipeline works again, water is re-allocated, the job moves to history.
  Deleting a pipeline cancels its job.

SIMULATED DAYS AND READINGS
  "Start next day" refills the reservoirs and every area asks for its daily demand again (day 1, 2, 3 ...).
  "Record today's demand / supply reading" lets the operator type measured values by hand; the water is
  shared out again at once and the reading is saved in the event log.

SECURITY (what is done and why)
  Passwords        never stored; only a salted PBKDF2 hash (Auth.java)
  Sessions         random token in an HttpOnly, SameSite=Strict cookie; ends after 2 hours unused or on log out
  Wrong passwords  5 wrong tries lock that username for 5 minutes
  Roles            checked on the SERVER for every request (hiding buttons is only for looks)
  SQL injection    every query uses PreparedStatement
  Page injection   names typed by users are shown as text, never run as HTML
  CSV files        cells that start with = + - @ get a ' in front, so Excel cannot run them as formulas
  File access      the server will not serve anything outside the web folder
  Limits           plain HTTP on your own computer; do not put it on the public internet as it is.
                   Sessions are kept in memory, so everyone is logged out when the program restarts.

FOLDERS
  web/    login.html, login.js, index.html, app.js, style.css
  src/    Main, Server, Auth, WaterService, Database (all JDBC), WaterNetwork, SampleData,
          Reservoir, Pipeline, DemandArea, CriticalInstitution, ResidentialArea,
          WaterRequest, WaterAllocationPolicy, PriorityAllocationPolicy,
          MaintenanceRequest, MaintenanceQueue, AllocationRecord, AllocationHistory
  test/   TestRunner.java
  sql/    schema.sql   the 9 database tables
  docs/   uml-class-diagram.png / .svg   architecture.png / .svg   database-tables.png / .svg
          test-results.txt   (the .dot files are the sources of the diagrams)
  lib/    sqlite-jdbc.jar   JDBC driver for SQLite

HOW A CHANGE FLOWS
  Browser (app.js) -> Server.java (is the user logged in? is the role allowed?)
                   -> WaterService.java (check input, save, update, write event, save snapshot)
                        -> Database.java  (saves to waterdb.sqlite)
                        -> WaterNetwork.java (BFS + priority allocation) / MaintenanceQueue.java
  The new results come back as JSON and the page redraws.

TO START AGAIN FROM THE SAMPLE DATA: History page -> "Reset to sample data" (operator only),
or stop the program and delete waterdb.sqlite (this also resets the login accounts).

EDITING THE CODE: after any change to a .java file, stop the program and run run.bat / run.sh again.
