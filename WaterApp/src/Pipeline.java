// A pipeline joining two nodes. Water can go both ways.
public class Pipeline {

    private String id;
    private String node1;
    private String node2;
    private boolean working;

    public Pipeline(String id, String node1, String node2) {
        this.id = id;
        this.node1 = node1;
        this.node2 = node2;
        this.working = true;
    }

    public String getId() { return id; }
    public String getNode1() { return node1; }
    public String getNode2() { return node2; }
    public boolean isWorking() { return working; }

    public void markFailed() { working = false; }
    public void markWorking() { working = true; }

    public String getOtherEnd(String node) {
        if (node.equals(node1)) {
            return node2;
        } else {
            return node1;
        }
    }
}
