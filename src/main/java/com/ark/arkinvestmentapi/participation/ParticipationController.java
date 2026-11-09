package com.ark.arkinvestmentapi.participation;

import com.ark.arkinvestmentapi.common.PageResponse;
import com.ark.arkinvestmentapi.funds.FundResponse;
import com.ark.arkinvestmentapi.investors.InvestorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/{clientId}")
public class ParticipationController {
    private final ParticipationService service;

    public ParticipationController(ParticipationService service) {
        this.service = service;
    }

    @GetMapping("/funds/{fundId}/investors")
    public PageResponse<InvestorResponse> investors(@PathVariable UUID clientId, @PathVariable UUID fundId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.investors(clientId, fundId, page, size);
    }

    @GetMapping("/investors/{investorId}/funds")
    public PageResponse<FundResponse> funds(@PathVariable UUID clientId, @PathVariable UUID investorId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.funds(clientId, investorId, page, size);
    }

    @PutMapping("/funds/{fundId}/investors/{investorId}")
    public ResponseEntity<Void> add(@PathVariable UUID clientId, @PathVariable UUID fundId, @PathVariable UUID investorId) {
        service.add(clientId, fundId, investorId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/funds/{fundId}/investors/{investorId}")
    public ResponseEntity<Void> remove(@PathVariable UUID clientId, @PathVariable UUID fundId, @PathVariable UUID investorId) {
        service.remove(clientId, fundId, investorId);
        return ResponseEntity.noContent().build();
    }
}
