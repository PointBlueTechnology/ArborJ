package com.pointbluetech.arborj.view.edir;

import com.pointbluetech.arborj.controller.MainController;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * eDirectory Replication Status using NDS extended operations.
 * Uses listReplicas + getReplicaInfo for accurate data.
 */
public class ReplicationStatusView extends Dialog<Void> {

    private final MainController controller;
    private final VBox cardList = new VBox(10);
    private final Button refreshButton = new Button("Refresh");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final Label statusLabel = new Label();

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm:ss a").withZone(ZoneId.systemDefault());

    public ReplicationStatusView(MainController controller) {
        this(controller, null);
    }

    public ReplicationStatusView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;
        setTitle("eDirectory Replication Status");
        setResizable(true);
        if (owner != null) initOwner(owner);

        // Header
        Label titleLabel = new Label("eDirectory Replication Status");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");
        Label subtitleLabel = new Label("Partition replica status for the connected eDirectory server");
        subtitleLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        progressIndicator.setPrefSize(18, 18);
        progressIndicator.setVisible(false);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox headerRow = new HBox(8, new VBox(2, titleLabel, subtitleLabel), headerSpacer,
                refreshButton, progressIndicator);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(12));
        headerRow.setStyle("-fx-background-color: -color-bg-subtle;");

        ScrollPane scrollPane = new ScrollPane(cardList);
        scrollPane.setFitToWidth(true);
        cardList.setPadding(new Insets(12));

        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");
        statusLabel.setPadding(new Insets(4, 12, 4, 12));

        VBox content = new VBox(headerRow, new Separator(), statusLabel, scrollPane);
        content.setPrefWidth(750);
        content.setPrefHeight(550);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("Done", ButtonBar.ButtonData.CANCEL_CLOSE));

        refreshButton.setOnAction(e -> loadReplicationData());
        loadReplicationData();
    }

    private void loadReplicationData() {
        refreshButton.setDisable(true);
        progressIndicator.setVisible(true);
        cardList.getChildren().clear();
        statusLabel.setText("Loading replication data...");

        Thread.startVirtualThread(() -> {
            try {
                var ldap = controller.getLdapService();

                // Step 1: Find server DNs
                List<String> serverDNs = ldap.findServerDNsForReplicas();
                if (serverDNs.isEmpty()) {
                    Platform.runLater(() -> showError("Could not determine server DN from Root DSE"));
                    return;
                }

                // Step 2: List partitions
                List<String> result = ldap.listReplicas(serverDNs);
                if (result.isEmpty()) {
                    Platform.runLater(() -> showError("No partitions found via extended operations"));
                    return;
                }

                String workingServerDN = result.getFirst();
                List<String> partitionDNs = result.subList(1, result.size());

                // Step 3: Get replica info for each partition
                List<PartitionCard> cards = new ArrayList<>();
                for (String partDN : partitionDNs) {
                    try {
                        byte[] data = ldap.getReplicaInfo(workingServerDN, partDN);
                        if (data != null) {
                            int[] info = ldap.parseReplicaInfoResponse(data);
                            // info = [replicaState, modTime, replicaNumber, replicaType]
                            cards.add(new PartitionCard(partDN, info[3], info[0], info[2], info[1]));
                        } else {
                            cards.add(new PartitionCard(partDN, -1, -1, -1, 0));
                        }
                    } catch (Exception e) {
                        cards.add(new PartitionCard(partDN, -1, -1, -1, 0));
                    }
                }

                Platform.runLater(() -> {
                    statusLabel.setText(cards.size() + " partition" + (cards.size() != 1 ? "s" : ""));
                    for (PartitionCard card : cards) {
                        cardList.getChildren().add(card.build());
                    }
                    refreshButton.setDisable(false);
                    progressIndicator.setVisible(false);
                });

            } catch (Exception ex) {
                Platform.runLater(() -> showError(ex.getMessage()));
            }
        });
    }

    private void showError(String msg) {
        refreshButton.setDisable(false);
        progressIndicator.setVisible(false);
        statusLabel.setText("Error: " + msg);
        statusLabel.setStyle("-fx-text-fill: red;");

        Label errorLabel = new Label(msg);
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        Label hint = new Label("Reading partition replica attributes may require admin-level rights.");
        hint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        hint.setWrapText(true);
        cardList.getChildren().addAll(errorLabel, hint);
    }

    // --- Partition Card ---

    private static class PartitionCard {
        final String dn;
        final int replicaType;
        final int replicaState;
        final int replicaNumber;
        final int modificationTime; // NDS epoch seconds

        PartitionCard(String dn, int replicaType, int replicaState, int replicaNumber, int modTime) {
            this.dn = dn;
            this.replicaType = replicaType;
            this.replicaState = replicaState;
            this.replicaNumber = replicaNumber;
            this.modificationTime = modTime;
        }

        String displayName() {
            if (dn == null || dn.isEmpty()) return "[Root]";
            String first = dn.contains(",") ? dn.substring(0, dn.indexOf(',')) : dn;
            int eq = first.indexOf('=');
            return eq > 0 ? first.substring(eq + 1) : first;
        }

        String typeLabel() {
            return switch (replicaType) {
                case 0 -> "Master";
                case 1 -> "Read/Write";
                case 2 -> "Read-Only";
                case 3 -> "Subordinate Reference";
                default -> "Unknown";
            };
        }

        String stateLabel() {
            int core = replicaState & 0x7FFF;
            boolean syncing = (replicaState & 0x8000) != 0;
            String label = switch (core) {
                case 0 -> "On";
                case 1 -> "New Replica";
                case 2 -> "Dying";
                case 3 -> "Locked";
                case 6 -> "Transition On";
                case 0x0D -> "Federated";
                default -> syncing ? "On (Syncing)" : "On";
            };
            if (syncing && core == 0) label = "On (Syncing)";
            return label;
        }

        boolean isOK() {
            int core = replicaState & 0x7FFF;
            return core == 0 || core == 0x0D;
        }

        javafx.scene.Node build() {
            VBox card = new VBox(6);
            card.setPadding(new Insets(10));
            card.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; "
                    + "-fx-border-color: -color-border-default; -fx-border-radius: 6;");

            // Header: status dot + name + OK badge
            Label dot = new Label("\u25CF");
            dot.setTextFill(isOK() ? Color.GREEN : Color.ORANGE);
            Label nameLabel = new Label(displayName());
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label okBadge = new Label(isOK() ? "OK" : stateLabel());
            okBadge.setTextFill(isOK() ? Color.GREEN : Color.ORANGE);
            okBadge.setStyle("-fx-font-weight: bold;");
            HBox header = new HBox(6, dot, nameLabel, spacer, okBadge);
            header.setAlignment(Pos.CENTER_LEFT);

            // Details grid
            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(12);
            grid.setVgap(3);
            grid.setPadding(new Insets(0, 0, 0, 20));

            int row = 0;
            addDetailRow(grid, row++, "Replica Type", typeLabel());
            addDetailRow(grid, row++, "Replica State", stateLabel());
            if (replicaNumber >= 0) {
                addDetailRow(grid, row++, "Replica Number", String.valueOf(replicaNumber));
            }
            if (modificationTime > 0) {
                String dateStr = DATE_FMT.format(Instant.ofEpochSecond(modificationTime));
                addDetailRow(grid, row++, "Last Modified", dateStr);

                // Relative time
                long secsAgo = Instant.now().getEpochSecond() - modificationTime;
                String relative;
                if (secsAgo < 60) relative = secsAgo + "s ago";
                else if (secsAgo < 3600) relative = (secsAgo / 60) + "m ago";
                else if (secsAgo < 86400) relative = (secsAgo / 3600) + "h " + ((secsAgo % 3600) / 60) + "m ago";
                else relative = (secsAgo / 86400) + "d ago";

                Label syncLabel = new Label(dateStr);
                syncLabel.setStyle("-fx-font-family: monospaced; -fx-font-size: 12;");
                Label relLabel = new Label(relative);
                Color relColor = secsAgo < 120 ? Color.GREEN : secsAgo < 600 ? Color.ORANGE : Color.RED;
                relLabel.setTextFill(relColor);
                relLabel.setStyle("-fx-font-size: 11;");
                VBox syncBox = new VBox(1, syncLabel, relLabel);
                addDetailRowNode(grid, row++, "Last Sync", syncBox);
            }

            // DN footer
            Label dnLabel = new Label(dn.isEmpty() ? "[Tree Root]" : dn);
            dnLabel.setStyle("-fx-font-family: monospaced; -fx-font-size: 10; -fx-text-fill: -color-fg-muted;");

            card.getChildren().addAll(header, grid, dnLabel);
            return card;
        }

        private void addDetailRow(javafx.scene.layout.GridPane grid, int row, String label, String value) {
            Label l = new Label(label);
            l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");
            l.setMinWidth(110);
            Label v = new Label(value);
            v.setStyle("-fx-font-family: monospaced; -fx-font-size: 12;");
            grid.add(l, 0, row);
            grid.add(v, 1, row);
        }

        private void addDetailRowNode(javafx.scene.layout.GridPane grid, int row, String label, javafx.scene.Node node) {
            Label l = new Label(label);
            l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");
            l.setMinWidth(110);
            grid.add(l, 0, row);
            grid.add(node, 1, row);
        }
    }
}
