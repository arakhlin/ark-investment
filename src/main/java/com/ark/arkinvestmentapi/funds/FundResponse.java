package com.ark.arkinvestmentapi.funds;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FundResponse(UUID id, String name, String description, String currency) {
    public static FundResponse from(Fund value) {
        return new FundResponse(value.getId(), value.getName(), value.getDescription(), value.getCurrency());
    }
}
