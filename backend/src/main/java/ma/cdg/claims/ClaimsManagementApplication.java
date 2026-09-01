package ma.cdg.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CDG customer complaint ("reclamations clients") management system.
 *
 * <p>The BPMN process deployed on Camunda 8 is the single source of truth for the
 * routing of a complaint; this application owns the business data around it and
 * exposes a REST API consumed by the Angular front end.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ClaimsManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsManagementApplication.class, args);
    }
}
