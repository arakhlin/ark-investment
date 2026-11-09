package com.ark.arkinvestmentapi.reporting;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clients/{clientId}")
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) { this.service = service; }

    @GetMapping("/funds/{fundId}/report")
    public FundReport fund(@PathVariable UUID clientId, @PathVariable UUID fundId, @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to) {
        return service.fund(clientId, fundId, from, to);
    }

    @GetMapping("/investors/{investorId}/report")
    public InvestorReport investor(@PathVariable UUID clientId, @PathVariable UUID investorId, @RequestParam(required = false) UUID fundId,
                                   @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return service.investor(clientId, investorId, fundId, from, to);
    }
}
