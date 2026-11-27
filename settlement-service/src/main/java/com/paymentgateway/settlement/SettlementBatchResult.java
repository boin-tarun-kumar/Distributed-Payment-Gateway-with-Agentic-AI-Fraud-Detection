package com.paymentgateway.settlement;

import java.time.LocalDate;

public record SettlementBatchResult(
        String batchId,
        LocalDate businessDate,
        int settledTransactions,
        int merchantPayouts) {
}
