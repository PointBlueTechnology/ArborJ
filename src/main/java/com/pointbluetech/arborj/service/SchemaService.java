package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.*;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassType;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and caches LDAP schema information.
 * Handles directory type detection, container class inference,
 * attribute syntax mapping, and objectClass info.
 */
public class SchemaService {

    private static final Set<String> WELL_KNOWN_CONTAINERS = Set.of(
            "domain", "organization", "organizationalunit", "country", "locality", "dcobject",
            // Active Directory
            "container", "configuration", "grouppolicycontainer", "domaindns",
            "msdfsr-replicationgroup", "builtindomain",
            // OpenLDAP/FreeIPA
            "nisdomainobject", "groupofnames", "krbrealmcontainer",
            // eDirectory
            "ndscountry", "organizationalrole", "resource", "server", "ndsprofile",
            "treeroot", "ncp server", "volume"
    );

    private static final Map<String, LDAPAttributeSyntax> OID_TO_SYNTAX = buildOidToSyntaxMap();

    private final LDAPService ldapService;

    private DirectoryType detectedType = DirectoryType.AUTO;
    private Set<String> containerClasses;
    private Map<String, LDAPAttributeInfo> attributeMap;
    private Map<String, LDAPObjectClassInfo> objectClassMap;

    public SchemaService(LDAPService ldapService) {
        this.ldapService = ldapService;
    }

    // --- Directory Type Detection ---

    public DirectoryType detectDirectoryType() throws LDAPException {
        Map<String, List<String>> rootDSE = ldapService.fetchRootDSE();

        // eDirectory: vendor name contains Novell/NetIQ/OpenText/MicroFocus
        String vendorName = firstValue(rootDSE, "vendorName");
        String vendorVersion = firstValue(rootDSE, "vendorVersion");
        if (vendorName != null) {
            String lower = vendorName.toLowerCase();
            if (lower.contains("novell") || lower.contains("netiq") ||
                    lower.contains("opentext") || lower.contains("micro focus")) {
                detectedType = DirectoryType.EDIRECTORY;
                return detectedType;
            }
        }
        if (vendorVersion != null) {
            String lower = vendorVersion.toLowerCase();
            if (lower.contains("edirectory") || lower.contains("edir")) {
                detectedType = DirectoryType.EDIRECTORY;
                return detectedType;
            }
        }

        // Active Directory: forestFunctionality or rootDomainNamingContext
        if (rootDSE.containsKey("forestFunctionality") || rootDSE.containsKey("rootDomainNamingContext")
                || rootDSE.containsKey("defaultNamingContext") || rootDSE.containsKey("schemaNamingContext")) {
            detectedType = DirectoryType.ACTIVE_DIRECTORY;
            return detectedType;
        }

        // OpenLDAP/389DS
        if (rootDSE.containsKey("subschemaSubentry")) {
            detectedType = DirectoryType.OPENLDAP;
            return detectedType;
        }

        detectedType = DirectoryType.GENERIC;
        return detectedType;
    }

    public DirectoryType getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(DirectoryType type) {
        this.detectedType = type;
    }

    // --- Schema Loading ---

    /**
     * Load full schema: container classes, attribute map, and objectClass map.
     */
    public void loadSchema() throws LDAPException {
        if (detectedType == DirectoryType.AUTO) {
            detectDirectoryType();
        }
        // Fetch subschema ONCE and reuse for all loading
        var schema = fetchSubschema();
        containerClasses = loadContainerClasses(schema);
        attributeMap = loadAttributeMap(schema);
        objectClassMap = loadObjectClassMap(schema);
    }

    public Set<String> getContainerClasses() {
        return containerClasses != null ? containerClasses : WELL_KNOWN_CONTAINERS;
    }

    public Map<String, LDAPAttributeInfo> getAttributeMap() {
        return attributeMap != null ? attributeMap : Map.of();
    }

    public Map<String, LDAPObjectClassInfo> getObjectClassMap() {
        return objectClassMap != null ? objectClassMap : Map.of();
    }

