package com.paymentgateway.vault;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CardTokenService {
    private static final int AES_BLOCK_SIZE = 16;
    private static final int CMAC_RB = 0x87;

    private final byte[] aesKey;

    public CardTokenService(@Value("${card-vault.aes-key}") String aesKeyHex) {
        this.aesKey = HexFormat.of().parseHex(aesKeyHex);
        if (aesKey.length != 32) {
            Arrays.fill(aesKey, (byte) 0);
            throw new IllegalArgumentException("The card-vault AES-256 key must be 64 hex characters");
        }
    }

    public String tokenize(byte[] panBytes, byte[] cvvBytes) {
        byte[] tag = null;
        try {
            int panLength = normalizeAndValidatePan(panBytes);
            validateCvv(cvvBytes);
            byte[] normalizedPan = Arrays.copyOf(panBytes, panLength);
            try {
                tag = aesCmac(normalizedPan);
                return "ct_" + HexFormat.of().formatHex(tag);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Card tokenization failed", exception);
            } finally {
                Arrays.fill(normalizedPan, (byte) 0);
            }
        } finally {
            if (panBytes != null) {
                Arrays.fill(panBytes, (byte) 0);
            }
            if (cvvBytes != null) {
                Arrays.fill(cvvBytes, (byte) 0);
            }
            if (tag != null) {
                Arrays.fill(tag, (byte) 0);
            }
        }
    }

    private static int normalizeAndValidatePan(byte[] panBytes) {
        if (panBytes == null) {
            throw new IllegalArgumentException("Invalid card number");
        }
        int length = 0;
        for (byte value : panBytes) {
            if (value == ' ') {
                continue;
            }
            if (value < '0' || value > '9') {
                throw new IllegalArgumentException("Invalid card number");
            }
            panBytes[length++] = value;
        }
        if (length < 12 || length > 19) {
            throw new IllegalArgumentException("Invalid card number");
        }
        return length;
    }

    private static void validateCvv(byte[] cvvBytes) {
        if (cvvBytes == null || (cvvBytes.length != 3 && cvvBytes.length != 4)) {
            throw new IllegalArgumentException("Invalid CVV");
        }
        for (byte value : cvvBytes) {
            if (value < '0' || value > '9') {
                throw new IllegalArgumentException("Invalid CVV");
            }
        }
    }

    private byte[] aesCmac(byte[] message) throws GeneralSecurityException {
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));

        byte[] zeroBlock = new byte[AES_BLOCK_SIZE];
        byte[] l = aes.doFinal(zeroBlock);
        byte[] k1 = doubleBlock(l);
        byte[] k2 = doubleBlock(k1);
        byte[] state = new byte[AES_BLOCK_SIZE];
        byte[] block = new byte[AES_BLOCK_SIZE];
        byte[] lastBlock = new byte[AES_BLOCK_SIZE];
        try {
            int blockCount = (message.length + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE;
            int lastOffset = (blockCount - 1) * AES_BLOCK_SIZE;
            boolean completeLastBlock = message.length % AES_BLOCK_SIZE == 0;

            for (int offset = 0; offset < lastOffset; offset += AES_BLOCK_SIZE) {
                for (int index = 0; index < AES_BLOCK_SIZE; index++) {
                    block[index] = (byte) (state[index] ^ message[offset + index]);
                }
                byte[] nextState = aes.doFinal(block);
                Arrays.fill(state, (byte) 0);
                state = nextState;
                Arrays.fill(block, (byte) 0);
            }

            int lastLength = message.length - lastOffset;
            System.arraycopy(message, lastOffset, lastBlock, 0, lastLength);
            byte[] subkey = completeLastBlock ? k1 : k2;
            if (!completeLastBlock) {
                lastBlock[lastLength] = (byte) 0x80;
            }
            for (int index = 0; index < AES_BLOCK_SIZE; index++) {
                block[index] = (byte) (state[index] ^ lastBlock[index] ^ subkey[index]);
            }
            return aes.doFinal(block);
        } finally {
            Arrays.fill(zeroBlock, (byte) 0);
            Arrays.fill(l, (byte) 0);
            Arrays.fill(k1, (byte) 0);
            Arrays.fill(k2, (byte) 0);
            Arrays.fill(state, (byte) 0);
            Arrays.fill(block, (byte) 0);
            Arrays.fill(lastBlock, (byte) 0);
        }
    }

    private static byte[] doubleBlock(byte[] block) {
        byte[] result = new byte[AES_BLOCK_SIZE];
        int carry = 0;
        for (int index = AES_BLOCK_SIZE - 1; index >= 0; index--) {
            int value = Byte.toUnsignedInt(block[index]);
            result[index] = (byte) ((value << 1) | carry);
            carry = value >>> 7;
        }
        if (carry != 0) {
            result[AES_BLOCK_SIZE - 1] ^= (byte) CMAC_RB;
        }
        return result;
    }
}
