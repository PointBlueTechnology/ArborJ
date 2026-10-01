package com.pointbluetech.arborj.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.prefs.Preferences;

/**
 * Whether ArborJ suggests schema attribute names while the user types.
 * Default is on. Shared by the search filter, Filter Builder, Add Attribute,
 * and Effective Rights.
 */
public final class AttributeSuggestSettings {

    private static final Preferences prefs = Preferences.userNodeForPackage(AttributeSuggestSettings.class);
    private static final AttributeSuggestSettings INSTANCE = new AttributeSuggestSettings();
    private static final String PREF_SUGGEST_ATTRIBUTE_NAMES = "suggestAttributeNames";

    private final BooleanProperty enabled = new SimpleBooleanProperty();

    private AttributeSuggestSettings() {
        enabled.set(prefs.getBoolean(PREF_SUGGEST_ATTRIBUTE_NAMES, true));
        enabled.addListener((obs, oldVal, newVal) -> prefs.putBoolean(PREF_SUGGEST_ATTRIBUTE_NAMES, newVal));
    }

    public static AttributeSuggestSettings getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }
}
