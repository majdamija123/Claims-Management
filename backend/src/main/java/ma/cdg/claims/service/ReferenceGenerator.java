package ma.cdg.claims.service;

import java.time.Year;
import ma.cdg.claims.domain.ReferenceCounter;
import ma.cdg.claims.repository.ReferenceCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Produces the customer-facing complaint references, e.g. {@code REC-2026-000042}. */
@Service
public class ReferenceGenerator {

    private static final String FORMAT = "REC-%s-%06d";

    private final ReferenceCounterRepository counters;

    public ReferenceGenerator(ReferenceCounterRepository counters) {
        this.counters = counters;
    }

    @Transactional
    public String next() {
        String scope = String.valueOf(Year.now().getValue());
        ReferenceCounter counter = counters.lockByScope(scope).orElseGet(() -> {
            ReferenceCounter created = new ReferenceCounter();
            created.setScope(scope);
            created.setLastValue(0L);
            return counters.saveAndFlush(created);
        });
        counter.setLastValue(counter.getLastValue() + 1);
        counters.saveAndFlush(counter);
        return FORMAT.formatted(scope, counter.getLastValue());
    }
}
