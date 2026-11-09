package com.ark.arkinvestmentapi.common;

import java.util.UUID;

public final class Uuids {
    private Uuids() {}

    public static UUID parse(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(value);
    }
}
