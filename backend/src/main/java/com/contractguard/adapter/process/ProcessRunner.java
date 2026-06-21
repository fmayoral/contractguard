package com.contractguard.adapter.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes fixed argument arrays with a timeout and an output cap (§17.6-8).
 * There is deliberately no method taking a shell string: every caller passes
 * a pre-built argv, so model output can never become an executable command.
 */
public class ProcessRunner {

    public record ProcessResult(int exitCode, String output, boolean truncated,
            boolean timedOut, Duration duration) {
    }

    public ProcessResult run(List<String> command, Path workingDirectory,
            Map<String, String> extraEnvironment, Duration timeout, int maxOutputBytes) {
        Instant start = Instant.now();
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(extraEnvironment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start command " + command.get(0), e);
        }
        try {
            byte[] captured = readBounded(process.getInputStream(), maxOutputBytes);
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                return new ProcessResult(-1, new String(captured, StandardCharsets.UTF_8),
                        captured.length >= maxOutputBytes, true, Duration.between(start, Instant.now()));
            }
            return new ProcessResult(process.exitValue(), new String(captured, StandardCharsets.UTF_8),
                    captured.length >= maxOutputBytes, false, Duration.between(start, Instant.now()));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("cannot read output of " + command.get(0), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command.get(0), e);
        }
    }

    /**
     * Reads until EOF or the cap; on hitting the cap the remaining stream is
     * drained (discarded) so the child never blocks on a full pipe.
     */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        byte[] head = in.readNBytes(maxBytes);
        if (head.length == maxBytes) {
            in.transferTo(OutputStreamSink.INSTANCE);
        }
        return head;
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discard
        }
    }
}
