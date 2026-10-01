package com.pointbluetech.arborj.service;

import javafx.beans.property.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * Manages font preferences for the detail/search results area.
 * Changes are applied live and persisted across sessions.
 */
public class FontSettings {

    private static final Preferences prefs = Preferences.userNodeForPackage(FontSettings.class);
    private static final FontSettings INSTANCE = new FontSettings();

    private static final String PREF_FONT_FAMILY = "fontFamily";
    private static final String PREF_FONT_SIZE = "fontSize";
    private static final String PREF_FONT_WEIGHT = "fontWeight";

    private static final String DEFAULT_FAMILY = "monospaced";
    private static final int DEFAULT_SIZE = 13;
    private static final String DEFAULT_WEIGHT = "Normal";

    public static final List<String> AVAILABLE_WEIGHTS = List.of(
            "Thin", "Light", "Normal", "Medium", "Semi Bold", "Bold");

    /** Common monospaced font families that support multiple weights. */
    public static final List<String> RECOMMENDED_MONO_FONTS = List.of(
            "monospaced", "Menlo", "SF Mono", "Courier New", "Consolas",
            "JetBrains Mono", "Fira Code", "Source Code Pro", "IBM Plex Mono",
            "Cascadia Code", "DejaVu Sans Mono", "Ubuntu Mono");

    private final StringProperty fontFamily = new SimpleStringProperty();
    private final IntegerProperty fontSize = new SimpleIntegerProperty();
    private final StringProperty fontWeight = new SimpleStringProperty();

    // Derived fonts updated when any property changes
    private final ObjectProperty<Font> detailFont = new SimpleObjectProperty<>();
    private final ObjectProperty<Font> detailBoldFont = new SimpleObjectProperty<>();

    private FontSettings() {
        fontFamily.set(prefs.get(PREF_FONT_FAMILY, DEFAULT_FAMILY));
        fontSize.set(prefs.getInt(PREF_FONT_SIZE, DEFAULT_SIZE));
        fontWeight.set(prefs.get(PREF_FONT_WEIGHT, DEFAULT_WEIGHT));

        // Rebuild derived fonts when any property changes
        fontFamily.addListener((obs, o, n) -> { prefs.put(PREF_FONT_FAMILY, n); rebuildFonts(); });
        fontSize.addListener((obs, o, n) -> { prefs.putInt(PREF_FONT_SIZE, n.intValue()); rebuildFonts(); });
        fontWeight.addListener((obs, o, n) -> { prefs.put(PREF_FONT_WEIGHT, n); rebuildFonts(); });

        rebuildFonts();
    }

    public static FontSettings getInstance() {
        return INSTANCE;
    }

    private void rebuildFonts() {
        FontWeight fw = toFontWeight(fontWeight.get());
        detailFont.set(Font.font(fontFamily.get(), fw, fontSize.get()));

        // Bold is one step heavier than the selected weight
        FontWeight bolder = switch (fw) {
            case THIN, EXTRA_LIGHT -> FontWeight.LIGHT;
            case LIGHT -> FontWeight.NORMAL;
            case NORMAL -> FontWeight.BOLD;
            case MEDIUM -> FontWeight.BOLD;
            case SEMI_BOLD -> FontWeight.BOLD;
            case BOLD, EXTRA_BOLD, BLACK -> FontWeight.EXTRA_BOLD;
        };
        detailBoldFont.set(Font.font(fontFamily.get(), bolder, fontSize.get()));
    }

    private static FontWeight toFontWeight(String name) {
        return switch (name) {
            case "Thin" -> FontWeight.THIN;
            case "Light" -> FontWeight.LIGHT;
            case "Medium" -> FontWeight.MEDIUM;
            case "Semi Bold" -> FontWeight.SEMI_BOLD;
            case "Bold" -> FontWeight.BOLD;
            default -> FontWeight.NORMAL;
        };
    }

    // --- Properties ---

    public StringProperty fontFamilyProperty() { return fontFamily; }
    public IntegerProperty fontSizeProperty() { return fontSize; }
    public StringProperty fontWeightProperty() { return fontWeight; }

    public ReadOnlyObjectProperty<Font> detailFontProperty() { return detailFont; }
    public ReadOnlyObjectProperty<Font> detailBoldFontProperty() { return detailBoldFont; }

    public Font getDetailFont() { return detailFont.get(); }
    public Font getDetailBoldFont() { return detailBoldFont.get(); }

    /** Available monospaced and system font families. */
    public static List<String> getAvailableFamilies() {
        // Include common monospaced fonts plus all system fonts
        List<String> all = Font.getFamilies();
        // Ensure "monospaced" is first
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        result.add("monospaced");
        for (String f : all) {
            if (!f.equalsIgnoreCase("monospaced")) {
                result.add(f);
            }
        }
        return result;
    }
}
