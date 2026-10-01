package com.pointbluetech.arborj.model;

import java.util.*;

public class LDAPEntry {

    private final String id;
    private final String dn;
    private final Map<String, List<String>> attributes;

    public LDAPEntry(String dn, Map<String, List<String>> attributes) {
        this.id = UUID.randomUUID().toString();
        this.dn = dn;
        this.attributes = new LinkedHashMap<>(attributes);
    }

    public String getId() { return id; }
    public String getDn() { return dn; }
    public Map<String, List<String>> getAttributes() { return attributes; }

    /**
     * Get the first value for an attribute, or null if not present.
     */
    public String getFirstValue(String attributeName) {
        List<String> values = attributes.get(attributeName);
        if (values == null || values.isEmpty()) {
            // Case-insensitive lookup
            for (var entry : attributes.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(attributeName) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }
        return values.getFirst();
    }

    /**
     * Get all values for an attribute (case-insensitive), or empty list.
     */
    public List<String> getValues(String attributeName) {
        List<String> values = attributes.get(attributeName);
        if (values != null) return values;
        for (var entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(attributeName)) {
                return entry.getValue();
            }
        }
        return List.of();
    }
}
