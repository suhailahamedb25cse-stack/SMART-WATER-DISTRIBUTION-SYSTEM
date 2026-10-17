import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// The project's own tests. They need no extra library and no database:
// they build the sample town in memory and check the rules of the system.
// Run them with run_tests.bat (Windows) or sh run_tests.sh (Mac/Linux).
public class TestRunner {

    static int passed = 0;
    static int failed = 0;

    // one check: prints PASS or FAIL
    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    static void group(String title) {
        System.out.println();
        System.out.println(title);
    }

    static DemandArea area(WaterNetwork n, String id) {
        return n.getDemandAreas().get(id);
    }

    static void fail(WaterNetwork n, String... pipelineIds) {
        for (String id : pipelineIds) {
            n.findPipeline(id).markFailed();
        }
        n.recalculate();
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.001;
    }

    public static void main(String[] args) {
        System.out.println("=== Smart Water Distribution - tests ===");

        sampleNetwork();
        connectivity();
        supplyPaths();
        allocation();
        oopRules();
        repairQueue();
        randomScenarios();

        System.out.println();
        System.out.println("=======================================");
        System.out.println("Passed: " + passed + "   Failed: " + failed);
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : "SOME TESTS FAILED");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------

    static void sampleNetwork() {
        group("1. The sample town");
        WaterNetwork n = SampleData.buildNetwork();
        check("2 reservoirs, 8 areas", n.getReservoirs().size() == 2 && n.getDemandAreas().size() == 8);
        check("14 pipelines (the brief asks for 12 to 15)", n.getAllPipelines().size() == 14);
        check("normal day: every area fully supplied", n.getTotalUnmet() == 0);
        check("normal day: 27,500 L allocated of 30,000 L", near(n.getTotalAllocated(), 27500) && near(n.getTotalAvailable(), 30000));
        check("normal day: nothing disconnected", n.findDisconnectedAreas().isEmpty());
    }

    static void connectivity() {
        group("2. Connectivity (BFS) after failures");

        WaterNetwork n = SampleData.buildNetwork();
        fail(n, "P1");
        check("one failure (P1): the loops keep A1 connected", n.findDisconnectedAreas().isEmpty());

        n = SampleData.buildNetwork();
        fail(n, "P3");
        check("one failure (P3): hospital A3 still reachable by its second route", n.findDisconnectedAreas().isEmpty());

        n = SampleData.buildNetwork();
        fail(n, "P3", "P10");
        check("two failures (P3 + P10): hospital A3 is disconnected", n.findDisconnectedAreas().equals(java.util.Arrays.asList("A3")));
        check("a disconnected area receives no water", area(n, "A3").getAllocated() == 0);
        check("status of the cut-off hospital is DISCONNECTED", n.getAreaStatus(area(n, "A3"), n.findDisconnectedAreas()).equals("DISCONNECTED"));
        n.findPipeline("P10").markWorking();
        n.recalculate();
        check("repairing P10 reconnects the hospital", n.findDisconnectedAreas().isEmpty() && area(n, "A3").getUnmetDemand() == 0);

        n = SampleData.buildNetwork();
        fail(n, "P7", "P11", "P14");
        check("fire station A7 cut off when P7, P11 and P14 fail", n.findDisconnectedAreas().equals(java.util.Arrays.asList("A7")));

        n = SampleData.buildNetwork();
        fail(n, "P1", "P2");
        check("North Reservoir cut off (P1 + P2): the other side still reaches every area", n.findDisconnectedAreas().isEmpty());
        check("... but only South's 12,000 L can be used", near(n.getReservoirs().get("R1").getAllocated(), 0) && near(n.getTotalAllocated(), 12000));

        n = SampleData.buildNetwork();
        check("BFS from R1 reaches all 8 areas", n.findReachableAreas("R1").size() == 8);
        check("BFS from an unknown reservoir reaches nothing", n.findReachableAreas("R9").isEmpty());
    }

