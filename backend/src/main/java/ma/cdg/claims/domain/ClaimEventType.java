package ma.cdg.claims.domain;

/** Entries of the per-claim audit trail shown as a timeline in the UI. */
public enum ClaimEventType {

    CREATED("Complaint registered"),
    PROCESS_STARTED("Process instance started"),
    TASK_CREATED("Task available"),
    TASK_ASSIGNED("Task assigned"),
    TASK_UNASSIGNED("Task released"),
    TASK_COMPLETED("Task completed"),
    ESCALATED("Escalated to the next unit"),
    RETURNED("Returned for rework"),
    REJECTED("Complaint rejected"),
    RESOLVED("Complaint resolved"),
    CANCELLED("Complaint cancelled"),
    SLA_BREACHED("SLA deadline missed"),
    NOTIFIED("Customer notified"),
    COMMENT("Comment added");

    private final String label;

    ClaimEventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
