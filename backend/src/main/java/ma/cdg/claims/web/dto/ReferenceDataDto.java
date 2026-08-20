package ma.cdg.claims.web.dto;

import java.util.List;

/** Enumerations the front end needs to build its dropdowns and legends. */
public record ReferenceDataDto(List<Option> claimTypes,
                               List<Option> channels,
                               List<Option> priorities,
                               List<Option> statuses,
                               List<StepOption> steps,
                               List<Option> roles) {

    public record Option(String value, String label) {
    }

    /** A workflow step with the decisions available on it. */
    public record StepOption(String value,
                             String label,
                             String candidateGroup,
                             String role,
                             int order,
                             List<Option> decisions) {
    }
}
