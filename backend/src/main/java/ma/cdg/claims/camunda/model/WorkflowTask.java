package ma.cdg.claims.camunda.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ma.cdg.claims.domain.WorkflowStep;

/** A Camunda user task, reduced to what this application needs. */
public record WorkflowTask(long taskKey,
                           String elementId,
                           String name,
                           String assignee,
                           List<String> candidateGroups,
                           long processInstanceKey,
                           String businessId,
                           Instant creationDate,
                           Instant dueDate,
                           Integer priority,
                           String state) {

    /** The business step this task belongs to, when the element id is one we know. */
    public Optional<WorkflowStep> step() {
        return WorkflowStep.fromElementId(elementId);
    }

    public boolean isUnassigned() {
        return assignee == null || assignee.isBlank();
    }

    public boolean isOverdue() {
        return dueDate != null && Instant.now().isAfter(dueDate);
    }
}
