package ma.cdg.claims.camunda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientBuilder;
import io.camunda.client.CredentialsProvider;
import ma.cdg.claims.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Camunda client and chooses which gateway the rest of the application talks to.
 *
 * <p>The client is created explicitly rather than through Camunda's own Spring Boot
 * starter: the 8.10 starter targets Spring Boot 4 and would put a second, incompatible
 * actuator on the classpath. Building it here also keeps the connection settings in the
 * application's own {@code cdg.camunda.*} namespace, next to everything else.
 *
 * <p>When the connection is not configured the application falls back to
 * {@link SimulatedCamundaGateway} instead of failing to start, so a missing credential
 * never stops a demonstration.
 */
@Configuration
public class CamundaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CamundaConfiguration.class);

    /**
     * The Camunda client, or {@code null} when the connection is not configured — the
     * gateway bean below then selects the simulator.
     */
    @Bean(destroyMethod = "close")
    CamundaClient camundaClient(ApplicationProperties properties) {
        ApplicationProperties.Camunda config = properties.getCamunda();

        if (!config.isEnabled()) {
            return null;
        }
        if (!config.isConfigured()) {
            log.error("""
                    cdg.camunda.enabled is true but the connection is incomplete \
                    (missing: {}).
                    Falling back to the built-in simulator.""", config.describeMissing());
            return null;
        }

        try {
            CamundaClientBuilder builder = switch (config.getMode()) {
                case SAAS -> CamundaClient.newCloudClientBuilder()
                        .withClusterId(config.getClusterId())
                        .withClientId(config.getClientId())
                        .withClientSecret(config.getClientSecret())
                        .withRegion(config.getRegion());
                case SELF_MANAGED -> selfManaged(config);
            };

            builder.defaultRequestTimeout(config.getRequestTimeout())
                    .preferRestOverGrpc(true);

            if (config.getTenantId() != null && !config.getTenantId().isBlank()) {
                builder.defaultTenantId(config.getTenantId());
            }

            CamundaClient client = builder.build();
            log.info("Camunda client configured in {} mode", config.getMode());
            return client;
        } catch (RuntimeException e) {
            log.error("Could not build the Camunda client ({}); falling back to the simulator",
                    e.getMessage());
            return null;
        }
    }

    private CamundaClientBuilder selfManaged(ApplicationProperties.Camunda config) {
        CamundaClientBuilder builder = CamundaClient.newClientBuilder()
                .grpcAddress(config.getGrpcAddress())
                .restAddress(config.getRestAddress());

        // A self-managed cluster may be open (local docker compose) or behind an identity
        // provider; credentials are only applied when they are actually supplied.
        if (config.getClientId() != null && !config.getClientId().isBlank()) {
            CredentialsProvider credentials = CredentialsProvider.newCredentialsProviderBuilder()
                    .clientId(config.getClientId())
                    .clientSecret(config.getClientSecret())
                    .audience(config.getAudience())
                    .authorizationServerUrl(config.getAuthorizationServerUrl())
                    .build();
            builder.credentialsProvider(credentials);
        }
        return builder;
    }

    @Bean
    CamundaGateway camundaGateway(ApplicationProperties properties,
                                  ObjectMapper objectMapper,
                                  org.springframework.beans.factory.ObjectProvider<CamundaClient> client) {

        CamundaClient camundaClient = client.getIfAvailable();
        if (camundaClient == null) {
            log.warn("""

                    ------------------------------------------------------------------
                     Workflow engine: BUILT-IN SIMULATOR
                     No Camunda cluster is connected. The application replays
                     reclamation-client-cdg.bpmn in memory, so every screen works.
                     Set cdg.camunda.enabled=true with your cluster credentials
                     (see application-camunda.yml) to drive the real engine.
                    ------------------------------------------------------------------""");
            return new SimulatedCamundaGateway(properties.getWorkflow().getProcessId());
        }

        log.info("Workflow engine: Camunda 8 cluster, process '{}'",
                properties.getWorkflow().getProcessId());
        return new CamundaClientGateway(camundaClient, properties, objectMapper);
    }
}
