package com.paymentgateway.events;

import java.math.BigDecimal;
import java.time.Instant;

public record SagaCommand(
        String commandId,
        String transactionId,
        String merchantId,
        String idempotencyKey,
        String paymentMethod,
        String cardToken,
        String bankCode,
        BigDecimal amount,
        String currency,
        String action,
        Instant timestamp) {
}
