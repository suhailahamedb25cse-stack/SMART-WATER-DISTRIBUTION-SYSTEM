// CHILD of DemandArea: a neighbourhood where people live.
// The operator chooses HIGH or NORMAL priority for it.
public class ResidentialArea extends DemandArea {

    private Priority priority;
    private int households;

    public ResidentialArea(String id, String name, double dailyDemand, Priority priority, int households) {
        super(id, name, dailyDemand);
        if (priority == Priority.CRITICAL) {
            throw new IllegalArgumentException("A residential area can only be HIGH or NORMAL. Use a critical institution for CRITICAL.");
        }
        this.priority = priority;
        this.households = households;
    }

    @Override
    public int allocationPriority() {
        return priority.ordinal();   // HIGH = 1, NORMAL = 2
    }

    @Override
    public String getAreaType() {
        return "RESIDENTIAL";
    }

    @Override
    public String getDetails() {
        return households + " households";
    }

    @Override
    public DemandArea withDemand(double newDemand) {
        return new ResidentialArea(getId(), getName(), newDemand, priority, households);
    }

    @Override
    public int getHouseholds() {
        return households;
    }
}
