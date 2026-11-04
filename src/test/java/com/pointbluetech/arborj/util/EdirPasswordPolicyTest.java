package com.pointbluetech.arborj.util;

import com.pointbluetech.arborj.util.EdirPasswordPolicy.AncestorProbe;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.Assignment;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.Method;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.PartitionAssessment;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.PolicyLookup;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.Source;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdirPasswordPolicyTest {

    private static final String USER = "cn=alice,ou=eng,ou=users,o=acme";
    private static final String PARENT = "ou=eng,ou=users,o=acme";
    private static final String MIDDLE = "ou=users,o=acme";
    private static final String PARTITION = "o=acme";
    private static final String POLICY = "cn=Sample Password Policy,cn=Password Policies,cn=Security";
    private static final String OTHER = "cn=Other Policy,cn=Password Policies,cn=Security";
    private static final String LOGIN = "cn=Login Policy,cn=Security";

    private static Map<String, String> policies(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("a policy on the user wins over the container, partition, and Login Policy")
        void userAssignmentWins() {
            Map<String, String> map = policies(
                    USER, POLICY,
                    PARENT, OTHER,
                    PARTITION, OTHER,
                    LOGIN, OTHER);

            Assignment found = EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN);

            assertEquals(POLICY, found.policyDN());
            assertEquals(Source.USER, found.source());
            assertEquals(USER, found.assignedOnDN());
        }

        @Test
        @DisplayName("the parent container wins over the partition root and the Login Policy")
        void containerBeatsPartitionAndLoginPolicy() {
            Map<String, String> map = policies(
                    USER, null,
                    PARENT, POLICY,
                    PARTITION, OTHER,
                    LOGIN, OTHER);

            Assignment found = EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN);

            assertEquals(Source.CONTAINER, found.source());
            assertEquals(POLICY, found.policyDN());
            assertTrue(found.sourceText().contains(PARENT));
        }

        @Test
        @DisplayName("a policy on a container that is neither the parent nor the partition root is ignored")
        void intermediateContainerDoesNotApply() {
            // ou=users is between the parent and the partition root, and it is
            // not itself a partition. NMAS does not inherit that assignment.
            Map<String, String> map = policies(
                    USER, null,
                    PARENT, null,
                    MIDDLE, POLICY,
                    PARTITION, null,
                    LOGIN, null);

            assertNull(EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN));
        }

        @Test
        @DisplayName("the partition root applies when the parent container has no policy")
        void partitionRootWhenParentHasNone() {
            Map<String, String> map = policies(
                    USER, "",
                    PARENT, "   ",
                    PARTITION, POLICY,
                    LOGIN, OTHER);

            Assignment found = EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN);

            assertEquals(Source.PARTITION_ROOT, found.source());
            assertEquals(POLICY, found.policyDN());
            assertEquals(PARTITION, found.assignedOnDN());
        }

        @Test
        @DisplayName("a child partition does not inherit the parent partition's policy")
        void childPartitionDoesNotUseParentPartition() {
            String user = "cn=alice,ou=secret,o=acme";
            String childPartition = "ou=secret,o=acme";
            Map<String, String> map = policies(
                    user, null,
                    "ou=secret,o=acme", null,
                    PARTITION, POLICY,
                    LOGIN, null);

            assertNull(EdirPasswordPolicy.resolve(
                    user, map, List.of(PARTITION, childPartition), LOGIN));
        }

        @Test
        @DisplayName("the Login Policy is the default when nothing closer is assigned")
        void loginPolicyIsTheDefault() {
            Map<String, String> map = policies(
                    USER, null,
                    PARENT, null,
                    PARTITION, null,
                    LOGIN, POLICY);

            Assignment found = EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN);

            assertEquals(Source.LOGIN_POLICY, found.source());
            assertEquals(POLICY, found.policyDN());
            assertEquals(LOGIN, found.assignedOnDN());
        }

        @Test
        @DisplayName("no assignment anywhere means legacy NDS rules")
        void nothingAssigned() {
            Map<String, String> map = policies(USER, null, PARENT, null, PARTITION, null, LOGIN, null);
            assertNull(EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), LOGIN));
            assertNull(EdirPasswordPolicy.resolve(USER, map, List.of(PARTITION), null));
        }

        @Test
        @DisplayName("DN spelling differences still match")
        void dnMatchIsCaseInsensitive() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("CN=Alice,OU=Eng,OU=Users,O=ACME", POLICY);

            Assignment found = EdirPasswordPolicy.resolve(USER, map, List.of("O=Acme"), null);

            assertEquals(Source.USER, found.source());
            assertEquals("CN=Alice,OU=Eng,OU=Users,O=ACME", found.assignedOnDN());
        }

        @Test
        @DisplayName("a blank user DN does not resolve")
        void blankUser() {
            assertNull(EdirPasswordPolicy.resolve("  ", Map.of(USER, POLICY), List.of(PARTITION), LOGIN));
            assertNull(EdirPasswordPolicy.resolve(null, Map.of(), List.of(), null));
        }
    }

    @Nested
    @DisplayName("which entries are consulted")
    class SearchList {

        @Test
        @DisplayName("user directly in a partition is not read twice")
        void parentThatIsThePartitionIsListedOnce() {
            String user = "cn=alice,o=acme";
            assertEquals(List.of(user, "o=acme"),
                    EdirPasswordPolicy.policySearchDns(user, List.of("o=acme", "")));
        }

        @Test
        @DisplayName("parent and a different partition root are both listed; intermediate containers are not")
        void parentAndPartitionOnly() {
            List<String> dns = EdirPasswordPolicy.policySearchDns(USER, List.of(PARTITION, "cn=Security"));
            assertEquals(List.of(USER, PARENT, PARTITION), dns);
            assertFalse(dns.contains(MIDDLE));
        }

        @Test
        @DisplayName("escaped comma in the RDN stays in the user DN")
        void escapedCommaParent() {
            String user = "cn=a\\,b,ou=c,o=d";
            assertEquals("ou=c,o=d", EdirPasswordPolicy.parentDn(user));
            assertNull(EdirPasswordPolicy.parentDn("cn=only"));
            assertNull(EdirPasswordPolicy.parentDn(null));
        }

        @Test
        @DisplayName("the nearest naming context is the partition, ignoring spacing")
        void nearestNamingContext() {
            assertEquals("ou=users, o=acme",
                    EdirPasswordPolicy.nearestPartitionRoot(
                            "cn=alice,ou=eng,ou=users,o=acme",
                            List.of("", "o=acme", "ou=users, o=acme", "o=other")));
            // The user entry is not its own partition root.
            assertNull(EdirPasswordPolicy.nearestPartitionRoot("o=acme", List.of("o=acme")));
        }
    }

    @Nested
    @DisplayName("Login Policy location")
    class LoginPolicyDn {

        @Test
        @DisplayName("candidates cover the root Security container and each naming context")
        void candidates() {
            List<String> contexts = List.of("", "o=acme", "cn=Security,o=acme");
            assertEquals(List.of(
                            "cn=Security",
                            "cn=Security,o=acme"),
                    EdirPasswordPolicy.securityContainerCandidates(contexts));
            assertEquals(List.of(
                            "cn=Login Policy,cn=Security",
                            "cn=Login Policy,cn=Security,o=acme"),
                    EdirPasswordPolicy.loginPolicyCandidates(contexts));
        }
    }

    @Nested
    @DisplayName("dialog decisions")
    class DialogDecisions {

        private final Assignment policy = new Assignment(POLICY, Source.USER, USER);
        private final PolicyLookup found = new PolicyLookup(policy, true);
        private final PolicyLookup none = new PolicyLookup(null, true);

        @Test
        @DisplayName("a found policy is shown and Password Modify stays selected")
        void policyFound() {
            String status = EdirPasswordPolicy.describeStatus(found, null);
            assertTrue(status.startsWith("Universal Password policy applies. "));
            assertTrue(status.contains("Assigned to this user."));
            assertFalse(EdirPasswordPolicy.offerMethodChoice(found));
            assertEquals(Method.UNIVERSAL_PASSWORD,
                    EdirPasswordPolicy.defaultMethod(found, true));
        }

        @Test
        @DisplayName("no policy offers the choice and defaults to legacy on LDAPS")
        void noPolicyDefaultsToLegacyWhenSecure() {
            String status = EdirPasswordPolicy.describeStatus(none, null);
            assertTrue(status.contains("No Universal Password policy"));
            assertTrue(status.contains("Login Policy"));
            assertTrue(EdirPasswordPolicy.offerMethodChoice(none));
            assertEquals(Method.LEGACY_NDS,
                    EdirPasswordPolicy.defaultMethod(none, true));
        }

        @Test
        @DisplayName("no policy on a cleartext connection keeps Password Modify")
        void noPolicyOnCleartextStaysUniversal() {
            assertEquals(Method.UNIVERSAL_PASSWORD,
                    EdirPasswordPolicy.defaultMethod(none, false));
        }

        @Test
        @DisplayName("a failed lookup keeps Password Modify and still offers the choice")
        void lookupFailed() {
            String status = EdirPasswordPolicy.describeStatus(null, "insufficient access");
            assertTrue(status.startsWith("Could not check whether a Universal Password policy applies: "));
            assertTrue(status.contains("insufficient access"));
            assertTrue(EdirPasswordPolicy.offerMethodChoice(null));
            assertEquals(Method.UNIVERSAL_PASSWORD,
                    EdirPasswordPolicy.defaultMethod(null, true));
        }

        @Test
        @DisplayName("partition and Login Policy source text names the entry")
        void sourceText() {
            assertEquals("Inherited from partition root " + PARTITION + ".",
                    new Assignment(POLICY, Source.PARTITION_ROOT, PARTITION).sourceText());
            assertEquals("Inherited from the Login Policy (" + LOGIN + ").",
                    new Assignment(POLICY, Source.LOGIN_POLICY, LOGIN).sourceText());
        }
    }

    @Nested
    @DisplayName("when to offer a legacy retry")
    class LegacyRetry {

        @Test
        @DisplayName("result 53 and 19 offer a retry")
        void unwillingAndConstraint() {
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "no")));
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.CONSTRAINT_VIOLATION, "too short")));
        }

        @Test
        @DisplayName("an NMAS diagnostic or response control offers a retry")
        void nmas() {
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.OTHER, "NMAS Change Password extension failed")));
            Control control = new Control(EdirPasswordPolicy.NMAS_CONTROL_PREFIX + "7", false,
                    new ASN1OctetString(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x28}));
            LDAPException withControl = new LDAPException(ResultCode.OTHER, "Password too short",
                    null, null, new Control[] {control}, null);
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(withControl));
        }

        @Test
        @DisplayName("NMAS on the cause is enough when the wrapper text dropped the word")
        void nmasOnTheCause() {
            LDAPException cause = new LDAPException(ResultCode.OTHER,
                    "NMAS Change Password extension failed");
            LDAPException wrapper = new LDAPException(ResultCode.OTHER, "Password too short", cause);
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(wrapper));
        }

        @Test
        @DisplayName("a negative matched DN is an NMAS failure even when the text is not")
        void matchedDnCode() {
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.OTHER, "Password too short", "-216", null)));
            assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.OTHER, "Password too short", "NDS error: too short", null)));
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.OTHER, "Password too short", "42", null)));
        }

        @Test
        @DisplayName("retry only when the lookup succeeded and found no policy")
        void retryRequiresConclusiveNone() {
            LDAPException unwilling = new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "no");
            LDAPException constraint = new LDAPException(ResultCode.CONSTRAINT_VIOLATION, "too short");
            PolicyLookup none = new PolicyLookup(null, true);
            PolicyLookup applied = new PolicyLookup(
                    new Assignment(POLICY, Source.USER, USER), true);
            PolicyLookup unseenPartition = new PolicyLookup(null, false);

            assertTrue(EdirPasswordPolicy.offerLegacyRetry(none, unwilling));
            assertTrue(EdirPasswordPolicy.offerLegacyRetry(none, constraint));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(applied, unwilling));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(unseenPartition, unwilling));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(null, unwilling));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(none,
                    new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS, "no rights")));
        }

        @Test
        @DisplayName("a wrong current password or missing rights is not a legacy retry")
        void otherFailures() {
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.INVALID_CREDENTIALS, "Current password is incorrect.")));
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS, "no rights")));
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                    new LDAPException(ResultCode.OTHER, "server returned other")));
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(null));
            assertFalse(EdirPasswordPolicy.passwordModifySuggestsLegacy(new IllegalStateException("no")));
        }
    }

    @Nested
    @DisplayName("partition root not held on this server")
    class PartitionVisibility {

        @Test
        @DisplayName("an unread closer container blocks a farther naming context")
        void unreadCloserContainerIsNotAParentPartition() {
            // namingContexts would report o=acme, but ou=secret sits between
            // the user and that context and could not be read. It may be the
            // real partition root. Do not treat o=acme as this user's partition.
            PartitionAssessment assessment = EdirPasswordPolicy.assessPartition(List.of(
                    new AncestorProbe("ou=secret,o=acme", false, false)));
            assertFalse(assessment.known());
            assertNull(assessment.partitionRootDn());

            String user = "cn=alice,ou=secret,o=acme";
            Map<String, String> map = policies(
                    user, null,
                    "ou=secret,o=acme", null,
                    PARTITION, POLICY,
                    LOGIN, OTHER);
            PolicyLookup lookup = EdirPasswordPolicy.lookup(user, map, null, false, LOGIN);

            assertNull(lookup.assignment());
            assertFalse(lookup.conclusive());
            String status = EdirPasswordPolicy.describeStatus(lookup, null);
            assertFalse(status.contains("No Universal Password policy"));
            assertTrue(status.toLowerCase().contains("partition"));
            assertEquals(Method.UNIVERSAL_PASSWORD, EdirPasswordPolicy.defaultMethod(lookup, true));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(lookup,
                    new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "NMAS")));
        }

        @Test
        @DisplayName("a replica attribute closer than any naming context is the partition root")
        void replicaBeatsAFartherNamingContext() {
            PartitionAssessment assessment = EdirPasswordPolicy.assessPartition(List.of(
                    new AncestorProbe(PARENT, false, true),
                    new AncestorProbe(MIDDLE, true, true)));
            assertTrue(assessment.known());
            assertEquals(MIDDLE, assessment.partitionRootDn());

            Map<String, String> map = policies(USER, null, PARENT, null, MIDDLE, POLICY, PARTITION, OTHER);
            PolicyLookup lookup = EdirPasswordPolicy.lookup(USER, map, MIDDLE, true, LOGIN);
            assertEquals(Source.PARTITION_ROOT, lookup.assignment().source());
            assertEquals(POLICY, lookup.assignment().policyDN());
            assertTrue(lookup.conclusive());
        }

        @Test
        @DisplayName("a naming context is the root after closer containers are readable and are not")
        void namingContextAfterConfirmedContainers() {
            PartitionAssessment assessment = EdirPasswordPolicy.assessPartition(List.of(
                    new AncestorProbe(PARENT, false, true),
                    new AncestorProbe(MIDDLE, false, true),
                    new AncestorProbe(PARTITION, true, true)));
            assertTrue(assessment.known());
            assertEquals(PARTITION, assessment.partitionRootDn());
        }

        @Test
        @DisplayName("walking off the top without a partition root is not 'no policy'")
        void noPartitionRootOnThisServer() {
            PartitionAssessment assessment = EdirPasswordPolicy.assessPartition(List.of(
                    new AncestorProbe(PARENT, false, true),
                    new AncestorProbe(PARTITION, false, true)));
            assertFalse(assessment.known());

            PolicyLookup lookup = EdirPasswordPolicy.lookup(
                    USER, policies(USER, null, PARENT, null, LOGIN, POLICY), null, false, LOGIN);
            assertNull(lookup.assignment());
            assertFalse(lookup.conclusive());
            assertEquals(Method.UNIVERSAL_PASSWORD, EdirPasswordPolicy.defaultMethod(lookup, true));
        }

        @Test
        @DisplayName("a user or parent policy is still conclusive when the partition is unseen")
        void userAndContainerDoNotNeedThePartition() {
            PolicyLookup onUser = EdirPasswordPolicy.lookup(
                    USER, policies(USER, POLICY), null, false, LOGIN);
            assertTrue(onUser.conclusive());
            assertEquals(Source.USER, onUser.assignment().source());

            PolicyLookup onParent = EdirPasswordPolicy.lookup(
                    USER, policies(USER, null, PARENT, POLICY, LOGIN, OTHER), null, false, LOGIN);
            assertTrue(onParent.conclusive());
            assertEquals(Source.CONTAINER, onParent.assignment().source());
            assertEquals(POLICY, onParent.assignment().policyDN());
            assertEquals(Method.UNIVERSAL_PASSWORD, EdirPasswordPolicy.defaultMethod(onParent, true));
        }
    }

    @Nested
    @DisplayName("secure connection")
    class SecureConnection {

        @Test
        @DisplayName("a connected socket counts only when TLS is actually on")
        void liveSocket() {
            assertTrue(EdirPasswordPolicy.connectionIsSecure(true, true, false, 389));
            assertFalse(EdirPasswordPolicy.connectionIsSecure(true, false, true, 636));
            assertFalse(EdirPasswordPolicy.connectionIsSecure(true, false, false, 389));
        }

        @Test
        @DisplayName("before connect, LDAPS config counts")
        void configOnly() {
            assertTrue(EdirPasswordPolicy.connectionIsSecure(false, false, true, 389));
            assertTrue(EdirPasswordPolicy.connectionIsSecure(false, false, false, 636));
            assertFalse(EdirPasswordPolicy.connectionIsSecure(false, false, false, 389));
        }
    }
}
