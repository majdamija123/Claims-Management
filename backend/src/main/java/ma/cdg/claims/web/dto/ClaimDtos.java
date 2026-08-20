package ma.cdg.claims.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimType;

/** Payloads of the complaint endpoints. */
public final class ClaimDtos {

    private ClaimDtos() {
    }

    /** What the registration form submits. */
    public record CreateClaimRequest(
            @NotBlank @Size(max = 150) String customerName,
            @Email @Size(max = 150) String customerEmail,
            @Size(max = 40) String customerPhone,
            @Size(max = 60) String customerReference,
            ClaimChannel channel,
            @Size(max = 120) String entity,
            @NotBlank @Size(max = 250) String subject,
            @NotBlank @Size(max = 4000) String description,
            ClaimType type,
            ClaimPriority priority) {
    }

    /** Row of the complaint list. */
    public record ClaimSummary(Long id,
                               String reference,
                               String customerName,
                               String subject,
                               String type,
                               String typeLabel,
                               String priority,
                               String priorityLabel,
                               String status,
                               String statusLabel,
                               String currentStep,
                               String currentStepLabel,
                               String currentAssignee,
                               String channel,
                               String channelLabel,
                               Instant createdAt,
                               Instant slaDueAt,
                               boolean overdue,
                               String slaHealth) {
    }

    /** Everything shown on the complaint page. */
    public record ClaimDetail(ClaimSummary summary,
                              String customerEmail,
                              String customerPhone,
                              String customerReference,
                              String entity,
                              String description,
                              String resolution,
                              String rejectionReason,
                              String predictedType,
                              String predictedTypeLabel,
                              Double predictionConfidence,
                              Long processInstanceKey,
                              Integer processVersion,
                              int returnCount,
                              boolean slaBreached,
                              Instant stepStartedAt,
                              Instant updatedAt,
                              Instant closedAt,
                              String createdBy,
                              List<ClaimEventDto> history,
                              List<TaskDtos.TaskSummary> openTasks,
                              Map<String, Object> processVariables) {
    }

    /** One line of the timeline. */
    public record ClaimEventDto(Long id,
                                String type,
                                String typeLabel,
                                String step,
                                String stepLabel,
                                String decision,
                                String decisionLabel,
                                String actor,
                                String actorRole,
                                String comment,
                                Instant occurredAt) {
    }

    /** Suggestion returned by the classification model. */
    public record TypeSuggestion(String type,
                                 String typeLabel,
                                 double confidence,
                                 String source,
                                 List<TypeSuggestionAlternative> alternatives) {
    }

    public record TypeSuggestionAlternative(String type, String typeLabel, double confidence) {
    }

    public record SuggestTypeRequest(@Size(max = 250) String subject,
                                     @Size(max = 4000) String description) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String comment) {
    }

    public record CancelRequest(@NotBlank @Size(max = 1000) String reason) {
    }
}
