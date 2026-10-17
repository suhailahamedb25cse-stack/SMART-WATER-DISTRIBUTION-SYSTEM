import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

// The web server (uses the HTTP server that comes with Java).
// It does two jobs:
//   1. serves the website files from the "web" folder
//   2. answers the website's requests under /api/ with JSON data
//
// Who may do what:
//   not logged in  -> only the login page
//   VIEWER         -> can look at everything, cannot change anything
//   OPERATOR       -> can look at everything and change data
//
//   POST /api/login                       log in
//   POST /api/logout                      log out
//   GET  /api/me                          who am I?
//   GET  /api/status                      everything the dashboard shows
//   GET  /api/events                      the event history
//   GET  /api/history                     the allocation history (who got how much, after every change)
//   GET  /api/export/events.csv           download the event history
//   GET  /api/export/allocation.csv       download the allocation history
//   POST /api/day/new                     start the next simulated day
//   POST /api/area/reading                type in today's demand reading of an area
//   POST /api/reservoir/reading           type in today's supply reading of a reservoir
//   POST /api/reservoir/add|update|delete
//   POST /api/area/add|update|delete
//   POST /api/pipeline/add|fail|delete
//   POST /api/maintenance/start|complete  the repair queue
//   POST /api/password                    change my own password
//   POST /api/reset                       put the sample data back
public class Server {

    static WaterService service = new WaterService();
    static Path webFolder = Paths.get("web").toAbsolutePath().normalize();

    // these files can be opened without logging in
    static List<String> publicFiles = Arrays.asList("/login.html", "/login.js", "/style.css");

    public static void start(int port) throws IOException, SQLException {
        service.start(); // creates the tables and loads the data from the database

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new ApiHandler());
        server.createContext("/", new FileHandler());
        server.start();

