package com.pointbluetech.arborj.util;

import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which Universal Password policy, if any, applies to an eDirectory user.
 *
 * <p>NMAS looks at {@code nspmPasswordPolicyDN} and keeps the first hit, in
 * this order (OpenText eDirectory administration guide, "Assigning Password
 * Policies"):
 * <ol>
 *   <li>the user</li>
 *   <li>the container that holds the user — only that container, not every
 *       ancestor. A policy on a container that is not a partition root is
 *       <em>not</em> inherited by users in subcontainers</li>
 *   <li>the partition root of the partition the user is in (the nearest
 *       naming context above the user). A policy on a parent partition does
 *       not apply inside a child partition</li>
 *   <li>the Login Policy object in the Security container, the tree-wide
 *       default</li>
 * </ol>
 * No assignment means the tree is still on legacy NDS password rules.
 * Password Modify (RFC 3062) is an NMAS operation and fails there; a
 * {@code userPassword} replace over LDAPS is what works.
 *
 * <p>This class only decides. {@code LDAPService} does the reads, and those
 * reads are not writes.
 */
public final class EdirPasswordPolicy {

    public static final String POLICY_ATTRIBUTE = "nspmPasswordPolicyDN";

    /** eDirectory NMAS response controls: {@code 2.16.840.1.113719.1.27.103.*}. */
    public static final String NMAS_CONTROL_PREFIX = "2.16.840.1.113719.1.27.103.";

    /** LDAP result codes that mean "Password Modify cannot do this". */
    public static final int CONSTRAINT_VIOLATION = 19;
    public static final int UNWILLING_TO_PERFORM = 53;

    private EdirPasswordPolicy() {
    }

    public enum Source {
        USER,
        CONTAINER,
        PARTITION_ROOT,
        LOGIN_POLICY
    }

    public enum Method {
        UNIVERSAL_PASSWORD,
        LEGACY_NDS
    }

    /**
     * A policy that applies, where it was found, and the entry that holds
     * the {@code nspmPasswordPolicyDN} value.
     */
    public record Assignment(String policyDN, Source source, String assignedOnDN) {
        public String sourceText() {
            return switch (source) {
                case USER -> "Assigned to this user.";
                case CONTAINER -> "Inherited from container " + assignedOnDN + ".";
                case PARTITION_ROOT -> "Inherited from partition root " + assignedOnDN + ".";
                case LOGIN_POLICY -> "Inherited from the Login Policy (" + assignedOnDN + ").";
            };
        }
    }

    /**
     * Result of a policy check. {@code conclusive} is false when the user's
     * partition root could not be seen on this server: a missing assignment
     * is then not "no policy". An assignment on the user or the parent
     * container is still conclusive, because those are checked before the
     * partition root.
     */
    public record PolicyLookup(Assignment assignment, boolean conclusive) {
        public boolean foundNone() {
            return conclusive && assignment == null;
        }
    }

    /**
     * One container above the user, closest first.
     * {@code partitionRoot} means this entry has a {@code replica} attribute
     * or is a naming context this server holds. {@code readable} is false
     * when the read failed or was referred — the walk stops there, because a
     * closer partition root may have been missed.
     */
    public record AncestorProbe(String dn, boolean partitionRoot, boolean readable) {}

    /** The user's partition root, or {@code known == false} when it isn't visible here. */
    public record PartitionAssessment(String partitionRootDn, boolean known) {}

    /**
     * Status line for the change-password dialog. The policy DN, when there
     * is one, is {@link Assignment#policyDN()} and is shown separately.
     * A failed lookup ({@code failureMessage}) and an unseen partition root
     * both refuse to say that no policy applies.
     */
    public static String describeStatus(PolicyLookup lookup, String failureMessage) {
        if (failureMessage != null && !failureMessage.isBlank()) {
            return "Could not check whether a Universal Password policy applies: "
                    + failureMessage;
        }
        if (lookup != null && lookup.assignment() != null) {
            return "Universal Password policy applies. " + lookup.assignment().sourceText();
        }
        if (lookup == null || !lookup.conclusive()) {
            return "This server does not show the user's partition root. "
                    + "namingContexts lists only partitions replicated here, and no "
                    + "closer replica attribute could be read, so a Universal Password "
                    + "policy on that partition could not be ruled out. "
                    + "Password Modify stays selected.";
        }
        return "No Universal Password policy applies to this user "
                + "(none on the user, the parent container, the partition root, "
                + "or the Login Policy).";
    }

    /**
     * Show the Universal Password / Legacy NDS choice when no policy was
     * found. That includes a failed or inconclusive lookup; {@link #defaultMethod}
     * keeps Password Modify selected in those cases.
     */
    public static boolean offerMethodChoice(PolicyLookup lookup) {
        return lookup == null || lookup.assignment() == null;
    }

    /**
     * Legacy NDS only when the lookup succeeded, the partition root was
     * visible, and nothing was assigned — and the connection can carry a
     * {@code userPassword} replace. A policy, a failed lookup, an unseen
     * partition, or a cleartext connection keeps Password Modify.
     */
    public static Method defaultMethod(PolicyLookup lookup, boolean secureConnection) {
        if (lookup == null || !lookup.foundNone() || !secureConnection) {
            return Method.UNIVERSAL_PASSWORD;
        }
        return Method.LEGACY_NDS;
    }

    /**
     * Offer a {@code userPassword} retry only when the lookup succeeded and
     * found no Universal Password policy, and Password Modify failed in the
     * way a legacy-only tree fails. A policy, a failed lookup, or an unseen
     * partition root just shows the Password Modify error.
     */
    public static boolean offerLegacyRetry(PolicyLookup lookup, Throwable error) {
        return lookup != null && lookup.foundNone() && passwordModifySuggestsLegacy(error);
    }

    /**
     * True when a failed Password Modify is the case the legacy replace
     * can cover: result 53 (unwillingToPerform), 19 (constraintViolation),
     * an NMAS response control, an error that says NMAS, or a matched DN
     * carrying an NMAS code. Other failures (bad current password, no
     * rights) are not retried this way.
     */
    public static boolean passwordModifySuggestsLegacy(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current instanceof LDAPException ldap) {
                ResultCode rc = ldap.getResultCode();
                int code = rc == null ? -1 : rc.intValue();
                if (code == CONSTRAINT_VIOLATION || code == UNWILLING_TO_PERFORM) return true;
                if (hasNmasControl(ldap.getResponseControls())) return true;
                if (mentionsNmas(ldap.getDiagnosticMessage())) return true;
                // eDirectory sometimes puts the NMAS code in the matched DN
                // ("-216") and the formatted dialog text no longer says NMAS.
                if (matchedDnIsNmas(ldap.getMatchedDN())) return true;
            }
            if (mentionsNmas(current.getMessage())) return true;
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return false;
    }

    /**
     * A connected socket is secure only when it is actually TLS. Config
     * flags apply before a socket exists.
     */
    public static boolean connectionIsSecure(boolean connected, boolean sslSessionActive,
                                             boolean useTls, int port) {
        if (connected) return sslSessionActive;
        return sslSessionActive || useTls || port == 636;
    }

    /**
     * DNs to read, in NMAS order, excluding the Login Policy (that object
     * is located separately). The user is always first. The partition root
     * is omitted when it is the parent already listed.
     */
    public static List<String> policySearchDns(String userDN, Collection<String> namingContexts) {
        if (userDN == null || userDN.isBlank()) return List.of();
        List<String> dns = new ArrayList<>();
        dns.add(userDN);
        String parent = parentDn(userDN);
        if (parent != null) dns.add(parent);
        String partition = nearestPartitionRoot(userDN, namingContexts);
        if (partition != null && (parent == null || !sameDn(partition, parent))) {
            dns.add(partition);
        }
        return List.copyOf(dns);
    }

    /**
     * Pick the policy from values already read, treating the nearest
     * naming context as the partition root. When the user is not under any
     * naming context, the partition root is unknown: a user or parent
     * assignment is still returned, and anything else is not "no policy".
     * {@link #lookup} is the same decision with an explicit partition root
     * from {@link #assessPartition}.
     */
    public static Assignment resolve(String userDN, Map<String, String> policyByDn,
                                     Collection<String> namingContexts, String loginPolicyDN) {
        if (userDN == null || userDN.isBlank()) return null;
        String root = nearestPartitionRoot(userDN, namingContexts);
        return lookup(userDN, policyByDn, root, root != null, loginPolicyDN).assignment();
    }

    /**
     * NMAS order. {@code partitionRootKnown} is false when this server could
     * not see the user's partition root. The user and the parent container
     * are still decisive. The partition root and the Login Policy are not
     * consulted then, and the result is not a conclusive "none".
     */
    public static PolicyLookup lookup(String userDN, Map<String, String> policyByDn,
                                      String partitionRootDn, boolean partitionRootKnown,
                                      String loginPolicyDN) {
        if (userDN == null || userDN.isBlank()) return new PolicyLookup(null, false);

        Hit user = find(policyByDn, userDN);
        if (user != null) {
            return new PolicyLookup(new Assignment(user.policyDN, Source.USER, user.dn), true);
        }

        String parent = parentDn(userDN);
        if (parent != null) {
            Hit container = find(policyByDn, parent);
            if (container != null) {
                return new PolicyLookup(
                        new Assignment(container.policyDN, Source.CONTAINER, container.dn), true);
            }
        }

        if (!partitionRootKnown) return new PolicyLookup(null, false);

        if (partitionRootDn != null && (parent == null || !sameDn(partitionRootDn, parent))) {
            Hit part = find(policyByDn, partitionRootDn);
            if (part != null) {
                return new PolicyLookup(
                        new Assignment(part.policyDN, Source.PARTITION_ROOT, part.dn), true);
            }
        }

        if (loginPolicyDN != null && !loginPolicyDN.isBlank()) {
            Hit login = find(policyByDn, loginPolicyDN);
            if (login != null) {
                return new PolicyLookup(
                        new Assignment(login.policyDN, Source.LOGIN_POLICY, login.dn), true);
            }
        }
        return new PolicyLookup(null, true);
    }

    /**
     * Walk ancestors from the parent upward. The first readable partition
     * root wins. An unreadable ancestor stops the walk: a naming context
     * further up is not the user's partition, because a closer one may have
     * been missed. Finishing the walk without a partition root means this
     * server does not show it.
     */
    public static PartitionAssessment assessPartition(List<AncestorProbe> fromParentUpward) {
        if (fromParentUpward == null) return new PartitionAssessment(null, false);
        for (AncestorProbe probe : fromParentUpward) {
            if (probe == null || !probe.readable()) return new PartitionAssessment(null, false);
            if (probe.partitionRoot()) return new PartitionAssessment(probe.dn(), true);
        }
        return new PartitionAssessment(null, false);
    }

    /** True when {@code dn} is one of the naming contexts, ignoring case and spacing. */
    public static boolean isNamingContext(String dn, Collection<String> namingContexts) {
        if (dn == null || dn.isBlank() || namingContexts == null) return false;
        for (String ctx : namingContexts) {
            if (ctx == null || ctx.isBlank()) continue;
            if (sameDn(dn, ctx)) return true;
        }
        return false;
    }

    /** Parent DN, or null for a top-level or empty DN. */
    public static String parentDn(String dn) {
        if (dn == null || dn.isBlank()) return null;
        try {
            DN parsed = new DN(dn);
            DN parent = parsed.getParent();
            if (parent == null || parent.isNullDN()) return null;
            return parent.toString();
        } catch (LDAPException e) {
            return null;
        }
    }

    /**
     * Longest naming context the user is strictly inside. eDirectory's
     * root DSE lists only partitions replicated on this server, so a hit
     * here can still be a parent partition with a closer partition root
     * in between. {@link #assessPartition} is what decides that. The user
     * DN itself is not a partition root for this purpose.
     */
    public static String nearestPartitionRoot(String userDN, Collection<String> namingContexts) {
        if (userDN == null || userDN.isBlank() || namingContexts == null) return null;
        DN user;
        try {
            user = new DN(userDN);
        } catch (LDAPException e) {
            return null;
        }
        String best = null;
        int bestRdns = -1;
        for (String ctx : namingContexts) {
            if (ctx == null || ctx.isBlank()) continue;
            try {
                DN ctxDn = new DN(ctx);
                if (ctxDn.isNullDN()) continue;
                if (user.isDescendantOf(ctxDn, false) && ctxDn.getRDNs().length > bestRdns) {
                    bestRdns = ctxDn.getRDNs().length;
                    best = ctx;
                }
            } catch (LDAPException ignored) {
                // A naming context that is not a DN cannot be a partition root.
            }
        }
        return best;
    }

    /** {@code cn=Security} and {@code cn=Security} under each naming context. */
    public static List<String> securityContainerCandidates(Collection<String> namingContexts) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("cn=Security");
        if (namingContexts != null) {
            for (String ctx : namingContexts) {
                if (ctx == null || ctx.isBlank()) continue;
                if (isSecurityContainer(ctx)) out.add(ctx);
                else out.add("cn=Security," + ctx);
            }
        }
        return List.copyOf(out);
    }

    /** {@code cn=Login Policy} directly under each security-container candidate. */
    public static List<String> loginPolicyCandidates(Collection<String> namingContexts) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String security : securityContainerCandidates(namingContexts)) {
            out.add("cn=Login Policy," + security);
        }
        return List.copyOf(out);
    }

    public static boolean sameDn(String a, String b) {
        if (a == null || b == null) return false;
        return normalize(a).equals(normalize(b));
    }

    public static String normalize(String dn) {
        if (dn == null) return "";
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean isSecurityContainer(String dn) {
        try {
            DN parsed = new DN(dn);
            if (parsed.isNullDN()) return false;
            RDN rdn = parsed.getRDN();
            String[] names = rdn.getAttributeNames();
            String[] values = rdn.getAttributeValues();
            for (int i = 0; i < names.length && i < values.length; i++) {
                if ("cn".equalsIgnoreCase(names[i]) && "security".equalsIgnoreCase(values[i])) {
                    return true;
                }
            }
            return false;
        } catch (LDAPException e) {
            return false;
        }
    }

    private record Hit(String dn, String policyDN) {}

    private static Hit find(Map<String, String> policyByDn, String dn) {
        if (policyByDn == null || dn == null) return null;
        if (policyByDn.containsKey(dn)) {
            String value = blankToNull(policyByDn.get(dn));
            return value == null ? null : new Hit(dn, value);
        }
        String want = normalize(dn);
        for (var entry : policyByDn.entrySet()) {
            if (want.equals(normalize(entry.getKey()))) {
                String value = blankToNull(entry.getValue());
                return value == null ? null : new Hit(entry.getKey(), value);
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean mentionsNmas(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("nmas");
    }

    private static boolean matchedDnIsNmas(String matchedDN) {
        if (matchedDN == null || matchedDN.isBlank()) return false;
        String trimmed = matchedDN.trim();
        if (trimmed.matches("-\\d+")) return true;
        if (trimmed.startsWith("NDS error:")) return true;
        return mentionsNmas(trimmed);
    }

    private static boolean hasNmasControl(Control[] controls) {
        if (controls == null) return false;
        for (Control control : controls) {
            if (control != null && control.getOID() != null
                    && control.getOID().startsWith(NMAS_CONTROL_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
