package com.paymentgateway.acquirer;

public class BankUnavailableException extends RuntimeException {
    public BankUnavailableException(String message) {
        super(message);
    }
}
