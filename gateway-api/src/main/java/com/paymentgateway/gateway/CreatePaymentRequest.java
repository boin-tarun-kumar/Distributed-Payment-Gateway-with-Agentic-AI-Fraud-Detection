package com.paymentgateway.gateway;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        String merchantId,
        BigDecimal amount,
        String currency,
        String cardToken,
        String bankCode) {
}
