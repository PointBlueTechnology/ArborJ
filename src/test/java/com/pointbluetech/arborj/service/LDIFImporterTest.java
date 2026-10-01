package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.LDAPEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LDIFImporterTest {

    private final LDIFImporter importer = new LDIFImporter(null);

    @Test
    @DisplayName("parses a single entry with multiple attributes")
    void parsesSingleEntry() throws IOException {
        String ldif = """
                dn: cn=Alice,dc=example,dc=com
                objectClass: top
                objectClass: person
                cn: Alice
                sn: Smith
                """;

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(1, entries.size());
        LDAPEntry e = entries.getFirst();
        assertEquals("cn=Alice,dc=example,dc=com", e.getDn());
        assertEquals(List.of("top", "person"), e.getValues("objectClass"));
        assertEquals("Alice", e.getFirstValue("cn"));
        assertEquals("Smith", e.getFirstValue("sn"));
    }

    @Test
    @DisplayName("blank line separates multiple entries")
    void parsesMultipleEntries() throws IOException {
        String ldif = """
                dn: cn=Alice,dc=example,dc=com
                cn: Alice

                dn: cn=Bob,dc=example,dc=com
                cn: Bob
                """;

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(2, entries.size());
        assertEquals("cn=Alice,dc=example,dc=com", entries.get(0).getDn());
        assertEquals("cn=Bob,dc=example,dc=com", entries.get(1).getDn());
    }

    @Test
    @DisplayName("decodes base64-encoded values (::)")
    void decodesBase64Values() throws IOException {
        String encoded = Base64.getEncoder().encodeToString("Tāne".getBytes("UTF-8"));
        String ldif = "dn: cn=Tane,dc=example,dc=com\n"
                + "cn:: " + encoded + "\n";

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(1, entries.size());
        assertEquals("Tāne", entries.getFirst().getFirstValue("cn"));
    }

    @Test
    @DisplayName("unfolds continuation lines (space-prefixed)")
    void unfoldsContinuationLines() throws IOException {
        // RFC 2849: continuation lines start with a single space and are
        // appended to the previous line without that leading space.
        String ldif = "dn: cn=Alice,dc=exa\n"
                + " mple,dc=com\n"
                + "description: a very long\n"
                + "  description that wraps\n";

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(1, entries.size());
        assertEquals("cn=Alice,dc=example,dc=com", entries.getFirst().getDn());
        assertEquals("a very long description that wraps",
                entries.getFirst().getFirstValue("description"));
    }

    @Test
    @DisplayName("ignores LDIF version header")
    void ignoresVersionHeader() throws IOException {
        String ldif = """
                version: 1

                dn: cn=Alice,dc=example,dc=com
                objectClass: person
                cn: Alice
                """;

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(1, entries.size());
        assertEquals("cn=Alice,dc=example,dc=com", entries.getFirst().getDn());
        assertFalse(entries.getFirst().getAttributes().containsKey("version"));
    }

    @Test
    @DisplayName("comment lines starting with # are skipped")
    void ignoresComments() throws IOException {
        String ldif = """
                # top comment
                dn: cn=Alice,dc=example,dc=com
                # mid comment
                cn: Alice
                """;

        List<LDAPEntry> entries = importer.parse(ldif);

        assertEquals(1, entries.size());
        assertEquals("Alice", entries.getFirst().getFirstValue("cn"));
    }

    @Test
    @DisplayName("captures the final entry even with no trailing blank line")
    void capturesTrailingEntry() throws IOException {
        String ldif = "dn: cn=Alice,dc=example,dc=com\ncn: Alice";
        List<LDAPEntry> entries = importer.parse(ldif);
        assertEquals(1, entries.size());
    }

    @Test
    @DisplayName("empty input yields no entries")
    void emptyInputYieldsNothing() throws IOException {
        assertTrue(importer.parse("").isEmpty());
    }
}
