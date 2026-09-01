package ma.cdg.claims.service;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimStatus;
import org.springframework.data.jpa.domain.Specification;

/** Translates {@link ClaimSearchCriteria} into a JPA specification. */
public final class ClaimSpecifications {

    private static final List<ClaimStatus> TERMINAL = Arrays.stream(ClaimStatus.values())
            .filter(ClaimStatus::isTerminal)
            .toList();

    private ClaimSpecifications() {
    }

    public static List<ClaimStatus> terminalStatuses() {
        return TERMINAL;
    }

    public static Specification<Claim> from(ClaimSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.search() != null && !criteria.search().isBlank()) {
                String pattern = "%" + criteria.search().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("reference")), pattern),
                        builder.like(builder.lower(root.get("subject")), pattern),
                        builder.like(builder.lower(root.get("customerName")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            if (isNotEmpty(criteria.statuses())) {
                predicates.add(root.get("status").in(criteria.statuses()));
            }
            if (isNotEmpty(criteria.types())) {
                predicates.add(root.get("type").in(criteria.types()));
            }
            if (isNotEmpty(criteria.priorities())) {
                predicates.add(root.get("priority").in(criteria.priorities()));
            }
            if (isNotEmpty(criteria.channels())) {
                predicates.add(root.get("channel").in(criteria.channels()));
            }
            if (criteria.step() != null) {
                predicates.add(builder.equal(root.get("currentStep"), criteria.step()));
            }
            if (criteria.assignee() != null && !criteria.assignee().isBlank()) {
                predicates.add(builder.equal(root.get("currentAssignee"), criteria.assignee()));
            }
            if (Boolean.TRUE.equals(criteria.openOnly())) {
                predicates.add(builder.not(root.get("status").in(TERMINAL)));
            }
            if (criteria.overdue() != null) {
                Predicate overdue = builder.and(
                        builder.isNotNull(root.get("slaDueAt")),
                        builder.lessThan(root.get("slaDueAt"), Instant.now()),
                        builder.not(root.get("status").in(TERMINAL)));
                predicates.add(criteria.overdue() ? overdue : builder.not(overdue));
            }
            if (criteria.createdFrom() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
            }
            if (criteria.createdTo() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), criteria.createdTo()));
            }

            return predicates.isEmpty() ? builder.conjunction()
                    : builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean isNotEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }
}
