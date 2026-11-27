package com.paymentgateway.fraud;

public record TokenHistoryEvidence(long transactions, long declines, long merchants) {
}
