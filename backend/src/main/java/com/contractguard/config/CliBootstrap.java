package com.contractguard.config;

import com.contractguard.adapter.cli.CliRunner;
import com.contractguard.domain.ContractGuardException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * Translates {@code contractguard.cli.*} properties into a {@link CliRunner}
 * invocation and carries its exit code to the JVM (FR-026).
 */
public class CliBootstrap implements ApplicationRunner, ExitCodeGenerator {

    private final CliRunner runner;
    private final Environment environment;
    private int exitCode;

    public CliBootstrap(CliRunner runner, Environment environment) {
        this.runner = runner;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String output = environment.getProperty("contractguard.cli.output");
        CliRunner.Options options = new CliRunner.Options(
                environment.getProperty("contractguard.cli.repository"),
                environment.getProperty("contractguard.cli.old-spec"),
                environment.getProperty("contractguard.cli.new-spec"),
                CliRunner.FailOn.parse(environment.getProperty("contractguard.cli.fail-on")),
                output == null || output.isBlank() ? null : Path.of(output));
        try {
            exitCode = runner.execute(options);
        } catch (ContractGuardException e) {
            System.err.println("contractguard: " + e.getMessage());
            System.err.println("remediation: " + e.failure().remediation());
            exitCode = CliRunner.EXIT_RUN_FAILED;
        } catch (IllegalArgumentException e) {
            System.err.println("contractguard: " + e.getMessage());
            exitCode = CliRunner.EXIT_RUN_FAILED;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
