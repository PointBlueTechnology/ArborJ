package com.pointbluetech.arborj.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ADHelpersTest {

    @Test
    @DisplayName("isAccountDisabled picks up the 0x0002 bit")
    void accountDisabled() {
        assertTrue(ADHelpers.isAccountDisabled(0x0002));
        assertTrue(ADHelpers.isAccountDisabled(0x0202));     // NORMAL_ACCOUNT | DISABLED
        assertFalse(ADHelpers.isAccountDisabled(0x0200));    // just NORMAL_ACCOUNT
        assertFalse(ADHelpers.isAccountDisabled(0));
    }

    @Test
    @DisplayName("isPasswordNeverExpires picks up the 0x10000 bit")
    void passwordNeverExpires() {
        assertTrue(ADHelpers.isPasswordNeverExpires(0x10000));
        assertTrue(ADHelpers.isPasswordNeverExpires(0x10200)); // NORMAL_ACCOUNT | DONT_EXPIRE
        assertFalse(ADHelpers.isPasswordNeverExpires(0x0200)); // just NORMAL_ACCOUNT
    }

    @Test
    @DisplayName("getSetFlags returns only the matching flags in bit-order")
    void getSetFlagsReturnsMatches() {
        // NORMAL_ACCOUNT (0x200) + DONT_EXPIRE_PASSWORD (0x10000)
        int uac = 0x0200 | 0x10000;
        List<ADHelpers.UACFlag> flags = ADHelpers.getSetFlags(uac);

        assertEquals(2, flags.size());
        List<String> names = flags.stream().map(ADHelpers.UACFlag::name).toList();
        assertTrue(names.contains("NORMAL_ACCOUNT"));
        assertTrue(names.contains("DONT_EXPIRE_PASSWORD"));

        // Bit-order preserved: NORMAL_ACCOUNT (0x200) before DONT_EXPIRE (0x10000).
        assertEquals("NORMAL_ACCOUNT", flags.getFirst().name());
    }

    @Test
    @DisplayName("getSetFlags returns empty list when no bits are set")
    void getSetFlagsEmpty() {
        assertTrue(ADHelpers.getSetFlags(0).isEmpty());
    }

    @Test
    @DisplayName("ALL_FLAGS covers the 21 documented UAC bits")
    void allFlagsExpectedSize() {
        assertEquals(22, ADHelpers.ALL_FLAGS.size());
    }

    @Test
    @DisplayName("read-only bits include system-managed flags")
    void readOnlyBitsIncludeSystemManaged() {
        assertTrue(ADHelpers.READ_ONLY_BITS.contains(0x0010), "LOCKOUT");
        assertTrue(ADHelpers.READ_ONLY_BITS.contains(0x0200), "NORMAL_ACCOUNT");
        assertTrue(ADHelpers.READ_ONLY_BITS.contains(0x800000), "PASSWORD_EXPIRED");
    }

    @Test
    @DisplayName("user-toggleable bits are not in READ_ONLY_BITS")
    void userToggleableBitsNotReadOnly() {
        assertFalse(ADHelpers.READ_ONLY_BITS.contains(0x0002), "ACCOUNTDISABLE");
        assertFalse(ADHelpers.READ_ONLY_BITS.contains(0x10000), "DONT_EXPIRE_PASSWORD");
        assertFalse(ADHelpers.READ_ONLY_BITS.contains(0x40000), "SMARTCARD_REQUIRED");
    }
}
