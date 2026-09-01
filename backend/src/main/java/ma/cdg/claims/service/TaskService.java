package ma.cdg.claims.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.camunda.ProcessVariables;
import ma.cdg.claims.camunda.model.TaskPage;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimWorkflow;
import ma.cdg.claims.domain.TaskDecision;
import ma.cdg.claims.domain.UserRole;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.error.AccessDeniedForTaskException;
import ma.cdg.claims.error.BusinessRuleException;
import ma.cdg.claims.error.NotFoundException;
import ma.cdg.claims.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task inbox: which complaints a given user can work on, and what happens when they
 * take, release or finish one.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** Which slice of the task list the caller wants. */
    public enum InboxScope {
        /** Tasks this user has taken. */
        MINE,
        /** Unassigned tasks offered to this user's unit. */
        AVAILABLE,
        /** Every open task of this user's unit, taken or not. */
        GROUP,
        /** Tasks this user has already finished. */
        COMPLETED
    }

    /** A task together with the complaint behind it and what the caller may do with it. */
    public record TaskItem(WorkflowTask task,
                           Claim claim,
                           WorkflowStep step,
                           List<TaskDecision> decisions,
                           boolean canAct) {
    }

    public record TaskInbox(List<TaskItem> items, long total) {
    }

    private final CamundaGateway camunda;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SlaService sla;

    public TaskService(CamundaGateway camunda,
                       ClaimRepository claims,
                       ClaimService claimService,
                       SlaService sla) {
        this.camunda = camunda;
        this.claims = claims;
        this.claimService = claimService;
        this.sla = sla;
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public TaskInbox inbox(AppUser user, InboxScope scope, WorkflowStep step,
                           int page, int size) {

        Set<String> groups = user.getRole().visibleCandidateGroups();
        if (groups.isEmpty() && scope != InboxScope.MINE && scope != InboxScope.COMPLETED) {
            return new TaskInbox(List.of(), 0L);
        }

        TaskQuery.Builder query = TaskQuery.builder()
                .step(step)
                .page(page * size, size);

        switch (scope) {
            case MINE -> query.assignee(user.getUsername());
            case AVAILABLE -> query.candidateGroups(groups).unassignedOnly(true);
            case GROUP -> query.candidateGroups(groups);
            case COMPLETED -> query.assignee(user.getUsername()).completed(true);
        }

        TaskPage result = camunda.searchTasks(query.build());
        return new TaskInbox(enrich(result.items(), user), result.total());
    }

    /** Open tasks of one process instance, used by the complaint page. */
    @Transactional(readOnly = true)
    public List<TaskItem> forProcessInstance(AppUser user, long processInstanceKey) {
        TaskPage result = camunda.searchTasks(TaskQuery.builder()
                .processInstanceKey(processInstanceKey)
                .page(0, 20)
                .build());
        return enrich(result.items(), user);
    }

    @Transactional(readOnly = true)
    public TaskItem require(AppUser user, long taskKey) {
        WorkflowTask task = camunda.findTask(taskKey)
                .orElseThrow(() -> NotFoundException.task(taskKey));
        return enrich(List.of(task), user).stream()
                .findFirst()
                .orElseThrow(() -> NotFoundException.task(taskKey));
    }

    /** Open task count per step, for the dashboard workload chart. */
    @Transactional(readOnly = true)
    public Map<WorkflowStep, Long> openTaskCountsByStep() {
        Map<WorkflowStep, Long> counts = new LinkedHashMap<>();
        for (WorkflowStep step : WorkflowStep.values()) {
            TaskPage page = camunda.searchTasks(TaskQuery.builder()
                    .step(step)
                    .page(0, 1)
                    .build());
            counts.put(step, page.total());
        }
        return counts;
    }

    private List<TaskItem> enrich(List<WorkflowTask> tasks, AppUser user) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Set<String> references = tasks.stream()
                .map(WorkflowTask::businessId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, Claim> byReference = references.isEmpty()
                ? Map.of()
                : claims.findByReferenceIn(references).stream()
                        .collect(Collectors.toMap(Claim::getReference, Function.identity(),
                                (a, b) -> a));

        List<TaskItem> items = new ArrayList<>(tasks.size());
        for (WorkflowTask task : tasks) {
            WorkflowStep step = task.step().orElse(null);
            Claim claim = task.businessId() == null ? null : byReference.get(task.businessId());
            if (claim == null && task.processInstanceKey() > 0) {
                claim = claims.findByProcessInstanceKey(task.processInstanceKey()).orElse(null);
            }
            List<TaskDecision> decisions = step == null ? List.of() : ClaimWorkflow.decisionsFor(step);
            items.add(new TaskItem(task, claim, step, decisions, mayAct(user, task, step)));
        }
        return items;
    }

    // ------------------------------------------------------------------ actions

    /** Takes an unassigned task, or re-takes one the caller already holds. */
    public TaskItem assignToSelf(AppUser user, long taskKey) {
        WorkflowTask task = openTask(taskKey);
        requireMayAct(user, task);

        if (!task.isUnassigned() && !task.assignee().equals(user.getUsername())) {
            throw new BusinessRuleException("Task %d is already held by %s"
                    .formatted(taskKey, task.assignee()));
        }
        camunda.assignTask(taskKey, user.getUsername());

        Claim claim = claimService.requireForProcess(task.businessId(), task.processInstanceKey());
        claimService.updateAssignee(claim.getId(), user.getUsername(), user, taskKey);
        return require(user, taskKey);
    }

    /** Puts a task back in the unit's queue. */
    public TaskItem release(AppUser user, long taskKey) {
        WorkflowTask task = openTask(taskKey);
        requireMayAct(user, task);

        if (task.isUnassigned()) {
            throw new BusinessRuleException("Task %d is not assigned to anybody".formatted(taskKey));
        }
        if (!task.assignee().equals(user.getUsername()) && !user.getRole().isOversight()) {
            throw new AccessDeniedForTaskException(
                    "Task %d is held by %s".formatted(taskKey, task.assignee()));
        }
        camunda.unassignTask(taskKey);

        Claim claim = claimService.requireForProcess(task.businessId(), task.processInstanceKey());
        claimService.updateAssignee(claim.getId(), null, user, taskKey);
        return require(user, taskKey);
    }

    /**
     * Completes a task: hands the decision to Camunda, which takes the branch drawn in the
     * model, then projects the result onto the complaint.
     */
    public Claim complete(AppUser user, long taskKey, CompleteTaskCommand command) {
        WorkflowTask task = openTask(taskKey);
        requireMayAct(user, task);

        WorkflowStep step = task.step().orElseThrow(() -> new BusinessRuleException(
                "Task %d belongs to element '%s', which is not a step of this process"
                        .formatted(taskKey, task.elementId())));

        if (command.decision() == null || !ClaimWorkflow.isAllowed(step, command.decision())) {
            throw new BusinessRuleException("Decision %s is not available at the %s step. Allowed: %s"
                    .formatted(command.decision(), step.getLabel(), ClaimWorkflow.decisionsFor(step)));
        }
        if (!task.isUnassigned() && !task.assignee().equals(user.getUsername())
                && !user.getRole().isOversight()) {
            throw new AccessDeniedForTaskException(
                    "Task %d is held by %s".formatted(taskKey, task.assignee()));
        }
        if (command.decision() == TaskDecision.REJECT
                && (command.rejectionReason() == null || command.rejectionReason().isBlank())) {
            throw new BusinessRuleException("A rejection reason is required");
        }
        if (command.decision() == TaskDecision.ANSWER
                && (command.resolution() == null || command.resolution().isBlank())) {
            throw new BusinessRuleException("An answer to the customer is required");
        }

        Claim claim = claimService.requireForProcess(task.businessId(), task.processInstanceKey());

        // Qualification is also where the category and the urgency get corrected.
        if (step == WorkflowStep.QUALIFICATION) {
            claim = applyQualificationCorrections(claim, command, user);
        }

        ClaimWorkflow.Outcome outcome = ClaimWorkflow.outcomeOf(step, command.decision());

        if (task.isUnassigned()) {
            // Completing implies taking the task first, so the audit trail names an owner.
            camunda.assignTask(taskKey, user.getUsername());
            claimService.updateAssignee(claim.getId(), user.getUsername(), user, taskKey);
        }

        camunda.completeTask(taskKey, completionVariables(claim, step, command, outcome, user));

        Claim updated = claimService.applyDecision(claim, step, command.decision(), outcome, user,
                command.comment(), command.resolution(), command.rejectionReason(), taskKey);

        log.info("{} completed the {} step of {} with {} -> {}", user.getUsername(),
                step.getLabel(), updated.getReference(), command.decision(), updated.getStatus());
        return updated;
    }

    /**
     * Applies the corrections the qualification agent made to the category and urgency.
     * Each write below is transactional on its own repository, which is enough here: the
     * corrections are recorded before the task is completed and never need to be undone.
     */
    private Claim applyQualificationCorrections(Claim claim, CompleteTaskCommand command, AppUser user) {
        boolean changed = false;
        StringBuilder note = new StringBuilder();

        if (command.type() != null && command.type() != claim.getType()) {
            note.append("category %s -> %s".formatted(claim.getType(), command.type()));
            claim.setType(command.type());
            changed = true;
        }
        if (command.priority() != null && command.priority() != claim.getPriority()) {
            if (!note.isEmpty()) {
                note.append("; ");
            }
            note.append("priority %s -> %s".formatted(claim.getPriority(), command.priority()));
            claim.setPriority(command.priority());
            changed = true;
        }
        if (!changed) {
            return claim;
        }
        claim.setUpdatedAt(Instant.now());
        Claim saved = claims.save(claim);
        claimService.record(saved, ma.cdg.claims.domain.ClaimEventType.COMMENT, user,
                WorkflowStep.QUALIFICATION, null, "Qualification corrected: " + note);
        return saved;
    }

    /** Gateway conditions plus the trace the next agent will want to read. */
    private Map<String, Object> completionVariables(Claim claim,
                                                    WorkflowStep step,
                                                    CompleteTaskCommand command,
                                                    ClaimWorkflow.Outcome outcome,
                                                    AppUser user) {
        Map<String, Object> variables = new HashMap<>(
                ClaimWorkflow.gatewayVariables(step, command.decision()));

        variables.put(ProcessVariables.LAST_ACTOR, user.getUsername());
        variables.put(ProcessVariables.LAST_DECISION, command.decision().name());
        variables.put(ProcessVariables.LAST_COMMENT, command.comment());

        if (command.resolution() != null && !command.resolution().isBlank()) {
            variables.put(ProcessVariables.RESOLUTION, command.resolution());
        }
        if (command.rejectionReason() != null && !command.rejectionReason().isBlank()) {
            variables.put(ProcessVariables.REJECTION_REASON, command.rejectionReason());
        }
        if (step == WorkflowStep.QUALIFICATION) {
            variables.put(ProcessVariables.CLAIM_TYPE, claim.getType().name());
            variables.put(ProcessVariables.PRIORITY, claim.getPriority().name());
            variables.put(ProcessVariables.PRIORITY_SCORE, claim.getPriority().getCamundaPriority());
        }
        // Give the next user task the deadline it should be created with.
        if (!outcome.isTerminal()) {
            WorkflowStep next = outcome.nextStep();
            variables.put(sla.slaVariableName(next),
                    sla.deadlineFor(next, claim.getPriority(), Instant.now()).toString());
        }
        return variables;
    }

    // ------------------------------------------------------------------- guards

    private WorkflowTask openTask(long taskKey) {
        WorkflowTask task = camunda.findTask(taskKey)
                .orElseThrow(() -> NotFoundException.task(taskKey));
        if (task.state() != null && !"CREATED".equals(task.state())) {
            throw new BusinessRuleException(
                    "Task %d is %s and can no longer be changed".formatted(taskKey, task.state()));
        }
        return task;
    }

    private void requireMayAct(AppUser user, WorkflowTask task) {
        WorkflowStep step = task.step().orElse(null);
        if (!mayAct(user, task, step)) {
            throw new AccessDeniedForTaskException(
                    "Your role (%s) cannot act on the %s step".formatted(user.getRole(),
                            step == null ? task.elementId() : step.getLabel()));
        }
    }

    /** Oversight roles may act anywhere; everyone else only on their own unit's step. */
    private boolean mayAct(AppUser user, WorkflowTask task, WorkflowStep step) {
        UserRole role = user.getRole();
        if (role.isOversight()) {
            return true;
        }
        if (step == null) {
            return false;
        }
        if (step.getRole() != role) {
            return false;
        }
        return task.isUnassigned() || user.getUsername().equals(task.assignee());
    }
}
