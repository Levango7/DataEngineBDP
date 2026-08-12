package com.levango7.dataenginebdp.rule.engine.orchestrator.alert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertManager} 单元测试。
 */
class AlertManagerTest {

    @Test
    void dispatch_shouldCallAllChannels() {
        RecordingChannel ch1 = new RecordingChannel("ch1", true);
        RecordingChannel ch2 = new RecordingChannel("ch2", true);
        AlertManager manager = new AlertManager(List.of(ch1, ch2));
        AlertEvent event = AlertEvent.builder().id("e1").type("TASK_FAILED").build();
        boolean result = manager.dispatch(event);
        assertTrue(result);
        assertTrue(ch1.received);
        assertTrue(ch2.received);
    }

    @Test
    void dispatch_partialFailure_shouldReturnTrueIfAnySuccess() {
        RecordingChannel ch1 = new RecordingChannel("ch1", false);
        RecordingChannel ch2 = new RecordingChannel("ch2", true);
        AlertManager manager = new AlertManager(List.of(ch1, ch2));
        AlertEvent event = AlertEvent.builder().id("e1").build();
        assertTrue(manager.dispatch(event));
    }

    @Test
    void dispatch_allFail_shouldReturnFalse() {
        RecordingChannel ch1 = new RecordingChannel("ch1", false);
        AlertManager manager = new AlertManager(List.of(ch1));
        AlertEvent event = AlertEvent.builder().id("e1").build();
        assertFalse(manager.dispatch(event));
    }

    @Test
    void dispatch_noChannels_shouldReturnFalse() {
        AlertManager manager = new AlertManager(List.of());
        AlertEvent event = AlertEvent.builder().id("e1").build();
        assertFalse(manager.dispatch(event));
    }

    @Test
    void dispatch_nullEvent_shouldReturnFalse() {
        AlertManager manager = new AlertManager(List.of(new RecordingChannel("ch", true)));
        assertFalse(manager.dispatch(null));
    }

    /** 录制型通道，用于测试 */
    static class RecordingChannel implements AlertChannel {
        final String name;
        final boolean success;
        boolean received;

        RecordingChannel(String name, boolean success) {
            this.name = name;
            this.success = success;
        }

        @Override
        public boolean send(AlertEvent event) {
            received = true;
            return success;
        }

        @Override
        public String name() {
            return name;
        }
    }
}