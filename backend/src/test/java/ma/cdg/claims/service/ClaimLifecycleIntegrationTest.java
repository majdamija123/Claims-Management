package ma.cdg.claims.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimEvent;
import ma.cdg.claims.domain.ClaimEventType;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.TaskDecision;
import ma.cdg.claims.domain.UserRole;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.error.AccessDeniedForTaskException;
import ma.cdg.claims.error.BusinessRuleException;
import ma.cdg.claims.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Walks complaints through the whole process against the in-memory engine, checking that
 * the claim projection, the audit trail and the deadlines follow the model.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1",
        "cdg.camunda.enabled=false",
        "cdg.demo.seed-users=true",
        "cdg.demo.seed-claims=false",
        "cdg.workflow.deploy-on-startup=false",
})
class ClaimLifecycleIntegrationTest {

    @Autowired
    private ClaimService claims;

    @Autowired
    private TaskService tasks;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CamundaGateway gateway;

    @Test
    @DisplayName("the tests run against the built-in simulator, not a real cluster")
    void runsOnTheSimulator() {
        assertThat(gateway.isSimulated()).isTrue();
    }

    @Test
    @DisplayName("registering a complaint starts a process instance and opens qualification")
    void registrationStartsTheProcess() {
        Claim claim = register("Client A", "Frais preleves a tort");

        assertThat(claim.getReference()).matches("REC-\\d{4}-\\d{6}");
        assertThat(claim.getProcessInstanceKey()).isNotNull();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.IN_QUALIFICATION);
        assertThat(claim.getCurrentStep()).isEqualTo(WorkflowStep.QUALIFICATION);
        assertThat(claim.getSlaDueAt()).isAfter(claim.getCreatedAt());

        assertThat(claims.history(claim.getId()))
                .extracting(ClaimEvent::getType)
                .contains(ClaimEventType.CREATED, ClaimEventType.PROCESS_STARTED);

