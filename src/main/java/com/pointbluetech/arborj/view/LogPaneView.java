package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.service.ActivityLogger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;

/**
 * Bottom-dock pane that displays the application's modification and search
 * logs, modeled on Apache Directory Studio's Modification Logs / Search Logs
 * views. Content is bound directly to {@link ActivityLogger}'s properties.
 */
public class LogPaneView {

    private final ActivityLogger logger = ActivityLogger.getInstance();
    private final VBox root = new VBox();
    private Runnable onClose = () -> {};

    /** Called when the user clicks the pane's close button. */
    public void setOnClose(Runnable handler) {
        this.onClose = handler == null ? () -> {} : handler;
    }

    public LogPaneView() {
        TextArea modArea = buildLogArea(logger.modificationLogProperty());
        TextArea searchArea = buildLogArea(logger.searchLogProperty());

        Tab modTab = new Tab("Modification Logs", modArea);
        modTab.setClosable(false);
        Tab searchTab = new Tab("Search Logs", searchArea);
        searchTab.setClosable(false);

        TabPane tabs = new TabPane(modTab, searchTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // Toolbar: Export / Clear / Clear All. Enable/disable + size live in Settings.
        Button exportBtn = new Button("Export…");
        exportBtn.setTooltip(new Tooltip("Save the visible tab to a file"));
        exportBtn.setOnAction(e -> {
            boolean isMod = tabs.getSelectionModel().getSelectedItem() == modTab;
            String content = isMod ? logger.modificationLogProperty().get()
                                   : logger.searchLogProperty().get();
            exportToFile(content, isMod ? "modifications" : "searches");
        });

        Button clearCurrent = new Button("Clear");
        clearCurrent.setTooltip(new Tooltip("Clear the visible tab"));
        clearCurrent.setOnAction(e -> {
            if (tabs.getSelectionModel().getSelectedItem() == modTab) {
                logger.clearModificationLog();
            } else {
                logger.clearSearchLog();
            }
        });

        Button clearAll = new Button("Clear All");
        clearAll.setOnAction(e -> {
            logger.clearModificationLog();
            logger.clearSearchLog();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕"); // ✕
        closeBtn.setTooltip(new Tooltip("Hide the log pane"));
        closeBtn.setStyle("-fx-font-size: 11; -fx-padding: 2 8 2 8;");
        closeBtn.setOnAction(e -> onClose.run());

        HBox toolbar = new HBox(8, spacer, exportBtn, clearCurrent, clearAll,
                new Separator(javafx.geometry.Orientation.VERTICAL), closeBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: -color-bg-subtle; "
                + "-fx-border-color: -color-border-default; -fx-border-width: 1 0 0 0;");

        root.getChildren().addAll(tabs, toolbar);
        root.setMinHeight(140);
        root.setPrefHeight(220);
    }

    public VBox getRoot() { return root; }

    private void exportToFile(String content, String baseName) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Log");
        fc.setInitialFileName(baseName + "-" + LocalDate.now() + ".log");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log files", "*.log"),
                new FileChooser.ExtensionFilter("Text files", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File out = fc.showSaveDialog(root.getScene() != null ? root.getScene().getWindow() : null);
        if (out == null) return;
        try {
            Files.writeString(out.toPath(), content == null ? "" : content);
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR,
                    "Failed to export log: " + ex.getMessage()).showAndWait();
        }
    }

    private static TextArea buildLogArea(javafx.beans.property.StringProperty source) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.setFont(Font.font("monospaced", 12));
        area.textProperty().bind(source);
        // Auto-scroll to bottom when new content arrives.
        source.addListener((o, a, b) -> area.positionCaret(b == null ? 0 : b.length()));
        return area;
    }
}
