package com.paymentgateway.orchestrator;

import java.math.BigDecimal;

record PaymentTransaction(
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
