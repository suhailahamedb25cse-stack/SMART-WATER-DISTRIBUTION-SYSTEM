import java.util.HashMap;

// A place that needs water. This is the PARENT class.
// There are two kinds of area (the child classes):
//   CriticalInstitution  - hospital, fire station, school ...
//   ResidentialArea      - a neighbourhood where people live
// Each child decides its own allocationPriority() (that is "overriding"),
// so the allocation code can treat every area the same way without
// asking what kind it is (that is "polymorphism").
public abstract class DemandArea {

    public enum Priority {
        CRITICAL, HIGH, NORMAL
    }

    private String id;
    private String name;
    private double dailyDemand;
    private double unmetDemand;   // litres still not supplied
    private HashMap<String, Double> sources = new HashMap<>();   // reservoir id -> litres it gave us

    public DemandArea(String id, String name, double dailyDemand) {
        this.id = id;
        this.name = name;
        this.dailyDemand = dailyDemand;
        this.unmetDemand = dailyDemand;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getDailyDemand() { return dailyDemand; }
    public double getUnmetDemand() { return unmetDemand; }

    // litres this area has received so far
    public double getAllocated() {
        return dailyDemand - unmetDemand;
    }

    public void receiveAllocation(double litres) {
        unmetDemand = unmetDemand - litres;
        if (unmetDemand < 0) {
            unmetDemand = 0;
        }
    }

    public void resetDailyDemand() {
        unmetDemand = dailyDemand;
        sources.clear();
    }

    // ----- which reservoirs supplied this area (filled in during allocation) -----

    public void addSource(String reservoirId, double litres) {
        double before = sources.containsKey(reservoirId) ? sources.get(reservoirId) : 0;
        sources.put(reservoirId, before + litres);
    }

    public HashMap<String, Double> getSources() {
        return sources;
    }

    // the reservoir that gave the most water (null if the area got none)
    public String getMainSource() {
        String best = null;
        for (String reservoirId : sources.keySet()) {
            if (best == null || sources.get(reservoirId) > sources.get(best)) {
                best = reservoirId;
            }
        }
        return best;
    }

    // ----- every child class must write its own version of these -----

    // 0 = served first, 1 = second, 2 = last
    public abstract int allocationPriority();

    public abstract String getAreaType();

    // short text shown on the website, e.g. "Hospital" or "120 households"
    public abstract String getDetails();

    // a copy of this area with a new daily demand (used when a demand reading is entered)
    public abstract DemandArea withDemand(double newDemand);

    // ----- the label (CRITICAL / HIGH / NORMAL) comes from the priority number -----

    public Priority getPriority() {
        return Priority.values()[allocationPriority()];
    }

    // only some children have these, so the parent gives a default answer
    public String getInstitutionType() { return ""; }
    public int getHouseholds() { return 0; }
}
