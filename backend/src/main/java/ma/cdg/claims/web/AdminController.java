package ma.cdg.claims.web;

import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.camunda.model.DeploymentResult;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.AppUser;
import ma.cdg.claims.error.BusinessRuleException;
import ma.cdg.claims.error.NotFoundException;
import ma.cdg.claims.repository.AppUserRepository;
import ma.cdg.claims.service.WorkflowSyncService;
import ma.cdg.claims.web.dto.AdminDtos;
import ma.cdg.claims.web.dto.AuthDtos;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration: engine status, model deployment and user accounts. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CamundaGateway camunda;
    private final ApplicationProperties properties;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WorkflowSyncService sync;
    private final DtoMapper mapper;

    private volatile String lastDeployment;

    public AdminController(CamundaGateway camunda,
                           ApplicationProperties properties,
                           AppUserRepository users,
                           PasswordEncoder passwordEncoder,
                           WorkflowSyncService sync,
                           DtoMapper mapper) {
        this.camunda = camunda;
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sync = sync;
        this.mapper = mapper;
    }

    @GetMapping("/engine")
    public AdminDtos.EngineStatus engine() {
        return new AdminDtos.EngineStatus(
                camunda.isSimulated(),
                camunda.describeConnection(),
                properties.getWorkflow().getProcessId(),
                properties.getWorkflow().isDeployOnStartup(),
                lastDeployment);
    }

    /** Pushes the packaged BPMN model to the cluster. */
    @PostMapping("/engine/deploy")
    public AdminDtos.DeploymentResponse deploy() {
        byte[] model = readModel();
        DeploymentResult result = camunda.deploy(
                fileNameOf(properties.getWorkflow().getResource()), model);
        lastDeployment = "%s v%d (key %d)".formatted(result.bpmnProcessId(), result.version(),
                result.processDefinitionKey());
        return new AdminDtos.DeploymentResponse(result.bpmnProcessId(),
                String.valueOf(result.processDefinitionKey()), result.version());
    }

    /** The BPMN source, so the front end can render the diagram. */
    @GetMapping(value = "/engine/model", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> model() {
        return ResponseEntity.ok(new String(readModel(), StandardCharsets.UTF_8));
    }

    /** Runs the reconciliation immediately instead of waiting for the scheduler. */
    @PostMapping("/engine/synchronise")
    public java.util.Map<String, Integer> synchronise() {
        return java.util.Map.of(
                "correctedClaims", sync.synchronise(),
                "breachedDeadlines", sync.flagBreachedDeadlines());
    }

    // ------------------------------------------------------------------- users

    @GetMapping("/users")
    public List<AuthDtos.UserSummary> listUsers() {
        return users.findByActiveTrueOrderByFullNameAsc().stream().map(mapper::toSummary).toList();
    }

    @PostMapping("/users")
    public AuthDtos.UserSummary createUser(@Valid @RequestBody AdminDtos.CreateUserRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw new BusinessRuleException("Username " + request.username() + " is already taken");
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setRole(request.role());
        user.setDepartment(request.department());
        user.setActive(true);
        return mapper.toSummary(users.save(user));
    }

    @PutMapping("/users/{id}")
    public AuthDtos.UserSummary updateUser(@PathVariable Long id,
                                           @Valid @RequestBody AdminDtos.UpdateUserRequest request) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new NotFoundException("No user with id " + id));

        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.department() != null) {
            user.setDepartment(request.department());
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        return mapper.toSummary(users.save(user));
    }

    // ----------------------------------------------------------------- helpers

    private byte[] readModel() {
        try {
            return new ClassPathResource(properties.getWorkflow().getResource())
                    .getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read " + properties.getWorkflow().getResource(), e);
        }
    }

    private static String fileNameOf(String resource) {
        int slash = resource.lastIndexOf('/');
        return slash < 0 ? resource : resource.substring(slash + 1);
    }
}
