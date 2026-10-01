package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.SearchScope;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

/**
 * In-memory log of modification and search operations, modeled on Apache
 * Directory Studio's Modification Logs / Search Logs panes. Entries are
 * appended to JavaFX string properties so the UI can bind directly.
 *
 * <p>The log is capped at {@link #MAX_CHARS} per tab; once a write would
 * exceed the cap, the oldest half of the buffer is trimmed. This keeps
 * memory bounded without forcing the user to clear manually.
 */
public final class ActivityLogger {

    private static final ActivityLogger INSTANCE = new ActivityLogger();

    public static ActivityLogger getInstance() { return INSTANCE; }

    private static final String PREF_MOD_ENABLED = "activityLoggerModEnabled";
    private static final String PREF_SEARCH_ENABLED = "activityLoggerSearchEnabled";
    private static final String PREF_MAX_SIZE_KB = "activityLoggerMaxSizeKb";
    private static final String PREF_MASKED_ATTRS = "activityLoggerMaskedAttrs";
    private static final int DEFAULT_MAX_SIZE_KB = 1000;
    private static final int MIN_SIZE_KB = 10;

    private final Preferences prefs = Preferences.userNodeForPackage(ActivityLogger.class);

    private final BooleanProperty modEnabled;
    private final BooleanProperty searchEnabled;
    private final IntegerProperty maxSizeKb;
    private final StringProperty maskedAttributesRaw;

    private final StringProperty modificationLog = new SimpleStringProperty("");
    private final StringProperty searchLog = new SimpleStringProperty("");

    // Backing buffers so append() doesn't materialize the whole log on every
    // write (the previous `current + text` allocation was O(n²) at the cap).
    private final StringBuilder modBuffer = new StringBuilder();
    private final StringBuilder searchBuffer = new StringBuilder();

    /** Parsed cache of {@link #maskedAttributesRaw} — lowercased, trimmed. */
    private volatile Set<String> maskedAttributesLower = Set.of();

    private final AtomicInteger searchSeq = new AtomicInteger();

    private ActivityLogger() {
        modEnabled = new SimpleBooleanProperty(prefs.getBoolean(PREF_MOD_ENABLED, true));
        modEnabled.addListener((o, a, b) -> prefs.putBoolean(PREF_MOD_ENABLED, b));

        searchEnabled = new SimpleBooleanProperty(prefs.getBoolean(PREF_SEARCH_ENABLED, true));
        searchEnabled.addListener((o, a, b) -> prefs.putBoolean(PREF_SEARCH_ENABLED, b));

        maxSizeKb = new SimpleIntegerProperty(
                Math.max(MIN_SIZE_KB, prefs.getInt(PREF_MAX_SIZE_KB, DEFAULT_MAX_SIZE_KB)));
        maxSizeKb.addListener((o, a, b) -> prefs.putInt(PREF_MAX_SIZE_KB, b.intValue()));

        maskedAttributesRaw = new SimpleStringProperty(prefs.get(PREF_MASKED_ATTRS, "userPassword"));
        maskedAttributesRaw.addListener((o, a, b) -> {
            prefs.put(PREF_MASKED_ATTRS, b == null ? "" : b);
            maskedAttributesLower = parseMasked(b);
        });
        maskedAttributesLower = parseMasked(maskedAttributesRaw.get());
    }

    public BooleanProperty modEnabledProperty() { return modEnabled; }
    public BooleanProperty searchEnabledProperty() { return searchEnabled; }
    public IntegerProperty maxSizeKbProperty() { return maxSizeKb; }
    public StringProperty maskedAttributesRawProperty() { return maskedAttributesRaw; }

    public StringProperty modificationLogProperty() { return modificationLog; }
    public StringProperty searchLogProperty() { return searchLog; }

    public void clearModificationLog() {
        synchronized (modBuffer) { modBuffer.setLength(0); }
        publish(modificationLog, "");
    }
    public void clearSearchLog() {
        synchronized (searchBuffer) { searchBuffer.setLength(0); }
        publish(searchLog, "");
    }

    // --- Public logging API, called from LDAPService ---

