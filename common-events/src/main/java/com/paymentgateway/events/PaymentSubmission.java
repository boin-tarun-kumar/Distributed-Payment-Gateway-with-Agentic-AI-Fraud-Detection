package com.paymentgateway.events;

import java.math.BigDecimal;

public record PaymentSubmission(
        String transactionId,
        String merchantId,
        String idempotencyKey,
        String paymentMethod,
        String cardToken,
        String bankCode,
        BigDecimal amount,
        String currency,
        String ipAddress) {
}
