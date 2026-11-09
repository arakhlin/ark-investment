package com.ark.arkinvestmentapi.investors;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvestorResponse(UUID id, String name, String email) {
    public static InvestorResponse from(Investor value) {
        return new InvestorResponse(value.getId(), value.getName(), value.getEmail());
    }
}
