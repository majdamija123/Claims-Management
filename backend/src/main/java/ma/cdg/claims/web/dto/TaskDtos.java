package ma.cdg.claims.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.TaskDecision;

/** Payloads of the task inbox endpoints. */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** One option offered by the completion form. */
    public record DecisionOption(String value, String label) {
    }

    /** A task as shown in the inbox, already joined with its complaint. */
    public record TaskSummary(String taskKey,
                              String elementId,
                              String name,
                              String step,
                              String stepLabel,
                              String state,
                              String assignee,
                              List<String> candidateGroups,
                              Instant createdAt,
                              Instant dueDate,
                              boolean overdue,
                              Integer priority,
                              String processInstanceKey,
                              boolean canAct,
                              List<DecisionOption> decisions,
                              ClaimDtos.ClaimSummary claim) {
    }

    /** What the completion form submits. */
    public record CompleteTaskRequest(@NotNull TaskDecision decision,
                                      @Size(max = 2000) String comment,
                                      @Size(max = 4000) String resolution,
                                      @Size(max = 1000) String rejectionReason,
                                      ClaimType type,
                                      ClaimPriority priority) {
    }
}
