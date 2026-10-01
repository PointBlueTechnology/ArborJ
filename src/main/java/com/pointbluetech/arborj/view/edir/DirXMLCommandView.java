package com.pointbluetech.arborj.view.edir;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.SearchScope;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.List;

/**
 * Dialog for submitting DirXML commands to an eDirectory driver.
 */
public class DirXMLCommandView extends Dialog<Void> {

    private final MainController controller;

    private final ComboBox<String> driverCombo = new ComboBox<>();
    private final TextArea commandArea = new TextArea();
    private final TextArea responseArea = new TextArea();
    private final Button submitButton = new Button("Submit");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final Label statusLabel = new Label();

    public DirXMLCommandView(MainController controller) {
        this(controller, null);
    }

    public DirXMLCommandView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("DirXML Command");
        setResizable(true);
        if (owner != null) initOwner(owner);

        Font mono = Font.font("monospaced", 12);

        // Driver selection
        driverCombo.setPromptText("Select a DirXML Driver...");
        driverCombo.setMaxWidth(Double.MAX_VALUE);

        commandArea.setFont(mono);
        commandArea.setPrefRowCount(12);
        commandArea.setPromptText("Enter XML command document here...");
        commandArea.setWrapText(false);

        responseArea.setFont(mono);
        responseArea.setPrefRowCount(12);
        responseArea.setEditable(false);
        responseArea.setWrapText(false);
        responseArea.setPromptText("Response will appear here...");

        progressIndicator.setPrefSize(20, 20);
        progressIndicator.setVisible(false);

        HBox submitRow = new HBox(8, submitButton, progressIndicator, statusLabel);

        VBox content = new VBox(8,
                new Label("Driver:"), driverCombo,
                new Separator(),
                new Label("XML Command:"), commandArea,
                submitRow,
                new Separator(),
                new Label("Response:"), responseArea
        );
        content.setPadding(new Insets(12));
        content.setPrefWidth(700);
        content.setPrefHeight(650);

        VBox.setVgrow(commandArea, Priority.ALWAYS);
        VBox.setVgrow(responseArea, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        submitButton.setOnAction(e -> submitCommand());

        // Populate drivers on open
        loadDrivers();
    }

    private void loadDrivers() {
        driverCombo.getItems().clear();
        statusLabel.setText("Loading drivers...");
        statusLabel.setStyle("-fx-text-fill: gray;");

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
                                driverCombo.getItems().add(entry.getDn());
                            }
                        });
                    } catch (Exception ignored) {
                        // Some contexts may not be searchable
                    }
                }

                Platform.runLater(() -> {
                    if (driverCombo.getItems().isEmpty()) {
                        statusLabel.setText("No DirXML drivers found.");
                    } else {
                        statusLabel.setText(driverCombo.getItems().size() + " driver(s) found.");
                        driverCombo.getSelectionModel().selectFirst();
                    }
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error loading drivers: " + ex.getMessage());
                    statusLabel.setStyle("-fx-text-fill: red;");
                });
            }
        });
    }

    private void submitCommand() {
        String driverDN = driverCombo.getValue();
        String xmlCommand = commandArea.getText().trim();

        if (driverDN == null || driverDN.isEmpty()) {
            statusLabel.setText("Please select a driver.");
            statusLabel.setStyle("-fx-text-fill: red;");
            return;
        }
        if (xmlCommand.isEmpty()) {
            statusLabel.setText("Please enter an XML command.");
            statusLabel.setStyle("-fx-text-fill: red;");
            return;
        }

        submitButton.setDisable(true);
        progressIndicator.setVisible(true);
        responseArea.clear();
        statusLabel.setText("Submitting...");
        statusLabel.setStyle("-fx-text-fill: gray;");

        Thread.startVirtualThread(() -> {
            try {
                String response = controller.getLdapService()
                        .submitDirXMLCommand(driverDN, xmlCommand);

                Platform.runLater(() -> {
                    responseArea.setText(response);
                    submitButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Command completed.");
                    statusLabel.setStyle("-fx-text-fill: green;");
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    responseArea.setText("Error: " + ex.getMessage());
                    submitButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Command failed.");
                    statusLabel.setStyle("-fx-text-fill: red;");
                });
            }
        });
    }
}
