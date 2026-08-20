package ma.cdg.claims.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five human steps of the complaint process, bound to the BPMN element ids of
 * {@code reclamation-client-cdg.bpmn}. Changing an element id in the model means
 * changing it here — this enum is the contract between the app and the process.
 */
public enum WorkflowStep {

    QUALIFICATION("Task_Qualification", "Qualification",
            UserRole.QUALIFICATION, ClaimStatus.IN_QUALIFICATION, 1),
    FRONT_OFFICE("Task_TraitementFO", "Front Office handling",
            UserRole.FO, ClaimStatus.IN_FRONT_OFFICE, 2),
    MIDDLE_OFFICE("Task_TraitementMO", "Middle Office handling",
            UserRole.MO, ClaimStatus.IN_MIDDLE_OFFICE, 3),
    BACK_OFFICE("Task_TraitementBO", "Back Office handling",
            UserRole.BO, ClaimStatus.IN_BACK_OFFICE, 4),
    VALIDATION("Task_Validation", "Validation",
            UserRole.VALIDATION, ClaimStatus.IN_VALIDATION, 5);

    private final String elementId;
    private final String label;
    private final UserRole role;
    private final ClaimStatus status;
    private final int order;

    WorkflowStep(String elementId, String label, UserRole role, ClaimStatus status, int order) {
        this.elementId = elementId;
        this.label = label;
        this.role = role;
        this.status = status;
        this.order = order;
    }

    public String getElementId() {
        return elementId;
    }

    public String getLabel() {
        return label;
    }

    public UserRole getRole() {
        return role;
    }

    public String getCandidateGroup() {
        return role.getCandidateGroup();
    }

    /** The claim status while this step is the active one. */
    public ClaimStatus getStatus() {
        return status;
    }

    public int getOrder() {
        return order;
    }

    public static Optional<WorkflowStep> fromElementId(String elementId) {
        return Arrays.stream(values())
                .filter(s -> s.elementId.equals(elementId))
                .findFirst();
    }

    public static Optional<WorkflowStep> fromCandidateGroup(String candidateGroup) {
        return Arrays.stream(values())
                .filter(s -> s.getCandidateGroup().equals(candidateGroup))
                .findFirst();
    }
}
