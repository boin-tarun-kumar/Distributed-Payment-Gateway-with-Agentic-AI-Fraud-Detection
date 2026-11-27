package com.paymentgateway.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SettlementFeeCalculator {
    private static final int CURRENCY_SCALE = 2;

    private final BigDecimal percentageRate;
    private final BigDecimal fixedFeePerTransaction;

    public SettlementFeeCalculator(
            @Value("${settlement.processing-fee-rate:0.029}") BigDecimal percentageRate,
            @Value("${settlement.processing-fee-fixed:0.30}") BigDecimal fixedFeePerTransaction) {
        if (percentageRate.signum() < 0
                || percentageRate.compareTo(BigDecimal.ONE) > 0
                || fixedFeePerTransaction.signum() < 0) {
            throw new IllegalArgumentException("Invalid settlement fee configuration");
        }
        this.percentageRate = percentageRate;
        this.fixedFeePerTransaction = fixedFeePerTransaction;
    }

    public FeeBreakdown calculate(BigDecimal grossAmount, long transactionCount) {
        if (grossAmount == null || grossAmount.signum() < 0 || transactionCount < 0) {
            throw new IllegalArgumentException("Settlement totals must not be negative");
        }
        BigDecimal percentageFee = grossAmount.multiply(percentageRate).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        BigDecimal fixedFee = fixedFeePerTransaction
                .multiply(BigDecimal.valueOf(transactionCount))
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalFee = percentageFee.add(fixedFee);
        BigDecimal netAmount = grossAmount.subtract(totalFee).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        return new FeeBreakdown(grossAmount, totalFee, netAmount);
    }

    public record FeeBreakdown(BigDecimal grossAmount, BigDecimal fees, BigDecimal netAmount) {
    }
}
