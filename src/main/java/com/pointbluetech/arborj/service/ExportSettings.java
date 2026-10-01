package com.pointbluetech.arborj.service;

import javafx.beans.property.*;

import java.util.prefs.Preferences;

/**
 * Persistent settings for LDIF and CSV export formats.
 */
public class ExportSettings {

    private static final Preferences prefs = Preferences.userNodeForPackage(ExportSettings.class);
    private static final ExportSettings INSTANCE = new ExportSettings();

    // LDIF settings
    private static final String PREF_LDIF_LINE_SEPARATOR = "ldifLineSeparator";
    private static final String PREF_LDIF_LINE_LENGTH = "ldifLineLength";
    private static final String PREF_LDIF_SPACE_AFTER_COLON = "ldifSpaceAfterColon";
    private static final String PREF_LDIF_INCLUDE_VERSION = "ldifIncludeVersion";

    // CSV settings
    private static final String PREF_CSV_ATTR_DELIMITER = "csvAttrDelimiter";
    private static final String PREF_CSV_VALUE_DELIMITER = "csvValueDelimiter";
    private static final String PREF_CSV_QUOTE_CHAR = "csvQuoteChar";
    private static final String PREF_CSV_LINE_SEPARATOR = "csvLineSeparator";
    private static final String PREF_CSV_ENCODING = "csvEncoding";

    // LDIF properties
    private final StringProperty ldifLineSeparator = new SimpleStringProperty();
    private final IntegerProperty ldifLineLength = new SimpleIntegerProperty();
    private final BooleanProperty ldifSpaceAfterColon = new SimpleBooleanProperty();
    private final BooleanProperty ldifIncludeVersion = new SimpleBooleanProperty();

    // CSV properties
    private final StringProperty csvAttrDelimiter = new SimpleStringProperty();
    private final StringProperty csvValueDelimiter = new SimpleStringProperty();
    private final StringProperty csvQuoteChar = new SimpleStringProperty();
    private final StringProperty csvLineSeparator = new SimpleStringProperty();
    private final StringProperty csvEncoding = new SimpleStringProperty();

    private ExportSettings() {
        // Load LDIF defaults
        ldifLineSeparator.set(prefs.get(PREF_LDIF_LINE_SEPARATOR, "\n"));
        ldifLineLength.set(prefs.getInt(PREF_LDIF_LINE_LENGTH, 76));
        ldifSpaceAfterColon.set(prefs.getBoolean(PREF_LDIF_SPACE_AFTER_COLON, true));
        ldifIncludeVersion.set(prefs.getBoolean(PREF_LDIF_INCLUDE_VERSION, true));

        // Load CSV defaults
        csvAttrDelimiter.set(prefs.get(PREF_CSV_ATTR_DELIMITER, ","));
        csvValueDelimiter.set(prefs.get(PREF_CSV_VALUE_DELIMITER, "|"));
        csvQuoteChar.set(prefs.get(PREF_CSV_QUOTE_CHAR, "\""));
        csvLineSeparator.set(prefs.get(PREF_CSV_LINE_SEPARATOR, "\n"));
        csvEncoding.set(prefs.get(PREF_CSV_ENCODING, "UTF-8"));

        // Auto-persist on change
        ldifLineSeparator.addListener((o, ov, nv) -> prefs.put(PREF_LDIF_LINE_SEPARATOR, nv));
        ldifLineLength.addListener((o, ov, nv) -> prefs.putInt(PREF_LDIF_LINE_LENGTH, nv.intValue()));
        ldifSpaceAfterColon.addListener((o, ov, nv) -> prefs.putBoolean(PREF_LDIF_SPACE_AFTER_COLON, nv));
        ldifIncludeVersion.addListener((o, ov, nv) -> prefs.putBoolean(PREF_LDIF_INCLUDE_VERSION, nv));

        csvAttrDelimiter.addListener((o, ov, nv) -> prefs.put(PREF_CSV_ATTR_DELIMITER, nv));
        csvValueDelimiter.addListener((o, ov, nv) -> prefs.put(PREF_CSV_VALUE_DELIMITER, nv));
        csvQuoteChar.addListener((o, ov, nv) -> prefs.put(PREF_CSV_QUOTE_CHAR, nv));
        csvLineSeparator.addListener((o, ov, nv) -> prefs.put(PREF_CSV_LINE_SEPARATOR, nv));
        csvEncoding.addListener((o, ov, nv) -> prefs.put(PREF_CSV_ENCODING, nv));
    }

    public static ExportSettings getInstance() { return INSTANCE; }

    // LDIF getters
    public StringProperty ldifLineSeparatorProperty() { return ldifLineSeparator; }
    public IntegerProperty ldifLineLengthProperty() { return ldifLineLength; }
    public BooleanProperty ldifSpaceAfterColonProperty() { return ldifSpaceAfterColon; }
    public BooleanProperty ldifIncludeVersionProperty() { return ldifIncludeVersion; }

    public String getLdifLineSeparator() { return ldifLineSeparator.get(); }
    public int getLdifLineLength() { return ldifLineLength.get(); }
    public boolean isLdifSpaceAfterColon() { return ldifSpaceAfterColon.get(); }
    public boolean isLdifIncludeVersion() { return ldifIncludeVersion.get(); }

    // CSV getters
    public StringProperty csvAttrDelimiterProperty() { return csvAttrDelimiter; }
    public StringProperty csvValueDelimiterProperty() { return csvValueDelimiter; }
    public StringProperty csvQuoteCharProperty() { return csvQuoteChar; }
    public StringProperty csvLineSeparatorProperty() { return csvLineSeparator; }
    public StringProperty csvEncodingProperty() { return csvEncoding; }

    public String getCsvAttrDelimiter() { return csvAttrDelimiter.get(); }
    public String getCsvValueDelimiter() { return csvValueDelimiter.get(); }
    public String getCsvQuoteChar() { return csvQuoteChar.get(); }
    public String getCsvLineSeparator() { return csvLineSeparator.get(); }
    public String getCsvEncoding() { return csvEncoding.get(); }

    public void restoreDefaults() {
        ldifLineSeparator.set("\n");
        ldifLineLength.set(76);
        ldifSpaceAfterColon.set(true);
        ldifIncludeVersion.set(true);

        csvAttrDelimiter.set(",");
        csvValueDelimiter.set("|");
        csvQuoteChar.set("\"");
        csvLineSeparator.set("\n");
        csvEncoding.set("UTF-8");
    }
}
