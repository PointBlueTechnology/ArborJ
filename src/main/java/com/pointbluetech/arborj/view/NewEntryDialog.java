package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.*;

/**
 * Two-step wizard dialog for creating a new LDAP entry.
 *
 * Step 1: Select objectClasses and specify the RDN.
 * Step 2: Fill in required (MUST) attributes for the chosen objectClasses.
 */
public class NewEntryDialog extends Dialog<Void> {

    private final MainController controller;
    private final String parentDN;

    // Step 1 controls
    private final TextField rdnField = new TextField();
    private final TextField filterField = new TextField();
    private final ListView<String> availableList = new ListView<>();
    private final ListView<String> selectedList = new ListView<>();
    private final ObservableList<String> availableClasses = FXCollections.observableArrayList();
    private final ObservableList<String> selectedClasses = FXCollections.observableArrayList();

    // RDN naming attribute combo
    private ComboBox<String> rdnAttrCombo;

    // Step 2 controls
    private final GridPane attributeGrid = new GridPane();
    private final Map<String, TextField> attributeFields = new LinkedHashMap<>();

    // Wizard panes
    private final VBox step1Pane;
    private final VBox step2Pane;
    private final StackPane contentStack = new StackPane();

    // Navigation buttons
    private final Button backButton = new Button("Back");
    private final Button nextButton = new Button("Next");
    private final Button createButton = new Button("Create");

    private int currentStep = 1;

    public NewEntryDialog(MainController controller, String parentDN) {
        this(controller, parentDN, null);
    }

    public NewEntryDialog(MainController controller, String parentDN,
                          javafx.stage.Window owner) {
        this.controller = controller;
        this.parentDN = parentDN;

        setTitle("New Entry");
        setResizable(true);
        if (owner != null) initOwner(owner);

        // Populate available objectClasses from schema
        Map<String, LDAPObjectClassInfo> ocMap = controller.getSchemaService().getObjectClassMap();
        if (ocMap != null) {
            List<String> sorted = new ArrayList<>(ocMap.keySet());
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            availableClasses.addAll(sorted);
        }

        step1Pane = buildStep1();
        step2Pane = buildStep2();

        contentStack.getChildren().addAll(step1Pane, step2Pane);
        showStep(1);

        // Navigation bar
        HBox navBar = buildNavBar();

        VBox root = new VBox(8, contentStack, navBar);
        root.setPadding(new Insets(12));
        root.setPrefWidth(700);
        root.setPrefHeight(500);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // Hide the default close button -- we manage our own buttons
        Button closeBtn = (Button) getDialogPane().lookupButton(ButtonType.CLOSE);
        closeBtn.setVisible(false);
        closeBtn.setManaged(false);

        Font mono = Font.font("monospaced", 12);
        rdnField.setFont(mono);
    }

    // ---- Step 1: ObjectClass selection ----

