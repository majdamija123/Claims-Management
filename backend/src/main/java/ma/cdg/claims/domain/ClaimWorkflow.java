package ma.cdg.claims.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transition table of {@code reclamation-client-cdg.bpmn}, expressed once in Java.
 *
 * <p>Camunda remains the engine that actually routes an instance; this table lets the
 * application (a) know which decisions an agent may take at a given step, (b) update its
 * own projection of the claim without waiting for a round trip, and (c) drive the
 * in-memory simulator used by the {@code demo} profile. Because all three share this
 * single definition they can never drift apart.
 */
public final class ClaimWorkflow {

    /** Where a decision leads: either the next human step, or a terminal status. */
    public record Outcome(WorkflowStep nextStep, ClaimStatus status) {

        public boolean isTerminal() {
            return nextStep == null;
        }
    }

    private static final Map<WorkflowStep, Map<TaskDecision, Outcome>> TRANSITIONS = build();

    private ClaimWorkflow() {
    }

    private static Map<WorkflowStep, Map<TaskDecision, Outcome>> build() {
        Map<WorkflowStep, Map<TaskDecision, Outcome>> map = new LinkedHashMap<>();

        // Qualification -> "Reclamation valide / pertinente ?"
        map.put(WorkflowStep.QUALIFICATION, Map.of(
                TaskDecision.VALIDATE, new Outcome(WorkflowStep.FRONT_OFFICE, ClaimStatus.IN_FRONT_OFFICE),
                TaskDecision.REJECT, new Outcome(null, ClaimStatus.REJECTED)));

        // Traitement FO -> "FO peut repondre ?"
        map.put(WorkflowStep.FRONT_OFFICE, Map.of(
                TaskDecision.ANSWER, new Outcome(WorkflowStep.VALIDATION, ClaimStatus.IN_VALIDATION),
                TaskDecision.ESCALATE, new Outcome(WorkflowStep.MIDDLE_OFFICE, ClaimStatus.IN_MIDDLE_OFFICE)));

        // Traitement MO -> "MO peut repondre ?"
        map.put(WorkflowStep.MIDDLE_OFFICE, Map.of(
                TaskDecision.ANSWER, new Outcome(WorkflowStep.VALIDATION, ClaimStatus.IN_VALIDATION),
                TaskDecision.ESCALATE, new Outcome(WorkflowStep.BACK_OFFICE, ClaimStatus.IN_BACK_OFFICE)));

        // Traitement BO is the last resort: it always goes to validation.
        map.put(WorkflowStep.BACK_OFFICE, Map.of(
                TaskDecision.ANSWER, new Outcome(WorkflowStep.VALIDATION, ClaimStatus.IN_VALIDATION)));

        // Validation -> "Validee ?"
        map.put(WorkflowStep.VALIDATION, Map.of(
                TaskDecision.APPROVE, new Outcome(null, ClaimStatus.RESOLVED),
                TaskDecision.RETURN, new Outcome(WorkflowStep.QUALIFICATION, ClaimStatus.IN_QUALIFICATION)));

        return map;
    }

    /** Decisions offered to an agent working on {@code step}, in display order. */
    public static List<TaskDecision> decisionsFor(WorkflowStep step) {
        return switch (step) {
            case QUALIFICATION -> List.of(TaskDecision.VALIDATE, TaskDecision.REJECT);
            case FRONT_OFFICE, MIDDLE_OFFICE -> List.of(TaskDecision.ANSWER, TaskDecision.ESCALATE);
            case BACK_OFFICE -> List.of(TaskDecision.ANSWER);
            case VALIDATION -> List.of(TaskDecision.APPROVE, TaskDecision.RETURN);
        };
    }

    public static boolean isAllowed(WorkflowStep step, TaskDecision decision) {
        return TRANSITIONS.getOrDefault(step, Map.of()).containsKey(decision);
    }

    /**
     * Resolves where the process goes next.
     *
     * @throws IllegalArgumentException if the decision is not offered at this step
     */
    public static Outcome outcomeOf(WorkflowStep step, TaskDecision decision) {
        Outcome outcome = TRANSITIONS.getOrDefault(step, Map.of()).get(decision);
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Decision " + decision + " is not allowed at step " + step
                            + ". Allowed: " + decisionsFor(step));
        }
        return outcome;
    }

    /**
     * The process variables Camunda needs in order to take the same branch. These names
     * are the ones used by the gateway conditions in the BPMN file.
     */
    public static Map<String, Object> gatewayVariables(WorkflowStep step, TaskDecision decision) {
        return switch (step) {
            case QUALIFICATION -> Map.of("qualificationDecision",
                    decision == TaskDecision.VALIDATE ? "VALID" : "REJECTED");
            case FRONT_OFFICE -> Map.of("foCanAnswer", decision == TaskDecision.ANSWER);
            case MIDDLE_OFFICE -> Map.of("moCanAnswer", decision == TaskDecision.ANSWER);
            case BACK_OFFICE -> Map.of();
            case VALIDATION -> Map.of("validationDecision",
                    decision == TaskDecision.APPROVE ? "APPROVED" : "RETURNED");
        };
    }

    /** The audit-trail entry type that best describes a decision. */
    public static ClaimEventType eventTypeFor(TaskDecision decision) {
        return switch (decision) {
            case REJECT -> ClaimEventType.REJECTED;
            case ESCALATE -> ClaimEventType.ESCALATED;
            case RETURN -> ClaimEventType.RETURNED;
            case APPROVE -> ClaimEventType.RESOLVED;
            case VALIDATE, ANSWER -> ClaimEventType.TASK_COMPLETED;
        };
    }
}
