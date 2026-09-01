package ma.cdg.claims.domain;

/**
 * Business priority. The factor shortens or extends the SLA configured for each step,
 * and the score is passed to Camunda as the native user-task priority (0-100).
 */
public enum ClaimPriority {

    LOW("Low", 1.5d, 25),
    NORMAL("Normal", 1.0d, 50),
    HIGH("High", 0.6d, 75),
    URGENT("Urgent", 0.35d, 100);

    private final String label;
    private final double slaFactor;
    private final int camundaPriority;

    ClaimPriority(String label, double slaFactor, int camundaPriority) {
        this.label = label;
        this.slaFactor = slaFactor;
        this.camundaPriority = camundaPriority;
    }

    public String getLabel() {
        return label;
    }

    public double getSlaFactor() {
        return slaFactor;
    }

    public int getCamundaPriority() {
        return camundaPriority;
    }
}
