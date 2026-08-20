package ma.cdg.claims.service;

import java.time.Instant;
import java.util.List;
import ma.cdg.claims.domain.ClaimChannel;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.ClaimType;
import ma.cdg.claims.domain.WorkflowStep;

/**
 * Filters accepted by the complaint list. Every field is optional; {@code null} or an
 * empty list means "do not filter on this".
 *
 * @param search free text matched against reference, subject and customer name
 */
public record ClaimSearchCriteria(String search,
                                  List<ClaimStatus> statuses,
                                  List<ClaimType> types,
                                  List<ClaimPriority> priorities,
                                  List<ClaimChannel> channels,
                                  WorkflowStep step,
                                  String assignee,
                                  Boolean overdue,
                                  Boolean openOnly,
                                  Instant createdFrom,
                                  Instant createdTo) {

    public static ClaimSearchCriteria empty() {
        return new ClaimSearchCriteria(null, List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null);
    }
}
