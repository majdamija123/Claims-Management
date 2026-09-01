package ma.cdg.claims.web;

import jakarta.validation.Valid;
import java.time.Instant;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.repository.AppUserRepository;
import ma.cdg.claims.security.AppUserPrincipal;
import ma.cdg.claims.security.CurrentUser;
import ma.cdg.claims.security.JwtService;
import ma.cdg.claims.web.dto.AuthDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in and "who am I". */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final DtoMapper mapper;

    public AuthController(AuthenticationManager authenticationManager,
                          AppUserRepository users,
                          JwtService jwtService,
                          CurrentUser currentUser,
                          DtoMapper mapper) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        AppUser user = ((AppUserPrincipal) authentication.getPrincipal()).getUser();
        user.setLastLoginAt(Instant.now());
        users.save(user);

        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                jwtService.issue(user), jwtService.expiresInSeconds(), mapper.toSummary(user)));
    }

    @GetMapping("/me")
    public AuthDtos.UserSummary me() {
        return mapper.toSummary(currentUser.user());
    }
}
