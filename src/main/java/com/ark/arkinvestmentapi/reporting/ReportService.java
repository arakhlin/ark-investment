package com.ark.arkinvestmentapi.reporting;

import com.ark.arkinvestmentapi.security.ClientAccess;
import com.ark.arkinvestmentapi.funds.FundService;
import com.ark.arkinvestmentapi.investors.InvestorService;
import com.ark.arkinvestmentapi.transactions.TransactionValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportService {
    private final ClientAccess access;
    private final ReportRepository repository;
    private final FundService funds;
    private final InvestorService investors;

    public ReportService(ClientAccess access, ReportRepository repository, FundService funds, InvestorService investors) {
        this.access = access;
        this.repository = repository;
        this.funds = funds;
        this.investors = investors;
    }

    private record Period(LocalDate from, LocalDate to) {
        static Period parse(String from, String to) {
            var lower = from == null ? null : TransactionValues.date(from);
            var upper = to == null ? null : TransactionValues.date(to);
            if (lower != null && upper != null && lower.isAfter(upper)) throw new IllegalArgumentException("from exceeds to");
            return new Period(lower, upper);
        }
    }

    public FundReport fund(UUID clientId, UUID fundId, String from, String to) {
        access.reports(clientId);
        var period = Period.parse(from, to);
        var owner = funds.require(clientId, fundId);
        return new FundReport(fundId, owner.getName(), owner.getCurrency(), period.from(), period.to(),
                ReportTotals.from(repository.aggregate(clientId, fundId, null, period.from(), period.to())));
    }

    public InvestorReport investor(UUID clientId, UUID investorId, UUID fundId, String from, String to) {
        access.reports(clientId);
        var period = Period.parse(from, to);
        var investor = investors.require(clientId, investorId);
        var fund = fundId == null ? null : funds.require(clientId, fundId);
        return new InvestorReport(investorId, investor.getName(), fundId, fund == null ? null : fund.getName(), "USD", period.from(), period.to(),
                ReportTotals.from(repository.aggregate(clientId, fundId, investorId, period.from(), period.to())));
    }
}
