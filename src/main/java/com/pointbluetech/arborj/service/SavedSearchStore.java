package com.pointbluetech.arborj.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pointbluetech.arborj.model.SavedSearch;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists saved searches as JSON in ~/.arborj/saved_searches.json.
 */
public class SavedSearchStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".arborj");
    private static final Path SAVED_FILE = STORE_DIR.resolve("saved_searches.json");
    private static final SavedSearchStore INSTANCE = new SavedSearchStore();

    private final ObjectMapper mapper;
    private final ObservableList<SavedSearch> entries = FXCollections.observableArrayList();

    private SavedSearchStore() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public static SavedSearchStore getInstance() {
        return INSTANCE;
    }

    public ObservableList<SavedSearch> getEntries() {
        return entries;
    }

    public void add(SavedSearch search) {
        entries.add(search);
        persist();
    }

    public void update(SavedSearch search) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(search.getId())) {
                entries.set(i, search);
                break;
            }
        }
        persist();
    }

    public void remove(SavedSearch search) {
        entries.removeIf(e -> e.getId().equals(search.getId()));
        persist();
    }

    private void load() {
        if (!Files.exists(SAVED_FILE)) return;
        try {
            List<SavedSearch> loaded = mapper.readValue(
                    SAVED_FILE.toFile(),
                    new TypeReference<List<SavedSearch>>() {});
            entries.setAll(loaded);
        } catch (IOException e) {
            System.err.println("Failed to load saved searches: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(STORE_DIR);
            mapper.writeValue(SAVED_FILE.toFile(), new ArrayList<>(entries));
        } catch (IOException e) {
            System.err.println("Failed to save saved searches: " + e.getMessage());
        }
    }
}
