package com.contractguard.application.service;

import com.contractguard.adapter.diff.SwaggerOpenApiDiffAdapter;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.testsupport.InMemorySpecSourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses the real bundled demo specifications and the real {@link SwaggerOpenApiDiffAdapter} --
 * not a fake diff port -- so this proves the preview genuinely reuses the same deterministic
 * path a real run's first step takes, not just that it calls some port correctly.
 */
class SpecPreviewServiceTest {

    private static final Path SAMPLES = Path.of("../samples/openapi");

    private SpecPreviewService previewService(Path uploads) {
        SpecSourceService specSources = new SpecSourceService(new InMemorySpecSourceRegistry(),
                (RemoteGitPort) null, uploads.resolve("spec-source-cache"), Clock.systemUTC());
        SpecResolutionService resolution = new SpecResolutionService(SAMPLES, uploads, specSources);
        return new SpecPreviewService(resolution, new SwaggerOpenApiDiffAdapter());
    }

    @Test
    void previewsTheSameChangesARealRunWouldDetect(@TempDir Path uploads) {
        SpecPreviewService.Preview result =
                previewService(uploads).preview("customer-api-v1.yaml", "customer-api-v2.yaml");

        assertThat(result.changes()).hasSize(4);
        assertThat(result.changes()).extracting(ApiChange::type).contains(ChangeType.ENDPOINT_RENAMED);
    }

    @Test
    void reportsNoChangesWhenBothSpecsAreIdentical(@TempDir Path uploads) {
        SpecPreviewService.Preview result =
                previewService(uploads).preview("customer-api-v1.yaml", "customer-api-v1.yaml");

        assertThat(result.changes()).isEmpty();
    }

    @Test
    void surfacesResolutionFailuresRatherThanSwallowingThem(@TempDir Path uploads) {
        SpecPreviewService service = previewService(uploads);
        assertThatThrownBy(() -> service.preview("ghost.yaml", "customer-api-v2.yaml"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_OPENAPI));
    }
}
