package ma.cdg.claims.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    Optional<Claim> findByReference(String reference);

    Optional<Claim> findByProcessInstanceKey(Long processInstanceKey);

    List<Claim> findByReferenceIn(Collection<String> references);

    List<Claim> findByStatusIn(List<ClaimStatus> statuses);

    long countByStatus(ClaimStatus status);

    long countBySlaBreachedTrue();

    @Query("select count(c) from Claim c where c.status not in :terminal")
    long countOpen(@Param("terminal") List<ClaimStatus> terminal);

    @Query("""
            select count(c) from Claim c
            where c.slaDueAt is not null
              and c.slaDueAt < :now
              and c.status not in :terminal
            """)
    long countOverdue(@Param("now") Instant now, @Param("terminal") List<ClaimStatus> terminal);

    /** Open claims whose deadline has passed but that have not yet been flagged. */
    @Query("""
            select c from Claim c
            where c.slaBreached = false
              and c.slaDueAt is not null
              and c.slaDueAt < :now
              and c.status not in :terminal
            """)
    List<Claim> findNewlyBreached(@Param("now") Instant now, @Param("terminal") List<ClaimStatus> terminal);

    /** [status, count] rows. */
    @Query("select c.status, count(c) from Claim c group by c.status")
    List<Object[]> aggregateByStatus();

    /** [type, count] rows. */
    @Query("select c.type, count(c) from Claim c group by c.type order by count(c) desc")
    List<Object[]> aggregateByType();

    /** [channel, count] rows. */
    @Query("select c.channel, count(c) from Claim c group by c.channel")
    List<Object[]> aggregateByChannel();

    /** [priority, count] rows, restricted to open complaints. */
    @Query("select c.priority, count(c) from Claim c where c.status not in :terminal group by c.priority")
    List<Object[]> aggregateOpenByPriority(@Param("terminal") List<ClaimStatus> terminal);

    /** [currentStep, count] rows for the work-in-progress view. */
    @Query("""
            select c.currentStep, count(c) from Claim c
            where c.status not in :terminal and c.currentStep is not null
            group by c.currentStep
            """)
    List<Object[]> aggregateOpenByStep(@Param("terminal") List<ClaimStatus> terminal);

    /**
     * Registration and closure timestamps of every closed complaint. Averaged in Java so
     * the query stays portable between H2 and PostgreSQL.
     */
    @Query("select c.createdAt, c.closedAt from Claim c where c.closedAt is not null")
    List<Object[]> findResolutionTimestamps();

    List<Claim> findByCreatedAtAfterOrderByCreatedAtAsc(Instant from);

    @Query("select c from Claim c where c.status not in :terminal order by c.slaDueAt asc nulls last")
    List<Claim> findOpenOrderBySlaDueAt(@Param("terminal") List<ClaimStatus> terminal);
}
