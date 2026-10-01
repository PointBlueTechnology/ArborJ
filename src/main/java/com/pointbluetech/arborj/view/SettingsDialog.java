package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.ArborJApp;
import com.pointbluetech.arborj.service.FontSettings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Modality;

import java.util.List;
import javafx.stage.Stage;

/**
 * Application settings window.
 */
public class SettingsDialog {

    private final Stage stage;
    private final com.pointbluetech.arborj.service.CredentialStore credentialStore;

    public SettingsDialog() {
        this(null, null);
    }

    public SettingsDialog(com.pointbluetech.arborj.service.CredentialStore credentialStore) {
        this(credentialStore, null);
    }

    public SettingsDialog(com.pointbluetech.arborj.service.CredentialStore credentialStore,
                          javafx.stage.Window owner) {
        this.credentialStore = credentialStore;
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Settings");
        stage.setResizable(false);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(buildAppearanceTab());
        tabs.getTabs().add(buildExportTab());
        tabs.getTabs().add(buildLogsTab());
        tabs.getTabs().add(buildPerformanceTab());
        if (credentialStore != null) {
            tabs.getTabs().add(buildSecurityTab());
        }

        VBox root = new VBox(tabs);
        Scene scene = new Scene(root, 520, 520);
        stage.setScene(scene);
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    private Tab buildAppearanceTab() {
        Tab tab = new Tab("Appearance");
        FontSettings fs = FontSettings.getInstance();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        int row = 0;

        // Theme selector
        grid.add(new Label("Theme:"), 0, row);
        ComboBox<ArborJApp.ThemeOption> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll(ArborJApp.THEMES);
        String currentTheme = ArborJApp.getCurrentTheme();
        for (ArborJApp.ThemeOption opt : ArborJApp.THEMES) {
            if (opt.name().equals(currentTheme)) {
                themeCombo.setValue(opt);
                break;
            }
        }
        themeCombo.setOnAction(e -> {
            ArborJApp.ThemeOption selected = themeCombo.getValue();
            if (selected != null) {
                ArborJApp.applyTheme(selected.name());
            }
        });
        grid.add(themeCombo, 1, row++);

        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);

        // Section header
        Label fontHeader = new Label("Detail & Search Results Font");
        fontHeader.setStyle("-fx-font-weight: bold;");
        grid.add(fontHeader, 0, row++, 2, 1);

        // Font family — recommended monospaced fonts first, then all system fonts
        grid.add(new Label("Font:"), 0, row);
        ComboBox<String> familyCombo = new ComboBox<>();
        List<String> allFamilies = FontSettings.getAvailableFamilies();
        // Add installed recommended fonts first
        for (String rec : FontSettings.RECOMMENDED_MONO_FONTS) {
            if (allFamilies.contains(rec)) {
                familyCombo.getItems().add(rec);
            }
        }
        // Separator and then all fonts
        if (!familyCombo.getItems().isEmpty()) {
            familyCombo.getItems().add("───────────────");
        }
        for (String f : allFamilies) {
            if (!familyCombo.getItems().contains(f)) {
                familyCombo.getItems().add(f);
            }
        }
        familyCombo.setValue(fs.fontFamilyProperty().get());
        familyCombo.setOnAction(e -> {
            String val = familyCombo.getValue();
            if (val != null && !val.startsWith("───")) {
                fs.fontFamilyProperty().set(val);
            }
        });
        familyCombo.setMaxWidth(Double.MAX_VALUE);
        grid.add(familyCombo, 1, row++);

        // Font size
        grid.add(new Label("Size:"), 0, row);
        Spinner<Integer> sizeSpinner = new Spinner<>(9, 24, fs.fontSizeProperty().get());
        sizeSpinner.setEditable(true);
        sizeSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
                fs.fontSizeProperty().set(newVal));
        sizeSpinner.setPrefWidth(80);
        grid.add(sizeSpinner, 1, row++);

        // Font weight
        grid.add(new Label("Weight:"), 0, row);
        ComboBox<String> weightCombo = new ComboBox<>();
        weightCombo.getItems().addAll(FontSettings.AVAILABLE_WEIGHTS);
        weightCombo.setValue(fs.fontWeightProperty().get());
        weightCombo.setOnAction(e -> fs.fontWeightProperty().set(weightCombo.getValue()));
        grid.add(weightCombo, 1, row++);

