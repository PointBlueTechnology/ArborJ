package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaServiceTest {

    @Test
    @DisplayName("collectRDNAttIDs maps classSchema lDAPDisplayName to rDNAttID")
    void collectRdnAttIdsFromClassSchema() {
        LDAPEntry group = classSchema("group", "cn");
        LDAPEntry ou = classSchema("organizationalUnit", "ou");
        LDAPEntry noRdn = classSchema("top", null);

        Map<String, String> ids = SchemaService.collectRDNAttIDs(List.of(group, ou, noRdn));

        assertEquals("cn", ids.get("group"));
        assertEquals("ou", ids.get("organizationalunit"));
        assertFalse(ids.containsKey("top"));
        assertEquals(2, ids.size());
    }

    @Test
    @DisplayName("collectRDNAttIDs lowercases names and skips blank / OID values")
    void collectRdnAttIdsNormalizesAndSkipsOids() {
        LDAPEntry mixed = classSchema("Group", "CN");
        LDAPEntry oid = classSchema("person", "2.5.4.3");
        LDAPEntry blank = classSchema("computer", "  ");

        Map<String, String> ids = SchemaService.collectRDNAttIDs(List.of(mixed, oid, blank));

        assertEquals(Map.of("group", "cn"), ids);
    }

    @Test
    @DisplayName("collectRDNAttIDs is empty for null or empty input")
    void collectRdnAttIdsEmptyInput() {
        assertTrue(SchemaService.collectRDNAttIDs(null).isEmpty());
        assertTrue(SchemaService.collectRDNAttIDs(List.of()).isEmpty());
    }

    @Test
    @DisplayName("looksLikeOid detects numeric dotted OIDs only")
    void looksLikeOid() {
        assertTrue(SchemaService.looksLikeOid("2.5.4.3"));
        assertTrue(SchemaService.looksLikeOid("1.2.840.113556.1.4.750"));
        assertFalse(SchemaService.looksLikeOid("cn"));
        assertFalse(SchemaService.looksLikeOid("ou"));
        assertFalse(SchemaService.looksLikeOid("123"));
        assertFalse(SchemaService.looksLikeOid(""));
        assertFalse(SchemaService.looksLikeOid(null));
    }

    @Test
    @DisplayName("applyRDNAttIDs sets group naming to cn when schema had none")
    void applyRdnAttIdWhenNamingMissing() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("group", oc("group", "top", List.of("grouptype"), List.of("cn"), null));
        map.put("top", oc("top", null, List.of("objectclass"), List.of("cn"), null));

        SchemaService.applyRDNAttIDs(map, Map.of("group", "cn"));

        assertEquals(List.of("cn"), map.get("group").getNamingAttributes());
        assertNull(map.get("top").getNamingAttributes());
    }

    @Test
    @DisplayName("applyRDNAttIDs puts rDNAttID first alongside existing X-NDS_NAMING")
    void applyRdnAttIdPrependsExistingNaming() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("user", oc("user", "top", List.of("cn"), List.of(), List.of("cn", "uid")));

        SchemaService.applyRDNAttIDs(map, Map.of("user", "cn"));

        assertEquals(List.of("cn", "uid"), map.get("user").getNamingAttributes());
    }

    @Test
    @DisplayName("applyRDNAttIDs prepends a distinct rDNAttID ahead of X-NDS_NAMING")
    void applyRdnAttIdAlongsideNdsNaming() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("org", oc("org", "top", List.of(), List.of(), List.of("o")));

        SchemaService.applyRDNAttIDs(map, Map.of("org", "cn"));

        assertEquals(List.of("cn", "o"), map.get("org").getNamingAttributes());
    }

    @Test
    @DisplayName("applyRDNAttIDs inherits rDNAttID from the SUP chain")
    void applyRdnAttIdInheritsFromSuperclass() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("group", oc("group", "top", List.of("grouptype"), List.of("cn"), null));
        map.put("top", oc("top", null, List.of("objectclass"), List.of("cn"), null));

        SchemaService.applyRDNAttIDs(map, Map.of("top", "cn"));

        assertEquals(List.of("cn"), map.get("group").getNamingAttributes());
        assertEquals(List.of("cn"), map.get("top").getNamingAttributes());
    }

    @Test
    @DisplayName("applyRDNAttIDs prefers the class's own rDNAttID over the superclass")
    void applyRdnAttIdPrefersDirectOverInherited() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("organizationalunit", oc("organizationalUnit", "top",
                List.of("ou"), List.of(), null));
        map.put("top", oc("top", null, List.of(), List.of(), null));

        SchemaService.applyRDNAttIDs(map, Map.of(
                "top", "cn",
                "organizationalunit", "ou"));

        assertEquals(List.of("ou"), map.get("organizationalunit").getNamingAttributes());
        assertEquals(List.of("cn"), map.get("top").getNamingAttributes());
    }

    @Test
    @DisplayName("applyRDNAttIDs is a no-op when the rDNAttID map is empty")
    void applyRdnAttIdNoOpWhenEmpty() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        LDAPObjectClassInfo original = oc("group", "top", List.of("grouptype"), List.of("cn"), null);
        map.put("group", original);

        SchemaService.applyRDNAttIDs(map, Map.of());
        SchemaService.applyRDNAttIDs(map, null);

        assertSame(original, map.get("group"));
    }

    @Test
    @DisplayName("applyRDNAttIDs does not loop on a cyclic SUP chain")
    void applyRdnAttIdCyclicSup() {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();
        map.put("a", oc("a", "b", List.of(), List.of(), null));
        map.put("b", oc("b", "a", List.of(), List.of(), null));

        SchemaService.applyRDNAttIDs(map, Map.of("c", "cn"));

        assertNull(map.get("a").getNamingAttributes());
        assertNull(map.get("b").getNamingAttributes());
    }

    private static LDAPEntry classSchema(String displayName, String rdnAttId) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("lDAPDisplayName", List.of(displayName));
        if (rdnAttId != null) {
            attrs.put("rDNAttID", List.of(rdnAttId));
        }
        return new LDAPEntry("CN=" + displayName + ",CN=Schema,CN=Configuration,DC=lab", attrs);
    }

    private static LDAPObjectClassInfo oc(String name, String sup,
                                          List<String> must, List<String> may,
                                          List<String> naming) {
        return new LDAPObjectClassInfo(name, must, may, sup, naming);
    }
}
