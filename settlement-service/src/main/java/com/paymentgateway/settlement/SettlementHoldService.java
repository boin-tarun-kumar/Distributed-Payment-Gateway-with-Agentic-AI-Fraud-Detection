package com.paymentgateway.settlement;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SettlementHoldService {
    private final JdbcTemplate jdbcTemplate;

    public SettlementHoldService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public HoldResponse placeHold(PlaceHoldRequest request) {
        validate(request);
        SettlementMerchantLock.acquire(jdbcTemplate, request.merchantId());
        int affected = jdbcTemplate.update(
                """
                INSERT INTO settlement_holds (alert_id, merchant_id, reason, active, created_at)
                VALUES (?, ?, ?, TRUE, ?)
                ON CONFLICT (alert_id) DO UPDATE
                    SET active = TRUE, reason = EXCLUDED.reason
                    WHERE settlement_holds.merchant_id = EXCLUDED.merchant_id
                """,
                request.alertId(),
                request.merchantId(),
                request.reason(),
                Timestamp.from(Instant.now()));
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Alert is already associated with another merchant");
        }
        jdbcTemplate.update(
                "UPDATE settlement_payouts SET status = 'HELD' WHERE merchant_id = ? AND status = 'READY'",
                request.merchantId());
        return new HoldResponse(request.merchantId(), request.alertId(), true);
    }

    @Transactional
    public int releaseHolds(String merchantId) {
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid merchant identifier");
        }
        SettlementMerchantLock.acquire(jdbcTemplate, merchantId);
        jdbcTemplate.update(
                "UPDATE settlement_holds SET active = FALSE WHERE merchant_id = ? AND active = TRUE",
                merchantId);
        return jdbcTemplate.update(
                """
                UPDATE settlement_payouts p
                SET status = 'READY'
                WHERE p.merchant_id = ? AND p.status = 'HELD'
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_holds h
                      WHERE h.merchant_id = p.merchant_id AND h.active = TRUE
                  )
                """,
                merchantId);
    }

    private static void validate(PlaceHoldRequest request) {
        if (request == null
                || request.merchantId() == null
                || !request.merchantId().matches("[A-Za-z0-9._-]{1,128}")
                || request.alertId() == null
                || !request.alertId().matches("[A-Za-z0-9._:-]{1,128}")
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid settlement hold request");
        }
    }
}