        assertThat(openTasks(claim)).hasSize(1);
    }

    @Test
    @DisplayName("a complaint answered by the Front Office and approved is resolved and closed")
    void happyPath() {
        Claim claim = register("Client B", "Duplicata non recu");

        complete(claim, user("qualif1"), TaskDecision.VALIDATE, null, null);
        assertThat(reload(claim).getStatus()).isEqualTo(ClaimStatus.IN_FRONT_OFFICE);

        complete(claim, user("fo1"), TaskDecision.ANSWER, "Duplicata renvoye", null);
        Claim inValidation = reload(claim);
        assertThat(inValidation.getStatus()).isEqualTo(ClaimStatus.IN_VALIDATION);
        assertThat(inValidation.getResolution()).isEqualTo("Duplicata renvoye");

        complete(claim, user("valid1"), TaskDecision.APPROVE, null, null);
        Claim closed = reload(claim);

        assertThat(closed.getStatus()).isEqualTo(ClaimStatus.RESOLVED);
        assertThat(closed.getCurrentStep()).isNull();
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getSlaDueAt()).isNull();
        assertThat(openTasks(closed)).isEmpty();

        assertThat(claims.history(closed.getId()))
                .extracting(ClaimEvent::getType)
                .contains(ClaimEventType.RESOLVED, ClaimEventType.NOTIFIED);
    }

    @Test
    @DisplayName("escalation walks the complaint through Middle and Back Office")
    void escalationPath() {
        Claim claim = register("Client C", "Restitution de consignation bloquee");

        complete(claim, user("qualif1"), TaskDecision.VALIDATE, null, null);
        complete(claim, user("fo1"), TaskDecision.ESCALATE, null, null);
        assertThat(reload(claim).getStatus()).isEqualTo(ClaimStatus.IN_MIDDLE_OFFICE);

        complete(claim, user("mo1"), TaskDecision.ESCALATE, null, null);
        assertThat(reload(claim).getStatus()).isEqualTo(ClaimStatus.IN_BACK_OFFICE);

        complete(claim, user("bo1"), TaskDecision.ANSWER, "Dossier debloque", null);
        assertThat(reload(claim).getStatus()).isEqualTo(ClaimStatus.IN_VALIDATION);

        assertThat(claims.history(claim.getId()))
                .extracting(ClaimEvent::getType)
                .filteredOn(type -> type == ClaimEventType.ESCALATED)
                .hasSize(2);
    }

    @Test
    @DisplayName("rejecting at qualification closes the complaint and records the reason")
    void rejectionPath() {
        Claim claim = register("Client D", "Message inexploitable");

        complete(claim, user("qualif1"), TaskDecision.REJECT, null, "Client non identifiable");
        Claim rejected = reload(claim);

        assertThat(rejected.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("Client non identifiable");
        assertThat(rejected.getClosedAt()).isNotNull();
        assertThat(openTasks(rejected)).isEmpty();
    }

    @Test
    @DisplayName("validation can send the complaint back, which counts as a return")
    void returnLoop() {
        Claim claim = register("Client E", "Contestation d'agios");

        complete(claim, user("qualif1"), TaskDecision.VALIDATE, null, null);
        complete(claim, user("fo1"), TaskDecision.ANSWER, "Geste commercial", null);
        complete(claim, user("valid1"), TaskDecision.RETURN, null, null);

        Claim returned = reload(claim);
        assertThat(returned.getStatus()).isEqualTo(ClaimStatus.IN_QUALIFICATION);
        assertThat(returned.getCurrentStep()).isEqualTo(WorkflowStep.QUALIFICATION);
        assertThat(returned.getReturnCount()).isEqualTo(1);
        assertThat(openTasks(returned)).hasSize(1);
    }

    @Test
    @DisplayName("an agent cannot act on a step that belongs to another unit")
    void roleIsEnforced() {
        Claim claim = register("Client F", "Virement non credite");
        long taskKey = openTasks(claim).getFirst().task().taskKey();

        assertThatThrownBy(() -> tasks.complete(user("fo1"), taskKey,
                new CompleteTaskCommand(TaskDecision.VALIDATE, null, null, null, null, null)))
                .isInstanceOf(AccessDeniedForTaskException.class);
    }

    @Test
    @DisplayName("a decision the step does not offer is refused")
    void decisionIsValidatedAgainstTheStep() {
        Claim claim = register("Client G", "Reclamation diverse");
        long taskKey = openTasks(claim).getFirst().task().taskKey();

        assertThatThrownBy(() -> tasks.complete(user("qualif1"), taskKey,
                new CompleteTaskCommand(TaskDecision.APPROVE, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not available");
    }

    @Test
    @DisplayName("an answer to the customer is mandatory when a unit answers")
    void answerRequiresAResolution() {
        Claim claim = register("Client H", "Delai trop long");
        complete(claim, user("qualif1"), TaskDecision.VALIDATE, null, null);
        long taskKey = openTasks(claim).getFirst().task().taskKey();

        assertThatThrownBy(() -> tasks.complete(user("fo1"), taskKey,
                new CompleteTaskCommand(TaskDecision.ANSWER, null, "  ", null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("answer");
    }

    @Test
    @DisplayName("qualification can correct the category and the urgency")
    void qualificationCorrectsTheCategory() {
        Claim claim = register("Client I", "Objet mal categorise");
        long taskKey = openTasks(claim).getFirst().task().taskKey();

        tasks.complete(user("qualif1"), taskKey, new CompleteTaskCommand(
                TaskDecision.VALIDATE, "Recategorise", null, null,
                ClaimType.PENSION_RETIREMENT, ClaimPriority.URGENT));

        Claim corrected = reload(claim);
        assertThat(corrected.getType()).isEqualTo(ClaimType.PENSION_RETIREMENT);
        assertThat(corrected.getPriority()).isEqualTo(ClaimPriority.URGENT);
    }

    @Test
    @DisplayName("taking a task records the assignee, releasing it clears them")
    void assignmentIsTracked() {
        Claim claim = register("Client J", "Question sur mon compte");
        long taskKey = openTasks(claim).getFirst().task().taskKey();
        AppUser agent = user("qualif1");

        tasks.assignToSelf(agent, taskKey);
        assertThat(reload(claim).getCurrentAssignee()).isEqualTo("qualif1");

        tasks.release(agent, taskKey);
        assertThat(reload(claim).getCurrentAssignee()).isNull();
    }

    @Test
    @DisplayName("an administrator can cancel a complaint mid-flight")
    void cancellationTerminatesTheInstance() {
        Claim claim = register("Client K", "Demande retiree");
        Claim cancelled = claims.cancel(claim.getId(), "Demande retiree par le client", user("admin"));

        assertThat(cancelled.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
        assertThat(openTasks(cancelled)).isEmpty();
        assertThatThrownBy(() -> claims.cancel(claim.getId(), "again", user("admin")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("each unit only sees the tasks of its own queue")
    void inboxIsScopedToTheUnit() {
        Claim claim = register("Client L", "Erreur de prelevement");
        complete(claim, user("qualif1"), TaskDecision.VALIDATE, null, null);

        assertThat(tasks.inbox(user("fo1"), TaskService.InboxScope.GROUP, null, 0, 50).items())
                .extracting(TaskService.TaskItem::step)
                .containsOnly(WorkflowStep.FRONT_OFFICE);

        assertThat(tasks.inbox(user("mo1"), TaskService.InboxScope.GROUP, null, 0, 50).items())
                .extracting(TaskService.TaskItem::step)
                .doesNotContain(WorkflowStep.FRONT_OFFICE);

        // Oversight roles see every queue.
        assertThat(tasks.inbox(user("supervisor"), TaskService.InboxScope.GROUP, null, 0, 50).total())
                .isGreaterThanOrEqualTo(1);
    }

    // ----------------------------------------------------------------- helpers

    private Claim register(String customer, String subject) {
        return claims.create(new CreateClaimCommand(customer, "client@example.ma", null, null,
                        ClaimChannel.EMAIL, "CDG", subject,
                        subject + " — description de la reclamation pour le test.",
                        ClaimType.OTHER, ClaimPriority.NORMAL),
                user("qualif1"));
    }

    private void complete(Claim claim, AppUser actor, TaskDecision decision,
                          String resolution, String rejectionReason) {
        long taskKey = openTasks(claim).getFirst().task().taskKey();
        tasks.complete(actor, taskKey,
                new CompleteTaskCommand(decision, "test", resolution, rejectionReason, null, null));
    }

    private List<TaskService.TaskItem> openTasks(Claim claim) {
        return tasks.forProcessInstance(user("admin"), claim.getProcessInstanceKey());
    }

    private Claim reload(Claim claim) {
        return claims.require(claim.getId());
    }

    private AppUser user(String username) {
        return users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalStateException("missing seeded user " + username));
    }

    @Test
    @DisplayName("the seeded roles cover every step of the process")
    void seededRolesCoverEveryStep() {
        for (WorkflowStep step : WorkflowStep.values()) {
            assertThat(users.findByRoleAndActiveTrue(step.getRole()))
                    .as("an active user for %s", step)
                    .isNotEmpty();
        }
        assertThat(user("admin").getRole()).isEqualTo(UserRole.ADMIN);
    }
}
