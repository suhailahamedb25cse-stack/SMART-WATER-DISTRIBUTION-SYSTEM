import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.TreeMap;

// The water network kept in memory: reservoirs, areas and pipelines.
// The graph is an adjacency list (node id -> pipelines touching it).
// The permanent copy of the data lives in the database (see Database.java);
// this class does the calculations (BFS and allocation).
public class WaterNetwork {

    private TreeMap<String, Reservoir> reservoirs = new TreeMap<>();
    private TreeMap<String, DemandArea> demandAreas = new TreeMap<>();
    private HashMap<String, List<Pipeline>> adjacencyList = new HashMap<>();
    private List<Pipeline> allPipelines = new ArrayList<>();

    // the rule used to rank water requests (can be replaced by another policy)
    private WaterAllocationPolicy policy = new PriorityAllocationPolicy();

    public WaterAllocationPolicy getPolicy() { return policy; }
    public void setPolicy(WaterAllocationPolicy policy) { this.policy = policy; }

    // ---------- adding ----------

    public void addReservoir(Reservoir r) {
        reservoirs.put(r.getId(), r);
        adjacencyList.put(r.getId(), new ArrayList<Pipeline>());
    }

    public void addDemandArea(DemandArea a) {
        demandAreas.put(a.getId(), a);
        adjacencyList.put(a.getId(), new ArrayList<Pipeline>());
    }

    public void addPipeline(Pipeline p) {
        adjacencyList.get(p.getNode1()).add(p);
        adjacencyList.get(p.getNode2()).add(p);
        allPipelines.add(p);
    }

    // replaces an area with a new object that has the same id
    // (used when an area is edited, even if its type changes)
    public void replaceDemandArea(DemandArea a) {
        demandAreas.put(a.getId(), a);
    }

    // ---------- removing ----------

    public void removePipeline(String pipelineId) {
        Pipeline p = findPipeline(pipelineId);
        if (p == null) {
            return;
        }
        adjacencyList.get(p.getNode1()).remove(p);
        adjacencyList.get(p.getNode2()).remove(p);
        allPipelines.remove(p);
    }

    // Removing a reservoir or area also removes the pipelines joined to it.
    public void removeNode(String nodeId) {
        List<Pipeline> touching = new ArrayList<>(adjacencyList.get(nodeId));
        for (Pipeline p : touching) {
            removePipeline(p.getId());
        }
        adjacencyList.remove(nodeId);
        reservoirs.remove(nodeId);
        demandAreas.remove(nodeId);
    }

    public void clear() {
        reservoirs.clear();
        demandAreas.clear();
        adjacencyList.clear();
        allPipelines.clear();
    }

    // ---------- looking up ----------

    public TreeMap<String, Reservoir> getReservoirs() { return reservoirs; }
    public TreeMap<String, DemandArea> getDemandAreas() { return demandAreas; }
    public List<Pipeline> getAllPipelines() { return allPipelines; }

    // the pipelines joined to a reservoir or area
    public List<Pipeline> getPipelinesTouching(String nodeId) {
        List<Pipeline> found = new ArrayList<>();
        if (adjacencyList.containsKey(nodeId)) {
            found.addAll(adjacencyList.get(nodeId));
        }
        return found;
    }

    public boolean hasNode(String id) {
        return adjacencyList.containsKey(id);
    }

