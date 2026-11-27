package com.paymentgateway.settlement;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementBatchService {
    private final JdbcTemplate jdbcTemplate;
    private final SettlementFeeCalculator feeCalculator;
    private final ZoneId settlementZone;

    public SettlementBatchService(
            JdbcTemplate jdbcTemplate,
            SettlementFeeCalculator feeCalculator,
            @Value("${settlement.zone:UTC}") String settlementZone) {
        this.jdbcTemplate = jdbcTemplate;
        this.feeCalculator = feeCalculator;
        this.settlementZone = ZoneId.of(settlementZone);
    }

    @Scheduled(
            cron = "${settlement.cron:0 0 2 * * *}",
            zone = "${settlement.zone:UTC}")
    @Transactional
    public void runNightlySettlement() {
        LocalDate businessDate = LocalDate.now(settlementZone);
        settle(businessDate, Instant.now());
    }

    @Transactional
    public SettlementBatchResult settle(LocalDate businessDate, Instant cutoff) {
        UUID batchId = UUID.randomUUID();
        int created = jdbcTemplate.update(
                """
                INSERT INTO settlement_batches (batch_id, business_date, status, created_at)
                VALUES (?, ?, 'PROCESSING', ?)
                ON CONFLICT (business_date) DO NOTHING
                """,
                batchId,
                businessDate,
                Timestamp.from(Instant.now()));
        if (created == 0) {
            String existingBatchId = jdbcTemplate.queryForObject(
                    "SELECT batch_id::text FROM settlement_batches WHERE business_date = ?",
                    String.class,
                    businessDate);
            return new SettlementBatchResult(existingBatchId, businessDate, 0, 0);
        }

        List<SettlementItem> items = jdbcTemplate.query(
                """
                INSERT INTO settlement_items (batch_id, transaction_id, merchant_id, currency, amount, settled_at)
                SELECT ?, p.transaction_id, p.merchant_id, p.currency, p.amount, p.updated_at
                FROM payment_transactions p
                WHERE p.status = 'SUCCEEDED'
                  AND p.updated_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_items i WHERE i.transaction_id = p.transaction_id
                  )
                ON CONFLICT (transaction_id) DO NOTHING
                RETURNING merchant_id, currency, amount
                """,
                (resultSet, rowNumber) -> new SettlementItem(
                        resultSet.getString("merchant_id"),
                        resultSet.getString("currency").trim(),
                        resultSet.getBigDecimal("amount")),
                batchId,
                Timestamp.from(cutoff));

        Map<MerchantCurrency, Aggregate> aggregates = aggregate(items);
        for (Map.Entry<MerchantCurrency, Aggregate> entry : aggregates.entrySet()) {
            MerchantCurrency key = entry.getKey();
            Aggregate aggregate = entry.getValue();
            SettlementMerchantLock.acquire(jdbcTemplate, key.merchantId());
            SettlementFeeCalculator.FeeBreakdown fees =
                    feeCalculator.calculate(aggregate.grossAmount(), aggregate.transactionCount());
            boolean held = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM settlement_holds WHERE merchant_id = ? AND active = TRUE)",
                    Boolean.class,
                    key.merchantId()));
            jdbcTemplate.update(
                    """
                    INSERT INTO settlement_payouts (
                        batch_id, merchant_id, currency, transaction_count, gross_amount,
                        processing_fees, net_amount, status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    batchId,
                    key.merchantId(),
                    key.currency(),
                    aggregate.transactionCount(),
                    fees.grossAmount(),
                    fees.fees(),
                    fees.netAmount(),
                    held ? "HELD" : "READY",
                    Timestamp.from(Instant.now()));
        }

        jdbcTemplate.update(
                """
                UPDATE settlement_batches
                SET status = 'COMPLETE', completed_at = ?, transaction_count = ?, payout_count = ?
                WHERE batch_id = ?
                """,
                Timestamp.from(Instant.now()),
                items.size(),
                aggregates.size(),
                batchId);
        return new SettlementBatchResult(batchId.toString(), businessDate, items.size(), aggregates.size());
    }

    private static Map<MerchantCurrency, Aggregate> aggregate(List<SettlementItem> items) {
        Map<MerchantCurrency, Aggregate> aggregates = new LinkedHashMap<>();
        for (SettlementItem item : items) {
            MerchantCurrency key = new MerchantCurrency(item.merchantId(), item.currency());
            Aggregate aggregate = aggregates.computeIfAbsent(key, ignored -> new Aggregate());
            aggregate.add(item.amount());
        }
        return aggregates;
    }

    private record SettlementItem(String merchantId, String currency, BigDecimal amount) {
    }

    private record MerchantCurrency(String merchantId, String currency) {
    }

    private static final class Aggregate {
        private BigDecimal grossAmount = BigDecimal.ZERO;
        private long transactionCount;

        private void add(BigDecimal amount) {
            grossAmount = grossAmount.add(amount);
            transactionCount++;
        }

        private BigDecimal grossAmount() {
            return grossAmount;
        }

        private long transactionCount() {
            return transactionCount;
        }
    }
}
