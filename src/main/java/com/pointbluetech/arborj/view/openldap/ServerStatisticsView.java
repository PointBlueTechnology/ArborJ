package com.pointbluetech.arborj.view.openldap;

import com.pointbluetech.arborj.controller.MainController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog showing OpenLDAP server statistics from cn=monitor.
 */
public class ServerStatisticsView extends Dialog<Void> {

    private final MainController controller;

    private final GridPane statsGrid = new GridPane();
    private final Button refreshButton = new Button("Refresh");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final Label statusLabel = new Label();

    // Statistic labels stored for update
    private final Map<String, Label> valueLabels = new LinkedHashMap<>();

    /** Monitor sub-entries to fetch and their display names. */
    private static final String[][] MONITOR_ENTRIES = {
            {"cn=monitor", "monitoredInfo", "Server Info"},
            {"cn=monitor", "description", "Description"},
            {"cn=Connections,cn=monitor", "monitorCounter", "Total Connections"},
            {"cn=Current,cn=Connections,cn=monitor", "monitorCounter", "Current Connections"},
            {"cn=Bytes,cn=Statistics,cn=monitor", "monitorCounter", "Bytes Sent"},
            {"cn=PDU,cn=Statistics,cn=monitor", "monitorCounter", "PDUs Sent"},
            {"cn=Entries,cn=Statistics,cn=monitor", "monitorCounter", "Entries Sent"},
            {"cn=Referrals,cn=Statistics,cn=monitor", "monitorCounter", "Referrals Sent"},
            {"cn=Bind,cn=Operations,cn=monitor", "monitorOpInitiated", "Bind Ops Initiated"},
            {"cn=Bind,cn=Operations,cn=monitor", "monitorOpCompleted", "Bind Ops Completed"},
            {"cn=Search,cn=Operations,cn=monitor", "monitorOpInitiated", "Search Ops Initiated"},
            {"cn=Search,cn=Operations,cn=monitor", "monitorOpCompleted", "Search Ops Completed"},
            {"cn=Modify,cn=Operations,cn=monitor", "monitorOpInitiated", "Modify Ops Initiated"},
            {"cn=Modify,cn=Operations,cn=monitor", "monitorOpCompleted", "Modify Ops Completed"},
            {"cn=Add,cn=Operations,cn=monitor", "monitorOpInitiated", "Add Ops Initiated"},
            {"cn=Add,cn=Operations,cn=monitor", "monitorOpCompleted", "Add Ops Completed"},
            {"cn=Delete,cn=Operations,cn=monitor", "monitorOpInitiated", "Delete Ops Initiated"},
            {"cn=Delete,cn=Operations,cn=monitor", "monitorOpCompleted", "Delete Ops Completed"},
            {"cn=Threads,cn=monitor", "monitoredInfo", "Threads"},
            {"cn=Max,cn=Threads,cn=monitor", "monitoredInfo", "Max Threads"},
            {"cn=Start,cn=Time,cn=monitor", "monitorTimestamp", "Start Time"},
            {"cn=Current,cn=Time,cn=monitor", "monitorTimestamp", "Current Time"},
            {"cn=Uptime,cn=Time,cn=monitor", "monitoredInfo", "Uptime (seconds)"},
    };

    public ServerStatisticsView(MainController controller) {
        this(controller, null);
    }

    public ServerStatisticsView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("OpenLDAP Server Statistics");
        setResizable(true);
        if (owner != null) initOwner(owner);

        // Build the statistics grid
        statsGrid.setHgap(12);
        statsGrid.setVgap(6);
        statsGrid.setPadding(new Insets(12));

        Font mono = Font.font("monospaced", 12);

        ColumnConstraints nameCol = new ColumnConstraints();
        nameCol.setPrefWidth(200);
        ColumnConstraints valCol = new ColumnConstraints();
        valCol.setHgrow(Priority.ALWAYS);
        statsGrid.getColumnConstraints().addAll(nameCol, valCol);

        for (int i = 0; i < MONITOR_ENTRIES.length; i++) {
            String displayName = MONITOR_ENTRIES[i][2];

            Label nameLabel = new Label(displayName + ":");
            nameLabel.setStyle("-fx-font-weight: bold;");

            Label valLabel = new Label("--");
            valLabel.setFont(mono);
            valLabel.setWrapText(true);
            valLabel.setMaxWidth(Double.MAX_VALUE);

            statsGrid.add(nameLabel, 0, i);
            statsGrid.add(valLabel, 1, i);
            valueLabels.put(displayName, valLabel);
        }

        progressIndicator.setPrefSize(20, 20);
        progressIndicator.setVisible(false);

        HBox toolbar = new HBox(8, refreshButton, progressIndicator, statusLabel);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        ScrollPane scrollPane = new ScrollPane(statsGrid);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox content = new VBox(8, toolbar, scrollPane);
        content.setPadding(new Insets(12));
        content.setPrefWidth(550);
        content.setPrefHeight(600);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        refreshButton.setOnAction(e -> loadStatistics());

        // Load on open
        loadStatistics();
    }

    private void loadStatistics() {
        refreshButton.setDisable(true);
        progressIndicator.setVisible(true);
        statusLabel.setText("Loading statistics...");
        statusLabel.setStyle("-fx-text-fill: gray;");

        // Reset all values
        for (Label label : valueLabels.values()) {
            label.setText("--");
        }

        Thread.startVirtualThread(() -> {
            // Cache fetched DNs to avoid re-fetching
            Map<String, Map<String, List<String>>> cache = new LinkedHashMap<>();

            try {
                for (String[] spec : MONITOR_ENTRIES) {
                    String dn = spec[0];
                    String attr = spec[1];
                    String displayName = spec[2];

                    try {
                        Map<String, List<String>> attrs = cache.computeIfAbsent(dn, d -> {
                            try {
                                return controller.getLdapService().fetchAttributes(d, true);
                            } catch (Exception ex) {
                                return Map.of();
                            }
                        });

                        String value = findValue(attrs, attr);
                        Platform.runLater(() -> {
                            Label label = valueLabels.get(displayName);
                            if (label != null) {
                                label.setText(value != null ? value : "(not available)");
                            }
                        });
                    } catch (Exception ignored) {
                        // Individual entry failures are non-fatal
                    }
                }

                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Statistics loaded.");
                    statusLabel.setStyle("");
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setStyle("-fx-text-fill: red;");
                });
            }
        });
    }

    private static String findValue(Map<String, List<String>> attrs, String attrName) {
        // Direct lookup
        List<String> values = attrs.get(attrName);
        if (values != null && !values.isEmpty()) {
            return String.join(", ", values);
        }
        // Case-insensitive fallback
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(attrName) && !entry.getValue().isEmpty()) {
                return String.join(", ", entry.getValue());
            }
        }
        return null;
    }
}
