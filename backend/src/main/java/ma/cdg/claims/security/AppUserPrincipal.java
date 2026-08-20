package ma.cdg.claims.security;

import java.util.Collection;
import java.util.List;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.domain.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Adapts an {@link AppUser} to Spring Security while keeping the entity reachable. */
public class AppUserPrincipal implements UserDetails {

    private final transient AppUser user;

    public AppUserPrincipal(AppUser user) {
        this.user = user;
    }

    public AppUser getUser() {
        return user;
    }

    public UserRole getRole() {
        return user.getRole();
    }

    public String getFullName() {
        return user.getFullName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(user.getRole().authority()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }

    @Override
    public boolean isAccountNonExpired() {
        return user.isActive();
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.isActive();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
