package com.pointbluetech.arborj.view.edir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.SearchScope;
import com.pointbluetech.arborj.service.LDAPService;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dialog for viewing and modifying DirXML-Associations on eDirectory objects.
 * Supports single-object and bulk operations.
 *
 * Association format: driverDN#state#associationValue
 * States: 0=disabled, 1=enabled, 2=migrate, 3=pending
 */
public class AssociationModifierView {

    private final MainController controller;
    private final Stage stage;

    // Single object mode
    private final Label objectDnLabel = new Label();
    private final TextField singleDnField = new TextField();
    private final ComboBox<String> driverFilterCombo = new ComboBox<>();
    private final TableView<AssociationRow> associationTable = new TableView<>();
    private final ObservableList<AssociationRow> allAssociations = FXCollections.observableArrayList();

    // Bulk mode
    private final TextField bulkSearchFilter = new TextField();
    private final TextField bulkBaseDn = new TextField();
    private final ComboBox<String> bulkScopeCombo = new ComboBox<>();
    private final ComboBox<String> bulkOperationCombo = new ComboBox<>();
    private final ComboBox<String> bulkDriverCombo = new ComboBox<>();
    private final ListView<String> bulkObjectList = new ListView<>();
    private final TextArea bulkLog = new TextArea();
    private final ProgressBar bulkProgress = new ProgressBar(0);
    private final Button bulkExecuteBtn = new Button("Execute");

    // Export / Import mode
    private final ComboBox<String> exportDriverCombo = new ComboBox<>();
    private final TextArea exportLog = new TextArea();
    private final ProgressBar exportProgress = new ProgressBar(0);
    private final Button exportBtn = new Button("Export to File…");
    private final Button importBtn = new Button("Import from File…");
    private final CheckBox includeNonEnabledCb =
            new CheckBox("Include non-Enabled associations (Disabled, Migrate, Pending)");

    // Driver cache
    private final ObservableList<String> discoveredDrivers = FXCollections.observableArrayList();

    public AssociationModifierView(MainController controller) {
        this(controller, null, null);
    }

    /**
     * @param initialDn if non-null and non-blank, opens the Single Object tab
     *                  pre-populated with this DN; otherwise falls back to the
     *                  tree's currently-selected node.
     */
    public AssociationModifierView(MainController controller, String initialDn) {
        this(controller, initialDn, null);
    }

    public AssociationModifierView(MainController controller, String initialDn,
                                   javafx.stage.Window owner) {
        this.controller = controller;

        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("DirXML Associations");
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab singleTab = new Tab("Single Object", buildSingleObjectPane());
        Tab bulkTab = new Tab("Bulk Operations", buildBulkPane());
        Tab exportTab = new Tab("Export / Import", buildExportImportPane());
        tabPane.getTabs().addAll(singleTab, bulkTab, exportTab);

        Scene scene = new Scene(tabPane, 900, 650);
        stage.setScene(scene);

        // Load drivers in background
        loadDrivers();

        // Pre-populate: caller-supplied DN wins over the tree's current selection.
        String dnToLoad = (initialDn != null && !initialDn.isBlank())
                ? initialDn
                : controller.selectedDNProperty().get();
        if (dnToLoad != null && !dnToLoad.isEmpty()) {
            singleDnField.setText(dnToLoad);
            objectDnLabel.setText(dnToLoad);
            bulkBaseDn.setText(dnToLoad);
            loadAssociations(dnToLoad);
        }
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    // ---- Single Object Mode ----

    private VBox buildSingleObjectPane() {
        objectDnLabel.setFont(Font.font("monospaced", 13));
        objectDnLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        objectDnLabel.setMaxWidth(Double.MAX_VALUE);

        // DN input with browse
        singleDnField.setPromptText("Enter DN or select from tree...");
        singleDnField.setFont(Font.font("monospaced", 12));

        Button loadBtn = new Button("Load");
        loadBtn.setOnAction(e -> {
            String dn = singleDnField.getText().trim();
            if (!dn.isEmpty()) {
                objectDnLabel.setText(dn);
                loadAssociations(dn);
            }
        });

        HBox dnRow = new HBox(8, new Label("Object DN:"), singleDnField, loadBtn);
        dnRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(singleDnField, Priority.ALWAYS);

        // Driver filter
        driverFilterCombo.getItems().add("All Drivers");
        driverFilterCombo.getSelectionModel().selectFirst();
        driverFilterCombo.setOnAction(e -> applyDriverFilter());

        HBox filterRow = new HBox(8, new Label("Filter by Driver:"), driverFilterCombo);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        // Table
        buildAssociationTable();

        // Action buttons
        Button addBtn = new Button("Add Association");
        addBtn.setOnAction(e -> showAddDialog());
        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> {
            String dn = objectDnLabel.getText();
            if (dn != null && !dn.isEmpty()) loadAssociations(dn);
        });

        HBox actionRow = new HBox(8, addBtn, refreshBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox pane = new VBox(10, dnRow, objectDnLabel, filterRow, associationTable, actionRow);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(associationTable, Priority.ALWAYS);
        return pane;
    }

    private void buildAssociationTable() {
        TableColumn<AssociationRow, String> driverCol = new TableColumn<>("Driver");
        driverCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().driverName()));
        driverCol.setPrefWidth(200);

