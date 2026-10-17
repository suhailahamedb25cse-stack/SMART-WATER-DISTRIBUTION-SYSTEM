import java.util.ArrayList;
import java.util.List;

// The allocation history kept in memory (an ArrayList, newest first).
// Only the latest 50 snapshots are kept here; all of them stay in the database.
public class AllocationHistory {

    private static final int KEEP = 50;
    private ArrayList<AllocationRecord> records = new ArrayList<>();

    public void clear() {
        records.clear();
    }

    // a new snapshot goes to the FRONT of the list
    public void addNewest(AllocationRecord record) {
        records.add(0, record);
        trim();
    }

    // used when loading from the database (already newest first)
    public void addOldest(AllocationRecord record) {
        records.add(record);
        trim();
    }

    private void trim() {
        while (records.size() > KEEP) {
            records.remove(records.size() - 1);
        }
    }

    public List<AllocationRecord> getRecords() {
        return records;
    }
}
