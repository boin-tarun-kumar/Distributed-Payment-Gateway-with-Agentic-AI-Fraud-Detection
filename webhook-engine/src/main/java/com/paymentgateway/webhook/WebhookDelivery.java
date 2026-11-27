package com.paymentgateway.webhook;

import java.util.UUID;

record WebhookDelivery(
        UUID deliveryId,
        UUID subscriptionId,
        String merchantId,
        String endpointUrl,
        String signingSecret,
        String eventId,
        String eventType,
        String payload,
        int attempts) {
}
