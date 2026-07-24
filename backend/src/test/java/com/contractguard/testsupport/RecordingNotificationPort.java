package com.contractguard.testsupport;

import com.contractguard.application.port.NotificationPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RunState;

import java.util.ArrayList;
import java.util.List;

/** Captures every {@code notify} call instead of sending anything, for assertions. */
public class RecordingNotificationPort implements NotificationPort {

    public record Notification(String runId, RunState state) {
    }

    private final List<Notification> notifications = new ArrayList<>();

    @Override
    public void notify(AnalysisRun run, RunState state) {
        notifications.add(new Notification(run.id(), state));
    }

    public List<Notification> notifications() {
        return notifications;
    }
}
