package com.paymentgateway.webhook;

import java.util.UUID;

record WebhookSubscription(UUID subscriptionId, String merchantId, String endpointUrl, String signingSecret) {
}
