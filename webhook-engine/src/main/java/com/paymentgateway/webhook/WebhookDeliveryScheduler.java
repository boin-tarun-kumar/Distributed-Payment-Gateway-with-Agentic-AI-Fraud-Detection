package com.paymentgateway.webhook;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class WebhookDeliveryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);
    private static final String DEAD_LETTER_TOPIC = "webhook.dlq";

    private final WebhookDeliveryRepository repository;
    private final WebhookHttpClient httpClient;
    private final WebhookRetryPolicy retryPolicy;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final int batchSize;
    private final int concurrency;

    public WebhookDeliveryScheduler(
            WebhookDeliveryRepository repository,
            WebhookHttpClient httpClient,
            WebhookRetryPolicy retryPolicy,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${webhook.scheduler.batch-size:200}") int batchSize,
            @Value("${webhook.scheduler.concurrency:64}") int concurrency) {
        if (batchSize < 1 || concurrency < 1) {
            throw new IllegalArgumentException("Webhook scheduler limits must be positive");
        }
        this.repository = repository;
        this.httpClient = httpClient;
        this.retryPolicy = retryPolicy;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.concurrency = concurrency;
    }

    @Scheduled(fixedDelayString = "${webhook.scheduler.poll-ms:250}")
    public void dispatchDueDeliveries() {
        List<WebhookDelivery> deliveries = repository.claimDue(batchSize);
        Flux.fromIterable(deliveries)
                .flatMap(this::deliver, concurrency)
                .doOnError(exception -> LOGGER.error("Webhook delivery batch failed", exception))
                .subscribe();

        List<WebhookDeadLetter> deadLetters = repository.claimDeadLetters(batchSize);
        Flux.fromIterable(deadLetters)
                .flatMap(this::publishDeadLetter, concurrency)
                .doOnError(exception -> LOGGER.error("Webhook DLQ publication batch failed", exception))
                .subscribe();
    }

    private Mono<Void> deliver(WebhookDelivery delivery) {
        return httpClient
                .deliver(delivery)
                .then(Mono.fromRunnable(() -> repository.markDelivered(delivery.deliveryId(), delivery.attempts() + 1))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(exception -> recordFailure(delivery, exception));
    }

    private Mono<Void> recordFailure(WebhookDelivery delivery, Throwable exception) {
        int attemptNumber = delivery.attempts() + 1;
        WebhookRetryPolicy.RetryPlan retryPlan = retryPolicy.afterFailure(attemptNumber);
        String failureCode = exception.getClass().getSimpleName();
        LOGGER.warn(
                "Webhook delivery {} attempt {} failed with {}",
                delivery.deliveryId(),
                attemptNumber,
                failureCode);
        return Mono.fromRunnable(() -> repository.recordFailure(
                        delivery, attemptNumber, retryPlan, failureCode))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> publishDeadLetter(WebhookDeadLetter deadLetter) {
        return Mono.defer(() -> Mono.fromFuture(kafkaTemplate.send(
                        DEAD_LETTER_TOPIC, deadLetter.deliveryId().toString(), deadLetter)))
                .then(Mono.fromRunnable(() -> repository.markDeadLetterPublished(deadLetter.deliveryId()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(exception -> {
                    LOGGER.warn(
                            "Webhook DLQ publication failed for {} with {}",
                            deadLetter.deliveryId(),
                            exception.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
