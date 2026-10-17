import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

// The rules of the system. Every change goes through here:
//   1. check the input is valid
//   2. save it in the database
//   3. update the network in memory and recalculate
//   4. write a line in the event history (with the name of the user)
// Methods are synchronized so two browser tabs can't change data at the same time.
public class WaterService {

    private static final List<String> INSTITUTION_TYPES =
            Arrays.asList("Hospital", "Fire Station", "School", "Police Station", "Other");

    private WaterNetwork network = new WaterNetwork();
    private MaintenanceQueue queue = new MaintenanceQueue();
    private AllocationHistory history = new AllocationHistory();
    private int day = 1;   // the simulated day

    public WaterNetwork getNetwork() { return network; }
    public MaintenanceQueue getQueue() { return queue; }
    public AllocationHistory getHistory() { return history; }
    public int getDay() { return day; }

    // called once when the program starts
    public synchronized void start() throws SQLException {
        Database.createTables();
        Auth.createDefaultUsers();
        if (Database.isEmpty()) {
            Database.loadSampleData();
            Database.logEvent("SYSTEM", "Sample data for Neer Nagar loaded into the database", "system");
        }
        Database.loadInto(network);
        queue.clear();
        Database.loadMaintenance(queue);
        day = Database.getDay();
        history.clear();
        Database.loadAllocationHistory(history, 50);

        // a pipeline that is failed but has no repair job (for example from an older
        // version of the program) gets one now, so nothing is forgotten
        for (Pipeline p : network.getAllPipelines()) {
            if (!p.isWorking() && !queue.hasOpenRequestFor(p.getId())) {
                createRepairJob(p, "system");
            }
        }
        network.recalculate();
        if (Database.countAllocations() == 0) {
            recordAllocation("START", "system");
        }
    }

    public synchronized void resetSampleData(String user) throws SQLException {
        Database.loadSampleData();
        Database.loadInto(network);
        queue.clear();
        history.clear();
        day = 1;
        finishChange("SYSTEM", "Database reset to the sample data", user);   // one snapshot, one event
    }

    // ================= simulated days and manual readings =================

    // a new day: reservoirs are full again and every area asks for its daily demand again
    public synchronized void startNewDay(String user) throws SQLException {
        day++;
        Database.setDay(day);
        finishChange("NEW DAY", "Day " + day + " started: reservoirs refilled, every area asks for its daily demand again", user);
    }

    // the operator types in today's measured demand for an area
    public synchronized void recordDemandReading(String user, String id, String demandText) throws SQLException {
        DemandArea old = network.getDemandAreas().get(id);
        if (old == null) {
            throw new IllegalArgumentException("No area with id " + id);
        }
        double demand = checkNumber(demandText, "Demand reading");
        DemandArea updated = old.withDemand(demand);   // same kind of area, new demand
        Database.updateArea(updated);
        network.replaceDemandArea(updated);
        finishChange("DEMAND READING", old.getName() + " (" + id + "): " + litres(demand)
                + " (it was " + litres(old.getDailyDemand()) + ")", user);
    }

    // the operator types in today's measured supply of a reservoir
    public synchronized void recordSupplyReading(String user, String id, String supplyText) throws SQLException {
        Reservoir r = network.getReservoirs().get(id);
        if (r == null) {
            throw new IllegalArgumentException("No reservoir with id " + id);
        }
        double supply = checkNumber(supplyText, "Supply reading");
        double old = r.getCapacity();
        Database.updateReservoir(id, r.getName(), supply);
        r.setCapacity(supply);
        finishChange("SUPPLY READING", r.getName() + " (" + id + "): " + litres(supply) + " (it was " + litres(old) + ")", user);
    }

    // ================= every change ends here =================

    // recalculates the water, writes the event, and saves a snapshot of who got what
    private void finishChange(String eventType, String details, String user) throws SQLException {
        network.recalculate();
        Database.logEvent(eventType, details, user);
        recordAllocation(eventType, user);
    }

