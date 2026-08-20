package ma.cdg.claims.service;

import java.time.Duration;
import java.time.Instant;
import ma.cdg.claims.camunda.ProcessVariables;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.WorkflowStep;
import org.springframework.stereotype.Service;

/**
 * Owns the handling deadlines.
 *
 * <p>A deadline is computed when a step becomes active and is written twice: on the claim
 * row, so the UI and the reports can use it, and as the process variable read by the
 * {@code zeebe:taskSchedule} expression of that step, so Camunda's own due date agrees
 * with ours.
 */
@Service
public class SlaService {

    private final ApplicationProperties properties;

    public SlaService(ApplicationProperties properties) {
        this.properties = properties;
    }

    public Duration budgetFor(WorkflowStep step, ClaimPriority priority) {
        return properties.getSla().durationFor(step, priority);
    }

    public Instant deadlineFor(WorkflowStep step, ClaimPriority priority, Instant from) {
        return from.plus(budgetFor(step, priority));
    }

    /** The process-variable name and ISO-8601 value Camunda needs for a step. */
    public String slaVariableName(WorkflowStep step) {
        return ProcessVariables.slaVariable(step);
    }

    /** Moves a claim onto a new step and refreshes its deadline. */
    public void startStep(Claim claim, WorkflowStep step, Instant at) {
        claim.setCurrentStep(step);
        claim.setStepStartedAt(at);
        claim.setSlaDueAt(deadlineFor(step, claim.getPriority(), at));
    }

    /** Fraction of the current step's budget that has elapsed, clamped to [0, 2]. */
    public double consumedRatio(Claim claim) {
        if (claim.getSlaDueAt() == null || claim.getStepStartedAt() == null) {
            return 0d;
        }
        long total = Duration.between(claim.getStepStartedAt(), claim.getSlaDueAt()).getSeconds();
        if (total <= 0) {
            return 1d;
        }
        long elapsed = Duration.between(claim.getStepStartedAt(), Instant.now()).getSeconds();
        return Math.clamp(elapsed / (double) total, 0d, 2d);
    }

    /** {@code OK}, {@code WARNING} once most of the budget is gone, or {@code BREACHED}. */
    public String healthOf(Claim claim) {
        if (claim.getStatus().isTerminal()) {
            return claim.isSlaBreached() ? "BREACHED" : "OK";
        }
        double ratio = consumedRatio(claim);
        if (ratio >= 1d) {
            return "BREACHED";
        }
        return ratio >= properties.getSla().getWarningThreshold() ? "WARNING" : "OK";
    }
}