        TableColumn<AssociationRow, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().stateLabel()));
        stateCol.setPrefWidth(100);
        stateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(switch (item) {
                        case "Enabled" -> "-fx-text-fill: green; -fx-font-weight: bold;";
                        case "Disabled" -> "-fx-text-fill: red; -fx-font-weight: bold;";
                        case "Migrate" -> "-fx-text-fill: orange; -fx-font-weight: bold;";
                        default -> "-fx-text-fill: gray; -fx-font-weight: bold;";
                    });
                }
            }
        });

        TableColumn<AssociationRow, String> valueCol = new TableColumn<>("Association Value");
        valueCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().value()));
        valueCol.setPrefWidth(300);
        valueCol.setCellFactory(col -> {
            TableCell<AssociationRow, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item);
                    setFont(Font.font("monospaced", 12));
                }
            };
            return cell;
        });

        TableColumn<AssociationRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setPrefWidth(220);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button toggleBtn = new Button();
            private final Button migrateBtn = new Button("Migrate");
            private final Button removeBtn = new Button("Remove");
            private final HBox box = new HBox(4, toggleBtn, migrateBtn, removeBtn);

            {
                toggleBtn.setStyle("-fx-font-size: 11;");
                migrateBtn.setStyle("-fx-font-size: 11;");
                removeBtn.setStyle("-fx-font-size: 11;");
                box.setAlignment(Pos.CENTER);

                toggleBtn.setOnAction(e -> {
                    AssociationRow row = getTableView().getItems().get(getIndex());
                    toggleState(row);
                });
                migrateBtn.setOnAction(e -> {
                    AssociationRow row = getTableView().getItems().get(getIndex());
                    setMigrate(row);
                });
                removeBtn.setOnAction(e -> {
                    AssociationRow row = getTableView().getItems().get(getIndex());
                    removeAssociation(row);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    AssociationRow row = getTableView().getItems().get(getIndex());
                    toggleBtn.setText(row.state() == 1 ? "Disable" : "Enable");
                    setGraphic(box);
                }
            }
        });

        associationTable.getColumns().addAll(driverCol, stateCol, valueCol, actionsCol);
        associationTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        associationTable.setPlaceholder(new Label("No associations found. Load an object above."));
    }

    private void loadAssociations(String dn) {
        allAssociations.clear();
        associationTable.setItems(allAssociations);
        associationTable.setPlaceholder(new Label("Loading..."));

        Thread.startVirtualThread(() -> {
            try {
                Map<String, List<String>> attrs = controller.getLdapService()
                        .fetchAttributes(dn, false);
                List<String> values = findAttribute(attrs, "DirXML-Associations");

                List<AssociationRow> rows = new ArrayList<>();
                for (String raw : values) {
                    DirXMLAssociation assoc = DirXMLAssociation.parse(raw);
                    if (assoc != null) {
                        rows.add(new AssociationRow(dn, assoc, raw));
                    }
                }

                Platform.runLater(() -> {
                    allAssociations.setAll(rows);
                    updateDriverFilter();
                    applyDriverFilter();
                    if (rows.isEmpty()) {
                        associationTable.setPlaceholder(
                                new Label("No DirXML-Associations on this object."));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                        associationTable.setPlaceholder(
                                new Label("Error: " + ex.getMessage())));
            }
        });
    }

    private List<String> findAttribute(Map<String, List<String>> attrs, String name) {
        List<String> values = attrs.get(name);
        if (values != null) return values;
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return List.of();
    }

    private void updateDriverFilter() {
        String selected = driverFilterCombo.getValue();
        driverFilterCombo.getItems().clear();
        driverFilterCombo.getItems().add("All Drivers");
        for (AssociationRow row : allAssociations) {
            String dn = row.association().driverDN();
            if (!driverFilterCombo.getItems().contains(dn)) {
                driverFilterCombo.getItems().add(dn);
            }
        }
        if (selected != null && driverFilterCombo.getItems().contains(selected)) {
            driverFilterCombo.setValue(selected);
        } else {
            driverFilterCombo.getSelectionModel().selectFirst();
        }
    }

    private void applyDriverFilter() {
        String filter = driverFilterCombo.getValue();
        if (filter == null || "All Drivers".equals(filter)) {
            associationTable.setItems(allAssociations);
        } else {
            ObservableList<AssociationRow> filtered = allAssociations.filtered(
                    row -> row.association().driverDN().equalsIgnoreCase(filter));
            associationTable.setItems(filtered);
        }
    }

    private void toggleState(AssociationRow row) {
        int newState = row.state() == 1 ? 0 : 1;
        modifyAssociationState(row, newState);
    }

    private void setMigrate(AssociationRow row) {
        modifyAssociationState(row, 2);
    }

    private void modifyAssociationState(AssociationRow row, int newState) {
        DirXMLAssociation updated = new DirXMLAssociation(
                row.association().driverDN(), newState, row.association().value());

        Thread.startVirtualThread(() -> {
            try {
                LDAPService svc = controller.getLdapService();
                svc.modifyAttribute(row.objectDn(), "DirXML-Associations",
                        LDAPService.LDAPModifyOperation.DELETE, List.of(row.rawValue()));
                svc.modifyAttribute(row.objectDn(), "DirXML-Associations",
                        LDAPService.LDAPModifyOperation.ADD, List.of(updated.serialize()));

                Platform.runLater(() -> loadAssociations(row.objectDn()));
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Failed to modify association", ex.getMessage()));
            }
        });
    }

    private void removeAssociation(AssociationRow row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove this association?\n\n" + row.rawValue(),
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Removal");
        confirm.setHeaderText("Remove Association");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                Thread.startVirtualThread(() -> {
                    try {
                        controller.getLdapService().modifyAttribute(
                                row.objectDn(), "DirXML-Associations",
                                LDAPService.LDAPModifyOperation.DELETE, List.of(row.rawValue()));
                        Platform.runLater(() -> loadAssociations(row.objectDn()));
                    } catch (Exception ex) {
                        Platform.runLater(() -> showError("Failed to remove", ex.getMessage()));
                    }
                });
            }
        });
    }

    private void showAddDialog() {
        String objectDn = objectDnLabel.getText();
        if (objectDn == null || objectDn.isEmpty()) {
            showError("No Object", "Load an object first.");
            return;
        }

        Stage addStage = new Stage();
        addStage.initModality(Modality.APPLICATION_MODAL);
        addStage.initOwner(stage);
        addStage.setTitle("Add Association");
        addStage.setResizable(false);

        ComboBox<String> driverCombo = new ComboBox<>(discoveredDrivers);
        driverCombo.setEditable(true);
        driverCombo.setPromptText("Driver DN...");
        driverCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> stateCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Enabled", "Disabled", "Migrate", "Pending"));
        stateCombo.getSelectionModel().selectFirst();

        TextField valueField = new TextField();
        valueField.setPromptText("Association value (remote system ID)...");
        valueField.setFont(Font.font("monospaced", 12));

        Button addBtn = new Button("Add");
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> addStage.close());

        addBtn.setOnAction(e -> {
            String driverDn = driverCombo.getEditor().getText().trim();
            if (driverDn.isEmpty()) {
                showError("Missing Driver", "Select or enter a driver DN.");
                return;
            }
            String val = valueField.getText().trim();
            int state = switch (stateCombo.getValue()) {
                case "Disabled" -> 0;
                case "Migrate" -> 2;
                case "Pending" -> 3;
                default -> 1;
            };

            DirXMLAssociation assoc = new DirXMLAssociation(driverDn, state, val);

            Thread.startVirtualThread(() -> {
                try {
                    controller.getLdapService().modifyAttribute(
                            objectDn, "DirXML-Associations",
                            LDAPService.LDAPModifyOperation.ADD, List.of(assoc.serialize()));
                    Platform.runLater(() -> {
                        addStage.close();
                        loadAssociations(objectDn);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> showError("Failed to add", ex.getMessage()));
                }
            });
        });

        HBox buttons = new HBox(8, addBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
                new Label("Driver DN:"), driverCombo,
                new Label("State:"), stateCombo,
                new Label("Association Value:"), valueField,
                buttons);
        content.setPadding(new Insets(16));
        content.setPrefWidth(500);

        addStage.setScene(new Scene(content));
        addStage.showAndWait();
    }

    // ---- Bulk Mode ----

    private VBox buildExportImportPane() {
        exportDriverCombo.setItems(discoveredDrivers);
        exportDriverCombo.setEditable(true);
        exportDriverCombo.setPromptText("Select driver…");
        exportDriverCombo.setMaxWidth(Double.MAX_VALUE);

        HBox driverRow = new HBox(8, new Label("Driver:"), exportDriverCombo);
        driverRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(exportDriverCombo, Priority.ALWAYS);

        Label exportDesc = new Label(
                "Exports Enabled associations (state 1) for the selected driver to a JSON file.");
        exportDesc.setWrapText(true);
        exportDesc.setStyle("-fx-text-fill: -color-fg-muted;");
        exportBtn.setOnAction(e -> exportAssociations());

        Label importDesc = new Label(
                "Imports associations from a previously exported file. "
                + "The driver DN recorded in the file is ignored; the selected driver above is used instead.");
        importDesc.setWrapText(true);
        importDesc.setStyle("-fx-text-fill: -color-fg-muted;");
        importBtn.setOnAction(e -> importAssociations());

        TitledPane exportBox = new TitledPane("Export",
                new VBox(8, exportDesc, includeNonEnabledCb, exportBtn));
        exportBox.setCollapsible(false);
        TitledPane importBox = new TitledPane("Import", new VBox(8, importDesc, importBtn));
        importBox.setCollapsible(false);

        exportProgress.setMaxWidth(Double.MAX_VALUE);
        exportProgress.setVisible(false);

        exportLog.setEditable(false);
        exportLog.setFont(Font.font("monospaced", 11));
        exportLog.setWrapText(true);
        exportLog.setPrefRowCount(10);

        VBox pane = new VBox(10, driverRow, exportBox, importBox,
                exportProgress, new Label("Results:"), exportLog);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(exportLog, Priority.ALWAYS);
        return pane;
    }

    private void exportAssociations() {
        String driverDn = selectedExportDriver();
        if (driverDn == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Export DirXML Associations");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON files", "*.json"));
        fc.setInitialFileName(suggestedExportFilename(driverDn));
        File out = fc.showSaveDialog(stage);
        if (out == null) return;

        exportLog.clear();
        exportProgress.setVisible(true);
        exportProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setActionsDisabled(true);

        boolean includeNonEnabled = includeNonEnabledCb.isSelected();

        Thread.startVirtualThread(() -> {
            try {
                List<ExportEntry> entries = new ArrayList<>();
                fetchAssociationsForDriver(driverDn, includeNonEnabled, entries);

                ExportFile file = new ExportFile(1, Instant.now().toString(), driverDn, entries);
                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(out, file);

                int total = entries.size();
                Platform.runLater(() -> {
                    appendExportLog("Exported " + total + " association"
                            + (total == 1 ? "" : "s") + " to " + out.getAbsolutePath());
                    exportProgress.setProgress(1);
                    setActionsDisabled(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendExportLog("Export failed: " + ex.getMessage());
                    exportProgress.setVisible(false);
                    setActionsDisabled(false);
                });
            }
        });
    }

    /**
     * Searches all naming contexts for entries whose DirXML-Associations attribute
     * names the given driver, and appends an {@link ExportEntry} for each match to
     * {@code out}. When {@code includeNonEnabled} is false (the default), only state
     * 1 (Enabled) is exported.
     *
     * <p>eDirectory's substring matching on DirXML-Associations requires enough prefix
     * to reach an indexable position, so each sub-filter includes the state digit.
     */
    private void fetchAssociationsForDriver(
            String driverDn, boolean includeNonEnabled, List<ExportEntry> out) throws Exception {
        String escapedDn = escapeFilterValue(driverDn);
        int[] states = includeNonEnabled ? new int[]{0, 1, 2, 3} : new int[]{1};

        StringBuilder fb = new StringBuilder();
        if (states.length > 1) fb.append("(|");
        for (int s : states) {
            fb.append("(DirXML-Associations=").append(escapedDn).append('#').append(s).append("#*)");
        }
        if (states.length > 1) fb.append(')');
        String searchFilter = fb.toString();

        List<String> contexts = controller.getLdapService().fetchNamingContexts();
        for (String base : contexts) {
            if (base == null || base.isBlank()) continue;
            List<LDAPEntry> results;
            try {
                results = controller.getLdapService().search(
                        base, SearchScope.SUBTREE, searchFilter, "DirXML-Associations");
            } catch (Exception ex) {
                appendExportLog("Search failed under " + base + ": " + ex.getMessage());
                continue;
            }
            appendExportLog("Scanned " + base + " (" + results.size() + " match"
                    + (results.size() == 1 ? "" : "es") + ")");
            for (LDAPEntry entry : results) {
                List<String> values = findAttribute(entry.getAttributes(), "DirXML-Associations");
                for (String raw : values) {
                    DirXMLAssociation assoc = DirXMLAssociation.parse(raw);
                    if (assoc == null) continue;
                    if (!assoc.driverDN().equalsIgnoreCase(driverDn)) continue;
                    if (!includeNonEnabled && assoc.state() != 1) continue;
                    out.add(new ExportEntry(entry.getDn(), assoc.state(), assoc.value()));
                }
            }
        }
    }

    /** Escapes the characters that are special inside an LDAP filter value (RFC 4515). */
    private static String escapeFilterValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*'  -> sb.append("\\2a");
                case '('  -> sb.append("\\28");
                case ')'  -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private void importAssociations() {
        String targetDriver = selectedExportDriver();
        if (targetDriver == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Import DirXML Associations");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON files", "*.json"));
        File in = fc.showOpenDialog(stage);
        if (in == null) return;

        ExportFile file;
        try {
            file = new ObjectMapper().readValue(in, ExportFile.class);
        } catch (Exception ex) {
            showError("Unable to read file", ex.getMessage());
            return;
        }
        if (file.associations() == null || file.associations().isEmpty()) {
            showError("Empty file", "No associations found in the file.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Import " + file.associations().size() + " association(s)?\n\n"
                        + "Source driver (ignored): " + file.sourceDriverDN() + "\n"
                        + "Target driver: " + targetDriver,
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Import");
        confirm.setHeaderText("Import DirXML Associations");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.YES) return;

        exportLog.clear();
        exportProgress.setVisible(true);
        exportProgress.setProgress(0);
        setActionsDisabled(true);

        Thread.startVirtualThread(() -> {
            int total = file.associations().size();
            int added = 0, existed = 0, failed = 0;
            LDAPService svc = controller.getLdapService();

            for (int i = 0; i < total; i++) {
                ExportEntry e = file.associations().get(i);
                DirXMLAssociation assoc = new DirXMLAssociation(targetDriver, e.state(), e.value());
                try {
                    svc.modifyAttribute(e.dn(), "DirXML-Associations",
                            LDAPService.LDAPModifyOperation.ADD, List.of(assoc.serialize()));
                    added++;
                    appendExportLog("Added to " + e.dn());
                } catch (LDAPException ex) {
                    if (ex.getResultCode().intValue() == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS_INT_VALUE) {
                        existed++;
                        appendExportLog("Already present: " + e.dn());
                    } else if (ex.getResultCode().intValue() == ResultCode.NO_SUCH_OBJECT_INT_VALUE) {
                        failed++;
                        appendExportLog("Object not found: " + e.dn());
                    } else {
                        failed++;
                        appendExportLog("FAILED [" + e.dn() + "]: " + ex.getMessage());
                    }
                } catch (Exception ex) {
                    failed++;
                    appendExportLog("FAILED [" + e.dn() + "]: " + ex.getMessage());
                }

                final double p = (double) (i + 1) / total;
                Platform.runLater(() -> exportProgress.setProgress(p));
            }

            int a = added, x = existed, f = failed;
            Platform.runLater(() -> {
                appendExportLog("\n--- Complete: " + a + " added, " + x
                        + " already present, " + f + " failed ---");
                setActionsDisabled(false);
            });
        });
    }

    private String selectedExportDriver() {
        String text = exportDriverCombo.getEditor().getText();
        if (text == null || text.isBlank()) {
            Object v = exportDriverCombo.getValue();
            text = v == null ? null : v.toString();
        }
        if (text == null || text.isBlank()) {
            showError("No Driver", "Select a driver DN first.");
            return null;
        }
        return text.trim();
    }

    private String suggestedExportFilename(String driverDn) {
        String cn = driverDn;
        if (driverDn.toLowerCase().startsWith("cn=")) {
            int comma = driverDn.indexOf(',');
            cn = comma > 0 ? driverDn.substring(3, comma) : driverDn.substring(3);
        }
        return cn.replaceAll("[^A-Za-z0-9._-]", "_") + "-associations.json";
    }

    private void setActionsDisabled(boolean disabled) {
        exportBtn.setDisable(disabled);
        importBtn.setDisable(disabled);
    }

    private void appendExportLog(String line) {
        Platform.runLater(() -> exportLog.appendText(line + "\n"));
    }

    private VBox buildBulkPane() {
        // Search section
        bulkBaseDn.setPromptText("Base DN (e.g., ou=users,o=org)");
        bulkBaseDn.setFont(Font.font("monospaced", 12));

        bulkScopeCombo.getItems().addAll("One Level", "Subtree");
        bulkScopeCombo.getSelectionModel().select("Subtree");

        bulkSearchFilter.setPromptText("LDAP filter (e.g., (objectClass=User))");
        bulkSearchFilter.setFont(Font.font("monospaced", 12));

        Button searchBtn = new Button("Search");
        searchBtn.setOnAction(e -> bulkSearch());

        HBox searchRow1 = new HBox(8, new Label("Base DN:"), bulkBaseDn, new Label("Scope:"), bulkScopeCombo);
        searchRow1.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(bulkBaseDn, Priority.ALWAYS);

        HBox searchRow2 = new HBox(8, new Label("Filter:"), bulkSearchFilter, searchBtn);
        searchRow2.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(bulkSearchFilter, Priority.ALWAYS);

        // Object list
        bulkObjectList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        bulkObjectList.setPlaceholder(new Label("Search for objects above."));

        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setOnAction(e -> bulkObjectList.getSelectionModel().selectAll());
        Button selectNoneBtn = new Button("Select None");
        selectNoneBtn.setOnAction(e -> bulkObjectList.getSelectionModel().clearSelection());
        Label countLabel = new Label();
        bulkObjectList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<String>) c ->
                        countLabel.setText(bulkObjectList.getSelectionModel().getSelectedItems().size()
                                + " of " + bulkObjectList.getItems().size() + " selected"));

        HBox selectRow = new HBox(8, selectAllBtn, selectNoneBtn, countLabel);
        selectRow.setAlignment(Pos.CENTER_LEFT);

        // Operation
        bulkOperationCombo.getItems().addAll("Remove", "Disable", "Enable", "Migrate");
        bulkOperationCombo.getSelectionModel().selectFirst();

        bulkDriverCombo.setPromptText("Select driver to affect...");
        bulkDriverCombo.setMaxWidth(Double.MAX_VALUE);

        HBox opRow = new HBox(8,
                new Label("Operation:"), bulkOperationCombo,
                new Label("Driver:"), bulkDriverCombo);
        opRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(bulkDriverCombo, Priority.ALWAYS);

        bulkExecuteBtn.setOnAction(e -> executeBulk());

        bulkProgress.setMaxWidth(Double.MAX_VALUE);
        bulkProgress.setVisible(false);

        bulkLog.setEditable(false);
        bulkLog.setFont(Font.font("monospaced", 11));
        bulkLog.setWrapText(true);
        bulkLog.setPrefRowCount(8);

        VBox pane = new VBox(8,
                searchRow1, searchRow2,
                bulkObjectList, selectRow,
                new Separator(),
                opRow,
                bulkExecuteBtn, bulkProgress,
                new Label("Results:"), bulkLog);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(bulkObjectList, Priority.ALWAYS);
        VBox.setVgrow(bulkLog, Priority.SOMETIMES);
        return pane;
    }

    private void bulkSearch() {
        String baseDn = bulkBaseDn.getText().trim();
        String filter = bulkSearchFilter.getText().trim();
        if (baseDn.isEmpty() || filter.isEmpty()) {
            showError("Missing Fields", "Enter both Base DN and filter.");
            return;
        }

        SearchScope scope = "One Level".equals(bulkScopeCombo.getValue())
                ? SearchScope.ONE_LEVEL : SearchScope.SUBTREE;

        bulkObjectList.getItems().clear();
        bulkObjectList.setPlaceholder(new Label("Searching..."));

        Thread.startVirtualThread(() -> {
            try {
                List<LDAPEntry> results = controller.getLdapService()
                        .search(baseDn, scope, filter, "dn");

                Platform.runLater(() -> {
                    for (LDAPEntry entry : results) {
                        bulkObjectList.getItems().add(entry.getDn());
                    }
                    if (results.isEmpty()) {
                        bulkObjectList.setPlaceholder(new Label("No results found."));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    bulkObjectList.setPlaceholder(new Label("Error: " + ex.getMessage()));
                });
            }
        });
    }

    private void executeBulk() {
        List<String> selectedDns = new ArrayList<>(
                bulkObjectList.getSelectionModel().getSelectedItems());
        if (selectedDns.isEmpty()) {
            showError("No Selection", "Select one or more objects.");
            return;
        }

        String operation = bulkOperationCombo.getValue();
        String driverFilter = bulkDriverCombo.getValue();
        if (driverFilter == null || driverFilter.isEmpty()) {
            showError("No Driver", "Select a driver to filter by.");
            return;
        }

        if (!confirmBulkOperation(operation, driverFilter, selectedDns.size())) return;

        bulkExecuteBtn.setDisable(true);
        bulkProgress.setVisible(true);
        bulkProgress.setProgress(0);
        bulkLog.clear();

            Thread.startVirtualThread(() -> {
                int total = selectedDns.size();
                int success = 0;
                int failed = 0;
                int skipped = 0;

                for (int i = 0; i < total; i++) {
                    String dn = selectedDns.get(i);
                    final int idx = i;

                    try {
                        Map<String, List<String>> attrs = controller.getLdapService()
                                .fetchAttributes(dn, false);
                        List<String> assocValues = findAttribute(attrs, "DirXML-Associations");

                        boolean found = false;
                        for (String raw : assocValues) {
                            DirXMLAssociation assoc = DirXMLAssociation.parse(raw);
                            if (assoc == null) continue;
                            if (!assoc.driverDN().equalsIgnoreCase(driverFilter)) continue;

                            found = true;
                            LDAPService svc = controller.getLdapService();

                            switch (operation) {
                                case "Remove" -> {
                                    svc.modifyAttribute(dn, "DirXML-Associations",
                                            LDAPService.LDAPModifyOperation.DELETE, List.of(raw));
                                    appendLog("Removed: " + dn);
                                }
                                case "Disable" -> {
                                    if (assoc.state() == 0) {
                                        appendLog("Already disabled: " + dn);
                                    } else {
                                        DirXMLAssociation updated = new DirXMLAssociation(
                                                assoc.driverDN(), 0, assoc.value());
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.DELETE, List.of(raw));
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.ADD,
                                                List.of(updated.serialize()));
                                        appendLog("Disabled: " + dn);
                                    }
                                }
                                case "Enable" -> {
                                    if (assoc.state() == 1) {
                                        appendLog("Already enabled: " + dn);
                                    } else {
                                        DirXMLAssociation updated = new DirXMLAssociation(
                                                assoc.driverDN(), 1, assoc.value());
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.DELETE, List.of(raw));
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.ADD,
                                                List.of(updated.serialize()));
                                        appendLog("Enabled: " + dn);
                                    }
                                }
                                case "Migrate" -> {
                                    if (assoc.state() == 2) {
                                        appendLog("Already migrate: " + dn);
                                    } else {
                                        DirXMLAssociation updated = new DirXMLAssociation(
                                                assoc.driverDN(), 2, assoc.value());
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.DELETE, List.of(raw));
                                        svc.modifyAttribute(dn, "DirXML-Associations",
                                                LDAPService.LDAPModifyOperation.ADD,
                                                List.of(updated.serialize()));
                                        appendLog("Migrate set: " + dn);
                                    }
                                }
                            }
                        }

                        if (found) {
                            success++;
                        } else {
                            skipped++;
                            appendLog("No matching association: " + dn);
                        }
                    } catch (Exception ex) {
                        failed++;
                        appendLog("FAILED [" + dn + "]: " + ex.getMessage());
                    }

                    final double progress = (double)(idx + 1) / total;
                    Platform.runLater(() -> bulkProgress.setProgress(progress));
                }

                final int s = success, f = failed, sk = skipped;
                Platform.runLater(() -> {
                    appendLog("\n--- Complete: " + s + " succeeded, " + f + " failed, "
                            + sk + " skipped ---");
                    bulkExecuteBtn.setDisable(false);
                    bulkProgress.setProgress(1);
                });
            });
    }

    /**
     * Warns the user that a bulk operation could be destructive and recommends
     * exporting first. Returns {@code true} only when the user picks "Continue".
     */
    private boolean confirmBulkOperation(String operation, String driverDn, int objectCount) {
        ButtonType continueBtn = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert confirm = new Alert(Alert.AlertType.WARNING,
                operation + " associations for driver:\n" + driverDn
                        + "\non " + objectCount + " object(s).\n\n"
                        + "This operation could be destructive and cannot be undone automatically. "
                        + "We recommend exporting the associations for this driver first "
                        + "(see the Export / Import tab).",
                cancelBtn, continueBtn);
        confirm.setTitle("Confirm Bulk Operation");
        confirm.setHeaderText("Are you sure?");
        confirm.getDialogPane().setMinWidth(640);

        // Default the keyboard focus to Cancel.
        Button defaultCancel = (Button) confirm.getDialogPane().lookupButton(cancelBtn);
        if (defaultCancel != null) defaultCancel.setDefaultButton(true);

        return confirm.showAndWait().orElse(cancelBtn) == continueBtn;
    }

    private void appendLog(String message) {
        Platform.runLater(() -> {
            bulkLog.appendText(message + "\n");
        });
    }

    // ---- Driver Discovery ----

    private void loadDrivers() {
        Thread.startVirtualThread(() -> {
            try {
                List<String> contexts = controller.getLdapService().fetchNamingContexts();
                for (String baseDN : contexts) {
                    try {
                        List<LDAPEntry> drivers = controller.getLdapService().search(
                                baseDN, SearchScope.SUBTREE,
                                "(objectClass=DirXML-Driver)", "dn");

                        Platform.runLater(() -> {
                            for (LDAPEntry entry : drivers) {
                                String dn = entry.getDn();
                                if (!discoveredDrivers.contains(dn)) {
                                    discoveredDrivers.add(dn);
                                }
                            }
                            bulkDriverCombo.setItems(discoveredDrivers);
                        });
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    // ---- Data Models ----

    /**
     * Parsed DirXML-Association value: driverDN#state#associationValue
     */
    public record DirXMLAssociation(String driverDN, int state, String value) {

        public static DirXMLAssociation parse(String raw) {
            if (raw == null || raw.isEmpty()) return null;

            // Format: driverDN#state#value
            // The driver DN may contain '#' in attribute values, so we parse carefully.
            // State is a single digit. Value may be empty.
            // Strategy: find last two '#' delimiters — state is always a single digit
            int lastHash = raw.lastIndexOf('#');
            if (lastHash <= 0) return null;

            String val = raw.substring(lastHash + 1);

            String remainder = raw.substring(0, lastHash);
            int secondHash = remainder.lastIndexOf('#');
            if (secondHash < 0) return null;

            String stateStr = remainder.substring(secondHash + 1);
            String driverDn = remainder.substring(0, secondHash);

            try {
                int st = Integer.parseInt(stateStr);
                return new DirXMLAssociation(driverDn, st, val);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public String serialize() {
            return driverDN + "#" + state + "#" + value;
        }

        public String stateLabel() {
            return switch (state) {
                case 0 -> "Disabled";
                case 1 -> "Enabled";
                case 2 -> "Migrate";
                case 3 -> "Pending";
                default -> "Unknown (" + state + ")";
            };
        }

        public String driverName() {
            // Extract CN from driver DN
            if (driverDN.toLowerCase().startsWith("cn=")) {
                int comma = driverDN.indexOf(',');
                return comma > 0 ? driverDN.substring(3, comma) : driverDN.substring(3);
            }
            return driverDN;
        }
    }

    /**
     * Row in the association table.
     */
    public record AssociationRow(String objectDn, DirXMLAssociation association, String rawValue) {
        public String driverName() { return association.driverName(); }
        public String stateLabel() { return association.stateLabel(); }
        public String value() { return association.value(); }
        public int state() { return association.state(); }
    }

    /**
     * Serialized form of an exported associations file.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportFile(
            int version,
            String exportedAt,
            String sourceDriverDN,
            List<ExportEntry> associations) {

        public ExportFile() { this(1, null, null, List.of()); }
    }

    /**
     * One association entry in the export file — the driver DN is intentionally
     * omitted because it is supplied by the user at import time.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportEntry(String dn, int state, String value) {
        public ExportEntry() { this(null, 1, null); }
    }
}
