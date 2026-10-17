// One area's request for water, waiting in the priority queue.
public class WaterRequest {

    private DemandArea area;
    private double litres;
    private int arrivalOrder;   // 1 = first to arrive; used to break ties

    public WaterRequest(DemandArea area, double litres, int arrivalOrder) {
        this.area = area;
        this.litres = litres;
        this.arrivalOrder = arrivalOrder;
    }

    public DemandArea getArea() { return area; }
    public double getLitres() { return litres; }
    public int getArrivalOrder() { return arrivalOrder; }
}
