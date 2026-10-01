package com.pointbluetech.arborj.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LDAPEntryTest {

    private static LDAPEntry sample() {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("cn", List.of("Alice"));
        attrs.put("mail", List.of("alice@example.com", "a.smith@example.com"));
        attrs.put("objectClass", List.of("top", "person"));
        return new LDAPEntry("cn=Alice,ou=People,dc=example,dc=com", attrs);
    }

    @Test
    @DisplayName("each entry gets a unique id")
    void idIsUnique() {
        LDAPEntry a = sample();
        LDAPEntry b = sample();
        assertNotNull(a.getId());
        assertNotEquals(a.getId(), b.getId());
    }

    @Test
    @DisplayName("getFirstValue returns the first value for a known attribute")
    void getFirstValueExactMatch() {
        assertEquals("Alice", sample().getFirstValue("cn"));
        assertEquals("alice@example.com", sample().getFirstValue("mail"));
    }

    @Test
    @DisplayName("getFirstValue is case-insensitive on attribute names")
    void getFirstValueCaseInsensitive() {
        assertEquals("Alice", sample().getFirstValue("CN"));
        assertEquals("top", sample().getFirstValue("objectclass"));
    }

    @Test
    @DisplayName("getFirstValue returns null for unknown attributes")
    void getFirstValueMissing() {
        assertNull(sample().getFirstValue("sn"));
    }

    @Test
    @DisplayName("getValues returns all values for a known attribute")
    void getValuesReturnsAll() {
        List<String> mail = sample().getValues("mail");
        assertEquals(2, mail.size());
        assertEquals(List.of("alice@example.com", "a.smith@example.com"), mail);
    }

    @Test
    @DisplayName("getValues is case-insensitive")
    void getValuesCaseInsensitive() {
        assertEquals(2, sample().getValues("MAIL").size());
    }

    @Test
    @DisplayName("getValues returns empty list for unknown attributes")
    void getValuesMissing() {
        assertTrue(sample().getValues("telephoneNumber").isEmpty());
    }

    @Test
    @DisplayName("constructor defensively copies the attribute map")
    void constructorCopiesAttributes() {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("cn", List.of("Alice"));
        LDAPEntry entry = new LDAPEntry("cn=Alice", attrs);

        attrs.put("sn", List.of("Smith"));

        assertNull(entry.getFirstValue("sn"),
                "mutating the original map must not leak into the entry");
    }
}
