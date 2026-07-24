package com.contractguard.adapter.notification;

import com.contractguard.application.port.NotificationPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RunState;

/** Selected when no webhook URL is configured — outbound notifications are opt-in (FR-031). */
public class NoOpNotificationPort implements NotificationPort {

    @Override
    public void notify(AnalysisRun run, RunState state) {
        // Intentionally a no-op: this is the selected implementation exactly when
        // notifications are disabled (no webhook URL configured).
    }
}
