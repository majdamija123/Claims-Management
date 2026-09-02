package ma.cdg.claims.config;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import ma.cdg.claims.domain.ClaimPriority;
import ma.cdg.claims.domain.WorkflowStep;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under the {@code cdg.*} prefix in {@code application.yml}. */
@ConfigurationProperties(prefix = "cdg")
public class ApplicationProperties {

    private final Camunda camunda = new Camunda();
    private final Workflow workflow = new Workflow();
    private final Sla sla = new Sla();
    private final Ml ml = new Ml();
    private final Assistant assistant = new Assistant();
    private final Mail mail = new Mail();
    private final Jwt jwt = new Jwt();
    private final Demo demo = new Demo();

    public Camunda getCamunda() {
        return camunda;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public Sla getSla() {
        return sla;
    }

    public Ml getMl() {
        return ml;
    }

    public Assistant getAssistant() {
        return assistant;
    }

    public Mail getMail() {
        return mail;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Demo getDemo() {
        return demo;
    }

    /**
     * Connection to the Camunda 8 cluster.
     *
     * <p>Left disabled, the application runs on its built-in simulator, which replays the
     * same BPMN in memory. Enable it and fill in the credentials to drive the real engine.
     */
    public static class Camunda {

        /** How the cluster is reached. */
        public enum Mode {
            /** Camunda 8 SaaS, addressed by cluster id and region. */
            SAAS,
            /** A self-managed cluster, addressed by its gRPC and REST endpoints. */
            SELF_MANAGED
        }

        private boolean enabled = false;
        private Mode mode = Mode.SAAS;

        // SaaS
        private String clusterId;
        private String region;

        // Self-managed
        private URI grpcAddress = URI.create("http://localhost:26500");
        private URI restAddress = URI.create("http://localhost:8088");

        // OAuth client credentials; required for SaaS, optional for self-managed.
        private String clientId;
        private String clientSecret;
        private String audience;
        private String authorizationServerUrl;

        private Duration requestTimeout = Duration.ofSeconds(20);
        private String tenantId;

        /** True when the values needed to open a connection are all present. */
        public boolean isConfigured() {
            if (!enabled) {
                return false;
            }
            if (mode == Mode.SAAS) {
                return hasText(clusterId) && hasText(region)
                        && hasText(clientId) && hasText(clientSecret);
            }
            return grpcAddress != null;
        }

        /** Names the values that are still missing, for a helpful startup message. */
        public String describeMissing() {
            if (mode != Mode.SAAS) {
                return "cdg.camunda.grpc-address";
            }
            StringBuilder missing = new StringBuilder();
            appendIfBlank(missing, clusterId, "cdg.camunda.cluster-id");
            appendIfBlank(missing, region, "cdg.camunda.region");
            appendIfBlank(missing, clientId, "cdg.camunda.client-id");
            appendIfBlank(missing, clientSecret, "cdg.camunda.client-secret");
            return missing.toString();
        }

        private static void appendIfBlank(StringBuilder target, String value, String name) {
            if (!hasText(value)) {
                if (!target.isEmpty()) {
                    target.append(", ");
                }
                target.append(name);
            }
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getClusterId() {
            return clusterId;
        }

        public void setClusterId(String clusterId) {
            this.clusterId = clusterId;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public URI getGrpcAddress() {
            return grpcAddress;
        }

        public void setGrpcAddress(URI grpcAddress) {
            this.grpcAddress = grpcAddress;
        }

        public URI getRestAddress() {
            return restAddress;
        }

        public void setRestAddress(URI restAddress) {
            this.restAddress = restAddress;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getAuthorizationServerUrl() {
            return authorizationServerUrl;
        }

        public void setAuthorizationServerUrl(String authorizationServerUrl) {
            this.authorizationServerUrl = authorizationServerUrl;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    /** Binding to the deployed BPMN process. */
    public static class Workflow {

        /** The {@code bpmn:process} id of the deployed model. */
        private String processId = "reclamation-client-cdg";

        /** Classpath location of the model, used for the optional startup deployment. */
        private String resource = "bpmn/reclamation-client-cdg.bpmn";

        /** Deploy the model to the cluster when the application starts. */
        private boolean deployOnStartup = true;

        /** How often the local projection is reconciled with the engine. */
        private Duration syncInterval = Duration.ofSeconds(60);

        public String getProcessId() {
            return processId;
        }

        public void setProcessId(String processId) {
            this.processId = processId;
        }

        public String getResource() {
            return resource;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public boolean isDeployOnStartup() {
            return deployOnStartup;
        }

        public void setDeployOnStartup(boolean deployOnStartup) {
            this.deployOnStartup = deployOnStartup;
        }

        public Duration getSyncInterval() {
            return syncInterval;
        }

        public void setSyncInterval(Duration syncInterval) {
            this.syncInterval = syncInterval;
        }
    }

    /** Per-step handling deadlines, adjusted by the claim priority. */
    public static class Sla {

        private Duration defaultDuration = Duration.ofHours(24);

        private Map<WorkflowStep, Duration> steps = new EnumMap<>(WorkflowStep.class);

        /** Warn when this share of the allotted time has elapsed. */
        private double warningThreshold = 0.75d;

        /** How often missed deadlines are looked for. */
        private Duration checkInterval = Duration.ofMinutes(2);

        public Duration getDefaultDuration() {
            return defaultDuration;
        }

        public void setDefaultDuration(Duration defaultDuration) {
            this.defaultDuration = defaultDuration;
        }

        public Map<WorkflowStep, Duration> getSteps() {
            return steps;
        }

        public void setSteps(Map<WorkflowStep, Duration> steps) {
            this.steps = steps;
        }

        public Duration getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(Duration checkInterval) {
            this.checkInterval = checkInterval;
        }

        public double getWarningThreshold() {
            return warningThreshold;
        }

        public void setWarningThreshold(double warningThreshold) {
            this.warningThreshold = warningThreshold;
        }

        /** The deadline budget for a step, shortened for high-priority complaints. */
        public Duration durationFor(WorkflowStep step, ClaimPriority priority) {
            Duration base = steps.getOrDefault(step, defaultDuration);
            long seconds = Math.max(60L, Math.round(base.getSeconds() * priority.getSlaFactor()));
            return Duration.ofSeconds(seconds);
        }
    }

    /** Optional classification service built during the data-analysis phase. */
    public static class Ml {

        private boolean enabled = false;
        private String baseUrl = "http://localhost:8000";
        private Duration timeout = Duration.ofSeconds(3);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Customer e-mail notifications. Disabled by default so nothing is sent by accident. */
    /** The assistant that helps a unit read a complaint and draft its answer. */
    public static class Assistant {

        private boolean enabled = false;
        private String apiKey = "";
        private String model = "llama-3.3-70b-versatile";
        /** Kept small: these are short, focused answers, not essays. */
        private int maxTokens = 1200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Mail {

        private boolean enabled = false;
        private String from = "reclamations@cdg.ma";
        private String signature = "Service Reclamations Clients - CDG";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
        }
    }

    /** Signing configuration for the API tokens. */
    public static class Jwt {

        /** HMAC secret. Override this in every environment. */
        private String secret = "change-me-this-development-secret-must-be-at-least-32-bytes-long";
        private Duration expiration = Duration.ofHours(8);
        private String issuer = "cdg-claims-management";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getExpiration() {
            return expiration;
        }

        public void setExpiration(Duration expiration) {
            this.expiration = expiration;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    /** Seed data for demonstrations. */
    public static class Demo {

        /** Create the demo users if the user table is empty. */
        private boolean seedUsers = true;

        /** Also create a handful of sample complaints at various stages. */
        private boolean seedClaims = true;

        /** Password given to every seeded account. */
        private String password = "Cdg@2026";

        public boolean isSeedUsers() {
            return seedUsers;
        }

        public void setSeedUsers(boolean seedUsers) {
            this.seedUsers = seedUsers;
        }

        public boolean isSeedClaims() {
            return seedClaims;
        }

        public void setSeedClaims(boolean seedClaims) {
            this.seedClaims = seedClaims;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
