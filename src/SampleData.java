import java.util.ArrayList;
import java.util.List;

// The sample town "Neer Nagar": 2 reservoirs, 8 areas and 14 pipelines.
// It is written ONCE here and used by the database (first start / reset) and by the tests.
//
//        R1 ---- A1 ---- A3 ---- A4 ---- A2 ---- R1        (a loop: A1, A3, A4, A2)
//         |      |                |
//         +-- A2 +-- A5 ----------+                        (A1-A5 and A4-A5 join the two sides)
//        R2 ---- A5 ---- A7 ---- A8 ---- A6 ---- R2        (a loop: A5, A7, A8, A6)
//         +------ A7  (R2 also feeds A7 directly)           A5-A6 closes a small triangle with R2
//
// Because of the loops, most areas have two or more routes, so one failed pipe
// does not always cut an area off - but two failures can.
public class SampleData {

    public static List<Reservoir> reservoirs() {
        List<Reservoir> list = new ArrayList<>();
        list.add(new Reservoir("R1", "North Reservoir", 18000));
        list.add(new Reservoir("R2", "South Reservoir", 12000));
        return list;
    }

    public static List<DemandArea> areas() {
        List<DemandArea> list = new ArrayList<>();
        list.add(new ResidentialArea("A1", "Gandhi Nagar", 5000, DemandArea.Priority.HIGH, 120));
        list.add(new ResidentialArea("A2", "Lake View Colony", 3000, DemandArea.Priority.NORMAL, 70));
        list.add(new CriticalInstitution("A3", "City Hospital Zone", 4000, "Hospital"));
        list.add(new ResidentialArea("A4", "Market Street", 3500, DemandArea.Priority.NORMAL, 60));
        list.add(new ResidentialArea("A5", "Riverside Township", 4500, DemandArea.Priority.HIGH, 110));
        list.add(new ResidentialArea("A6", "Old Town", 3000, DemandArea.Priority.NORMAL, 75));
        list.add(new CriticalInstitution("A7", "Fire Station Zone", 2000, "Fire Station"));
        list.add(new ResidentialArea("A8", "Green Park", 2500, DemandArea.Priority.NORMAL, 55));
        return list;
    }

    // each row is {pipeline id, from, to}
    public static List<String[]> pipelines() {
        List<String[]> list = new ArrayList<>();
        list.add(new String[] { "P1", "R1", "A1" });
        list.add(new String[] { "P2", "R1", "A2" });
        list.add(new String[] { "P3", "A1", "A3" });
        list.add(new String[] { "P4", "A2", "A4" });
        list.add(new String[] { "P5", "R2", "A5" });
        list.add(new String[] { "P6", "R2", "A6" });
        list.add(new String[] { "P7", "A5", "A7" });
        list.add(new String[] { "P8", "A6", "A8" });
        list.add(new String[] { "P9", "A4", "A5" });
        list.add(new String[] { "P10", "A3", "A4" });    // second route to the hospital
        list.add(new String[] { "P11", "A7", "A8" });    // second route to the fire station
        list.add(new String[] { "P12", "A1", "A5" });    // a second link between the two sides
        list.add(new String[] { "P13", "A5", "A6" });
        list.add(new String[] { "P14", "R2", "A7" });    // South reservoir feeds the fire station directly
        return list;
    }

    // puts the whole sample town into a network (used by the tests)
    public static WaterNetwork buildNetwork() {
        WaterNetwork network = new WaterNetwork();
        for (Reservoir r : reservoirs()) {
            network.addReservoir(r);
        }
        for (DemandArea a : areas()) {
            network.addDemandArea(a);
        }
        for (String[] p : pipelines()) {
            network.addPipeline(new Pipeline(p[0], p[1], p[2]));
        }
        network.recalculate();
        return network;
    }
}
