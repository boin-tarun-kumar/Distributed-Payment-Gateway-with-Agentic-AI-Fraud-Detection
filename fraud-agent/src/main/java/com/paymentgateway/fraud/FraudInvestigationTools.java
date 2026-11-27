package com.paymentgateway.fraud;

import com.paymentgateway.events.FraudAlertEvent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class FraudInvestigationTools {
    private static final DefaultRedisScript<Long> VELOCITY_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now_parts = redis.call('TIME')
            local now_ms = now_parts[1] * 1000 + math.floor(now_parts[2] / 1000)
            return redis.call('ZCOUNT', KEYS[1], now_ms - tonumber(ARGV[1]), now_ms)
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient settlementClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public FraudInvestigationTools(
            StringRedisTemplate redis,
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${settlement-service.base-url:http://localhost:8086}") String settlementUrl) {
        this.redis = redis;
        this.jdbcTemplate = jdbcTemplate;
        this.settlementClient = webClientBuilder.baseUrl(settlementUrl).build();
        this.kafkaTemplate = kafkaTemplate;
    }

    @Tool("Read the number of payment attempts for a merchant and source IP in a Redis sliding window.")
    public VelocityWindowEvidence queryRedisVelocityWindow(
            @P("Merchant identifier") String merchantId,
            @P("Source IP address") String ipAddress,
            @P("Window size in seconds") int windowSeconds) {
        if (merchantId == null || merchantId.isBlank() || ipAddress == null || ipAddress.isBlank()
                || windowSeconds < 1 || windowSeconds > 3600) {
            throw new IllegalArgumentException("Invalid velocity lookup");
        }
        String redisKey = "gateway:velocity:" + merchantId + ":" + ipAddress;
        Long attempts = redis.execute(
                        VELOCITY_WINDOW_SCRIPT,
                        List.of(redisKey),
                        Long.toString(Duration.ofSeconds(windowSeconds).toMillis()));
        if (attempts == null) {
            throw new IllegalStateException("Redis returned no velocity count");
        }
        return new VelocityWindowEvidence(attempts, windowSeconds);
    }

    @Tool("Inspect recent transaction-history aggregates for a tokenized card; never return token values.")
    public TokenHistoryEvidence inspectTokenizedTransactionHistory(
            @P("Irreversible card token") String cardToken) {
        if (cardToken == null || !cardToken.matches("ct_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("A valid tokenized card identifier is required");
        }
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS transactions,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS declines,
                       COUNT(DISTINCT merchant_id) AS merchants
                FROM payment_transactions
                WHERE card_token = ? AND created_at >= now() - interval '30 days'
                """,
                (resultSet, rowNumber) -> new TokenHistoryEvidence(
                        resultSet.getLong("transactions"),
                        resultSet.getLong("declines"),
                        resultSet.getLong("merchants")),
                cardToken);
    }

    @Tool("Detect a coordinated BIN attack using distinct tokenized cards across recent investigations.")
    public BinAttackEvidence detectCoordinatedBinAttack(
            @P("Six-digit BIN prefix; may be unavailable") String binPrefix) {
        if (binPrefix == null || binPrefix.isBlank()) {
            return new BinAttackEvidence(false, 0, 0);
        }
        if (!binPrefix.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("BIN prefix must contain six digits");
        }
        BinAttackEvidence evidence = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT card_token) AS distinct_cards,
                       COUNT(DISTINCT merchant_id) AS merchants
                FROM fraud_investigations
                WHERE bin_prefix = ? AND detected_at >= now() - interval '10 minutes'
                """,
                (resultSet, rowNumber) -> new BinAttackEvidence(
                        resultSet.getLong("distinct_cards") >= 10
                                && resultSet.getLong("merchants") >= 3,
                        resultSet.getLong("distinct_cards"),
                        resultSet.getLong("merchants")),
                binPrefix);
        if (evidence == null) {
            throw new IllegalStateException("PostgreSQL returned no BIN investigation result");
        }
        return evidence;
    }

    @Tool("Place an idempotent payout hold on a merchant after a high-risk fraud decision.")
    public String placeSettlementPayoutHold(
            @P("Merchant identifier") String merchantId,
            @P("Stable fraud alert identifier") String alertId,
            @P("Explainable hold reason") String reason) {
        return settlementClient
                .post()
                .uri("/api/v1/settlements/holds")
                .bodyValue(new SettlementHoldRequest(merchantId, alertId, reason))
                .retrieve()
                .bodyToMono(String.class)
                .blockOptional(Duration.ofSeconds(5))
                .orElseThrow(() -> new IllegalStateException("Settlement service returned no hold confirmation"));
    }

    @Tool("Publish an explainable fraud alert containing only tokenized event data.")
    public void emitFraudRiskAlert(
            @P("Stable alert identifier") String alertId,
            @P("Payment transaction identifier") String transactionId,
            @P("Merchant identifier") String merchantId,
            @P("Irreversible card token") String cardToken,
            @P("Decision: ALLOW, REVIEW, or HOLD") String decision,
            @P("Explainable evidence summary") String aiSummary,
            @P("Whether settlement is held") boolean holdSettlement) {
        if (cardToken == null || !cardToken.matches("ct_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Fraud alerts require a tokenized card");
        }
        FraudAlertEvent alert = new FraudAlertEvent(
                alertId,
                transactionId,
                merchantId,
                cardToken,
                decision,
                aiSummary,
                holdSettlement);
        try {
            kafkaTemplate.send("fraud.alerts", transactionId, alert).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fraud alert publication interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Fraud alert publication failed", exception);
        }
    }

    public void recordInvestigation(
            String transactionId,
            String merchantId,
            String cardToken,
            String binPrefix,
            String ipAddress,
            double riskScore) {
        jdbcTemplate.update(
                """
                INSERT INTO fraud_investigations (
                    transaction_id, merchant_id, card_token, bin_prefix, source_ip, risk_score, detected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                transactionId,
                merchantId,
                cardToken,
                binPrefix,
                ipAddress,
                riskScore,
                Timestamp.from(Instant.now()));
    }
}
