package com.paymentgateway.webhook;

import com.paymentgateway.events.FraudAlertEvent;
import com.paymentgateway.events.PaymentEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WebhookDeliveryRepository {
    private final JdbcTemplate jdbcTemplate;

    public WebhookDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void enqueue(
            String merchantId,
            String eventId,
            String eventType,
            String payload) {
        List<WebhookSubscription> subscriptions = jdbcTemplate.query(
                """
                SELECT subscription_id, merchant_id, endpoint_url, signing_secret
                FROM webhook_subscriptions
                WHERE merchant_id = ? AND active = TRUE
                """,
                (resultSet, rowNumber) -> new WebhookSubscription(
                        resultSet.getObject("subscription_id", UUID.class),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("endpoint_url"),
                        resultSet.getString("signing_secret")),
                merchantId);
        Instant now = Instant.now();
        for (WebhookSubscription subscription : subscriptions) {
            jdbcTemplate.update(
                    """
                    INSERT INTO webhook_deliveries (
                        delivery_id, subscription_id, merchant_id, event_id, event_type, payload,
                        status, attempts, created_at, next_attempt_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    ON CONFLICT (subscription_id, event_id) DO NOTHING
                    """,
                    UUID.randomUUID(),
                    subscription.subscriptionId(),
                    merchantId,
                    eventId,
                    eventType,
                    payload,
                    Timestamp.from(now),
                    Timestamp.from(now));
        }
    }

    public void enqueue(PaymentEvent event, String payload) {
        enqueue(event.merchantId(), event.transactionId(), "payment.event", payload);
    }

    public void enqueue(FraudAlertEvent event, String payload) {
        enqueue(event.merchantId(), event.alertId(), "fraud.alert", payload);
    }

    @Transactional
    public List<WebhookDelivery> claimDue(int limit) {
        jdbcTemplate.update("""
                UPDATE webhook_deliveries
                SET status = 'PENDING', lease_until = NULL
                WHERE status = 'DELIVERING' AND lease_until < now()
                """);
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT d.delivery_id
                    FROM webhook_deliveries d
                    JOIN webhook_subscriptions s ON s.subscription_id = d.subscription_id
                    WHERE d.status = 'PENDING'
                      AND d.next_attempt_at <= now()
                      AND s.active = TRUE
                    ORDER BY d.next_attempt_at, d.created_at
                    LIMIT ?
                    FOR UPDATE OF d SKIP LOCKED
                ),
                claimed AS (
                    UPDATE webhook_deliveries d
                    SET status = 'DELIVERING', lease_until = now() + interval '60 seconds'
                    FROM candidates c
                    WHERE d.delivery_id = c.delivery_id
                    RETURNING d.delivery_id, d.subscription_id, d.merchant_id, d.event_id,
                              d.event_type, d.payload, d.attempts
                )
                SELECT c.delivery_id, c.subscription_id, c.merchant_id, s.endpoint_url,
                       s.signing_secret, c.event_id, c.event_type, c.payload, c.attempts
                FROM claimed c
                JOIN webhook_subscriptions s ON s.subscription_id = c.subscription_id
                """,
                (resultSet, rowNumber) -> new WebhookDelivery(
                        resultSet.getObject("delivery_id", UUID.class),
                        resultSet.getObject("subscription_id", UUID.class),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("endpoint_url"),
                        resultSet.getString("signing_secret"),
                        resultSet.getString("event_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts")),
                limit);
    }

    @Transactional
    public void markDelivered(UUID deliveryId, int attempts) {
        int updated = jdbcTemplate.update(
                """
                UPDATE webhook_deliveries
                SET status = 'DELIVERED', attempts = ?, delivered_at = now(), lease_until = NULL
                WHERE delivery_id = ? AND status = 'DELIVERING'
                """,
                attempts,
                deliveryId);
        if (updated != 1) {
            throw new IllegalStateException("Webhook delivery lease was lost");
        }
    }

    @Transactional
    public void recordFailure(
            WebhookDelivery delivery,
            int completedAttempts,
            WebhookRetryPolicy.RetryPlan retryPlan,
            String failureCode) {
        if (retryPlan.deadLetter()) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE webhook_deliveries
                    SET status = 'DLQ', attempts = ?, last_failure_code = ?, lease_until = NULL
                    WHERE delivery_id = ? AND status = 'DELIVERING'
                    """,
                    completedAttempts,
                    failureCode,
                    delivery.deliveryId());
            if (updated != 1) {
                throw new IllegalStateException("Webhook delivery lease was lost");
            }
            jdbcTemplate.update(
                    """
                    INSERT INTO webhook_dead_letters (
                        delivery_id, merchant_id, event_id, event_type, endpoint_url, payload,
                        attempts, failure_code, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (delivery_id) DO NOTHING
                    """,
                    delivery.deliveryId(),
                    delivery.merchantId(),
                    delivery.eventId(),
                    delivery.eventType(),
                    delivery.endpointUrl(),
                    delivery.payload(),
                    completedAttempts,
                    failureCode,
                    Timestamp.from(Instant.now()));
            return;
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE webhook_deliveries
                SET status = 'PENDING', attempts = ?, next_attempt_at = ?,
                    last_failure_code = ?, lease_until = NULL
                WHERE delivery_id = ? AND status = 'DELIVERING'
                """,
                completedAttempts,
                Timestamp.from(Instant.now().plus(retryPlan.delay())),
                failureCode,
                delivery.deliveryId());
        if (updated != 1) {
            throw new IllegalStateException("Webhook delivery lease was lost");
        }
    }

    @Transactional
    public List<WebhookDeadLetter> claimDeadLetters(int limit) {
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT delivery_id
                    FROM webhook_dead_letters
                    WHERE published_at IS NULL
                      AND (lease_until IS NULL OR lease_until < now())
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                claimed AS (
                    UPDATE webhook_dead_letters d
                    SET lease_until = now() + interval '60 seconds',
                        publish_attempts = publish_attempts + 1
                    FROM candidates c
                    WHERE d.delivery_id = c.delivery_id
                    RETURNING d.delivery_id, d.merchant_id, d.event_id, d.event_type,
                              d.endpoint_url, d.payload, d.attempts, d.failure_code
                )
                SELECT * FROM claimed
                """,
                (resultSet, rowNumber) -> new WebhookDeadLetter(
                        resultSet.getObject("delivery_id", UUID.class),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("event_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("endpoint_url"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts"),
                        resultSet.getString("failure_code")),
                limit);
    }

    @Transactional
    public void markDeadLetterPublished(UUID deliveryId) {
        jdbcTemplate.update(
                """
                UPDATE webhook_dead_letters
                SET published_at = now(), lease_until = NULL
                WHERE delivery_id = ? AND published_at IS NULL
                """,
                deliveryId);
    }
}
