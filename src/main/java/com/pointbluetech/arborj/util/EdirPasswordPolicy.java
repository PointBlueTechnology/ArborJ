package com.pointbluetech.arborj.util;

import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Exception;
import com.unboundid.asn1.ASN1Integer;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;

import java.util.HexFormat;
import java.util.Set;

/**
 * Which Universal Password policy, if any, applies to an eDirectory user.
 *
 * <p>NMAS resolves the effective policy (user, container, partition root,
 * Login Policy) itself. {@code nspmPasswordPolicyDN} only shows an explicit
 * assignment on the entry that holds it, so reading it cannot tell whether a
 * policy is inherited. The NMAS "Get password policy information" extended
 * operation asks the server instead:
 *
 * <pre>
 * requestName  2.16.840.1.113719.1.39.42.100.19
 * requestValue SEQUENCE { nmasVersion INTEGER (1), objectDN OCTET STRING }
 *
 * responseName 2.16.840.1.113719.1.39.42.100.20
 * responseValue SEQUENCE { nmasVersion INTEGER, nmasError INTEGER,
 *                          policyDN OCTET STRING OPTIONAL }
 * </pre>
 *
 * The layout follows the other NMAS LDAP extensions (Get/Set Password).
 * {@link #decodeResponse} accepts the elements with or without the outer
 * SEQUENCE and reports anything else as {@link Status#UNKNOWN} with the raw
 * bytes, so an unexpected layout never reads as "no policy".
 *
 * <p>No policy means the tree is still on legacy NDS password rules.
 * Password Modify (RFC 3062) is an NMAS operation and fails there; a
 * {@code userPassword} replace over LDAPS is what works.
 */
public final class EdirPasswordPolicy {

    public static final String GET_POLICY_INFO_REQUEST_OID = "2.16.840.1.113719.1.39.42.100.19";
    public static final String GET_POLICY_INFO_RESPONSE_OID = "2.16.840.1.113719.1.39.42.100.20";
    public static final int NMAS_LDAP_EXT_VERSION = 1;

    /** eDirectory NMAS response controls: {@code 2.16.840.1.113719.1.27.103.*}. */
    public static final String NMAS_CONTROL_PREFIX = "2.16.840.1.113719.1.27.103.";

    /**
     * NMAS / NDS codes that mean the bound account may not set this password.
     * A legacy replace would only hit the same wall, so it is not offered.
     * -16049 NMAS insufficient rights, -672 NDS no access.
     */
    private static final Set<Integer> RIGHTS_CODES = Set.of(-16049, -672);

    private EdirPasswordPolicy() {
    }

    public enum Status {
        /** NMAS returned a policy DN. */
        POLICY,
        /** NMAS answered successfully with no policy DN. */
        NONE,
        /** The operation failed, is not supported, or answered in an unexpected form. */
        UNKNOWN
    }

    public enum Method {
        UNIVERSAL_PASSWORD,
        LEGACY_NDS
    }

    /** Result of the policy check. {@code detail} explains an UNKNOWN result. */
    public record PolicyLookup(Status status, String policyDN, String detail) {
        public static PolicyLookup policy(String dn) {
            return new PolicyLookup(Status.POLICY, dn, null);
        }

        public static PolicyLookup none() {
            return new PolicyLookup(Status.NONE, null, null);
        }

        public static PolicyLookup unknown(String detail) {
            return new PolicyLookup(Status.UNKNOWN, null, detail);
        }

        public boolean foundNone() {
            return status == Status.NONE;
        }

        public boolean hasPolicy() {
            return status == Status.POLICY;
        }
    }

    /** BER request value for the Get password policy information operation. */
    public static ASN1OctetString encodeRequest(String userDN) {
        ASN1Sequence seq = new ASN1Sequence(
                new ASN1Integer(NMAS_LDAP_EXT_VERSION),
                new ASN1OctetString(userDN == null ? "" : userDN));
        return new ASN1OctetString(seq.encode());
    }

