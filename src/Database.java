import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// All the database work is done here using JDBC.
// We use SQLite, so the whole database is just one file (waterdb.sqlite)
// next to the program and no separate database server is needed.
// To use MySQL instead, only DB_URL and the driver jar need to change
// (plus a few small SQL differences such as AUTOINCREMENT).
public class Database {

    private static final String DB_URL = "jdbc:sqlite:waterdb.sqlite";

    // opens a connection to the database file
    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    // ================= tables =================

    public static void createTables() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS reservoirs ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "capacity REAL NOT NULL CHECK (capacity >= 0))");
            st.execute("CREATE TABLE IF NOT EXISTS demand_areas ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "daily_demand REAL NOT NULL CHECK (daily_demand >= 0), "
                    + "priority TEXT NOT NULL CHECK (priority IN ('CRITICAL','HIGH','NORMAL')), "
                    + "area_type TEXT NOT NULL DEFAULT 'RESIDENTIAL', "
                    + "institution_type TEXT, "
                    + "households INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS pipelines ("
                    + "id TEXT PRIMARY KEY, node1 TEXT NOT NULL, node2 TEXT NOT NULL, "
                    + "working INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE TABLE IF NOT EXISTS maintenance_requests ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, pipeline_id TEXT NOT NULL, "
                    + "description TEXT NOT NULL, status TEXT NOT NULL, created_time TEXT NOT NULL, "
                    + "started_time TEXT, completed_time TEXT, created_by TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "event_time TEXT NOT NULL DEFAULT (datetime('now','localtime')), "
                    + "event_type TEXT NOT NULL, details TEXT NOT NULL, user_name TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS allocation_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, run_time TEXT NOT NULL, day INTEGER NOT NULL, "
                    + "trigger_event TEXT NOT NULL, user_name TEXT, total_available REAL NOT NULL, "
                    + "total_allocated REAL NOT NULL, total_unmet REAL NOT NULL, disconnected_areas INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS allocation_details ("
                    + "record_id INTEGER NOT NULL, area_id TEXT NOT NULL, area_name TEXT NOT NULL, priority TEXT NOT NULL, "
                    + "demand REAL NOT NULL, allocated REAL NOT NULL, status TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "username TEXT PRIMARY KEY, full_name TEXT NOT NULL, "
                    + "role TEXT NOT NULL CHECK (role IN ('OPERATOR','VIEWER')), "
                    + "salt TEXT NOT NULL, password_hash TEXT NOT NULL)");

            // If this database was made by an older version of the program,
            // add the columns that are new (your old data is kept).
            if (!hasColumn(con, "demand_areas", "area_type")) {
                st.execute("ALTER TABLE demand_areas ADD COLUMN area_type TEXT NOT NULL DEFAULT 'RESIDENTIAL'");
                st.execute("ALTER TABLE demand_areas ADD COLUMN institution_type TEXT");
                st.execute("ALTER TABLE demand_areas ADD COLUMN households INTEGER NOT NULL DEFAULT 0");
                st.execute("UPDATE demand_areas SET area_type = 'CRITICAL_INSTITUTION', institution_type = 'Other' "
                        + "WHERE priority = 'CRITICAL'");
            }
            if (!hasColumn(con, "events", "user_name")) {
                st.execute("ALTER TABLE events ADD COLUMN user_name TEXT");
            }
        }
    }

    private static boolean hasColumn(Connection con, String table, String column) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equals(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isEmpty() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservoirs")) {
            rs.next();
            return rs.getInt(1) == 0;
        }
    }

    // Deletes the network data and puts the sample town (Neer Nagar) back.
    // The event history and the user accounts are kept. The day counter and the allocation history start again.
    public static void loadSampleData() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement()) {
            st.execute("DELETE FROM maintenance_requests");
            st.execute("DELETE FROM allocation_details");
            st.execute("DELETE FROM allocation_history");
            st.execute("DELETE FROM settings");
            st.execute("DELETE FROM pipelines");
            st.execute("DELETE FROM demand_areas");
            st.execute("DELETE FROM reservoirs");
        }

        for (Reservoir r : SampleData.reservoirs()) {
            insertReservoir(r.getId(), r.getName(), r.getCapacity());
        }
        for (DemandArea area : SampleData.areas()) {
            insertArea(area);
        }
        for (String[] p : SampleData.pipelines()) {
            insertPipeline(p[0], p[1], p[2]);
        }
    }

    // ================= READ: copy everything from the database into memory =================

    public static void loadInto(WaterNetwork network) throws SQLException {
        network.clear();
        try (Connection con = connect(); Statement st = con.createStatement()) {

            ResultSet rs = st.executeQuery("SELECT id, name, capacity FROM reservoirs");
            while (rs.next()) {
                network.addReservoir(new Reservoir(rs.getString("id"), rs.getString("name"), rs.getDouble("capacity")));
            }

            // each row becomes the right KIND of area (child class)
            rs = st.executeQuery("SELECT id, name, daily_demand, priority, area_type, institution_type, households FROM demand_areas");
            while (rs.next()) {
                String id = rs.getString("id");
                String name = rs.getString("name");
                double demand = rs.getDouble("daily_demand");
                if (rs.getString("area_type").equals("CRITICAL_INSTITUTION")) {
                    String type = rs.getString("institution_type");
                    network.addDemandArea(new CriticalInstitution(id, name, demand, type == null ? "Other" : type));
                } else {
                    network.addDemandArea(new ResidentialArea(id, name, demand,
                            DemandArea.Priority.valueOf(rs.getString("priority")), rs.getInt("households")));
                }
            }

            // pipelines last, because they join reservoirs and areas that must already exist
            rs = st.executeQuery("SELECT id, node1, node2, working FROM pipelines");
            while (rs.next()) {
                Pipeline p = new Pipeline(rs.getString("id"), rs.getString("node1"), rs.getString("node2"));
                if (rs.getInt("working") == 0) {
                    p.markFailed();
                }
                network.addPipeline(p);
            }
        }
    }

    // ================= CREATE =================

    public static void insertReservoir(String id, String name, double capacity) throws SQLException {
        String sql = "INSERT INTO reservoirs (id, name, capacity) VALUES (?, ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setDouble(3, capacity);
            ps.executeUpdate();
        }
    }

    public static void insertArea(DemandArea a) throws SQLException {
        String sql = "INSERT INTO demand_areas (id, name, daily_demand, priority, area_type, institution_type, households) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, a.getId());
            ps.setString(2, a.getName());
            ps.setDouble(3, a.getDailyDemand());
            ps.setString(4, a.getPriority().name());
            ps.setString(5, a.getAreaType());
            ps.setString(6, a.getInstitutionType());
            ps.setInt(7, a.getHouseholds());
            ps.executeUpdate();
        }
    }

    public static void insertPipeline(String id, String node1, String node2) throws SQLException {
        String sql = "INSERT INTO pipelines (id, node1, node2, working) VALUES (?, ?, ?, 1)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, node1);
            ps.setString(3, node2);
            ps.executeUpdate();
        }
    }

    // ================= UPDATE =================

    public static void updateReservoir(String id, String name, double capacity) throws SQLException {
        String sql = "UPDATE reservoirs SET name = ?, capacity = ? WHERE id = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setDouble(2, capacity);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public static void updateArea(DemandArea a) throws SQLException {
        String sql = "UPDATE demand_areas SET name = ?, daily_demand = ?, priority = ?, area_type = ?, "
                + "institution_type = ?, households = ? WHERE id = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, a.getName());
            ps.setDouble(2, a.getDailyDemand());
            ps.setString(3, a.getPriority().name());
            ps.setString(4, a.getAreaType());
            ps.setString(5, a.getInstitutionType());
            ps.setInt(6, a.getHouseholds());
            ps.setString(7, a.getId());
            ps.executeUpdate();
        }
    }

    public static void updatePipelineStatus(String id, boolean working) throws SQLException {
        String sql = "UPDATE pipelines SET working = ? WHERE id = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, working ? 1 : 0);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    // ================= DELETE =================

    public static void deleteReservoir(String id) throws SQLException {
        deleteById("DELETE FROM reservoirs WHERE id = ?", id);
        deletePipelinesTouching(id);
    }

    public static void deleteArea(String id) throws SQLException {
        deleteById("DELETE FROM demand_areas WHERE id = ?", id);
        deletePipelinesTouching(id);
    }

    public static void deletePipeline(String id) throws SQLException {
        deleteById("DELETE FROM pipelines WHERE id = ?", id);
    }

    private static void deletePipelinesTouching(String nodeId) throws SQLException {
        String sql = "DELETE FROM pipelines WHERE node1 = ? OR node2 = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, nodeId);
            ps.executeUpdate();
        }
    }

    private static void deleteById(String sql, String id) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    // ================= repair queue =================

    // saves a new PENDING request and returns the id the database gave it
    public static int insertMaintenance(String pipelineId, String description, String createdTime, String createdBy) throws SQLException {
        String sql = "INSERT INTO maintenance_requests (pipeline_id, description, status, created_time, created_by) "
                + "VALUES (?, ?, 'PENDING', ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, pipelineId);
            ps.setString(2, description);
            ps.setString(3, createdTime);
            ps.setString(4, createdBy);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getInt(1);
        }
    }

    public static void updateMaintenance(MaintenanceRequest r) throws SQLException {
        String sql = "UPDATE maintenance_requests SET status = ?, started_time = ?, completed_time = ? WHERE id = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, r.getStatus().name());
            ps.setString(2, r.getStartedTime());
            ps.setString(3, r.getCompletedTime());
            ps.setInt(4, r.getId());
            ps.executeUpdate();
        }
    }

    // fills the queue from the database when the program starts
    public static void loadMaintenance(MaintenanceQueue queue) throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement()) {

            // open jobs, oldest first, so the queue keeps its order
            ResultSet rs = st.executeQuery("SELECT * FROM maintenance_requests "
                    + "WHERE status IN ('PENDING','IN_PROGRESS') ORDER BY id");
            while (rs.next()) {
                MaintenanceRequest r = readRequest(rs);
                if (r.getStatus() == MaintenanceRequest.Status.IN_PROGRESS) {
                    queue.setCurrent(r);
                } else {
                    queue.addPending(r);
                }
            }

            // latest finished jobs, newest first
            rs = st.executeQuery("SELECT * FROM maintenance_requests "
                    + "WHERE status IN ('DONE','CANCELLED') ORDER BY id DESC LIMIT 20");
            while (rs.next()) {
                queue.addHistory(readRequest(rs));
            }
        }
    }

    private static MaintenanceRequest readRequest(ResultSet rs) throws SQLException {
        return new MaintenanceRequest(rs.getInt("id"), rs.getString("pipeline_id"), rs.getString("description"),
                MaintenanceRequest.Status.valueOf(rs.getString("status")), rs.getString("created_time"),
                rs.getString("started_time"), rs.getString("completed_time"), rs.getString("created_by"));
    }

    // ================= allocation history =================

    // saves a snapshot and its lines; returns the id the database gave it
    public static int insertAllocation(AllocationRecord r) throws SQLException {
        String sql = "INSERT INTO allocation_history (run_time, day, trigger_event, user_name, total_available, "
                + "total_allocated, total_unmet, disconnected_areas) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getTime());
            ps.setInt(2, r.getDay());
            ps.setString(3, r.getTrigger());
            ps.setString(4, r.getUser());
            ps.setDouble(5, r.getTotalAvailable());
            ps.setDouble(6, r.getTotalAllocated());
            ps.setDouble(7, r.getTotalUnmet());
            ps.setInt(8, r.getDisconnectedAreas());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            int id = keys.getInt(1);

            String lineSql = "INSERT INTO allocation_details (record_id, area_id, area_name, priority, demand, allocated, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement lp = con.prepareStatement(lineSql)) {
                for (AllocationRecord.Line line : r.getLines()) {
                    lp.setInt(1, id);
                    lp.setString(2, line.areaId);
                    lp.setString(3, line.areaName);
                    lp.setString(4, line.priority);
                    lp.setDouble(5, line.demand);
                    lp.setDouble(6, line.allocated);
                    lp.setString(7, line.status);
                    lp.executeUpdate();
                }
            }
            return id;
        }
    }

    // the latest snapshots, newest first
    public static void loadAllocationHistory(AllocationHistory history, int limit) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM allocation_history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                AllocationRecord r = new AllocationRecord(rs.getInt("id"), rs.getString("run_time"), rs.getInt("day"),
                        rs.getString("trigger_event"), rs.getString("user_name"), rs.getDouble("total_available"),
                        rs.getDouble("total_allocated"), rs.getDouble("total_unmet"), rs.getInt("disconnected_areas"));
                try (PreparedStatement lp = con.prepareStatement(
                        "SELECT * FROM allocation_details WHERE record_id = ? ORDER BY area_id")) {
                    lp.setInt(1, r.getId());
                    ResultSet lrs = lp.executeQuery();
                    while (lrs.next()) {
                        AllocationRecord.Line line = new AllocationRecord.Line();
                        line.areaId = lrs.getString("area_id");
                        line.areaName = lrs.getString("area_name");
                        line.priority = lrs.getString("priority");
                        line.demand = lrs.getDouble("demand");
                        line.allocated = lrs.getDouble("allocated");
                        line.status = lrs.getString("status");
                        r.addLine(line);
                    }
                }
                history.addOldest(r);
            }
        }
    }

    public static int countAllocations() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM allocation_history")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // for the CSV download: one row per area per snapshot
    public static List<String[]> getAllocationRows() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        String sql = "SELECT h.id, h.run_time, h.day, h.trigger_event, h.user_name, d.area_id, d.area_name, d.priority, "
                + "d.demand, d.allocated, d.status FROM allocation_history h JOIN allocation_details d ON d.record_id = h.id "
                + "ORDER BY h.id, d.area_id";
        try (Connection con = connect(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String user = rs.getString(5);
                rows.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        user == null ? "" : user, rs.getString(6), rs.getString(7), rs.getString(8),
                        String.valueOf(Math.round(rs.getDouble(9))), String.valueOf(Math.round(rs.getDouble(10))), rs.getString(11) });
            }
        }
        return rows;
    }

    // ================= simulated day =================

    public static int getDay() throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement("SELECT value FROM settings WHERE key = 'day'")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        }
    }

    public static void setDay(int day) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO settings (key, value) VALUES ('day', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, String.valueOf(day));
            ps.executeUpdate();
        }
    }

    // ================= users =================

    public static int countUsers() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public static void insertUser(String username, String fullName, String role, String salt, String hash) throws SQLException {
        String sql = "INSERT INTO users (username, full_name, role, salt, password_hash) VALUES (?, ?, ?, ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, role);
            ps.setString(4, salt);
            ps.setString(5, hash);
            ps.executeUpdate();
        }
    }

    // returns {username, fullName, role, salt, hash} or null if there is no such user
    public static String[] findUser(String username) throws SQLException {
        String sql = "SELECT username, full_name, role, salt, password_hash FROM users WHERE username = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[] { rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5) };
            }
            return null;
        }
    }

    public static void updatePassword(String username, String salt, String hash) throws SQLException {
        String sql = "UPDATE users SET salt = ?, password_hash = ? WHERE username = ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, salt);
            ps.setString(2, hash);
            ps.setString(3, username);
            ps.executeUpdate();
        }
    }

    // ================= event history =================

    public static void logEvent(String type, String details, String user) throws SQLException {
        String sql = "INSERT INTO events (event_type, details, user_name) VALUES (?, ?, ?)";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, details);
            ps.setString(3, user);
            ps.executeUpdate();
        }
    }

    // newest first; each row is {time, type, details, user}
    public static List<String[]> getEvents(int limit) throws SQLException {
        List<String[]> events = new ArrayList<>();
        String sql = "SELECT event_time, event_type, details, user_name FROM events ORDER BY id DESC LIMIT ?";
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String user = rs.getString(4);
                events.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3), user == null ? "" : user });
            }
        }
        return events;
    }

    // every event, oldest first (for the CSV download)
    public static List<String[]> getAllEvents() throws SQLException {
        List<String[]> events = new ArrayList<>();
        try (Connection con = connect(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_time, event_type, details, user_name FROM events ORDER BY id")) {
            while (rs.next()) {
                String user = rs.getString(4);
                events.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3), user == null ? "" : user });
            }
        }
        return events;
    }

    public static int countEvents() throws SQLException {
        try (Connection con = connect(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
