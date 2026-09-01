package ma.cdg.claims.camunda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.client.api.response.Process;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ma.cdg.claims.camunda.model.DeploymentResult;
import ma.cdg.claims.camunda.model.ProcessInstanceRef;
import ma.cdg.claims.camunda.model.TaskPage;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;
import ma.cdg.claims.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to a real Camunda 8 cluster through the official Java client (REST/gRPC v2 API).
 *
 * <p>Active whenever a {@link CamundaClient} bean exists, i.e. when
 * {@code camunda.client.enabled} is left at its default of {@code true}.
 */
public class CamundaClientGateway implements CamundaGateway {

    private static final Logger log = LoggerFactory.getLogger(CamundaClientGateway.class);

    private final CamundaClient client;
    private final ApplicationProperties properties;
    private final ObjectMapper objectMapper;

    public CamundaClientGateway(CamundaClient client,
                                ApplicationProperties properties,
                                ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isSimulated() {
        return false;
    }

    @Override
    public String describeConnection() {
        try {
            var config = client.getConfiguration();
            return "Camunda 8 cluster at " + config.getRestAddress();
        } catch (RuntimeException e) {
            return "Camunda 8 cluster";
        }
    }

    @Override
    public ProcessInstanceRef startProcess(String businessId, Map<String, Object> variables) {
        try {
            ProcessInstanceEvent event = client.newCreateInstanceCommand()
                    .bpmnProcessId(properties.getWorkflow().getProcessId())
                    .latestVersion()
                    .businessId(businessId)
                    .variables(variables)
                    .execute();
            return new ProcessInstanceRef(event.getProcessInstanceKey(),
                    event.getProcessDefinitionKey(), event.getBpmnProcessId(), event.getVersion());
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not start a process instance for " + businessId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public TaskPage searchTasks(TaskQuery query) {
        try {
            SearchResponse<UserTask> response = client.newUserTaskSearchRequest()
                    .filter(f -> {
                        f.bpmnProcessId(properties.getWorkflow().getProcessId());
                        f.state(query.completed() ? UserTaskState.COMPLETED : UserTaskState.CREATED);
                        if (!query.candidateGroups().isEmpty()) {
                            f.candidateGroup(g -> g.in(List.copyOf(query.candidateGroups())));
                        }
                        if (query.assignee() != null) {
                            f.assignee(query.assignee());
                        } else if (query.unassignedOnly()) {
                            f.assignee(a -> a.exists(false));
                        }
                        // No f.businessId(...) filter here: that field only exists on user task
                        // search starting with Camunda 8.10. The project targets the 8.9 broker
                        // (see the "camunda.version" comment in pom.xml), so correlation to a
                        // claim always goes through processInstanceKey instead - see toWorkflowTask().
                        if (query.processInstanceKey() != null) {
                            f.processInstanceKey(query.processInstanceKey());
                        }
                        if (query.step() != null) {
                            f.elementId(query.step().getElementId());
                        }
                    })
                    .sort(s -> {
                        if (query.completed()) {
                            s.completionDate().desc();
                        } else {
                            s.creationDate().asc();
                        }
                    })
                    .page(p -> p.from(query.from()).limit(query.size()))
                    .execute();

            List<WorkflowTask> items = new ArrayList<>(response.items().size());
            response.items().forEach(t -> items.add(toWorkflowTask(t)));
            Long total = response.page() == null ? null : response.page().totalItems();
            return new TaskPage(items, total == null ? items.size() : total);
        } catch (RuntimeException e) {
            throw new CamundaGatewayException("User task search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<WorkflowTask> findTask(long taskKey) {
        try {
            return Optional.of(toWorkflowTask(client.newUserTaskGetRequest(taskKey).execute()));
        } catch (RuntimeException e) {
            log.debug("User task {} could not be read: {}", taskKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void assignTask(long taskKey, String assignee) {
        try {
            client.newAssignUserTaskCommand(taskKey)
                    .assignee(assignee)
                    .allowOverride(true)
                    .action("assign")
                    .execute();
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not assign task " + taskKey + " to " + assignee + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void unassignTask(long taskKey) {
        try {
            client.newUnassignUserTaskCommand(taskKey).execute();
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not release task " + taskKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void completeTask(long taskKey, Map<String, Object> variables) {
        try {
            client.newCompleteUserTaskCommand(taskKey)
                    .variables(variables)
                    .action("complete")
                    .execute();
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not complete task " + taskKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelProcessInstance(long processInstanceKey) {
        try {
            client.newCancelInstanceCommand(processInstanceKey).execute();
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not cancel process instance " + processInstanceKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> processVariables(long processInstanceKey) {
        try {
            SearchResponse<Variable> response = client.newVariableSearchRequest()
                    .filter(f -> f.processInstanceKey(processInstanceKey).scopeKey(processInstanceKey))
                    .page(p -> p.limit(200))
                    .execute();

            Map<String, Object> variables = new LinkedHashMap<>();
            for (Variable variable : response.items()) {
                variables.put(variable.getName(), parseJson(variable.getValue()));
            }
            return variables;
        } catch (RuntimeException e) {
            log.warn("Could not read variables of instance {}: {}", processInstanceKey, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public Optional<String> processInstanceState(long processInstanceKey) {
        try {
            var instance = client.newProcessInstanceGetRequest(processInstanceKey).execute();
            return Optional.ofNullable(instance.getState()).map(Enum::name);
        } catch (RuntimeException e) {
            log.debug("Process instance {} could not be read: {}", processInstanceKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public DeploymentResult deploy(String resourceName, byte[] content) {
        try {
            DeploymentEvent event = client.newDeployResourceCommand()
                    .addResourceBytes(content, resourceName)
                    .execute();
            List<Process> processes = event.getProcesses();
            if (processes.isEmpty()) {
                throw new CamundaGatewayException("Deployment of " + resourceName
                        + " returned no process definition");
            }
            Process process = processes.getFirst();
            return new DeploymentResult(process.getBpmnProcessId(),
                    process.getProcessDefinitionKey(), process.getVersion());
        } catch (CamundaGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CamundaGatewayException(
                    "Could not deploy " + resourceName + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ mapping

    private WorkflowTask toWorkflowTask(UserTask task) {
        return new WorkflowTask(
                task.getUserTaskKey() == null ? 0L : task.getUserTaskKey(),
                task.getElementId(),
                task.getName(),
                task.getAssignee(),
                task.getCandidateGroups() == null ? List.of() : List.copyOf(task.getCandidateGroups()),
                task.getProcessInstanceKey() == null ? 0L : task.getProcessInstanceKey(),
                // UserTask.getBusinessId() does not exist on the 8.9 client this project targets
                // (added in 8.10). Every caller of WorkflowTask.businessId() already falls back
                // to processInstanceKey when this is null - see ClaimService.requireForProcess().
                null,
                toInstant(task.getCreationDate()),
                toInstant(task.getDueDate()),
                task.getPriority(),
                task.getState() == null ? null : task.getState().name());
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private Object parseJson(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            return objectMapper.readValue(rawValue, new TypeReference<Object>() {
            });
        } catch (Exception e) {
            // Not valid JSON (a plain string that was stored unquoted): keep it as-is.
            return rawValue;
        }
    }

    /** Utility used by callers that need a mutable copy of the variable map. */
    static Map<String, Object> mutable(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }
}
