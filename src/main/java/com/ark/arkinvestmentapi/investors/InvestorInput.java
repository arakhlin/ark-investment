package com.ark.arkinvestmentapi.investors;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.*;

public record InvestorInput(
        @NotNull @Size(min = 1, max = 200) @Pattern(regexp = "(?s).*\\S.*") String name,
        @NotBlank @Email @Size(max = 254) String email) {
}
