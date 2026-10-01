package com.pointbluetech.arborj.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link HexEditorDialog#parseHexInput(String)}.
 */
class HexEditorDialogTest {

    @Test
    @DisplayName("parses contiguous hex string")
    void contiguousHex() {
        assertArrayEquals(new byte[]{0x01, 0x23, (byte) 0xab, (byte) 0xcd},
                HexEditorDialog.parseHexInput("0123ABCD"));
    }

    @Test
    @DisplayName("strips whitespace, commas, colons between bytes")
    void separators() {
        byte[] expected = {0x01, 0x02, 0x03};
        assertArrayEquals(expected, HexEditorDialog.parseHexInput("01 02 03"));
        assertArrayEquals(expected, HexEditorDialog.parseHexInput("01:02:03"));
        assertArrayEquals(expected, HexEditorDialog.parseHexInput("01,02,03"));
        assertArrayEquals(expected, HexEditorDialog.parseHexInput("01\t02\n03"));
    }

    @Test
    @DisplayName("accepts the dialog's own dump format (offset + hex + ASCII)")
    void dumpFormat() {
        // Mimics the format produced by buildHexLines for 16 bytes:
        // "0000:     48 65 6C 6C 6F 20 57 6F  72 6C 64 21 0A 0D 00 FF  Hello World!....."
        String dump = "0000:     48 65 6C 6C 6F 20 57 6F  72 6C 64 21 0A 0D 00 FF  Hello World!.....\n";
        byte[] expected = {0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x57, 0x6F,
                0x72, 0x6C, 0x64, 0x21, 0x0A, 0x0D, 0x00, (byte) 0xFF};
        assertArrayEquals(expected, HexEditorDialog.parseHexInput(dump));
    }

    @Test
    @DisplayName("multi-line dump format round-trips")
    void multiLineDump() {
        String dump = "0000:     01 02 03 04 05 06 07 08  09 0A 0B 0C 0D 0E 0F 10  ................\n"
                    + "0010:     11 12                                              ..\n";
        byte[] expected = new byte[18];
        for (int i = 0; i < 18; i++) expected[i] = (byte) (i + 1);
        assertArrayEquals(expected, HexEditorDialog.parseHexInput(dump));
    }

    @Test
    @DisplayName("blank input yields empty byte array")
    void blankInput() {
        assertArrayEquals(new byte[0], HexEditorDialog.parseHexInput(""));
        assertArrayEquals(new byte[0], HexEditorDialog.parseHexInput("   \n  "));
        assertArrayEquals(new byte[0], HexEditorDialog.parseHexInput(null));
    }

    @Test
    @DisplayName("odd number of hex digits is rejected")
    void oddDigits() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> HexEditorDialog.parseHexInput("ABC"));
        assertTrue(ex.getMessage().toLowerCase().contains("odd"));
    }

    @Test
    @DisplayName("non-hex characters are rejected")
    void nonHexCharacter() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> HexEditorDialog.parseHexInput("01 GG 03"));
        assertTrue(ex.getMessage().toLowerCase().contains("non-hex"));
    }

    @Test
    @DisplayName("offset prefix without a hex value (just the offset line) is tolerated")
    void offsetOnlyLineSkipped() {
        // After stripping the offset and the gutter, the line is empty —
        // should not produce any bytes and should not error.
        assertArrayEquals(new byte[0], HexEditorDialog.parseHexInput("0000:     \n"));
    }

    @Test
    @DisplayName("the four high-bit ASCII bytes survive a round-trip through gutter stripping")
    void highBitBytesRoundTrip() {
        // The dialog's gutter renders bytes >= 0x80 as '.', so the gutter
        // stripping must not strip any byte data above 0x7F either.
        String dump = "0000:     80 81 FE FF                                      ....\n";
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0xFE, (byte) 0xFF},
                HexEditorDialog.parseHexInput(dump));
    }
}
