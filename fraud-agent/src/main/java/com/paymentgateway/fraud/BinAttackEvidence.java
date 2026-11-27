package com.paymentgateway.fraud;

public record BinAttackEvidence(boolean detected, long distinctCards, long merchants) {
}
