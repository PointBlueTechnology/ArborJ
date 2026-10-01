package com.pointbluetech.arborj.view.edir;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.service.AttributeSuggestSettings;
import com.pointbluetech.arborj.model.EffectiveRights;
import com.pointbluetech.arborj.model.EffectiveRights.AttributeRight;
import com.pointbluetech.arborj.model.EffectiveRights.EntryRight;
import com.pointbluetech.arborj.view.DNPickerDialog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.EnumSet;
import java.util.List;

/**
 * Dialog for querying eDirectory effective rights on an entry or attribute.
 */
public class EffectiveRightsView extends Dialog<Void> {

    private final MainController controller;

    private final TextField targetDNField = new TextField();
    private final TextField trusteeDNField = new TextField();
    private final ComboBox<String> attributeCombo = new ComboBox<>();
    private final Button queryButton = new Button("Query");
    private final Label statusLabel = new Label();

    // Entry rights checkboxes
    private final CheckBox browseCB = new CheckBox("Browse");
    private final CheckBox addCB = new CheckBox("Add");
    private final CheckBox deleteCB = new CheckBox("Delete");
    private final CheckBox renameCB = new CheckBox("Rename");
    private final CheckBox supervisorEntryCB = new CheckBox("Supervisor");
    private final CheckBox inheritableEntryCB = new CheckBox("Inheritable");

    // Attribute rights checkboxes
    private final CheckBox compareCB = new CheckBox("Compare");
    private final CheckBox readCB = new CheckBox("Read");
    private final CheckBox writeCB = new CheckBox("Write");
    private final CheckBox addSelfCB = new CheckBox("Add Self");
    private final CheckBox supervisorAttrCB = new CheckBox("Supervisor");
    private final CheckBox inheritableAttrCB = new CheckBox("Inheritable");

    private final GridPane entryRightsBox;
    private final GridPane attrRightsBox;
    private final Label entryRightsHeader = new Label();
    private final Label attrRightsHeader = new Label();

    public EffectiveRightsView(MainController controller) {
        this(controller, null);
    }

    public EffectiveRightsView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("Effective Rights");
        setResizable(true);
        if (owner != null) initOwner(owner);

        Font mono = Font.font("monospaced", 12);
        targetDNField.setFont(mono);
        trusteeDNField.setFont(mono);

        // Pre-fill target DN from selected DN
        String selectedDN = controller.selectedDNProperty().get();
        if (selectedDN != null) {
            targetDNField.setText(selectedDN);
        }

