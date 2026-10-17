// The rule we use: Critical first, then High, then Normal.
// If two requests have the same priority, the one that arrived first wins.
// The last step (the id) makes sure the order is always the same.
public class PriorityAllocationPolicy extends WaterAllocationPolicy {

    @Override
    public int compare(WaterRequest a, WaterRequest b) {
        int byPriority = a.getArea().allocationPriority() - b.getArea().allocationPriority();
        if (byPriority != 0) {
            return byPriority;
        }
        if (a.getArrivalOrder() != b.getArrivalOrder()) {
            return a.getArrivalOrder() - b.getArrivalOrder();
        }
        return a.getArea().getId().compareTo(b.getArea().getId());
    }

    @Override
    public String getName() {
        return "Priority first (Critical, High, Normal), then arrival order";
    }
}
