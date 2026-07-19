package com.contractguard.adapter.artifacts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemArtifactStoreTest {

    @TempDir
    Path storage;

    private FilesystemArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new FilesystemArtifactStore(storage);
    }

    @Test
    void savesAndReadsRunScopedArtifacts() {
        String id = store.save("run-1", "report.md", "# Report");
        assertThat(id).isEqualTo("report.md");
        assertThat(store.read("run-1", "report.md")).contains("# Report");
        // Same artifact name in another run is isolated.
        store.save("run-2", "report.md", "# Other");
        assertThat(store.read("run-1", "report.md")).contains("# Report");
    }

    @Test
    void overwritesOnRepeatedSave() {
        store.save("run-1", "report.md", "v1");
        store.save("run-1", "report.md", "v2");
        assertThat(store.read("run-1", "report.md")).contains("v2");
    }

    @Test
    void pathLikeNamesAreFlattenedNotTraversed() {
        String id = store.save("run-1", "../../evil.txt", "x");
        assertThat(id).doesNotContain("/").doesNotContain("\\");
        assertThat(store.read("run-1", id)).contains("x");
        assertThat(storage.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void missingArtifactsReturnEmpty() {
        assertThat(store.read("run-1", "nope.log")).isEmpty();
        assertThat(store.read("../..", "nope.log")).isEmpty();
    }

    @Test
    void deleteForRunRemovesOnlyThatRunsArtifacts() {
        store.save("run-1", "report.md", "# One");
        store.save("run-1", "patch.diff", "diff");
        store.save("run-2", "report.md", "# Two");

        store.deleteForRun("run-1");

        assertThat(store.read("run-1", "report.md")).isEmpty();
        assertThat(store.read("run-1", "patch.diff")).isEmpty();
        assertThat(store.read("run-2", "report.md")).contains("# Two");
        // Deleting an unknown run is a harmless no-op.
        store.deleteForRun("ghost");
    }
}
