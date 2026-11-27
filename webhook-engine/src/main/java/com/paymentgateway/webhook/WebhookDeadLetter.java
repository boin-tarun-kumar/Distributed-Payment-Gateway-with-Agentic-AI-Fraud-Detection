package com.paymentgateway.webhook;

import java.util.UUID;

public record WebhookDeadLetter(
        UUID deliveryId,
        String merchantId,
        String eventId,
        String eventType,
        String endpointUrl,
        String payload,
        int attempts,
        String failureCode) {
}
