package com.contractguard.application.service;

import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.PatchArtifact;
import com.contractguard.domain.RunState;
import com.contractguard.domain.ValidationResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only aggregation over the whole run history for the dashboard (FR-021).
 * Everything is computed from data the runs already persist — no dedicated
 * statistics tables or counters to keep in sync. Full-history iteration over
 * {@link RunRepository#findAll()} is deliberate: local-first scale (bounded
 * further by FR-023 retention) makes a precomputed store premature.
 */
public class RunStatisticsService {

    /** Days of history covered by {@link Statistics#runsPerDay()}, today inclusive. */
    public static final int ACTIVITY_WINDOW_DAYS = 14;

    private final RunRepository runs;
    private final Clock clock;

    public RunStatisticsService(RunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * @param runsPerDay      one entry per day, oldest first, covering the last
     *                        {@link #ACTIVITY_WINDOW_DAYS} days including empty ones
     * @param changesByType   only categories that actually occurred, most frequent first
     * @param remediation     null-free; zeros when no run has reached validation yet
     */
    public record Statistics(
            int totalRuns,
            int activeRuns,
            Map<RunState, Integer> runsByState,
            List<DailyCount> runsPerDay,
            int totalChanges,
            Map<ChangeType, Integer> changesByType,
            Map<Classification, Integer> changesByClassification,
            Remediation remediation) {
    }

    public record DailyCount(LocalDate day, int count) {
    }

    /**
     * @param firstPassRuns   runs whose first validation attempt succeeded outright
     * @param repairedRuns    runs saved by the single bounded repair (attempt 2 succeeded)
     * @param linesAdded      across applied patches only — rejected patches never landed
     * @param filesTouched    distinct repository paths across applied patches
     */
    public record Remediation(
            int validatedRuns,
            int firstPassRuns,
            int repairAttempts,
            int repairedRuns,
            long averageValidationMillis,
            int linesAdded,
            int linesRemoved,
            int filesTouched) {
    }

    public Statistics statistics() {
        List<AnalysisRun> all = runs.findAll();

        Map<RunState, Integer> byState = new EnumMap<>(RunState.class);
        Map<ChangeType, Integer> byType = new EnumMap<>(ChangeType.class);
        Map<Classification, Integer> byClassification = new EnumMap<>(Classification.class);
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        int totalChanges = 0;
        int activeRuns = 0;
        RemediationAccumulator remediation = new RemediationAccumulator();

        for (AnalysisRun run : all) {
            byState.merge(run.state(), 1, Integer::sum);
            if (!run.state().isTerminal()) {
                activeRuns++;
            }
            byDay.merge(run.createdAt().atZone(ZoneOffset.UTC).toLocalDate(), 1, Integer::sum);
            totalChanges += run.changes().size();
            run.changes().forEach(change -> {
                byType.merge(change.type(), 1, Integer::sum);
                byClassification.merge(change.classification(), 1, Integer::sum);
            });
            remediation.accumulate(run);
        }

        return new Statistics(all.size(), activeRuns, byState, activityWindow(byDay), totalChanges,
                sortedByCountDescending(byType), sortedByCountDescending(byClassification),
                remediation.toRemediation());
    }

    /**
     * Running totals for {@link Remediation}, updated one run at a time -- pulled out of
     * {@link #statistics()} so that method's own loop stays flat and readable.
     */
    private static final class RemediationAccumulator {
        private int validatedRuns;
        private int firstPassRuns;
        private int repairAttempts;
        private int repairedRuns;
        private long validationMillisTotal;
        private int validationCount;
        private int linesAdded;
        private int linesRemoved;
        private final Set<String> filesTouched = new LinkedHashSet<>();

        void accumulate(AnalysisRun run) {
            if (!run.validations().isEmpty()) {
                validatedRuns++;
            }
            for (ValidationResult validation : run.validations()) {
                validationMillisTotal += validation.duration().toMillis();
                validationCount++;
                if (validation.attempt() == 1 && validation.successful()) {
                    firstPassRuns++;
                }
                if (validation.attempt() == 2) {
                    repairAttempts++;
                    if (validation.successful()) {
                        repairedRuns++;
                    }
                }
            }
            for (PatchArtifact patch : run.patches()) {
                if (patch.checkStatus() != PatchArtifact.CheckStatus.APPLIED) {
                    continue;
                }
                linesAdded += countPrefixed(patch.unifiedDiff(), '+');
                linesRemoved += countPrefixed(patch.unifiedDiff(), '-');
                filesTouched.addAll(patch.changedPaths());
            }
        }

        Remediation toRemediation() {
            return new Remediation(validatedRuns, firstPassRuns, repairAttempts, repairedRuns,
                    validationCount == 0 ? 0 : validationMillisTotal / validationCount,
                    linesAdded, linesRemoved, filesTouched.size());
        }

        /** Hunk content lines only: {@code +++}/{@code ---} file headers are not additions/removals. */
        private static int countPrefixed(String unifiedDiff, char prefix) {
            return (int) unifiedDiff.lines()
                    .filter(line -> !line.isEmpty() && line.charAt(0) == prefix)
                    .filter(line -> !line.startsWith("+++") && !line.startsWith("---"))
                    .count();
        }
    }

    /** A dense window (empty days included) so a chart's time axis never has holes. */
    private List<DailyCount> activityWindow(Map<LocalDate, Integer> byDay) {
        // Same UTC bucketing as the per-run days above, so "today" is always the window's last bucket.
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        List<DailyCount> window = new ArrayList<>(ACTIVITY_WINDOW_DAYS);
        for (int i = ACTIVITY_WINDOW_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            window.add(new DailyCount(day, byDay.getOrDefault(day, 0)));
        }
        return window;
    }

    private static <K> Map<K, Integer> sortedByCountDescending(Map<K, Integer> counts) {
        Map<K, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<K, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }
}
