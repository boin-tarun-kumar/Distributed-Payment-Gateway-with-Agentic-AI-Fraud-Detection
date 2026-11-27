package com.paymentgateway.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CardTokenServiceTest {
    private final CardTokenService tokenService =
            new CardTokenService("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    @Test
    void createsStableTokenWithoutRetainingPanOrCvvBytes() {
        byte[] pan = bytes("4111111111111111");
        byte[] cvv = bytes("123");
        byte[] secondPan = bytes("4111 1111 1111 1111");
        byte[] secondCvv = bytes("999");

        String firstToken = tokenService.tokenize(pan, cvv);
        String secondToken = tokenService.tokenize(secondPan, secondCvv);

        assertEquals(firstToken, secondToken);
        assertEquals(35, firstToken.length());
        assertArrayEquals(new byte[pan.length], pan);
        assertArrayEquals(new byte[cvv.length], cvv);
        assertArrayEquals(new byte[secondPan.length], secondPan);
        assertArrayEquals(new byte[secondCvv.length], secondCvv);
    }

    @Test
    void clearsSensitiveByteArraysEvenWhenValidationFails() {
        byte[] pan = bytes("not-a-card");
        byte[] cvv = bytes("123");

        assertThrows(IllegalArgumentException.class, () -> tokenService.tokenize(pan, cvv));

        assertArrayEquals(new byte[pan.length], pan);
        assertArrayEquals(new byte[cvv.length], cvv);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
