package ma.cdg.claims.camunda.model;

import java.util.Set;
import ma.cdg.claims.domain.WorkflowStep;

/**
 * Filter for the user-task search.
 *
 * @param candidateGroups restrict to tasks offered to any of these groups
 * @param assignee        restrict to tasks held by this user
 * @param unassignedOnly  when true, only tasks nobody has claimed yet
 * @param businessId      the claim reference carried by the process instance
 * @param processInstanceKey restrict to a single process instance
 * @param step            restrict to a single step of the process
 * @param completed       search completed tasks instead of open ones
 */
public record TaskQuery(Set<String> candidateGroups,
                        String assignee,
                        boolean unassignedOnly,
                        String businessId,
                        Long processInstanceKey,
                        WorkflowStep step,
                        boolean completed,
                        int from,
                        int size) {

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; every field is optional. */
    public static final class Builder {

        private Set<String> candidateGroups = Set.of();
        private String assignee;
        private boolean unassignedOnly;
        private String businessId;
        private Long processInstanceKey;
        private WorkflowStep step;
        private boolean completed;
        private int from = 0;
        private int size = 50;

        public Builder candidateGroups(Set<String> groups) {
            this.candidateGroups = groups == null ? Set.of() : groups;
            return this;
        }

        public Builder assignee(String assignee) {
            this.assignee = assignee;
            return this;
        }

        public Builder unassignedOnly(boolean unassignedOnly) {
            this.unassignedOnly = unassignedOnly;
            return this;
        }

        public Builder businessId(String businessId) {
            this.businessId = businessId;
            return this;
        }

        public Builder processInstanceKey(Long processInstanceKey) {
            this.processInstanceKey = processInstanceKey;
            return this;
        }

        public Builder step(WorkflowStep step) {
            this.step = step;
            return this;
        }

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder page(int from, int size) {
            this.from = Math.max(0, from);
            this.size = Math.clamp(size, 1, 1000);
            return this;
        }

        public TaskQuery build() {
            return new TaskQuery(candidateGroups, assignee, unassignedOnly, businessId,
                    processInstanceKey, step, completed, from, size);
        }
    }
}
