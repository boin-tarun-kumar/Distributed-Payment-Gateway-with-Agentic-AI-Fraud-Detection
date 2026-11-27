package com.paymentgateway.events;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentEvent(
        String transactionId,
        String merchantId,
        String paymentMethod,
        String cardToken,
        BigDecimal amount,
        String currency,
        String status,
        String ipAddress,
        Instant timestamp) {
}
