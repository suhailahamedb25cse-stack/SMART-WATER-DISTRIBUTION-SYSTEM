
public class Reservoir {

    private String id;
    private String name;
    private double capacity;  
    private double remaining;  

    public Reservoir(String id, String name, double capacity) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
        this.remaining = capacity;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getCapacity() { return capacity; }
    public double getRemaining() { return remaining; }

    public double getAllocated() {
        return capacity - remaining;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
        this.remaining = capacity;
    }

    public double allocateWater(double requested) {
        double given;
        if (requested > remaining) {
            given = remaining;
        } else {
            given = requested;
        }
        remaining = remaining - given;
        return given;
    }

   
    public void resetDailySupply() {
        remaining = capacity;
    }
}
