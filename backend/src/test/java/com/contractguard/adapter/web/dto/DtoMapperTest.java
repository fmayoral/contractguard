package com.contractguard.adapter.web.dto;

import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMapperTest {

    @Test
    void toDetailCarriesTheQualifiedSpecIdsSeparatelyFromTheDisplayNames() {
        AnalysisRun run = new AnalysisRun("run-1", "demo", "customer-consumer", "trace-1", Instant.now(),
                "source:acme-openapi:customer-api-v1.yaml", "source:acme-openapi:customer-api-v2.yaml");
        run.recordSpecs("customer-api-v1.yaml", "customer-api-v2.yaml", "hash-old", "hash-new", Instant.now());

        RunDtos.RunDetail detail = DtoMapper.toDetail(run, false);

        assertThat(detail.oldSpecFile()).isEqualTo("source:acme-openapi:customer-api-v1.yaml");
        assertThat(detail.newSpecFile()).isEqualTo("source:acme-openapi:customer-api-v2.yaml");
        assertThat(detail.oldSpecName()).isEqualTo("customer-api-v1.yaml");
        assertThat(detail.newSpecName()).isEqualTo("customer-api-v2.yaml");
    }

    @Test
    void toChangesFromARawListOmitsTheRunOnlyExplanationField() {
        var change = Fixtures.change("chg-1");

        List<RunDtos.Change> changes = DtoMapper.toChanges(List.of(change));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("chg-1");
            assertThat(c.type()).isEqualTo(change.type().name());
            assertThat(c.explanation()).isNull();
        });
    }
}
