import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class Server {

    static WaterNetwork network = new WaterNetwork();

    public static void main(String[] args) throws IOException {
        network.loadDemoData();

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                byte[] page = Files.readAllBytes(Paths.get("WaterApp","web", "index.html"));
                send(ex, 200, "text/html; charset=utf-8", page);
            }
        });

        server.createContext("/api/status", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                sendJson(ex, buildStatusJson());
            }
        });

        server.createContext("/api/pipeline", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                HashMap<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                synchronized (network) {
                    Pipeline p = network.findPipeline(q.get("id"));
                    if (p != null) {
                        if ("FAILED".equals(q.get("status"))) {
                            p.markFailed();
                        } else {
                            p.markWorking();
                        }
                        network.recalculate(); // update distribution
                    }
                }
                sendJson(ex, buildStatusJson());
            }
        });

        server.createContext("/api/reservoir", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                HashMap<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                synchronized (network) {
                    Reservoir r = network.getReservoirs().get(q.get("id"));
                    if (r != null) {
                        try {
                            double capacity = Double.parseDouble(q.get("capacity"));
                            if (capacity >= 0) {
                                r.setCapacity(capacity);
                                network.recalculate();
                            }
                        } catch (Exception e) {
                           
                        }
                    }
                }
                sendJson(ex, buildStatusJson());
            }
        });

        server.createContext("/api/reset", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                synchronized (network) {
                    network.loadDemoData();
                }
                sendJson(ex, buildStatusJson());
            }
        });

        server.start();
        System.out.println("Smart Water Distribution running at http://localhost:" + port);
    }



    static String buildStatusJson() {
        synchronized (network) {
            List<String> disconnected = network.findDisconnectedAreas();
            List<String> alerts = new ArrayList<>();

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalAvailable\":").append(network.getTotalAvailable()).append(",");
            sb.append("\"totalAllocated\":").append(network.getTotalAllocated()).append(",");
            sb.append("\"totalRemaining\":").append(network.getTotalRemaining()).append(",");

            // reservoirs
            sb.append("\"reservoirs\":[");
            boolean first = true;
            for (Reservoir r : network.getReservoirs().values()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":\"").append(r.getId()).append("\",")
                  .append("\"name\":\"").append(r.getName()).append("\",")
                  .append("\"capacity\":").append(r.getCapacity()).append(",")
                  .append("\"allocated\":").append(r.getAllocated()).append(",")
                  .append("\"remaining\":").append(r.getRemaining()).append("}");

                if (r.getCapacity() > 0 && r.getRemaining() == 0) {
                    alerts.add("danger|" + r.getName() + " is dry - all its water has been allocated.");
                } else if (r.getCapacity() == 0) {
                    alerts.add("danger|" + r.getName() + " has no water supply.");
                } else if (r.getRemaining() < r.getCapacity() * 0.1) {
                    alerts.add("warn|" + r.getName() + " is running low.");
                }
            }
            sb.append("],");

          
            sb.append("\"areas\":[");
            first = true;
            for (DemandArea a : network.getDemandAreas().values()) {
                String status;
                if (disconnected.contains(a.getId())) {
                    status = "DISCONNECTED";
                    alerts.add("danger|" + a.getName() + " (" + a.getPriority() + ") is disconnected from every reservoir.");
                } else if (a.getUnmetDemand() == 0) {
                    status = "SUPPLIED";
                } else if (a.getAllocated() == 0) {
                    status = "SHORTAGE";
                    alerts.add("danger|" + a.getName() + " (" + a.getPriority() + ") is getting no water.");
                } else {
                    status = "LOW SUPPLY";
                    alerts.add("warn|" + a.getName() + " (" + a.getPriority() + ") is short by "
                            + Math.round(a.getUnmetDemand()) + " L.");
                }

                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":\"").append(a.getId()).append("\",")
                  .append("\"name\":\"").append(a.getName()).append("\",")
                  .append("\"priority\":\"").append(a.getPriority()).append("\",")
                  .append("\"demand\":").append(a.getDailyDemand()).append(",")
                  .append("\"allocated\":").append(a.getAllocated()).append(",")
                  .append("\"unmet\":").append(a.getUnmetDemand()).append(",")
                  .append("\"status\":\"").append(status).append("\"}");
            }
            sb.append("],");

            sb.append("\"pipelines\":[");
            first = true;
            for (Pipeline p : network.getAllPipelines()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":\"").append(p.getId()).append("\",")
                  .append("\"from\":\"").append(p.getNode1()).append("\",")
                  .append("\"to\":\"").append(p.getNode2()).append("\",")
                  .append("\"status\":\"").append(p.isWorking() ? "WORKING" : "FAILED").append("\"}");

                if (!p.isWorking()) {
                    alerts.add("danger|Pipeline " + p.getId() + " (" + p.getNode1() + " - " + p.getNode2() + ") has failed.");
                }
            }
            sb.append("],");

           
            sb.append("\"alerts\":[");
            first = true;
            for (String al : alerts) {
                if (!first) sb.append(",");
                first = false;
                String[] parts = al.split("\\|", 2);
                sb.append("{\"level\":\"").append(parts[0]).append("\",")
                  .append("\"text\":\"").append(parts[1]).append("\"}");
            }
            sb.append("]}");

            return sb.toString();
        }
    }

    /

    static HashMap<String, String> parseQuery(String query) {
        HashMap<String, String> map = new HashMap<>();
        if (query == null) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    static void sendJson(HttpExchange ex, String json) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        OutputStream os = ex.getResponseBody();
        os.write(body);
        os.close();
    }
}
