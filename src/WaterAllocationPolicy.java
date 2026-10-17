import java.util.Comparator;

// ABSTRACTION: this class only says "a policy can rank two water requests".
// How the ranking is done is decided by a child class, so the rule can be
// replaced without touching WaterNetwork.
public abstract class WaterAllocationPolicy implements Comparator<WaterRequest> {

    // negative = request a is served before request b
    public abstract int compare(WaterRequest a, WaterRequest b);

    public abstract String getName();
}
