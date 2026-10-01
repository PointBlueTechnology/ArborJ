package com.pointbluetech.arborj.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pointbluetech.arborj.model.ConnectionProfile;
import com.pointbluetech.arborj.model.LDAPConnectionConfig;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists connection profiles as JSON in ~/.arborj/profiles.json.
 */
public class ProfileStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".arborj");
    private static final Path PROFILES_FILE = STORE_DIR.resolve("profiles.json");

    private final ObjectMapper mapper;
    private final ObservableList<ConnectionProfile> profiles = FXCollections.observableArrayList();

    public ProfileStore() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public ObservableList<ConnectionProfile> getProfiles() {
        return profiles;
    }

    public void save(ConnectionProfile profile) {
        upsert(profile);
        persist();
    }

    /**
     * Update or insert all of {@code updates} and persist once. Use this when
     * mutating multiple profiles in a single user action (drag-drop reorder,
     * rename group, etc.) so the JSON file is rewritten exactly once.
     */
    public void saveAll(java.util.Collection<ConnectionProfile> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (ConnectionProfile p : updates) upsert(p);
        persist();
    }

    private void upsert(ConnectionProfile profile) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equals(profile.getId())) {
                profiles.set(i, profile);
                return;
            }
        }
        profiles.add(profile);
    }

    public void delete(ConnectionProfile profile) {
        profiles.removeIf(p -> p.getId().equals(profile.getId()));
        persist();
    }

    public ConnectionProfile duplicate(ConnectionProfile profile) {
        // Deep copy the connection config so the duplicate is independent
        LDAPConnectionConfig orig = profile.getConnection();
        LDAPConnectionConfig configCopy = new LDAPConnectionConfig(
                orig.getHost(), orig.getPort(), orig.isUseTLS(), orig.getBaseDN(), orig.getBindDN());
        configCopy.setDirectoryType(orig.getDirectoryType());
        configCopy.setUsePagedResults(orig.isUsePagedResults());
        configCopy.setSyncSecurityEquivalence(orig.isSyncSecurityEquivalence());
        configCopy.setReadOnly(orig.isReadOnly());

        ConnectionProfile copy = new ConnectionProfile(profile.getName() + " (copy)", configCopy);
        copy.setSavePassword(profile.isSavePassword());
        profiles.add(copy);
        persist();

        return copy;
    }

    /**
     * Export profiles to a JSON string for sharing.
     */
    public String exportProfiles(List<ConnectionProfile> toExport) throws IOException {
        var export = new ProfileExport("1.0", toExport);
        return mapper.writeValueAsString(export);
    }

    /**
     * Import profiles from a JSON string.
     */
    public List<ConnectionProfile> importProfiles(String json) throws IOException {
        ProfileExport imported = mapper.readValue(json, ProfileExport.class);
        if (imported.profiles() != null) {
            profiles.addAll(imported.profiles());
            persist();
            return imported.profiles();
        }
        return List.of();
    }

    private void load() {
        if (!Files.exists(PROFILES_FILE)) return;
        try {
            List<ConnectionProfile> loaded = mapper.readValue(
                    PROFILES_FILE.toFile(),
                    new TypeReference<List<ConnectionProfile>>() {}
            );
            profiles.setAll(loaded);
        } catch (IOException e) {
            System.err.println("Failed to load profiles: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(STORE_DIR);
            // Write to a temp file in the same directory, then atomically rename, so
            // a crash mid-write cannot leave a truncated profiles.json behind.
            Path tmp = Files.createTempFile(STORE_DIR, "profiles-", ".json.tmp");
            try {
                mapper.writeValue(tmp.toFile(), new ArrayList<>(profiles));
                try {
                    Files.move(tmp, PROFILES_FILE,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, PROFILES_FILE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            System.err.println("Failed to save profiles: " + e.getMessage());
        }
    }

    private record ProfileExport(String appVersion, List<ConnectionProfile> profiles) {}
}
