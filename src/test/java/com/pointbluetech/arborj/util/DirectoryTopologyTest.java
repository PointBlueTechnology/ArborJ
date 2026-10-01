package com.pointbluetech.arborj.util;

import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.util.DirectoryTopology.ChildCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryTopologyTest {

    private static final boolean CONTAINER = true;
    private static final boolean NOT_CONTAINER = false;

    private static LDAPEntry entryWith(String attr, String value) {
        return new LDAPEntry("cn=driverset1,o=system", Map.of(attr, List.of(value)));
    }

    // --- Root selection ---

    @Nested
    @DisplayName("selectRootDns")
    class RootSelection {

        @Test
        @DisplayName("a partition nested under another root is not promoted to the top")
        void nestedNamingContextIsNotARoot() {
            // Regression: eDirectory publishes every partition root as a naming
            // context. cn=driverset1,o=system is both a container and a partition,
            // so it appeared at the top of the tree beside o=system, carrying no
            // objectClass — a leaf that showed its attributes but never its
            // children. It belongs under o=system, where the DIT already has it.
            Set<String> topLevel = new LinkedHashSet<>(List.of(
                    "o=system", "cn=Security", "o=data", "o=Communities"));
            List<String> contexts = List.of("", "o=data", "cn=driverset1,o=system");

            assertEquals(topLevel, DirectoryTopology.selectRootDns(contexts, topLevel));
        }

        @Test
        @DisplayName("naming contexts outside the browsable tree are kept as roots")
        void unreachableNamingContextStaysARoot() {
            // Active Directory answers a root-DSE one-level search with nothing, so
            // its naming contexts are the only way to reach the tree — even though
            // Configuration and Schema sit under the domain DN by DN arithmetic.
            List<String> contexts = List.of(
                    "DC=fed1,DC=pointbluetech,DC=com",
                    "CN=Configuration,DC=fed1,DC=pointbluetech,DC=com",
                    "CN=Schema,CN=Configuration,DC=fed1,DC=pointbluetech,DC=com");

            assertEquals(new LinkedHashSet<>(contexts),
                    DirectoryTopology.selectRootDns(contexts, Set.of()));
        }

        @Test
        @DisplayName("on a browsable server a context under a promoted root is suppressed")
        void nestedContextUnderAPromotedRootIsSuppressed() {
            // The root-DSE search covered o=system but not o=data. o=data is
            // unreachable, so it is promoted — and once it is a root, the
            // partition beneath it is reachable by walking down and must not
            // appear at the top as well.
            Set<String> topLevel = new LinkedHashSet<>(List.of("o=system"));
            List<String> contexts = List.of("o=data", "ou=Eng,o=data", "cn=driverset1,o=system");

            Set<String> roots = DirectoryTopology.selectRootDns(contexts, topLevel);

            assertEquals(new LinkedHashSet<>(List.of("o=system", "o=data")), roots);
        }

        @Test
        @DisplayName("a naming context that is itself a top-level entry is not duplicated")
        void topLevelNamingContextIsNotDuplicated() {
            Set<String> topLevel = new LinkedHashSet<>(List.of("o=system", "o=data"));
            Set<String> roots = DirectoryTopology.selectRootDns(List.of("", "o=data"), topLevel);
            assertEquals(2, roots.size());
            assertTrue(roots.contains("o=data"));
        }

        @Test
        @DisplayName("a context spelled in a different case is not a second root")
        void caseVariantIsNotDuplicated() {
            Set<String> topLevel = new LinkedHashSet<>(List.of("o=data"));
            Set<String> roots = DirectoryTopology.selectRootDns(List.of("O=DATA"), topLevel);
            assertEquals(new LinkedHashSet<>(List.of("o=data")), roots);
        }

        @Test
        @DisplayName("root selection tolerates a null naming-context list")
        void nullNamingContextsAreTolerated() {
            Set<String> topLevel = new LinkedHashSet<>(List.of("o=system"));
            assertEquals(topLevel, DirectoryTopology.selectRootDns(null, topLevel));
        }

        @Test
        @DisplayName("blank and empty contexts are skipped")
        void blankContextsAreSkipped() {
            Set<String> roots = DirectoryTopology.selectRootDns(
                    java.util.Arrays.asList("", "   ", null, "o=data"), Set.of());
            assertEquals(new LinkedHashSet<>(List.of("o=data")), roots);
        }
    }

    // --- Child counts ---

    @Nested
    @DisplayName("readChildCount")
    class ChildCounts {

        @Test
        @DisplayName("eDirectory subordinateCount of 0 is not believed")
        void subordinateCountZeroIsUnusable() {
            // Regression: a container that is also a partition root (the tree root,
            // an IDM driver set, a partitioned OU) is a subordinate reference when
            // seen from the parent partition and reports 0 subordinates, which used
            // to render it as a childless leaf.
            var count = DirectoryTopology.readChildCount(entryWith("subordinateCount", "0"));
            assertFalse(count.isUsable());
            assertFalse(count.isKnownEmpty());
        }

        @Test
        @DisplayName("positive subordinateCount is used as the child count")
        void subordinateCountPositiveIsUsable() {
            var count = DirectoryTopology.readChildCount(entryWith("subordinateCount", "12"));
            assertTrue(count.isKnownPopulated());
            assertEquals(12, count.value());
        }

        @Test
        @DisplayName("Active Directory child counts are believed even at 0")
        void adCountsAreUsableAtZero() {
            var numSub = DirectoryTopology.readChildCount(entryWith("numSubordinates", "0"));
            assertTrue(numSub.isKnownEmpty());

            var approx = DirectoryTopology.readChildCount(
                    entryWith("msDS-Approx-Immed-Subordinates", "0"));
            assertTrue(approx.isKnownEmpty());
        }

        @Test
        @DisplayName("numSubordinates wins over eDirectory's subordinateCount")
        void numSubordinatesTakesPrecedence() {
            LDAPEntry entry = new LDAPEntry("cn=driverset1,o=system", Map.of(
                    "subordinateCount", List.of("0"),
                    "numSubordinates", List.of("4")));
            var count = DirectoryTopology.readChildCount(entry);
            assertTrue(count.isKnownPopulated());
            assertEquals(4, count.value());
        }

        @Test
        @DisplayName("missing or malformed counts read as unknown, never as empty")
        void missingCountIsUnknown() {
            LDAPEntry noCount = new LDAPEntry("ou=People,o=data",
                    Map.of("objectClass", List.of("organizationalUnit")));
            assertFalse(DirectoryTopology.readChildCount(noCount).isUsable());
            assertFalse(DirectoryTopology.readChildCount(
                    entryWith("numSubordinates", "many")).isUsable());
            assertFalse(DirectoryTopology.readChildCount(
                    entryWith("numSubordinates", "-1")).isUsable());
        }

        @Test
        @DisplayName("surrounding whitespace does not spoil a count")
        void whitespaceIsTrimmed() {
            var count = DirectoryTopology.readChildCount(entryWith("numSubordinates", " 7 "));
            assertTrue(count.isKnownPopulated());
            assertEquals(7, count.value());
        }
    }

    // --- Leaf decisions ---

    @Nested
    @DisplayName("isLeafEntry")
    class EntryLeafDecision {

        @Test
        @DisplayName("a trustworthy count of 0 demotes even a container to a leaf")
        void knownEmptyContainerIsALeaf() {
            assertTrue(DirectoryTopology.isLeafEntry(new ChildCount(0, true), CONTAINER));
        }

        @Test
        @DisplayName("a positive count outranks the schema")
        void populatedNonContainerOpens() {
            // An objectClass the schema did not recognise as a container still
            // opens when the server says it has children.
            assertFalse(DirectoryTopology.isLeafEntry(new ChildCount(3, true), NOT_CONTAINER));
        }

        @Test
        @DisplayName("an unknown count leaves the decision to the schema")
        void unknownCountDefersToSchema() {
            assertFalse(DirectoryTopology.isLeafEntry(ChildCount.UNKNOWN, CONTAINER));
            assertTrue(DirectoryTopology.isLeafEntry(ChildCount.UNKNOWN, NOT_CONTAINER));
        }

        @Test
        @DisplayName("eDirectory's untrustworthy 0 does not demote a container")
        void edirZeroDoesNotDemoteAContainer() {
            // The whole point of the tri-state: readChildCount() hands back an
            // unusable 0 here, and the container must stay expandable.
            ChildCount count = DirectoryTopology.readChildCount(
                    entryWith("subordinateCount", "0"));
            assertFalse(DirectoryTopology.isLeafEntry(count, CONTAINER));
        }
    }

    @Nested
    @DisplayName("isLeafNamingContext")
    class NamingContextLeafDecision {

        @Test
        @DisplayName("a context the server said nothing about still opens")
        void unknownContextOpens() {
            // No objectClass came back and no count — the benefit of the doubt
            // goes to opening it, because a naming context tops a directory tree.
            assertFalse(DirectoryTopology.isLeafNamingContext(
                    ChildCount.UNKNOWN, List.of(), NOT_CONTAINER));
            assertFalse(DirectoryTopology.isLeafNamingContext(
                    ChildCount.UNKNOWN, null, NOT_CONTAINER));
        }

        @Test
        @DisplayName("a context the server reports as empty is a leaf")
        void knownEmptyContextIsALeaf() {
            assertTrue(DirectoryTopology.isLeafNamingContext(
                    new ChildCount(0, true), List.of(), NOT_CONTAINER));
        }

        @Test
        @DisplayName("a known non-container context with a known count is a leaf")
        void knownNonContainerIsALeaf() {
            assertTrue(DirectoryTopology.isLeafNamingContext(
                    ChildCount.UNKNOWN, List.of("device"), NOT_CONTAINER));
        }

        @Test
        @DisplayName("a container context opens")
        void containerContextOpens() {
            assertFalse(DirectoryTopology.isLeafNamingContext(
                    ChildCount.UNKNOWN, List.of("organization"), CONTAINER));
        }
    }
}
