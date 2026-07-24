package com.contractguard.adapter.notification;

import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpNotificationPortTest {

    @Test
    void doesNothingForAnyState() {
        NoOpNotificationPort port = new NoOpNotificationPort();

        assertThatCode(() -> port.notify(Fixtures.newRun(), RunState.AWAITING_APPROVAL)).doesNotThrowAnyException();
    }
}
