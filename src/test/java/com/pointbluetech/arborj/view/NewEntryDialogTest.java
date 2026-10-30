package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for New Entry create-payload and RDN combo helpers.
 * Does not construct a Dialog, so no JavaFX runtime is required.
 */
class NewEntryDialogTest {

    @Test
    @DisplayName("create payload includes MAY RDN (cn) plus MUST groupType")
    void createPayloadIncludesMayRdnAndMust() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("groupType", "-2147483646");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "gr2");

        assertEquals(List.of("gr2"), attrs.get("cn"));
        assertEquals(List.of("-2147483646"), attrs.get("groupType"));
        assertEquals(2, attrs.size());
    }

    @Test
    @DisplayName("create payload does not duplicate RDN when it is already a MUST field")
    void createPayloadDoesNotDuplicateMustRdn() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("cn", "Alice");
        fields.put("sn", "Smith");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "Alice");

        assertEquals(List.of("Alice"), attrs.get("cn"));
        assertEquals(List.of("Smith"), attrs.get("sn"));
        assertEquals(2, attrs.size());
    }

    @Test
    @DisplayName("create payload treats CN and cn as the same attribute")
    void createPayloadDedupesRdnCaseInsensitive() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("CN", "Alice");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "Alice");

        assertEquals(List.of("Alice"), attrs.get("cn"));
        assertFalse(attrs.containsKey("CN"));
        assertEquals(1, attrs.size());
    }

    @Test
    @DisplayName("create payload uses the RDN value when a MUST field disagrees")
    void createPayloadRdnValueWinsOverMustField() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("cn", "from-field");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "from-rdn");

        assertEquals(List.of("from-rdn"), attrs.get("cn"));
    }

    @Test
    @DisplayName("create payload with no MUST fields still sends the RDN attribute")
    void createPayloadRdnOnly() {
        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(Map.of(), "ou", "Sales");

        assertEquals(Map.of("ou", List.of("Sales")), attrs);
    }

    @Test
    @DisplayName("blank RDN attribute name defaults to cn")
    void createPayloadDefaultRdnAttrIsCn() {
        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(Map.of(), "  ", "gr2");

        assertEquals(List.of("gr2"), attrs.get("cn"));
    }

    @Test
    @DisplayName("empty MUST field values are omitted")
    void createPayloadOmitsEmptyFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("description", "   ");
        fields.put("groupType", "2");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "gr2");

        assertFalse(attrs.containsKey("description"));
        assertEquals(List.of("2"), attrs.get("groupType"));
        assertEquals(List.of("gr2"), attrs.get("cn"));
    }

    @Test
    @DisplayName("null field map is treated as empty")
    void createPayloadNullFields() {
        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(null, "cn", "gr2");

        assertEquals(Map.of("cn", List.of("gr2")), attrs);
    }

    @Test
    @DisplayName("empty RDN value does not add a blank attribute")
    void createPayloadSkipsEmptyRdnValue() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("groupType", "2");

        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(fields, "cn", "  ");

        assertEquals(Map.of("groupType", List.of("2")), attrs);
    }

    @Test
    @DisplayName("schema naming attributes (rDNAttID) come first in the combo")
    void namingChoicesPreferSchemaRdnAttId() {
        LDAPObjectClassInfo group = new LDAPObjectClassInfo(
                "group", List.of("grouptype"), List.of("cn", "member"),
                "top", List.of("cn"));

        List<String> choices = NewEntryDialog.namingAttributeChoices(List.of(group));

        assertEquals(List.of("cn"), choices);
        assertFalse(choices.contains("grouptype"));
    }

    @Test
    @DisplayName("fallback cn/ou/o/c/dc/uid is used when schema has no naming attributes")
    void namingChoicesFallbackWhenSchemaEmpty() {
        LDAPObjectClassInfo group = new LDAPObjectClassInfo(
                "group", List.of("grouptype"), List.of("cn"), "top", null);

        assertEquals(List.of("cn", "ou", "o", "c", "dc", "uid"),
                NewEntryDialog.namingAttributeChoices(List.of(group)));
        assertEquals(List.of("cn", "ou", "o", "c", "dc", "uid"),
                NewEntryDialog.namingAttributeChoices(List.of()));
    }

    @Test
    @DisplayName("person MUST cn still yields cn (regression)")
    void namingChoicesPersonAndGroup() {
        LDAPObjectClassInfo person = new LDAPObjectClassInfo(
                "person", List.of("cn", "sn"), List.of(), "top", List.of("cn"));
        LDAPObjectClassInfo group = new LDAPObjectClassInfo(
                "group", List.of("grouptype"), List.of("cn"), "top", List.of("cn"));

        assertEquals(List.of("cn"),
                NewEntryDialog.namingAttributeChoices(List.of(person, group)));
    }

    @Test
    @DisplayName("comma is escaped in the DN and sent raw in the Add")
    void rdnEscapesComma() {
        assertEscapedRdn("Smith, John", "cn=Smith\\, John");
    }

    @Test
    @DisplayName("plus is escaped as a value character, not a multi-valued RDN")
    void rdnEscapesPlus() {
        assertEscapedRdn("Smith+John", "cn=Smith\\+John");
    }

    @Test
    @DisplayName("padded RDN value is trimmed: \" Bob \" becomes DN cn=Bob and Add Bob")
    void rdnValueIsTrimmed() {
        assertEquals("cn=Bob", NewEntryDialog.formatRDN("cn", " Bob "));
        assertEquals(List.of("Bob"),
                NewEntryDialog.buildCreateAttributes(Map.of(), "cn", " Bob ").get("cn"));
    }

    @Test
    @DisplayName("leading hash is escaped in the DN and sent raw in the Add")
    void rdnEscapesLeadingHash() {
        assertEscapedRdn("#hash", "cn=\\#hash");
    }

    @Test
    @DisplayName("backslash is escaped in the DN and sent raw in the Add")
    void rdnEscapesBackslash() {
        assertEscapedRdn("foo\\bar", "cn=foo\\\\bar");
    }

    @Test
    @DisplayName("plain RDN value is not escaped")
    void rdnPlainValueUnchanged() {
        assertEscapedRdn("gr2", "cn=gr2");
    }

    @Test
    @DisplayName("typed combo editor text wins over a stale committed value")
    void resolveComboTextPrefersEditor() {
        assertEquals("ou", NewEntryDialog.resolveComboText("ou", "cn"));
        assertEquals("ou", NewEntryDialog.resolveComboText("  ou  ", "cn"));
        assertEquals("cn", NewEntryDialog.resolveComboText("", "cn"));
        assertEquals("cn", NewEntryDialog.resolveComboText("  ", "cn"));
        assertEquals("cn", NewEntryDialog.resolveComboText(null, "cn"));
        assertEquals("", NewEntryDialog.resolveComboText(null, null));
        assertEquals("", NewEntryDialog.resolveComboText("", "  "));
    }

    @Test
    @DisplayName("typed ou with stale cn value produces ou DN and Add, not cn")
    void typedEditorAttrUsedForDnAndAdd() {
        String attr = NewEntryDialog.resolveComboText("ou", "cn");
        assertEquals("ou=Sales", NewEntryDialog.formatRDN(attr, "Sales"));
        Map<String, List<String>> attrs =
                NewEntryDialog.buildCreateAttributes(Map.of(), attr, "Sales");
        assertEquals(List.of("Sales"), attrs.get("ou"));
        assertFalse(attrs.containsKey("cn"));
    }

    @Test
    @DisplayName("attribute type accepts LDAP descr and numeric OID")
    void validAttributeTypes() {
        assertTrue(NewEntryDialog.isValidAttributeType("cn"));
        assertTrue(NewEntryDialog.isValidAttributeType("telephoneNumber"));
        assertTrue(NewEntryDialog.isValidAttributeType("CN"));
        assertTrue(NewEntryDialog.isValidAttributeType("  ou  "));
        assertTrue(NewEntryDialog.isValidAttributeType("2.5.4.3"));
        assertTrue(NewEntryDialog.isValidAttributeType("0.9.2342.19200300.100.1.1"));
    }

    @Test
    @DisplayName("attribute type rejects empty, underscore, and junk")
    void invalidAttributeTypes() {
        assertFalse(NewEntryDialog.isValidAttributeType(null));
        assertFalse(NewEntryDialog.isValidAttributeType(""));
        assertFalse(NewEntryDialog.isValidAttributeType("  "));
        assertFalse(NewEntryDialog.isValidAttributeType("cn=Bob"));
        assertFalse(NewEntryDialog.isValidAttributeType("_cn"));
        assertFalse(NewEntryDialog.isValidAttributeType("cn_name"));
        assertFalse(NewEntryDialog.isValidAttributeType("1cn"));
        assertFalse(NewEntryDialog.isValidAttributeType(".2.5.4.3"));
    }

    /** DN uses RFC 4514 escapes; Add attributes keep the unescaped field text. */
    private static void assertEscapedRdn(String raw, String expectedRdn) {
        assertEquals(expectedRdn, NewEntryDialog.formatRDN("cn", raw));
        assertEquals(List.of(raw),
                NewEntryDialog.buildCreateAttributes(Map.of(), "cn", raw).get("cn"));
    }
}
