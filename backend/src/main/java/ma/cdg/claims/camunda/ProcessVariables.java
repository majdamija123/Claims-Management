package ma.cdg.claims.camunda;

import ma.cdg.claims.domain.WorkflowStep;

/** Names of the variables exchanged with the {@code reclamation-client-cdg} process. */
public final class ProcessVariables {

    // Business payload, carried so that Operate / Tasklist show meaningful data.
    public static final String CLAIM_REFERENCE = "claimReference";
    public static final String CLAIM_ID = "claimId";
    public static final String CUSTOMER_NAME = "customerName";
    public static final String CUSTOMER_EMAIL = "customerEmail";
    public static final String SUBJECT = "subject";
    public static final String CLAIM_TYPE = "claimType";
    public static final String CHANNEL = "channel";
    public static final String PRIORITY = "priority";
    public static final String PRIORITY_SCORE = "claimPriorityScore";
    public static final String ENTITY = "entity";

    // Gateway conditions, matching the FEEL expressions in the BPMN file.
    public static final String QUALIFICATION_DECISION = "qualificationDecision";
    public static final String FO_CAN_ANSWER = "foCanAnswer";
    public static final String MO_CAN_ANSWER = "moCanAnswer";
    public static final String VALIDATION_DECISION = "validationDecision";

    // Trace of the last completed step.
    public static final String LAST_ACTOR = "lastActor";
    public static final String LAST_COMMENT = "lastComment";
    public static final String LAST_DECISION = "lastDecision";
    public static final String RESOLUTION = "resolution";
    public static final String REJECTION_REASON = "rejectionReason";

    private ProcessVariables() {
    }

    /** Name of the variable holding the deadline used by the task schedule of a step. */
    public static String slaVariable(WorkflowStep step) {
        return switch (step) {
            case QUALIFICATION -> "slaQualification";
            case FRONT_OFFICE -> "slaFrontOffice";
            case MIDDLE_OFFICE -> "slaMiddleOffice";
            case BACK_OFFICE -> "slaBackOffice";
            case VALIDATION -> "slaValidation";
        };
    }
}
