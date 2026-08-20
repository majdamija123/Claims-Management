package ma.cdg.claims.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimEventType;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the local projection honest.
 *
 * <p>The engine is the source of truth, and the application updates its own row right
 * after every completion. A crash between the two, or somebody driving the instance from
 * Operate or Tasklist, would leave the two out of step — so every minute the open
 * complaints are compared against the engine's open tasks and corrected. It also flags
 * the complaints whose deadline has just passed.
 */
@Service
public class WorkflowSyncService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSyncService.class);

    private final CamundaGateway camunda;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SlaService sla;
    private final NotificationService notifications;

    public WorkflowSyncService(CamundaGateway camunda,
                               ClaimRepository claims,
                               ClaimService claimService,
                               SlaService sla,
                               NotificationService notifications) {
        this.camunda = camunda;
        this.claims = claims;
        this.claimService = claimService;
        this.sla = sla;
        this.notifications = notifications;
    }

    /** Reconciles claim rows with the engine. Returns the number of rows corrected. */
    @Scheduled(fixedDelayString = "${cdg.workflow.sync-interval:60s}", initialDelay = 20_000L)
    @Transactional
    public int synchronise() {
        List<Claim> open = claims.findByStatusIn(
                List.of(ClaimStatus.IN_QUALIFICATION, ClaimStatus.IN_FRONT_OFFICE,
                        ClaimStatus.IN_MIDDLE_OFFICE, ClaimStatus.IN_BACK_OFFICE,
                        ClaimStatus.IN_VALIDATION));
        if (open.isEmpty()) {
            return 0;
        }

        Map<Long, WorkflowTask> openTasksByInstance;
        try {
            openTasksByInstance = camunda.searchTasks(TaskQuery.builder().page(0, 1000).build())
                    .items().stream()
                    .collect(Collectors.toMap(WorkflowTask::processInstanceKey,
                            Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("Workflow synchronisation skipped: {}", e.getMessage());
            return 0;
        }

        int corrected = 0;
        for (Claim claim : open) {
            if (claim.getProcessInstanceKey() == null) {
                continue;
            }
            WorkflowTask task = openTasksByInstance.get(claim.getProcessInstanceKey());
            corrected += task == null ? reconcileWithoutTask(claim) : reconcileWithTask(claim, task);
        }
        if (corrected > 0) {
            log.info("Workflow synchronisation corrected {} complaint(s)", corrected);
        }
        return corrected;
    }

    /** The engine shows an open task: make sure the claim points at the same step. */
    private int reconcileWithTask(Claim claim, WorkflowTask task) {
        WorkflowStep step = task.step().orElse(null);
        if (step == null) {
            return 0;
        }
        boolean changed = false;

        if (claim.getCurrentStep() != step) {
            log.info("Complaint {} was at {} but the engine is at {}; realigning",
                    claim.getReference(), claim.getCurrentStep(), step);
            sla.startStep(claim, step, task.creationDate() == null ? Instant.now() : task.creationDate());
            claim.setStatus(step.getStatus());
            claim.setSlaBreached(false);
            changed = true;
        }
        String assignee = task.isUnassigned() ? null : task.assignee();
        if (!java.util.Objects.equals(claim.getCurrentAssignee(), assignee)) {
            claim.setCurrentAssignee(assignee);
            changed = true;
        }
        if (changed) {
            claim.setUpdatedAt(Instant.now());
            claims.save(claim);
        }
        return changed ? 1 : 0;
    }

    /** No open task: either the instance finished, or it was cancelled behind our back. */
    private int reconcileWithoutTask(Claim claim) {
        Optional<String> state = camunda.processInstanceState(claim.getProcessInstanceKey());
        if (state.isEmpty() || "ACTIVE".equals(state.get())) {
            // Still running (a task may simply not have been indexed yet): leave it alone.
            return 0;
        }

        Instant now = Instant.now();
        ClaimStatus resolved = "TERMINATED".equals(state.get())
                ? ClaimStatus.CANCELLED
                : resolveClosingStatus(claim);

        log.info("Process instance {} of complaint {} is {}; closing the record as {}",
                claim.getProcessInstanceKey(), claim.getReference(), state.get(), resolved);

        claim.setStatus(resolved);
        claim.setCurrentStep(null);
        claim.setCurrentAssignee(null);
        claim.setSlaDueAt(null);
        claim.setClosedAt(now);
        claim.setUpdatedAt(now);
        claims.save(claim);

        claimService.record(claim, ClaimEventType.PROCESS_STARTED, null, null, null,
                "Reconciled with the engine: process instance is " + state.get());
        return 1;
    }

    /**
     * A completed instance ended either at "Rejetee &amp; notifiee" or at "Notifier client
     * &amp; Cloturer"; the last step it visited tells which.
     */
    private ClaimStatus resolveClosingStatus(Claim claim) {
        if (claim.getRejectionReason() != null && !claim.getRejectionReason().isBlank()) {
            return ClaimStatus.REJECTED;
        }
        return claim.getCurrentStep() == WorkflowStep.QUALIFICATION
                ? ClaimStatus.REJECTED
                : ClaimStatus.RESOLVED;
    }

    /** Flags complaints whose current step has just run out of time and warns their unit. */
    @Scheduled(fixedDelayString = "${cdg.sla.check-interval:120s}", initialDelay = 30_000L)
    @Transactional
    public int flagBreachedDeadlines() {
        List<Claim> breached = claims.findNewlyBreached(
                Instant.now(), ClaimSpecifications.terminalStatuses());

        for (Claim claim : breached) {
            claim.setSlaBreached(true);
            claim.setUpdatedAt(Instant.now());
            claims.save(claim);

            claimService.record(claim, ClaimEventType.SLA_BREACHED, null, claim.getCurrentStep(),
                    null, "Deadline of the %s step passed on %s".formatted(
                            claim.getCurrentStep() == null ? "current" : claim.getCurrentStep().getLabel(),
                            claim.getSlaDueAt()));
            notifications.notifySlaBreached(claim);
        }
        if (!breached.isEmpty()) {
            log.info("{} complaint(s) crossed their deadline", breached.size());
        }
        return breached.size();
    }
}