    private void recordAllocation(String trigger, String user) throws SQLException {
        List<String> disconnected = network.findDisconnectedAreas();
        AllocationRecord record = new AllocationRecord(0, MaintenanceRequest.now(), day, trigger, user,
                network.getTotalAvailable(), network.getTotalAllocated(), network.getTotalUnmet(), disconnected.size());
        for (DemandArea a : network.getDemandAreas().values()) {
            AllocationRecord.Line line = new AllocationRecord.Line();
            line.areaId = a.getId();
            line.areaName = a.getName();
            line.priority = a.getPriority().name();
            line.demand = a.getDailyDemand();
            line.allocated = a.getAllocated();
            line.status = network.getAreaStatus(a, disconnected);
            record.addLine(line);
        }
        record.setId(Database.insertAllocation(record));
        history.addNewest(record);
    }

    // ================= reservoirs =================

    public synchronized void addReservoir(String user, String id, String name, String capacityText) throws SQLException {
        id = checkId(id);
        name = checkName(name);
        double capacity = checkNumber(capacityText, "Daily supply");
        if (network.hasNode(id)) {
            throw new IllegalArgumentException("The id " + id + " is already used by another reservoir or area.");
        }
        Database.insertReservoir(id, name, capacity);
        network.addReservoir(new Reservoir(id, name, capacity));
        finishChange("ADD RESERVOIR", name + " (" + id + ") added with " + litres(capacity) + " per day", user);
    }

    public synchronized void updateReservoir(String user, String id, String name, String capacityText) throws SQLException {
        Reservoir r = network.getReservoirs().get(id);
        if (r == null) {
            throw new IllegalArgumentException("No reservoir with id " + id);
        }
        name = checkName(name);
        double capacity = checkNumber(capacityText, "Daily supply");
        double oldCapacity = r.getCapacity();

        Database.updateReservoir(id, name, capacity);
        r.setName(name);
        r.setCapacity(capacity);

        if (oldCapacity != capacity) {
            finishChange("SUPPLY CHANGE", name + " (" + id + "): " + litres(oldCapacity) + " to " + litres(capacity), user);
        } else {
            finishChange("UPDATE RESERVOIR", name + " (" + id + ") updated", user);
        }
    }

    public synchronized void deleteReservoir(String user, String id) throws SQLException {
        Reservoir r = network.getReservoirs().get(id);
        if (r == null) {
            throw new IllegalArgumentException("No reservoir with id " + id);
        }
        cancelJobsOfPipelines(network.getPipelinesTouching(id), user);
        Database.deleteReservoir(id);
        network.removeNode(id);
        finishChange("DELETE RESERVOIR", r.getName() + " (" + id + ") and its pipelines deleted", user);
    }

    // ================= demand areas =================

    public synchronized void addArea(String user, String id, String name, String demandText, String type,
                                     String priorityText, String institutionType, String householdsText) throws SQLException {
        id = checkId(id);
        if (network.hasNode(id)) {
            throw new IllegalArgumentException("The id " + id + " is already used by another reservoir or area.");
        }
        DemandArea area = buildArea(id, name, demandText, type, priorityText, institutionType, householdsText);
        Database.insertArea(area);
        network.addDemandArea(area);
        finishChange("ADD AREA", area.getName() + " (" + id + ") added, " + area.getPriority()
                + " priority, " + area.getDetails() + ", demand " + litres(area.getDailyDemand()), user);
    }

    public synchronized void updateArea(String user, String id, String name, String demandText, String type,
                                        String priorityText, String institutionType, String householdsText) throws SQLException {
        DemandArea old = network.getDemandAreas().get(id);
        if (old == null) {
            throw new IllegalArgumentException("No area with id " + id);
        }
        DemandArea area = buildArea(id, name, demandText, type, priorityText, institutionType, householdsText);

        Database.updateArea(area);
        network.replaceDemandArea(area);   // a new object, because the type may have changed

        if (old.getDailyDemand() != area.getDailyDemand()) {
            finishChange("DEMAND CHANGE", area.getName() + " (" + id + "): "
                    + litres(old.getDailyDemand()) + " to " + litres(area.getDailyDemand()), user);
        } else {
            finishChange("UPDATE AREA", area.getName() + " (" + id + ") updated, " + area.getPriority()
                    + " priority, " + area.getDetails(), user);
        }
    }

