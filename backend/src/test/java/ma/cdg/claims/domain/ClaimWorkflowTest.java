package ma.cdg.claims.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The transition table must match the BPMN diagram exactly. */
class ClaimWorkflowTest {

    @Test
    @DisplayName("a qualified complaint goes to the Front Office, a rejected one ends")
    void qualificationBranches() {
        assertThat(ClaimWorkflow.outcomeOf(WorkflowStep.QUALIFICATION, TaskDecision.VALIDATE))
                .isEqualTo(new ClaimWorkflow.Outcome(WorkflowStep.FRONT_OFFICE,
                        ClaimStatus.IN_FRONT_OFFICE));

        ClaimWorkflow.Outcome rejected =
                ClaimWorkflow.outcomeOf(WorkflowStep.QUALIFICATION, TaskDecision.REJECT);
        assertThat(rejected.isTerminal()).isTrue();
        assertThat(rejected.status()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    @DisplayName("each office either answers into validation or escalates to the next one")
    void escalationChain() {
        assertThat(ClaimWorkflow.outcomeOf(WorkflowStep.FRONT_OFFICE, TaskDecision.ESCALATE).nextStep())
                .isEqualTo(WorkflowStep.MIDDLE_OFFICE);
        assertThat(ClaimWorkflow.outcomeOf(WorkflowStep.MIDDLE_OFFICE, TaskDecision.ESCALATE).nextStep())
                .isEqualTo(WorkflowStep.BACK_OFFICE);

        for (WorkflowStep step : new WorkflowStep[]{
                WorkflowStep.FRONT_OFFICE, WorkflowStep.MIDDLE_OFFICE, WorkflowStep.BACK_OFFICE}) {
            assertThat(ClaimWorkflow.outcomeOf(step, TaskDecision.ANSWER).nextStep())
                    .isEqualTo(WorkflowStep.VALIDATION);
        }
    }

    @Test
    @DisplayName("the Back Office cannot escalate any further")
    void backOfficeIsTheLastResort() {
        assertThat(ClaimWorkflow.isAllowed(WorkflowStep.BACK_OFFICE, TaskDecision.ESCALATE)).isFalse();
        assertThat(ClaimWorkflow.decisionsFor(WorkflowStep.BACK_OFFICE))
                .containsExactly(TaskDecision.ANSWER);
    }

    @Test
    @DisplayName("validation either closes the complaint or sends it back to qualification")
    void validationBranches() {
        assertThat(ClaimWorkflow.outcomeOf(WorkflowStep.VALIDATION, TaskDecision.APPROVE))
                .isEqualTo(new ClaimWorkflow.Outcome(null, ClaimStatus.RESOLVED));
        assertThat(ClaimWorkflow.outcomeOf(WorkflowStep.VALIDATION, TaskDecision.RETURN).nextStep())
                .isEqualTo(WorkflowStep.QUALIFICATION);
    }

    @Test
    @DisplayName("a decision that the step does not offer is refused")
    void unknownDecisionIsRejected() {
        assertThatThrownBy(() -> ClaimWorkflow.outcomeOf(WorkflowStep.VALIDATION, TaskDecision.ESCALATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("the gateway variables use the names the BPMN conditions read")
    void gatewayVariablesMatchTheModel() {
        assertThat(ClaimWorkflow.gatewayVariables(WorkflowStep.QUALIFICATION, TaskDecision.VALIDATE))
                .isEqualTo(Map.of("qualificationDecision", "VALID"));
        assertThat(ClaimWorkflow.gatewayVariables(WorkflowStep.QUALIFICATION, TaskDecision.REJECT))
                .isEqualTo(Map.of("qualificationDecision", "REJECTED"));
        assertThat(ClaimWorkflow.gatewayVariables(WorkflowStep.FRONT_OFFICE, TaskDecision.ANSWER))
                .isEqualTo(Map.of("foCanAnswer", true));
        assertThat(ClaimWorkflow.gatewayVariables(WorkflowStep.MIDDLE_OFFICE, TaskDecision.ESCALATE))
                .isEqualTo(Map.of("moCanAnswer", false));
        assertThat(ClaimWorkflow.gatewayVariables(WorkflowStep.VALIDATION, TaskDecision.APPROVE))
                .isEqualTo(Map.of("validationDecision", "APPROVED"));
    }

    @Test
    @DisplayName("every step offers at least one decision, and only allowed ones")
    void everyStepIsReachableAndActionable() {
        for (WorkflowStep step : WorkflowStep.values()) {
            assertThat(ClaimWorkflow.decisionsFor(step)).isNotEmpty();
            for (TaskDecision decision : ClaimWorkflow.decisionsFor(step)) {
                assertThat(ClaimWorkflow.isAllowed(step, decision)).isTrue();
                assertThat(ClaimWorkflow.outcomeOf(step, decision)).isNotNull();
            }
        }
    }
}
