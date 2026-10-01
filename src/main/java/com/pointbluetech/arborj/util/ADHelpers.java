package com.pointbluetech.arborj.util;

import java.util.List;
import java.util.Set;

/**
 * Active Directory utility functions.
 */
public class ADHelpers {

    public record UACFlag(int bit, String name, String description) {}

    /** All 21 userAccountControl flag definitions in bitmask order. */
    public static final List<UACFlag> ALL_FLAGS = List.of(
            new UACFlag(0x0001,    "SCRIPT",                       "Logon script is executed"),
            new UACFlag(0x0002,    "ACCOUNTDISABLE",                "Account is disabled"),
            new UACFlag(0x0008,    "HOMEDIR_REQUIRED",              "Home directory required"),
            new UACFlag(0x0010,    "LOCKOUT",                       "Account is locked out"),
            new UACFlag(0x0020,    "PASSWD_NOTREQD",                "No password required"),
            new UACFlag(0x0040,    "PASSWD_CANT_CHANGE",            "Cannot change password"),
            new UACFlag(0x0080,    "ENCRYPTED_TEXT_PWD_ALLOWED",    "Encrypted text password allowed"),
            new UACFlag(0x0100,    "TEMP_DUPLICATE_ACCOUNT",        "Account for users in other domains"),
            new UACFlag(0x0200,    "NORMAL_ACCOUNT",                "Default account type"),
            new UACFlag(0x0800,    "INTERDOMAIN_TRUST_ACCOUNT",     "Trust account for a system domain"),
            new UACFlag(0x1000,    "WORKSTATION_TRUST_ACCOUNT",     "Computer account"),
            new UACFlag(0x2000,    "SERVER_TRUST_ACCOUNT",          "Domain controller account"),
            new UACFlag(0x10000,   "DONT_EXPIRE_PASSWORD",          "Password never expires"),
            new UACFlag(0x20000,   "MNS_LOGON_ACCOUNT",             "MNS logon account"),
            new UACFlag(0x40000,   "SMARTCARD_REQUIRED",            "Smart card required for logon"),
            new UACFlag(0x80000,   "TRUSTED_FOR_DELEGATION",        "Trusted for delegation"),
            new UACFlag(0x100000,  "NOT_DELEGATED",                 "Account is sensitive, cannot be delegated"),
            new UACFlag(0x200000,  "USE_DES_KEY_ONLY",              "Use DES encryption types only"),
            new UACFlag(0x400000,  "DONT_REQ_PREAUTH",              "Kerberos pre-authentication not required"),
            new UACFlag(0x800000,  "PASSWORD_EXPIRED",              "Password has expired"),
            new UACFlag(0x1000000, "TRUSTED_TO_AUTH_FOR_DELEGATION","Constrained delegation"),
            new UACFlag(0x4000000, "PARTIAL_SECRETS_ACCOUNT",       "Read-only domain controller")
    );

    /** Flag bits that are system-managed and should not be toggled by users. */
    public static final Set<Integer> READ_ONLY_BITS = Set.of(
            0x0001,     // SCRIPT
            0x0010,     // LOCKOUT
            0x0200,     // NORMAL_ACCOUNT
            0x0800,     // INTERDOMAIN_TRUST_ACCOUNT
            0x1000,     // WORKSTATION_TRUST_ACCOUNT
            0x2000,     // SERVER_TRUST_ACCOUNT
            0x800000,   // PASSWORD_EXPIRED
            0x4000000   // PARTIAL_SECRETS_ACCOUNT
    );

    /** Returns only the flags that are set in the given UAC value. */
    public static List<UACFlag> getSetFlags(int uac) {
        return ALL_FLAGS.stream().filter(f -> (uac & f.bit()) != 0).toList();
    }

    public static boolean isAccountDisabled(int uac) {
        return (uac & 0x0002) != 0;
    }

    public static boolean isPasswordNeverExpires(int uac) {
        return (uac & 0x10000) != 0;
    }
}
