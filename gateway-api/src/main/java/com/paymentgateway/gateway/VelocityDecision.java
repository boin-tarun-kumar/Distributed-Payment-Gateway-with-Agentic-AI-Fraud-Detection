package com.paymentgateway.gateway;

public record VelocityDecision(long attemptsInWindow, boolean suspicious, boolean blocked) {
}
