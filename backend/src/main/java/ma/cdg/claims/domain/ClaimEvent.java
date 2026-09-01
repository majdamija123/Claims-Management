package ma.cdg.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One immutable entry of a claim's audit trail. */
@Entity
@Table(name = "claim_events", indexes = {
        @Index(name = "idx_claim_events_claim", columnList = "claim_id")
})
@Getter
@Setter
public class ClaimEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimEventType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private WorkflowStep step;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskDecision decision;

    /** Username of whoever triggered the event, or {@code system}. */
    @Column(length = 80)
    private String actor;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(length = 2000)
    private String comment;

    @Column(name = "user_task_key")
    private Long userTaskKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static ClaimEvent of(Long claimId, ClaimEventType type, String actor) {
        ClaimEvent event = new ClaimEvent();
        event.setClaimId(claimId);
        event.setType(type);
        event.setActor(actor);
        return event;
    }
}
