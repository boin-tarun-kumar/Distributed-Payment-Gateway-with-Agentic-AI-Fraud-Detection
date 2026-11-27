package com.paymentgateway.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {
    @Test
    void signsExactPayloadUsingHmacSha256() {
        WebhookSigner signer = new WebhookSigner();

        String signature = signer.sign("Jefe", "what do ya want for nothing?");

        assertEquals(
                "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                signature);
    }

    @Test
    void refusesMissingSigningSecret() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookSigner().sign(" ", "{}"));
    }
}
