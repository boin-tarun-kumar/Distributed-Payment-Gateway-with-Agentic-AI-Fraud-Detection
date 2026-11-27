package com.paymentgateway.acquirer;

import com.paymentgateway.events.SagaCommand;
import com.paymentgateway.events.SagaReply;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class AcquirerAuthorizationService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration PROCESSING_LEASE = Duration.ofSeconds(60);

    private final ReactiveStringRedisTemplate redis;
    private final BankAuthorizationClient bankClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AcquirerAuthorizationService(
            ReactiveStringRedisTemplate redis,
            BankAuthorizationClient bankClient,
            CircuitBreaker acquirerCircuitBreaker,
            Retry acquirerRetry) {
        this.redis = redis;
        this.bankClient = bankClient;
        this.circuitBreaker = acquirerCircuitBreaker;
        this.retry = acquirerRetry;
    }

    public Mono<SagaReply> authorize(SagaCommand command) {
        if (command == null || command.transactionId() == null || command.transactionId().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid acquirer command"));
        }
        String redisKey = "acquirer:idempotency:" + command.transactionId();
        return redis.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING", PROCESSING_LEASE)
                .flatMap(acquired -> acquired
                        ? authorizeNew(command, redisKey)
                        : loadCachedReply(redisKey));
    }

    private Mono<SagaReply> authorizeNew(SagaCommand command, String redisKey) {
        if (!"AUTHORIZE".equals(command.action())) {
            return saveReply(redisKey, reply(command, "DECLINED", "UNSUPPORTED_ACTION", null));
        }

        return bankClient
                .authorize(command)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .map(reference -> reply(command, "APPROVED", null, reference))
                .onErrorResume(exception -> Mono.just(reply(
                        command,
                        "DECLINED",
                        exception instanceof BankUnavailableException
                                        || exception instanceof CallNotPermittedException
                                ? "ACQUIRER_UNAVAILABLE"
                                : "AUTHORIZATION_FAILED",
                        null)))
                .flatMap(result -> saveReply(redisKey, result));
    }

    private Mono<SagaReply> loadCachedReply(String redisKey) {
        return redis.opsForValue()
                .get(redisKey)
                .flatMap(value -> {
                    if ("PROCESSING".equals(value)) {
                        return Mono.empty();
                    }
                    try {
                        return Mono.just(decode(value));
                    } catch (RuntimeException exception) {
                        return Mono.error(new IllegalStateException("Invalid cached acquirer reply", exception));
                    }
                });
    }

    private Mono<SagaReply> saveReply(String redisKey, SagaReply reply) {
        String encoded = String.join(
                ".",
                encode(reply.commandId()),
                encode(reply.transactionId()),
                encode(reply.status()),
                encode(reply.reason()),
                encode(reply.acquirerReference()),
                encode(reply.timestamp() == null ? null : reply.timestamp().toString()));
        return redis.opsForValue().set(redisKey, encoded, IDEMPOTENCY_TTL).thenReturn(reply);
    }

    private static SagaReply decode(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid cached reply encoding");
        }
        String timestamp = decodePart(parts[5]);
        return new SagaReply(
                decodePart(parts[0]),
                decodePart(parts[1]),
                decodePart(parts[2]),
                decodePart(parts[3]),
                decodePart(parts[4]),
                timestamp == null ? null : Instant.parse(timestamp));
    }

    private static String encode(String value) {
        return value == null
                ? "~"
                : Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return "~".equals(value)
                ? null
                : new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static SagaReply reply(SagaCommand command, String status, String reason, String reference) {
        return new SagaReply(
                command.commandId(),
                command.transactionId(),
                status,
                reason,
                reference,
                Instant.now());
    }
}
