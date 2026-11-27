package com.paymentgateway.orchestrator;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public OutboxDispatcher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.circuitBreaker = CircuitBreaker.of(
                "outboxKafka",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .build());
        this.retry = Retry.of(
                "outboxKafka",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(100))
                        .retryExceptions(Exception.class)
                        .build());
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> messages = outboxRepository.lockPendingBatch(50);
        for (OutboxMessage message : messages) {
            try {
                Callable<?> send = Retry.decorateCallable(
                        retry,
                        CircuitBreaker.decorateCallable(circuitBreaker, () -> kafkaTemplate
                                .send(message.topic(), message.messageKey(), message.payload())
                                .get(5, TimeUnit.SECONDS)));
                send.call();
                outboxRepository.markPublished(message.id());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                outboxRepository.recordFailure(message);
                LOGGER.warn("Outbox publication failed for message {}", message.id(), exception);
                if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                    break;
                }
            }
        }
    }
}
