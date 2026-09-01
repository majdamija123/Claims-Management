package ma.cdg.claims.camunda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import ma.cdg.claims.camunda.model.ProcessInstanceRef;
import ma.cdg.claims.camunda.model.TaskQuery;
import ma.cdg.claims.camunda.model.WorkflowTask;
import ma.cdg.claims.domain.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The simulator must route exactly like the deployed BPMN. */
class SimulatedCamundaGatewayTest {

    private SimulatedCamundaGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SimulatedCamundaGateway("reclamation-client-cdg");
    }

    @Test
    @DisplayName("starting a process opens a qualification task for the qualification group")
    void startOpensQualification() {
        ProcessInstanceRef instance = gateway.startProcess("REC-2026-000001", Map.of());

        WorkflowTask task = onlyOpenTask();
        assertThat(task.step()).contains(WorkflowStep.QUALIFICATION);
        assertThat(task.candidateGroups()).containsExactly("qualification");
        assertThat(task.businessId()).isEqualTo("REC-2026-000001");
        assertThat(task.processInstanceKey()).isEqualTo(instance.processInstanceKey());
        assertThat(gateway.processInstanceState(instance.processInstanceKey())).contains("ACTIVE");
    }

    @Test
    @DisplayName("rejecting at qualification ends the instance without another task")
    void rejectionEndsTheInstance() {
        ProcessInstanceRef instance = gateway.startProcess("REC-1", Map.of());
        gateway.completeTask(onlyOpenTask().taskKey(),
                Map.of(ProcessVariables.QUALIFICATION_DECISION, "REJECTED"));

        assertThat(gateway.searchTasks(TaskQuery.builder().build()).items()).isEmpty();
        assertThat(gateway.processInstanceState(instance.processInstanceKey())).contains("COMPLETED");
    }

    @Test
    @DisplayName("the full escalation path reaches validation through all three offices")
    void escalationReachesValidation() {
        gateway.startProcess("REC-2", Map.of());

        complete(Map.of(ProcessVariables.QUALIFICATION_DECISION, "VALID"));
        assertThat(onlyOpenTask().step()).contains(WorkflowStep.FRONT_OFFICE);

        complete(Map.of(ProcessVariables.FO_CAN_ANSWER, false));
        assertThat(onlyOpenTask().step()).contains(WorkflowStep.MIDDLE_OFFICE);

        complete(Map.of(ProcessVariables.MO_CAN_ANSWER, false));
        assertThat(onlyOpenTask().step()).contains(WorkflowStep.BACK_OFFICE);

        complete(Map.of());
        assertThat(onlyOpenTask().step()).contains(WorkflowStep.VALIDATION);
    }

    @Test
    @DisplayName("the Front Office answering short-circuits straight to validation")
    void frontOfficeAnswerSkipsTheOtherOffices() {
        gateway.startProcess("REC-3", Map.of());
        complete(Map.of(ProcessVariables.QUALIFICATION_DECISION, "VALID"));
        complete(Map.of(ProcessVariables.FO_CAN_ANSWER, true));

        assertThat(onlyOpenTask().step()).contains(WorkflowStep.VALIDATION);
    }

    @Test
    @DisplayName("validation sending the complaint back reopens qualification")
    void validationCanReturnTheComplaint() {
        gateway.startProcess("REC-4", Map.of());
        complete(Map.of(ProcessVariables.QUALIFICATION_DECISION, "VALID"));
        complete(Map.of(ProcessVariables.FO_CAN_ANSWER, true));
        complete(Map.of(ProcessVariables.VALIDATION_DECISION, "RETURNED"));

        assertThat(onlyOpenTask().step()).contains(WorkflowStep.QUALIFICATION);
    }

    @Test
    @DisplayName("approving at validation closes the instance")
    void approvalClosesTheInstance() {
        ProcessInstanceRef instance = gateway.startProcess("REC-5", Map.of());
        complete(Map.of(ProcessVariables.QUALIFICATION_DECISION, "VALID"));
        complete(Map.of(ProcessVariables.FO_CAN_ANSWER, true));
        complete(Map.of(ProcessVariables.VALIDATION_DECISION, "APPROVED"));

        assertThat(gateway.searchTasks(TaskQuery.builder().build()).items()).isEmpty();
        assertThat(gateway.processInstanceState(instance.processInstanceKey())).contains("COMPLETED");
    }

    @Test
    @DisplayName("tasks are filtered by candidate group, assignee and business key")
    void searchFiltersApply() {
        gateway.startProcess("REC-6", Map.of());
        gateway.startProcess("REC-7", Map.of());

        assertThat(gateway.searchTasks(
                TaskQuery.builder().candidateGroups(java.util.Set.of("front-office")).build()).items())
                .isEmpty();
        assertThat(gateway.searchTasks(
                TaskQuery.builder().candidateGroups(java.util.Set.of("qualification")).build()).items())
                .hasSize(2);
        assertThat(gateway.searchTasks(TaskQuery.builder().businessId("REC-7").build()).items())
                .hasSize(1);

        long key = gateway.searchTasks(TaskQuery.builder().businessId("REC-6").build())
                .items().getFirst().taskKey();
        gateway.assignTask(key, "qualif1");

        assertThat(gateway.searchTasks(TaskQuery.builder().assignee("qualif1").build()).items())
                .hasSize(1);
        assertThat(gateway.searchTasks(TaskQuery.builder().unassignedOnly(true).build()).items())
                .hasSize(1);
    }

    @Test
    @DisplayName("a task cannot be completed twice")
    void completingTwiceIsRefused() {
        gateway.startProcess("REC-8", Map.of());
        long key = onlyOpenTask().taskKey();
        gateway.completeTask(key, Map.of(ProcessVariables.QUALIFICATION_DECISION, "REJECTED"));

        assertThatThrownBy(() -> gateway.completeTask(key, Map.of()))
                .isInstanceOf(CamundaGatewayException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("cancelling an instance closes its open task")
    void cancellingTerminatesTheInstance() {
        ProcessInstanceRef instance = gateway.startProcess("REC-9", Map.of());
        gateway.cancelProcessInstance(instance.processInstanceKey());

        assertThat(gateway.searchTasks(TaskQuery.builder().build()).items()).isEmpty();
        assertThat(gateway.processInstanceState(instance.processInstanceKey())).contains("TERMINATED");
    }

    @Test
    @DisplayName("variables accumulate across steps and null clears one")
    void variablesAccumulate() {
        ProcessInstanceRef instance = gateway.startProcess("REC-10", Map.of("subject", "Frais"));

        Map<String, Object> update = new HashMap<>();
        update.put(ProcessVariables.QUALIFICATION_DECISION, "VALID");
        update.put("subject", null);
        gateway.completeTask(onlyOpenTask().taskKey(), update);

        Map<String, Object> variables = gateway.processVariables(instance.processInstanceKey());
        assertThat(variables).containsEntry(ProcessVariables.QUALIFICATION_DECISION, "VALID");
        assertThat(variables).doesNotContainKey("subject");
    }

    private void complete(Map<String, Object> variables) {
        gateway.completeTask(onlyOpenTask().taskKey(), variables);
    }

    private WorkflowTask onlyOpenTask() {
        var tasks = gateway.searchTasks(TaskQuery.builder().build()).items();
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }
}
