package com.ark.arkinvestmentapi.investors;

import com.ark.arkinvestmentapi.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/{clientId}/investors")
public class InvestorController {
    private final InvestorService service;

    public InvestorController(InvestorService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<InvestorResponse> list(@PathVariable UUID clientId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(clientId, page, size);
    }

    @GetMapping("/{id}")
    public InvestorResponse get(@PathVariable UUID clientId, @PathVariable UUID id) {
        return service.get(clientId, id);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<InvestorResponse> create(@PathVariable UUID clientId, @Valid @RequestBody InvestorInput input) {
        InvestorResponse result = service.create(clientId, input);
        return ResponseEntity.created(URI.create("/api/v1/clients/" + clientId + "/investors/" + result.id())).body(result);
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public InvestorResponse replace(@PathVariable UUID clientId, @PathVariable UUID id, @Valid @RequestBody InvestorInput input) {
        return service.replace(clientId, id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID clientId, @PathVariable UUID id) {
        service.delete(clientId, id);
        return ResponseEntity.noContent().build();
    }
}
