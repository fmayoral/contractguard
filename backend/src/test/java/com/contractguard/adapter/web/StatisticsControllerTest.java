package com.contractguard.adapter.web;

import com.contractguard.application.service.RunStatisticsService;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.RunState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatisticsController.class)
class StatisticsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RunStatisticsService statistics;

    @Test
    void serialisesAggregatesWithStringEnumKeysAndIsoDays() throws Exception {
        // LinkedHashMap so the most-frequent-first contract survives into JSON key order.
        Map<ChangeType, Integer> byType = new LinkedHashMap<>();
        byType.put(ChangeType.PROPERTY_RENAMED, 4);
        byType.put(ChangeType.ENDPOINT_ADDED, 1);
        when(statistics.statistics()).thenReturn(new RunStatisticsService.Statistics(
                3, 1,
                Map.of(RunState.SUCCEEDED, 2, RunState.AWAITING_APPROVAL, 1),
                List.of(new RunStatisticsService.DailyCount(LocalDate.parse("2026-07-23"), 3)),
                5, byType,
                Map.of(Classification.BREAKING, 5),
                new RunStatisticsService.Remediation(2, 1, 1, 1, 30_000, 12, 4, 3)));

        mvc.perform(get("/api/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(3))
                .andExpect(jsonPath("$.activeRuns").value(1))
                .andExpect(jsonPath("$.runsByState.SUCCEEDED").value(2))
                .andExpect(jsonPath("$.runsPerDay[0].day").value("2026-07-23"))
                .andExpect(jsonPath("$.runsPerDay[0].count").value(3))
                .andExpect(jsonPath("$.changesByType.PROPERTY_RENAMED").value(4))
                .andExpect(jsonPath("$.changesByClassification.BREAKING").value(5))
                .andExpect(jsonPath("$.remediation.firstPassRuns").value(1))
                .andExpect(jsonPath("$.remediation.linesAdded").value(12))
                .andExpect(jsonPath("$.remediation.filesTouched").value(3));
    }
}
