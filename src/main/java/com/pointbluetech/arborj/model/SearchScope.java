package com.pointbluetech.arborj.model;

public enum SearchScope {
    BASE("Base"),
    ONE_LEVEL("One Level"),
    SUBTREE("Subtree");

    private final String displayName;

    SearchScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public com.unboundid.ldap.sdk.SearchScope toUnboundID() {
        return switch (this) {
            case BASE -> com.unboundid.ldap.sdk.SearchScope.BASE;
            case ONE_LEVEL -> com.unboundid.ldap.sdk.SearchScope.ONE;
            case SUBTREE -> com.unboundid.ldap.sdk.SearchScope.SUB;
        };
    }
}
