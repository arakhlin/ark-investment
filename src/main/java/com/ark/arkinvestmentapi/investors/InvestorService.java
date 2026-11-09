package com.ark.arkinvestmentapi.investors;

import com.ark.arkinvestmentapi.security.ClientAccess;
import com.ark.arkinvestmentapi.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InvestorService {
    private final ClientAccess access;
    private final InvestorRepository repository;

    public InvestorService(ClientAccess access, InvestorRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    public Investor require(UUID clientId, UUID id) {
        access.reports(clientId);
        return repository.findByClientIdAndId(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Investor not found"));
    }

    @Transactional
    public Investor lock(UUID clientId, UUID id) {
        access.financial(clientId);
        return repository.findForUpdate(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Investor not found"));
    }

    public InvestorResponse get(UUID clientId, UUID id) {
        access.financial(clientId);
        return InvestorResponse.from(require(clientId, id));
    }

    public PageResponse<InvestorResponse> list(UUID clientId, int page, int size) {
        access.financial(clientId);
        return PageResponse.from(repository.findAllByClientId(clientId, Pagination.request(page, size)).map(InvestorResponse::from));
    }

    @Transactional
    public InvestorResponse create(UUID clientId, InvestorInput input) {
        access.financial(clientId);
        return InvestorResponse.from(repository.saveAndFlush(new Investor(clientId, input.name(), input.email())));
    }

    @Transactional
    public InvestorResponse replace(UUID clientId, UUID id, InvestorInput input) {
        access.financial(clientId);
        Investor entity = require(clientId, id);
        entity.replace(input.name(), input.email());
        repository.flush();
        return InvestorResponse.from(entity);
    }

    @Transactional
    public void delete(UUID clientId, UUID id) {
        access.financial(clientId);
        repository.delete(require(clientId, id));
        repository.flush();
    }
}
