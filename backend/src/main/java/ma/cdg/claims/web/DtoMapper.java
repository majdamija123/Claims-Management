package ma.cdg.claims.web;

import java.util.List;
import java.util.Map;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimEvent;
import ma.cdg.claims.domain.ClaimWorkflow;
import ma.cdg.claims.domain.Notification;
import ma.cdg.claims.domain.TaskDecision;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.service.ClaimTypePredictionService;
import ma.cdg.claims.service.SlaService;
import ma.cdg.claims.service.TaskService;
import ma.cdg.claims.web.dto.AuthDtos;
import ma.cdg.claims.web.dto.ClaimDtos;
import ma.cdg.claims.web.dto.NotificationDto;
import ma.cdg.claims.web.dto.TaskDtos;
import org.springframework.stereotype.Component;

/** Turns domain objects into the shapes the Angular client consumes. */
@Component
public class DtoMapper {

    private final SlaService sla;

    public DtoMapper(SlaService sla) {
        this.sla = sla;
    }

    // -------------------------------------------------------------------- user

    public AuthDtos.UserSummary toSummary(AppUser user) {
        WorkflowStep step = WorkflowStep.fromCandidateGroup(
                        user.getRole().getCandidateGroup() == null ? "" : user.getRole().getCandidateGroup())
                .orElse(null);
        return new AuthDtos.UserSummary(
                user.getId(), user.getUsername(), user.getFullName(), user.getEmail(),
                user.getRole().name(), user.getRole().getLabel(), user.getDepartment(),
                user.isActive(), List.copyOf(user.getRole().visibleCandidateGroups()),
                step == null ? null : step.name());
    }

    // ------------------------------------------------------------------- claim

    public ClaimDtos.ClaimSummary toSummary(Claim claim) {
        return new ClaimDtos.ClaimSummary(
                claim.getId(),
                claim.getReference(),
                claim.getCustomerName(),
                claim.getSubject(),
                claim.getType().name(),
                claim.getType().getLabel(),
                claim.getPriority().name(),
                claim.getPriority().getLabel(),
                claim.getStatus().name(),
                claim.getStatus().getLabel(),
                claim.getCurrentStep() == null ? null : claim.getCurrentStep().name(),
                claim.getCurrentStep() == null ? null : claim.getCurrentStep().getLabel(),
                claim.getCurrentAssignee(),
                claim.getChannel().name(),
                claim.getChannel().getLabel(),
                claim.getCreatedAt(),
                claim.getSlaDueAt(),
                claim.isOverdue(),
                sla.healthOf(claim));
    }

    public ClaimDtos.ClaimDetail toDetail(Claim claim,
                                          List<ClaimEvent> history,
                                          List<TaskDtos.TaskSummary> openTasks,
                                          Map<String, Object> processVariables) {
        return new ClaimDtos.ClaimDetail(
                toSummary(claim),
                claim.getCustomerEmail(),
                claim.getCustomerPhone(),
                claim.getCustomerReference(),
                claim.getEntity(),
                claim.getDescription(),
                claim.getResolution(),
                claim.getRejectionReason(),
                claim.getPredictedType() == null ? null : claim.getPredictedType().name(),
                claim.getPredictedType() == null ? null : claim.getPredictedType().getLabel(),
                claim.getPredictionConfidence(),
                claim.getProcessInstanceKey(),
                claim.getProcessVersion(),
                claim.getReturnCount(),
                claim.isSlaBreached(),
                claim.getStepStartedAt(),
                claim.getUpdatedAt(),
                claim.getClosedAt(),
                claim.getCreatedBy(),
                history.stream().map(this::toDto).toList(),
                openTasks,
                processVariables);
    }

    public ClaimDtos.ClaimEventDto toDto(ClaimEvent event) {
        return new ClaimDtos.ClaimEventDto(
                event.getId(),
                event.getType().name(),
                event.getType().getLabel(),
                event.getStep() == null ? null : event.getStep().name(),
                event.getStep() == null ? null : event.getStep().getLabel(),
                event.getDecision() == null ? null : event.getDecision().name(),
                event.getDecision() == null ? null : event.getDecision().getLabel(),
                event.getActor(),
                event.getActorRole(),
                event.getComment(),
                event.getOccurredAt());
    }

    public ClaimDtos.TypeSuggestion toDto(ClaimTypePredictionService.Prediction prediction) {
        return new ClaimDtos.TypeSuggestion(
                prediction.type().name(),
                prediction.type().getLabel(),
                prediction.confidence(),
                prediction.source(),
                prediction.alternatives().stream()
                        .map(alternative -> new ClaimDtos.TypeSuggestionAlternative(
                                alternative.type().name(),
                                alternative.type().getLabel(),
                                alternative.confidence()))
                        .toList());
    }

    // -------------------------------------------------------------------- task

    /**
     * Task keys are 64-bit Camunda keys; they are serialised as strings so that the
     * JavaScript number type cannot silently round them.
     */
    public TaskDtos.TaskSummary toDto(TaskService.TaskItem item) {
        var task = item.task();
        return new TaskDtos.TaskSummary(
                String.valueOf(task.taskKey()),
                task.elementId(),
                task.name(),
                item.step() == null ? null : item.step().name(),
                item.step() == null ? task.name() : item.step().getLabel(),
                task.state(),
                task.assignee(),
                task.candidateGroups(),
                task.creationDate(),
                task.dueDate(),
                task.isOverdue(),
                task.priority(),
                String.valueOf(task.processInstanceKey()),
                item.canAct(),
                item.decisions().stream().map(this::toDto).toList(),
                item.claim() == null ? null : toSummary(item.claim()));
    }

    public TaskDtos.DecisionOption toDto(TaskDecision decision) {
        return new TaskDtos.DecisionOption(decision.name(), decision.getLabel());
    }

    public List<TaskDtos.DecisionOption> decisionsFor(WorkflowStep step) {
        return ClaimWorkflow.decisionsFor(step).stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------ notification

    public NotificationDto toDto(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getLevel().name(),
                notification.getClaimId(),
                notification.getClaimReference(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
