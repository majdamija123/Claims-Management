package ma.cdg.claims.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ma.cdg.claims.domain.UserRole;

/** Payloads of the administration endpoints. */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** State of the link with the workflow engine, shown on the admin page. */
    public record EngineStatus(boolean simulated,
                               String description,
                               String processId,
                               boolean deployOnStartup,
                               String lastDeployment) {
    }

    public record DeploymentResponse(String bpmnProcessId, String processDefinitionKey, int version) {
    }

    public record CreateUserRequest(@NotBlank @Size(max = 80) String username,
                                    @NotBlank @Size(min = 8, max = 100) String password,
                                    @NotBlank @Size(max = 150) String fullName,
                                    @Email @Size(max = 150) String email,
                                    @NotNull UserRole role,
                                    @Size(max = 100) String department) {
    }

    public record UpdateUserRequest(@Size(max = 150) String fullName,
                                    @Email @Size(max = 150) String email,
                                    UserRole role,
                                    @Size(max = 100) String department,
                                    Boolean active,
                                    @Size(min = 8, max = 100) String password) {
    }
}
