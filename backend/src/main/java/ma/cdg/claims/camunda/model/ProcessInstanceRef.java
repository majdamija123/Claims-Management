package ma.cdg.claims.camunda.model;

/** Identity of a freshly created process instance. */
public record ProcessInstanceRef(long processInstanceKey,
                                 long processDefinitionKey,
                                 String bpmnProcessId,
                                 int version) {
}
