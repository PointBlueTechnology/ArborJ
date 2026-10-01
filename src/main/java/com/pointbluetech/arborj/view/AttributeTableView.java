package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.controller.MainController.AttributeRow;
import com.pointbluetech.arborj.model.LDAPAttributeSyntax;
import com.pointbluetech.arborj.service.AttributeSuggestSettings;
import com.pointbluetech.arborj.service.FontSettings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Attribute name/value table with syntax-aware rendering and context menus.
 */
public class AttributeTableView {

    private static final Set<String> NON_EDITABLE_ATTRS = Set.of(
            "objectclass", "entryuuid", "creatorsname", "createtimestamp",
            "modifiersname", "modifytimestamp", "structuralobjectclass",
            "governingstructurerule", "pwdlastset", "lastlogon", "lastlogontimestamp",
            "accountexpires", "badpasswordtime", "lockouttime", "objectguid", "objectsid",
            "entrydn", "subschemasubentry", "hassubordinates", "numsubordinates"
    );

    private static final FontSettings fontSettings = FontSettings.getInstance();

    private final MainController controller;
    private final VBox root;
    private final FilteredList<AttributeRow> filteredAttributes;
    /** Per-instance expansion state — keyed by attribute name + value identity.
     *  Cleared when the selected DN changes. Per-instance so multiple windows
     *  don't share UI state. */
    private final java.util.Set<String> expandedRows =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public AttributeTableView(MainController controller) {
        this.controller = controller;
        this.filteredAttributes = new FilteredList<>(controller.getSelectedAttributes(), p -> true);
        this.root = build();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox build() {
        VBox container = new VBox();

        // DN header
        HBox dnHeader = buildDNHeader();

        // Filter bar
        HBox filterBar = buildFilterBar();

        // Table
        TableView<AttributeRow> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        // Loading indicator
        ProgressIndicator loadingSpinner = new ProgressIndicator();
        loadingSpinner.setPrefSize(32, 32);
        loadingSpinner.visibleProperty().bind(controller.loadingAttributesProperty());
        loadingSpinner.managedProperty().bind(loadingSpinner.visibleProperty());

        StackPane content = new StackPane(table, loadingSpinner);
        StackPane.setAlignment(loadingSpinner, Pos.CENTER);
        VBox.setVgrow(content, Priority.ALWAYS);

        container.getChildren().addAll(dnHeader, filterBar, content);
        return container;
    }

    private HBox buildDNHeader() {
        HBox header = new HBox(8);
        header.setPadding(new Insets(6, 8, 6, 8));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dn-header");

        Label dnLabel = new Label();
        dnLabel.fontProperty().bind(fontSettings.detailFontProperty());
        controller.selectedDNProperty().addListener((obs, oldVal, newVal) ->
                dnLabel.setText(newVal != null ? newVal : ""));
        HBox.setHgrow(dnLabel, Priority.ALWAYS);

        Button copyBtn = new Button("Copy DN");
        copyBtn.setOnAction(e -> {
            String dn = controller.selectedDNProperty().get();
            if (dn != null) {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(dn);
                Clipboard.getSystemClipboard().setContent(cc);
            }
        });

        header.getChildren().addAll(dnLabel, copyBtn);
        header.visibleProperty().bind(controller.selectedDNProperty().isNotNull());
        header.managedProperty().bind(header.visibleProperty());
        return header;
    }

    private HBox buildFilterBar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: -color-bg-default;");

        TextField filterField = new TextField();
        filterField.setPromptText("Filter attributes...");
        filterField.setPrefWidth(200);
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lower = newVal != null ? newVal.toLowerCase() : "";
            filteredAttributes.setPredicate(row ->
                    lower.isEmpty() ||
                            row.name().toLowerCase().contains(lower) ||
                            row.value().toLowerCase().contains(lower));
        });

        CheckBox opCheckbox = new CheckBox("Operational");
        opCheckbox.selectedProperty().bindBidirectional(controller.showOperationalAttributesProperty());
        opCheckbox.setOnAction(e -> {
            String dn = controller.selectedDNProperty().get();
            if (dn != null) controller.loadAttributes(dn);
        });

        Button addAttrBtn = new Button("+");
        addAttrBtn.setTooltip(new Tooltip("Add Attribute"));
        addAttrBtn.disableProperty().bind(controller.readOnlyProperty()
                .or(controller.selectedDNProperty().isNull()));
        addAttrBtn.setOnAction(e -> {
            String dn = controller.selectedDNProperty().get();
            if (dn == null) return;
            promptForAttributeName(dn).ifPresent(attrName -> {
                if (attrName.isBlank()) return;
                showAttributeEditor("Add Attribute Value", "Value for " + attrName, "", attrName,
                        value -> {
                            if (!value.isEmpty()) {
                                controller.saveAttributeValue(dn, attrName, null, value);
                            }
                        });
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(filterField, spacer, opCheckbox, addAttrBtn);
        bar.visibleProperty().bind(controller.connectedProperty());
        bar.managedProperty().bind(bar.visibleProperty());
        return bar;
    }

    @SuppressWarnings("unchecked")
    private TableView<AttributeRow> buildTable() {
        TableView<AttributeRow> table = new TableView<>(filteredAttributes);

        // Bind table font style to FontSettings — updates live
        // Force table to re-render cells when font settings change
        fontSettings.detailFontProperty().addListener((obs, o, n) -> table.refresh());

        // Clear expanded state when a new entry is loaded
        controller.selectedDNProperty().addListener((obs, o, n) -> expandedRows.clear());

        // Store controller ref for toggle rows
        table.setUserData(controller);

        table.setPlaceholder(new Label("Select an entry to view attributes"));

        // Attribute name column
        TableColumn<AttributeRow, String> nameCol = new TableColumn<>("Attribute");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().isToggleRow() ? "" : cd.getValue().name()));
        nameCol.setPrefWidth(240);
        nameCol.setMinWidth(120);
        nameCol.setMaxWidth(500);
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                setFont(fontSettings.getDetailBoldFont());

                // Lock icon for non-editable
                if (NON_EDITABLE_ATTRS.contains(item.toLowerCase())) {
                    Label lock = new Label("\uD83D\uDD12");
                    lock.setStyle("-fx-font-size: 10;");
                    setGraphic(lock);
                } else {
                    setGraphic(null);
                }
            }
        });

        // Value column
        TableColumn<AttributeRow, AttributeRow> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue()));
        valueCol.setCellFactory(col -> new SyntaxAwareCell());

        table.getColumns().addAll(nameCol, valueCol);

        // Make value column fill remaining space
        valueCol.prefWidthProperty().bind(table.widthProperty().subtract(nameCol.widthProperty()).subtract(20));

        // Row factory for context menus
        table.setRowFactory(tv -> {
            TableRow<AttributeRow> row = new TableRow<>();
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    ContextMenu menu = buildRowContextMenu(row.getItem());
                    menu.show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });

        return table;
    }

    private ContextMenu buildRowContextMenu(AttributeRow row) {
        ContextMenu menu = new ContextMenu();

        MenuItem copyValue = new MenuItem("Copy Value");
        copyValue.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(row.value());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        menu.getItems().add(copyValue);

        // ACL edit shortcut for eDirectory
        if (row.name().equalsIgnoreCase("ACL")
                && controller.directoryTypeProperty().get() == com.pointbluetech.arborj.model.DirectoryType.EDIRECTORY) {
            MenuItem editACLs = new MenuItem("Edit ACLs...");
            editACLs.setOnAction(e -> {
                String dn = controller.selectedDNProperty().get();
                if (dn != null) {
                    new com.pointbluetech.arborj.view.edir.ACLEditorView(controller, dn).showAndWait();
                }
            });
            menu.getItems().addAll(new SeparatorMenuItem(), editACLs);
        }

        boolean editable = !NON_EDITABLE_ATTRS.contains(row.name().toLowerCase())
                && !controller.readOnlyProperty().get();

        if (editable) {
            menu.getItems().add(new SeparatorMenuItem());

            if (row.syntax() == LDAPAttributeSyntax.BOOLEAN) {
                MenuItem setTrue = new MenuItem("Set TRUE");
                setTrue.setOnAction(e -> controller.saveAttributeValue(
                        controller.selectedDNProperty().get(), row.name(), row.value(), "TRUE"));
                MenuItem setFalse = new MenuItem("Set FALSE");
                setFalse.setOnAction(e -> controller.saveAttributeValue(
                        controller.selectedDNProperty().get(), row.name(), row.value(), "FALSE"));
                menu.getItems().addAll(setTrue, setFalse);
            } else if (row.syntax() == LDAPAttributeSyntax.BINARY) {
                MenuItem hexEdit = new MenuItem("View/Edit Hex...");
                hexEdit.setOnAction(e -> showHexEditor(row));
                menu.getItems().add(hexEdit);
            } else {
                MenuItem editValue = new MenuItem("Edit Value...");
                editValue.setOnAction(e -> showEditDialog(row));
                menu.getItems().add(editValue);
            }

            MenuItem addValue = new MenuItem("Add Value...");
            addValue.setOnAction(e -> showAddValueDialog(row.name()));
            menu.getItems().add(addValue);

            menu.getItems().add(new SeparatorMenuItem());

            MenuItem deleteValue = new MenuItem("Delete Value");
            deleteValue.setStyle("-fx-text-fill: #cc3333;");
            deleteValue.setOnAction(e -> controller.deleteAttributeValue(
                    controller.selectedDNProperty().get(), row.name(), row.value()));
            menu.getItems().add(deleteValue);
        }

        return menu;
    }

    private void showEditDialog(AttributeRow row) {
        showAttributeEditor("Edit Attribute Value", "Edit " + row.name(), row.value(), row.name(),
                newValue -> {
                    if (!newValue.equals(row.value())) {
                        controller.saveAttributeValue(
                                controller.selectedDNProperty().get(), row.name(), row.value(), newValue);
                    }
                });
    }

    private void showAddValueDialog(String attributeName) {
        showAttributeEditor("Add Attribute Value", "Add value for " + attributeName, "", attributeName,
                value -> {
                    if (!value.isEmpty()) {
                        controller.saveAttributeValue(
                                controller.selectedDNProperty().get(), attributeName, null, value);
                    }
                });
    }

    private void showHexEditor(AttributeRow row) {
        boolean isReadOnly = NON_EDITABLE_ATTRS.contains(row.name().toLowerCase())
                || controller.readOnlyProperty().get();
        HexEditorDialog dialog = new HexEditorDialog(
                row.name(), row.value(), isReadOnly,
                newData -> {
                    String dn = controller.selectedDNProperty().get();
                    if (dn != null) {
                        try {
                            controller.getLdapService().modifyBinaryAttribute(dn, row.name(),
                                    com.pointbluetech.arborj.service.LDAPService.LDAPModifyOperation.REPLACE,
                                    java.util.List.of(newData));
                            controller.loadAttributes(dn);
                        } catch (Exception ex) {
                            new Alert(Alert.AlertType.ERROR,
                                    "Failed to save: " + ex.getMessage()).showAndWait();
                        }
                    }
                },
                ownerWindow());
        dialog.showAndWait();
    }

    private javafx.stage.Window ownerWindow() {
        return root.getScene() != null ? root.getScene().getWindow() : null;
    }

    /**
     * Attribute value editor with single-line/multi-line toggle.
     * Defaults to multi-line if the value contains newlines. When
     * {@code attributeName} is a DN-syntax attribute, offers a Browse
     * button that opens the DN picker.
     */
    private void showAttributeEditor(String title, String header, String initialValue,
                                      String attributeName,
                                      java.util.function.Consumer<String> onSave) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        javafx.stage.Window owner = ownerWindow();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(title);
        stage.setResizable(true);

        boolean hasNewlines = initialValue.contains("\n");

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(8);
        root.setPadding(new javafx.geometry.Insets(12));

        // Header
        Label headerLabel = new Label(header);
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        // Single-line field
        TextField singleLine = new TextField(hasNewlines ? "" : initialValue);
        singleLine.setFont(fontSettings.getDetailFont());
        singleLine.setPrefWidth(450);

        // Multi-line area
        javafx.scene.control.TextArea multiLine = new javafx.scene.control.TextArea(initialValue);
        multiLine.setFont(fontSettings.getDetailFont());
        multiLine.setPrefWidth(450);
        multiLine.setPrefHeight(200);
        multiLine.setWrapText(true);

        // Toggle
        javafx.scene.control.ToggleGroup toggleGroup = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioButton singleBtn = new javafx.scene.control.RadioButton("Single line");
        javafx.scene.control.RadioButton multiBtn = new javafx.scene.control.RadioButton("Multi-line");
        singleBtn.setToggleGroup(toggleGroup);
        multiBtn.setToggleGroup(toggleGroup);

        javafx.scene.layout.HBox toggleRow = new javafx.scene.layout.HBox(12, singleBtn, multiBtn);

        // Container that swaps between single and multi
        javafx.scene.layout.StackPane editorPane = new javafx.scene.layout.StackPane();
        javafx.scene.layout.VBox.setVgrow(editorPane, javafx.scene.layout.Priority.ALWAYS);

        Runnable showSingle = () -> {
            editorPane.getChildren().setAll(singleLine);
            if (!multiLine.getText().isEmpty()) {
                singleLine.setText(multiLine.getText().replace("\n", " "));
            }
            singleLine.requestFocus();
            stage.setHeight(180);
        };
        Runnable showMulti = () -> {
            editorPane.getChildren().setAll(multiLine);
            if (!singleLine.getText().isEmpty() && multiLine.getText().isEmpty()) {
                multiLine.setText(singleLine.getText());
            }
            multiLine.requestFocus();
            stage.setHeight(350);
        };

        singleBtn.setOnAction(e -> showSingle.run());
        multiBtn.setOnAction(e -> showMulti.run());

        // Default to multi-line if value has newlines
        if (hasNewlines) {
            multiBtn.setSelected(true);
            showMulti.run();
        } else {
            singleBtn.setSelected(true);
            showSingle.run();
        }

        // Buttons
        Button saveBtn = new Button("Save");
        saveBtn.setDefaultButton(true);
        saveBtn.setOnAction(e -> {
            String value = multiBtn.isSelected() ? multiLine.getText() : singleLine.getText();
            onSave.accept(value);
            stage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> stage.close());

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, cancelBtn, saveBtn);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        // DN picker (only for DN-syntax attributes).
        boolean dnSyntax = EntryContextMenu.isDnSyntax(controller, attributeName);
        if (dnSyntax) {
            Button browseBtn = new Button("Browse…");
            browseBtn.setOnAction(e -> {
                String current = multiBtn.isSelected() ? multiLine.getText() : singleLine.getText();
                DNPickerDialog picker = new DNPickerDialog(controller, current, stage);
                picker.showAndWait();
                String picked = picker.getSelectedDN();
                if (picked != null && !picked.isEmpty()) {
                    if (multiBtn.isSelected()) multiLine.setText(picked);
                    else singleLine.setText(picked);
                }
            });
            buttons.getChildren().addFirst(browseBtn);
        }

        root.getChildren().addAll(headerLabel, toggleRow, editorPane, buttons);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        stage.setScene(scene);
        stage.setWidth(500);
        stage.showAndWait();
    }

    // --- Syntax-Aware Cell Renderer ---

    private static String expandKey(AttributeRow row) {
        return row.name() + "\0" + row.value();
    }

    private class SyntaxAwareCell extends TableCell<AttributeRow, AttributeRow> {

        private static final String STYLE_DN = "-fx-text-fill: -color-accent-fg;";
        private static final String STYLE_MUTED = "-fx-text-fill: -color-fg-muted;";
        private static final String STYLE_DEFAULT = "";
        private static final int TRUNCATE_LENGTH = 300;
        private static final int TRUNCATE_LINES = 4;

        private boolean isLongValue(String value) {
            if (value.length() > TRUNCATE_LENGTH) return true;
            long lines = value.chars().filter(c -> c == '\n').count();
            return lines >= TRUNCATE_LINES;
        }

        private String truncate(String value) {
            // Truncate by lines first
            String[] lines = value.split("\n", TRUNCATE_LINES + 1);
            if (lines.length > TRUNCATE_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < TRUNCATE_LINES; i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
                return sb + "...";
            }
            // Truncate by length
            if (value.length() > TRUNCATE_LENGTH) {
                return value.substring(0, TRUNCATE_LENGTH) + "...";
            }
            return value;
        }

        private javafx.scene.Node buildUACView(AttributeRow item) {
            int uac;
            try { uac = Integer.parseInt(item.value()); }
            catch (NumberFormatException e) { return new Label(item.value()); }

            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(1);
            box.setPadding(new javafx.geometry.Insets(2));

            // Raw numeric value
            Label rawLabel = new Label(uac + "  (0x" + Integer.toHexString(uac).toUpperCase() + ")");
            rawLabel.setFont(fontSettings.getDetailFont());
            rawLabel.setStyle("-fx-text-fill: -color-fg-muted;");
            box.getChildren().add(rawLabel);

            // Determine if editable
            boolean editable = getTableView() != null
                    && getTableView().getUserData() instanceof MainController ctrl
                    && !ctrl.readOnlyProperty().get()
                    && !NON_EDITABLE_ATTRS.contains(item.name().toLowerCase());

            // Show set flags by default, all on expand
            String expandKey = "uac_expand_" + item.name();
            boolean showAll = expandedRows.contains(expandKey);

            var flags = showAll
                    ? com.pointbluetech.arborj.util.ADHelpers.ALL_FLAGS
                    : com.pointbluetech.arborj.util.ADHelpers.getSetFlags(uac);

            for (var flag : flags) {
                boolean isSet = (uac & flag.bit()) != 0;
                boolean isReadOnly = com.pointbluetech.arborj.util.ADHelpers.READ_ONLY_BITS.contains(flag.bit());

                CheckBox cb = new CheckBox();
                cb.setSelected(isSet);
                cb.setDisable(!editable || isReadOnly);
                cb.setStyle("-fx-font-size: 11;");

                if (editable && !isReadOnly) {
                    cb.setOnAction(ev -> {
                        if (getTableView() != null
                                && getTableView().getUserData() instanceof MainController ctrl) {
                            int newUAC = uac ^ flag.bit();
                            ctrl.saveAttributeValue(
                                    ctrl.selectedDNProperty().get(),
                                    item.name(), item.value(), String.valueOf(newUAC));
                        }
                    });
                }

                Label nameLabel = new Label(flag.name());
                nameLabel.setFont(javafx.scene.text.Font.font("monospaced", 11));
                nameLabel.setStyle(isReadOnly ? "-fx-text-fill: -color-fg-muted;" : "");

                Label descLabel = new Label(" \u2014 " + flag.description());
                descLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 10;");

                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(4, cb, nameLabel, descLabel);
                if (isReadOnly) {
                    Label lock = new Label("\uD83D\uDD12");
                    lock.setStyle("-fx-font-size: 8;");
                    row.getChildren().add(lock);
                }
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                box.getChildren().add(row);
            }

            // Show more/less toggle
            int setCount = com.pointbluetech.arborj.util.ADHelpers.getSetFlags(uac).size();
            int hiddenCount = com.pointbluetech.arborj.util.ADHelpers.ALL_FLAGS.size() - setCount;
            if (hiddenCount > 0) {
                javafx.scene.control.Hyperlink toggle = new javafx.scene.control.Hyperlink(
                        showAll ? "Show set flags only"
                                : hiddenCount + " more flag" + (hiddenCount != 1 ? "s" : "") + "\u2026");
                toggle.setStyle("-fx-font-size: 11;");
                toggle.setOnAction(ev -> {
                    if (expandedRows.contains(expandKey)) {
                        expandedRows.remove(expandKey);
                    } else {
                        expandedRows.add(expandKey);
                    }
                    var table = getTableView();
                    if (table != null) table.refresh();
                });
                box.getChildren().add(toggle);
            }

            return box;
        }

        /**
         * Context menu for DN-syntax attribute values. Offers "Locate DN in
         * DIT" (mirrors Apache Directory Studio's same-named action) and a
         * Copy DN convenience.
         */
        private ContextMenu buildDnContextMenu(String dnValue) {
            ContextMenu menu = new ContextMenu();
            MenuItem locate = new MenuItem("Locate DN in DIT");
            locate.setOnAction(e -> {
                if (dnValue == null || dnValue.isBlank()) return;
                if (getTableView() != null
                        && getTableView().getUserData() instanceof MainController ctrl) {
                    ctrl.navigateToDN(dnValue);
                }
            });
            MenuItem copy = new MenuItem("Copy DN");
            copy.setOnAction(e -> {
                if (dnValue == null) return;
                var cc = new ClipboardContent();
                cc.putString(dnValue);
                Clipboard.getSystemClipboard().setContent(cc);
            });
            menu.getItems().addAll(locate, copy);
            return menu;
        }

        private javafx.scene.Node buildCollapsibleValue(AttributeRow item, String style) {
            String value = item.value();
            if (!isLongValue(value)) return null; // Use normal setText rendering

            String key = expandKey(item);
            boolean isExpanded = expandedRows.contains(key);

            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(2);

            javafx.scene.control.Hyperlink toggle = new javafx.scene.control.Hyperlink(
                    isExpanded ? "Show less" : "Show more");
            toggle.setStyle("-fx-font-size: 11;");
            toggle.setOnAction(e -> {
                if (expandedRows.contains(key)) {
                    expandedRows.remove(key);
                } else {
                    expandedRows.add(key);
                }
                var table = getTableView();
                if (table != null) table.refresh();
            });

            if (isExpanded) {
                // Use a read-only TextArea for expanded — handles height correctly
                javafx.scene.control.TextArea textArea = new javafx.scene.control.TextArea(value);
                textArea.setEditable(false);
                textArea.setWrapText(true);
                textArea.setFont(fontSettings.getDetailFont());
                if (style != null && !style.isEmpty()) textArea.setStyle(style);
                // Size to content: estimate rows needed
                int lineCount = value.split("\n", -1).length;
                int charWidth = 80; // rough chars per line
                int wrappedLines = 0;
                for (String line : value.split("\n", -1)) {
                    wrappedLines += Math.max(1, (int) Math.ceil(line.length() / (double) charWidth));
                }
                int rows = Math.min(wrappedLines + 1, 20);
                textArea.setPrefRowCount(rows);
                box.getChildren().addAll(textArea, toggle);
            } else {
                Label textLabel = new Label(truncate(value));
                textLabel.setFont(fontSettings.getDetailFont());
                textLabel.setWrapText(false);
                textLabel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                if (style != null && !style.isEmpty()) textLabel.setStyle(style);
                box.getChildren().addAll(textLabel, toggle);
            }

            return box;
        }

        private static final java.time.format.DateTimeFormatter DISPLAY_FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                        .withZone(java.time.ZoneId.systemDefault());

        private static String parseTimestamp(String raw) {
            try {
                // Generalized Time: 20260225175753Z or 20260225175753.0Z or 20260225175753+0000
                if (raw.length() >= 14) {
                    String normalized = raw.replace(".", "").replace(",", "");
                    // Strip fractional seconds if present after the base 14 digits
                    String base = normalized.substring(0, 14);
                    String tz = normalized.substring(14);

                    java.time.format.DateTimeFormatter parser = java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMddHHmmss");

                    java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(base, parser);

                    java.time.ZonedDateTime zdt;
                    if (tz.isEmpty() || tz.equals("Z") || tz.equals("z")) {
                        zdt = ldt.atZone(java.time.ZoneOffset.UTC);
                    } else {
                        // +0000 or -0500 format
                        java.time.ZoneOffset offset = java.time.ZoneOffset.of(
                                tz.length() == 5 ? tz.substring(0, 3) + ":" + tz.substring(3) : tz);
                        zdt = ldt.atZone(offset);
                    }

                    return DISPLAY_FMT.format(zdt);
                }
            } catch (Exception ignored) {}
            return null;
        }

        @Override
        protected void updateItem(AttributeRow item, boolean empty) {
            super.updateItem(item, empty);

            // Reset any per-row state that earlier branches may have set —
            // cells get recycled across rows, so a cell that previously
            // rendered a DN row (and got a DN context menu) would otherwise
            // carry that menu over to whatever row reuses it next.
            setContextMenu(null);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setStyle(STYLE_DEFAULT);
                return;
            }

            // Toggle row for multi-value collapse
            if (item.isToggleRow()) {
                setText(null);
                setStyle(STYLE_DEFAULT);
                String label = item.hiddenCount() > 0
                        ? "Show " + item.hiddenCount() + " more value" + (item.hiddenCount() != 1 ? "s" : "")
                        : "Show less";
                javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink(label);
                link.setStyle("-fx-font-size: 11;");
                link.setOnAction(e -> {
                    // Find the controller via the table's user data or scene
                    // We stored a reference — use the static controller ref
                    if (getTableView() != null && getTableView().getUserData() instanceof MainController ctrl) {
                        ctrl.toggleMultiValueExpansion(item.name());
                    }
                });
                setGraphic(link);
                return;
            }

            setFont(fontSettings.getDetailFont());

            switch (item.syntax()) {
                case BINARY -> {
                    setText(null);
                    setStyle(STYLE_DEFAULT);
                    javafx.scene.control.Hyperlink badge = new javafx.scene.control.Hyperlink(
                            "Binary Data (" + item.value().length() / 2 + " bytes)");
                    badge.getStyleClass().add("binary-badge");
                    badge.setOnAction(ev -> {
                        if (getTableView() != null
                                && getTableView().getUserData() instanceof MainController ctrl) {
                            boolean ro = NON_EDITABLE_ATTRS.contains(item.name().toLowerCase())
                                    || ctrl.readOnlyProperty().get();
                            javafx.stage.Window w = getTableView().getScene() != null
                                    ? getTableView().getScene().getWindow() : null;
                            new HexEditorDialog(item.name(), item.value(), ro, newData -> {
                                String dn = ctrl.selectedDNProperty().get();
                                if (dn != null) {
                                    try {
                                        ctrl.getLdapService().modifyBinaryAttribute(dn, item.name(),
                                                com.pointbluetech.arborj.service.LDAPService.LDAPModifyOperation.REPLACE,
                                                java.util.List.of(newData));
                                        ctrl.loadAttributes(dn);
                                    } catch (Exception ex) {
                                        new Alert(Alert.AlertType.ERROR,
                                                "Failed to save: " + ex.getMessage()).showAndWait();
                                    }
                                }
                            }, w).showAndWait();
                        }
                    });
                    setGraphic(badge);
                }
                case NET_ADDRESS -> {
                    setText(null);
                    setStyle(STYLE_DEFAULT);
                    var decoded = com.pointbluetech.arborj.util.NDSNetAddress.decodeFromHex(item.value());
                    if (decoded != null) {
                        Label netIcon = new Label("\uD83C\uDF10"); // globe
                        netIcon.setStyle("-fx-font-size: 12;");
                        Label addrLabel = new Label(decoded.getDisplayString());
                        addrLabel.setFont(fontSettings.getDetailFont());
                        addrLabel.setStyle("-fx-text-fill: -color-fg-muted;");
                        Label typeLabel = new Label("Type: " + decoded.getType().getDisplayName()
                                + " (" + decoded.getType().getValue() + ")  •  "
                                + decoded.getAddressData().length + " bytes");
                        typeLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
                        javafx.scene.layout.HBox top = new javafx.scene.layout.HBox(6, netIcon, addrLabel);
                        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(2, top, typeLabel);
                        // Click to open hex editor
                        box.setOnMouseClicked(ev -> {
                            if (ev.getClickCount() == 2 && getTableView() != null
                                    && getTableView().getUserData() instanceof MainController ctrl) {
                                javafx.stage.Window w = getTableView().getScene() != null
                                        ? getTableView().getScene().getWindow() : null;
                                new HexEditorDialog(item.name(), item.value(), true, null, w).showAndWait();
                            }
                        });
                        box.setCursor(javafx.scene.Cursor.HAND);
                        setGraphic(box);
                    } else {
                        // Fallback to binary display
                        Label badge2 = new Label("Binary Data (" + item.value().length() / 2 + " bytes)");
                        badge2.getStyleClass().add("binary-badge");
                        setGraphic(badge2);
                    }
                }
                case BOOLEAN -> {
                    String icon = "TRUE".equalsIgnoreCase(item.value()) ? "\u2713" : "\u2717";
                    setText(icon + " " + item.value().toUpperCase());
                    setStyle("TRUE".equalsIgnoreCase(item.value())
                            ? "-fx-text-fill: -color-success-fg;" : "-fx-text-fill: -color-danger-fg;");
                    setGraphic(null);
                }
                case GENERALIZED_TIME, UTC_TIME -> {
                    setText(null);
                    setStyle(STYLE_DEFAULT);
                    String parsed = parseTimestamp(item.value());
                    Label rawLabel = new Label(item.value());
                    rawLabel.setFont(fontSettings.getDetailFont());
                    if (parsed != null) {
                        Label parsedLabel = new Label(parsed);
                        parsedLabel.setFont(fontSettings.getDetailFont());
                        parsedLabel.setStyle("-fx-text-fill: -color-fg-muted;");
                        setGraphic(new javafx.scene.layout.VBox(1, rawLabel, parsedLabel));
                    } else {
                        setGraphic(rawLabel);
                    }
                }
                case INTEGER -> {
                    // Inline UAC bitmask display for AD userAccountControl
                    if (item.name().equalsIgnoreCase("userAccountControl")) {
                        setText(null);
                        setStyle(STYLE_DEFAULT);
                        setGraphic(buildUACView(item));
                    } else {
                        setText(item.value());
                        setStyle(STYLE_MUTED);
                        setGraphic(null);
                    }
                }
                case NDS_TIMESTAMP -> {
                    setText(null);
                    setStyle(STYLE_DEFAULT);
                    Label rawLabel2 = new Label(item.value());
                    rawLabel2.setFont(fontSettings.getDetailFont());
                    rawLabel2.setStyle("-fx-text-fill: -color-fg-muted;");
                    try {
                        long epoch = Long.parseLong(item.value());
                        if (epoch > 0) {
                            java.time.Instant instant = java.time.Instant.ofEpochSecond(epoch);
                            String formatted = java.time.format.DateTimeFormatter
                                    .ofPattern("yyyy-MM-dd HH:mm:ss z")
                                    .withZone(java.time.ZoneId.systemDefault())
                                    .format(instant);
                            Label dateLabel = new Label(formatted);
                            dateLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
                            setGraphic(new javafx.scene.layout.VBox(2, rawLabel2, dateLabel));
                        } else {
                            setGraphic(rawLabel2);
                        }
                    } catch (NumberFormatException ex) {
                        setGraphic(rawLabel2);
                    }
                }
                case PATH -> {
                    setText(null);
                    setStyle(STYLE_DEFAULT);
                    // NDS Path format: dn#number#text
                    String[] parts = item.value().split("#", 3);
                    if (parts.length == 3) {
                        Label dnLabel = new Label(parts[0]);
                        dnLabel.setFont(fontSettings.getDetailFont());
                        dnLabel.setStyle(STYLE_DN);
                        Label detailLabel = new Label(parts[1] + "  " + parts[2]);
                        detailLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
                        setGraphic(new javafx.scene.layout.VBox(1, dnLabel, detailLabel));
                    } else {
                        Label fallback = new Label(item.value());
                        fallback.setFont(fontSettings.getDetailFont());
                        fallback.setStyle("-fx-text-fill: -color-fg-muted;");
                        setGraphic(fallback);
                    }
                }
                case DN -> {
                    var dnNode = buildCollapsibleValue(item, STYLE_DN);
                    if (dnNode != null) {
                        setText(null);
                        setStyle(STYLE_DEFAULT);
                        setGraphic(dnNode);
                    } else {
                        setText(item.value());
                        setStyle(STYLE_DN);
                        setGraphic(null);
                    }
                    setContextMenu(buildDnContextMenu(item.value()));
                }
                default -> { // STRING
                    var strNode = buildCollapsibleValue(item, null);
                    if (strNode != null) {
                        setText(null);
                        setStyle(STYLE_DEFAULT);
                        setGraphic(strNode);
                    } else {
                        setText(item.value());
                        setStyle(STYLE_DEFAULT);
                        setGraphic(null);
                    }
                }
            }
        }
    }

    /**
     * Shows a modal dialog asking for an attribute name, with schema-backed autocomplete.
     */
    private Optional<String> promptForAttributeName(String dn) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Add Attribute");
        dialog.setHeaderText("Add attribute to: " + dn);

        TextField field = new TextField();
        field.setPromptText("Attribute name");
        field.setPrefColumnCount(30);

        ContextMenu suggestions = new ContextMenu();
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            suggestions.hide();
            if (!AttributeSuggestSettings.getInstance().isEnabled()) return;
            if (newVal == null || newVal.length() < 2) return;
            var attrMap = controller.getSchemaService().getAttributeMap();
            if (attrMap == null) return;
            String lower = newVal.toLowerCase();
            List<String> matches = attrMap.keySet().stream()
                    .filter(name -> name.toLowerCase().startsWith(lower))
                    .sorted()
                    .limit(10)
                    .toList();
            if (matches.isEmpty()) return;
            suggestions.getItems().clear();
            for (String match : matches) {
                MenuItem mi = new MenuItem(match);
                mi.setOnAction(a -> {
                    field.setText(match);
                    field.positionCaret(match.length());
                    suggestions.hide();
                });
                suggestions.getItems().add(mi);
            }
            suggestions.show(field, Side.BOTTOM, 0, 0);
        });

        VBox content = new VBox(8, new Label("Attribute name:"), field);
        content.setPadding(new Insets(8, 12, 0, 12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == ButtonType.OK ? field.getText().trim() : null);
        javafx.application.Platform.runLater(field::requestFocus);
        return dialog.showAndWait();
    }
}
