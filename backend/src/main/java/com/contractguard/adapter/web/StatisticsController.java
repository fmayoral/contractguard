package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.StatisticsDtos;
import com.contractguard.application.service.RunStatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only dashboard statistics (FR-021): aggregates computed on demand from
 * the persisted run history — same pattern as {@link AuditController}, one
 * GET, no parameters, no mutation surface.
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final RunStatisticsService statistics;

    public StatisticsController(RunStatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping
    public StatisticsDtos.Statistics statistics() {
        return StatisticsDtos.from(statistics.statistics());
    }
}
