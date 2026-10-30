package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import com.unboundid.ldap.sdk.RDN;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.*;
import java.util.regex.Pattern;

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

    /** RFC 4512 descr: ALPHA *( ALPHA / DIGIT / HYPHEN ). */
    private static final Pattern ATTR_DESCR = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    /** RFC 4512 numericoid: number *( DOT number ). */
    private static final Pattern ATTR_OID = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");

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

        // Prefill the RDN attribute from the (unescaped) RDN field value
        String rdnAttr = rdnAttrCombo.getValue();
        String rdnValue = rdnField.getText() == null ? "" : rdnField.getText();
        if (rdnAttr == null || rdnAttr.isBlank()) rdnAttr = "cn";

        Font mono = Font.font("monospaced", 12);
        int row = 0;

        for (String attr : mustAttrs) {
            Label label = new Label(attr + " *");
            label.setStyle("-fx-font-weight: bold;");

            TextField field = new TextField();
            field.setFont(mono);

            // Prefill the RDN attribute from Step 1 and keep it read-only so
            // the Add cannot disagree with the DN (Arbor does the same).
            if (attr.equalsIgnoreCase(rdnAttr)) {
                field.setText(rdnValue != null ? rdnValue : "");
                field.setEditable(false);
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

        List<LDAPObjectClassInfo> selected = new ArrayList<>();
        for (String ocName : selectedClasses) {
            selected.add(ocMap.get(ocName));
        }
        List<String> namingAttrs = namingAttributeChoices(selected);

        String current = combo.getValue();
        combo.getItems().setAll(namingAttrs);
        if (current != null && namingAttrs.contains(current)) {
            combo.setValue(current);
        } else if (!namingAttrs.isEmpty()) {
            combo.setValue(namingAttrs.getFirst());
        }
    }

    private String getRDN() {
        return formatRDN(rdnAttrCombo.getValue(), rdnField.getText());
    }

    private boolean validateStep1() {
        String value = rdnField.getText() == null ? "" : rdnField.getText().trim();
        if (value.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Validation",
                    "Please enter an RDN value.");
            return false;
        }
        rdnField.setText(value);

        String attr = rdnAttrCombo.getValue();
        if (attr == null || attr.isBlank()) {
            rdnAttrCombo.setValue("cn");
        } else {
            attr = attr.trim();
            if (!isValidAttributeType(attr)) {
                showAlert(Alert.AlertType.WARNING, "Validation",
                        "RDN attribute must be an LDAP name such as cn, or a numeric OID such as 2.5.4.3.");
                return false;
            }
            rdnAttrCombo.setValue(attr);
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
        String rdnAttr = rdnAttrCombo.getValue();
        String rdnValue = rdnField.getText() == null ? "" : rdnField.getText();
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

        Map<String, String> fieldValues = new LinkedHashMap<>();
        for (var entry : attributeFields.entrySet()) {
            fieldValues.put(entry.getKey(), entry.getValue().getText());
        }

        Map<String, List<String>> attributes = buildCreateAttributes(fieldValues, rdnAttr, rdnValue);

        List<String> objectClasses = new ArrayList<>(selectedClasses);

        controller.createEntry(parentDN, rdn, objectClasses, attributes);
        close();
    }

    /**
     * Builds {@code attributeType=} plus an RFC 4514-escaped value. The RDN
     * text field is the unescaped attribute value; {@code +} is escaped as
     * part of that value (multi-valued RDNs are not supported).
     */
    static String formatRDN(String attr, String unescapedValue) {
        if (attr == null || attr.isBlank()) attr = "cn";
        else attr = attr.trim();
        String value = unescapedValue == null ? "" : unescapedValue.trim();
        return new RDN(attr, value).toString();
    }

    /**
     * Builds the LDAP Add attribute map from MUST field values plus the RDN.
     * The RDN attribute is always included even when it is MAY (not a MUST
     * field) — Active Directory requires the naming attribute on Add.
     * The Add value is the unescaped RDN text (not the DN-escaped form).
     * If the RDN attribute is already a MUST field, the RDN value wins so
     * the Add cannot disagree with the DN.
     */
    static Map<String, List<String>> buildCreateAttributes(Map<String, String> fieldValues,
                                                           String rdnAttr, String rdnValue) {
        String attr = (rdnAttr == null || rdnAttr.isBlank()) ? "cn" : rdnAttr.trim();
        String value = rdnValue == null ? "" : rdnValue.trim();

        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (fieldValues != null) {
            for (var entry : fieldValues.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                if (entry.getKey().equalsIgnoreCase(attr)) continue;
                String fieldValue = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!fieldValue.isEmpty()) {
                    attributes.put(entry.getKey(), List.of(fieldValue));
                }
            }
        }

        if (!value.isBlank()) {
            attributes.put(attr, List.of(value));
        }
        return attributes;
    }

    /**
     * True for an RFC 4512 attribute descr ({@code cn}, {@code telephoneNumber})
     * or a numeric OID ({@code 2.5.4.3}).
     */
    static boolean isValidAttributeType(String attr) {
        if (attr == null || attr.isBlank()) return false;
        String trimmed = attr.trim();
        return ATTR_DESCR.matcher(trimmed).matches() || ATTR_OID.matcher(trimmed).matches();
    }

    /**
     * RDN combo choices for the selected objectClasses: schema naming
     * attributes (X-NDS_NAMING / AD rDNAttID) in encounter order — so the
     * class's naming attribute is first — then the conventional cn/ou/o/c/dc/uid
     * fallback when the schema has none.
     */
    static List<String> namingAttributeChoices(Iterable<LDAPObjectClassInfo> selectedClassInfos) {
        Set<String> namingAttrs = new LinkedHashSet<>();
        if (selectedClassInfos != null) {
            for (LDAPObjectClassInfo info : selectedClassInfos) {
                if (info != null && info.getNamingAttributes() != null) {
                    namingAttrs.addAll(info.getNamingAttributes());
                }
            }
        }
        if (namingAttrs.isEmpty()) {
            namingAttrs.addAll(List.of("cn", "ou", "o", "c", "dc", "uid"));
        }
        return new ArrayList<>(namingAttrs);
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
