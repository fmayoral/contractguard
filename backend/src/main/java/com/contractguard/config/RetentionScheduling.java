package com.contractguard.config;

import com.contractguard.application.service.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/** Runs the retention purge shortly after startup and daily thereafter (FR-023). */
@Configuration
@EnableScheduling
public class RetentionScheduling {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduling.class);

    private final RetentionService retention;

    public RetentionScheduling(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT24H")
    public void purge() {
        List<String> deleted = retention.purgeExpired();
        if (!deleted.isEmpty()) {
            log.info("retention purged {} finished run(s)", deleted.size());
        }
    }
}