    public Pipeline findPipeline(String id) {
        for (Pipeline p : allPipelines) {
            if (p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---------- BFS ----------

    // BFS from one reservoir through WORKING pipes only.
    public List<String> findReachableAreas(String reservoirId) {
        List<String> reachable = new ArrayList<>();
        if (!reservoirs.containsKey(reservoirId)) {
            return reachable;
        }

        HashSet<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(reservoirId);
        visited.add(reservoirId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (demandAreas.containsKey(current)) {
                reachable.add(current);
            }
            for (Pipeline p : adjacencyList.get(current)) {
                if (!p.isWorking()) {
                    continue;
                }
                String neighbour = p.getOtherEnd(current);
                if (!visited.contains(neighbour)) {
                    visited.add(neighbour);
                    queue.add(neighbour);
                }
            }
        }
        return reachable;
    }

    // areas that no reservoir can reach
    public List<String> findDisconnectedAreas() {
        HashSet<String> reached = new HashSet<>();
        for (String rid : reservoirs.keySet()) {
            reached.addAll(findReachableAreas(rid));
        }
        List<String> disconnected = new ArrayList<>();
        for (String areaId : demandAreas.keySet()) {
            if (!reached.contains(areaId)) {
                disconnected.add(areaId);
            }
        }
        return disconnected;
    }

    // ---------- supply path (BFS) ----------

    // The same BFS, but this time it remembers how it got to each node, so the
    // route can be shown. It returns the nodes from the reservoir to the area,
    // for example [R1, A2, A4, A3], or null if there is no working route.
    // BFS finds the route with the FEWEST pipelines.
    public List<String> bfsPath(String startId, String targetId) {
        if (!adjacencyList.containsKey(startId) || !adjacencyList.containsKey(targetId)) {
            return null;
        }
        HashMap<String, String> cameFrom = new HashMap<>();   // node -> the node we came from
        HashSet<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startId);
        visited.add(startId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(targetId)) {
                // walk backwards from the target to the start, then turn the list round
                LinkedList<String> path = new LinkedList<>();
                String step = targetId;
                while (step != null) {
                    path.addFirst(step);
                    step = cameFrom.get(step);
                }
                return path;
            }
            for (Pipeline p : adjacencyList.get(current)) {
                if (!p.isWorking()) {
                    continue;
                }
                String neighbour = p.getOtherEnd(current);
                if (!visited.contains(neighbour)) {
                    visited.add(neighbour);
                    cameFrom.put(neighbour, current);
                    queue.add(neighbour);
                }
            }
        }
        return null;
    }

    // The route that supplies an area: from the reservoir that gave it the
    // most water. If it got no water, the shortest route from any reservoir.
    // The list is empty when the area is cut off.
    public List<String> findSupplyPath(DemandArea area) {
        String main = area.getMainSource();
        if (main != null) {
            List<String> path = bfsPath(main, area.getId());
            if (path != null) {
                return path;
            }
        }
        List<String> shortest = null;
        for (String reservoirId : reservoirs.keySet()) {
            List<String> path = bfsPath(reservoirId, area.getId());
            if (path != null && (shortest == null || path.size() < shortest.size())) {
                shortest = path;
            }
        }
        return shortest == null ? new ArrayList<String>() : shortest;
    }

    // SUPPLIED, LOW SUPPLY, SHORTAGE or DISCONNECTED
    public String getAreaStatus(DemandArea a, List<String> disconnected) {
        if (disconnected.contains(a.getId())) {
            return "DISCONNECTED";
        }
        if (a.getUnmetDemand() == 0) {
            return "SUPPLIED";
        }
        if (a.getAllocated() == 0) {
            return "SHORTAGE";
        }
        return "LOW SUPPLY";
    }

    // ---------- allocation ----------

    // Works out the whole day's distribution again from scratch.
    // Called after every change (pipeline, supply, new area, ...).
    public void recalculate() {
        for (Reservoir r : reservoirs.values()) {
            r.resetDailySupply();
        }
        for (DemandArea a : demandAreas.values()) {
            a.resetDailyDemand();
        }

        // which areas each reservoir can reach right now
        HashMap<String, List<String>> reach = new HashMap<>();
        for (String rid : reservoirs.keySet()) {
            reach.put(rid, findReachableAreas(rid));
        }

        // Every area puts in a request for its daily demand. The policy
        // decides the order (Critical first, then High, then Normal).
        PriorityQueue<WaterRequest> queue = new PriorityQueue<>(policy);
        int arrival = 0;
        for (DemandArea a : demandAreas.values()) {
            arrival++;
            queue.add(new WaterRequest(a, a.getDailyDemand(), arrival));
        }

        while (!queue.isEmpty()) {
            DemandArea area = queue.poll().getArea();

            // keep taking water until the area is satisfied or no
            // reachable reservoir has any water left
            while (area.getUnmetDemand() > 0) {
                Reservoir best = null;
                for (String rid : reservoirs.keySet()) {
                    if (!reach.get(rid).contains(area.getId())) {
                        continue; // this reservoir cannot reach the area
                    }
                    Reservoir r = reservoirs.get(rid);
                    // pick the reachable reservoir with the most water left
                    if (r.getRemaining() > 0 && (best == null || r.getRemaining() > best.getRemaining())) {
                        best = r;
                    }
                }
                if (best == null) {
                    break; // nothing left to give, the rest stays unmet
                }
                double given = best.allocateWater(area.getUnmetDemand());
                area.receiveAllocation(given);
                area.addSource(best.getId(), given);   // remember who supplied it
            }
        }
    }

    // ---------- totals ----------

    public double getTotalAvailable() {
        double total = 0;
        for (Reservoir r : reservoirs.values()) {
            total = total + r.getCapacity();
        }
        return total;
    }

    public double getTotalAllocated() {
        double total = 0;
        for (Reservoir r : reservoirs.values()) {
            total = total + r.getAllocated();
        }
        return total;
    }

    public double getTotalUnmet() {
        double total = 0;
        for (DemandArea a : demandAreas.values()) {
            total = total + a.getUnmetDemand();
        }
        return total;
    }

    public double getTotalRemaining() {
        return getTotalAvailable() - getTotalAllocated();
    }
}
