package com.contractguard.application.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLibraryTest {

    private final PromptLibrary library = new PromptLibrary();

    @Test
    void loadsEveryShippedPromptWithVersion() {
        for (String name : new String[] {"change-explainer", "impact-assessor",
                "migration-planner", "implementation-agent", "repair-agent"}) {
            PromptLibrary.Prompt prompt = library.get(name, "v1");
            assertThat(prompt.text()).as(name).contains("JSON");
            assertThat(prompt.name()).isEqualTo(name);
            assertThat(prompt.version()).isEqualTo("v1");
        }
    }

    @Test
    void cachesLoadedPrompts() {
        PromptLibrary.Prompt first = library.get("change-explainer", "v1");
        PromptLibrary.Prompt second = library.get("change-explainer", "v1");
        assertThat(first).isSameAs(second);
    }

    @Test
    void missingPromptFailsFast() {
        assertThatThrownBy(() -> library.get("nonexistent", "v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }
}
