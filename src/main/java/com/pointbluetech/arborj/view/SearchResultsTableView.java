package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.service.LDAPService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spreadsheet-style table view of search results.
 * Shows one row per entry with columns for DN and each selected attribute.
 * Attribute cells are editable — changes are written to LDAP on commit.
 */
public class SearchResultsTableView {

    private final MainController controller;
    private final List<String> attributes;
    private final Stage stage;
    private final Label statusLabel = new Label();

    public SearchResultsTableView(MainController controller, List<String> attributes) {
        this.controller = controller;
        this.attributes = attributes;

        stage = new Stage();
        stage.setTitle("Search Results \u2014 Table View");
        stage.setMinWidth(600);
        stage.setMinHeight(400);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        // Header
        int count = controller.getSearchResults().size();
        Label header = new Label(count + " result" + (count != 1 ? "s" : "")
                + " \u2014 " + attributes.size() + " attribute" + (attributes.size() != 1 ? "s" : ""));
        header.setFont(Font.font("System", FontWeight.BOLD, 14));

        // Table
        boolean editable = !controller.readOnlyProperty().get();
        TableView<LDAPEntry> table = buildTable(editable);
        VBox.setVgrow(table, Priority.ALWAYS);

        // Footer
        Button exportCsvBtn = new Button("Export CSV...");
        exportCsvBtn.setOnAction(e -> exportCSV(table));

        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        if (editable) {
            statusLabel.setText("Double-click a cell to edit");
        }

        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, exportCsvBtn, statusLabel, spacer, closeBtn);
        footer.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(header, table, footer);

        Scene scene = new Scene(root, 950, 600);
        stage.setScene(scene);

