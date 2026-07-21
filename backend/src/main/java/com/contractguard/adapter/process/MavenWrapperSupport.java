package com.contractguard.adapter.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Shared by {@link MavenBuildValidationAdapter} and {@link DockerBuildValidationAdapter}: both run
 * a consumer repository's {@code mvnw} directly, which requires the executable bit regardless of
 * whether the build happens on the host or bind-mounted into a sandbox container.
 *
 * <p>Git does not reliably preserve the executable bit on {@code mvnw} across every clone path —
 * {@code scripts/reset-demo.sh} already works around this for the bundled demo repository with an
 * explicit {@code chmod +x}, but a repository cloned by {@link com.contractguard.adapter.git.RemoteGitCliAdapter}
 * (FR-027/FR-043) had no equivalent, so validation failed with "cannot start command" for any
 * consumer whose {@code mvnw} was committed without the bit set — a common real-world case, not a
 * malformed repository.
 */
final class MavenWrapperSupport {

    private MavenWrapperSupport() {
    }

    /** No-op if already executable or the filesystem has no POSIX permission concept (e.g. NTFS). */
    static void ensureExecutable(Path wrapper) {
        if (Files.isExecutable(wrapper)) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(wrapper);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(wrapper, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem, or genuinely unable to change the bit -- the subsequent process
            // launch fails with its own clear error either way; nothing more useful to do here.
        }
    }
}
