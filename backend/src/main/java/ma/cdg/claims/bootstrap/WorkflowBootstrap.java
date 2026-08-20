package ma.cdg.claims.bootstrap;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import ma.cdg.claims.camunda.CamundaGateway;
import ma.cdg.claims.camunda.SimulatedCamundaGateway;
import ma.cdg.claims.camunda.model.DeploymentResult;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.Claim;
import ma.cdg.claims.domain.ClaimStatus;
import ma.cdg.claims.domain.WorkflowStep;
import ma.cdg.claims.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Prepares the engine at startup.
 *
 * <p>Against a real cluster this deploys the packaged BPMN model, so a fresh environment
 * is usable without opening the Modeler. Against the simulator it rebuilds the open
 * instances from the database, so restarting the application does not strand the
 * complaints that were in flight.
 */
@Component
@Order(1)
public class WorkflowBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowBootstrap.class);

    private static final List<ClaimStatus> OPEN = List.of(
            ClaimStatus.IN_QUALIFICATION, ClaimStatus.IN_FRONT_OFFICE,
            ClaimStatus.IN_MIDDLE_OFFICE, ClaimStatus.IN_BACK_OFFICE,
            ClaimStatus.IN_VALIDATION);

    private final CamundaGateway camunda;
    private final ApplicationProperties properties;
    private final ClaimRepository claims;

    public WorkflowBootstrap(CamundaGateway camunda, ApplicationProperties properties,
                             ClaimRepository claims) {
        this.camunda = camunda;
        this.properties = properties;
        this.claims = claims;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (camunda.isSimulated()) {
            restoreOpenInstances();
        } else if (properties.getWorkflow().isDeployOnStartup()) {
            deployModel();
        }
    }

    private void deployModel() {
        String resource = properties.getWorkflow().getResource();
        try {
            byte[] model = new ClassPathResource(resource).getInputStream().readAllBytes();
            DeploymentResult result = camunda.deploy(fileNameOf(resource), model);
            log.info("Deployed {} as {} version {} (definition key {})", resource,
                    result.bpmnProcessId(), result.version(), result.processDefinitionKey());
        } catch (IOException e) {
            log.error("Could not read {} from the classpath: {}", resource, e.getMessage());
        } catch (RuntimeException e) {
            log.error("""
                    Could not deploy {} to the cluster: {}
                    The application will keep running; deploy the model from the Modeler, or \
                    retry from the administration screen once the connection is fixed.""",
                    resource, e.getMessage());
        }
    }

    private void restoreOpenInstances() {
        if (!(camunda instanceof SimulatedCamundaGateway simulator)) {
            return;
        }
        List<Claim> open = claims.findByStatusIn(OPEN);
        int restored = 0;
        for (Claim claim : open) {
            if (claim.getProcessInstanceKey() == null || claim.getCurrentStep() == null) {
                continue;
            }
            simulator.restoreInstance(
                    claim.getProcessInstanceKey(),
                    claim.getReference(),
                    claim.getCurrentStep(),
                    restoredVariables(claim),
                    claim.getCurrentAssignee());
            restored++;
        }
        if (restored > 0) {
            log.info("Simulator: restored {} open complaint(s) from the database", restored);
        }
    }

    /** The subset of variables the simulator needs to recreate a faithful task. */
    private Map<String, Object> restoredVariables(Claim claim) {
        WorkflowStep step = claim.getCurrentStep();
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put(ma.cdg.claims.camunda.ProcessVariables.CLAIM_REFERENCE, claim.getReference());
        variables.put(ma.cdg.claims.camunda.ProcessVariables.CLAIM_ID, claim.getId());
        variables.put(ma.cdg.claims.camunda.ProcessVariables.SUBJECT, claim.getSubject());
        variables.put(ma.cdg.claims.camunda.ProcessVariables.CUSTOMER_NAME, claim.getCustomerName());
        variables.put(ma.cdg.claims.camunda.ProcessVariables.PRIORITY, claim.getPriority().name());
        variables.put(ma.cdg.claims.camunda.ProcessVariables.PRIORITY_SCORE,
                claim.getPriority().getCamundaPriority());
        if (claim.getSlaDueAt() != null) {
            variables.put(ma.cdg.claims.camunda.ProcessVariables.slaVariable(step),
                    claim.getSlaDueAt().toString());
        }
        return variables;
    }

    private static String fileNameOf(String resource) {
        int slash = resource.lastIndexOf('/');
        return slash < 0 ? resource : resource.substring(slash + 1);
    }
}
