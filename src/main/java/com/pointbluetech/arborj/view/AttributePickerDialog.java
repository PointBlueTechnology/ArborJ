package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Dialog for choosing which LDAP attributes a search should return.
 *
 * <p>Two synchronized views of the same ordered list:
 * <ul>
 *   <li>"Pick" — dual-list with available / selected, plus up/down reorder.</li>
 *   <li>"Paste List" — a free-form text area (comma- or whitespace-separated).</li>
 * </ul>
 * Edits in either view propagate live; the user's chosen order is preserved
 * (it determines the Table View's column order). On OK, every selected name
 * is validated against the schema cache and unknown names block the close.
 */
public class AttributePickerDialog extends Stage {

    private static final String ALL_ATTRIBUTES = "[All Attributes]";

    /** Pseudo-attribute markers always considered valid. */
    private static final Set<String> SPECIAL_TOKENS = Set.of("*", "+", "1.1");

    /** Persisted history of paste-list inputs. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(AttributePickerDialog.class);
    private static final String PREF_HISTORY = "pasteHistory";
    private static final String HISTORY_DELIMITER = ""; // ASCII unit separator
    private static final int HISTORY_MAX = 20;

    /** All known attribute names, fully populated and showing the user's order. */
    private final ObservableList<String> availableItems = FXCollections.observableArrayList();
    /** Selected attributes in user-defined order. NOT sorted. */
    private final ObservableList<String> selectedItems = FXCollections.observableArrayList();

    private List<String> result = null;

    public AttributePickerDialog(MainController controller, List<String> currentlySelected) {
        this(controller, currentlySelected, null);
    }

    public AttributePickerDialog(MainController controller, List<String> currentlySelected,
                                 javafx.stage.Window owner) {
        setTitle("Select Attributes");
        initModality(Modality.APPLICATION_MODAL);
        if (owner != null) initOwner(owner);

        var attrMap = controller.getSchemaService().getAttributeMap();

        // Build the universe of attribute names from the schema.
        List<String> allAttrs = new ArrayList<>();
        allAttrs.add(ALL_ATTRIBUTES);
        if (attrMap != null) {
            attrMap.values().stream()
                    .map(com.pointbluetech.arborj.model.LDAPAttributeInfo::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(allAttrs::add);
        }

        // Seed selectedItems from the caller's current selection (preserve order).
        if (currentlySelected != null) {
            for (String attr : currentlySelected) {
                selectedItems.add("*".equals(attr) ? ALL_ATTRIBUTES : attr);
            }
        }
        // Available = everything in the schema not currently selected.
        for (String attr : allAttrs) {
            if (!selectedItems.contains(attr)) availableItems.add(attr);
        }

        // ---------------- Available column (filterable, sorted) ----------------
        TextField filterField = new TextField();
        filterField.setPromptText("Filter attributes...");

        FilteredList<String> filteredAvailable = new FilteredList<>(availableItems, s -> true);
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lower = newVal == null ? "" : newVal.toLowerCase();
            filteredAvailable.setPredicate(attr ->
                    lower.isEmpty() || attr.toLowerCase().contains(lower));
        });
        SortedList<String> sortedAvailable = new SortedList<>(filteredAvailable,
                Comparator.comparing(s -> s.equals(ALL_ATTRIBUTES) ? "" : s.toLowerCase()));

        ListView<String> availableList = new ListView<>(sortedAvailable);
        availableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(availableList, Priority.ALWAYS);

        Label availableLabel = new Label("Available");
        availableLabel.setStyle("-fx-font-weight: bold;");
        VBox availableBox = new VBox(4, availableLabel, filterField, availableList);
        availableBox.setPrefWidth(250);
        HBox.setHgrow(availableBox, Priority.ALWAYS);

        // ---------------- Selected column (ordered, reorderable) ----------------
        ListView<String> selectedList = new ListView<>(selectedItems);
        selectedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(selectedList, Priority.ALWAYS);

        Button upBtn = new Button("↑");
        upBtn.setTooltip(new Tooltip("Move selected attributes up"));
        upBtn.setOnAction(e -> moveSelected(selectedList, -1));
        Button downBtn = new Button("↓");
        downBtn.setTooltip(new Tooltip("Move selected attributes down"));
        downBtn.setOnAction(e -> moveSelected(selectedList, +1));
        upBtn.disableProperty().bind(
                selectedList.getSelectionModel().selectedIndexProperty().lessThanOrEqualTo(0));
        downBtn.disableProperty().bind(
                javafx.beans.binding.Bindings.createBooleanBinding(() -> {
                    int last = selectedItems.size() - 1;
                    return selectedList.getSelectionModel().getSelectedIndices().isEmpty()
                            || selectedList.getSelectionModel().getSelectedIndices().getLast() >= last
                            || last < 0;
                }, selectedList.getSelectionModel().getSelectedIndices(), selectedItems));

        Label selectedLabel = new Label("Selected");
        selectedLabel.setStyle("-fx-font-weight: bold;");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox selectedHeader = new HBox(4, selectedLabel, headerSpacer, upBtn, downBtn);
        selectedHeader.setAlignment(Pos.CENTER_LEFT);
        VBox selectedBox = new VBox(4, selectedHeader, selectedList);
        selectedBox.setPrefWidth(250);
        HBox.setHgrow(selectedBox, Priority.ALWAYS);

        // ---------------- Transfer buttons (>, >>, <, <<) ----------------
        Button addBtn = new Button(">");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setTooltip(new Tooltip("Add selected"));
        addBtn.setOnAction(e -> addToSelected(
                new ArrayList<>(availableList.getSelectionModel().getSelectedItems())));

        Button addAllBtn = new Button(">>");
        addAllBtn.setMaxWidth(Double.MAX_VALUE);
        addAllBtn.setTooltip(new Tooltip("Add all currently visible"));
        addAllBtn.setOnAction(e -> addToSelected(new ArrayList<>(sortedAvailable)));

        Button removeBtn = new Button("<");
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        removeBtn.setTooltip(new Tooltip("Remove selected"));
        removeBtn.setOnAction(e -> removeFromSelected(
                new ArrayList<>(selectedList.getSelectionModel().getSelectedItems())));

        Button removeAllBtn = new Button("<<");
        removeAllBtn.setMaxWidth(Double.MAX_VALUE);
        removeAllBtn.setTooltip(new Tooltip("Remove all"));
        removeAllBtn.setOnAction(e -> removeFromSelected(new ArrayList<>(selectedItems)));

        VBox transferBox = new VBox(8, addBtn, addAllBtn, removeBtn, removeAllBtn);
        transferBox.setAlignment(Pos.CENTER);
        transferBox.setPadding(new Insets(0, 8, 0, 8));

        // ---------------- Pick tab ----------------
        HBox pickContent = new HBox(8, availableBox, transferBox, selectedBox);
        pickContent.setPadding(new Insets(12));
        HBox.setHgrow(pickContent, Priority.ALWAYS);
        Tab pickTab = new Tab("Pick", pickContent);
        pickTab.setClosable(false);

        // ---------------- Paste tab (live two-way sync, with history) ----------------
        ComboBox<String> pasteCombo = new ComboBox<>(FXCollections.observableArrayList(loadHistory()));
        pasteCombo.setEditable(true);
        pasteCombo.setMaxWidth(Double.MAX_VALUE);
        pasteCombo.setPromptText("Comma-separated list of attribute names. Example: cn, sn, mail");
        pasteCombo.getEditor().setText(formatCurrent(selectedItems));

        Label pasteLabel = new Label("Returning Attributes:");
        Label pasteHint = new Label("Order is preserved. The list and the Pick tab stay in sync. "
                + "Past inputs appear in the dropdown.");
        pasteHint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        pasteHint.setWrapText(true);

        VBox pasteBox = new VBox(6, pasteLabel, pasteCombo, pasteHint);
        pasteBox.setPadding(new Insets(12));
        Tab pasteTab = new Tab("Paste List", pasteBox);
        pasteTab.setClosable(false);

        TabPane tabs = new TabPane(pickTab, pasteTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // ---------------- Two-way sync between selectedItems and pasteCombo ----------------
        // When the list changes (Pick-tab edits), refresh the combo's editor —
        // but not while the user is actively typing, which would clobber the
        // cursor and in-progress text.
        final boolean[] syncing = {false};
        selectedItems.addListener((ListChangeListener<String>) c -> {
            if (syncing[0]) return;
            if (pasteCombo.getEditor().isFocused()) return;
            syncing[0] = true;
            try { pasteCombo.getEditor().setText(formatCurrent(selectedItems)); }
            finally { syncing[0] = false; }
        });
        // When the editor loses focus, when the user picks a history entry,
        // or when they switch away from the Paste tab, parse the text and
        // reconcile selectedItems / availableItems, preserving the typed order.
        Runnable commitPaste = () -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try { reconcileFromPaste(pasteCombo.getEditor().getText(), attrMap); }
            finally { syncing[0] = false; }
        };
        pasteCombo.getEditor().focusedProperty().addListener((obs, was, isFocused) -> {
            if (was && !isFocused) commitPaste.run();
        });
        // Selecting a history entry from the dropdown commits immediately.
        pasteCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(pasteCombo.getEditor().getText())) {
                pasteCombo.getEditor().setText(newVal);
            }
            commitPaste.run();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab == pasteTab) commitPaste.run();
        });

        // ---------------- OK / Cancel ----------------
        Button okBtn = new Button("OK");
        okBtn.setDefaultButton(true);
        okBtn.setOnAction(e -> {
            commitPaste.run();
            List<String> unknown = findUnknown(selectedItems, attrMap);
            if (!unknown.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        "These attribute names aren't in the schema and would be rejected by "
                        + "most servers:\n\n  " + String.join(", ", unknown)
                        + "\n\nFix them or remove them, then click OK again.",
                        ButtonType.OK);
                alert.setTitle("Unknown Attributes");
                alert.setHeaderText(null);
                alert.initOwner(this);
                alert.showAndWait();
                // If the user was on the Paste tab, leave them there to fix
                // the names — the textarea is the most ergonomic place to
                // edit. Otherwise switch to it for them.
                if (tabs.getSelectionModel().getSelectedItem() != pasteTab) {
                    tabs.getSelectionModel().select(pasteTab);
                }
                pasteCombo.getEditor().requestFocus();
                return;
            }
            // Persist the committed paste-tab string so it shows up in the
            // dropdown next time. Only the canonical comma-separated form is
            // saved (whatever the user actually pasted is normalized first).
            String committed = formatCurrent(selectedItems);
            if (!committed.isEmpty()) saveToHistory(committed);
            result = new ArrayList<>();
            for (String item : selectedItems) {
                result.add(ALL_ATTRIBUTES.equals(item) ? "*" : item);
            }
            close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> {
            result = null;
            close();
        });

        HBox bottomBar = new HBox(8, okBtn, cancelBtn);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        bottomBar.setPadding(new Insets(0, 12, 12, 12));

        VBox root = new VBox(tabs, bottomBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Scene scene = new Scene(root, 620, 470);
        setScene(scene);
    }

    /**
     * Move every currently-selected row in the Selected list by {@code delta}
     * positions (–1 = up, +1 = down). Multi-selection is supported and the
     * relative order of moved rows is preserved.
     */
    private void moveSelected(ListView<String> selectedList, int delta) {
        var indices = new ArrayList<>(selectedList.getSelectionModel().getSelectedIndices());
        if (indices.isEmpty()) return;
        // Sort ascending for "up" (process top-down) and descending for "down".
        if (delta < 0) indices.sort(Integer::compareTo);
        else indices.sort(Comparator.reverseOrder());

        var newSelection = new ArrayList<Integer>();
        for (int idx : indices) {
            int target = idx + delta;
            if (target < 0 || target >= selectedItems.size()) {
                newSelection.add(idx);
                continue;
            }
            // Don't displace another selected row (it's about to move too).
            if (newSelection.contains(target)) {
                newSelection.add(idx);
                continue;
            }
            String moving = selectedItems.remove(idx);
            selectedItems.add(target, moving);
            newSelection.add(target);
        }
        selectedList.getSelectionModel().clearSelection();
        for (int idx : newSelection) selectedList.getSelectionModel().select(idx);
    }

    private void addToSelected(List<String> toAdd) {
        if (toAdd.isEmpty()) return;
        boolean addingAll = toAdd.contains(ALL_ATTRIBUTES);
        boolean addingSpecific = toAdd.stream().anyMatch(s -> !ALL_ATTRIBUTES.equals(s));

        // Adding ALL_ATTRIBUTES collapses any specific selection.
        if (addingAll) {
            for (String item : new ArrayList<>(selectedItems)) {
                if (!ALL_ATTRIBUTES.equals(item)) {
                    selectedItems.remove(item);
                    if (!availableItems.contains(item)) availableItems.add(item);
                }
            }
        }
        // Adding any specific attribute removes ALL_ATTRIBUTES.
        if (addingSpecific && selectedItems.contains(ALL_ATTRIBUTES)) {
            selectedItems.remove(ALL_ATTRIBUTES);
            if (!availableItems.contains(ALL_ATTRIBUTES)) availableItems.add(ALL_ATTRIBUTES);
        }
        for (String item : toAdd) {
            if (selectedItems.contains(item)) continue;
            availableItems.remove(item);
            selectedItems.add(item);
        }
    }

    private void removeFromSelected(List<String> toRemove) {
        for (String item : toRemove) {
            if (selectedItems.remove(item) && !availableItems.contains(item)) {
                availableItems.add(item);
            }
        }
    }

    /**
     * Reconcile selectedItems / availableItems with the pasted text. The
     * resulting selectedItems list mirrors the paste order exactly. When
     * possible, casing is canonicalized to match the schema.
     */
    private void reconcileFromPaste(String text,
                                    java.util.Map<String, com.pointbluetech.arborj.model.LDAPAttributeInfo> attrMap) {
        List<String> tokens = parseAttributeList(text);
        List<String> resolved = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            if ("*".equals(t)) {
                if (!resolved.contains(ALL_ATTRIBUTES)) resolved.add(ALL_ATTRIBUTES);
                continue;
            }
            String canonical = t;
            if (attrMap != null) {
                var info = attrMap.get(t.toLowerCase());
                if (info != null) canonical = info.getName();
            }
            if (!resolved.contains(canonical)) resolved.add(canonical);
        }

        // Move everything currently selected back to available, then bring
        // the resolved list across in order. setAll keeps the order the user
        // typed.
        for (String item : new ArrayList<>(selectedItems)) {
            if (!resolved.contains(item) && !availableItems.contains(item)) {
                availableItems.add(item);
            }
        }
        for (String item : resolved) {
            availableItems.remove(item);
        }
        selectedItems.setAll(resolved);
    }

    /** Names not in the schema, ignoring "*", "+", "1.1", and ALL_ATTRIBUTES. */
    private static List<String> findUnknown(List<String> items,
                                             java.util.Map<String, com.pointbluetech.arborj.model.LDAPAttributeInfo> attrMap) {
        if (attrMap == null || attrMap.isEmpty()) return List.of();
        List<String> unknown = new ArrayList<>();
        for (String item : items) {
            if (ALL_ATTRIBUTES.equals(item)) continue;
            if (SPECIAL_TOKENS.contains(item)) continue;
            // Tolerate the ;binary attribute option.
            String base = item;
            int semi = item.indexOf(';');
            if (semi > 0) base = item.substring(0, semi);
            if (!attrMap.containsKey(base.toLowerCase())) unknown.add(item);
        }
        return unknown;
    }

    /** Format a list of attribute names as a comma-separated string. */
    private static String formatCurrent(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ALL_ATTRIBUTES.equals(item) ? "*" : item);
        }
        return sb.toString();
    }

    /**
     * Parse a free-form attribute list. Splits on commas, semicolons, and
     * whitespace; trims each token; dedupes case-insensitively while
     * preserving the user's casing and the input order.
     */
    static List<String> parseAttributeList(String input) {
        if (input == null || input.isBlank()) return List.of();
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (String raw : input.split("[\\s,;]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            // Strip wrapping quotes if pasted from a CSV-style source.
            if (t.length() >= 2
                    && ((t.startsWith("\"") && t.endsWith("\""))
                        || (t.startsWith("'") && t.endsWith("'")))) {
                t = t.substring(1, t.length() - 1).trim();
                if (t.isEmpty()) continue;
            }
            seen.putIfAbsent(t.toLowerCase(), t);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Returns the selected attributes, or null if cancelled.
     * The special "[All Attributes]" entry is represented as "*".
     */
    public List<String> getSelectedAttributes() {
        return result;
    }

    /** Load the persisted paste-list history (most recent first). */
    private static List<String> loadHistory() {
        String raw = PREFS.get(PREF_HISTORY, "");
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String entry : raw.split(HISTORY_DELIMITER, -1)) {
            if (!entry.isBlank()) out.add(entry);
        }
        return out;
    }

    /**
     * Add {@code entry} to the head of the persisted history (most-recent
     * first), dedupe against existing entries, and cap at {@link #HISTORY_MAX}.
     */
    private static void saveToHistory(String entry) {
        if (entry == null || entry.isBlank()) return;
        List<String> current = new ArrayList<>(loadHistory());
        // Move-to-front: remove existing duplicates, then insert at head.
        Set<String> seen = new LinkedHashSet<>();
        seen.add(entry);
        for (String s : current) {
            if (seen.size() >= HISTORY_MAX) break;
            if (!s.equals(entry)) seen.add(s);
        }
        PREFS.put(PREF_HISTORY, String.join(HISTORY_DELIMITER, seen));
    }
}
