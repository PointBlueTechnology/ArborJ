package com.pointbluetech.arborj.view.edir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointbluetech.arborj.view.edir.AssociationModifierView.ExportEntry;
import com.pointbluetech.arborj.view.edir.AssociationModifierView.ExportFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssociationExportFileTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("round-trips version, timestamp, source driver, and entries")
    void roundTrip() throws Exception {
        ExportFile original = new ExportFile(
                1,
                "2026-04-21T00:00:00Z",
                "cn=DevAD,cn=DriverSet,o=system",
                List.of(
                        new ExportEntry("cn=alice,ou=People,o=data", 1, "CN=alice,OU=Users,DC=ex,DC=com"),
                        new ExportEntry("cn=bob,ou=People,o=data", 0, "CN=bob,OU=Users,DC=ex,DC=com")));

        String json = mapper.writeValueAsString(original);
        ExportFile decoded = mapper.readValue(json, ExportFile.class);

        assertEquals(1, decoded.version());
        assertEquals("2026-04-21T00:00:00Z", decoded.exportedAt());
        assertEquals("cn=DevAD,cn=DriverSet,o=system", decoded.sourceDriverDN());
        assertEquals(2, decoded.associations().size());
        assertEquals("cn=alice,ou=People,o=data", decoded.associations().get(0).dn());
        assertEquals(1, decoded.associations().get(0).state());
        assertEquals("CN=alice,OU=Users,DC=ex,DC=com", decoded.associations().get(0).value());
    }

    @Test
    @DisplayName("tolerates unknown future fields")
    void ignoresUnknownFields() throws Exception {
        String json = """
                {
                  "version": 2,
                  "exportedAt": "2026-04-21T00:00:00Z",
                  "sourceDriverDN": "cn=X",
                  "futureField": "ignore me",
                  "associations": [
                    {"dn": "cn=a", "state": 1, "value": "v", "extraField": 42}
                  ]
                }
                """;

        ExportFile decoded = mapper.readValue(json, ExportFile.class);

        assertEquals(2, decoded.version());
        assertEquals(1, decoded.associations().size());
        assertEquals("cn=a", decoded.associations().getFirst().dn());
    }

    @Test
    @DisplayName("omits nothing essential in serialized form")
    void serializedShape() throws Exception {
        ExportFile f = new ExportFile(1, "now", "cn=Driver", List.of(new ExportEntry("cn=x", 1, "v")));
        String json = mapper.writeValueAsString(f);

        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("\"sourceDriverDN\":\"cn=Driver\""));
        assertTrue(json.contains("\"associations\""));
        assertTrue(json.contains("\"dn\":\"cn=x\""));
        assertTrue(json.contains("\"state\":1"));
        assertTrue(json.contains("\"value\":\"v\""));
    }
}
