package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.LDAPEntry;
import com.unboundid.ldap.sdk.LDAPException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * RFC 2849 LDIF parser and batch importer.
 */
public class LDIFImporter {

    private final LDAPService ldapService;

    public LDIFImporter(LDAPService ldapService) {
        this.ldapService = ldapService;
    }

    /**
     * Parse LDIF text into entries. Materializes the whole file in memory; use
     * {@link #streamingImport} for large files.
     */
    public List<LDAPEntry> parse(String ldifText) throws IOException {
        List<LDAPEntry> entries = new ArrayList<>();
        try (EntryReader r = new EntryReader(new StringReader(ldifText))) {
            LDAPEntry e;
            while ((e = r.next()) != null) entries.add(e);
        }
        return entries;
    }

    /**
     * Import parsed entries into the directory.
     *
     * @param entries         Entries to import
     * @param continueOnError Whether to continue after errors
     * @param progressCallback Called with progress messages
     * @return Import result summary
     */
    public ImportResult importEntries(List<LDAPEntry> entries, boolean continueOnError,
                                       Consumer<String> progressCallback) {
        return importEntries(entries, continueOnError, progressCallback, () -> false);
    }

    public ImportResult importEntries(List<LDAPEntry> entries, boolean continueOnError,
                                      Consumer<String> progressCallback,
                                      BooleanSupplier cancelRequested) {
        int success = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();
        boolean cancelled = false;

        for (int i = 0; i < entries.size(); i++) {
            if (cancelRequested != null && cancelRequested.getAsBoolean()) {
                cancelled = true;
                if (progressCallback != null) {
                    progressCallback.accept("Import cancelled");
                }
                break;
            }

            LDAPEntry entry = entries.get(i);
            if (progressCallback != null) {
                progressCallback.accept("Importing " + (i + 1) + "/" + entries.size() + ": " + entry.getDn());
            }

            try {
                ldapService.addEntry(entry.getDn(), entry.getAttributes());
                success++;
            } catch (LDAPException e) {
                errors++;
                String msg = "Failed to add " + entry.getDn() + ": " + e.getMessage();
                errorMessages.add(msg);

                if (!continueOnError) {
                    break;
                }
            }
        }

        return new ImportResult(success, errors, errorMessages, cancelled);
    }

    /**
     * Stream entries from an LDIF file straight into the directory without
     * holding the whole file or the parsed entry list in memory. Suitable for
     * large LDIF imports.
     */
    public ImportResult streamingImport(Path file, boolean continueOnError,
                                         Consumer<String> progressCallback,
                                         BooleanSupplier cancelRequested) throws IOException {
        int success = 0;
        int errors = 0;
        int processed = 0;
        List<String> errorMessages = new ArrayList<>();
        boolean cancelled = false;

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             EntryReader r = new EntryReader(br)) {
            LDAPEntry entry;
            while ((entry = r.next()) != null) {
                if (cancelRequested != null && cancelRequested.getAsBoolean()) {
                    cancelled = true;
                    if (progressCallback != null) progressCallback.accept("Import cancelled");
                    break;
                }
                processed++;
                if (progressCallback != null) {
                    progressCallback.accept("Importing " + processed + ": " + entry.getDn());
                }
                try {
                    ldapService.addEntry(entry.getDn(), entry.getAttributes());
                    success++;
                } catch (LDAPException ex) {
                    errors++;
                    errorMessages.add("Failed to add " + entry.getDn() + ": " + ex.getMessage());
                    if (!continueOnError) break;
                }
            }
        }

        return new ImportResult(success, errors, errorMessages, cancelled);
    }

    /**
     * Streaming RFC 2849 entry parser. Reads logical (unfolded) lines from a
     * Reader and yields {@link LDAPEntry} instances one at a time.
     */
    static final class EntryReader implements AutoCloseable {

        private final BufferedReader reader;
        private String pending;             // logical line buffer being built
        private String peekedPhysical;      // single-line lookahead for unfolding
        private boolean eof = false;

        private String currentDN;
        private final Map<String, List<String>> currentAttrs = new LinkedHashMap<>();
        private boolean sawAnything = false;

        EntryReader(Reader reader) {
            this.reader = (reader instanceof BufferedReader br) ? br : new BufferedReader(reader);
        }

        /** Returns the next entry or null at EOF. */
        LDAPEntry next() throws IOException {
            while (true) {
                String logical = readLogicalLine();
                if (logical == null) {
                    return finishEntry();
                }
                if (logical.isEmpty()) {
                    LDAPEntry e = finishEntry();
                    if (e != null) return e;
                    continue;
                }
                if (logical.startsWith("#")) continue; // comment
                processLine(logical);
            }
        }

        private LDAPEntry finishEntry() {
            if (currentDN == null) return null;
            LDAPEntry e = new LDAPEntry(currentDN, new LinkedHashMap<>(currentAttrs));
            currentDN = null;
            currentAttrs.clear();
            return e;
        }

        private String readLogicalLine() throws IOException {
            String first;
            if (peekedPhysical != null) {
                first = peekedPhysical;
                peekedPhysical = null;
            } else {
                if (eof) return null;
                first = reader.readLine();
                if (first == null) { eof = true; return null; }
            }
            StringBuilder sb = new StringBuilder(first);
            while (true) {
                String next = reader.readLine();
                if (next == null) { eof = true; break; }
                if (next.startsWith(" ")) {
                    sb.append(next, 1, next.length());
                } else {
                    peekedPhysical = next;
                    break;
                }
            }
            return sb.toString();
        }

        private void processLine(String line) throws IOException {
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) return;
            String name = line.substring(0, colonIdx);
            String rawValue = line.substring(colonIdx + 1);

            String value;
            if (!rawValue.isEmpty() && rawValue.charAt(0) == ':') {
                // ":: [SP*] base64" — RFC 2849 allows zero or more spaces after `::`.
                value = new String(
                        Base64.getDecoder().decode(rawValue.substring(1).trim()),
                        StandardCharsets.UTF_8);
            } else if (!rawValue.isEmpty() && rawValue.charAt(0) == '<') {
                throw new IOException("URL-reference values (`" + name
                        + ":< ...`) are not supported by the LDIF importer");
            } else {
                value = rawValue.startsWith(" ") ? rawValue.substring(1) : rawValue;
            }

            // Skip the leading "version: 1" header.
            if (name.equalsIgnoreCase("version") && currentDN == null
                    && currentAttrs.isEmpty() && !sawAnything) {
                sawAnything = true;
                return;
            }
            sawAnything = true;

            if (name.equalsIgnoreCase("dn")) {
                currentDN = value;
            } else {
                currentAttrs.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    // --- Result ---

    public record ImportResult(int successCount, int errorCount, List<String> errors, boolean cancelled) {
        public ImportResult(int successCount, int errorCount, List<String> errors) {
            this(successCount, errorCount, errors, false);
        }
    }
}
