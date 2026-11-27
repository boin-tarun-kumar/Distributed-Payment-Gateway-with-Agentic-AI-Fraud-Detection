package com.paymentgateway.fraud;

public record SettlementHoldRequest(String merchantId, String alertId, String reason) {
}
