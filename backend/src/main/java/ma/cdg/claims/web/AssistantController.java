package ma.cdg.claims.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.security.CurrentUser;
import ma.cdg.claims.service.AssistantService;
import ma.cdg.claims.service.ClaimService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant panel, scoped to one complaint and to the unit the signed-in user works.
 *
 * <p>The step is taken from the user's own role rather than the request, so nobody can ask
 * for another unit's advice by editing the payload. A supervisor or administrator, who
 * works no single step, gets the advice for wherever the complaint currently sits.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistant;
    private final ClaimService claims;
    private final CurrentUser currentUser;

    public AssistantController(AssistantService assistant, ClaimService claims,
                               CurrentUser currentUser) {
        this.assistant = assistant;
        this.claims = claims;
        this.currentUser = currentUser;
    }

    /** Lets the UI show the panel only when the assistant is actually configured. */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", assistant.isAvailable());
    }

    @PostMapping("/claims/{claimId}")
    public ReplyDto ask(@PathVariable Long claimId, @Valid @RequestBody AskRequest request) {
        Claim claim = claims.require(claimId);

        List<AssistantService.Turn> history = request.messages().stream()
                .map(m -> new AssistantService.Turn(m.fromUser(), m.text()))
                .toList();

        return new ReplyDto(assistant.answer(claim, stepFor(claim), history));
    }

    /** The step the asking user works, falling back to where the complaint stands. */
    private WorkflowStep stepFor(Claim claim) {
        return Arrays.stream(WorkflowStep.values())
                .filter(step -> step.getRole() == currentUser.role())
                .findFirst()
                .orElseGet(() -> claim.getCurrentStep() == null
                        ? WorkflowStep.QUALIFICATION
                        : claim.getCurrentStep());
    }

    /** The conversation so far, oldest first; the last turn is the new question. */
    public record AskRequest(
            @NotEmpty(message = "Ask a question first")
            @Size(max = 40, message = "This conversation is too long; start a new one")
            List<@Valid TurnDto> messages) {
    }

    public record TurnDto(
            @NotNull boolean fromUser,
            @NotEmpty @Size(max = 4000, message = "That message is too long") String text) {
    }

    public record ReplyDto(String reply) {
    }
}
