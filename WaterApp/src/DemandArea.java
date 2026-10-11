// A neighbourhood that needs water.
public class DemandArea {

    public enum Priority {
        CRITICAL, HIGH, NORMAL
    }

    private String id;
    private String name;
    private double dailyDemand;
    private double unmetDemand;  
    private Priority priority;

    public DemandArea(String id, String name, double dailyDemand, Priority priority) {
        this.id = id;
        this.name = name;
        this.dailyDemand = dailyDemand;
        this.priority = priority;
        this.unmetDemand = dailyDemand;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getDailyDemand() { return dailyDemand; }
    public double getUnmetDemand() { return unmetDemand; }
    public Priority getPriority() { return priority; }

    
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
    }
}
