package com.paymentgateway.settlement;

public record HoldResponse(String merchantId, String alertId, boolean active) {
}