    public void logAdd(String connectionUrl, String dn,
                       Collection<com.unboundid.ldap.sdk.Attribute> attrs, Exception error) {
        if (!modEnabled.get()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(modHeader(error, connectionUrl));
        sb.append("dn: ").append(dn).append('\n');
        sb.append("changetype: add\n");
        if (attrs != null) {
            for (com.unboundid.ldap.sdk.Attribute a : attrs) {
                for (String v : a.getValues()) {
                    sb.append(a.getName()).append(": ").append(maskValue(a.getName(), v)).append('\n');
                }
            }
        }
        sb.append("\n");
        append(modificationLog, sb.toString());
    }

    public void logModify(String connectionUrl, String dn,
                          List<Modification> mods, Exception error) {
        if (!modEnabled.get()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(modHeader(error, connectionUrl));
        sb.append("dn: ").append(dn).append('\n');
        sb.append("changetype: modify\n");
        for (Modification m : mods) {
            sb.append(opKeyword(m.getModificationType())).append(": ").append(m.getAttributeName()).append('\n');
            String[] values = m.getValues();
            if (values != null) {
                for (String v : values) {
                    sb.append(m.getAttributeName()).append(": ")
                            .append(maskValue(m.getAttributeName(), v)).append('\n');
                }
            }
            sb.append("-\n");
        }
        sb.append("\n");
        append(modificationLog, sb.toString());
    }

    public void logDelete(String connectionUrl, String dn, Exception error) {
        if (!modEnabled.get()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(modHeader(error, connectionUrl));
        sb.append("dn: ").append(dn).append('\n');
        sb.append("changetype: delete\n\n");
        append(modificationLog, sb.toString());
    }

    public void logRename(String connectionUrl, String dn, String newRdn,
                          boolean deleteOldRdn, String newSuperior, Exception error) {
        if (!modEnabled.get()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(modHeader(error, connectionUrl));
        sb.append("dn: ").append(dn).append('\n');
        sb.append("changetype: moddn\n");
        sb.append("newrdn: ").append(newRdn).append('\n');
        sb.append("deleteoldrdn: ").append(deleteOldRdn ? 1 : 0).append('\n');
        if (newSuperior != null && !newSuperior.isEmpty()) {
            sb.append("newsuperior: ").append(newSuperior).append('\n');
        }
        sb.append("\n");
        append(modificationLog, sb.toString());
    }

    /**
     * Returns a search id. Pass the same id to {@link #logSearchResult} when
     * the result is known so the request and result are correlated in the log.
     */
    public int logSearchRequest(String connectionUrl, String baseDN, SearchScope scope,
                                 String filter, String[] attributes, int sizeLimit) {
        int id = searchSeq.incrementAndGet();
        if (!searchEnabled.get()) return id;
        StringBuilder sb = new StringBuilder();
        sb.append("#!SEARCH REQUEST (").append(id).append(") OK\n");
        sb.append("#!CONNECTION ").append(safe(connectionUrl)).append('\n');
        sb.append("#!DATE ").append(now()).append('\n');
        sb.append("# baseObject  : ").append(baseDN == null ? "" : baseDN).append('\n');
        sb.append("# scope       : ").append(scopeLabel(scope)).append('\n');
        sb.append("# sizeLimit   : ").append(sizeLimit).append('\n');
        sb.append("# filter      : ").append(filter == null ? "" : filter).append('\n');
        sb.append("# attributes  : ").append(attributes == null ? "" : String.join(" ", attributes)).append('\n');
        sb.append("\n");
        append(searchLog, sb.toString());
        return id;
    }

    public void logSearchResult(int id, String connectionUrl, int numEntries, Exception error) {
        if (!searchEnabled.get()) return;
        StringBuilder sb = new StringBuilder();
        String status = error == null ? "OK" : "ERROR";
        sb.append("#!SEARCH RESULT DONE (").append(id).append(") ").append(status).append('\n');
        sb.append("#!CONNECTION ").append(safe(connectionUrl)).append('\n');
        sb.append("#!DATE ").append(now()).append('\n');
        if (error == null) {
            sb.append("# numEntries : ").append(numEntries).append('\n');
        } else {
            sb.append("# error      : ").append(error.getMessage()).append('\n');
        }
        sb.append("\n");
        append(searchLog, sb.toString());
    }

    // --- Helpers ---

    private String modHeader(Exception error, String connectionUrl) {
        StringBuilder sb = new StringBuilder();
        if (error == null) {
            sb.append("#!RESULT OK\n");
        } else {
            sb.append("#!RESULT ERROR ").append(error.getMessage()).append('\n');
        }
        sb.append("#!CONNECTION ").append(safe(connectionUrl)).append('\n');
        sb.append("#!DATE ").append(now()).append('\n');
        return sb.toString();
    }

    private static String opKeyword(ModificationType type) {
        return switch (type.intValue()) {
            case 0 -> "add";
            case 1 -> "delete";
            case 2 -> "replace";
            case 3 -> "increment";
            default -> "modify";
        };
    }

    private static String scopeLabel(SearchScope scope) {
        if (scope == null) return "subtree (2)";
        return switch (scope) {
            case BASE -> "baseObject (0)";
            case ONE_LEVEL -> "singleLevel (1)";
            case SUBTREE -> "wholeSubtree (2)";
        };
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void append(StringProperty prop, String text) {
        StringBuilder buf = (prop == modificationLog) ? modBuffer : searchBuffer;
        int maxChars = Math.max(MIN_SIZE_KB, maxSizeKb.get()) * 1024;
        String snapshot;
        synchronized (buf) {
            buf.append(text);
            if (buf.length() > maxChars) {
                int keepFrom = buf.length() - maxChars / 2;
                String trimMarker = "# ...older log entries trimmed...\n";
                buf.replace(0, keepFrom, trimMarker);
            }
            snapshot = buf.toString();
        }
        publish(prop, snapshot);
    }

    private static void publish(StringProperty prop, String value) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            prop.set(value);
        } else {
            javafx.application.Platform.runLater(() -> prop.set(value));
        }
    }

    private String maskValue(String attributeName, String value) {
        if (attributeName == null || value == null) return value;
        if (maskedAttributesLower.contains(attributeName.toLowerCase())) {
            return "****";
        }
        return value;
    }

    private static Set<String> parseMasked(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String token : raw.split(",")) {
            String t = token.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