        System.out.println("Database connected: waterdb.sqlite");
        System.out.println("Website running at http://localhost:" + port);
        System.out.println("Log in with  operator / operator123  (can change data)  or  viewer / viewer123  (view only)");
    }

    // ================= website files =================

    static class FileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String urlPath = ex.getRequestURI().getPath();
            if (urlPath.equals("/")) {
                urlPath = "/index.html";
            }

            Path file = webFolder.resolve(urlPath.substring(1)).normalize();
            // never serve anything outside the web folder
            if (!file.startsWith(webFolder) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Page not found".getBytes(StandardCharsets.UTF_8));
                return;
            }

            Auth.Session me = Auth.check(ex);

            // the login page: if you are already logged in, go to the website
            if (urlPath.equals("/login.html") && me != null) {
                redirect(ex, "/");
                return;
            }
            // every other page (except the public ones) needs a login
            if (me == null && !publicFiles.contains(urlPath)) {
                redirect(ex, "/login.html");
                return;
            }

            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, contentType(file.toString()), Files.readAllBytes(file));
        }
    }

    static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ================= the API =================

    static class ApiHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            HashMap<String, String> p = readParams(ex);

            try {
                // ----- logging in (the only thing allowed without a login) -----
                if (path.equals("/api/login")) {
                    if (!method.equals("POST")) {
                        sendJson(ex, 405, errorJson("Use POST to log in."));
                        return;
                    }
                    handleLogin(ex, p);
                    return;
                }

                // ----- everything else needs a logged-in user -----
                Auth.Session me = Auth.check(ex);
                if (me == null) {
                    sendJson(ex, 401, errorJson("Please log in."));
                    return;
                }

                if (path.equals("/api/me")) {
                    sendJson(ex, 200, userJson(me));
                    return;
                }
                if (path.equals("/api/status")) {
                    sendJson(ex, 200, buildStatusJson());
                    return;
                }
                if (path.equals("/api/events")) {
                    sendJson(ex, 200, buildEventsJson());
                    return;
                }
                if (path.equals("/api/history")) {
                    sendJson(ex, 200, buildHistoryJson());
                    return;
                }
                if (path.equals("/api/export/events.csv")) {
                    sendCsv(ex, "events.csv", eventsCsv());
                    return;
                }
                if (path.equals("/api/export/allocation.csv")) {
                    sendCsv(ex, "allocation-history.csv", allocationCsv());
                    return;
                }

                // ----- changing things must be POST -----
                if (!method.equals("POST")) {
                    sendJson(ex, 405, errorJson("Use POST to change data."));
                    return;
                }

                // these two are allowed for every logged-in user
                if (path.equals("/api/logout")) {
                    Auth.logout(ex);
                    ex.getResponseHeaders().add("Set-Cookie", Auth.COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                    Database.logEvent("LOGOUT", me.username + " logged out", me.username);
                    sendJson(ex, 200, "{\"ok\":true}");
                    return;
                }
                if (path.equals("/api/password")) {
                    service.changePassword(me.username, p.get("oldPassword"), p.get("newPassword"));
                    sendJson(ex, 200, "{\"ok\":true}");
                    return;
                }

                // everything below changes data: operators only
                if (!me.role.equals("OPERATOR")) {
                    sendJson(ex, 403, errorJson("Only operators can change data. You are logged in as a viewer."));
                    return;
                }
                String user = me.username;

                switch (path) {
                    case "/api/reservoir/add":
                        service.addReservoir(user, p.get("id"), p.get("name"), p.get("capacity"));
                        break;
                    case "/api/reservoir/update":
                        service.updateReservoir(user, p.get("id"), p.get("name"), p.get("capacity"));
                        break;
                    case "/api/reservoir/delete":
                        service.deleteReservoir(user, p.get("id"));
                        break;
                    case "/api/area/add":
                        service.addArea(user, p.get("id"), p.get("name"), p.get("demand"), p.get("type"),
                                p.get("priority"), p.get("institutionType"), p.get("households"));
                        break;
                    case "/api/area/update":
                        service.updateArea(user, p.get("id"), p.get("name"), p.get("demand"), p.get("type"),
                                p.get("priority"), p.get("institutionType"), p.get("households"));
                        break;
                    case "/api/area/delete":
                        service.deleteArea(user, p.get("id"));
                        break;
                    case "/api/pipeline/add":
                        service.addPipeline(user, p.get("id"), p.get("from"), p.get("to"));
                        break;
                    case "/api/pipeline/fail":
                        service.failPipeline(user, p.get("id"));
                        break;
                    case "/api/pipeline/delete":
                        service.deletePipeline(user, p.get("id"));
                        break;
                    case "/api/maintenance/start":
                        service.startNextJob(user);
                        break;
                    case "/api/maintenance/complete":
                        service.completeCurrentJob(user);
                        break;
                    case "/api/day/new":
                        service.startNewDay(user);
                        break;
                    case "/api/area/reading":
                        service.recordDemandReading(user, p.get("id"), p.get("demand"));
                        break;
                    case "/api/reservoir/reading":
                        service.recordSupplyReading(user, p.get("id"), p.get("supply"));
                        break;
                    case "/api/reset":
                        service.resetSampleData(user);
                        break;
                    default:
                        sendJson(ex, 404, errorJson("Unknown request: " + path));
                        return;
                }
                // after any change, send back the new state of everything
                sendJson(ex, 200, buildStatusJson());

            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, errorJson(e.getMessage()));          // bad input from the user
            } catch (SQLException e) {
                sendJson(ex, 500, errorJson("Database error: " + e.getMessage()));
            } catch (Exception e) {
                sendJson(ex, 500, errorJson("Something went wrong: " + e));
            }
        }
    }

    static void handleLogin(HttpExchange ex, HashMap<String, String> p) throws IOException, SQLException {
        String username = p.get("username");
        try {
            String token = Auth.login(username, p.get("password"));
            Auth.Session me = Auth.getSession(token);
            ex.getResponseHeaders().add("Set-Cookie", Auth.COOKIE_NAME + "=" + token
                    + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=7200");
            Database.logEvent("LOGIN", me.username + " logged in", me.username);
            sendJson(ex, 200, userJson(me));
        } catch (IllegalArgumentException e) {
            String shown = username == null ? "" : username.trim();
            if (shown.length() > 30) {
                shown = shown.substring(0, 30);
            }
            Database.logEvent("LOGIN FAILED", "Failed login attempt for \"" + shown + "\"", "");
            sendJson(ex, 401, errorJson(e.getMessage()));
        }
    }

    // ================= building the JSON by hand =================

    static String userJson(Auth.Session s) {
        return "{\"username\":" + q(s.username) + ",\"fullName\":" + q(s.fullName) + ",\"role\":" + q(s.role) + "}";
    }

    static String buildStatusJson() {
        synchronized (service) {
            WaterNetwork network = service.getNetwork();
            List<String> disconnected = network.findDisconnectedAreas();
            List<String> alerts = new ArrayList<>();

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalAvailable\":").append(network.getTotalAvailable()).append(",");
            sb.append("\"totalAllocated\":").append(network.getTotalAllocated()).append(",");
            sb.append("\"totalRemaining\":").append(network.getTotalRemaining()).append(",");
            sb.append("\"policy\":").append(q(network.getPolicy().getName())).append(",");
            sb.append("\"day\":").append(service.getDay()).append(",");

            // reservoirs
            sb.append("\"reservoirs\":[");
            boolean first = true;
            for (Reservoir r : network.getReservoirs().values()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(q(r.getId()))
                  .append(",\"name\":").append(q(r.getName()))
                  .append(",\"capacity\":").append(r.getCapacity())
                  .append(",\"allocated\":").append(r.getAllocated())
                  .append(",\"remaining\":").append(r.getRemaining()).append("}");

                if (r.getCapacity() == 0) {
                    alerts.add("danger|" + r.getName() + " has no water supply.");
                } else if (r.getRemaining() == 0) {
                    alerts.add("danger|" + r.getName() + " is dry - all its water has been allocated.");
                } else if (r.getRemaining() < r.getCapacity() * 0.1) {
                    alerts.add("warn|" + r.getName() + " is running low.");
                }
            }
            sb.append("],");

            // areas (each one is a CriticalInstitution or a ResidentialArea)
            sb.append("\"areas\":[");
            first = true;
            for (DemandArea a : network.getDemandAreas().values()) {
                String status = network.getAreaStatus(a, disconnected);
                if (status.equals("DISCONNECTED")) {
                    alerts.add("danger|" + a.getName() + " (" + a.getPriority() + ") is disconnected from every reservoir.");
                } else if (status.equals("SHORTAGE")) {
                    alerts.add("danger|" + a.getName() + " (" + a.getPriority() + ") is getting no water.");
                } else if (status.equals("LOW SUPPLY")) {
                    alerts.add("warn|" + a.getName() + " (" + a.getPriority() + ") is short by "
                            + Math.round(a.getUnmetDemand()) + " L.");
                }

                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(q(a.getId()))
                  .append(",\"name\":").append(q(a.getName()))
                  .append(",\"type\":").append(q(a.getAreaType()))
                  .append(",\"details\":").append(q(a.getDetails()))
                  .append(",\"institutionType\":").append(q(a.getInstitutionType()))
                  .append(",\"households\":").append(a.getHouseholds())
                  .append(",\"priority\":").append(q(a.getPriority().name()))
                  .append(",\"demand\":").append(a.getDailyDemand())
                  .append(",\"allocated\":").append(a.getAllocated())
                  .append(",\"unmet\":").append(a.getUnmetDemand())
                  .append(",\"status\":").append(q(status))
                  .append(",\"path\":").append(listJson(network.findSupplyPath(a)))     // the BFS route, e.g. R1, A2, A4, A3
                  .append(",\"sources\":").append(sourcesJson(a)).append("}");
            }
            sb.append("],");

            // pipelines
            sb.append("\"pipelines\":[");
            first = true;
            for (Pipeline p : network.getAllPipelines()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(q(p.getId()))
                  .append(",\"from\":").append(q(p.getNode1()))
                  .append(",\"to\":").append(q(p.getNode2()))
                  .append(",\"status\":").append(q(p.isWorking() ? "WORKING" : "FAILED")).append("}");

                if (!p.isWorking()) {
                    alerts.add("danger|Pipeline " + p.getId() + " (" + p.getNode1() + " - " + p.getNode2() + ") has failed.");
                }
            }
            sb.append("],");

            // repair queue
            MaintenanceQueue queue = service.getQueue();
            sb.append("\"maintenance\":{");
            sb.append("\"current\":").append(queue.getCurrent() == null ? "null" : requestJson(queue.getCurrent())).append(",");
            sb.append("\"pending\":[");
            first = true;
            for (MaintenanceRequest r : queue.getPending()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(requestJson(r));
            }
            sb.append("],\"history\":[");
            first = true;
            for (MaintenanceRequest r : queue.getHistory()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(requestJson(r));
            }
            sb.append("]},");

            // alerts
            sb.append("\"alerts\":[");
            first = true;
            for (String al : alerts) {
                if (!first) sb.append(",");
                first = false;
                String[] parts = al.split("\\|", 2);
                sb.append("{\"level\":").append(q(parts[0])).append(",\"text\":").append(q(parts[1])).append("}");
            }
            sb.append("]}");

            return sb.toString();
        }
    }

    static String requestJson(MaintenanceRequest r) {
        return "{\"id\":" + r.getId()
                + ",\"pipelineId\":" + q(r.getPipelineId())
                + ",\"description\":" + q(r.getDescription())
                + ",\"status\":" + q(r.getStatus().name())
                + ",\"created\":" + q(orEmpty(r.getCreatedTime()))
                + ",\"started\":" + q(orEmpty(r.getStartedTime()))
                + ",\"completed\":" + q(orEmpty(r.getCompletedTime()))
                + ",\"by\":" + q(orEmpty(r.getCreatedBy())) + "}";
    }

    // ["R1","A2","A4"]
    static String listJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(q(items.get(i)));
        }
        return sb.append("]").toString();
    }

    // [{"reservoir":"R1","litres":3000}, ...]  which reservoirs supplied this area
    static String sourcesJson(DemandArea a) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String reservoirId : new java.util.TreeSet<String>(a.getSources().keySet())) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"reservoir\":").append(q(reservoirId)).append(",\"litres\":").append(a.getSources().get(reservoirId)).append("}");
        }
        return sb.append("]").toString();
    }

    static String buildHistoryJson() {
        synchronized (service) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"day\":").append(service.getDay()).append(",\"records\":[");
            boolean first = true;
            for (AllocationRecord r : service.getHistory().getRecords()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(r.getId())
                  .append(",\"time\":").append(q(r.getTime()))
                  .append(",\"day\":").append(r.getDay())
                  .append(",\"trigger\":").append(q(r.getTrigger()))
                  .append(",\"user\":").append(q(orEmpty(r.getUser())))
                  .append(",\"available\":").append(r.getTotalAvailable())
                  .append(",\"allocated\":").append(r.getTotalAllocated())
                  .append(",\"unmet\":").append(r.getTotalUnmet())
                  .append(",\"disconnected\":").append(r.getDisconnectedAreas())
                  .append(",\"lines\":[");
                boolean firstLine = true;
                for (AllocationRecord.Line line : r.getLines()) {
                    if (!firstLine) sb.append(",");
                    firstLine = false;
                    sb.append("{\"id\":").append(q(line.areaId)).append(",\"name\":").append(q(line.areaName))
                      .append(",\"priority\":").append(q(line.priority)).append(",\"demand\":").append(line.demand)
                      .append(",\"allocated\":").append(line.allocated).append(",\"status\":").append(q(line.status)).append("}");
                }
                sb.append("]}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    // ----- CSV downloads (open them in Excel) -----

    static String eventsCsv() throws SQLException {
        StringBuilder sb = new StringBuilder("Time,Event,Details,User\r\n");
        for (String[] e : Database.getAllEvents()) {
            sb.append(csv(e[0])).append(",").append(csv(e[1])).append(",").append(csv(e[2])).append(",").append(csv(e[3])).append("\r\n");
        }
        return sb.toString();
    }

    static String allocationCsv() throws SQLException {
        StringBuilder sb = new StringBuilder("Snapshot,Time,Day,Caused by,User,Area id,Area,Priority,Demand (L),Allocated (L),Status\r\n");
        for (String[] r : Database.getAllocationRows()) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(csv(r[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    // one CSV cell: quotes it, and stops Excel from running text such as =1+1 as a formula
    static String csv(String text) {
        if (text == null) {
            text = "";
        }
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !text.matches("-?[0-9.]+")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static void sendCsv(HttpExchange ex, String fileName, String content) throws IOException {
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        // the first three bytes tell Excel the file is UTF-8
        byte[] body = ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
        send(ex, 200, "text/csv; charset=utf-8", body);
    }

    static String buildEventsJson() throws SQLException {
        List<String[]> events = Database.getEvents(100);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"total\":").append(Database.countEvents()).append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(",");
            String[] e = events.get(i);
            sb.append("{\"time\":").append(q(e[0])).append(",\"type\":").append(q(e[1]))
              .append(",\"details\":").append(q(e[2])).append(",\"user\":").append(q(e[3])).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ================= small helpers =================

    static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    // puts a text inside quotes for JSON, escaping special characters
    static String q(String text) {
        String s = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
        return "\"" + s + "\"";
    }

    static String errorJson(String message) {
        return "{\"error\":" + q(message) + "}";
    }

    // The values sent by the website: they are in the address (?id=R1) or,
    // for POST requests, in the body of the request.
    static HashMap<String, String> readParams(HttpExchange ex) throws IOException {
        HashMap<String, String> map = parseQuery(ex.getRequestURI().getRawQuery());
        if (ex.getRequestMethod().equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            map.putAll(parseQuery(body));
        }
        return map;
    }

    // turns  id=R3&name=New%20Tank  into a map; also decodes %20 and +
    static HashMap<String, String> parseQuery(String query) {
        HashMap<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void redirect(HttpExchange ex, String where) throws IOException {
        ex.getResponseHeaders().set("Location", where);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        OutputStream os = ex.getResponseBody();
        os.write(body);
        os.close();
    }
}
