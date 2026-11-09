package com.ark.arkinvestmentapi.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record InvestorReport(UUID investorId, String investorName, UUID fundId, String fundName, String currency, LocalDate from, LocalDate to, ReportTotals totals) {}
