package com.pointbluetech.arborj.model;

import java.util.List;

public class LDAPObjectClassInfo {

    private final String name;
    private final List<String> mustAttributes;
    private final List<String> mayAttributes;
    private final String superClass;
    private final List<String> namingAttributes;

    public LDAPObjectClassInfo(String name, List<String> mustAttributes, List<String> mayAttributes,
                                String superClass, List<String> namingAttributes) {
        this.name = name;
        this.mustAttributes = mustAttributes;
        this.mayAttributes = mayAttributes;
        this.superClass = superClass;
        this.namingAttributes = namingAttributes;
    }

    public String getName() { return name; }
    public List<String> getMustAttributes() { return mustAttributes; }
    public List<String> getMayAttributes() { return mayAttributes; }
    public String getSuperClass() { return superClass; }
    public List<String> getNamingAttributes() { return namingAttributes; }
}
