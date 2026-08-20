package ma.cdg.claims.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.camunda.ProcessVariables;
import ma.cdg.claims.camunda.model.ProcessInstanceRef;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimEvent;
import ma.cdg.claims.domain.ClaimEventType;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.ClaimWorkflow;
import ma.cdg.claims.domain.TaskDecision;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.error.BusinessRuleException;
import ma.cdg.claims.error.NotFoundException;
import ma.cdg.claims.repository.ClaimEventRepository;
import ma.cdg.claims.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the complaint record: registration, retrieval, audit trail and the projection of
 * the process state onto the claim row.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ClaimRepository claims;
    private final ClaimEventRepository events;
    private final CamundaGateway camunda;
    private final ReferenceGenerator referenceGenerator;
    private final ClaimTypePredictionService prediction;
    private final SlaService sla;
    private final NotificationService notifications;

    public ClaimService(ClaimRepository claims,
                        ClaimEventRepository events,
                        CamundaGateway camunda,
                        ReferenceGenerator referenceGenerator,
                        ClaimTypePredictionService prediction,
                        SlaService sla,
                        NotificationService notifications) {
        this.claims = claims;
        this.events = events;
        this.camunda = camunda;
        this.referenceGenerator = referenceGenerator;
        this.prediction = prediction;
        this.sla = sla;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------- registration

    /**
     * Registers a complaint and starts the Camunda process instance that will route it.
     *
     * <p>Both happen in one transaction: if the engine refuses the instance the row is
     * rolled back, so the database never holds a complaint that no process is driving.
     */
    @Transactional
    public Claim create(CreateClaimCommand command, AppUser actor) {
        Instant now = Instant.now();

        ClaimTypePredictionService.Prediction suggestion =
                prediction.predict(command.subject(), command.description());

        Claim claim = new Claim();
        claim.setReference(referenceGenerator.next());
        claim.setCustomerName(command.customerName());
        claim.setCustomerEmail(command.customerEmail());
        claim.setCustomerPhone(command.customerPhone());
        claim.setCustomerReference(command.customerReference());
        claim.setChannel(command.channel() == null ? ma.cdg.claims.domain.ClaimChannel.WEB_PORTAL
                : command.channel());
        claim.setEntity(command.entity());
        claim.setSubject(command.subject());
        claim.setDescription(command.description());
        claim.setType(command.type() == null ? suggestion.type() : command.type());
        claim.setPredictedType(suggestion.type());
        claim.setPredictionConfidence(suggestion.confidence());
        claim.setPriority(command.priority() == null ? ClaimPriority.NORMAL : command.priority());
        claim.setStatus(ClaimStatus.IN_QUALIFICATION);
        claim.setCreatedBy(actor == null ? null : actor.getUsername());
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        sla.startStep(claim, WorkflowStep.QUALIFICATION, now);

        Claim saved = claims.saveAndFlush(claim);

        ProcessInstanceRef instance =
                camunda.startProcess(saved.getReference(), startVariables(saved));
        saved.setProcessInstanceKey(instance.processInstanceKey());
        saved.setProcessVersion(instance.version());
        saved = claims.save(saved);

        record(saved, ClaimEventType.CREATED, actor, null, null,
                "Complaint registered through %s".formatted(saved.getChannel().getLabel()));
        record(saved, ClaimEventType.PROCESS_STARTED, actor, WorkflowStep.QUALIFICATION, null,
                "Process instance %d started (%s v%d)".formatted(instance.processInstanceKey(),
                        instance.bpmnProcessId(), instance.version()));

        notifications.notifyStepAvailable(saved, WorkflowStep.QUALIFICATION);
        log.info("Registered complaint {} as process instance {}",
                saved.getReference(), instance.processInstanceKey());
        return saved;
    }

    /** The variables handed to the process at start. */
    private Map<String, Object> startVariables(Claim claim) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(ProcessVariables.CLAIM_REFERENCE, claim.getReference());
        variables.put(ProcessVariables.CLAIM_ID, claim.getId());
        variables.put(ProcessVariables.CUSTOMER_NAME, claim.getCustomerName());
        variables.put(ProcessVariables.CUSTOMER_EMAIL, claim.getCustomerEmail());
        variables.put(ProcessVariables.SUBJECT, claim.getSubject());
        variables.put(ProcessVariables.CLAIM_TYPE, claim.getType().name());
        variables.put(ProcessVariables.CHANNEL, claim.getChannel().name());
        variables.put(ProcessVariables.PRIORITY, claim.getPriority().name());
        variables.put(ProcessVariables.PRIORITY_SCORE, claim.getPriority().getCamundaPriority());
        variables.put(ProcessVariables.ENTITY, claim.getEntity());
        // Deadline of the first step; the following ones are set as each step is completed.
        variables.put(sla.slaVariableName(WorkflowStep.QUALIFICATION),
                claim.getSlaDueAt() == null ? null : claim.getSlaDueAt().toString());
        return variables;
    }

    // ---------------------------------------------------------------- retrieval

    @Transactional(readOnly = true)
    public Page<Claim> search(ClaimSearchCriteria criteria, Pageable pageable) {
        return claims.findAll(ClaimSpecifications.from(criteria), pageable);
    }

    @Transactional(readOnly = true)
    public Claim require(Long id) {
        return claims.findById(id).orElseThrow(() -> NotFoundException.claim(id));
    }

    @Transactional(readOnly = true)
    public Claim requireByReference(String reference) {
        return claims.findByReference(reference)
                .orElseThrow(() -> NotFoundException.claim(reference));
    }

    /** Resolves the claim a task belongs to, by business key first and process key second. */
    @Transactional(readOnly = true)
    public Claim requireForProcess(String businessId, long processInstanceKey) {
        if (businessId != null) {
            var byReference = claims.findByReference(businessId);
            if (byReference.isPresent()) {
                return byReference.get();
            }
        }
        return claims.findByProcessInstanceKey(processInstanceKey)
                .orElseThrow(() -> NotFoundException.claim(
                        "process instance " + processInstanceKey));
    }

    @Transactional(readOnly = true)
    public List<ClaimEvent> history(Long claimId) {
        return events.findByClaimIdOrderByOccurredAtAscIdAsc(claimId);
    }

    /** Live variables of the process instance, for the "workflow" tab of a complaint. */
    @Transactional(readOnly = true)
    public Map<String, Object> processVariables(Claim claim) {
        return claim.getProcessInstanceKey() == null
                ? Map.of()
                : camunda.processVariables(claim.getProcessInstanceKey());
    }

    // ----------------------------------------------------------------- mutation

    /**
     * Projects the result of a completed user task onto the claim.
     *
     * <p>Called by {@link TaskService} once Camunda has accepted the completion. The next
     * step comes from {@link ClaimWorkflow}, the same table the model was drawn from.
     */
    @Transactional
    public Claim applyDecision(Claim claim,
                               WorkflowStep completedStep,
                               TaskDecision decision,
                               ClaimWorkflow.Outcome outcome,
                               AppUser actor,
                               String comment,
                               String resolution,
                               String rejectionReason,
                               Long userTaskKey) {

        Instant now = Instant.now();
        Claim managed = claims.findById(claim.getId()).orElseThrow(() -> NotFoundException.claim(claim.getId()));

        if (resolution != null && !resolution.isBlank()) {
            managed.setResolution(resolution);
        }
        if (rejectionReason != null && !rejectionReason.isBlank()) {
            managed.setRejectionReason(rejectionReason);
        }

        managed.setCurrentAssignee(null);
        managed.setStatus(outcome.status());

        if (outcome.isTerminal()) {
            managed.setCurrentStep(null);
            managed.setSlaDueAt(null);
            managed.setClosedAt(now);
        } else {
            if (decision == TaskDecision.RETURN) {
                managed.setReturnCount(managed.getReturnCount() + 1);
            }
            sla.startStep(managed, outcome.nextStep(), now);
            // A fresh step gets a fresh deadline, so an earlier breach no longer applies.
            managed.setSlaBreached(false);
        }
        managed.setUpdatedAt(now);
        Claim persisted = claims.save(managed);

        ClaimEvent event = record(persisted, ClaimWorkflow.eventTypeFor(decision), actor,
                completedStep, decision, comment);
        event.setUserTaskKey(userTaskKey);
        events.save(event);

        if (outcome.isTerminal()) {
            boolean mailed = decision == TaskDecision.APPROVE
                    ? notifications.notifyCustomerResolved(persisted)
                    : notifications.notifyCustomerRejected(persisted);
            record(persisted, ClaimEventType.NOTIFIED, null, completedStep, null,
                    mailed ? "Customer notified by e-mail"
                           : "Customer notification prepared (e-mail delivery disabled)");
            notifications.notifyClaimClosed(persisted);
        } else {
            notifications.notifyStepAvailable(persisted, outcome.nextStep());
        }
        return persisted;
    }

    /** Records that a user took or released the open task of a complaint. */
    @Transactional
    public void updateAssignee(Long claimId, String assignee, AppUser actor, Long userTaskKey) {
        Claim claim = require(claimId);
        claim.setCurrentAssignee(assignee);
        claim.setUpdatedAt(Instant.now());
        claims.save(claim);

        ClaimEvent event = record(claim,
                assignee == null ? ClaimEventType.TASK_UNASSIGNED : ClaimEventType.TASK_ASSIGNED,
                actor, claim.getCurrentStep(), null,
                assignee == null ? "Task released" : "Task taken by " + assignee);
        event.setUserTaskKey(userTaskKey);
        events.save(event);
    }

    @Transactional
    public Claim addComment(Long claimId, String comment, AppUser actor) {
        Claim claim = require(claimId);
        record(claim, ClaimEventType.COMMENT, actor, claim.getCurrentStep(), null, comment);
        return claim;
    }

    /** Administrative cancellation: terminates the process instance and closes the record. */
    @Transactional
    public Claim cancel(Long claimId, String reason, AppUser actor) {
        Claim claim = require(claimId);
        if (claim.getStatus().isTerminal()) {
            throw new BusinessRuleException(
                    "Complaint %s is already %s".formatted(claim.getReference(),
                            claim.getStatus().getLabel()));
        }
        if (claim.getProcessInstanceKey() != null) {
            camunda.cancelProcessInstance(claim.getProcessInstanceKey());
        }
        Instant now = Instant.now();
        claim.setStatus(ClaimStatus.CANCELLED);
        claim.setCurrentStep(null);
        claim.setCurrentAssignee(null);
        claim.setSlaDueAt(null);
        claim.setClosedAt(now);
        claim.setUpdatedAt(now);
        claim.setRejectionReason(reason);
        Claim persisted = claims.save(claim);

        record(persisted, ClaimEventType.CANCELLED, actor, null, null, reason);
        return persisted;
    }

    /** Appends an entry to the audit trail. */
    @Transactional
    public ClaimEvent record(Claim claim, ClaimEventType type, AppUser actor,
                             WorkflowStep step, TaskDecision decision, String comment) {
        ClaimEvent event = new ClaimEvent();
        event.setClaimId(claim.getId());
        event.setType(type);
        event.setStep(step);
        event.setDecision(decision);
        event.setActor(actor == null ? "system" : actor.getUsername());
        event.setActorRole(actor == null ? null : actor.getRole().name());
        event.setComment(comment);
        event.setOccurredAt(Instant.now());
        return events.save(event);
    }

    /** Categories suggested for a piece of text, used by the registration screen. */
    public ClaimTypePredictionService.Prediction suggestType(String subject, String description) {
        return prediction.predict(subject, description);
    }

    /** Convenience accessor for services that only need the repository view. */
    public ClaimRepository repository() {
        return claims;
    }

    /** Every claim type, for reference-data endpoints. */
    public List<ClaimType> types() {
        return List.of(ClaimType.values());
    }
}
