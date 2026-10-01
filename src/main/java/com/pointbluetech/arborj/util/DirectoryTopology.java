package com.pointbluetech.arborj.util;

import com.pointbluetech.arborj.model.LDAPEntry;
import com.unboundid.ldap.sdk.DN;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules for working out the shape of a directory tree from what the server
 * reports: which DNs belong at the top, and whether an entry has children.
 *
 * <p>The main DIT tree and the DN picker both ask these questions, and both
 * used to answer them wrongly against eDirectory, so the rules live here
 * rather than in either view.
 */
public final class DirectoryTopology {

    private DirectoryTopology() {
    }

    /**
     * Attributes a search must request for the leaf decisions below to have
     * anything to work with.
     */
    public static final String[] TOPOLOGY_ATTRS = {
            "objectClass", "subordinateCount", "numSubordinates",
            "msDS-Approx-Immed-Subordinates"};

    // --- Root selection ---

    /**
     * Decide which DNs belong at the top of the tree.
     *
     * <p>{@code topLevel} is what a one-level search of the root DSE returned —
     * the genuine top of the DIT. Naming contexts are added to it, but only the
     * ones that walking down the DIT cannot reach.
     *
     * <p>eDirectory publishes every partition root it holds as a naming context,
     * including partitions nested deep inside the tree. Those are already
     * reachable by walking down, so promoting them to the top listed them twice:
     * once in their real place, and once as a stray top-level node carrying
     * nothing but a DN. The stray copy had no objectClass, so it was drawn as a
     * leaf that opened its attributes but never its children — the symptom for
     * any container that was also a partition boundary (an OU, an IDM driver
     * set, the top of the tree).
     *
     * <p>Whether a context can be suppressed turns on one thing: can this server
     * be walked downward at all? A non-empty {@code topLevel} says yes, so a
     * context underneath <em>any</em> root being shown — including one added by
     * this method — is reachable there and is left out of the top. An empty
     * {@code topLevel} says no: Active Directory answers a root-DSE one-level
     * search with nothing, so nothing can be reached by walking and every
     * published context has to stand as its own root. That is what keeps AD's
     * Configuration and Schema contexts at the top even though their DNs sit
     * under the domain DN.
     */
    public static Set<String> selectRootDns(List<String> namingContexts, Set<String> topLevel) {
        Set<String> roots = new LinkedHashSet<>(topLevel);
        if (namingContexts == null) return roots;

        Set<String> normalizedRoots = new LinkedHashSet<>();
        for (String dn : roots) normalizedRoots.add(normalizeDn(dn));

        boolean browsable = !topLevel.isEmpty();

        for (String ctx : namingContexts) {
            if (ctx == null || ctx.isBlank()) continue;
            String normalized = normalizeDn(ctx);
            if (normalizedRoots.contains(normalized)) continue;
            if (isDescendantOfAny(ctx, browsable ? roots : topLevel)) continue;
            roots.add(ctx);
            normalizedRoots.add(normalized);
        }
        return roots;
    }

    /** True when {@code dn} sits underneath any of {@code ancestors}. */
    private static boolean isDescendantOfAny(String dn, Set<String> ancestors) {
        for (String ancestor : ancestors) {
            if (ancestor == null || ancestor.isEmpty()) continue;
            try {
                if (new DN(dn).isDescendantOf(new DN(ancestor), false)) return true;
            } catch (Exception ignored) {
                String suffix = "," + ancestor;
                if (dn.length() > suffix.length()
                        && dn.regionMatches(true, dn.length() - suffix.length(),
                        suffix, 0, suffix.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Case- and spacing-insensitive form of a DN, so two spellings of the same
     * entry cannot both claim a place at the top of the tree.
     */
    private static String normalizeDn(String dn) {
        if (dn == null) return "";
        try {
            return new DN(dn).toNormalizedString();
        } catch (Exception ignored) {
            return dn.trim().toLowerCase();
        }
    }

    // --- Child counts ---

    /**
     * Immediate-child count as reported by the server, and whether it can be
     * believed. {@code usable == false} means "no idea" — never "empty".
     */
    public record ChildCount(int value, boolean usable) {
        public static final ChildCount UNKNOWN = new ChildCount(-1, false);

        public boolean isUsable() {
            return usable && value >= 0;
        }

        public boolean isKnownEmpty() {
            return isUsable() && value == 0;
        }

        public boolean isKnownPopulated() {
            return isUsable() && value > 0;
        }
    }

    /**
     * Read the number of immediate children the server advertises for an entry.
     *
     * <p>eDirectory's {@code subordinateCount} cannot be trusted when it reads
     * zero. An entry that is both a container and a partition root is seen as a
     * subordinate reference from the parent partition, and that reference
     * reports no subordinates even though the real entry is full of children —
     * which is why partition boundaries (the tree root, an IDM driver set, a
     * partitioned OU) used to render as childless leaves. A zero from that
     * attribute is therefore discarded; positive values and the Active
     * Directory counterparts are taken at face value.
     */
    public static ChildCount readChildCount(LDAPEntry entry) {
        for (String attr : List.of("numSubordinates", "msDS-Approx-Immed-Subordinates",
                "subordinateCount")) {
            String val = entry.getFirstValue(attr);
            if (val == null) continue;
            try {
                int parsed = Integer.parseInt(val.trim());
                if (parsed < 0) return ChildCount.UNKNOWN;
                boolean trustworthy = parsed > 0 || !attr.equals("subordinateCount");
                return new ChildCount(parsed, trustworthy);
            } catch (NumberFormatException ignored) {
                return ChildCount.UNKNOWN;
            }
        }
        return ChildCount.UNKNOWN;
    }

    // --- Leaf decisions ---

    /**
     * Whether an ordinary entry should be drawn as a leaf.
     *
     * <p>Only a trustworthy count of zero demotes a container to a leaf; when
     * the count is unknown the entry stays expandable and the one-level search
     * behind the expansion settles the question against the directory itself. A
     * positive count outranks the schema, so an entry with children opens even
     * when its objectClass was not recognised as a container.
     */
    public static boolean isLeafEntry(ChildCount count, boolean schemaSaysContainer) {
        if (count.isKnownEmpty()) return true;
        if (count.isKnownPopulated()) return false;
        return !schemaSaysContainer;
    }

    /**
     * Whether a naming context at the top of the tree should be drawn as a leaf.
     *
     * <p>A naming context is the top of a directory tree, so one the server told
     * us nothing about — no objectClass, no count — gets the benefit of the
     * doubt and opens rather than hiding as a leaf. The one-level search behind
     * the expansion corrects the guess if it really is empty.
     */
    public static boolean isLeafNamingContext(ChildCount count, List<String> objectClasses,
                                              boolean schemaSaysContainer) {
        boolean container = objectClasses == null || objectClasses.isEmpty() || schemaSaysContainer;
        return isLeafEntry(count, container);
    }
}
