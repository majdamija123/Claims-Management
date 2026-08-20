package ma.cdg.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A customer complaint. The row is the application's own record; the authoritative
 * routing state lives in the Camunda process instance referenced by
 * {@link #processInstanceKey}, and is projected back onto {@link #status} /
 * {@link #currentStep} whenever a task is completed or the synchroniser runs.
 */
@Entity
@Table(name = "claims", indexes = {
        @Index(name = "idx_claims_reference", columnList = "reference", unique = true),
        @Index(name = "idx_claims_status", columnList = "status"),
        @Index(name = "idx_claims_process_instance", columnList = "process_instance_key"),
        @Index(name = "idx_claims_created_at", columnList = "created_at")
})
@Getter
@Setter
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable business key, e.g. {@code REC-2026-000042}. */
    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    // ---------------------------------------------------------------- customer

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Column(name = "customer_phone", length = 40)
    private String customerPhone;

    /** The customer's account or file number at CDG, when known. */
    @Column(name = "customer_reference", length = 60)
    private String customerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimChannel channel = ClaimChannel.WEB_PORTAL;

    /** CDG entity or branch the complaint is about. */
    @Column(length = 120)
    private String entity;

    // ----------------------------------------------------------------- content

    @Column(nullable = false, length = 250)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ClaimType type = ClaimType.OTHER;

    /** Category suggested by the classification model at registration time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_type", length = 40)
    private ClaimType predictedType;

    @Column(name = "prediction_confidence")
    private Double predictionConfidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimPriority priority = ClaimPriority.NORMAL;

    // ------------------------------------------------------------ process state

    @Column(name = "process_instance_key")
    private Long processInstanceKey;

    @Column(name = "process_version")
    private Integer processVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimStatus status = ClaimStatus.IN_QUALIFICATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 30)
    private WorkflowStep currentStep = WorkflowStep.QUALIFICATION;

    /** Username of the agent currently holding the open task, if any. */
    @Column(name = "current_assignee", length = 80)
    private String currentAssignee;

    @Column(name = "step_started_at")
    private Instant stepStartedAt = Instant.now();

    /** Deadline of the step currently in progress. */
    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    /** Set once the deadline of any step has been missed. */
    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached = false;

    /** How many times validation sent this complaint back to qualification. */
    @Column(name = "return_count", nullable = false)
    private int returnCount = 0;

    // ---------------------------------------------------------------- outcome

    @Column(length = 4000)
    private String resolution;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    // ---------------------------------------------------------------- metadata

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** True when the current step is past its deadline and still open. */
    public boolean isOverdue() {
        return slaDueAt != null && !status.isTerminal() && Instant.now().isAfter(slaDueAt);
    }
}
