import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// The repair queue. It is FIFO (first in, first out):
// the pipeline that failed first is repaired first.
// An ArrayDeque is used because we only add at the back
// and take from the front, and both are fast.
public class MaintenanceQueue {

    private ArrayDeque<MaintenanceRequest> pending = new ArrayDeque<>();   // waiting jobs
    private MaintenanceRequest current = null;                              // the one job being repaired
    private ArrayList<MaintenanceRequest> history = new ArrayList<>();     // finished jobs, newest first

    public void clear() {
        pending.clear();
        current = null;
        history.clear();
    }

    // a new job joins the BACK of the queue
    public void addPending(MaintenanceRequest request) {
        pending.addLast(request);
    }

    // used when loading from the database
    public void setCurrent(MaintenanceRequest request) {
        current = request;
    }

    public void addHistory(MaintenanceRequest request) {
        history.add(request);
        if (history.size() > 20) {
            history.remove(history.size() - 1);   // keep only the latest 20
        }
    }

    // takes the job at the FRONT of the queue and starts it
    public MaintenanceRequest startNext() {
        if (current != null) {
            throw new IllegalArgumentException("Finish the current job (pipeline " + current.getPipelineId() + ") first.");
        }
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("There are no jobs waiting in the queue.");
        }
        MaintenanceRequest next = pending.pollFirst();
        next.setStatus(MaintenanceRequest.Status.IN_PROGRESS);
        next.setStartedTime(MaintenanceRequest.now());
        current = next;
        return next;
    }

    // the current job is finished
    public MaintenanceRequest completeCurrent() {
        if (current == null) {
            throw new IllegalArgumentException("No job is in progress.");
        }
        MaintenanceRequest done = current;
        done.setStatus(MaintenanceRequest.Status.DONE);
        done.setCompletedTime(MaintenanceRequest.now());
        current = null;
        history.add(0, done);
        if (history.size() > 20) {
            history.remove(history.size() - 1);
        }
        return done;
    }

    // the pipeline was deleted, so its job is not needed any more
    public MaintenanceRequest cancelFor(String pipelineId) {
        MaintenanceRequest found = null;

        Iterator<MaintenanceRequest> it = pending.iterator();
        while (it.hasNext()) {
            MaintenanceRequest r = it.next();
            if (r.getPipelineId().equals(pipelineId)) {
                it.remove();
                found = r;
                break;
            }
        }
        if (found == null && current != null && current.getPipelineId().equals(pipelineId)) {
            found = current;
            current = null;
        }
        if (found != null) {
            found.setStatus(MaintenanceRequest.Status.CANCELLED);
            found.setCompletedTime(MaintenanceRequest.now());
            history.add(0, found);
            if (history.size() > 20) {
                history.remove(history.size() - 1);
            }
        }
        return found;
    }

    public boolean hasOpenRequestFor(String pipelineId) {
        if (current != null && current.getPipelineId().equals(pipelineId)) {
            return true;
        }
        for (MaintenanceRequest r : pending) {
            if (r.getPipelineId().equals(pipelineId)) {
                return true;
            }
        }
        return false;
    }

    public MaintenanceRequest getCurrent() { return current; }
    public int pendingCount() { return pending.size(); }
    public List<MaintenanceRequest> getPending() { return new ArrayList<>(pending); }
    public List<MaintenanceRequest> getHistory() { return history; }
}
