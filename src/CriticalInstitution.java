// CHILD of DemandArea: a hospital, fire station, school ...
// It is ALWAYS served first, and nobody can lower its priority.
public class CriticalInstitution extends DemandArea {

    private String institutionType;

    public CriticalInstitution(String id, String name, double dailyDemand, String institutionType) {
        super(id, name, dailyDemand);
        this.institutionType = institutionType;
    }

    @Override
    public int allocationPriority() {
        return 0;   // always first
    }

    @Override
    public String getAreaType() {
        return "CRITICAL_INSTITUTION";
    }

    @Override
    public String getDetails() {
        return institutionType;
    }

    @Override
    public DemandArea withDemand(double newDemand) {
        return new CriticalInstitution(getId(), getName(), newDemand, institutionType);
    }

    @Override
    public String getInstitutionType() {
        return institutionType;
    }
}
