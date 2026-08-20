package ma.cdg.claims.repository;

import java.util.List;
import ma.cdg.claims.domain.ClaimEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimEventRepository extends JpaRepository<ClaimEvent, Long> {

    List<ClaimEvent> findByClaimIdOrderByOccurredAtAscIdAsc(Long claimId);
}
