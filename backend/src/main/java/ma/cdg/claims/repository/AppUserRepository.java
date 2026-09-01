package ma.cdg.claims.repository;

import java.util.List;
import java.util.Optional;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findByRoleAndActiveTrue(UserRole role);

    List<AppUser> findByActiveTrueOrderByFullNameAsc();

    boolean existsByUsernameIgnoreCase(String username);
}
