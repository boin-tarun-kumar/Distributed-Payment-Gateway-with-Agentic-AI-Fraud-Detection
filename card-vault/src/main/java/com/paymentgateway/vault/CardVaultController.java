package com.paymentgateway.vault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tokens")
public class CardVaultController {
    private final CardTokenService tokenService;

    public CardVaultController(CardTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public TokenizeResponse tokenize(@RequestBody TokenizeRequest request) {
        byte[] panBytes = null;
        byte[] cvvBytes = null;
        try {
            if (request.getCardNumber() == null || request.getCvv() == null) {
                throw new IllegalArgumentException("Card details are required");
            }
            panBytes = request.getCardNumber().getBytes(StandardCharsets.US_ASCII);
            cvvBytes = request.getCvv().getBytes(StandardCharsets.US_ASCII);
            return new TokenizeResponse(tokenService.tokenize(panBytes, cvvBytes));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } finally {
            if (panBytes != null) {
                Arrays.fill(panBytes, (byte) 0);
            }
            if (cvvBytes != null) {
                Arrays.fill(cvvBytes, (byte) 0);
            }
            request.clearSensitiveData();
        }
    }
}
