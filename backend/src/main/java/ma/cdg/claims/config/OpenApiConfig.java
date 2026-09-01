package ma.cdg.claims.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI is served at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI claimsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CDG Claims Management API")
                        .version("1.0.0")
                        .description("""
                                REST API of the CDG customer complaint management system.
                                Complaint routing is orchestrated by the Camunda 8 process
                                `reclamation-client-cdg`; this API creates process instances,
                                serves each user's task inbox and records the outcome of every step.
                                """)
                        .contact(new Contact().name("CDG - Reclamations Clients")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
