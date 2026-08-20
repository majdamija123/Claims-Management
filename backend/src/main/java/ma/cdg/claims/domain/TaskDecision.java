package ma.cdg.claims.domain;

/** The outcome an agent can choose when completing a user task. */
public enum TaskDecision {

    /** Qualification: the complaint is valid and relevant, route it to the Front Office. */
    VALIDATE("Qualify and forward"),
    /** Qualification: the complaint is not admissible, reject and notify the customer. */
    REJECT("Reject the complaint"),
    /** FO/MO/BO: this unit can answer, send the proposed response to validation. */
    ANSWER("Answer and send to validation"),
    /** FO/MO: this unit cannot answer, escalate to the next unit. */
    ESCALATE("Escalate to the next unit"),
    /** Validation: approve the answer, notify the customer and close. */
    APPROVE("Approve and close"),
    /** Validation: send the complaint back to qualification for rework. */
    RETURN("Return for rework");

    private final String label;

    TaskDecision(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
