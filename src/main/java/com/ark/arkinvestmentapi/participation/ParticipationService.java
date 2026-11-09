package com.ark.arkinvestmentapi.participation;

import com.ark.arkinvestmentapi.security.ClientAccess;
import com.ark.arkinvestmentapi.common.*;
import com.ark.arkinvestmentapi.funds.*;
import com.ark.arkinvestmentapi.investors.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ParticipationService {
    private final ClientAccess access;
    private final ParticipationRepository repository;
    private final FundService funds;
    private final InvestorService investors;

    public ParticipationService(ClientAccess access, ParticipationRepository repository, FundService funds, InvestorService investors) {
        this.access = access;
        this.repository = repository;
        this.funds = funds;
        this.investors = investors;
    }

    private void lockOwners(UUID clientId, UUID fundId, UUID investorId) {
        funds.lock(clientId, fundId);
        investors.lock(clientId, investorId);
    }

    @Transactional
    public void add(UUID clientId, UUID fundId, UUID investorId) {
        access.financial(clientId);
        lockOwners(clientId, fundId, investorId);
        repository.associate(clientId, UUID.randomUUID(), fundId, investorId);
    }

    @Transactional
    public void requireForTransaction(UUID clientId, UUID fundId, UUID investorId) {
        access.financial(clientId);
        lockOwners(clientId, fundId, investorId);
        if (!repository.existsByClientIdAndFundIdAndInvestorId(clientId, fundId, investorId))
            throw new StateConflictException("Participation required before posting transactions");
    }

    @Transactional
    public void remove(UUID clientId, UUID fundId, UUID investorId) {
        access.financial(clientId);
        lockOwners(clientId, fundId, investorId);
        repository.remove(clientId, fundId, investorId);
    }

    public PageResponse<InvestorResponse> investors(UUID clientId, UUID fundId, int page, int size) {
        access.financial(clientId);
        var pageable = Pagination.request(page, size);
        funds.require(clientId, fundId);
        return PageResponse.from(repository.investors(clientId, fundId, pageable).map(InvestorResponse::from));
    }

    public PageResponse<FundResponse> funds(UUID clientId, UUID investorId, int page, int size) {
        access.financial(clientId);
        var pageable = Pagination.request(page, size);
        investors.require(clientId, investorId);
        return PageResponse.from(repository.funds(clientId, investorId, pageable).map(FundResponse::from));
    }
}
