package com.pointbluetech.arborj.model;

public enum DirectoryType {
    AUTO("Auto-Detect"),
    ACTIVE_DIRECTORY("Active Directory"),
    OPENLDAP("OpenLDAP / 389DS"),
    EDIRECTORY("eDirectory"),
    GENERIC("Generic LDAP");

    private final String displayName;

    DirectoryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
