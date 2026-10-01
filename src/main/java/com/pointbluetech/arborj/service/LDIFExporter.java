package com.pointbluetech.arborj.service;

import com.pointbluetech.arborj.model.LDAPEntry;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RFC 2849 LDIF serialization.
 * Respects ExportSettings for line separator, fold width, spacing, and version line.
 */
public class LDIFExporter {

    /**
     * Export a single entry to LDIF format.
     */
    public String exportEntry(LDAPEntry entry) {
        StringBuilder sb = new StringBuilder();
        ExportSettings settings = ExportSettings.getInstance();
        String eol = settings.getLdifLineSeparator();

        if (settings.isLdifIncludeVersion()) {
            sb.append("version: 1").append(eol);
            sb.append(eol);
        }

        appendEntry(sb, entry, settings);
        return sb.toString();
    }

    /**
     * Export multiple entries to LDIF format.
     */
    public String exportEntries(List<LDAPEntry> entries) {
        StringBuilder sb = new StringBuilder();
        ExportSettings settings = ExportSettings.getInstance();
        String eol = settings.getLdifLineSeparator();

        if (settings.isLdifIncludeVersion()) {
            sb.append("version: 1").append(eol);
            sb.append(eol);
        }

        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(eol);
            appendEntry(sb, entries.get(i), settings);
        }
        return sb.toString();
    }

    private void appendEntry(StringBuilder sb, LDAPEntry entry, ExportSettings settings) {
        // DN line
        appendLine(sb, "dn", entry.getDn(), settings);

        // Sort attributes: objectClass first, then alphabetically
        Map<String, List<String>> attrs = entry.getAttributes();
        List<String> sortedNames = new ArrayList<>(attrs.keySet());
        sortedNames.sort((a, b) -> {
            if (a.equalsIgnoreCase("objectClass")) return -1;
            if (b.equalsIgnoreCase("objectClass")) return 1;
            return a.compareToIgnoreCase(b);
        });

        for (String name : sortedNames) {
            for (String value : attrs.get(name)) {
                appendLine(sb, name, value, settings);
            }
        }

        sb.append(settings.getLdifLineSeparator());
    }

    private void appendLine(StringBuilder sb, String name, String value, ExportSettings settings) {
        String separator = settings.isLdifSpaceAfterColon() ? ": " : ":";
        String base64Separator = settings.isLdifSpaceAfterColon() ? ":: " : "::";
        String eol = settings.getLdifLineSeparator();
        int foldWidth = settings.getLdifLineLength();

        String line;
        if (needsBase64(value)) {
            String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            line = name + base64Separator + encoded;
        } else {
            line = name + separator + value;
        }

        // Line folding per RFC 2849
        if (foldWidth <= 0 || line.length() <= foldWidth) {
            sb.append(line).append(eol);
        } else {
            sb.append(line, 0, foldWidth).append(eol);
            int pos = foldWidth;
            while (pos < line.length()) {
                int end = Math.min(pos + foldWidth - 1, line.length());
                sb.append(" ").append(line, pos, end).append(eol);
                pos = end;
            }
        }
    }

    private boolean needsBase64(String value) {
        if (value.isEmpty()) return false;

        // Starts with space, colon, or less-than
        char first = value.charAt(0);
        if (first == ' ' || first == ':' || first == '<') return true;

        // Contains non-ASCII or control characters
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r') return true;
            if (c > 0x7E) return true;
        }

        // Trailing space
        if (value.charAt(value.length() - 1) == ' ') return true;

        return false;
    }
}
