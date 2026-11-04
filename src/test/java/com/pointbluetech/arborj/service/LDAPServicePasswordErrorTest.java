package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.util.EdirPasswordPolicy;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks {@link LDAPService#formatPasswordError} and {@link LDAPService#decodeNmasCode}.
 * The change-password dialog shows this text as-is.
 */
class LDAPServicePasswordErrorTest {

    @Test
    @DisplayName("a matched-DN NMAS code replaces the generic diagnostic")
    void matchedDnCode() {
        String msg = LDAPService.formatPasswordError(
                ResultCode.OTHER, "NMAS Change Password extension failed", "  -216  ", null);

        assertEquals("""
                Password change failed: Password too short

                [result: other (80)]""", msg);
        // The dialog sees the formatted text (no longer says "NMAS") wrapped
        // around the server exception, which still carries the matched DN.
        LDAPException cause = new LDAPException(ResultCode.OTHER,
                "NMAS Change Password extension failed", "-216", null);
        assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(
                new LDAPException(ResultCode.OTHER, msg, cause)));
        assertTrue(EdirPasswordPolicy.passwordModifySuggestsLegacy(cause));
    }

    @Test
    @DisplayName("an NMAS response control is decoded and not repeated")
    void controlCode() {
        byte[] code = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x29}; // -215
        Control control = new Control(EdirPasswordPolicy.NMAS_CONTROL_PREFIX + "1", false,
                new ASN1OctetString(code));

        String msg = LDAPService.formatPasswordError(
                ResultCode.CONSTRAINT_VIOLATION,
                "NMAS Change Password extension failed",
                null,
                new Control[] {control});

        assertEquals("""
                Password change failed: Password unique violation (matches a previous password)

                [result: constraint violation (19)]""", msg);
        assertFalse(msg.substring(msg.indexOf('[')).contains("Password unique violation"),
                "the NMAS reason is not appended a second time in the result line");
    }

    @Test
    @DisplayName("a real diagnostic is kept and the result-code hint is added beside it")
    void realDiagnosticPlusResultHint() {
        String msg = LDAPService.formatPasswordError(
                ResultCode.UNWILLING_TO_PERFORM, "password is fine but no", null, null);

        assertEquals("""
                Password change failed: password is fine but no

                [result: unwilling to perform (53); Server is unwilling to change the password (usually a policy or authentication-method restriction).]""",
                msg);
    }

    @Test
    @DisplayName("result 19, 53, 49, and 50 keep their password-specific sentences")
    void resultCodeSentences() {
        assertEquals("""
                Password change failed: Password rejected by the server's password policy (may be too short, in history, or contain disallowed text).

                [result: constraint violation (19)]""",
                LDAPService.formatPasswordError(ResultCode.CONSTRAINT_VIOLATION, null, null, null));
        assertEquals("""
                Password change failed: Server is unwilling to change the password (usually a policy or authentication-method restriction).

                [result: unwilling to perform (53)]""",
                LDAPService.formatPasswordError(ResultCode.UNWILLING_TO_PERFORM, "  ", null, null));
        assertEquals("""
                Password change failed: Current password is incorrect.

                [result: invalid credentials (49)]""",
                LDAPService.formatPasswordError(ResultCode.INVALID_CREDENTIALS, null, null, null));
        assertEquals("""
                Password change failed: You don't have rights to change this password.

                [result: insufficient access rights (50)]""",
                LDAPService.formatPasswordError(ResultCode.INSUFFICIENT_ACCESS_RIGHTS, null, null, null));
    }

    @Test
    @DisplayName("NDS error text in the matched DN is shown verbatim")
    void ndsErrorMatchedDn() {
        String msg = LDAPService.formatPasswordError(
                ResultCode.OTHER, null, "NDS error: password too short (-216)", null);
        assertTrue(msg.startsWith("Password change failed: NDS error: password too short (-216)"));
    }

    @Test
    @DisplayName("a short or unrelated control is ignored")
    void ignoredControls() {
        Control shortValue = new Control(EdirPasswordPolicy.NMAS_CONTROL_PREFIX + "2", false,
                new ASN1OctetString(new byte[] {1, 2}));
        Control otherOid = new Control("1.2.3.4", false,
                new ASN1OctetString(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x28}));

        String msg = LDAPService.formatPasswordError(
                ResultCode.OTHER, null, "42", new Control[] {shortValue, otherOid});

        assertEquals("""
                Password change failed: server returned 80 (other)

                [result: other (80)]""", msg);
    }

    @Test
    @DisplayName("known NMAS codes and the unknown-code fallback")
    void decodeNmasCodes() {
        assertEquals("Password unique violation (matches a previous password)",
                LDAPService.decodeNmasCode(-215));
        assertEquals("Password too short", LDAPService.decodeNmasCode(-216));
        assertEquals("Password too long", LDAPService.decodeNmasCode(-217));
        assertEquals("Password too simple (does not meet complexity requirements)",
                LDAPService.decodeNmasCode(-218));
        assertEquals("Password in history", LDAPService.decodeNmasCode(-219));
        assertEquals("Password expired", LDAPService.decodeNmasCode(-222));
        assertEquals("Insufficient rights to change this password",
                LDAPService.decodeNmasCode(-224));
        assertEquals("Password is the user's name or otherwise disallowed",
                LDAPService.decodeNmasCode(-226));
        assertEquals("Password policy violation", LDAPService.decodeNmasCode(-1697));
        assertEquals("NMAS: insufficient rights for password operation",
                LDAPService.decodeNmasCode(-16049));
        assertEquals("NMAS: password policy disallows this password",
                LDAPService.decodeNmasCode(-16066));
        assertEquals("NMAS error -1", LDAPService.decodeNmasCode(-1));
        assertNull(LDAPService.decodeNmasCode(0));
        assertNull(LDAPService.decodeNmasCode(42));
    }
}
