package com.ark.arkinvestmentapi.funds;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.*;

public final class FundInput {
    @NotNull
    @Size(min = 1, max = 200)
    @Pattern(regexp = "(?s).*\\S.*")
    private String name;
    @Size(max = 1000)
    private String description;

    public FundInput() {
    }

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public FundInput(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public void setName(String name) {
        this.name = name;
    }

    // Setter distinguishes explicit null from an omitted optional property.
    @JsonSetter(nulls = Nulls.FAIL)
    public void setDescription(String description) {
        this.description = description;
    }
}
