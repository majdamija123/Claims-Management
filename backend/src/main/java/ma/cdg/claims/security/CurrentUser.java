package ma.cdg.claims.security;

import java.util.Optional;
import java.util.Set;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Convenience access to the authenticated user from the service layer. */
@Component
public class CurrentUser {

    public static final String SYSTEM = "system";

    public Optional<AppUserPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public AppUserPrincipal require() {
        return principal().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    public AppUser user() {
        return require().getUser();
    }

    public String username() {
        return principal().map(AppUserPrincipal::getUsername).orElse(SYSTEM);
    }

    public UserRole role() {
        return require().getRole();
    }

    /** The Camunda candidate groups whose queues this user may read. */
    public Set<String> visibleCandidateGroups() {
        return require().getRole().visibleCandidateGroups();
    }

    public boolean isOversight() {
        return principal().map(p -> p.getRole().isOversight()).orElse(false);
    }
}
