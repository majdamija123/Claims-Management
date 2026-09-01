package ma.cdg.claims.camunda;

import java.util.Map;
import java.util.Optional;
import ma.cdg.claims.camunda.model.DeploymentResult;
import ma.cdg.claims.camunda.model.ProcessInstanceRef;
import ma.cdg.claims.camunda.model.TaskPage;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;

/**
 * The single seam between this application and the workflow engine.
 *
 * <p>Two implementations exist: {@link CamundaClientGateway}, which talks to a real
 * Camunda 8 cluster, and {@link SimulatedCamundaGateway}, an in-memory engine that
 * replays the same BPMN so the product can be demonstrated and tested without
 * credentials. Nothing outside this package should reference the Camunda SDK.
 */
public interface CamundaGateway {

    /** True when no real cluster is behind this gateway. */
    boolean isSimulated();

    /** Short human-readable description of the connection, surfaced in the admin screen. */
    String describeConnection();

    /** Starts a new instance of the complaint process. */
    ProcessInstanceRef startProcess(String businessId, Map<String, Object> variables);

    /** Searches user tasks. */
    TaskPage searchTasks(TaskQuery query);

    Optional<WorkflowTask> findTask(long taskKey);

    void assignTask(long taskKey, String assignee);

    void unassignTask(long taskKey);

    void completeTask(long taskKey, Map<String, Object> variables);

    void cancelProcessInstance(long processInstanceKey);

    /** All variables visible at the root scope of an instance. */
    Map<String, Object> processVariables(long processInstanceKey);

    /** {@code ACTIVE}, {@code COMPLETED}, {@code TERMINATED}, or empty when unknown. */
    Optional<String> processInstanceState(long processInstanceKey);

    DeploymentResult deploy(String resourceName, byte[] content);
}
