package com.ark.arkinvestmentapi.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record FundReport(UUID fundId, String fundName, String currency, LocalDate from, LocalDate to, ReportTotals totals) {}
