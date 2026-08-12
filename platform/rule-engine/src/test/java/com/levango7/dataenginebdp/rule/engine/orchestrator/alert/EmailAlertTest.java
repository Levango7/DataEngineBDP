package com.levango7.dataenginebdp.rule.engine.orchestrator.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EmailAlert} 单元测试。
 */
class EmailAlertTest {

    @Test
    void disabled_shouldReturnFalse() {
        EmailAlert alert = new EmailAlert(false, "from@x.com", "");
        AlertEvent event = AlertEvent.builder().id("e1").build();
        assertFalse(alert.send(event));
    }

    @Test
    void enabled_shouldReturnTrueAndLog() {
        EmailAlert alert = new EmailAlert(true, "from@x.com", "to1@x.com,to2@x.com");
        AlertEvent event = AlertEvent.builder()
                .id("e1")
                .type("TASK_FAILED")
                .level("ERROR")
                .title("test")
                .message("msg")
                .build();
        assertTrue(alert.send(event));
        assertTrue(alert.getRecipients().contains("to1@x.com"));
    }

    @Test
    void recipientsParsing_shouldHandleEmptyAndSpaces() {
        EmailAlert alert = new EmailAlert(true, "from@x.com", " a@x.com , ,b@x.com ");
        assertTrue(alert.getRecipients().contains("a@x.com"));
        assertTrue(alert.getRecipients().contains("b@x.com"));
        assertEqualsSize(2, alert.getRecipients().size());
    }

    private void assertEqualsSize(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }
}