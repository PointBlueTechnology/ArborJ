package com.pointbluetech.arborj.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link AttributePickerDialog#parseAttributeList(String)}.
 * Doesn't construct a Stage so no JavaFX runtime is required.
 */
class AttributePickerDialogTest {

    @Test
    @DisplayName("comma-separated list parses to trimmed names")
    void commaSeparated() {
        assertEquals(List.of("cn", "sn", "mail"),
                AttributePickerDialog.parseAttributeList("cn, sn, mail"));
    }

    @Test
    @DisplayName("whitespace alone separates names")
    void whitespaceSeparated() {
        assertEquals(List.of("cn", "sn", "mail"),
                AttributePickerDialog.parseAttributeList("cn  sn\tmail"));
    }

    @Test
    @DisplayName("newlines separate names")
    void newlineSeparated() {
        assertEquals(List.of("cn", "sn", "mail"),
                AttributePickerDialog.parseAttributeList("cn\nsn\r\nmail"));
    }

    @Test
    @DisplayName("mixed delimiters work")
    void mixedDelimiters() {
        assertEquals(List.of("cn", "sn", "mail", "telephoneNumber"),
                AttributePickerDialog.parseAttributeList("cn,sn ; mail\ntelephoneNumber"));
    }

    @Test
    @DisplayName("dedupes case-insensitively but keeps first casing")
    void dedupesCaseInsensitive() {
        assertEquals(List.of("CN", "sn"),
                AttributePickerDialog.parseAttributeList("CN, cn, sn, SN"));
    }

    @Test
    @DisplayName("strips wrapping double or single quotes")
    void stripsQuotes() {
        assertEquals(List.of("cn", "sn"),
                AttributePickerDialog.parseAttributeList("\"cn\", 'sn'"));
    }

    @Test
    @DisplayName("empty and null inputs yield empty list")
    void emptyInputs() {
        assertTrue(AttributePickerDialog.parseAttributeList("").isEmpty());
        assertTrue(AttributePickerDialog.parseAttributeList("   ").isEmpty());
        assertTrue(AttributePickerDialog.parseAttributeList(null).isEmpty());
    }

    @Test
    @DisplayName("preserves * (all attributes) marker")
    void preservesStar() {
        assertEquals(List.of("*", "cn"),
                AttributePickerDialog.parseAttributeList("*, cn"));
    }

    @Test
    @DisplayName("trailing and leading delimiters are tolerated")
    void trimsTrailingAndLeading() {
        assertEquals(List.of("cn", "sn"),
                AttributePickerDialog.parseAttributeList(",,cn,, sn,, "));
    }
}
