package com.ark.arkinvestmentapi.security;

public enum ClientRole {
    CLIENT_ADMIN, CLIENT_MANAGER, CLIENT_REPORT_VIEWER,
    ARK_ADMIN, ARK_OPERATIONS, ARK_REPORT_VIEWER;

    public boolean compatible(UserType type) {
        return name().startsWith(type.name() + "_");
    }
}
