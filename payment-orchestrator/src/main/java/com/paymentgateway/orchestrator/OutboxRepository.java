package com.paymentgateway.orchestrator;

import com.paymentgateway.events.PaymentEvent;
import com.paymentgateway.events.SagaCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueue(String topic, String messageKey, Object event) {
        String payload = toJson(event);
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO payment_outbox (id, topic, message_key, payload, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                topic,
                messageKey,
                payload,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public List<OutboxMessage> lockPendingBatch(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, topic, message_key, payload, attempts
                FROM payment_outbox
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new OutboxMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("topic"),
                        resultSet.getString("message_key"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts")),
                limit);
    }

    public void markPublished(UUID id) {
        jdbcTemplate.update(
                "UPDATE payment_outbox SET published_at = now() WHERE id = ? AND published_at IS NULL",
                id);
    }

    public void recordFailure(OutboxMessage message) {
        long backoffMillis = Math.min(60_000, 1_000L << Math.min(message.attempts(), 6));
        jdbcTemplate.update(
                """
                UPDATE payment_outbox
                SET attempts = attempts + 1, next_attempt_at = ?
                WHERE id = ? AND published_at IS NULL
                """,
                Timestamp.from(Instant.now().plusMillis(backoffMillis)),
                message.id());
    }

    private static String toJson(Object event) {
        StringBuilder json = new StringBuilder("{");
        if (event instanceof SagaCommand command) {
            appendString(json, "commandId", command.commandId());
            appendString(json, "transactionId", command.transactionId());
            appendString(json, "merchantId", command.merchantId());
            appendString(json, "idempotencyKey", command.idempotencyKey());
            appendString(json, "paymentMethod", command.paymentMethod());
            appendString(json, "cardToken", command.cardToken());
            appendString(json, "bankCode", command.bankCode());
            appendNumber(json, "amount", command.amount() == null ? null : command.amount().toPlainString());
            appendString(json, "currency", command.currency());
            appendString(json, "action", command.action());
            appendString(json, "timestamp", command.timestamp() == null ? null : command.timestamp().toString());
        } else if (event instanceof PaymentEvent payment) {
            appendString(json, "transactionId", payment.transactionId());
            appendString(json, "merchantId", payment.merchantId());
            appendString(json, "paymentMethod", payment.paymentMethod());
            appendString(json, "cardToken", payment.cardToken());
            appendNumber(json, "amount", payment.amount() == null ? null : payment.amount().toPlainString());
            appendString(json, "currency", payment.currency());
            appendString(json, "status", payment.status());
            appendString(json, "ipAddress", payment.ipAddress());
            appendString(json, "timestamp", payment.timestamp() == null ? null : payment.timestamp().toString());
        } else {
            throw new IllegalArgumentException("Unsupported outbox event type");
        }
        return json.append('}').toString();
    }

    private static void appendString(StringBuilder json, String name, String value) {
        appendPrefix(json, name);
        appendQuoted(json, value);
    }

    private static void appendNumber(StringBuilder json, String name, String value) {
        appendPrefix(json, name);
        json.append(value == null ? "null" : value);
    }

    private static void appendPrefix(StringBuilder json, String name) {
        if (json.length() > 1) {
            json.append(',');
        }
        appendQuoted(json, name);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(character);
                        json.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
