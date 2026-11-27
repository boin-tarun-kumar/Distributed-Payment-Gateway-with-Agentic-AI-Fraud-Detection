package com.paymentgateway.settlement;

public record PlaceHoldRequest(String merchantId, String alertId, String reason) {
}