    static void supplyPaths() {
        group("3. Supply paths (BFS shows the route)");

        WaterNetwork n = SampleData.buildNetwork();
        boolean allOk = true;
        for (DemandArea a : n.getDemandAreas().values()) {
            List<String> path = n.findSupplyPath(a);
            boolean startsAtReservoir = !path.isEmpty() && n.getReservoirs().containsKey(path.get(0));
            boolean endsAtArea = !path.isEmpty() && path.get(path.size() - 1).equals(a.getId());
            boolean linked = true;
            for (int i = 0; i + 1 < path.size(); i++) {
                linked = linked && joinedByWorkingPipe(n, path.get(i), path.get(i + 1));
            }
            allOk = allOk && startsAtReservoir && endsAtArea && linked;
        }
        check("every path starts at a reservoir, ends at its area, and every step is a working pipe", allOk);

        List<String> before = n.bfsPath("R1", "A3");
        check("R1 to A3 takes the shortest route (3 pipes: R1, A1, A3)", before.equals(java.util.Arrays.asList("R1", "A1", "A3")));
        fail(n, "P3");
        List<String> after = n.bfsPath("R1", "A3");
        check("after P3 fails the route changes (R1, A2, A4, A3)", after.equals(java.util.Arrays.asList("R1", "A2", "A4", "A3")));
        check("a route never uses a failed pipeline", !after.contains("A1") || after.indexOf("A1") + 1 >= after.size() || !after.get(after.indexOf("A1") + 1).equals("A3"));

        fail(n, "P10");
        check("no route to a cut-off area (BFS gives null)", n.bfsPath("R1", "A3") == null && n.bfsPath("R2", "A3") == null);
        check("findSupplyPath is empty for a cut-off area", n.findSupplyPath(area(n, "A3")).isEmpty());
    }

    static boolean joinedByWorkingPipe(WaterNetwork n, String a, String b) {
        for (Pipeline p : n.getAllPipelines()) {
            boolean same = (p.getNode1().equals(a) && p.getNode2().equals(b)) || (p.getNode1().equals(b) && p.getNode2().equals(a));
            if (same && p.isWorking()) {
                return true;
            }
        }
        return false;
    }

    static void allocation() {
        group("4. Priority allocation");

        WaterNetwork n = SampleData.buildNetwork();
        n.getReservoirs().get("R1").setCapacity(0);
        n.recalculate();
        check("dry reservoir (R1 = 0 L): both critical institutions fully supplied",
                area(n, "A3").getUnmetDemand() == 0 && area(n, "A7").getUnmetDemand() == 0);
        check("dry reservoir: HIGH area A1 is fully supplied, A5 gets the 1,000 L that is left (South has only 12,000 L)",
                area(n, "A1").getUnmetDemand() == 0 && near(area(n, "A5").getAllocated(), 1000));
        check("dry reservoir: the NORMAL areas get nothing, because the water ran out before their turn",
                area(n, "A2").getAllocated() == 0 && area(n, "A4").getAllocated() == 0
                && area(n, "A6").getAllocated() == 0 && area(n, "A8").getAllocated() == 0);
        check("dry reservoir: R1 gives out nothing", n.getReservoirs().get("R1").getAllocated() == 0);

        n = SampleData.buildNetwork();
        n.getReservoirs().get("R1").setCapacity(9000);
        n.recalculate();
        check("partial allocation (R1 = 9,000 L): 6,500 L of demand is left unmet", near(n.getTotalUnmet(), 6500));
        check("partial allocation: A4 gets 2,500 of 3,500 L", near(area(n, "A4").getAllocated(), 2500) && near(area(n, "A4").getUnmetDemand(), 1000));
        check("partial allocation: A6 and A8 get nothing", area(n, "A6").getAllocated() == 0 && area(n, "A8").getAllocated() == 0);
        check("partial allocation: A4 status is LOW SUPPLY", n.getAreaStatus(area(n, "A4"), n.findDisconnectedAreas()).equals("LOW SUPPLY"));
        check("partial allocation: A6 status is SHORTAGE", n.getAreaStatus(area(n, "A6"), n.findDisconnectedAreas()).equals("SHORTAGE"));

        n = SampleData.buildNetwork();
        n.getReservoirs().get("R1").setCapacity(0);
        n.getReservoirs().get("R2").setCapacity(0);
        n.recalculate();
        check("no water at all: nothing allocated, all demand unmet", n.getTotalAllocated() == 0 && near(n.getTotalUnmet(), 27500));

        n = SampleData.buildNetwork();
        double sources = 0;
        for (DemandArea a : n.getDemandAreas().values()) {
            double fromSources = 0;
            for (double litres : a.getSources().values()) {
                fromSources += litres;
            }
            sources += Math.abs(fromSources - a.getAllocated());
        }
        check("the reservoirs that supplied an area add up to what it received", sources < 0.001);

        n = SampleData.buildNetwork();
        n.recalculate();
        n.recalculate();
        check("recalculating twice gives the same answer (nothing is counted twice)", near(n.getTotalAllocated(), 27500));
    }

