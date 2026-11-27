package com.paymentgateway.gateway;

import com.paymentgateway.events.FraudInvestigateEvent;
import com.paymentgateway.events.PaymentStatusResponse;
import com.paymentgateway.events.PaymentSubmission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class PaymentGatewayService {
    private static final String FRAUD_TOPIC = "fraud.investigate";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final ReactiveStringRedisTemplate redis;
    private final RedisVelocityGate velocityGate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final WebClient orchestratorClient;
    private final long blockThreshold;

    public PaymentGatewayService(
            ReactiveStringRedisTemplate redis,
            RedisVelocityGate velocityGate,
            KafkaTemplate<String, Object> kafkaTemplate,
            WebClient.Builder webClientBuilder,
            @Value("${payment-orchestrator.base-url:http://localhost:8082}") String orchestratorUrl,
            @Value("${gateway.velocity.block-threshold:30}") long blockThreshold) {
        this.redis = redis;
        this.velocityGate = velocityGate;
        this.kafkaTemplate = kafkaTemplate;
        this.orchestratorClient = webClientBuilder.baseUrl(orchestratorUrl).build();
        this.blockThreshold = blockThreshold;
    }

    public Mono<PaymentStatusResponse> submit(
            CreatePaymentRequest request,
            String idempotencyKey,
            String paymentMethod,
            String sourceIp) {
        validate(request, idempotencyKey, paymentMethod);

        String transactionId = UUID.randomUUID().toString();
        String redisKey = "gateway:idempotency:" + request.merchantId() + ":" + idempotencyKey;
        PaymentSubmission submission = new PaymentSubmission(
                transactionId,
                request.merchantId(),
                idempotencyKey,
                paymentMethod,
                request.cardToken(),
                request.bankCode(),
                request.amount(),
                request.currency().toUpperCase(),
                sourceIp);

        return redis.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING:" + transactionId, IDEMPOTENCY_TTL)
                .flatMap(created -> created
                        ? processNewPayment(submission, redisKey)
                                .onErrorResume(exception -> redis.delete(redisKey).then(Mono.error(exception)))
                        : readIdempotentResult(redisKey))
                .onErrorMap(exception -> exception instanceof ResponseStatusException
                        ? exception
                        : new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway dependency unavailable", exception));
    }

    private Mono<PaymentStatusResponse> processNewPayment(PaymentSubmission submission, String redisKey) {
        return velocityGate.inspect(submission.merchantId(), submission.ipAddress(), submission.transactionId())
                .flatMap(decision -> {
                    if (decision.blocked()) {
                        return Mono.just(new PaymentStatusResponse(
                                submission.transactionId(), "BLOCKED", "Payment velocity limit exceeded"));
                    }
                    Mono<Void> riskSignal = decision.suspicious() && submission.cardToken() != null
                            ? publishFraudInvestigation(submission, decision.attemptsInWindow())
                            : Mono.empty();
                    return riskSignal.then(orchestratorClient.post()
                            .uri("/api/v1/orchestrations")
                            .bodyValue(submission)
                            .retrieve()
                            .bodyToMono(PaymentStatusResponse.class));
                })
                .flatMap(response -> redis.opsForValue()
                        .set(redisKey, serialize(response), IDEMPOTENCY_TTL)
                        .thenReturn(response));
    }

    private Mono<Void> publishFraudInvestigation(PaymentSubmission submission, long attempts) {
        double riskScore = Math.min(1.0, attempts / (double) blockThreshold);
        FraudInvestigateEvent event = new FraudInvestigateEvent(
                submission.transactionId(),
                submission.merchantId(),
                submission.cardToken(),
                null,
                submission.ipAddress(),
                submission.amount(),
                riskScore,
                "ELEVATED_PAYMENT_VELOCITY");
        return Mono.fromFuture(kafkaTemplate.send(FRAUD_TOPIC, submission.transactionId(), event)).then();
    }

    private Mono<PaymentStatusResponse> readIdempotentResult(String redisKey) {
        return redis.opsForValue()
                .get(redisKey)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency record expired; use a new key")))
                .map(value -> {
                    if (value.startsWith("PROCESSING:")) {
                        return new PaymentStatusResponse(value.substring("PROCESSING:".length()), "PROCESSING", null);
                    }
                    return deserialize(value);
                });
    }

    private String serialize(PaymentStatusResponse response) {
        return "RESPONSE|"
                + encode(response.transactionId()) + "|"
                + encode(response.status()) + "|"
                + encode(response.reason());
    }

    private static PaymentStatusResponse deserialize(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4 || !"RESPONSE".equals(parts[0])) {
            throw new IllegalStateException("Invalid cached idempotency response");
        }
        return new PaymentStatusResponse(decode(parts[1]), decode(parts[2]), decode(parts[3]));
    }

    private static String encode(String value) {
        return value == null
                ? "~"
                : Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return "~".equals(value)
                ? null
                : new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void validate(CreatePaymentRequest request, String idempotencyKey, String method) {
        if (request == null
                || request.merchantId() == null
                || !request.merchantId().matches("[A-Za-z0-9._-]{1,128}")
                || request.amount() == null
                || request.amount().compareTo(BigDecimal.ZERO) <= 0
                || request.amount().precision() > 19
                || request.amount().scale() > 4
                || request.currency() == null
                || !request.currency().matches("[A-Za-z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment details");
        }
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
        }
        if ("CARD".equals(method) && (request.cardToken() == null || request.cardToken().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cardToken is required");
        }
        if ("NET_BANKING".equals(method) && (request.bankCode() == null || request.bankCode().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bankCode is required");
        }
    }
}
