package com.contractguard.adapter.web.dto;

import com.contractguard.application.service.RunStatisticsService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire shape of the dashboard statistics (FR-021). Enum keys become plain
 * strings here so the frontend never depends on Java enum identity, and the
 * service's insertion order (most-frequent-first for distributions) survives
 * serialisation via {@link LinkedHashMap}.
 */
public final class StatisticsDtos {

    public record Statistics(
            int totalRuns,
            int activeRuns,
            Map<String, Integer> runsByState,
            List<DailyCount> runsPerDay,
            int totalChanges,
            Map<String, Integer> changesByType,
            Map<String, Integer> changesByClassification,
            Remediation remediation) {
    }

    public record DailyCount(String day, int count) {
    }

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

    private StatisticsDtos() {
    }

    public static Statistics from(RunStatisticsService.Statistics stats) {
        RunStatisticsService.Remediation remediation = stats.remediation();
        return new Statistics(
                stats.totalRuns(),
                stats.activeRuns(),
                stringKeyed(stats.runsByState()),
                stats.runsPerDay().stream()
                        .map(daily -> new DailyCount(daily.day().toString(), daily.count()))
                        .toList(),
                stats.totalChanges(),
                stringKeyed(stats.changesByType()),
                stringKeyed(stats.changesByClassification()),
                new Remediation(remediation.validatedRuns(), remediation.firstPassRuns(),
                        remediation.repairAttempts(), remediation.repairedRuns(),
                        remediation.averageValidationMillis(), remediation.linesAdded(),
                        remediation.linesRemoved(), remediation.filesTouched()));
    }

    private static Map<String, Integer> stringKeyed(Map<? extends Enum<?>, Integer> counts) {
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((key, value) -> result.put(key.name(), value));
        return result;
    }
}
