package ma.cdg.claims.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates the figures shown on the home dashboard. */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** A labelled count, ready to be drawn as a bar or a slice. */
    public record Slice(String key, String label, long count) {
    }

    /** Registrations and closures on a given day. */
    public record TrendPoint(LocalDate date, long created, long closed) {
    }

    /** A complaint that has run out of time, shown in the "needs attention" panel. */
    public record OverdueClaim(Long id, String reference, String subject, String customerName,
                               String step, String priority, Instant dueAt, long hoursLate) {
    }

    /** Everything the dashboard screen needs, in one response. */
    public record DashboardStats(long total,
                                 long open,
                                 long resolved,
                                 long rejected,
                                 long cancelled,
                                 long overdue,
                                 long registeredToday,
                                 long closedToday,
                                 Double averageResolutionHours,
                                 double slaComplianceRate,
                                 List<Slice> byStatus,
                                 List<Slice> byType,
                                 List<Slice> byChannel,
                                 List<Slice> byPriority,
                                 List<Slice> byStep,
                                 List<Slice> workload,
                                 List<TrendPoint> trend,
                                 List<OverdueClaim> attention) {
    }

    private final ClaimRepository claims;
    private final TaskService tasks;
    private final SlaService sla;

    public DashboardService(ClaimRepository claims, TaskService tasks, SlaService sla) {
        this.claims = claims;
        this.tasks = tasks;
        this.sla = sla;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats(int trendDays) {
        List<ClaimStatus> terminal = ClaimSpecifications.terminalStatuses();
        Instant now = Instant.now();

        long total = claims.count();
        long open = claims.countOpen(terminal);
        long resolved = claims.countByStatus(ClaimStatus.RESOLVED);
        long rejected = claims.countByStatus(ClaimStatus.REJECTED);
        long cancelled = claims.countByStatus(ClaimStatus.CANCELLED);
        long overdue = claims.countOverdue(now, terminal);

        Instant startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        List<Claim> since = claims.findByCreatedAtAfterOrderByCreatedAtAsc(
                LocalDate.now(ZONE).minusDays(trendDays).atStartOfDay(ZONE).toInstant());

        long registeredToday = since.stream()
                .filter(c -> c.getCreatedAt().isAfter(startOfToday))
                .count();
        long closedToday = since.stream()
                .filter(c -> c.getClosedAt() != null && c.getClosedAt().isAfter(startOfToday))
                .count();

        return new DashboardStats(
                total, open, resolved, rejected, cancelled, overdue,
                registeredToday, closedToday,
                averageResolutionHours(),
                slaComplianceRate(total),
                slices(claims.aggregateByStatus(), s -> ((ClaimStatus) s).getLabel()),
                slices(claims.aggregateByType(), s -> ((ma.cdg.claims.domain.ClaimType) s).getLabel()),
                slices(claims.aggregateByChannel(),
                        s -> ((ma.cdg.claims.domain.ClaimChannel) s).getLabel()),
                slices(claims.aggregateOpenByPriority(terminal),
                        s -> ((ma.cdg.claims.domain.ClaimPriority) s).getLabel()),
                slices(claims.aggregateOpenByStep(terminal), s -> ((WorkflowStep) s).getLabel()),
                workload(),
                trend(since, trendDays),
                attention(terminal));
    }

    /** Open user tasks per step, read from the engine rather than from our own rows. */
    private List<Slice> workload() {
        try {
            Map<WorkflowStep, Long> counts = tasks.openTaskCountsByStep();
            List<Slice> slices = new ArrayList<>();
            counts.forEach((step, count) -> slices.add(
                    new Slice(step.name(), step.getLabel(), count)));
            return slices;
        } catch (RuntimeException e) {
            log.warn("Could not read the engine workload: {}", e.getMessage());
            return List.of();
        }
    }

    private Double averageResolutionHours() {
        List<Object[]> rows = claims.findResolutionTimestamps();
        if (rows.isEmpty()) {
            return null;
        }
        double totalHours = 0d;
        for (Object[] row : rows) {
            Instant created = (Instant) row[0];
            Instant closed = (Instant) row[1];
            totalHours += Duration.between(created, closed).toMinutes() / 60d;
        }
        return Math.round((totalHours / rows.size()) * 10d) / 10d;
    }

    /** Share of complaints that never crossed a deadline. */
    private double slaComplianceRate(long total) {
        if (total == 0) {
            return 100d;
        }
        long breached = claims.countBySlaBreachedTrue();
        return Math.round((100d * (total - breached) / total) * 10d) / 10d;
    }

    private List<Slice> slices(List<Object[]> rows, java.util.function.Function<Object, String> labeller) {
        List<Slice> slices = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            slices.add(new Slice(((Enum<?>) row[0]).name(), labeller.apply(row[0]),
                    ((Number) row[1]).longValue()));
        }
        return slices;
    }

    private List<TrendPoint> trend(List<Claim> since, int days) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), new long[2]);
        }
        for (Claim claim : since) {
            LocalDate created = LocalDate.ofInstant(claim.getCreatedAt(), ZONE);
            long[] counters = byDay.get(created);
            if (counters != null) {
                counters[0]++;
            }
            if (claim.getClosedAt() != null) {
                long[] closedCounters = byDay.get(LocalDate.ofInstant(claim.getClosedAt(), ZONE));
                if (closedCounters != null) {
                    closedCounters[1]++;
                }
            }
        }
        List<TrendPoint> points = new ArrayList<>(byDay.size());
        byDay.forEach((date, counters) -> points.add(new TrendPoint(date, counters[0], counters[1])));
        return points;
    }

    /** The ten most overdue open complaints. */
    private List<OverdueClaim> attention(List<ClaimStatus> terminal) {
        Instant now = Instant.now();
        return claims.findOpenOrderBySlaDueAt(terminal).stream()
                .filter(Claim::isOverdue)
                .sorted(Comparator.comparing(Claim::getSlaDueAt))
                .limit(10)
                .map(claim -> new OverdueClaim(
                        claim.getId(),
                        claim.getReference(),
                        claim.getSubject(),
                        claim.getCustomerName(),
                        claim.getCurrentStep() == null ? "-" : claim.getCurrentStep().getLabel(),
                        claim.getPriority().getLabel(),
                        claim.getSlaDueAt(),
                        ChronoUnit.HOURS.between(claim.getSlaDueAt(), now)))
                .toList();
    }

    /** Health of the current step of a complaint, exposed to the UI. */
    public String slaHealth(Claim claim) {
        return sla.healthOf(claim);
    }
}
