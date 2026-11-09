package com.ark.arkinvestmentapi.transactions;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TransactionInput {
    @NotNull private String fundId;
    @NotNull private String investorId;
    @NotNull private String date;
    @NotNull private String amount;
    @NotNull private TransactionType type;
    @Size(max = 1000) private String description;

    public String fundId() { return fundId; }
    public String investorId() { return investorId; }
    public String date() { return date; }
    public String amount() { return amount; }
    public TransactionType type() { return type; }
    public String description() { return description; }
    public void setFundId(String value) { fundId = value; }
    public void setInvestorId(String value) { investorId = value; }
    public void setDate(String value) { date = value; }
    public void setAmount(String value) { amount = value; }
    public void setType(TransactionType value) { type = value; }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setDescription(String value) { description = value; }
}
