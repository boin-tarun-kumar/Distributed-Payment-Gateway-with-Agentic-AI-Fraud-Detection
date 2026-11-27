package com.paymentgateway.settlement;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlements/holds")
public class SettlementHoldController {
    private final SettlementHoldService holdService;

    public SettlementHoldController(SettlementHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<HoldResponse> placeHold(@RequestBody PlaceHoldRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holdService.placeHold(request));
    }

    @DeleteMapping("/{merchantId}")
    public ResponseEntity<Void> releaseMerchantHolds(@PathVariable String merchantId) {
        holdService.releaseHolds(merchantId);
        return ResponseEntity.noContent().build();
    }
}
