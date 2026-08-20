package ma.cdg.claims.camunda.model;

/** Outcome of deploying the BPMN model to the cluster. */
public record DeploymentResult(String bpmnProcessId, long processDefinitionKey, int version) {
}
