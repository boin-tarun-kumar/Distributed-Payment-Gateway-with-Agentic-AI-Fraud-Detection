package com.paymentgateway.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookRetryPolicyTest {
    private final WebhookRetryPolicy policy = new WebhookRetryPolicy();

    @Test
    void schedulesSixExponentialRetriesThenDeadLettersSeventhFailure() {
        for (int attempt = 1; attempt < WebhookRetryPolicy.MAX_ATTEMPTS; attempt++) {
            WebhookRetryPolicy.RetryPlan plan = policy.afterFailure(attempt);

            assertFalse(plan.deadLetter());
            assertEquals(Duration.ofSeconds(1L << (attempt - 1)), plan.delay());
        }

        WebhookRetryPolicy.RetryPlan finalFailure = policy.afterFailure(WebhookRetryPolicy.MAX_ATTEMPTS);
        assertTrue(finalFailure.deadLetter());
        assertEquals(Duration.ZERO, finalFailure.delay());
    }

    @Test
    void rejectsAttemptNumbersOutsideTheDeliveryPolicy() {
        assertThrows(IllegalArgumentException.class, () -> policy.afterFailure(0));
        assertThrows(IllegalArgumentException.class, () -> policy.afterFailure(8));
    }
}