    /**
     * Decode the response value. A missing value, a non-zero NMAS error, or
     * a layout that is not {version, error[, policyDN]} is UNKNOWN.
     */
    public static PolicyLookup decodeResponse(byte[] value) {
        if (value == null || value.length == 0) {
            return PolicyLookup.unknown("the server returned no response value");
        }
        ASN1Element[] elements;
        try {
            byte[] wrapped = value[0] == 0x30
                    ? value
                    : new ASN1Element((byte) 0x30, value).encode();
            elements = ASN1Sequence.decodeAsSequence(wrapped).elements();
        } catch (ASN1Exception e) {
            return PolicyLookup.unknown("unreadable response " + hex(value));
        }

        Integer version = null;
        Integer error = null;
        String policyDN = null;
        try {
            for (ASN1Element element : elements) {
                if (element.getType() == ASN1Constants.INTEGER) {
                    int n = ASN1Integer.decodeAsInteger(element).intValue();
                    if (version == null) version = n;
                    else if (error == null) error = n;
                } else if (element.getType() == ASN1Constants.OCTET_STRING && policyDN == null) {
                    policyDN = ASN1OctetString.decodeAsOctetString(element).stringValue();
                }
            }
        } catch (ASN1Exception e) {
            return PolicyLookup.unknown("unreadable response " + hex(value));
        }

        if (version == null || error == null) {
            return PolicyLookup.unknown("unexpected response " + hex(value));
        }
        if (error != 0) {
            return PolicyLookup.unknown("NMAS error " + error);
        }
        if (policyDN == null || policyDN.isBlank()) {
            return PolicyLookup.none();
        }
        return PolicyLookup.policy(policyDN.trim());
    }

    /** Status line for the change-password dialog. The policy DN is shown separately. */
    public static String describeStatus(PolicyLookup lookup, String failureMessage) {
        if (failureMessage != null && !failureMessage.isBlank()) {
            return "Could not check whether a Universal Password policy applies: "
                    + failureMessage + ". Password Modify stays selected.";
        }
        if (lookup == null) {
            return "Could not check whether a Universal Password policy applies. "
                    + "Password Modify stays selected.";
        }
        return switch (lookup.status()) {
            case POLICY -> "Universal Password policy applies:";
            case NONE -> "No Universal Password policy applies to this user.";
            case UNKNOWN -> "Could not check whether a Universal Password policy applies: "
                    + lookup.detail() + ". Password Modify stays selected.";
        };
    }

    /** Offer the Universal Password / Legacy NDS choice unless a policy was found. */
    public static boolean offerMethodChoice(PolicyLookup lookup) {
        return lookup == null || !lookup.hasPolicy();
    }

    /** Legacy NDS only when NMAS said there is no policy and the connection is LDAPS. */
    public static Method defaultMethod(PolicyLookup lookup, boolean secureConnection) {
        if (lookup == null || !lookup.foundNone() || !secureConnection) {
            return Method.UNIVERSAL_PASSWORD;
        }
        return Method.LEGACY_NDS;
    }

    /**
     * Offer a {@code userPassword} retry after a Password Modify failure only
     * when NMAS said no policy applies and the failure is not a rights
     * problem. Invalid credentials and insufficient access are shown as-is.
     */
    public static boolean offerLegacyRetry(PolicyLookup lookup, Throwable error) {
        if (lookup == null || !lookup.foundNone()) return false;
        LDAPException ldap = null;
        for (Throwable t = error; t != null && ldap == null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LDAPException e) ldap = e;
        }
        if (ldap == null) return false;
        ResultCode rc = ldap.getResultCode();
        if (rc == ResultCode.INVALID_CREDENTIALS || rc == ResultCode.INSUFFICIENT_ACCESS_RIGHTS) {
            return false;
        }
        Integer code = nmasCode(error);
        return code == null || !RIGHTS_CODES.contains(code);
    }

    /**
     * NMAS error code from the first exception in the cause chain that has
     * one: an NMAS response control (first 4 bytes, big-endian) or a matched
     * DN that is just the number.
     */
    public static Integer nmasCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!(t instanceof LDAPException ldap)) continue;
            Integer code = nmasCode(ldap.getResponseControls(), ldap.getMatchedDN());
            if (code != null) return code;
        }
        return null;
    }

    public static Integer nmasCode(Control[] controls, String matchedDN) {
        if (controls != null) {
            for (Control c : controls) {
                if (c == null || c.getOID() == null || !c.getOID().startsWith(NMAS_CONTROL_PREFIX)) continue;
                ASN1OctetString val = c.getValue();
                byte[] bytes = val == null ? null : val.getValue();
                if (bytes != null && bytes.length >= 4) {
                    return (bytes[0] << 24) | ((bytes[1] & 0xFF) << 16)
                            | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                }
            }
        }
        if (matchedDN != null && matchedDN.trim().matches("-?\\d+")) {
            try {
                return Integer.parseInt(matchedDN.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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

    private static String hex(byte[] value) {
        return "(" + HexFormat.of().formatHex(value) + ")";
    }

    /** Universal-class tags used in the response. */
    private static final class ASN1Constants {
        static final byte INTEGER = 0x02;
        static final byte OCTET_STRING = 0x04;
    }
}