    public synchronized void deleteArea(String user, String id) throws SQLException {
        DemandArea a = network.getDemandAreas().get(id);
        if (a == null) {
            throw new IllegalArgumentException("No area with id " + id);
        }
        cancelJobsOfPipelines(network.getPipelinesTouching(id), user);
        Database.deleteArea(id);
        network.removeNode(id);
        finishChange("DELETE AREA", a.getName() + " (" + id + ") and its pipelines deleted", user);
    }

    // checks the typed values and makes the right kind of area (child class)
    private DemandArea buildArea(String id, String name, String demandText, String type,
                                 String priorityText, String institutionType, String householdsText) {
        name = checkName(name);
        double demand = checkNumber(demandText, "Daily demand");

        if ("CRITICAL_INSTITUTION".equals(type)) {
            if (institutionType == null || !INSTITUTION_TYPES.contains(institutionType)) {
                throw new IllegalArgumentException("Choose the type of institution: " + INSTITUTION_TYPES);
            }
            return new CriticalInstitution(id, name, demand, institutionType);
        }
        if ("RESIDENTIAL".equals(type)) {
            DemandArea.Priority priority;
            try {
                priority = DemandArea.Priority.valueOf(priorityText.trim().toUpperCase());
            } catch (Exception e) {
                throw new IllegalArgumentException("Priority must be HIGH or NORMAL for a residential area.");
            }
            if (priority == DemandArea.Priority.CRITICAL) {
                throw new IllegalArgumentException("Priority must be HIGH or NORMAL for a residential area.");
            }
            double households = checkNumber(householdsText, "Households");
            if (households != Math.floor(households)) {
                throw new IllegalArgumentException("Households must be a whole number.");
            }
            return new ResidentialArea(id, name, demand, priority, (int) households);
        }
        throw new IllegalArgumentException("Choose the type of area: critical institution or residential.");
    }

    // ================= pipelines =================

    public synchronized void addPipeline(String user, String id, String from, String to) throws SQLException {
        id = checkId(id);
        if (network.findPipeline(id) != null) {
            throw new IllegalArgumentException("A pipeline with id " + id + " already exists.");
        }
        if (!network.hasNode(from) || !network.hasNode(to)) {
            throw new IllegalArgumentException("Both ends of the pipeline must be an existing reservoir or area.");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("A pipeline must join two different places.");
        }
        for (Pipeline p : network.getAllPipelines()) {
            boolean same = (p.getNode1().equals(from) && p.getNode2().equals(to))
                    || (p.getNode1().equals(to) && p.getNode2().equals(from));
            if (same) {
                throw new IllegalArgumentException("Pipeline " + p.getId() + " already joins " + from + " and " + to + ".");
            }
        }
        Database.insertPipeline(id, from, to);
        network.addPipeline(new Pipeline(id, from, to));
        finishChange("ADD PIPELINE", "Pipeline " + id + " added between " + from + " and " + to, user);
    }

    // A pipeline fails: the water is re-allocated at once and a repair job
    // joins the BACK of the repair queue.
    public synchronized void failPipeline(String user, String id) throws SQLException {
        Pipeline p = network.findPipeline(id);
        if (p == null) {
            throw new IllegalArgumentException("No pipeline with id " + id);
        }
        if (!p.isWorking()) {
            throw new IllegalArgumentException("Pipeline " + id + " has already failed and is in the repair queue.");
        }
        Database.updatePipelineStatus(id, false);
        p.markFailed();
        finishChange("PIPELINE FAILED", "Pipeline " + id + " (" + p.getNode1() + " - " + p.getNode2() + ") failed", user);

        MaintenanceRequest job = createRepairJob(p, user);
        Database.logEvent("REPAIR REQUEST", "Job #" + job.getId() + " for pipeline " + id
                + " added to the repair queue (position " + queue.pendingCount() + ")", user);
    }

