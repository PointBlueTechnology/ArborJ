package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.service.LDIFImporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * LDIF import dialog with file chooser, options, and progress display.
 */
public class LDIFImportDialog extends Dialog<Void> {

    private final MainController controller;
    private final TextArea logArea;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final CheckBox continueOnErrorCb;
    private volatile boolean cancelled = false;
    private volatile boolean importing = false;

    public LDIFImportDialog(MainController controller) {
        this(controller, null);
    }

    public LDIFImportDialog(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;
        setTitle("Import LDIF");
        setResizable(true);
        if (owner != null) initOwner(owner);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setPrefWidth(550);
        content.setPrefHeight(450);

        // Options
        continueOnErrorCb = new CheckBox("Continue on errors");
        continueOnErrorCb.setSelected(true);

        // File selection
        Label fileLabel = new Label("No file selected");
        Button chooseBtn = new Button("Choose LDIF File...");
        final File[] selectedFile = {null};

        HBox fileRow = new HBox(8, chooseBtn, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        // Progress
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 12));

        // Log
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setFont(Font.font("monospaced", 11));
        logArea.setWrapText(true);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        content.getChildren().addAll(fileRow, continueOnErrorCb, new Separator(),
                progressBar, statusLabel, logArea);

        getDialogPane().setContent(content);

        // Buttons
        ButtonType importType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(importType, cancelType);

        Button importBtn = (Button) getDialogPane().lookupButton(importType);
        importBtn.setDisable(true);

        // Now wire up file chooser (importBtn is initialized)
        chooseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select LDIF File");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("LDIF Files", "*.ldif", "*.ldf"),
                    new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            File file = fc.showOpenDialog(getDialogPane().getScene().getWindow());
            if (file != null) {
                selectedFile[0] = file;
                fileLabel.setText(file.getName());
                importBtn.setDisable(false);
            }
        });

        importBtn.setOnAction(e -> {
            e.consume();
            if (selectedFile[0] != null) {
                runImport(selectedFile[0], importBtn);
            }
        });

        Button cancelBtn = (Button) getDialogPane().lookupButton(cancelType);
        cancelBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (importing) {
                cancelled = true;
                statusLabel.setText("Cancelling...");
                e.consume();
            }
        });
    }

    private void runImport(File file, Button importBtn) {
        importBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        logArea.clear();
        cancelled = false;
        importing = true;
        boolean continueOnError = continueOnErrorCb.isSelected();

        Thread.startVirtualThread(() -> {
            try {
                LDIFImporter importer = new LDIFImporter(controller.getLdapService());

                Platform.runLater(() -> {
                    statusLabel.setText("Importing " + file.getName() + "...");
                    logArea.appendText("Importing " + file.getName() + "...\n\n");
                });

                var result = importer.streamingImport(file.toPath(), continueOnError,
                        msg -> Platform.runLater(() -> {
                            statusLabel.setText(msg);
                            logArea.appendText(msg + "\n");
                        }),
                        () -> cancelled);

                Platform.runLater(() -> {
                    importing = false;
                    progressBar.setProgress(1.0);
                    String summary = result.cancelled()
                            ? String.format("\nImport cancelled: %d succeeded, %d failed",
                                    result.successCount(), result.errorCount())
                            : String.format("\nImport complete: %d succeeded, %d failed",
                                    result.successCount(), result.errorCount());
                    statusLabel.setText(summary);
                    logArea.appendText(summary + "\n");

                    if (!result.errors().isEmpty()) {
                        logArea.appendText("\nErrors:\n");
                        for (String err : result.errors()) {
                            logArea.appendText("  " + err + "\n");
                        }
                    }

                    importBtn.setText("Import Again");
                    importBtn.setDisable(false);
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    importing = false;
                    progressBar.setProgress(0);
                    statusLabel.setText("Import failed: " + ex.getMessage());
                    logArea.appendText("ERROR: " + ex.getMessage() + "\n");
                    importBtn.setDisable(false);
                });
            }
        });
    }
}
