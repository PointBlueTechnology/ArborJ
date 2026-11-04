package com.pointbluetech.arborj.util;

import com.pointbluetech.arborj.util.EdirPasswordPolicy.Method;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.PolicyLookup;
import com.pointbluetech.arborj.util.EdirPasswordPolicy.Status;
import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Integer;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdirPasswordPolicyTest {

    private static final String USER = "cn=alice,ou=eng,o=acme";
    private static final String POLICY = "cn=Sample Password Policy,cn=Password Policies,cn=Security";

    private static byte[] seq(ASN1Element... elements) {
        return new ASN1Sequence(elements).encode();
    }

    private static byte[] concat(ASN1Element... elements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ASN1Element e : elements) out.writeBytes(e.encode());
        return out.toByteArray();
    }

    @Nested
    @DisplayName("request encoding")
    class Request {

        @Test
        @DisplayName("SEQUENCE { INTEGER 1, OCTET STRING userDN }")
        void layout() throws Exception {
            byte[] value = EdirPasswordPolicy.encodeRequest("cn=a").getValue();
            assertEquals("3009020101040463 6e3d61".replace(" ", ""),
                    HexFormat.of().formatHex(value));

            ASN1Element[] elements = ASN1Sequence.decodeAsSequence(value).elements();
            assertEquals(2, elements.length);
            assertEquals(1, ASN1Integer.decodeAsInteger(elements[0]).intValue());
            assertEquals(USER, ASN1OctetString.decodeAsOctetString(
                    ASN1Sequence.decodeAsSequence(EdirPasswordPolicy.encodeRequest(USER).getValue())
                            .elements()[1]).stringValue());
        }

        @Test
        @DisplayName("OIDs are the NMAS Get password policy information pair")
        void oids() {
            assertEquals("2.16.840.1.113719.1.39.42.100.19", EdirPasswordPolicy.GET_POLICY_INFO_REQUEST_OID);
            assertEquals("2.16.840.1.113719.1.39.42.100.20", EdirPasswordPolicy.GET_POLICY_INFO_RESPONSE_OID);
        }
    }

    @Nested
    @DisplayName("response decoding")
    class Response {

        @Test
        @DisplayName("version, error 0, policy DN is a policy")
        void policy() {
            PolicyLookup lookup = EdirPasswordPolicy.decodeResponse(seq(
                    new ASN1Integer(1), new ASN1Integer(0), new ASN1OctetString(POLICY)));
            assertEquals(Status.POLICY, lookup.status());
            assertEquals(POLICY, lookup.policyDN());
        }

        @Test
        @DisplayName("the elements without the outer SEQUENCE decode the same")
        void unwrapped() {
            PolicyLookup lookup = EdirPasswordPolicy.decodeResponse(concat(
                    new ASN1Integer(1), new ASN1Integer(0), new ASN1OctetString(POLICY)));
            assertEquals(Status.POLICY, lookup.status());
            assertEquals(POLICY, lookup.policyDN());
        }

        @Test
        @DisplayName("error 0 with no DN, or an empty DN, is no policy")
        void none() {
            assertEquals(Status.NONE, EdirPasswordPolicy.decodeResponse(seq(
                    new ASN1Integer(1), new ASN1Integer(0))).status());
            assertEquals(Status.NONE, EdirPasswordPolicy.decodeResponse(seq(
                    new ASN1Integer(1), new ASN1Integer(0), new ASN1OctetString(""))).status());
        }

        @Test
        @DisplayName("a non-zero NMAS error is unknown, not no policy")
        void nmasError() {
            PolicyLookup lookup = EdirPasswordPolicy.decodeResponse(seq(
                    new ASN1Integer(1), new ASN1Integer(-1697)));
            assertEquals(Status.UNKNOWN, lookup.status());
            assertEquals("NMAS error -1697", lookup.detail());
        }

        @Test
        @DisplayName("missing, short or garbled values are unknown and show the bytes")
        void garbage() {
            assertEquals(Status.UNKNOWN, EdirPasswordPolicy.decodeResponse(null).status());
            assertEquals(Status.UNKNOWN, EdirPasswordPolicy.decodeResponse(new byte[0]).status());

            PolicyLookup oneInt = EdirPasswordPolicy.decodeResponse(seq(new ASN1Integer(0)));
            assertEquals(Status.UNKNOWN, oneInt.status());
            assertTrue(oneInt.detail().contains("3003020100"), oneInt.detail());

            PolicyLookup junk = EdirPasswordPolicy.decodeResponse(new byte[] {0x30, 0x05, 0x02});
            assertEquals(Status.UNKNOWN, junk.status());
            assertTrue(junk.detail().startsWith("unreadable response"), junk.detail());
        }
    }

    @Nested
    @DisplayName("method choice")
    class Choice {

        @Test
        @DisplayName("Legacy is the default only for NMAS-confirmed no policy over LDAPS")
        void defaults() {
            assertEquals(Method.LEGACY_NDS, EdirPasswordPolicy.defaultMethod(PolicyLookup.none(), true));
            assertEquals(Method.UNIVERSAL_PASSWORD, EdirPasswordPolicy.defaultMethod(PolicyLookup.none(), false));
            assertEquals(Method.UNIVERSAL_PASSWORD,
                    EdirPasswordPolicy.defaultMethod(PolicyLookup.unknown("x"), true));
            assertEquals(Method.UNIVERSAL_PASSWORD,
                    EdirPasswordPolicy.defaultMethod(PolicyLookup.policy(POLICY), true));
            assertEquals(Method.UNIVERSAL_PASSWORD, EdirPasswordPolicy.defaultMethod(null, true));
        }

        @Test
        @DisplayName("the choice is hidden only when a policy applies")
        void offer() {
            assertFalse(EdirPasswordPolicy.offerMethodChoice(PolicyLookup.policy(POLICY)));
            assertTrue(EdirPasswordPolicy.offerMethodChoice(PolicyLookup.none()));
            assertTrue(EdirPasswordPolicy.offerMethodChoice(PolicyLookup.unknown("x")));
            assertTrue(EdirPasswordPolicy.offerMethodChoice(null));
        }

        @Test
        @DisplayName("status text")
        void status() {
            assertEquals("Universal Password policy applies:",
                    EdirPasswordPolicy.describeStatus(PolicyLookup.policy(POLICY), null));
            assertEquals("No Universal Password policy applies to this user.",
                    EdirPasswordPolicy.describeStatus(PolicyLookup.none(), null));
            assertEquals("Could not check whether a Universal Password policy applies: "
                            + "protocol error (2): Unrecognized extended operation. "
                            + "Password Modify stays selected.",
                    EdirPasswordPolicy.describeStatus(PolicyLookup.unknown(
                            "protocol error (2): Unrecognized extended operation"), null));
            assertTrue(EdirPasswordPolicy.describeStatus(null, "boom").contains("boom"));
        }
    }

    @Nested
    @DisplayName("legacy retry")
    class Retry {

        private final LDAPException generic = new LDAPException(ResultCode.OTHER,
                "NMAS Change Password extension failed");

        @Test
        @DisplayName("offered after a Password Modify failure when NMAS said no policy")
        void offered() {
            assertTrue(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(), generic));
            assertTrue(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "x")));
            assertTrue(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new RuntimeException("wrapped", generic)));
        }

        @Test
        @DisplayName("not offered when a policy applies or the lookup was inconclusive")
        void lookupGates() {
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.policy(POLICY), generic));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.unknown("x"), generic));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(null, generic));
        }

        @Test
        @DisplayName("not offered for rights or credential failures")
        void rights() {
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS, "no")));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new LDAPException(ResultCode.INVALID_CREDENTIALS, "no")));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new LDAPException(ResultCode.OTHER, "NMAS failed", "-16049", null)));
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new LDAPException(ResultCode.OTHER, "outer",
                            new LDAPException(ResultCode.OTHER, "NDS", "-672", null))));
        }

        @Test
        @DisplayName("not offered for a non-LDAP failure")
        void nonLdap() {
            assertFalse(EdirPasswordPolicy.offerLegacyRetry(PolicyLookup.none(),
                    new IllegalStateException("boom")));
        }
    }

    @Nested
    @DisplayName("NMAS code extraction")
    class Codes {

        @Test
        @DisplayName("from a response control, then the matched DN")
        void sources() {
            byte[] code = {(byte) 0xFF, (byte) 0xFF, (byte) 0xC1, (byte) 0x4F}; // -16049
            Control control = new Control(EdirPasswordPolicy.NMAS_CONTROL_PREFIX + "1", false,
                    new ASN1OctetString(code));
            assertEquals(-16049, EdirPasswordPolicy.nmasCode(new Control[] {control}, null));
            assertEquals(-216, EdirPasswordPolicy.nmasCode(null, " -216 "));
            assertNull(EdirPasswordPolicy.nmasCode(null, "cn=x"));
            assertNull(EdirPasswordPolicy.nmasCode((Throwable) null));
        }
    }

    @Test
    @DisplayName("a connected socket is secure only when TLS is active")
    void secureConnection() {
        assertTrue(EdirPasswordPolicy.connectionIsSecure(true, true, false, 389));
        assertFalse(EdirPasswordPolicy.connectionIsSecure(true, false, true, 636));
        assertTrue(EdirPasswordPolicy.connectionIsSecure(false, false, true, 389));
        assertTrue(EdirPasswordPolicy.connectionIsSecure(false, false, false, 636));
        assertFalse(EdirPasswordPolicy.connectionIsSecure(false, false, false, 389));
    }
}
