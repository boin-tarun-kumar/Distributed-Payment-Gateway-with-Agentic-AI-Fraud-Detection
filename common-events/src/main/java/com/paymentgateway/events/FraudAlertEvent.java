package com.paymentgateway.events;

public record FraudAlertEvent(
        String alertId,
        String transactionId,
        String merchantId,
        String cardToken,
        String decision,
        String aiSummary,
        boolean holdSettlement) {
}
