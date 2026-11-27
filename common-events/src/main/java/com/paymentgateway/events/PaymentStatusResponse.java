package com.paymentgateway.events;

public record PaymentStatusResponse(
        String transactionId,
        String status,
        String reason) {
}
