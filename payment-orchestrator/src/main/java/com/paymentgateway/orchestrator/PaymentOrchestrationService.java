package com.paymentgateway.orchestrator;

import com.paymentgateway.events.PaymentEvent;
import com.paymentgateway.events.PaymentStatusResponse;
import com.paymentgateway.events.PaymentSubmission;
import com.paymentgateway.events.SagaCommand;
import com.paymentgateway.events.SagaReply;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentOrchestrationService {
    private static final String COMMAND_TOPIC = "saga.commands";
    private static final String PAYMENT_TOPIC = "payment.events";

    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;

    public PaymentOrchestrationService(JdbcTemplate jdbcTemplate, OutboxRepository outboxRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public PaymentStatusResponse initiate(PaymentSubmission submission) {
        validate(submission);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    transaction_id, merchant_id, idempotency_key, payment_method, card_token, bank_code,
                    amount, currency, ip_address, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                """,
                submission.transactionId(),
                submission.merchantId(),
                submission.idempotencyKey(),
                submission.paymentMethod(),
                submission.cardToken(),
                submission.bankCode(),
                submission.amount(),
                submission.currency(),
                submission.ipAddress(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

        if (inserted == 0) {
            return existingPayment(submission.merchantId(), submission.idempotencyKey());
        }

        SagaCommand command = new SagaCommand(
                UUID.randomUUID().toString(),
                submission.transactionId(),
                submission.merchantId(),
                submission.idempotencyKey(),
                submission.paymentMethod(),
                submission.cardToken(),
                submission.bankCode(),
                submission.amount(),
                submission.currency(),
                "AUTHORIZE",
                Instant.now());
        outboxRepository.enqueue(COMMAND_TOPIC, submission.transactionId(), command);
        return new PaymentStatusResponse(submission.transactionId(), "PROCESSING", null);
    }

    @Transactional
    public void handleReply(SagaReply reply) {
        if ("PROCESSING".equals(reply.status())) {
            return;
        }
        String status = "APPROVED".equals(reply.status()) ? "SUCCEEDED" : "FAILED";
        int updated = jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET status = ?, failure_reason = ?, updated_at = ?
                WHERE transaction_id = ? AND status = 'PROCESSING'
                """,
                status,
                "SUCCEEDED".equals(status) ? null : reply.reason(),
                Timestamp.from(Instant.now()),
                reply.transactionId());
        if (updated == 0) {
            return;
        }

        PaymentTransaction transaction = jdbcTemplate.queryForObject(
                """
                SELECT transaction_id, merchant_id, idempotency_key, payment_method, card_token, bank_code,
                       amount, currency, ip_address
                FROM payment_transactions WHERE transaction_id = ?
                """,
                (resultSet, rowNumber) -> new PaymentTransaction(
                        resultSet.getString("transaction_id"),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("payment_method"),
                        resultSet.getString("card_token"),
                        resultSet.getString("bank_code"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("ip_address")),
                reply.transactionId());
        PaymentEvent paymentEvent = new PaymentEvent(
                transaction.transactionId(),
                transaction.merchantId(),
                transaction.paymentMethod(),
                transaction.cardToken(),
                transaction.amount(),
                transaction.currency(),
                status,
                transaction.ipAddress(),
                Instant.now());
        outboxRepository.enqueue(PAYMENT_TOPIC, transaction.transactionId(), paymentEvent);
    }

    @Scheduled(fixedDelayString = "${payment.saga.recovery-poll-ms:15000}")
    @Transactional
    public void recoverStalledTransactions() {
        var stalled = jdbcTemplate.query(
                """
                SELECT transaction_id, merchant_id, idempotency_key, payment_method, card_token,
                       bank_code, amount, currency, ip_address
                FROM payment_transactions
                WHERE status = 'PROCESSING' AND updated_at < now() - interval '75 seconds'
                ORDER BY updated_at
                LIMIT 50
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new PaymentTransaction(
                        resultSet.getString("transaction_id"),
                        resultSet.getString("merchant_id"),
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("payment_method"),
                        resultSet.getString("card_token"),
                        resultSet.getString("bank_code"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency"),
                        resultSet.getString("ip_address")));
        for (PaymentTransaction transaction : stalled) {
            jdbcTemplate.update(
                    "UPDATE payment_transactions SET updated_at = ? WHERE transaction_id = ? AND status = 'PROCESSING'",
                    Timestamp.from(Instant.now()),
                    transaction.transactionId());
            SagaCommand retryCommand = new SagaCommand(
                    UUID.randomUUID().toString(),
                    transaction.transactionId(),
                    transaction.merchantId(),
                    transaction.idempotencyKey(),
                    transaction.paymentMethod(),
                    transaction.cardToken(),
                    transaction.bankCode(),
                    transaction.amount(),
                    transaction.currency(),
                    "AUTHORIZE",
                    Instant.now());
            outboxRepository.enqueue(COMMAND_TOPIC, transaction.transactionId(), retryCommand);
        }
    }

    private PaymentStatusResponse existingPayment(String merchantId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                """
                SELECT transaction_id, status, failure_reason
                FROM payment_transactions WHERE merchant_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new PaymentStatusResponse(
                        resultSet.getString("transaction_id"),
                        resultSet.getString("status"),
                        resultSet.getString("failure_reason")),
                merchantId,
                idempotencyKey);
    }

    private static void validate(PaymentSubmission submission) {
        if (submission == null
                || submission.transactionId() == null
                || submission.transactionId().isBlank()
                || submission.merchantId() == null
                || !submission.merchantId().matches("[A-Za-z0-9._-]{1,128}")
                || submission.idempotencyKey() == null
                || !submission.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")
                || submission.amount() == null
                || submission.amount().compareTo(BigDecimal.ZERO) <= 0
                || submission.amount().precision() > 19
                || submission.amount().scale() > 4
                || submission.currency() == null
                || !submission.currency().matches("[A-Z]{3}")
                || !("CARD".equals(submission.paymentMethod()) || "NET_BANKING".equals(submission.paymentMethod()))
                || ("CARD".equals(submission.paymentMethod())
                        && (submission.cardToken() == null || submission.cardToken().isBlank()))
                || ("NET_BANKING".equals(submission.paymentMethod())
                        && (submission.bankCode() == null || submission.bankCode().isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment submission");
        }
    }
}
