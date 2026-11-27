package com.paymentgateway.events;

import java.time.Instant;

public record SagaReply(
        String commandId,
        String transactionId,
        String status,
        String reason,
        String acquirerReference,
        Instant timestamp) {
}
