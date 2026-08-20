package ma.cdg.claims.camunda.model;

import java.util.List;

/** A page of user tasks together with the total number of matches. */
public record TaskPage(List<WorkflowTask> items, long total) {

    public static TaskPage empty() {
        return new TaskPage(List.of(), 0L);
    }
}
