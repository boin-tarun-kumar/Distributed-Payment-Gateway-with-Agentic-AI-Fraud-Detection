package com.paymentgateway.webhook;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class WebhookRetryPolicy {
    public static final int MAX_ATTEMPTS = 7;

    public RetryPlan afterFailure(int completedAttempts) {
        if (completedAttempts < 1 || completedAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Attempt count must be between 1 and 7");
        }
        if (completedAttempts == MAX_ATTEMPTS) {
            return new RetryPlan(true, Duration.ZERO);
        }
        return new RetryPlan(false, Duration.ofSeconds(1L << (completedAttempts - 1)));
    }

    public record RetryPlan(boolean deadLetter, Duration delay) {
    }
}
