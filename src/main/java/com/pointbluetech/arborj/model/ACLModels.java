package com.pointbluetech.arborj.model;

import java.util.*;

/**
 * eDirectory ACL parsing and data structures.
 * ACL string format: "privileges#scope#trustee#protectedName"
 * Example: "16#entry#[Public]#[Entry Rights]"
 */
public class ACLModels {

    public enum ACLScope {
        ENTRY("entry"), SUBTREE("subtree");
        private final String value;
        ACLScope(String value) { this.value = value; }
        public String getValue() { return value; }
        public static ACLScope fromString(String s) {
            return "subtree".equalsIgnoreCase(s) ? SUBTREE : ENTRY;
        }
    }

    // Entry rights bit flags
    public static final int ER_BROWSE = 1;
    public static final int ER_ADD = 2;
    public static final int ER_DELETE = 4;
    public static final int ER_RENAME = 8;
    public static final int ER_SUPERVISOR = 16;
    public static final int ER_INHERITANCE_CONTROL = 64;
    public static final int ER_DYNAMIC = 536870912;

    public static final String[][] ENTRY_RIGHTS = {
            {"Browse", String.valueOf(ER_BROWSE)},
            {"Add", String.valueOf(ER_ADD)},
            {"Delete", String.valueOf(ER_DELETE)},
            {"Rename", String.valueOf(ER_RENAME)},
            {"Supervisor", String.valueOf(ER_SUPERVISOR)},
            {"Inheritance Control", String.valueOf(ER_INHERITANCE_CONTROL)},
            {"Dynamic", String.valueOf(ER_DYNAMIC)}
    };

    // Attribute rights bit flags
    public static final int AR_COMPARE = 1;
    public static final int AR_READ = 2;
    public static final int AR_WRITE = 4;
    public static final int AR_ADD_SELF = 8;
    public static final int AR_SUPERVISOR = 32;
    public static final int AR_INHERITANCE_CONTROL = 64;
    public static final int AR_DYNAMIC = 536870912;

    public static final String[][] ATTRIBUTE_RIGHTS = {
            {"Compare", String.valueOf(AR_COMPARE)},
            {"Read", String.valueOf(AR_READ)},
            {"Write", String.valueOf(AR_WRITE)},
            {"Add Self", String.valueOf(AR_ADD_SELF)},
            {"Supervisor", String.valueOf(AR_SUPERVISOR)},
            {"Inheritance Control", String.valueOf(AR_INHERITANCE_CONTROL)},
            {"Dynamic", String.valueOf(AR_DYNAMIC)}
    };

    public static final List<String> SPECIAL_TRUSTEES = List.of(
            "[Public]", "[Root]", "[Self]", "[Creator]", "[Inheritance Mask]");

    /**
     * A single ACL entry: one trustee + one protected name + privileges + scope.
     */
    public static class ACLEntry {
        private int privileges;
        private ACLScope scope;
        private String trustee;
        private String protectedName;

        public ACLEntry(int privileges, ACLScope scope, String trustee, String protectedName) {
            this.privileges = privileges;
            this.scope = scope;
            this.trustee = trustee;
            this.protectedName = protectedName;
        }

        /** Parse from ACL string format: "privileges#scope#trustee#protectedName" */
        public static ACLEntry parse(String aclString) {
            if (aclString == null) return null;
            String[] parts = aclString.split("#", 4);
            if (parts.length < 4) return null;
            try {
                int privs = Integer.parseInt(parts[0]);
                ACLScope scope = ACLScope.fromString(parts[1]);
                return new ACLEntry(privs, scope, parts[2], parts[3]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Serialize back to ACL string format. */
        public String toACLString() {
            return privileges + "#" + scope.getValue() + "#" + trustee + "#" + protectedName;
        }

        public int getPrivileges() { return privileges; }
        public void setPrivileges(int privileges) { this.privileges = privileges; }
        public ACLScope getScope() { return scope; }
        public void setScope(ACLScope scope) { this.scope = scope; }
        public String getTrustee() { return trustee; }
        public void setTrustee(String trustee) { this.trustee = trustee; }
        public String getProtectedName() { return protectedName; }
        public void setProtectedName(String protectedName) { this.protectedName = protectedName; }

        public boolean isEntryRights() {
            return "[Entry Rights]".equals(protectedName);
        }

        public boolean isAllAttributesRights() {
            return "[All Attributes Rights]".equals(protectedName);
        }

        public boolean isSpecialTrustee() {
            return trustee.startsWith("[") && trustee.endsWith("]");
        }
    }

    /**
     * Groups ACL entries by trustee DN.
     */
    public static class TrusteeGroup {
        private String trusteeName;
        private List<ACLEntry> entries;

        public TrusteeGroup(String trusteeName, List<ACLEntry> entries) {
            this.trusteeName = trusteeName;
            this.entries = new ArrayList<>(entries);
        }

        public String getTrusteeName() { return trusteeName; }
        public List<ACLEntry> getEntries() { return entries; }

        public boolean isSpecialTrustee() {
            return trusteeName.startsWith("[") && trusteeName.endsWith("]");
        }

        public ACLEntry getEntryRightsEntry() {
            return entries.stream().filter(ACLEntry::isEntryRights).findFirst().orElse(null);
        }

        public List<ACLEntry> getAttributeEntries() {
            return entries.stream().filter(e -> !e.isEntryRights()).toList();
        }
    }

    /**
     * Parse ACL attribute values into grouped TrusteeGroups.
     */
    public static List<TrusteeGroup> parseACLs(List<String> aclValues) {
        List<ACLEntry> entries = aclValues.stream()
                .map(ACLEntry::parse)
                .filter(Objects::nonNull)
                .toList();

        Map<String, List<ACLEntry>> grouped = new LinkedHashMap<>();
        for (ACLEntry entry : entries) {
            grouped.computeIfAbsent(entry.getTrustee(), k -> new ArrayList<>()).add(entry);
        }

        List<TrusteeGroup> groups = grouped.entrySet().stream()
                .map(e -> new TrusteeGroup(e.getKey(), e.getValue()))
                .sorted((g1, g2) -> {
                    if (g1.isSpecialTrustee() && !g2.isSpecialTrustee()) return -1;
                    if (!g1.isSpecialTrustee() && g2.isSpecialTrustee()) return 1;
                    return g1.getTrusteeName().compareToIgnoreCase(g2.getTrusteeName());
                })
                .toList();

        return new ArrayList<>(groups);
    }

    /**
     * Flatten TrusteeGroups back into ACL strings.
     */
    public static List<String> toACLStrings(List<TrusteeGroup> groups) {
        List<String> result = new ArrayList<>();
        for (TrusteeGroup group : groups) {
            for (ACLEntry entry : group.getEntries()) {
                result.add(entry.toACLString());
            }
        }
        return result;
    }
}
