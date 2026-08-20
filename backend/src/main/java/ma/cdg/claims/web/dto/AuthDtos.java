package ma.cdg.claims.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Payloads of the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, long expiresInSeconds, UserSummary user) {
    }

    /**
     * @param candidateGroups the Camunda queues this user can see, so the front end can
     *                        label the inbox without a second call
     */
    public record UserSummary(Long id,
                              String username,
                              String fullName,
                              String email,
                              String role,
                              String roleLabel,
                              String department,
                              boolean active,
                              List<String> candidateGroups,
                              String workflowStep) {
    }
}
