package com.paymentgateway.events;

import java.math.BigDecimal;

public record FraudInvestigateEvent(
        String transactionId,
        String merchantId,
        String cardToken,
        String binPrefix,
        String ipAddress,
        BigDecimal amount,
        double riskScore,
        String triggerReason) {
}
