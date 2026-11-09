package com.ark.arkinvestmentapi.transactions;

import com.ark.arkinvestmentapi.security.ClientAccess;
import com.ark.arkinvestmentapi.common.*;
import com.ark.arkinvestmentapi.funds.FundService;
import com.ark.arkinvestmentapi.investors.InvestorService;
import com.ark.arkinvestmentapi.participation.ParticipationService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TransactionService {
    private final ClientAccess access;
    private final TransactionRepository repository;
    private final FundService funds;
    private final InvestorService investors;
    private final ParticipationService participation;
    private final TransactionAuditWriter audit;

    public TransactionService(ClientAccess access, TransactionRepository repository, FundService funds, InvestorService investors, ParticipationService participation, TransactionAuditWriter audit) {
        this.access = access;
        this.repository = repository;
        this.funds = funds;
        this.investors = investors;
        this.participation = participation;
        this.audit = audit;
    }

    private Transaction require(UUID clientId, UUID id) {
        return repository.findByClientIdAndId(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
    }

    public TransactionResponse get(UUID clientId, UUID id) {
        access.financial(clientId); return TransactionResponse.from(require(clientId, id)); }

    public PageResponse<TransactionResponse> list(UUID clientId, int page, int size, UUID fundId, UUID investorId, TransactionType type, String from, String to) {
        access.financial(clientId);
        var pageable = Pagination.request(page, size).withSort(Sort.by(Sort.Order.desc("date"), Sort.Order.asc("id")));
        var lower = from == null ? null : TransactionValues.date(from);
        var upper = to == null ? null : TransactionValues.date(to);
        if (lower != null && upper != null && lower.isAfter(upper)) throw new IllegalArgumentException("from exceeds to");
        if (fundId != null) funds.require(clientId, fundId);
        if (investorId != null) investors.require(clientId, investorId);
        return PageResponse.from(repository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (fundId != null) predicates.add(cb.equal(root.get("fundId"), fundId));
            if (investorId != null) predicates.add(cb.equal(root.get("investorId"), investorId));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            if (lower != null) predicates.add(cb.greaterThanOrEqualTo(root.get("date"), lower));
            if (upper != null) predicates.add(cb.lessThanOrEqualTo(root.get("date"), upper));
            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable).map(TransactionResponse::from));
    }

    @Transactional
    public TransactionResponse create(UUID clientId, TransactionInput input) {
        access.financial(clientId);
        var amount = TransactionValues.amount(input.amount());
        var date = TransactionValues.date(input.date());
        var fundId = Uuids.parse(input.fundId());
        var investorId = Uuids.parse(input.investorId());
        participation.requireForTransaction(clientId, fundId, investorId);
        var entity = repository.saveAndFlush(new Transaction(clientId, fundId, investorId, date, amount, input.type(), input.description()));
        audit.append(clientId, entity.getId(), null, TransactionAuditWriter.Snapshot.from(entity));
        return TransactionResponse.from(entity);
    }

    @Transactional
    public TransactionResponse replace(UUID clientId, UUID id, TransactionInput input) {
        access.financial(clientId);
        var amount = TransactionValues.amount(input.amount());
        var date = TransactionValues.date(input.date());
        var fundId = Uuids.parse(input.fundId());
        var investorId = Uuids.parse(input.investorId());
        var entity = requireForUpdate(clientId, id);
        if (!entity.getFundId().equals(fundId) || !entity.getInvestorId().equals(investorId))
            throw new StateConflictException("Transaction ownership cannot change");
        var previous = TransactionAuditWriter.Snapshot.from(entity);
        entity.replace(date, amount, input.type(), input.description());
        repository.flush();
        audit.append(clientId, id, previous, TransactionAuditWriter.Snapshot.from(entity));
        return TransactionResponse.from(entity);
    }

    @Transactional
    public void delete(UUID clientId, UUID id) {
        access.financial(clientId);
        var entity = requireForUpdate(clientId, id);
        var previous = TransactionAuditWriter.Snapshot.from(entity);
        repository.delete(entity);
        repository.flush();
        audit.append(clientId, id, previous, null);
    }

    private Transaction requireForUpdate(UUID clientId, UUID id) {
        return repository.findForUpdate(clientId, id).orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
    }
}
