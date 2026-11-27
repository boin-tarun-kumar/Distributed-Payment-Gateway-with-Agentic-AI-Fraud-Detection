package com.paymentgateway.settlement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

final class SettlementMerchantLock {
    private SettlementMerchantLock() {
    }

    static void acquire(JdbcTemplate jdbcTemplate, String merchantId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtext(?), 726381)",
                (RowCallbackHandler) resultSet -> { },
                merchantId);
    }
}
