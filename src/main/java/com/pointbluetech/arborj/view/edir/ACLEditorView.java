package com.pointbluetech.arborj.view.edir;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.ACLModels;
import com.pointbluetech.arborj.model.ACLModels.ACLEntry;
import com.pointbluetech.arborj.model.ACLModels.ACLScope;
import com.pointbluetech.arborj.model.ACLModels.TrusteeGroup;
import com.pointbluetech.arborj.model.LDAPAttributeInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

/**
 * Stage-based dialog for editing eDirectory ACLs on an entry.
 * Shows trustee groups with expandable rights detail and supports
 * add/edit/delete trustee operations.
 */
public class ACLEditorView {

    private static final Font MONO = Font.font("monospaced", 13);
    private static final Font MONO_SMALL = Font.font("monospaced", 12);

    private final Stage stage;
    private final MainController controller;
    private final String dn;
    private final boolean readOnly;

    private final VBox trusteeListBox = new VBox();
    private final Set<String> expandedTrustees = new HashSet<>();
    private List<TrusteeGroup> groups = new ArrayList<>();

    public ACLEditorView(MainController controller, String dn) {
        this(controller, dn, null);
    }

    public ACLEditorView(MainController controller, String dn, javafx.stage.Window owner) {
        this.controller = controller;
        this.dn = dn;
        this.readOnly = controller.readOnlyProperty().get();

        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("ACL Editor");
        stage.setResizable(true);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        // --- Title bar ---
        Label titleLabel = new Label("ACL Editor");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");

        Label dnLabel = new Label(dn);
        dnLabel.setFont(MONO_SMALL);
        dnLabel.setStyle("-fx-text-fill: -color-accent-fg;");
        dnLabel.setWrapText(true);

        VBox headerBox = new VBox(2, titleLabel, dnLabel);

        if (readOnly) {
            Label roWarning = new Label("Read-only connection \u2014 changes cannot be saved");
            roWarning.setStyle("-fx-text-fill: #c9510c; -fx-font-size: 11; -fx-font-style: italic;");
            headerBox.getChildren().add(roWarning);
        }

        // --- Trustee list (scrollable) ---
        ScrollPane scrollPane = new ScrollPane(trusteeListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        trusteeListBox.setSpacing(1);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // --- Button bar ---
        Button addTrusteeBtn = new Button("Add Trustee");
        addTrusteeBtn.setOnAction(e -> showAddTrusteeDialog());
        addTrusteeBtn.setDisable(readOnly);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> stage.close());

        Button saveBtn = new Button("Save Changes");
        saveBtn.setDefaultButton(true);
        saveBtn.setDisable(readOnly);
        saveBtn.setOnAction(e -> saveChanges());

        HBox buttonBar = new HBox(8, addTrusteeBtn, spacer, cancelBtn, saveBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(8, 0, 0, 0));

        root.getChildren().addAll(headerBox, new Separator(), scrollPane, new Separator(), buttonBar);

        Scene scene = new Scene(root, 700, 550);
        stage.setScene(scene);

        // Load ACLs
        loadACLs();
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    private void loadACLs() {
        trusteeListBox.getChildren().clear();
        Label loadingLabel = new Label("Loading ACLs...");
        loadingLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
        trusteeListBox.getChildren().add(loadingLabel);

        Thread.startVirtualThread(() -> {
            try {
                List<TrusteeGroup> loaded = controller.loadACLs(dn);
                Platform.runLater(() -> {
                    groups = new ArrayList<>(loaded);
                    rebuildTrusteeList();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    trusteeListBox.getChildren().clear();
                    Label errLabel = new Label("Error loading ACLs: " + ex.getMessage());
                    errLabel.setStyle("-fx-text-fill: red;");
                    errLabel.setWrapText(true);
                    trusteeListBox.getChildren().add(errLabel);
                });
            }
        });
    }

    // -----------------------------------------------------------------------
    // Trustee list rendering
    // -----------------------------------------------------------------------

    private void rebuildTrusteeList() {
        trusteeListBox.getChildren().clear();

        if (groups.isEmpty()) {
            Label emptyLabel = new Label("No ACL entries found.");
            emptyLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
            emptyLabel.setPadding(new Insets(16));
            trusteeListBox.getChildren().add(emptyLabel);
            return;
        }

        for (TrusteeGroup group : groups) {
            trusteeListBox.getChildren().add(buildTrusteeGroupNode(group));
        }
    }

    private VBox buildTrusteeGroupNode(TrusteeGroup group) {
        VBox groupBox = new VBox();
        groupBox.setStyle("-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;");

        boolean expanded = expandedTrustees.contains(group.getTrusteeName());

        // --- Header row ---
        Label icon = new Label(group.isSpecialTrustee() ? "\uD83D\uDD12" : "\uD83D\uDC64");
        icon.setStyle("-fx-font-size: 16;");

        Label nameLabel = new Label(group.getTrusteeName());
        nameLabel.setFont(MONO_SMALL);
        nameLabel.setStyle("-fx-text-fill: -color-accent-fg; -fx-font-weight: bold;");

        Label toggleLabel = new Label(expanded ? "\u25BC" : "\u25B6");
        toggleLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -color-fg-muted;");

        int entryCount = group.getEntries().size();
        Label countBadge = new Label(entryCount + (entryCount == 1 ? " entry" : " entries"));
        countBadge.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button editBtn = new Button("Edit");
        editBtn.setStyle("-fx-font-size: 11;");
        editBtn.setDisable(readOnly);
        editBtn.setOnAction(e -> {
            e.consume();
            showEditTrusteeDialog(group);
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-font-size: 11;");
        deleteBtn.setDisable(readOnly);
        deleteBtn.setOnAction(e -> {
            e.consume();
            deleteTrustee(group);
        });

        HBox headerRow = new HBox(8, toggleLabel, icon, nameLabel, countBadge, headerSpacer, editBtn, deleteBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(8));
        headerRow.setStyle("-fx-background-color: -color-bg-subtle; -fx-cursor: hand;");
        headerRow.setOnMouseClicked(e -> {
            if (expanded) {
                expandedTrustees.remove(group.getTrusteeName());
            } else {
                expandedTrustees.add(group.getTrusteeName());
            }
            rebuildTrusteeList();
        });

        groupBox.getChildren().add(headerRow);

        // --- Expanded detail ---
        if (expanded) {
            VBox detailBox = new VBox(4);
            detailBox.setPadding(new Insets(4, 8, 8, 32));

            // Entry rights first
            ACLEntry entryRights = group.getEntryRightsEntry();
            if (entryRights != null) {
                detailBox.getChildren().add(buildACLEntryRow(entryRights));
            }

            // Then attribute rights
            for (ACLEntry attrEntry : group.getAttributeEntries()) {
                detailBox.getChildren().add(buildACLEntryRow(attrEntry));
            }

            groupBox.getChildren().add(detailBox);
        }

        return groupBox;
    }

    private HBox buildACLEntryRow(ACLEntry entry) {
        // Scope badge
        Label scopeBadge = new Label(entry.getScope().getValue());
        String scopeColor = entry.getScope() == ACLScope.ENTRY
                ? "-fx-background-color: #1f6feb; -fx-text-fill: white;"
                : "-fx-background-color: #238636; -fx-text-fill: white;";
        scopeBadge.setStyle(scopeColor
                + " -fx-padding: 1 6; -fx-background-radius: 3; -fx-font-size: 10;");

        // Protected name
        Label protNameLabel = new Label(entry.getProtectedName());
        protNameLabel.setFont(MONO_SMALL);
        protNameLabel.setStyle("-fx-font-weight: bold;");

        // Rights badges
        HBox rightsBox = new HBox(4);
        rightsBox.setAlignment(Pos.CENTER_LEFT);

        String[][] rightsTable = entry.isEntryRights()
                ? ACLModels.ENTRY_RIGHTS
                : ACLModels.ATTRIBUTE_RIGHTS;

        int privs = entry.getPrivileges();
        for (String[] right : rightsTable) {
            int bit = Integer.parseInt(right[1]);
            if ((privs & bit) != 0) {
                Label badge = new Label(right[0]);
                badge.setStyle("-fx-background-color: -color-bg-subtle; "
                        + "-fx-border-color: -color-border-default; -fx-border-width: 1; "
                        + "-fx-border-radius: 3; -fx-background-radius: 3; "
                        + "-fx-padding: 0 4; -fx-font-size: 10;");
                rightsBox.getChildren().add(badge);
            }
        }

        HBox row = new HBox(8, scopeBadge, protNameLabel, rightsBox);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 0, 3, 0));
        return row;
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    private void saveChanges() {
        Thread.startVirtualThread(() -> {
            try {
                controller.saveACLs(dn, groups);
                Platform.runLater(stage::close);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Failed to save ACLs:\n" + ex.getMessage(),
                            ButtonType.OK);
                    alert.setHeaderText("Save Error");
                    alert.showAndWait();
                });
            }
        });
    }

    // -----------------------------------------------------------------------
    // Delete Trustee
    // -----------------------------------------------------------------------

    private void deleteTrustee(TrusteeGroup group) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove all ACL entries for trustee \"" + group.getTrusteeName() + "\"?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Delete Trustee");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                groups.remove(group);
                expandedTrustees.remove(group.getTrusteeName());
                rebuildTrusteeList();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Add Trustee Dialog
    // -----------------------------------------------------------------------

    private void showAddTrusteeDialog() {
        new AddTrusteeDialog().showAndWait();
    }

    private class AddTrusteeDialog {

        private final Stage dlgStage;

        AddTrusteeDialog() {
            dlgStage = new Stage();
            dlgStage.initModality(Modality.APPLICATION_MODAL);
            dlgStage.initOwner(stage);
            dlgStage.setTitle("Add Trustee");
            dlgStage.setResizable(true);

            VBox root = new VBox(10);
            root.setPadding(new Insets(12));

            Label title = new Label("Add Trustee");
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

            // Trustee type
            ComboBox<String> trusteeTypeCombo = new ComboBox<>();
            trusteeTypeCombo.getItems().addAll("DN",
                    "[Public]", "[Root]", "[Self]", "[Creator]", "[Inheritance Mask]");
            trusteeTypeCombo.setValue("DN");

            TextField dnField = new TextField();
            dnField.setFont(MONO_SMALL);
            dnField.setPromptText("Enter trustee DN");

            // DN field visibility bound to type
            trusteeTypeCombo.valueProperty().addListener((obs, oldV, newV) -> {
                boolean isDN = "DN".equals(newV);
                dnField.setVisible(isDN);
                dnField.setManaged(isDN);
            });

            HBox trusteeRow = new HBox(8, new Label("Trustee:"), trusteeTypeCombo, dnField);
            trusteeRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(dnField, Priority.ALWAYS);

            // Rights type toggle
            ToggleGroup rightsToggle = new ToggleGroup();
            RadioButton entryRightsRadio = new RadioButton("Entry Rights");
            entryRightsRadio.setToggleGroup(rightsToggle);
            entryRightsRadio.setSelected(true);
            RadioButton attrRightsRadio = new RadioButton("Attribute Rights");
            attrRightsRadio.setToggleGroup(rightsToggle);

            HBox rightsTypeRow = new HBox(12, new Label("Rights type:"), entryRightsRadio, attrRightsRadio);
            rightsTypeRow.setAlignment(Pos.CENTER_LEFT);

            // Entry rights panel
            VBox entryPanel = new VBox(6);
            entryPanel.setPadding(new Insets(8));
            CheckBox[] entryCBs = createRightsCheckboxes(ACLModels.ENTRY_RIGHTS);
            ComboBox<String> entryScopeCombo = createScopeCombo();
            HBox entryScopeRow = new HBox(8, new Label("Scope:"), entryScopeCombo);
            entryScopeRow.setAlignment(Pos.CENTER_LEFT);
            entryPanel.getChildren().addAll(entryCBs);
            entryPanel.getChildren().add(entryScopeRow);

            // Attribute rights panel
            VBox attrPanel = new VBox(6);
            attrPanel.setPadding(new Insets(8));
            attrPanel.setVisible(false);
            attrPanel.setManaged(false);

            ComboBox<String> attrNameCombo = new ComboBox<>();
            attrNameCombo.setEditable(true);
            attrNameCombo.getItems().add("[All Attributes Rights]");
            // Add schema attributes sorted
            Map<String, LDAPAttributeInfo> attrMap = controller.getSchemaService().getAttributeMap();
            attrMap.values().stream()
                    .map(LDAPAttributeInfo::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> attrNameCombo.getItems().add(name));
            attrNameCombo.setValue("[All Attributes Rights]");

            HBox attrNameRow = new HBox(8, new Label("Attribute:"), attrNameCombo);
            attrNameRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(attrNameCombo, Priority.ALWAYS);

            CheckBox[] attrCBs = createRightsCheckboxes(ACLModels.ATTRIBUTE_RIGHTS);
            ComboBox<String> attrScopeCombo = createScopeCombo();
            HBox attrScopeRow = new HBox(8, new Label("Scope:"), attrScopeCombo);
            attrScopeRow.setAlignment(Pos.CENTER_LEFT);

            attrPanel.getChildren().add(attrNameRow);
            attrPanel.getChildren().addAll(attrCBs);
            attrPanel.getChildren().add(attrScopeRow);

            // Toggle panels based on rights type
            rightsToggle.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                boolean isEntry = newT == entryRightsRadio;
                entryPanel.setVisible(isEntry);
                entryPanel.setManaged(isEntry);
                attrPanel.setVisible(!isEntry);
                attrPanel.setManaged(!isEntry);
            });

            // Buttons
            Button addBtn = new Button("Add");
            addBtn.setDefaultButton(true);
            addBtn.setOnAction(e -> {
                // Resolve trustee name
                String trusteeName;
                if ("DN".equals(trusteeTypeCombo.getValue())) {
                    trusteeName = dnField.getText().trim();
                    if (trusteeName.isEmpty()) {
                        showWarning("Trustee DN is required.");
                        return;
                    }
                } else {
                    trusteeName = trusteeTypeCombo.getValue();
                }

                ACLEntry newEntry;
                if (entryRightsRadio.isSelected()) {
                    int privs = computePrivileges(entryCBs, ACLModels.ENTRY_RIGHTS);
                    ACLScope scope = ACLScope.fromString(entryScopeCombo.getValue());
                    newEntry = new ACLEntry(privs, scope, trusteeName, "[Entry Rights]");
                } else {
                    String protName = attrNameCombo.getValue();
                    if (protName == null || protName.isBlank()) {
                        showWarning("Attribute name is required.");
                        return;
                    }
                    int privs = computePrivileges(attrCBs, ACLModels.ATTRIBUTE_RIGHTS);
                    ACLScope scope = ACLScope.fromString(attrScopeCombo.getValue());
                    newEntry = new ACLEntry(privs, scope, trusteeName, protName);
                }

                // Find or create trustee group
                TrusteeGroup existingGroup = groups.stream()
                        .filter(g -> g.getTrusteeName().equals(trusteeName))
                        .findFirst().orElse(null);

                if (existingGroup != null) {
                    existingGroup.getEntries().add(newEntry);
                } else {
                    List<ACLEntry> entries = new ArrayList<>();
                    entries.add(newEntry);
                    groups.add(new TrusteeGroup(trusteeName, entries));
                }

                expandedTrustees.add(trusteeName);
                rebuildTrusteeList();
                dlgStage.close();
            });

            Button cancelBtn = new Button("Cancel");
            cancelBtn.setCancelButton(true);
            cancelBtn.setOnAction(e -> dlgStage.close());

            Region btnSpacer = new Region();
            HBox.setHgrow(btnSpacer, Priority.ALWAYS);
            HBox buttonBar = new HBox(8, btnSpacer, cancelBtn, addBtn);
            buttonBar.setAlignment(Pos.CENTER_RIGHT);

            root.getChildren().addAll(title, new Separator(),
                    trusteeRow, rightsTypeRow, new Separator(),
                    entryPanel, attrPanel,
                    new Separator(), buttonBar);

            Scene scene = new Scene(root, 520, 420);
            dlgStage.setScene(scene);
        }

        void showAndWait() {
            dlgStage.showAndWait();
        }
    }

    // -----------------------------------------------------------------------
    // Edit Trustee Dialog
    // -----------------------------------------------------------------------

    private void showEditTrusteeDialog(TrusteeGroup group) {
        new EditTrusteeDialog(group).showAndWait();
    }

    private class EditTrusteeDialog {

        private final Stage dlgStage;
        private final TrusteeGroup group;
        private final VBox attrEntriesBox = new VBox(6);

        // Entry rights section
        private final CheckBox[] entryCBs = createRightsCheckboxes(ACLModels.ENTRY_RIGHTS);
        private final ComboBox<String> entryScopeCombo = createScopeCombo();
        private boolean hasEntryRights;

        // Working copy of attribute entries
        private final List<AttrEditRow> attrEditRows = new ArrayList<>();

        EditTrusteeDialog(TrusteeGroup group) {
            this.group = group;

            dlgStage = new Stage();
            dlgStage.initModality(Modality.APPLICATION_MODAL);
            dlgStage.initOwner(stage);
            dlgStage.setTitle("Edit Trustee \u2014 " + group.getTrusteeName());
            dlgStage.setResizable(true);

            VBox root = new VBox(10);
            root.setPadding(new Insets(12));

            Label title = new Label("Edit Trustee");
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

            Label trusteeLabel = new Label(group.getTrusteeName());
            trusteeLabel.setFont(MONO_SMALL);
            trusteeLabel.setStyle("-fx-text-fill: -color-accent-fg;");

            // --- Entry Rights section ---
            Label entryHeader = new Label("Entry Rights");
            entryHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

            ACLEntry entryRights = group.getEntryRightsEntry();
            hasEntryRights = entryRights != null;

            CheckBox enableEntryRights = new CheckBox("Enable entry rights");
            enableEntryRights.setSelected(hasEntryRights);

            VBox entryRightsPanel = new VBox(6);
            entryRightsPanel.setPadding(new Insets(4, 0, 0, 16));

            if (entryRights != null) {
                fillCheckboxes(entryCBs, ACLModels.ENTRY_RIGHTS, entryRights.getPrivileges());
                entryScopeCombo.setValue(entryRights.getScope().getValue());
            }

            HBox entryScopeRow = new HBox(8, new Label("Scope:"), entryScopeCombo);
            entryScopeRow.setAlignment(Pos.CENTER_LEFT);
            entryRightsPanel.getChildren().addAll(entryCBs);
            entryRightsPanel.getChildren().add(entryScopeRow);

            entryRightsPanel.setVisible(hasEntryRights);
            entryRightsPanel.setManaged(hasEntryRights);

            enableEntryRights.selectedProperty().addListener((obs, oldV, newV) -> {
                hasEntryRights = newV;
                entryRightsPanel.setVisible(newV);
                entryRightsPanel.setManaged(newV);
            });

            // --- Attribute Rights section ---
            Label attrHeader = new Label("Attribute Rights");
            attrHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

            for (ACLEntry attrEntry : group.getAttributeEntries()) {
                AttrEditRow row = new AttrEditRow(attrEntry);
                attrEditRows.add(row);
            }
            rebuildAttrEntries();

            Button addAttrBtn = new Button("Add Attribute");
            addAttrBtn.setStyle("-fx-font-size: 11;");
            addAttrBtn.setOnAction(e -> {
                ACLEntry blankEntry = new ACLEntry(0, ACLScope.ENTRY,
                        group.getTrusteeName(), "[All Attributes Rights]");
                AttrEditRow row = new AttrEditRow(blankEntry);
                attrEditRows.add(row);
                rebuildAttrEntries();
            });

            ScrollPane scrollPane = new ScrollPane(new VBox(8,
                    entryHeader, enableEntryRights, entryRightsPanel,
                    new Separator(),
                    attrHeader, attrEntriesBox, addAttrBtn));
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scrollPane, Priority.ALWAYS);

            // Buttons
            Button saveBtn = new Button("Save");
            saveBtn.setDefaultButton(true);
            saveBtn.setOnAction(e -> {
                // Reconstruct TrusteeGroup
                List<ACLEntry> newEntries = new ArrayList<>();

                if (hasEntryRights) {
                    int privs = computePrivileges(entryCBs, ACLModels.ENTRY_RIGHTS);
                    ACLScope scope = ACLScope.fromString(entryScopeCombo.getValue());
                    newEntries.add(new ACLEntry(privs, scope, group.getTrusteeName(), "[Entry Rights]"));
                }

                for (AttrEditRow row : attrEditRows) {
                    String protName = row.attrNameCombo.getValue();
                    if (protName == null || protName.isBlank()) continue;
                    int privs = computePrivileges(row.checkboxes, ACLModels.ATTRIBUTE_RIGHTS);
                    ACLScope scope = ACLScope.fromString(row.scopeCombo.getValue());
                    newEntries.add(new ACLEntry(privs, scope, group.getTrusteeName(), protName));
                }

                // Replace group in-place
                int idx = groups.indexOf(group);
                if (idx >= 0) {
                    groups.set(idx, new TrusteeGroup(group.getTrusteeName(), newEntries));
                }

                expandedTrustees.add(group.getTrusteeName());
                rebuildTrusteeList();
                dlgStage.close();
            });

            Button cancelBtn = new Button("Cancel");
            cancelBtn.setCancelButton(true);
            cancelBtn.setOnAction(e -> dlgStage.close());

            Region btnSpacer = new Region();
            HBox.setHgrow(btnSpacer, Priority.ALWAYS);
            HBox buttonBar = new HBox(8, btnSpacer, cancelBtn, saveBtn);
            buttonBar.setAlignment(Pos.CENTER_RIGHT);

            root.getChildren().addAll(title, trusteeLabel, new Separator(),
                    scrollPane, new Separator(), buttonBar);

            Scene scene = new Scene(root, 560, 500);
            dlgStage.setScene(scene);
        }

        private void rebuildAttrEntries() {
            attrEntriesBox.getChildren().clear();
            for (AttrEditRow row : attrEditRows) {
                attrEntriesBox.getChildren().add(row.getNode());
            }
            if (attrEditRows.isEmpty()) {
                Label none = new Label("No attribute rights defined.");
                none.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
                attrEntriesBox.getChildren().add(none);
            }
        }

        /**
         * A single editable attribute-rights row inside the Edit Trustee dialog.
         */
        private class AttrEditRow {
            final ComboBox<String> attrNameCombo;
            final CheckBox[] checkboxes;
            final ComboBox<String> scopeCombo;

            AttrEditRow(ACLEntry entry) {
                attrNameCombo = new ComboBox<>();
                attrNameCombo.setEditable(true);
                attrNameCombo.getItems().add("[All Attributes Rights]");
                Map<String, LDAPAttributeInfo> attrMap = controller.getSchemaService().getAttributeMap();
                attrMap.values().stream()
                        .map(LDAPAttributeInfo::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(name -> attrNameCombo.getItems().add(name));
                attrNameCombo.setValue(entry.getProtectedName());

                checkboxes = createRightsCheckboxes(ACLModels.ATTRIBUTE_RIGHTS);
                fillCheckboxes(checkboxes, ACLModels.ATTRIBUTE_RIGHTS, entry.getPrivileges());

                scopeCombo = createScopeCombo();
                scopeCombo.setValue(entry.getScope().getValue());
            }

            VBox getNode() {
                VBox box = new VBox(4);
                box.setPadding(new Insets(6));
                box.setStyle("-fx-background-color: -color-bg-subtle; "
                        + "-fx-border-color: -color-border-default; -fx-border-width: 1; "
                        + "-fx-border-radius: 4; -fx-background-radius: 4;");

                Button removeBtn = new Button("Remove");
                removeBtn.setStyle("-fx-font-size: 10;");
                removeBtn.setOnAction(e -> {
                    attrEditRows.remove(this);
                    rebuildAttrEntries();
                });

                Region rowSpacer = new Region();
                HBox.setHgrow(rowSpacer, Priority.ALWAYS);
                HBox.setHgrow(attrNameCombo, Priority.ALWAYS);

                HBox topRow = new HBox(8, new Label("Attr:"), attrNameCombo, rowSpacer, removeBtn);
                topRow.setAlignment(Pos.CENTER_LEFT);

                FlowPane cbPane = new FlowPane(8, 4);
                cbPane.getChildren().addAll(checkboxes);

                HBox scopeRow = new HBox(8, new Label("Scope:"), scopeCombo);
                scopeRow.setAlignment(Pos.CENTER_LEFT);

                box.getChildren().addAll(topRow, cbPane, scopeRow);
                return box;
            }
        }

        void showAndWait() {
            dlgStage.showAndWait();
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static CheckBox[] createRightsCheckboxes(String[][] rightsTable) {
        CheckBox[] cbs = new CheckBox[rightsTable.length];
        for (int i = 0; i < rightsTable.length; i++) {
            cbs[i] = new CheckBox(rightsTable[i][0]);
        }
        return cbs;
    }

    private static ComboBox<String> createScopeCombo() {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll("entry", "subtree");
        combo.setValue("entry");
        return combo;
    }

    private static void fillCheckboxes(CheckBox[] cbs, String[][] rightsTable, int privileges) {
        for (int i = 0; i < rightsTable.length; i++) {
            int bit = Integer.parseInt(rightsTable[i][1]);
            cbs[i].setSelected((privileges & bit) != 0);
        }
    }

    private static int computePrivileges(CheckBox[] cbs, String[][] rightsTable) {
        int privs = 0;
        for (int i = 0; i < rightsTable.length; i++) {
            if (cbs[i].isSelected()) {
                privs |= Integer.parseInt(rightsTable[i][1]);
            }
        }
        return privs;
    }

    private static void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
