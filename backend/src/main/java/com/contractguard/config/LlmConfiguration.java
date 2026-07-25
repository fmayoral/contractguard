package com.contractguard.config;

import com.contractguard.adapter.llm.LlmSettings;
import com.contractguard.adapter.llm.OpenAiCompatibleLlmGateway;
import com.contractguard.adapter.llm.ScriptedLlmGateway;
import com.contractguard.application.agent.ChangeExplainer;
import com.contractguard.application.agent.ImpactInvestigator;
import com.contractguard.application.agent.ImplementationAgent;
import com.contractguard.application.agent.MigrationPlanner;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.LlmGateway;
import com.contractguard.application.port.ObservabilityPort;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.SourceReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Wires the LLM gateway (real or mock) and every agent built on top of it (§9): change
 * explanation, impact investigation, migration planning and implementation. Mock mode
 * ({@link ScriptedLlmGateway}) is the default so no test or zero-config demo run ever calls a
 * live model (NFR-2).
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    // The log.info call below runs exactly once, at startup -- lazy-evaluation guards would be
    // pure boilerplate for a one-shot bean factory method, not a hot path.
    @SuppressWarnings("java:S2629")
    @Bean
    public LlmGateway llmGateway(ContractGuardProperties properties) {
        ContractGuardProperties.Llm llm = properties.llm();
        if ("openai".equalsIgnoreCase(llm.provider())) {
            log.info("LLM provider: OpenAI-compatible endpoint {} (model {})", llm.baseUrl(), llm.model());
            return new OpenAiCompatibleLlmGateway(new LlmSettings(llm.baseUrl(), llm.apiKey(),
                    llm.model(), llm.timeout(), llm.maxTokens(), llm.temperature()));
        }
        log.info("LLM provider: deterministic scripted gateway (mock mode)");
        return new ScriptedLlmGateway();
    }

    @Bean
    public PromptLibrary promptLibrary() {
        return new PromptLibrary();
    }

    @Bean
    public LlmJsonClient llmJsonClient(LlmGateway gateway, JsonCodec codec, ObservabilityPort observability) {
        return new LlmJsonClient(gateway, codec, observability);
    }

    @Bean
    public ChangeExplainer changeExplainer(LlmJsonClient client, PromptLibrary prompts, JsonCodec codec) {
        return new ChangeExplainer(client, prompts, codec);
    }

    @Bean
    public ImpactInvestigator impactInvestigator(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec, RepositorySearchPort searchPort, SourceReaderPort sourceReader,
            ContractGuardProperties properties) {
        return new ImpactInvestigator(client, prompts, codec, searchPort, sourceReader,
                properties.llm().maxWorkflowSteps());
    }

    @Bean
    public MigrationPlanner migrationPlanner(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec, WorkspacePolicy policy, ContractGuardProperties properties, Clock clock) {
        return new MigrationPlanner(client, prompts, codec, policy,
                List.of(properties.validation().commandKey()), clock);
    }

    @Bean
    public ImplementationAgent implementationAgent(LlmJsonClient client, PromptLibrary prompts,
            JsonCodec codec) {
        return new ImplementationAgent(client, prompts, codec);
    }
}