    static void oopRules() {
        group("5. Inheritance, overriding and the policy");

        DemandArea hospital = new CriticalInstitution("X1", "Test Hospital", 100, "Hospital");
        DemandArea high = new ResidentialArea("X2", "High Area", 100, DemandArea.Priority.HIGH, 10);
        DemandArea normal = new ResidentialArea("X3", "Normal Area", 100, DemandArea.Priority.NORMAL, 10);
        check("CriticalInstitution overrides allocationPriority() = 0", hospital.allocationPriority() == 0);
        check("ResidentialArea HIGH = 1, NORMAL = 2", high.allocationPriority() == 1 && normal.allocationPriority() == 2);
        check("the label comes from the number: CRITICAL, HIGH, NORMAL",
                hospital.getPriority() == DemandArea.Priority.CRITICAL && high.getPriority() == DemandArea.Priority.HIGH && normal.getPriority() == DemandArea.Priority.NORMAL);
        check("each child describes itself differently", hospital.getDetails().equals("Hospital") && high.getDetails().equals("10 households"));
        check("each child reports its own type", hospital.getAreaType().equals("CRITICAL_INSTITUTION") && high.getAreaType().equals("RESIDENTIAL"));

        boolean rejected = false;
        try {
            new ResidentialArea("X4", "Bad", 100, DemandArea.Priority.CRITICAL, 5);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("a residential area cannot be CRITICAL", rejected);

        DemandArea copy = hospital.withDemand(250);
        check("withDemand() keeps the same kind of area (polymorphism)", copy instanceof CriticalInstitution && copy.getDailyDemand() == 250 && copy.getInstitutionType().equals("Hospital"));

        WaterAllocationPolicy policy = new PriorityAllocationPolicy();
        check("policy: a critical request goes before a high one", policy.compare(new WaterRequest(hospital, 100, 5), new WaterRequest(high, 100, 1)) < 0);
        check("policy: same priority - the earlier arrival goes first", policy.compare(new WaterRequest(normal, 100, 1),
                new WaterRequest(new ResidentialArea("X5", "Other", 100, DemandArea.Priority.NORMAL, 1), 100, 2)) < 0);

        Reservoir r = new Reservoir("T1", "Test", 1000);
        double given = r.allocateWater(1500);
        check("a reservoir never gives more than it holds", given == 1000 && r.getRemaining() == 0);
    }

    static void repairQueue() {
        group("6. Repair queue (first in, first out)");

        MaintenanceQueue q = new MaintenanceQueue();
        q.addPending(job(1, "P7"));
        q.addPending(job(2, "P3"));
        q.addPending(job(3, "P1"));
        check("3 jobs waiting", q.pendingCount() == 3 && q.hasOpenRequestFor("P3") && !q.hasOpenRequestFor("P9"));
        MaintenanceRequest first = q.startNext();
        check("startNext() takes the OLDEST job (P7)", first.getPipelineId().equals("P7") && first.getStatus() == MaintenanceRequest.Status.IN_PROGRESS);

        boolean refused = false;
        try {
            q.startNext();
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("a second job cannot start while one is in progress", refused);

        MaintenanceRequest done = q.completeCurrent();
        check("completeCurrent() marks it DONE and frees the crew", done.getStatus() == MaintenanceRequest.Status.DONE && q.getCurrent() == null);
        check("the next job is P3, then P1 (same order as they were reported)",
                q.startNext().getPipelineId().equals("P3") && q.pendingCount() == 1);

        MaintenanceRequest cancelled = q.cancelFor("P1");
        check("a waiting job can be cancelled (pipeline deleted)", cancelled != null && cancelled.getStatus() == MaintenanceRequest.Status.CANCELLED && q.pendingCount() == 0);
        check("history keeps the newest first", q.getHistory().get(0).getPipelineId().equals("P1") && q.getHistory().get(1).getPipelineId().equals("P7"));

        refused = false;
        MaintenanceQueue empty = new MaintenanceQueue();
        try {
            empty.startNext();
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("starting a job from an empty queue is refused", refused);
    }

    static MaintenanceRequest job(int id, String pipelineId) {
        return new MaintenanceRequest(id, pipelineId, "test", MaintenanceRequest.Status.PENDING, "now", null, null, "test");
    }

    // 300 random situations: random failed pipelines and random water supplies.
    // In every one of them these rules must hold.
    static void randomScenarios() {
        group("7. 300 random scenarios (random failures and supplies)");

        Random random = new Random(42);   // fixed seed, so the result is always the same
        int problems = 0;
        String firstProblem = "";

        for (int run = 0; run < 300; run++) {
            WaterNetwork n = SampleData.buildNetwork();
            for (Pipeline p : n.getAllPipelines()) {
                if (random.nextInt(100) < 25) {
                    p.markFailed();
                }
            }
            n.getReservoirs().get("R1").setCapacity(random.nextInt(20001));
            n.getReservoirs().get("R2").setCapacity(random.nextInt(20001));
            n.recalculate();
            List<String> disconnected = n.findDisconnectedAreas();

            double sumAreas = 0;
            for (DemandArea a : n.getDemandAreas().values()) {
                sumAreas += a.getAllocated();
                if (!near(a.getAllocated() + a.getUnmetDemand(), a.getDailyDemand())) {
                    problems++; firstProblem = "run " + run + ": allocated + unmet != demand for " + a.getId();
                }
                if (disconnected.contains(a.getId()) && a.getAllocated() > 0) {
                    problems++; firstProblem = "run " + run + ": disconnected area " + a.getId() + " got water";
                }
                // water is never left unused while a reachable area is still short
                if (!disconnected.contains(a.getId()) && a.getUnmetDemand() > 0.001) {
                    for (Reservoir r : n.getReservoirs().values()) {
                        if (n.findReachableAreas(r.getId()).contains(a.getId()) && r.getRemaining() > 0.001) {
                            problems++; firstProblem = "run " + run + ": " + a.getId() + " is short but " + r.getId() + " still has water";
                        }
                    }
                }
                // a lower-priority area only gets water from R if every higher-priority area R can reach is satisfied
                for (String reservoirId : a.getSources().keySet()) {
                    for (DemandArea higher : n.getDemandAreas().values()) {
                        if (higher.allocationPriority() < a.allocationPriority()
                                && n.findReachableAreas(reservoirId).contains(higher.getId())
                                && higher.getUnmetDemand() > 0.001) {
                            problems++; firstProblem = "run " + run + ": " + a.getId() + " was served before " + higher.getId();
                        }
                    }
                }
            }
            for (Reservoir r : n.getReservoirs().values()) {
                if (r.getAllocated() > r.getCapacity() + 0.001 || r.getRemaining() < -0.001) {
                    problems++; firstProblem = "run " + run + ": " + r.getId() + " gave out more than it holds";
                }
            }
            if (!near(sumAreas, n.getTotalAllocated())) {
                problems++; firstProblem = "run " + run + ": areas received " + sumAreas + " but reservoirs gave " + n.getTotalAllocated();
            }
        }
        check("a reservoir never gives out more than its supply (300 runs)", problems == 0);
        check("allocated + unmet = demand for every area, and disconnected areas get nothing (300 runs)", problems == 0);
        check("water is never left unused while a reachable area is short (300 runs)", problems == 0);
        check("lower priority is never served before a higher priority it could have helped (300 runs)", problems == 0);
        if (problems > 0) {
            System.out.println("        first problem: " + firstProblem);
        }
    }
}
