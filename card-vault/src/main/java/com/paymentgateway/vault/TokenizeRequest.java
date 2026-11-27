package com.paymentgateway.vault;

public final class TokenizeRequest {
    private String cardNumber;
    private String cvv;

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getCvv() {
        return cvv;
    }

    public void setCvv(String cvv) {
        this.cvv = cvv;
    }

    public void clearSensitiveData() {
        cardNumber = null;
        cvv = null;
    }
}
