package com.paymentgateway.gateway;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisVelocityGate {
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = RedisScript.of("""
            local now_parts = redis.call('TIME')
            local now_ms = now_parts[1] * 1000 + math.floor(now_parts[2] / 1000)
            local window_ms = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms - window_ms)
            redis.call('ZADD', KEYS[1], now_ms, ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]))
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final long windowMillis;
    private final long expiryMillis;
    private final long suspiciousThreshold;
    private final long blockThreshold;

    public RedisVelocityGate(
            ReactiveStringRedisTemplate redis,
            @Value("${gateway.velocity.window-ms:1000}") long windowMillis,
            @Value("${gateway.velocity.suspicious-threshold:10}") long suspiciousThreshold,
            @Value("${gateway.velocity.block-threshold:30}") long blockThreshold) {
        if (windowMillis < 1 || suspiciousThreshold < 1 || blockThreshold <= suspiciousThreshold) {
            throw new IllegalArgumentException("Invalid gateway velocity thresholds");
        }
        this.redis = redis;
        this.windowMillis = windowMillis;
        this.expiryMillis = windowMillis * 2;
        this.suspiciousThreshold = suspiciousThreshold;
        this.blockThreshold = blockThreshold;
    }

    public Mono<VelocityDecision> inspect(String merchantId, String sourceIp, String transactionId) {
        String key = "gateway:velocity:" + merchantId + ":" + sourceIp;
        return redis.execute(
                        SLIDING_WINDOW_SCRIPT,
                        List.of(key),
                        transactionId,
                        Long.toString(windowMillis),
                        Long.toString(expiryMillis))
                .next()
                .map(count -> new VelocityDecision(
                        count,
                        count >= suspiciousThreshold,
                        count >= blockThreshold));
    }
}
