package ma.cdg.claims.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import ma.cdg.claims.domain.ReferenceCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReferenceCounterRepository extends JpaRepository<ReferenceCounter, String> {

    /** Locks the row so two concurrent registrations cannot take the same number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReferenceCounter c where c.scope = :scope")
    Optional<ReferenceCounter> lockByScope(@Param("scope") String scope);
}