    /**
     * Returns the set of attribute names whose schema syntax is BINARY.
     */
    public Set<String> getBinaryAttributeNames() {
        Set<String> names = new HashSet<>();
        if (attributeMap != null) {
            for (var entry : attributeMap.entrySet()) {
                if (entry.getValue().getSyntax() == LDAPAttributeSyntax.BINARY
                        || entry.getValue().getSyntax() == LDAPAttributeSyntax.NET_ADDRESS) {
                    names.add(entry.getKey());
                }
            }
        }
        return names;
    }

    public boolean isContainer(List<String> objectClasses) {
        Set<String> containers = getContainerClasses();
        for (String oc : objectClasses) {
            if (containers.contains(oc.toLowerCase())) return true;
        }
        return false;
    }

    // --- Container Class Loading ---

    private Set<String> loadContainerClasses(Schema schema) throws LDAPException {
        Set<String> result = new HashSet<>(WELL_KNOWN_CONTAINERS);

        try {
            switch (detectedType) {
                case ACTIVE_DIRECTORY -> loadADContainerClasses(result);
                case EDIRECTORY -> loadEDirectoryContainerClasses(result, schema);
                default -> loadRFCContainerClasses(result, schema);
            }
        } catch (LDAPException e) {
            System.err.println("Failed to load container classes from schema, using defaults: " + e.getMessage());
        }

        return result;
    }

    private void loadADContainerClasses(Set<String> result) throws LDAPException {
        // Get schema naming context
        Map<String, List<String>> rootDSE = ldapService.fetchRootDSE();
        String schemaDN = firstValue(rootDSE, "schemaNamingContext");
        if (schemaDN == null) return;

        // Search for all classSchema objects with possSuperiors
        List<LDAPEntry> classSchemas = ldapService.search(schemaDN,
                com.pointbluetech.arborj.model.SearchScope.SUBTREE,
                "(objectClass=classSchema)",
                "lDAPDisplayName", "possSuperiors", "systemPossSuperiors");

        // Any class that appears as a possSuperior is a container
        for (LDAPEntry entry : classSchemas) {
            for (String attr : List.of("possSuperiors", "systemPossSuperiors")) {
                List<String> superiors = entry.getValues(attr);
                for (String sup : superiors) {
                    result.add(sup.toLowerCase());
                }
            }
        }
    }

    private void loadEDirectoryContainerClasses(Set<String> result, Schema schema) {
        if (schema == null) return;

        for (ObjectClassDefinition oc : schema.getObjectClasses()) {
            // Include structural classes that aren't explicitly marked as non-containers.
            if (oc.getObjectClassType() != ObjectClassType.STRUCTURAL) continue;

            java.util.Map<String, String[]> ext = oc.getExtensions();
            if (isExtensionTrue(ext, "X-NDS_NOT_CONTAINER")) continue;
            if (ext != null && ext.containsKey("X-NDS_AMBIGUOUS_CONTAINMENT")) continue;

            for (String name : oc.getNames()) {
                result.add(name.toLowerCase());
            }
        }
    }

    /** True when the extension is present and any value parses as a non-zero integer. */
    private static boolean isExtensionTrue(java.util.Map<String, String[]> ext, String key) {
        if (ext == null) return false;
        String[] values = ext.get(key);
        if (values == null) return false;
        for (String v : values) {
            if (v == null) continue;
            String trimmed = v.trim();
            if (trimmed.equals("1") || trimmed.equalsIgnoreCase("true")) return true;
        }
        return false;
    }

    private void loadRFCContainerClasses(Set<String> result, Schema schema) {
        if (schema == null) return;

        // Build superclass map and mark any class that is a SUP as a container
        Set<String> superClasses = new HashSet<>();
        for (ObjectClassDefinition oc : schema.getObjectClasses()) {
            for (String sup : oc.getSuperiorClasses()) {
                superClasses.add(sup.toLowerCase());
            }
        }
        result.addAll(superClasses);

        // Also add structural classes (over-inclusive but safe)
        for (ObjectClassDefinition oc : schema.getObjectClasses()) {
            if (oc.getObjectClassType() == ObjectClassType.STRUCTURAL) {
                for (String name : oc.getNames()) {
                    result.add(name.toLowerCase());
                }
            }
        }
    }

