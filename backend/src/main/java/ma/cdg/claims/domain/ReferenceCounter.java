package ma.cdg.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Per-year counter backing the human-readable claim references. */
@Entity
@Table(name = "reference_counters")
@Getter
@Setter
public class ReferenceCounter {

    /** The year the counter belongs to, e.g. {@code 2026}. */
    @Id
    @Column(length = 10)
    private String scope;

    @Column(name = "last_value", nullable = false)
    private long lastValue;
}
