package com.paymentgateway.orchestrator;

import java.util.UUID;

record OutboxMessage(UUID id, String topic, String messageKey, String payload, int attempts) {
}