        // Set icon
        var iconUrl = getClass().getResource("/com/pointbluetech/arborj/icons/icon-256.png");
        if (iconUrl != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
        }
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    private TableView<LDAPEntry> buildTable(boolean editable) {
        TableView<LDAPEntry> table = new TableView<>(controller.getSearchResults());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setEditable(editable);

        // Per-row context menu — mirrors the tree menu minus container-only items.
        table.setRowFactory(tv -> {
            TableRow<LDAPEntry> row = new TableRow<>();
            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                if (newItem == null) {
                    row.setContextMenu(null);
                } else {
                    row.setContextMenu(EntryContextMenu.build(
                            controller, newItem.getDn(), newItem.getValues("objectClass"), row));
                }
            });
            return row;
        });

        // DN column (always first, never editable)
        TableColumn<LDAPEntry, String> dnCol = new TableColumn<>("Dn");
        dnCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getDn()));
        dnCol.setPrefWidth(300);
        dnCol.setCellFactory(col -> readOnlyMonoCell());
        dnCol.setEditable(false);
        dnCol.setStyle("-fx-opacity: 0.85;");
        table.getColumns().add(dnCol);

        // One column per selected attribute
        for (String attr : attributes) {
            if ("*".equals(attr)) continue;

            TableColumn<LDAPEntry, String> attrCol = new TableColumn<>(editable ? "\u270E " + attr : attr);
            attrCol.setCellValueFactory(cd -> {
                List<String> values = cd.getValue().getValues(attr);
                if (values.isEmpty()) {
                    return new SimpleStringProperty("");
                } else if (values.size() == 1) {
                    return new SimpleStringProperty(values.getFirst());
                } else {
                    return new SimpleStringProperty(String.join("; ", values));
                }
            });
            attrCol.setPrefWidth(180);
            attrCol.setEditable(editable);

            if (editable) {
                attrCol.setCellFactory(col -> new EditableMonoCell(attr));
                attrCol.setOnEditCommit(event -> {
                    LDAPEntry entry = event.getRowValue();
                    String newValue = event.getNewValue();
                    String oldValue = event.getOldValue();
                    if (newValue.equals(oldValue)) return;
                    commitEdit(entry, attr, oldValue, newValue, table);
                });
            } else {
                attrCol.setCellFactory(col -> readOnlyMonoCell());
            }

            table.getColumns().add(attrCol);
        }

        return table;
    }

    private void commitEdit(LDAPEntry entry, String attr, String oldDisplay,
                            String newDisplay, TableView<LDAPEntry> table) {
        String dn = entry.getDn();
        List<String> oldValues = entry.getValues(attr);

        // Parse new values (semicolon-separated for multi-value)
        List<String> newValues = new ArrayList<>();
        for (String v : newDisplay.split(";")) {
            String trimmed = v.trim();
            if (!trimmed.isEmpty()) {
                newValues.add(trimmed);
            }
        }

        // Determine the LDAP operation
        LDAPService.LDAPModifyOperation op;
        if (oldValues.isEmpty() && !newValues.isEmpty()) {
            op = LDAPService.LDAPModifyOperation.ADD;
        } else if (!oldValues.isEmpty() && newValues.isEmpty()) {
            op = LDAPService.LDAPModifyOperation.DELETE;
        } else {
            op = LDAPService.LDAPModifyOperation.REPLACE;
        }

        statusLabel.setText("Saving...");
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        Thread.startVirtualThread(() -> {
            try {
                LDAPService svc = controller.getLdapService();
                if (op == LDAPService.LDAPModifyOperation.DELETE) {
                    // Delete the attribute entirely
                    svc.modifyAttribute(dn, attr, LDAPService.LDAPModifyOperation.DELETE, List.of());
                } else {
                    svc.modifyAttribute(dn, attr, op, newValues);
                }

                // Update the local entry's attributes map to reflect the change
                Platform.runLater(() -> {
                    if (newValues.isEmpty()) {
                        entry.getAttributes().remove(attr);
                        // Also remove case-insensitive
                        entry.getAttributes().entrySet().removeIf(
                                e -> e.getKey().equalsIgnoreCase(attr));
                    } else {
                        // Remove old key (case-insensitive) and put new
                        entry.getAttributes().entrySet().removeIf(
                                e -> e.getKey().equalsIgnoreCase(attr));
                        entry.getAttributes().put(attr, new ArrayList<>(newValues));
                    }
                    table.refresh();
                    statusLabel.setText("Saved: " + attr + " on " + extractRdn(dn));
                    statusLabel.setStyle("-fx-text-fill: green; -fx-font-size: 11;");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setStyle("-fx-text-fill: red; -fx-font-size: 11;");
                    table.refresh(); // Revert display
                });
            }
        });
    }

    private String extractRdn(String dn) {
        return dn.contains(",") ? dn.substring(0, dn.indexOf(',')) : dn;
    }

    private TableCell<LDAPEntry, String> readOnlyMonoCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                } else {
                    setText(item);
                    setFont(Font.font("monospaced", 12));
                    setStyle("-fx-background-color: -color-bg-subtle;");
                    if (item.length() > 60) {
                        setTooltip(new Tooltip(item));
                    }
                }
            }
        };
    }

    /**
     * Editable cell with monospaced font. Double-click to edit, Enter to commit, Escape to cancel.
     */
    private class EditableMonoCell extends TableCell<LDAPEntry, String> {
        private TextField textField;
        private final String attrName;

        EditableMonoCell(String attrName) {
            this.attrName = attrName;
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();

            textField = new TextField(getItem() == null ? "" : getItem());
            textField.setFont(Font.font("monospaced", 12));
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
            textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    commitEdit(textField.getText());
                }
            });

            setText(null);
            setGraphic(textField);
            textField.selectAll();
            textField.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem());
            setGraphic(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
            } else if (isEditing()) {
                if (textField != null) {
                    textField.setText(item);
                }
                setText(null);
                setGraphic(textField);
            } else {
                setText(item);
                setGraphic(null);
                setFont(Font.font("monospaced", 12));
                if (item != null && item.length() > 60) {
                    setTooltip(new Tooltip(item));
                } else {
                    setTooltip(null);
                }
            }
        }
    }

    private void exportCSV(TableView<LDAPEntry> table) {
        var settings = com.pointbluetech.arborj.service.ExportSettings.getInstance();
        String attrDelim = settings.getCsvAttrDelimiter();
        String valDelim = settings.getCsvValueDelimiter();
        String quote = settings.getCsvQuoteChar();
        String eol = settings.getCsvLineSeparator();
        String encoding = settings.getCsvEncoding();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export CSV");
        chooser.setInitialFileName("search_results.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.Charset.forName(encoding))) {
            // Header row
            writer.write(csvQuote("Dn", quote));
            for (String attr : attributes) {
                if ("*".equals(attr)) continue;
                writer.write(attrDelim + csvQuote(attr, quote));
            }
            writer.write(eol);

            // Data rows
            for (LDAPEntry entry : table.getItems()) {
                writer.write(csvQuote(entry.getDn(), quote));
                for (String attr : attributes) {
                    if ("*".equals(attr)) continue;
                    List<String> values = entry.getValues(attr);
                    String cell = String.join(valDelim, values);
                    writer.write(attrDelim + csvQuote(cell, quote));
                }
                writer.write(eol);
            }

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Exported " + table.getItems().size() + " rows to:\n" + file.getAbsolutePath());
            alert.setTitle("Export Complete");
            alert.setHeaderText(null);
            alert.showAndWait();
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Failed to export: " + ex.getMessage());
            alert.setTitle("Export Error");
            alert.showAndWait();
        }
    }

    private String csvQuote(String value, String quote) {
        if (value == null) return quote + quote;
        return quote + value.replace(quote, quote + quote) + quote;
    }
}
