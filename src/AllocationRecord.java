import java.util.ArrayList;
import java.util.List;

// A snapshot of "who got how much water" taken after every change.
// A list of these is the allocation history.
public class AllocationRecord {

    // what one area received in this snapshot
    public static class Line {
        public String areaId;
        public String areaName;
        public String priority;
        public double demand;
        public double allocated;
        public String status;
    }

    private int id;
    private String time;
    private int day;
    private String trigger;      // what caused the snapshot, e.g. PIPELINE FAILED
    private String user;
    private double totalAvailable;
    private double totalAllocated;
    private double totalUnmet;
    private int disconnectedAreas;
    private List<Line> lines = new ArrayList<>();

    public AllocationRecord(int id, String time, int day, String trigger, String user,
                            double totalAvailable, double totalAllocated, double totalUnmet, int disconnectedAreas) {
        this.id = id;
        this.time = time;
        this.day = day;
        this.trigger = trigger;
        this.user = user;
        this.totalAvailable = totalAvailable;
        this.totalAllocated = totalAllocated;
        this.totalUnmet = totalUnmet;
        this.disconnectedAreas = disconnectedAreas;
    }

    public int getId() { return id; }
    public String getTime() { return time; }
    public int getDay() { return day; }
    public String getTrigger() { return trigger; }
    public String getUser() { return user; }
    public double getTotalAvailable() { return totalAvailable; }
    public double getTotalAllocated() { return totalAllocated; }
    public double getTotalUnmet() { return totalUnmet; }
    public int getDisconnectedAreas() { return disconnectedAreas; }
    public List<Line> getLines() { return lines; }

    public void setId(int id) { this.id = id; }
    public void addLine(Line line) { lines.add(line); }
}
