package com.contractguard.adapter.cli;

import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Classification;
import com.contractguard.domain.RunState;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Headless analysis for CI gates (FR-026): runs the analysis pipeline
 * synchronously (never remediation), emits the JSON report, and computes an
 * exit code from the classification gate. Exit codes: 0 = no gated changes,
 * 1 = the analysis itself failed, 2 = gated changes detected.
 */
public class CliRunner {

    public static final int EXIT_OK = 0;
    public static final int EXIT_RUN_FAILED = 1;
    public static final int EXIT_GATE_TRIPPED = 2;

    /** Lowest classification band that fails the gate. */
    public enum FailOn {
        NONE,
        BREAKING,
        /** Also gates UNKNOWN: unanalysed change categories deserve a human look. */
        POTENTIALLY_BREAKING;

        public static FailOn parse(String value) {
            return value == null || value.isBlank()
                    ? BREAKING
                    : valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    public record Options(String repository, String oldSpec, String newSpec,
            FailOn failOn, Path outputFile) {
    }

    private final RunService runService;
    private final RunQueryService queries;
    private final ReportService reports;
    private final PrintStream out;

    public CliRunner(RunService runService, RunQueryService queries, ReportService reports,
            PrintStream out) {
        this.runService = runService;
        this.queries = queries;
        this.reports = reports;
        this.out = out;
    }

    /** @return the process exit code */
    public int execute(Options options) {
        // The injected executor runs the pipeline on this thread, so the run
        // is terminal-or-awaiting-approval when createRun returns.
        AnalysisRun created = runService.createRun(null, options.repository(),
                options.oldSpec(), options.newSpec());
        AnalysisRun run = queries.getRun(created.id());
        emitReport(run, options.outputFile());
        if (run.state() == RunState.FAILED) {
            return EXIT_RUN_FAILED;
        }
        return gateTripped(run, options.failOn()) ? EXIT_GATE_TRIPPED : EXIT_OK;
    }

    private void emitReport(AnalysisRun run, Path outputFile) {
        String json = reports.jsonReport(run.id());
        if (outputFile == null) {
            out.println(json);
            return;
        }
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write report to " + outputFile, e);
        }
    }

    static boolean gateTripped(AnalysisRun run, FailOn failOn) {
        return switch (failOn) {
            case NONE -> false;
            case BREAKING -> run.changes().stream()
                    .anyMatch(change -> change.classification() == Classification.BREAKING);
            case POTENTIALLY_BREAKING -> run.changes().stream()
                    .anyMatch(change -> change.classification() == Classification.BREAKING
                            || change.classification() == Classification.POTENTIALLY_BREAKING
                            || change.classification() == Classification.UNKNOWN);
        };
    }
}