    public synchronized void deletePipeline(String user, String id) throws SQLException {
        Pipeline p = network.findPipeline(id);
        if (p == null) {
            throw new IllegalArgumentException("No pipeline with id " + id);
        }
        List<Pipeline> one = Arrays.asList(p);
        cancelJobsOfPipelines(one, user);
        Database.deletePipeline(id);
        network.removePipeline(id);
        finishChange("DELETE PIPELINE", "Pipeline " + id + " (" + p.getNode1() + " - " + p.getNode2() + ") deleted", user);
    }

    // ================= repair queue =================

    // the crew takes the OLDEST waiting job
    public synchronized void startNextJob(String user) throws SQLException {
        MaintenanceRequest job = queue.startNext();
        Database.updateMaintenance(job);
        Database.logEvent("REPAIR STARTED", "Job #" + job.getId() + ": repair of pipeline " + job.getPipelineId() + " started", user);
    }

    // the crew has finished: the pipeline works again and the water is re-allocated
    public synchronized void completeCurrentJob(String user) throws SQLException {
        MaintenanceRequest job = queue.completeCurrent();
        Pipeline p = network.findPipeline(job.getPipelineId());
        if (p != null) {
            p.markWorking();
            Database.updatePipelineStatus(p.getId(), true);
        }
        Database.updateMaintenance(job);
        finishChange("PIPELINE REPAIRED", "Job #" + job.getId() + ": pipeline " + job.getPipelineId()
                + " repaired, service restored", user);
    }

    // saves a new job in the database and puts it at the back of the queue
    private MaintenanceRequest createRepairJob(Pipeline p, String user) throws SQLException {
        String description = "Pipeline " + p.getId() + " (" + p.getNode1() + " - " + p.getNode2() + ") has failed";
        String time = MaintenanceRequest.now();
        int id = Database.insertMaintenance(p.getId(), description, time, user);
        MaintenanceRequest job = new MaintenanceRequest(id, p.getId(), description,
                MaintenanceRequest.Status.PENDING, time, null, null, user);
        queue.addPending(job);
        return job;
    }

    // used when a pipeline (or the place it joins) is deleted
    private void cancelJobsOfPipelines(List<Pipeline> pipes, String user) throws SQLException {
        for (Pipeline p : pipes) {
            MaintenanceRequest cancelled = queue.cancelFor(p.getId());
            if (cancelled != null) {
                Database.updateMaintenance(cancelled);
                Database.logEvent("REPAIR CANCELLED", "Job #" + cancelled.getId() + " cancelled because pipeline "
                        + p.getId() + " was deleted", user);
            }
        }
    }

    // ================= account =================

    public synchronized void changePassword(String user, String oldPassword, String newPassword) throws SQLException {
        Auth.changePassword(user, oldPassword, newPassword);
        Database.logEvent("PASSWORD CHANGED", user + " changed their password", user);
    }

    // ================= input checks =================

    private String checkId(String id) {
        if (id == null) {
            id = "";
        }
        id = id.trim().toUpperCase();
        if (!id.matches("[A-Z0-9_-]{1,10}")) {
            throw new IllegalArgumentException("Id must be 1 to 10 letters or digits, for example R3 or A9.");
        }
        return id;
    }

    private String checkName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty.");
        }
        name = name.trim();
        if (name.length() > 40) {
            throw new IllegalArgumentException("Name can be at most 40 characters.");
        }
        return name;
    }

    private double checkNumber(String text, String fieldName) {
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " must be a number.");
        }
        if (value < 0 || value > 10000000) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 10,000,000.");
        }
        return value;
    }

    private String litres(double value) {
        return String.format("%,.0f L", value);
    }
}