    private VBox buildStep1() {
        Label parentLabel = new Label("Parent DN: " + parentDN);
        parentLabel.setFont(Font.font("monospaced", 12));

        Label rdnLabel = new Label("RDN:");
        rdnAttrCombo = new ComboBox<>();
        rdnAttrCombo.setEditable(true);
        rdnAttrCombo.setPrefWidth(120);
        rdnAttrCombo.setPromptText("cn");
        Label eqLabel = new Label("=");
        eqLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        rdnField.setPromptText("NewUser");
        HBox.setHgrow(rdnField, Priority.ALWAYS);

        // Update naming attribute suggestions when objectClasses change
        selectedClasses.addListener((javafx.collections.ListChangeListener<String>) c -> {
            updateNamingAttributes(rdnAttrCombo);
        });

        HBox rdnRow = new HBox(6, rdnLabel, rdnAttrCombo, eqLabel, rdnField);
        rdnRow.setAlignment(Pos.CENTER_LEFT);

        // Filter
        filterField.setPromptText("Filter objectClasses...");
        FilteredList<String> filteredAvailable = new FilteredList<>(availableClasses, s -> true);
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lowerFilter = newVal == null ? "" : newVal.toLowerCase();
            filteredAvailable.setPredicate(oc ->
                    lowerFilter.isEmpty() || oc.toLowerCase().contains(lowerFilter));
        });
        availableList.setItems(filteredAvailable);

        // Selected list
        selectedList.setItems(selectedClasses);

        // Add/Remove buttons
        Button addBtn = new Button("Add >");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> {
            String sel = availableList.getSelectionModel().getSelectedItem();
            if (sel != null && !selectedClasses.contains(sel)) {
                selectedClasses.add(sel);
                availableClasses.remove(sel);
            }
        });

        Button removeBtn = new Button("< Remove");
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        removeBtn.setOnAction(e -> {
            String sel = selectedList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                selectedClasses.remove(sel);
                availableClasses.add(sel);
                availableClasses.sort(String.CASE_INSENSITIVE_ORDER);
            }
        });

        VBox buttonCol = new VBox(8, addBtn, removeBtn);
        buttonCol.setAlignment(Pos.CENTER);
        buttonCol.setPadding(new Insets(0, 8, 0, 8));

        // Labels
        VBox leftCol = new VBox(4, new Label("Available ObjectClasses:"), filterField, availableList);
        VBox.setVgrow(availableList, Priority.ALWAYS);

        VBox rightCol = new VBox(4, new Label("Selected ObjectClasses:"), selectedList);
        VBox.setVgrow(selectedList, Priority.ALWAYS);

        HBox.setHgrow(leftCol, Priority.ALWAYS);
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox listsRow = new HBox(0, leftCol, buttonCol, rightCol);
        HBox.setHgrow(listsRow, Priority.ALWAYS);

        VBox pane = new VBox(8, parentLabel, rdnRow, listsRow);
        VBox.setVgrow(listsRow, Priority.ALWAYS);
        pane.setPadding(new Insets(4));
        return pane;
    }

    // ---- Step 2: Required attributes ----

    private VBox buildStep2() {
        attributeGrid.setHgap(8);
        attributeGrid.setVgap(8);
        attributeGrid.setPadding(new Insets(8));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(180);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        attributeGrid.getColumnConstraints().addAll(labelCol, fieldCol);

        ScrollPane scroll = new ScrollPane(attributeGrid);
        scroll.setFitToWidth(true);

        Label header = new Label("Required Attributes");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        VBox pane = new VBox(8, header, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        pane.setPadding(new Insets(4));
        return pane;
    }

    private void populateAttributeFields() {
        attributeGrid.getChildren().clear();
        attributeFields.clear();

        Map<String, LDAPObjectClassInfo> ocMap = controller.getSchemaService().getObjectClassMap();
        if (ocMap == null) return;

        // Collect all MUST attributes from selected objectClasses, de-duplicating
        // case-insensitively (LDAP attribute names are case-insensitive).
        Map<String, String> mustAttrsByLower = new LinkedHashMap<>();
        for (String ocName : selectedClasses) {
            LDAPObjectClassInfo info = ocMap.get(ocName);
            if (info != null && info.getMustAttributes() != null) {
                for (String attr : info.getMustAttributes()) {
                    mustAttrsByLower.putIfAbsent(attr.toLowerCase(Locale.ROOT), attr);
                }
            }
        }
        // objectClass is handled by the step 1 multiselect; never ask for it again
        mustAttrsByLower.remove("objectclass");
        Collection<String> mustAttrs = mustAttrsByLower.values();

        // Parse RDN attribute name and value
        String rdnAttr = rdnAttrCombo.getValue();
        String rdnValue = rdnField.getText().trim();
        if (rdnAttr == null || rdnAttr.isBlank()) rdnAttr = "cn";

        Font mono = Font.font("monospaced", 12);
        int row = 0;

        for (String attr : mustAttrs) {
            Label label = new Label(attr + " *");
            label.setStyle("-fx-font-weight: bold;");

            TextField field = new TextField();
            field.setFont(mono);

            // Pre-populate the RDN attribute
            if (attr.equalsIgnoreCase(rdnAttr)) {
                field.setText(rdnValue != null ? rdnValue : "");
            }

            attributeGrid.add(label, 0, row);
            attributeGrid.add(field, 1, row);
            attributeFields.put(attr, field);
            row++;
        }

        if (mustAttrs.isEmpty()) {
            Label noAttrs = new Label("No required attributes (besides objectClass).");
            attributeGrid.add(noAttrs, 0, 0, 2, 1);
        }
    }

    // ---- Navigation ----

    private HBox buildNavBar() {
        backButton.setOnAction(e -> showStep(1));
        nextButton.setOnAction(e -> {
            if (validateStep1()) {
                populateAttributeFields();
                showStep(2);
            }
        });
        createButton.setOnAction(e -> doCreate());

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, cancelButton, spacer, backButton, nextButton, createButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 0, 0, 0));
        return bar;
    }

    private void showStep(int step) {
        currentStep = step;
        step1Pane.setVisible(step == 1);
        step1Pane.setManaged(step == 1);
        step2Pane.setVisible(step == 2);
        step2Pane.setManaged(step == 2);

        backButton.setVisible(step == 2);
        backButton.setManaged(step == 2);
        nextButton.setVisible(step == 1);
        nextButton.setManaged(step == 1);
        createButton.setVisible(step == 2);
        createButton.setManaged(step == 2);
    }

    private void updateNamingAttributes(ComboBox<String> combo) {
        Map<String, LDAPObjectClassInfo> ocMap = controller.getSchemaService().getObjectClassMap();
        if (ocMap == null) return;

        Set<String> namingAttrs = new LinkedHashSet<>();
        for (String ocName : selectedClasses) {
            LDAPObjectClassInfo info = ocMap.get(ocName);
            if (info != null && info.getNamingAttributes() != null) {
                namingAttrs.addAll(info.getNamingAttributes());
            }
        }

        // Fallback: common naming attributes
        if (namingAttrs.isEmpty()) {
            namingAttrs.addAll(List.of("cn", "ou", "o", "c", "dc", "uid"));
        }

        String current = combo.getValue();
        combo.getItems().setAll(namingAttrs);
        if (current != null && namingAttrs.contains(current)) {
            combo.setValue(current);
        } else if (!namingAttrs.isEmpty()) {
            combo.setValue(namingAttrs.iterator().next());
        }
    }

    private String getRDN() {
        String attr = rdnAttrCombo.getValue();
        String value = rdnField.getText().trim();
        if (attr == null || attr.isBlank()) attr = "cn";
        return attr + "=" + value;
    }

    private boolean validateStep1() {
        String value = rdnField.getText().trim();
        if (value.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Validation",
                    "Please enter an RDN value.");
            return false;
        }
        if (selectedClasses.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Validation",
                    "Please select at least one objectClass.");
            return false;
        }
        return true;
    }

    // ---- Create entry ----

    private void doCreate() {
        String rdn = getRDN();

        // Validate required fields are filled
        for (var entry : attributeFields.entrySet()) {
            String value = entry.getValue().getText().trim();
            if (value.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Validation",
                        "Required attribute '" + entry.getKey() + "' must not be empty.");
                entry.getValue().requestFocus();
                return;
            }
        }

        // Build attribute map
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (var entry : attributeFields.entrySet()) {
            String value = entry.getValue().getText().trim();
            if (!value.isEmpty()) {
                attributes.put(entry.getKey(), List.of(value));
            }
        }

        List<String> objectClasses = new ArrayList<>(selectedClasses);

        controller.createEntry(parentDN, rdn, objectClasses, attributes);
        close();
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }
}
