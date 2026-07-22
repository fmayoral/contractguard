package com.contractguard;

import com.contractguard.config.ContractGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

/**
 * {@code @EnableConfigurationProperties} lives here rather than on any one {@code config.*}
 * class: it must be processed for every test slice ({@code @WebMvcTest} etc.), which only ever
 * load this single {@code @SpringBootConfiguration} root, not the full set of composition-root
 * {@code @Configuration} classes.
 */
@SpringBootApplication
@EnableConfigurationProperties(ContractGuardProperties.class)
public class ContractGuardApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                SpringApplication.run(ContractGuardApplication.class, args);
        // In CLI mode the process result is the gate verdict (FR-026); the
        // server mode keeps running and never reaches the exit call.
        if (context.getEnvironment().acceptsProfiles(Profiles.of("cli"))) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
