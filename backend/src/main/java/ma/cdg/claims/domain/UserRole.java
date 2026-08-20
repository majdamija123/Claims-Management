package ma.cdg.claims.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application roles. Each operational role maps to the candidate group used by the
 * corresponding user task in the BPMN model.
 */
public enum UserRole {

    /** Front desk agent qualifying incoming complaints. */
    QUALIFICATION("Qualification agent", "qualification"),
    /** Front Office. */
    FO("Front Office", "front-office"),
    /** Middle Office. */
    MO("Middle Office", "middle-office"),
    /** Back Office. */
    BO("Back Office", "back-office"),
    /** Validates the proposed answer before the customer is notified. */
    VALIDATION("Validation officer", "validation"),
    /** Read-only oversight across every step. */
    SUPERVISOR("Supervisor", null),
    /** Full administrative access. */
    ADMIN("Administrator", null);

    private final String label;
    private final String candidateGroup;

    UserRole(String label, String candidateGroup) {
        this.label = label;
        this.candidateGroup = candidateGroup;
    }

    public String getLabel() {
        return label;
    }

    /** The Camunda candidate group this role belongs to, or {@code null} for oversight roles. */
    public String getCandidateGroup() {
        return candidateGroup;
    }

    /** Oversight roles see every queue; operational roles only see their own. */
    public boolean isOversight() {
        return this == SUPERVISOR || this == ADMIN;
    }

    /** Candidate groups whose task queues this role is allowed to read. */
    public Set<String> visibleCandidateGroups() {
        if (isOversight()) {
            return Arrays.stream(values())
                    .map(UserRole::getCandidateGroup)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return candidateGroup == null ? Set.of() : Set.of(candidateGroup);
    }

    /** Spring Security authority name. */
    public String authority() {
        return "ROLE_" + name();
    }
}
