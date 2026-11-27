package com.paymentgateway.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettlementFeeCalculatorTest {
    @Test
    void calculatesPercentageAndPerTransactionFeesBeforeNetPayout() {
        SettlementFeeCalculator calculator =
                new SettlementFeeCalculator(new BigDecimal("0.029"), new BigDecimal("0.30"));

        SettlementFeeCalculator.FeeBreakdown fees = calculator.calculate(new BigDecimal("100.00"), 2);

        assertEquals(new BigDecimal("100.00"), fees.grossAmount());
        assertEquals(new BigDecimal("3.50"), fees.fees());
        assertEquals(new BigDecimal("96.50"), fees.netAmount());
    }
}