    // --- Attribute Map ---

    /**
     * Two-pass attribute map loading:
     * Pass 1: collect direct syntax OIDs and SUP references.
     * Pass 2: resolve inherited syntax by walking the SUP chain
     * (common in eDirectory where many attributes inherit syntax from a parent).
     */
    /** Raw attribute info for SUP chain resolution. */
    private record RawAttr(String name, String directOID, String sup, boolean singleValued, boolean operational) {}

    private Map<String, LDAPAttributeInfo> loadAttributeMap(Schema schema) throws LDAPException {
        if (schema == null) {
            if (detectedType == DirectoryType.ACTIVE_DIRECTORY) {
                return buildADFallbackMap();
            }
            return new LinkedHashMap<>();
        }

        // Pass 1: collect raw info
        Map<String, RawAttr> rawAttrs = new LinkedHashMap<>();

        for (AttributeTypeDefinition attrType : schema.getAttributeTypes()) {
            String name = attrType.getNameOrOID();
            String syntaxOID = attrType.getSyntaxOID();
            if (syntaxOID != null) {
                int braceIdx = syntaxOID.indexOf('{');
                if (braceIdx > 0) syntaxOID = syntaxOID.substring(0, braceIdx);
            }
            // Get SUP attribute (parent whose syntax we inherit)
            String sup = attrType.getSuperiorType();
            boolean singleValued = attrType.isSingleValued();
            boolean operational = attrType.isOperational();

            rawAttrs.put(name.toLowerCase(), new RawAttr(
                    name, syntaxOID, sup != null ? sup.toLowerCase() : null,
                    singleValued, operational));
        }

        // Pass 2: resolve syntax OID by walking SUP chain
        Map<String, LDAPAttributeInfo> map = new LinkedHashMap<>();
        for (var entry : rawAttrs.entrySet()) {
            String key = entry.getKey();
            RawAttr raw = entry.getValue();
            String oid = resolveAttributeOID(key, rawAttrs, new HashSet<>());
            LDAPAttributeSyntax syntax = OID_TO_SYNTAX.getOrDefault(oid, LDAPAttributeSyntax.STRING);
            map.put(key, new LDAPAttributeInfo(raw.name(), oid != null ? oid : "",
                    syntax, raw.singleValued(), raw.operational()));
        }

        return map;
    }

    private String resolveAttributeOID(String key, Map<String, RawAttr> rawAttrs, Set<String> visited) {
        if (visited.contains(key)) return "";
        visited.add(key);
        RawAttr raw = rawAttrs.get(key);
        if (raw == null) return "";
        if (raw.directOID() != null && !raw.directOID().isEmpty()) return raw.directOID();
        if (raw.sup() != null) return resolveAttributeOID(raw.sup(), rawAttrs, visited);
        return "";
    }

    // --- ObjectClass Map ---

