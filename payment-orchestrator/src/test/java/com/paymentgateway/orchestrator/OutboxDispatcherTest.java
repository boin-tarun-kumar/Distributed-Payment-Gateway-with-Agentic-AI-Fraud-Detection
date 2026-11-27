package com.paymentgateway.orchestrator;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxDispatcherTest {
    @Test
    @SuppressWarnings("unchecked")
    void marksOutboxMessagePublishedOnlyAfterKafkaAcknowledges() {
        OutboxRepository repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID messageId = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage(messageId, "payment.events", "tx-1", "{\"status\":\"SUCCEEDED\"}", 0);
        doReturn(List.of(message)).when(repository).lockPendingBatch(50);
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate)
                .send(anyString(), anyString(), anyString());

        new OutboxDispatcher(repository, kafkaTemplate).publishPending();

        verify(repository).markPublished(messageId);
    }
}
