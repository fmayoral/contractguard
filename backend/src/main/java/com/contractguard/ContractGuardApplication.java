package com.contractguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

@SpringBootApplication
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
