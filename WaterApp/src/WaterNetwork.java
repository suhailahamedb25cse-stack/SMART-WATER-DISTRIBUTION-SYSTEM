import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.TreeMap;

public class WaterNetwork {

    private TreeMap<String, Reservoir> reservoirs = new TreeMap<>();
    private TreeMap<String, DemandArea> demandAreas = new TreeMap<>();
    private HashMap<String, List<Pipeline>> adjacencyList = new HashMap<>();
    private List<Pipeline> allPipelines = new ArrayList<>();

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

    public TreeMap<String, Reservoir> getReservoirs() { return reservoirs; }
    public TreeMap<String, DemandArea> getDemandAreas() { return demandAreas; }
    public List<Pipeline> getAllPipelines() { return allPipelines; }

    public Pipeline findPipeline(String id) {
        for (Pipeline p : allPipelines) {
            if (p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

  
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

    public void recalculate() {
        for (Reservoir r : reservoirs.values()) {
            r.resetDailySupply();
        }
        for (DemandArea a : demandAreas.values()) {
            a.resetDailyDemand();
        }

        
        HashMap<String, List<String>> reach = new HashMap<>();
        for (String rid : reservoirs.keySet()) {
            reach.put(rid, findReachableAreas(rid));
        }

        PriorityQueue<DemandArea> queue = new PriorityQueue<>(new Comparator<DemandArea>() {
            public int compare(DemandArea a, DemandArea b) {
                if (a.getPriority() != b.getPriority()) {
                    return a.getPriority().ordinal() - b.getPriority().ordinal();
                }
                return a.getId().compareTo(b.getId());
            }
        });
        queue.addAll(demandAreas.values());

        while (!queue.isEmpty()) {
            DemandArea area = queue.poll();

            while (area.getUnmetDemand() > 0) {
                Reservoir best = null;
                for (String rid : reservoirs.keySet()) {
                    if (!reach.get(rid).contains(area.getId())) {
                        continue; 
                    }
                    Reservoir r = reservoirs.get(rid);
                    if (r.getRemaining() > 0 && (best == null || r.getRemaining() > best.getRemaining())) {
                        best = r;
                    }
                }
                if (best == null) {
                    break; 
                }
                double given = best.allocateWater(area.getUnmetDemand());
                area.receiveAllocation(given);
            }
        }
    }

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

    public double getTotalRemaining() {
        return getTotalAvailable() - getTotalAllocated();
    }

   
    public void loadDemoData() {
        reservoirs.clear();
        demandAreas.clear();
        adjacencyList.clear();
        allPipelines.clear();
        
        addReservoir(new Reservoir("R1", "North Reservoir", 18000));
        addReservoir(new Reservoir("R2", "South Reservoir", 12000));

        addDemandArea(new DemandArea("A1", "Gandhi Nagar", 5000, DemandArea.Priority.HIGH));
        addDemandArea(new DemandArea("A2", "Lake View Colony", 3000, DemandArea.Priority.NORMAL));
        addDemandArea(new DemandArea("A3", "City Hospital Zone", 4000, DemandArea.Priority.CRITICAL));
        addDemandArea(new DemandArea("A4", "Market Street", 3500, DemandArea.Priority.NORMAL));
        addDemandArea(new DemandArea("A5", "Riverside Township", 4500, DemandArea.Priority.HIGH));
        addDemandArea(new DemandArea("A6", "Old Town", 3000, DemandArea.Priority.NORMAL));
        addDemandArea(new DemandArea("A7", "Fire Station Zone", 2000, DemandArea.Priority.CRITICAL));
        addDemandArea(new DemandArea("A8", "Green Park", 2500, DemandArea.Priority.NORMAL));

        addPipeline(new Pipeline("P1", "R1", "A1"));
        addPipeline(new Pipeline("P2", "R1", "A2"));
        addPipeline(new Pipeline("P3", "A1", "A3"));
        addPipeline(new Pipeline("P4", "A2", "A4"));
        addPipeline(new Pipeline("P5", "R2", "A5"));
        addPipeline(new Pipeline("P6", "R2", "A6"));
        addPipeline(new Pipeline("P7", "A5", "A7"));
        addPipeline(new Pipeline("P8", "A6", "A8"));
        addPipeline(new Pipeline("P9", "A4", "A5"));

        recalculate();
    }
}