        Label weightNote = new Label("Weight varies by font. Some fonts only support Normal and Bold.");
        weightNote.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        weightNote.setWrapText(true);
        grid.add(weightNote, 1, row++);

        // Preview
        grid.add(new Separator(), 0, row++, 2, 1);
        Label previewLabel = new Label("Preview:");
        previewLabel.setStyle("-fx-font-weight: bold;");
        grid.add(previewLabel, 0, row);

        Label preview = new Label("cn=admin,ou=sa,o=system");
        preview.fontProperty().bind(fs.detailFontProperty());
        preview.setStyle("-fx-padding: 8; -fx-background-color: -color-bg-subtle; "
                + "-fx-background-radius: 4;");
        preview.setMaxWidth(Double.MAX_VALUE);
        grid.add(preview, 1, row++);

        // Column constraints
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(90);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        tab.setContent(grid);
        return tab;
    }

    private Tab buildExportTab() {
        Tab tab = new Tab("Export");
        var es = com.pointbluetech.arborj.service.ExportSettings.getInstance();

        // Two sections: LDIF and CSV
        VBox content = new VBox(16);
        content.setPadding(new Insets(20));

        // ── LDIF Section ──
        Label ldifHeader = new Label("LDIF Export");
        ldifHeader.setStyle("-fx-font-weight: bold;");

        GridPane ldifGrid = new GridPane();
        ldifGrid.setHgap(12);
        ldifGrid.setVgap(8);
        int row = 0;

        // Line separator
        ldifGrid.add(new Label("Line Separator:"), 0, row);
        ComboBox<String> ldifEolCombo = new ComboBox<>();
        ldifEolCombo.getItems().addAll("Unix (\\n)", "Windows (\\r\\n)");
        ldifEolCombo.setValue("\r\n".equals(es.getLdifLineSeparator()) ? "Windows (\\r\\n)" : "Unix (\\n)");
        ldifEolCombo.setOnAction(e -> es.ldifLineSeparatorProperty().set(
                "Windows (\\r\\n)".equals(ldifEolCombo.getValue()) ? "\r\n" : "\n"));
        ldifGrid.add(ldifEolCombo, 1, row++);

        // Line length
        ldifGrid.add(new Label("Line Length:"), 0, row);
        Spinner<Integer> lineLenSpinner = new Spinner<>(0, 998, es.getLdifLineLength());
        lineLenSpinner.setEditable(true);
        lineLenSpinner.setPrefWidth(100);
        lineLenSpinner.valueProperty().addListener((obs, o, n) -> es.ldifLineLengthProperty().set(n));
        HBox lineLenRow = new HBox(6, lineLenSpinner, new Label("characters (0 = no folding)"));
        lineLenRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        ldifGrid.add(lineLenRow, 1, row++);

        // Space after colon
        CheckBox spaceCheck = new CheckBox("Space after colon");
        spaceCheck.setSelected(es.isLdifSpaceAfterColon());
        spaceCheck.selectedProperty().addListener((obs, o, n) -> es.ldifSpaceAfterColonProperty().set(n));
        ldifGrid.add(spaceCheck, 1, row++);

        // Include version line
        CheckBox versionCheck = new CheckBox("Include version line");
        versionCheck.setSelected(es.isLdifIncludeVersion());
        versionCheck.selectedProperty().addListener((obs, o, n) -> es.ldifIncludeVersionProperty().set(n));
        ldifGrid.add(versionCheck, 1, row++);

        // ── CSV Section ──
        Label csvHeader = new Label("CSV Export");
        csvHeader.setStyle("-fx-font-weight: bold;");

        GridPane csvGrid = new GridPane();
        csvGrid.setHgap(12);
        csvGrid.setVgap(8);
        row = 0;

        // Attribute delimiter
        csvGrid.add(new Label("Attribute Delimiter:"), 0, row);
        ComboBox<String> attrDelimCombo = new ComboBox<>();
        attrDelimCombo.getItems().addAll("Comma (,)", "Tab", "Semicolon (;)", "Pipe (|)");
        attrDelimCombo.setValue(delimToLabel(es.getCsvAttrDelimiter()));
        attrDelimCombo.setOnAction(e -> es.csvAttrDelimiterProperty().set(labelToDelim(attrDelimCombo.getValue())));
        csvGrid.add(attrDelimCombo, 1, row++);

        // Value delimiter
        csvGrid.add(new Label("Value Delimiter:"), 0, row);
        ComboBox<String> valDelimCombo = new ComboBox<>();
        valDelimCombo.getItems().addAll("Pipe (|)", "Semicolon (;)", "Comma (,)");
        valDelimCombo.setValue(delimToLabel(es.getCsvValueDelimiter()));
        valDelimCombo.setOnAction(e -> es.csvValueDelimiterProperty().set(labelToDelim(valDelimCombo.getValue())));
        csvGrid.add(valDelimCombo, 1, row++);

        // Quote character
        csvGrid.add(new Label("Quote Character:"), 0, row);
        ComboBox<String> quoteCombo = new ComboBox<>();
        quoteCombo.getItems().addAll("Double Quote (\")", "Single Quote (')", "None");
        quoteCombo.setValue(quoteToLabel(es.getCsvQuoteChar()));
        quoteCombo.setOnAction(e -> es.csvQuoteCharProperty().set(labelToQuote(quoteCombo.getValue())));
        csvGrid.add(quoteCombo, 1, row++);

        // Line separator
        csvGrid.add(new Label("Line Separator:"), 0, row);
        ComboBox<String> csvEolCombo = new ComboBox<>();
        csvEolCombo.getItems().addAll("Unix (\\n)", "Windows (\\r\\n)");
        csvEolCombo.setValue("\r\n".equals(es.getCsvLineSeparator()) ? "Windows (\\r\\n)" : "Unix (\\n)");
        csvEolCombo.setOnAction(e -> es.csvLineSeparatorProperty().set(
                "Windows (\\r\\n)".equals(csvEolCombo.getValue()) ? "\r\n" : "\n"));
        csvGrid.add(csvEolCombo, 1, row++);

        // File encoding
        csvGrid.add(new Label("File Encoding:"), 0, row);
        ComboBox<String> encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll("UTF-8", "ISO-8859-1", "US-ASCII", "UTF-16");
        encodingCombo.setValue(es.getCsvEncoding());
        encodingCombo.setEditable(true);
        encodingCombo.setOnAction(e -> {
            String val = encodingCombo.getEditor().getText();
            if (val != null && !val.isEmpty()) es.csvEncodingProperty().set(val);
        });
        csvGrid.add(encodingCombo, 1, row++);

        // Column constraints for both grids
        for (GridPane g : List.of(ldifGrid, csvGrid)) {
            ColumnConstraints labelCol = new ColumnConstraints();
            labelCol.setPrefWidth(130);
            ColumnConstraints fieldCol = new ColumnConstraints();
            fieldCol.setHgrow(Priority.ALWAYS);
            g.getColumnConstraints().addAll(labelCol, fieldCol);
        }

        // Restore defaults button
        Button restoreBtn = new Button("Restore Defaults");
        restoreBtn.setOnAction(e -> {
            es.restoreDefaults();
            // Refresh UI
            ldifEolCombo.setValue("Unix (\\n)");
            lineLenSpinner.getValueFactory().setValue(76);
            spaceCheck.setSelected(true);
            versionCheck.setSelected(true);
            attrDelimCombo.setValue("Comma (,)");
            valDelimCombo.setValue("Pipe (|)");
            quoteCombo.setValue("Double Quote (\")");
            csvEolCombo.setValue("Unix (\\n)");
            encodingCombo.setValue("UTF-8");
        });

        HBox restoreRow = new HBox(restoreBtn);
        restoreRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        content.getChildren().addAll(
                ldifHeader, ldifGrid,
                new Separator(),
                csvHeader, csvGrid,
                new Separator(),
                restoreRow);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        tab.setContent(scroll);
        return tab;
    }

    private String delimToLabel(String delim) {
        return switch (delim) {
            case "," -> "Comma (,)";
            case "\t" -> "Tab";
            case ";" -> "Semicolon (;)";
            case "|" -> "Pipe (|)";
            default -> "Comma (,)";
        };
    }

    private String labelToDelim(String label) {
        return switch (label) {
            case "Tab" -> "\t";
            case "Semicolon (;)" -> ";";
            case "Pipe (|)" -> "|";
            default -> ",";
        };
    }

    private String quoteToLabel(String quote) {
        return switch (quote) {
            case "\"" -> "Double Quote (\")";
            case "'" -> "Single Quote (')";
            case "" -> "None";
            default -> "Double Quote (\")";
        };
    }

    private String labelToQuote(String label) {
        return switch (label) {
            case "Single Quote (')" -> "'";
            case "None" -> "";
            default -> "\"";
        };
    }

    private static final java.nio.file.Path JVM_OPTIONS_FILE =
            java.nio.file.Path.of(System.getProperty("user.home"), ".arborj", "jvm.options");

    private Tab buildLogsTab() {
        Tab tab = new Tab("Logs");
        com.pointbluetech.arborj.service.ActivityLogger logger =
                com.pointbluetech.arborj.service.ActivityLogger.getInstance();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        int row = 0;

        Label header = new Label("Activity Logging");
        header.setStyle("-fx-font-weight: bold;");
        grid.add(header, 0, row++, 2, 1);

        CheckBox modBox = new CheckBox("Enable modification logs");
        modBox.selectedProperty().bindBidirectional(logger.modEnabledProperty());
        grid.add(modBox, 0, row++, 2, 1);

        CheckBox searchBox = new CheckBox("Enable search logs");
        searchBox.selectedProperty().bindBidirectional(logger.searchEnabledProperty());
        grid.add(searchBox, 0, row++, 2, 1);

        // Max log size
        grid.add(new Label("Max size per log:"), 0, row);
        Spinner<Integer> sizeSpinner = new Spinner<>(10, 10_000, logger.maxSizeKbProperty().get(), 100);
        sizeSpinner.setEditable(true);
        sizeSpinner.valueProperty().addListener((o, a, b) -> {
            if (b != null) logger.maxSizeKbProperty().set(b);
        });
        HBox sizeRow = new HBox(8, sizeSpinner, new Label("kB (per log)"));
        sizeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.add(sizeRow, 1, row++);

        Label sizeNote = new Label(
                "When a log exceeds this size, the oldest half is trimmed. "
                + "Logs are kept in memory only; nothing is written to disk.");
        sizeNote.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        sizeNote.setWrapText(true);
        sizeNote.setMaxWidth(360);
        grid.add(sizeNote, 1, row++);

        // Masked attributes
        Label maskedLabel = new Label("Masked attributes:");
        grid.add(maskedLabel, 0, row);
        TextField maskedField = new TextField();
        maskedField.textProperty().bindBidirectional(logger.maskedAttributesRawProperty());
        maskedField.setPromptText("userPassword, otherSecret");
        grid.add(maskedField, 1, row++);

        Label maskedNote = new Label(
                "Comma-separated attribute names whose values are replaced with **** in the "
                + "modification log. Useful for sensitive fields like userPassword.");
        maskedNote.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        maskedNote.setWrapText(true);
        maskedNote.setMaxWidth(360);
        grid.add(maskedNote, 1, row++);

        // Clear buttons
        Button clearMods = new Button("Clear Modification Log");
        clearMods.setOnAction(e -> logger.clearModificationLog());
        Button clearSearches = new Button("Clear Search Log");
        clearSearches.setOnAction(e -> logger.clearSearchLog());
        HBox clearRow = new HBox(8, clearMods, clearSearches);
        grid.add(clearRow, 1, row++);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(140);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        tab.setContent(grid);
        return tab;
    }

    private Tab buildPerformanceTab() {
        Tab tab = new Tab("Performance");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new javafx.geometry.Insets(20));

        int row = 0;

        // Current memory usage
        Runtime rt = Runtime.getRuntime();
        long maxMB = rt.maxMemory() / (1024 * 1024);
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        Label memHeader = new Label("Memory");
        memHeader.setStyle("-fx-font-weight: bold;");
        grid.add(memHeader, 0, row++, 2, 1);

        grid.add(new Label("Current usage:"), 0, row);
        Label usageLabel = new Label(usedMB + " MB of " + maxMB + " MB max");
        usageLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        grid.add(usageLabel, 1, row++);

        // Heap size setting
        grid.add(new Label("Max heap size:"), 0, row);

        int savedHeap = loadSavedHeapSize();
        ComboBox<String> heapCombo = new ComboBox<>();
        heapCombo.getItems().addAll("512 MB", "1 GB", "2 GB", "4 GB", "8 GB");
        heapCombo.setValue(heapSizeToLabel(savedHeap));
        heapCombo.setOnAction(e -> {
            int mb = labelToHeapSize(heapCombo.getValue());
            saveHeapSize(mb);
        });
        grid.add(heapCombo, 1, row++);

        Label heapNote = new Label("Takes effect after restart. Default: 1 GB.");
        heapNote.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        heapNote.setWrapText(true);
        grid.add(heapNote, 1, row++);

        // Column constraints
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(110);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        tab.setContent(grid);
        return tab;
    }

    private int loadSavedHeapSize() {
        try {
            if (java.nio.file.Files.exists(JVM_OPTIONS_FILE)) {
                String content = java.nio.file.Files.readString(JVM_OPTIONS_FILE).trim();
                if (content.startsWith("-Xmx")) {
                    String val = content.substring(4);
                    if (val.endsWith("g")) return Integer.parseInt(val.replace("g", "")) * 1024;
                    if (val.endsWith("m")) return Integer.parseInt(val.replace("m", ""));
                }
            }
        } catch (Exception ignored) {}
        return 1024; // default
    }

    private void saveHeapSize(int mb) {
        try {
            java.nio.file.Files.createDirectories(JVM_OPTIONS_FILE.getParent());
            String value = mb >= 1024 ? "-Xmx" + (mb / 1024) + "g" : "-Xmx" + mb + "m";
            java.nio.file.Files.writeString(JVM_OPTIONS_FILE, value);
        } catch (Exception e) {
            System.err.println("Failed to save heap size: " + e.getMessage());
        }
    }

    private String heapSizeToLabel(int mb) {
        return switch (mb) {
            case 512 -> "512 MB";
            case 2048 -> "2 GB";
            case 4096 -> "4 GB";
            case 8192 -> "8 GB";
            default -> "1 GB";
        };
    }

    private int labelToHeapSize(String label) {
        return switch (label) {
            case "512 MB" -> 512;
            case "2 GB" -> 2048;
            case "4 GB" -> 4096;
            case "8 GB" -> 8192;
            default -> 1024;
        };
    }

    private Tab buildSecurityTab() {
        Tab tab = new Tab("Security");

        VBox content = new VBox(12);
        content.setPadding(new javafx.geometry.Insets(20));

        Label header = new Label("Approved Certificates");
        header.setStyle("-fx-font-weight: bold;");

        Label description = new Label("Certificates you have manually trusted. "
                + "Removing a certificate will prompt you to re-approve it on next connection.");
        description.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        description.setWrapText(true);

        ListView<com.pointbluetech.arborj.service.CredentialStore.ApprovedCert> certList = new ListView<>();
        certList.setPrefHeight(200);
        certList.getItems().addAll(credentialStore.listApprovedCerts());
        certList.setPlaceholder(new Label("No manually approved certificates"));
        certList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.pointbluetech.arborj.service.CredentialStore.ApprovedCert item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label hostLabel = new Label(item.hostPort());
                hostLabel.setStyle("-fx-font-weight: bold;");
                Label subjectLabel = new Label(item.subject().isEmpty() ? "" : item.subject());
                subjectLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
                subjectLabel.setWrapText(true);
                VBox cell = new VBox(1, hostLabel, subjectLabel);
                setGraphic(cell);
                setText(null);
            }
        });

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setDisable(true);
        certList.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) ->
                removeBtn.setDisable(sel == null));

        removeBtn.setOnAction(e -> {
            var selected = certList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Remove approved certificate for " + selected.hostPort() + "?\n\n"
                        + "You will be prompted to approve it again on next connection.",
                        ButtonType.YES, ButtonType.NO);
                confirm.setTitle("Remove Certificate");
                confirm.setHeaderText(null);
                confirm.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        credentialStore.deleteApprovedCertByKey(selected.hostPort());
                        certList.getItems().remove(selected);
                    }
                });
            }
        });

        Button removeAllBtn = new Button("Remove All");
        removeAllBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove all approved certificates?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Remove All Certificates");
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    for (var cert : new java.util.ArrayList<>(certList.getItems())) {
                        credentialStore.deleteApprovedCertByKey(cert.hostPort());
                    }
                    certList.getItems().clear();
                }
            });
        });

        HBox buttons = new HBox(8, removeBtn, removeAllBtn);

        content.getChildren().addAll(header, description, certList, buttons);
        tab.setContent(content);
        return tab;
    }
}
