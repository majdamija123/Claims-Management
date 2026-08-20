package ma.cdg.claims.domain;

/** Lifecycle of a complaint, mirroring where the process instance currently stands. */
public enum ClaimStatus {

    IN_QUALIFICATION("Awaiting qualification", false),
    IN_FRONT_OFFICE("Handled by Front Office", false),
    IN_MIDDLE_OFFICE("Handled by Middle Office", false),
    IN_BACK_OFFICE("Handled by Back Office", false),
    IN_VALIDATION("Awaiting validation", false),
    REJECTED("Rejected and customer notified", true),
    RESOLVED("Resolved and closed", true),
    CANCELLED("Cancelled", true);

    private final String label;
    private final boolean terminal;

    ClaimStatus(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String getLabel() {
        return label;
    }

    /** True once the process instance has ended and no further work is expected. */
    public boolean isTerminal() {
        return terminal;
    }
}
