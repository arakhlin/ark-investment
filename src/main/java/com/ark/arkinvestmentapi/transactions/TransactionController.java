package com.ark.arkinvestmentapi.transactions;

import com.ark.arkinvestmentapi.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/{clientId}/transactions")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) { this.service = service; }

    @GetMapping
    public PageResponse<TransactionResponse> list(@PathVariable UUID clientId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID fundId,
            @RequestParam(required = false) UUID investorId, @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return service.list(clientId, page, size, fundId, investorId, type, from, to);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID clientId, @PathVariable UUID id) { return service.get(clientId, id); }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<TransactionResponse> create(@PathVariable UUID clientId, @Valid @RequestBody TransactionInput input) {
        var result = service.create(clientId, input);
        return ResponseEntity.created(URI.create("/api/v1/clients/" + clientId + "/transactions/" + result.id())).body(result);
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public TransactionResponse replace(@PathVariable UUID clientId, @PathVariable UUID id, @Valid @RequestBody TransactionInput input) {
        return service.replace(clientId, id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID clientId, @PathVariable UUID id) {
        service.delete(clientId, id);
        return ResponseEntity.noContent().build();
    }
}
