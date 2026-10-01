package com.pointbluetech.arborj.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class NDSNetAddressTest {

    // --- AddressType enum ---

    @Test
    @DisplayName("AddressType.fromValue maps known codes, returns null for unknown")
    void addressTypeFromValue() {
        assertEquals(NDSNetAddress.AddressType.IP, NDSNetAddress.AddressType.fromValue(1));
        assertEquals(NDSNetAddress.AddressType.TCP, NDSNetAddress.AddressType.fromValue(9));
        assertEquals(NDSNetAddress.AddressType.URL, NDSNetAddress.AddressType.fromValue(12));
        assertNull(NDSNetAddress.AddressType.fromValue(99));
    }

    // --- decode(byte[]) ---

    @Test
    @DisplayName("decode parses ASCII type + # + payload")
    void decodeIpv4() {
        // Type 1 (IP), port 389 (0x0185), IP 10.1.2.3
        byte[] raw = new byte[] { '1', '#', 0x01, (byte) 0x85, 10, 1, 2, 3 };
        NDSNetAddress addr = NDSNetAddress.decode(raw);
        assertNotNull(addr);
        assertEquals(NDSNetAddress.AddressType.IP, addr.getType());
        assertEquals("IP 10.1.2.3:389", addr.getDisplayString());
    }

    @Test
    @DisplayName("decode returns null when # is missing")
    void decodeMissingSeparator() {
        assertNull(NDSNetAddress.decode(new byte[] { '1', '2', '3' }));
    }

    @Test
    @DisplayName("decode returns null when type is not a number")
    void decodeNonNumericType() {
        assertNull(NDSNetAddress.decode(new byte[] { 'X', '#', 0x00 }));
    }

    @Test
    @DisplayName("decode returns null when type is an unknown enum value")
    void decodeUnknownType() {
        assertNull(NDSNetAddress.decode(new byte[] { '9', '9', '#', 0x00 }));
    }

    // --- decodeFromHex ---

    @Test
    @DisplayName("decodeFromHex reconstructs bytes from hex")
    void decodeFromHex() {
        // '1' = 0x31, '#' = 0x23 — "31 23 00 50 C0 A8 00 01" => port 80, IP 192.168.0.1
        NDSNetAddress addr = NDSNetAddress.decodeFromHex("31230050C0A80001");
        assertNotNull(addr);
        assertEquals(NDSNetAddress.AddressType.IP, addr.getType());
        assertEquals("IP 192.168.0.1:80", addr.getDisplayString());
    }

    @Test
    @DisplayName("decodeFromHex rejects odd-length or empty input")
    void decodeFromHexInvalid() {
        assertNull(NDSNetAddress.decodeFromHex(null));
        assertNull(NDSNetAddress.decodeFromHex(""));
        assertNull(NDSNetAddress.decodeFromHex("ABC")); // odd length
    }

    // --- getDisplayString ---

    @Test
    @DisplayName("IP/TCP/UDP with port 0 omits the port")
    void ipZeroPortOmittedFromDisplay() {
        byte[] raw = new byte[] { '1', '#', 0x00, 0x00, 10, 0, 0, 1 };
        assertEquals("IP 10.0.0.1", NDSNetAddress.decode(raw).getDisplayString());
    }

    @Test
    @DisplayName("IPv6 (TCP6) builds a bracketed port-suffixed address")
    void ipv6Display() {
        byte[] payload = new byte[18];
        payload[0] = 0x01; payload[1] = (byte) 0x85; // port 389
        // bytes 2..17 = ::1 (all zero except last)
        payload[17] = 0x01;
        NDSNetAddress addr = new NDSNetAddress(NDSNetAddress.AddressType.TCP6, payload);
        String s = addr.getDisplayString();
        assertTrue(s.startsWith("TCP6 ["), "got: " + s);
        assertTrue(s.endsWith("]:389"), "got: " + s);
        assertTrue(s.contains("0001"), "low word should show 0001 — got: " + s);
    }

    @Test
    @DisplayName("URL type returns the payload as UTF-8")
    void urlTypeDisplay() {
        byte[] url = "ldap://example.com".getBytes(StandardCharsets.UTF_8);
        NDSNetAddress addr = new NDSNetAddress(NDSNetAddress.AddressType.URL, url);
        assertEquals("ldap://example.com", addr.getDisplayString());
    }

    @Test
    @DisplayName("IP with too-short payload falls back to '(invalid)'")
    void ipTooShortIsInvalid() {
        NDSNetAddress addr = new NDSNetAddress(NDSNetAddress.AddressType.IP, new byte[] { 1, 2 });
        assertEquals("IP (invalid)", addr.getDisplayString());
    }

    @Test
    @DisplayName("IPX builds an uppercase-hex string")
    void ipxDisplay() {
        NDSNetAddress addr = new NDSNetAddress(NDSNetAddress.AddressType.IPX,
                new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF });
        assertEquals("IPX DEADBEEF", addr.getDisplayString());
    }
}