        // Browse buttons for DN fields
        Button browseTarget = new Button("Browse...");
        browseTarget.setOnAction(e -> {
            DNPickerDialog picker = new DNPickerDialog(controller, targetDNField.getText(),
                    getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
            picker.showAndWait();
            String dn = picker.getSelectedDN();
            if (dn != null) targetDNField.setText(dn);
        });

        Button browseTrustee = new Button("Browse...");
        browseTrustee.setOnAction(e -> {
            DNPickerDialog picker = new DNPickerDialog(controller, trusteeDNField.getText(),
                    getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
            picker.showAndWait();
            String dn = picker.getSelectedDN();
            if (dn != null) trusteeDNField.setText(dn);
        });

        // Attribute combo with autocomplete — editable, sorted schema attributes
        attributeCombo.setEditable(true);
        attributeCombo.setPromptText("Leave blank for entry rights");
        attributeCombo.setPrefWidth(300);

        // Populate from schema
        var attrMap = controller.getSchemaService().getAttributeMap();
        if (attrMap != null && !attrMap.isEmpty()) {
            List<String> sorted = attrMap.keySet().stream().sorted().toList();
            attributeCombo.setItems(FXCollections.observableArrayList(sorted));
        }

        // Filter as user types
        attributeCombo.getEditor().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!AttributeSuggestSettings.getInstance().isEnabled()) {
                return;
            }
            if (newVal == null || newVal.isEmpty()) {
                if (attrMap != null) {
                    attributeCombo.getItems().setAll(attrMap.keySet().stream().sorted().toList());
                }
                return;
            }
            String lower = newVal.toLowerCase();
            if (attrMap != null) {
                List<String> filtered = attrMap.keySet().stream()
                        .filter(name -> name.startsWith(lower))
                        .sorted()
                        .limit(20)
                        .toList();
                attributeCombo.getItems().setAll(filtered);
                if (!filtered.isEmpty() && !attributeCombo.isShowing()) {
                    attributeCombo.show();
                }
            }
        });

        // Form layout
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(12));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(160);
        labelCol.setMinWidth(160);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints btnCol = new ColumnConstraints();
        btnCol.setPrefWidth(80);
        form.getColumnConstraints().addAll(labelCol, fieldCol, btnCol);

        int row = 0;
        form.add(new Label("Target DN:"), 0, row);
        form.add(targetDNField, 1, row);
        form.add(browseTarget, 2, row++);

        form.add(new Label("Trustee DN:"), 0, row);
        form.add(trusteeDNField, 1, row);
        form.add(browseTrustee, 2, row++);

        form.add(new Label("Attribute (optional):"), 0, row);
        form.add(attributeCombo, 1, row++);

        Label attrHint = new Label(
                "Leave blank to query the trustee's entry rights on the target. "
                + "Pick an attribute to query its rights instead.");
        attrHint.setWrapText(true);
        attrHint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        form.add(attrHint, 1, row++);

        HBox queryRow = new HBox(8, queryButton);
        queryRow.setAlignment(Pos.CENTER_LEFT);
        form.add(queryRow, 1, row++);

        // Entry rights group — two columns of three checkboxes
        entryRightsBox = buildRightsGrid(browseCB, addCB, deleteCB,
                renameCB, supervisorEntryCB, inheritableEntryCB);

        // Attribute rights group — two columns of three checkboxes
        attrRightsBox = buildRightsGrid(compareCB, readCB, writeCB,
                addSelfCB, supervisorAttrCB, inheritableAttrCB);

        // Results section — each group has its own header that hides with the group
        entryRightsHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
        attrRightsHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
        entryRightsHeader.visibleProperty().bind(entryRightsBox.visibleProperty());
        entryRightsHeader.managedProperty().bind(entryRightsBox.managedProperty());
        attrRightsHeader.visibleProperty().bind(attrRightsBox.visibleProperty());
        attrRightsHeader.managedProperty().bind(attrRightsBox.managedProperty());

        VBox resultsArea = new VBox(4,
                entryRightsHeader, entryRightsBox,
                attrRightsHeader, attrRightsBox);
        resultsArea.setPadding(new Insets(8, 0, 0, 0));

        // Initially hide both until a query is run
        entryRightsBox.setVisible(false);
        entryRightsBox.setManaged(false);
        attrRightsBox.setVisible(false);
        attrRightsBox.setManaged(false);

        VBox content = new VBox(8, form, new Separator(), statusLabel, resultsArea);
        content.setPadding(new Insets(12));
        content.setPrefWidth(600);
        content.setPrefHeight(550);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        queryButton.setOnAction(e -> performQuery());
    }

    private void performQuery() {
        String targetDN = targetDNField.getText().trim();
        String trusteeDN = trusteeDNField.getText().trim();
        String attribute = attributeCombo.getEditor().getText().trim();

        if (targetDN.isEmpty() || trusteeDN.isEmpty()) {
            statusLabel.setText("Target DN and Trustee DN are required.");
            statusLabel.setStyle("-fx-text-fill: -color-danger-fg;");
            return;
        }

        queryButton.setDisable(true);
        statusLabel.setText("Querying...");
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        String attrParam = attribute.isEmpty() ? null : attribute;

        Thread.startVirtualThread(() -> {
            try {
                if (attrParam == null) {
                    // Entry mode: also query [All Attribute Rights] so the user sees
                    // any blanket attribute ACL the trustee has on this target.
                    EffectiveRights entryRights = controller.getLdapService()
                            .getEffectiveRights(targetDN, trusteeDN, null);
                    EffectiveRights allAttrRights = controller.getLdapService()
                            .getEffectiveRights(targetDN, trusteeDN, "[All Attributes Rights]");

                    Platform.runLater(() -> {
                        queryButton.setDisable(false);
                        statusLabel.setText("");

                        entryRightsHeader.setText("Entry Rights");
                        showEntryRights(entryRights.getEntryRights());
                        entryRightsBox.setVisible(true);
                        entryRightsBox.setManaged(true);

                        attrRightsHeader.setText("All Attribute Rights");
                        showAttributeRights(allAttrRights.getAttributeRights());
                        attrRightsBox.setVisible(true);
                        attrRightsBox.setManaged(true);
                    });
                } else {
                    EffectiveRights rights = controller.getLdapService()
                            .getEffectiveRights(targetDN, trusteeDN, attrParam);

                    Platform.runLater(() -> {
                        queryButton.setDisable(false);
                        statusLabel.setText("");

                        attrRightsHeader.setText("Attribute Rights for: " + attrParam);
                        showAttributeRights(rights.getAttributeRights());
                        attrRightsBox.setVisible(true);
                        attrRightsBox.setManaged(true);
                        entryRightsBox.setVisible(false);
                        entryRightsBox.setManaged(false);
                    });
                }

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    queryButton.setDisable(false);
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setStyle("-fx-text-fill: -color-danger-fg;");
                });
            }
        });
    }

    private void showEntryRights(EnumSet<EntryRight> rights) {
        browseCB.setSelected(rights.contains(EntryRight.BROWSE));
        addCB.setSelected(rights.contains(EntryRight.ADD));
        deleteCB.setSelected(rights.contains(EntryRight.DELETE));
        renameCB.setSelected(rights.contains(EntryRight.RENAME));
        supervisorEntryCB.setSelected(rights.contains(EntryRight.SUPERVISOR));
        inheritableEntryCB.setSelected(rights.contains(EntryRight.INHERITABLE));
    }

    private void showAttributeRights(EnumSet<AttributeRight> rights) {
        compareCB.setSelected(rights.contains(AttributeRight.COMPARE));
        readCB.setSelected(rights.contains(AttributeRight.READ));
        writeCB.setSelected(rights.contains(AttributeRight.WRITE));
        addSelfCB.setSelected(rights.contains(AttributeRight.ADD_SELF));
        supervisorAttrCB.setSelected(rights.contains(AttributeRight.SUPERVISOR));
        inheritableAttrCB.setSelected(rights.contains(AttributeRight.INHERITABLE));
    }

    /**
     * Lays out six rights checkboxes in a 2-column × 3-row grid, with all of
     * them disabled (the panel is read-only — the query result drives the
     * checked state).
     */
    private static GridPane buildRightsGrid(CheckBox c1, CheckBox c2, CheckBox c3,
                                            CheckBox c4, CheckBox c5, CheckBox c6) {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        grid.setPadding(new Insets(8));

        CheckBox[] left = {c1, c2, c3};
        CheckBox[] right = {c4, c5, c6};
        for (int i = 0; i < 3; i++) {
            left[i].setDisable(true);
            right[i].setDisable(true);
            grid.add(left[i], 0, i);
            grid.add(right[i], 1, i);
        }
        return grid;
    }
}
