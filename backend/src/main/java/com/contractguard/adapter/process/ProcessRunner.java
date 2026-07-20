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
 *
 * <p>Output is drained on a background thread so the timeout is a real
 * wall-clock deadline on the process itself: a process that keeps producing
 * (bounded) output past the deadline is still killed on time, rather than
 * the deadline only being checked once reading has already run to
 * completion — which would only fire for processes that had already exited.
 */
public class ProcessRunner {

    private static final Duration DRAIN_GRACE_PERIOD = Duration.ofSeconds(10);

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

        OutputDrain drain = new OutputDrain(process.getInputStream(), maxOutputBytes);
        Thread drainThread = new Thread(drain, "process-output-drain");
        drainThread.setDaemon(true);
        drainThread.start();

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                drainThread.join(DRAIN_GRACE_PERIOD.toMillis());
                return new ProcessResult(-1, drain.output(), drain.truncated(),
                        true, Duration.between(start, Instant.now()));
            }
            drainThread.join(DRAIN_GRACE_PERIOD.toMillis());
            return new ProcessResult(process.exitValue(), drain.output(), drain.truncated(),
                    false, Duration.between(start, Instant.now()));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command.get(0), e);
        }
    }

    /**
     * Reads until EOF or the cap; on hitting the cap the remaining stream is
     * drained (discarded) so the child never blocks on a full pipe. Runs on
     * its own thread; results are read only after {@code Thread.join}, which
     * is enough happens-before ordering for the plain fields below.
     */
    private static final class OutputDrain implements Runnable {
        private final InputStream in;
        private final int maxBytes;
        private byte[] captured = new byte[0];
        private boolean truncated;

        OutputDrain(InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            try {
                byte[] head = in.readNBytes(maxBytes);
                if (head.length == maxBytes) {
                    in.transferTo(OutputStreamSink.INSTANCE);
                    truncated = true;
                }
                captured = head;
            } catch (IOException e) {
                // The process was very likely just killed (destroyForcibly closes the
                // pipe); whatever was captured before that stands.
            }
        }

        String output() {
            return new String(captured, StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
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
