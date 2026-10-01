package com.pointbluetech.arborj.model;

public class LDAPAttributeInfo {

    private final String name;
    private final String syntaxOID;
    private final LDAPAttributeSyntax syntax;
    private final boolean singleValued;
    private final boolean operational;

    public LDAPAttributeInfo(String name, String syntaxOID, LDAPAttributeSyntax syntax,
                             boolean singleValued, boolean operational) {
        this.name = name;
        this.syntaxOID = syntaxOID;
        this.syntax = syntax;
        this.singleValued = singleValued;
        this.operational = operational;
    }

    public String getName() { return name; }
    public String getSyntaxOID() { return syntaxOID; }
    public LDAPAttributeSyntax getSyntax() { return syntax; }
    public boolean isSingleValued() { return singleValued; }
    public boolean isOperational() { return operational; }
}
