package com.contractguard.config;

import com.contractguard.adapter.notification.NoOpNotificationPort;
import com.contractguard.adapter.notification.WebhookNotificationAdapter;
import com.contractguard.application.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires outbound run notifications (FR-031): a webhook adapter when
 * {@code contractguard.notifications.webhook-url} is set, otherwise a no-op — the same opt-in,
 * off-by-default shape as {@link ExecutionConfiguration}'s sandboxed-validation choice.
 */
@Configuration
public class NotificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

    @Bean
    public NotificationPort notificationPort(ContractGuardProperties properties) {
        ContractGuardProperties.Notifications notifications = properties.notifications();
        String webhookUrl = notifications == null ? null : notifications.webhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("Run notifications: disabled (no webhook URL configured)");
            return new NoOpNotificationPort();
        }
        log.info("Run notifications: webhook enabled");
        return new WebhookNotificationAdapter(webhookUrl, notifications.dashboardBaseUrl());
    }
}
