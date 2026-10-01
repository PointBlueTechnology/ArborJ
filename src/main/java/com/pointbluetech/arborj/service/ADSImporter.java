package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.ConnectionProfile;
import com.pointbluetech.arborj.model.LDAPConnectionConfig;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Imports LDAP connection profiles from Apache Directory Studio's connections.xml.
 */
public class ADSImporter {

    private static final String PLUGIN_PATH =
            ".metadata/.plugins/org.apache.directory.studio.connection.core/connections.xml";

    /**
     * Search known locations for the ADS connections file.
     * Returns the first found path, or null if not found.
     */
    public static Path findConnectionsFile() {
        String home = System.getProperty("user.home");
        List<Path> candidates = List.of(
                Path.of(home, ".ApacheDirectoryStudio", PLUGIN_PATH),
                Path.of(home, "ApacheDirectoryStudio", PLUGIN_PATH),
                // Eclipse-based workspace locations
                Path.of(home, ".eclipse", ".ApacheDirectoryStudio", PLUGIN_PATH)
        );

        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Parse an ADS connections.xml file into ArborJ ConnectionProfile objects.
     *
     * @param xmlFile   Path to the connections.xml
     * @param existing  Existing profiles to check for duplicates (host+port+bindDN)
     * @return Import result with profiles and passwords
     */
    public static ImportResult importConnections(Path xmlFile, List<ConnectionProfile> existing)
            throws Exception {
        // Build set of existing connection signatures for duplicate detection
        Set<String> existingKeys = existing.stream()
                .map(p -> connectionKey(p.getConnection()))
                .collect(Collectors.toSet());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entities for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile.toFile());

        NodeList connections = doc.getElementsByTagName("connection");
        List<ConnectionProfile> profiles = new ArrayList<>();
        List<PasswordEntry> passwords = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < connections.getLength(); i++) {
            Element conn = (Element) connections.item(i);

            String name = conn.getAttribute("name");
            String host = conn.getAttribute("host");
            int port = parseIntSafe(conn.getAttribute("port"), 389);
            String encryption = conn.getAttribute("encryptionMethod");
            String authMethod = conn.getAttribute("authMethod");
            String bindDN = conn.getAttribute("bindPrincipal");
            String password = conn.getAttribute("bindPassword");
            boolean readOnly = "true".equalsIgnoreCase(conn.getAttribute("readOnly"));

            // Map encryption to TLS
            boolean useTLS = "LDAPS".equalsIgnoreCase(encryption);

            // Build config
            LDAPConnectionConfig config = new LDAPConnectionConfig(host, port, useTLS, "", bindDN);
            config.setReadOnly(readOnly);

            // Check for paged search in extended properties
            boolean pagedSearch = false;
            NodeList extProps = conn.getElementsByTagName("extendedProperty");
            for (int j = 0; j < extProps.getLength(); j++) {
                Element prop = (Element) extProps.item(j);
                if ("ldapbrowser.pagedSearch".equals(prop.getAttribute("key"))) {
                    pagedSearch = "true".equalsIgnoreCase(prop.getAttribute("value"));
                }
            }
            config.setUsePagedResults(pagedSearch);

            // Skip duplicates
            String key = connectionKey(config);
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }
            existingKeys.add(key);

            // Create profile
            ConnectionProfile profile = new ConnectionProfile(name, config);
            profile.setGroup("Imported from ADS");

            // Handle password
            if ("SIMPLE".equalsIgnoreCase(authMethod) && password != null && !password.isEmpty()) {
                profile.setSavePassword(true);
                passwords.add(new PasswordEntry(profile.getId(), password));
            }

            profiles.add(profile);
        }

        return new ImportResult(profiles, passwords, skipped);
    }

    private static String connectionKey(LDAPConnectionConfig config) {
        return (config.getHost() + ":" + config.getPort() + ":" + config.getBindDN()).toLowerCase();
    }

    private static int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record PasswordEntry(String profileId, String password) {}

    public record ImportResult(List<ConnectionProfile> profiles, List<PasswordEntry> passwords,
                                int skippedDuplicates) {}
}
