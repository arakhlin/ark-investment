package com.ark.arkinvestmentapi.funds;

import com.ark.arkinvestmentapi.security.ClientAccess;
import com.ark.arkinvestmentapi.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FundService {
    private final ClientAccess access;
    private final FundRepository repository;

    public FundService(ClientAccess access, FundRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    public Fund require(UUID clientId, UUID id) {
        access.reports(clientId);
        return repository.findByClientIdAndId(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Fund not found"));
    }

    @Transactional
    public Fund lock(UUID clientId, UUID id) {
        access.financial(clientId);
        return repository.findForUpdate(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Fund not found"));
    }

    public FundResponse get(UUID clientId, UUID id) {
        access.financial(clientId);
        return FundResponse.from(require(clientId, id));
    }

    public PageResponse<FundResponse> list(UUID clientId, int page, int size) {
        access.financial(clientId);
        return PageResponse.from(repository.findAllByClientId(clientId, Pagination.request(page, size)).map(FundResponse::from));
    }

    @Transactional
    public FundResponse create(UUID clientId, FundInput input) {
        access.financial(clientId);
        return FundResponse.from(repository.saveAndFlush(new Fund(clientId, input.name(), input.description())));
    }

    @Transactional
    public FundResponse replace(UUID clientId, UUID id, FundInput input) {
        access.financial(clientId);
        Fund entity = require(clientId, id);
        entity.replace(input.name(), input.description());
        repository.flush();
        return FundResponse.from(entity);
    }

    @Transactional
    public void delete(UUID clientId, UUID id) {
        access.financial(clientId);
        repository.delete(require(clientId, id));
        repository.flush();
    }
}
