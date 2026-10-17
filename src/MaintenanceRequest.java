import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// One repair job for a failed pipeline.
// It is created automatically when a pipeline fails.
public class MaintenanceRequest {

    public enum Status {
        PENDING,       // waiting in the queue
        IN_PROGRESS,   // the repair crew is working on it
        DONE,          // pipeline repaired
        CANCELLED      // pipeline was deleted before it was repaired
    }

    private int id;
    private String pipelineId;
    private String description;
    private Status status;
    private String createdTime;
    private String startedTime;
    private String completedTime;
    private String createdBy;

    public MaintenanceRequest(int id, String pipelineId, String description, Status status,
                              String createdTime, String startedTime, String completedTime, String createdBy) {
        this.id = id;
        this.pipelineId = pipelineId;
        this.description = description;
        this.status = status;
        this.createdTime = createdTime;
        this.startedTime = startedTime;
        this.completedTime = completedTime;
        this.createdBy = createdBy;
    }

    public int getId() { return id; }
    public String getPipelineId() { return pipelineId; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public String getCreatedTime() { return createdTime; }
    public String getStartedTime() { return startedTime; }
    public String getCompletedTime() { return completedTime; }
    public String getCreatedBy() { return createdBy; }

    public void setStatus(Status status) { this.status = status; }
    public void setStartedTime(String time) { this.startedTime = time; }
    public void setCompletedTime(String time) { this.completedTime = time; }

    // the current date and time as text, e.g. 2026-10-15 14:30:05
    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
