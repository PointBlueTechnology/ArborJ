package com.pointbluetech.arborj.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pointbluetech.arborj.model.SearchHistoryEntry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists search history as JSON in ~/.arborj/search_history.json.
 * Keeps the most recent 15 entries.
 */
public class SearchHistoryStore {

    private static final int MAX_ENTRIES = 15;
    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".arborj");
    private static final Path HISTORY_FILE = STORE_DIR.resolve("search_history.json");
    private static final SearchHistoryStore INSTANCE = new SearchHistoryStore();

    private final ObjectMapper mapper;
    private final ObservableList<SearchHistoryEntry> entries = FXCollections.observableArrayList();

    private SearchHistoryStore() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public static SearchHistoryStore getInstance() {
        return INSTANCE;
    }

    public ObservableList<SearchHistoryEntry> getEntries() {
        return entries;
    }

    public void add(SearchHistoryEntry entry) {
        // Remove duplicate filters
        entries.removeIf(e -> e.getFilter().equals(entry.getFilter()));

        // Add to front
        entries.addFirst(entry);

        // Trim to max
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }

        persist();
    }

    public void clear() {
        entries.clear();
        persist();
    }

    private void load() {
        if (!Files.exists(HISTORY_FILE)) return;
        try {
            List<SearchHistoryEntry> loaded = mapper.readValue(
                    HISTORY_FILE.toFile(),
                    new TypeReference<List<SearchHistoryEntry>>() {});
            entries.setAll(loaded);
        } catch (IOException e) {
            System.err.println("Failed to load search history: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(STORE_DIR);
            mapper.writeValue(HISTORY_FILE.toFile(), new ArrayList<>(entries));
        } catch (IOException e) {
            System.err.println("Failed to save search history: " + e.getMessage());
        }
    }
}
