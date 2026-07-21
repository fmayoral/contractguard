package com.contractguard.adapter.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository cloned by {@code RemoteGitCliAdapter} can land on disk without the executable bit
 * on {@code mvnw} (git does not reliably preserve it), which previously failed validation with
 * "cannot start command" -- found against a real remote-registered repository, not a hypothetical.
 */
class MavenWrapperSupportTest {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir
    Path dir;

    @Test
    void makesANonExecutableWrapperExecutable() throws IOException {
        Path wrapper = dir.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\necho ok\n");
        if (POSIX) {
            Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rw-r--r--"));
            assertThat(Files.isExecutable(wrapper)).isFalse();
        }

        MavenWrapperSupport.ensureExecutable(wrapper);

        assertThat(Files.isExecutable(wrapper)).isTrue();
    }

    @Test
    void leavesAnAlreadyExecutableWrapperUnchanged() throws IOException {
        Path wrapper = dir.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\necho ok\n");
        if (POSIX) {
            Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        MavenWrapperSupport.ensureExecutable(wrapper);

        assertThat(Files.isExecutable(wrapper)).isTrue();
    }
}
