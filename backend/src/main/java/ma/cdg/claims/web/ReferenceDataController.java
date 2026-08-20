package ma.cdg.claims.web;

import java.util.Arrays;
import java.util.List;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.ClaimWorkflow;
import ma.cdg.claims.domain.UserRole;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.web.dto.ReferenceDataDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the enumerations to the front end, so labels and the list of decisions per step
 * are defined once, in the backend, and never drift from the process model.
 */
@RestController
@RequestMapping("/api/reference-data")
public class ReferenceDataController {

    @GetMapping
    public ReferenceDataDto referenceData() {
        return new ReferenceDataDto(
                Arrays.stream(ClaimType.values())
                        .map(t -> new ReferenceDataDto.Option(t.name(), t.getLabel())).toList(),
                Arrays.stream(ClaimChannel.values())
                        .map(c -> new ReferenceDataDto.Option(c.name(), c.getLabel())).toList(),
                Arrays.stream(ClaimPriority.values())
                        .map(p -> new ReferenceDataDto.Option(p.name(), p.getLabel())).toList(),
                Arrays.stream(ClaimStatus.values())
                        .map(s -> new ReferenceDataDto.Option(s.name(), s.getLabel())).toList(),
                steps(),
                Arrays.stream(UserRole.values())
                        .map(r -> new ReferenceDataDto.Option(r.name(), r.getLabel())).toList());
    }

    private List<ReferenceDataDto.StepOption> steps() {
        return Arrays.stream(WorkflowStep.values())
                .map(step -> new ReferenceDataDto.StepOption(
                        step.name(),
                        step.getLabel(),
                        step.getCandidateGroup(),
                        step.getRole().name(),
                        step.getOrder(),
                        ClaimWorkflow.decisionsFor(step).stream()
                                .map(d -> new ReferenceDataDto.Option(d.name(), d.getLabel()))
                                .toList()))
                .toList();
    }
}
