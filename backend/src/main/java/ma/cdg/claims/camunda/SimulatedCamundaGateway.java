package ma.cdg.claims.camunda;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import ma.cdg.claims.camunda.model.DeploymentResult;
import ma.cdg.claims.camunda.model.ProcessInstanceRef;
import ma.cdg.claims.camunda.model.TaskPage;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;
import ma.cdg.claims.domain.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-memory engine that replays {@code reclamation-client-cdg.bpmn}.
 *
 * <p>It exists so the product can be run, demonstrated and tested end to end before the
 * Camunda SaaS credentials are available — and so the automated tests do not need a
 * cluster. It implements exactly the routing drawn in the model: the same gateway
 * conditions, the same candidate groups, the same loop from validation back to
 * qualification. Swapping it for {@link CamundaClientGateway} changes nothing above this
 * interface.
 *
 * <p>State lives in memory only. On restart it is rebuilt from the claims table by
 * {@code SimulatedEngineRestorer}, so a demonstration survives a restart.
 */
public class SimulatedCamundaGateway implements CamundaGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCamundaGateway.class);

    /** Zeebe-like keys, so the values look realistic in the UI. */
    private final AtomicLong keySequence = new AtomicLong(2_251_799_813_685_248L);

    private final Map<Long, Instance> instances = new ConcurrentHashMap<>();
    private final Map<Long, MutableTask> tasks = new ConcurrentHashMap<>();
    private final String processId;

    public SimulatedCamundaGateway(String processId) {
        this.processId = processId;
    }

    /** A running or finished process instance. */
    private static final class Instance {
        final long key;
        final String businessId;
        final Map<String, Object> variables = new ConcurrentHashMap<>();
        volatile String state = "ACTIVE";

        Instance(long key, String businessId) {
            this.key = key;
            this.businessId = businessId;
        }
    }

    /** A user task; mutable because assignment and completion change it in place. */
    private static final class MutableTask {
        final long key;
        final long processInstanceKey;
        final String businessId;
        final WorkflowStep step;
        final Instant creationDate;
        final Instant dueDate;
        final Integer priority;
        volatile String assignee;
        volatile String state = "CREATED";
        volatile Instant completionDate;

        MutableTask(long key, long processInstanceKey, String businessId, WorkflowStep step,
                    Instant dueDate, Integer priority) {
            this.key = key;
            this.processInstanceKey = processInstanceKey;
            this.businessId = businessId;
            this.step = step;
            this.creationDate = Instant.now();
            this.dueDate = dueDate;
            this.priority = priority;
        }

        WorkflowTask toRecord() {
            return new WorkflowTask(key, step.getElementId(), step.getLabel(), assignee,
                    List.of(step.getCandidateGroup()), processInstanceKey, businessId,
                    creationDate, dueDate, priority, state);
        }
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String describeConnection() {
        return "Built-in workflow simulator (no Camunda cluster connected)";
    }

    @Override
    public ProcessInstanceRef startProcess(String businessId, Map<String, Object> variables) {
        long instanceKey = keySequence.incrementAndGet();
        Instance instance = new Instance(instanceKey, businessId);
        putAll(instance.variables, variables);
        instances.put(instanceKey, instance);
        createTask(instance, WorkflowStep.QUALIFICATION);
        log.debug("Simulated process instance {} started for {}", instanceKey, businessId);
        return new ProcessInstanceRef(instanceKey, keySequence.get(), processId, 1);
    }

    @Override
    public TaskPage searchTasks(TaskQuery query) {
        Comparator<MutableTask> order = query.completed()
                ? Comparator.comparing((MutableTask t) -> t.completionDate,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing((MutableTask t) -> t.creationDate);

        List<WorkflowTask> matches = tasks.values().stream()
                .filter(t -> query.completed()
                        ? "COMPLETED".equals(t.state)
                        : "CREATED".equals(t.state))
                .filter(t -> query.candidateGroups().isEmpty()
                        || query.candidateGroups().contains(t.step.getCandidateGroup()))
                .filter(t -> query.assignee() == null || query.assignee().equals(t.assignee))
                .filter(t -> !query.unassignedOnly() || query.assignee() != null || t.assignee == null)
                .filter(t -> query.businessId() == null || query.businessId().equals(t.businessId))
                .filter(t -> query.processInstanceKey() == null
                        || query.processInstanceKey() == t.processInstanceKey)
                .filter(t -> query.step() == null || query.step() == t.step)
                .sorted(order)
                .map(MutableTask::toRecord)
                .toList();

        long total = matches.size();
        List<WorkflowTask> page = matches.stream()
                .skip(query.from())
                .limit(query.size())
                .toList();
        return new TaskPage(page, total);
    }

    @Override
    public Optional<WorkflowTask> findTask(long taskKey) {
        return Optional.ofNullable(tasks.get(taskKey)).map(MutableTask::toRecord);
    }

    @Override
    public void assignTask(long taskKey, String assignee) {
        MutableTask task = openTask(taskKey);
        task.assignee = assignee;
    }

    @Override
    public void unassignTask(long taskKey) {
        openTask(taskKey).assignee = null;
    }

    @Override
    public synchronized void completeTask(long taskKey, Map<String, Object> variables) {
        MutableTask task = openTask(taskKey);
        Instance instance = instances.get(task.processInstanceKey);
        if (instance == null) {
            throw new CamundaGatewayException("Process instance "
                    + task.processInstanceKey + " no longer exists");
        }

        putAll(instance.variables, variables);
        task.state = "COMPLETED";
        task.completionDate = Instant.now();

        Optional<WorkflowStep> next = route(task.step, instance.variables);
        if (next.isPresent()) {
            createTask(instance, next.get());
        } else {
            instance.state = "COMPLETED";
        }
    }

    @Override
    public void cancelProcessInstance(long processInstanceKey) {
        Instance instance = instances.get(processInstanceKey);
        if (instance == null) {
            throw new CamundaGatewayException("Unknown process instance " + processInstanceKey);
        }
        instance.state = "TERMINATED";
        tasks.values().stream()
                .filter(t -> t.processInstanceKey == processInstanceKey && "CREATED".equals(t.state))
                .forEach(t -> t.state = "CANCELED");
    }

    @Override
    public Map<String, Object> processVariables(long processInstanceKey) {
        Instance instance = instances.get(processInstanceKey);
        return instance == null ? Map.of() : new LinkedHashMap<>(instance.variables);
    }

    @Override
    public Optional<String> processInstanceState(long processInstanceKey) {
        return Optional.ofNullable(instances.get(processInstanceKey)).map(i -> i.state);
    }

    @Override
    public DeploymentResult deploy(String resourceName, byte[] content) {
        log.info("Simulator: pretending to deploy {} ({} bytes)", resourceName, content.length);
        return new DeploymentResult(processId, keySequence.incrementAndGet(), 1);
    }

    // -------------------------------------------------------------- restoration

    /**
     * Recreates an instance that is already recorded in the database, so that restarting
     * the application does not strand open complaints.
     */
    public void restoreInstance(long processInstanceKey, String businessId, WorkflowStep step,
                                Map<String, Object> variables, String assignee) {
        Instance instance = new Instance(processInstanceKey, businessId);
        putAll(instance.variables, variables);
        instances.put(processInstanceKey, instance);
        keySequence.accumulateAndGet(processInstanceKey, Math::max);

        MutableTask task = createTask(instance, step);
        task.assignee = assignee;
    }

    // ------------------------------------------------------------------ routing

    /**
     * The gateways of the BPMN model, evaluated on the instance variables. Returning an
     * empty optional means the instance reached an end event.
     */
    private Optional<WorkflowStep> route(WorkflowStep completed, Map<String, Object> variables) {
        return switch (completed) {
            // "Reclamation valide / pertinente ?"
            case QUALIFICATION -> "VALID".equals(variables.get(ProcessVariables.QUALIFICATION_DECISION))
                    ? Optional.of(WorkflowStep.FRONT_OFFICE)
                    : Optional.empty();
            // "FO peut repondre ?"
            case FRONT_OFFICE -> Boolean.TRUE.equals(variables.get(ProcessVariables.FO_CAN_ANSWER))
                    ? Optional.of(WorkflowStep.VALIDATION)
                    : Optional.of(WorkflowStep.MIDDLE_OFFICE);
            // "MO peut repondre ?"
            case MIDDLE_OFFICE -> Boolean.TRUE.equals(variables.get(ProcessVariables.MO_CAN_ANSWER))
                    ? Optional.of(WorkflowStep.VALIDATION)
                    : Optional.of(WorkflowStep.BACK_OFFICE);
            // Back Office is the last resort and always hands over to validation.
            case BACK_OFFICE -> Optional.of(WorkflowStep.VALIDATION);
            // "Validee ?" - approved closes the case, otherwise back to qualification.
            case VALIDATION -> "APPROVED".equals(variables.get(ProcessVariables.VALIDATION_DECISION))
                    ? Optional.empty()
                    : Optional.of(WorkflowStep.QUALIFICATION);
        };
    }

    private MutableTask createTask(Instance instance, WorkflowStep step) {
        long taskKey = keySequence.incrementAndGet();
        MutableTask task = new MutableTask(taskKey, instance.key, instance.businessId, step,
                dueDateFor(instance, step), priorityOf(instance));
        tasks.put(taskKey, task);
        return task;
    }

    private MutableTask openTask(long taskKey) {
        MutableTask task = tasks.get(taskKey);
        if (task == null) {
            throw new CamundaGatewayException("Unknown user task " + taskKey);
        }
        if (!"CREATED".equals(task.state)) {
            throw new CamundaGatewayException("Task " + taskKey + " is already " + task.state);
        }
        return task;
    }

    /** Mirrors the {@code zeebe:taskSchedule} expression of the model. */
    private Instant dueDateFor(Instance instance, WorkflowStep step) {
        Object raw = instance.variables.get(ProcessVariables.slaVariable(step));
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                log.debug("Ignoring unparseable SLA value '{}' for {}", text, step);
            }
        }
        return null;
    }

    /** Mirrors the {@code zeebe:priority} expression of the model. */
    private Integer priorityOf(Instance instance) {
        Object raw = instance.variables.get(ProcessVariables.PRIORITY_SCORE);
        return raw instanceof Number number ? number.intValue() : 50;
    }

    private static void putAll(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        // ConcurrentHashMap rejects null values; a null variable simply means "unset".
        source.forEach((key, value) -> {
            if (value == null) {
                target.remove(key);
            } else {
                target.put(key, value);
            }
        });
    }

    /** Number of instances currently held in memory; used by the admin screen. */
    public int instanceCount() {
        return instances.size();
    }

    /** Open user tasks currently held in memory. */
    public long openTaskCount() {
        return tasks.values().stream().filter(t -> "CREATED".equals(t.state)).count();
    }

    /** Every open task, used by the restorer to avoid duplicating work. */
    public List<WorkflowTask> openTasks() {
        List<WorkflowTask> result = new ArrayList<>();
        tasks.values().stream()
                .filter(t -> "CREATED".equals(t.state))
                .forEach(t -> result.add(t.toRecord()));
        return result;
    }
}