    private Map<String, LDAPObjectClassInfo> loadObjectClassMap(Schema schema) {
        Map<String, LDAPObjectClassInfo> map = new LinkedHashMap<>();

        if (schema == null) return map;

        // Pass 1: direct extraction
        Map<String, List<String>> mustDirect = new HashMap<>();
        Map<String, List<String>> mayDirect = new HashMap<>();
        Map<String, String> supMap = new HashMap<>();
        Map<String, List<String>> namingMap = new HashMap<>();

        for (ObjectClassDefinition oc : schema.getObjectClasses()) {
            String name = oc.getNameOrOID().toLowerCase();
            mustDirect.put(name, list(oc.getRequiredAttributes()));
            mayDirect.put(name, list(oc.getOptionalAttributes()));
            String[] sups = oc.getSuperiorClasses();
            if (sups.length > 0) supMap.put(name, sups[0].toLowerCase());

            // Extract X-NDS_NAMING from raw definition
            String def = oc.toString();
            List<String> naming = extractNDSNaming(def);
            if (naming != null) namingMap.put(name, naming);
        }

        // Pass 2: inheritance resolution
        for (ObjectClassDefinition oc : schema.getObjectClasses()) {
            String name = oc.getNameOrOID().toLowerCase();

            Set<String> must = new TreeSet<>(mustDirect.getOrDefault(name, List.of()));
            Set<String> may = new TreeSet<>(mayDirect.getOrDefault(name, List.of()));
            String sup = supMap.get(name);

            // Walk SUP chain
            Set<String> visited = new HashSet<>();
            visited.add(name);
            String current = sup;
            while (current != null && !visited.contains(current)) {
                visited.add(current);
                must.addAll(mustDirect.getOrDefault(current, List.of()));
                may.addAll(mayDirect.getOrDefault(current, List.of()));
                current = supMap.get(current);
            }

            // Resolve naming attributes
            List<String> naming = resolveNaming(name, namingMap, supMap);

            map.put(name, new LDAPObjectClassInfo(
                    name,
                    new ArrayList<>(must),
                    new ArrayList<>(may),
                    sup,
                    naming
            ));
        }

        return map;
    }

    // --- Helpers ---

    private Schema fetchSubschema() {
        try {
            com.unboundid.ldap.sdk.LDAPConnection rawConn = ldapService.getRawConnection();
            if (rawConn == null) return null;
            return Schema.getSchema(rawConn);
        } catch (Exception e) {
            System.err.println("Failed to fetch subschema: " + e.getMessage());
            return null;
        }
    }

    private List<String> extractNDSNaming(String definition) {
        Pattern pattern = Pattern.compile("X-NDS_NAMING\\s+(?:'([^']+)'|\\(\\s*([^)]+)\\s*\\))");
        Matcher matcher = pattern.matcher(definition);
        if (!matcher.find()) return null;

        if (matcher.group(1) != null) {
            return List.of(matcher.group(1).toLowerCase());
        }
        if (matcher.group(2) != null) {
            List<String> names = new ArrayList<>();
            Pattern quoted = Pattern.compile("'([^']+)'");
            Matcher qm = quoted.matcher(matcher.group(2));
            while (qm.find()) {
                names.add(qm.group(1).toLowerCase());
            }
            return names.isEmpty() ? null : names;
        }
        return null;
    }

