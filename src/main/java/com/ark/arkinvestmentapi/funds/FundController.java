package com.ark.arkinvestmentapi.funds;

import com.ark.arkinvestmentapi.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/{clientId}/funds")
public class FundController {
    private final FundService service;

    public FundController(FundService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<FundResponse> list(@PathVariable UUID clientId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(clientId, page, size);
    }

    @GetMapping("/{id}")
    public FundResponse get(@PathVariable UUID clientId, @PathVariable UUID id) {
        return service.get(clientId, id);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<FundResponse> create(@PathVariable UUID clientId, @Valid @RequestBody FundInput input) {
        FundResponse result = service.create(clientId, input);
        return ResponseEntity.created(URI.create("/api/v1/clients/" + clientId + "/funds/" + result.id())).body(result);
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public FundResponse replace(@PathVariable UUID clientId, @PathVariable UUID id, @Valid @RequestBody FundInput input) {
        return service.replace(clientId, id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID clientId, @PathVariable UUID id) {
        service.delete(clientId, id);
        return ResponseEntity.noContent().build();
    }
}