    private List<String> resolveNaming(String className, Map<String, List<String>> namingMap,
                                        Map<String, String> supMap) {
        Set<String> visited = new HashSet<>();
        String current = className;
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            List<String> naming = namingMap.get(current);
            if (naming != null) return naming;
            current = supMap.get(current);
        }
        return null;
    }

    private static String firstValue(Map<String, List<String>> attrs, String key) {
        List<String> values = attrs.get(key);
        if (values != null && !values.isEmpty()) return values.getFirst();
        // Case-insensitive fallback
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    private static List<String> list(String[] arr) {
        List<String> result = new ArrayList<>();
        if (arr != null) {
            for (String s : arr) result.add(s.toLowerCase());
        }
        return result;
    }

    private Map<String, LDAPAttributeInfo> buildADFallbackMap() {
        Map<String, LDAPAttributeInfo> map = new LinkedHashMap<>();
        // String attributes
        for (String attr : List.of("cn", "sn", "givenname", "displayname", "mail",
                "telephonenumber", "description", "samaccountname", "userprincipalname",
                "name", "distinguishedname")) {
            map.put(attr, new LDAPAttributeInfo(attr, "", LDAPAttributeSyntax.STRING, false, false));
        }
        // Binary attributes
        for (String attr : List.of("objectguid", "objectsid", "thumbnailphoto",
                "usercertificate", "msexchmailboxguid")) {
            map.put(attr, new LDAPAttributeInfo(attr, "", LDAPAttributeSyntax.BINARY, false, false));
        }
        // Integer attributes
        for (String attr : List.of("useraccountcontrol", "badpwdcount", "logoncount",
                "primarygroupid", "instancetype", "samaccounttype")) {
            map.put(attr, new LDAPAttributeInfo(attr, "", LDAPAttributeSyntax.INTEGER, true, false));
        }
        // GeneralizedTime attributes
        for (String attr : List.of("whencreated", "whenchanged")) {
            map.put(attr, new LDAPAttributeInfo(attr, "", LDAPAttributeSyntax.GENERALIZED_TIME, true, false));
        }
        // DN attributes
        for (String attr : List.of("manager", "memberof", "member", "managedby")) {
            map.put(attr, new LDAPAttributeInfo(attr, "", LDAPAttributeSyntax.DN, false, false));
        }
        return map;
    }

    private static Map<String, LDAPAttributeSyntax> buildOidToSyntaxMap() {
        Map<String, LDAPAttributeSyntax> m = new HashMap<>();

        // RFC 4517 / LDAP standard syntaxes (1.3.6.1.4.1.1466.115.121.1.x)
        String base = "1.3.6.1.4.1.1466.115.121.1.";
        // Binary / opaque syntaxes
        m.put(base + "4", LDAPAttributeSyntax.BINARY);    // Audio
        m.put(base + "5", LDAPAttributeSyntax.BINARY);    // Binary
        m.put(base + "8", LDAPAttributeSyntax.BINARY);    // Certificate
        m.put(base + "9", LDAPAttributeSyntax.BINARY);    // Certificate List
        m.put(base + "10", LDAPAttributeSyntax.BINARY);   // Certificate Pair
        m.put(base + "22", LDAPAttributeSyntax.BINARY);   // Fax Image
        m.put(base + "28", LDAPAttributeSyntax.BINARY);   // JPEG
        m.put(base + "40", LDAPAttributeSyntax.BINARY);   // Octet String
        // Other standard syntaxes
        m.put(base + "7", LDAPAttributeSyntax.BOOLEAN);
        m.put(base + "12", LDAPAttributeSyntax.DN);
        m.put(base + "24", LDAPAttributeSyntax.GENERALIZED_TIME);
        m.put(base + "27", LDAPAttributeSyntax.INTEGER);
        m.put(base + "53", LDAPAttributeSyntax.UTC_TIME);

        // eDirectory NDS syntaxes (2.16.840.1.113719.1.1.5.1.x)
        String nds = "2.16.840.1.113719.1.1.5.1.";
        // NDS Binary syntaxes
        m.put(nds + "0", LDAPAttributeSyntax.BINARY);     // Unknown
        m.put(nds + "6", LDAPAttributeSyntax.BINARY);     // Stream
        m.put(nds + "9", LDAPAttributeSyntax.BINARY);     // Octet String
        m.put(nds + "14", LDAPAttributeSyntax.BINARY);    // Octet List
        m.put(nds + "21", LDAPAttributeSyntax.BINARY);    // Replica Pointer
        m.put(nds + "25", LDAPAttributeSyntax.BINARY);    // Back Link
        m.put(nds + "26", LDAPAttributeSyntax.BINARY);    // Hold
        // NDS Net Address
        m.put(nds + "12", LDAPAttributeSyntax.NET_ADDRESS);
        // NDS DN
        m.put(nds + "1", LDAPAttributeSyntax.DN);
        // NDS Boolean
        m.put(nds + "13", LDAPAttributeSyntax.BOOLEAN);
        // NDS Time
        m.put(nds + "19", LDAPAttributeSyntax.GENERALIZED_TIME);
        // NDS Integer / Counter
        m.put(nds + "2", LDAPAttributeSyntax.INTEGER);    // Counter
        m.put(nds + "7", LDAPAttributeSyntax.INTEGER);    // Counter (CI)
        m.put(nds + "23", LDAPAttributeSyntax.NDS_TIMESTAMP); // Timestamp (epoch seconds)
        m.put(nds + "15", LDAPAttributeSyntax.PATH);      // Path (volume DN + namespace + path)

        return m;
    }
}
