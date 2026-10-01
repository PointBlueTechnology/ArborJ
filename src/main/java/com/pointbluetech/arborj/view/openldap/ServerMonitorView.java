package com.pointbluetech.arborj.view.openldap;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.SearchScope;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Map;

/**
 * Dialog displaying OpenLDAP cn=monitor entries in a tree structure.
 */
public class ServerMonitorView extends Dialog<Void> {

    private final MainController controller;

    private final TreeView<MonitorItem> treeView = new TreeView<>();
    private final TextArea detailArea = new TextArea();
    private final Button refreshButton = new Button("Refresh");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final Label statusLabel = new Label();

    public ServerMonitorView(MainController controller) {
        this(controller, null);
    }

    public ServerMonitorView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("OpenLDAP Server Monitor");
        setResizable(true);
        if (owner != null) initOwner(owner);

        Font mono = Font.font("monospaced", 12);
        detailArea.setFont(mono);
        detailArea.setEditable(false);
        detailArea.setPrefRowCount(10);
        detailArea.setWrapText(true);
        detailArea.setPromptText("Select an entry to view its attributes.");

        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(MonitorItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });

        treeView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.getValue() != null) {
                        showDetail(newVal.getValue());
                    }
                });

        progressIndicator.setPrefSize(20, 20);
        progressIndicator.setVisible(false);

        HBox toolbar = new HBox(8, refreshButton, progressIndicator, statusLabel);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        SplitPane splitPane = new SplitPane(treeView, detailArea);
        splitPane.setDividerPositions(0.5);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        VBox content = new VBox(8, toolbar, splitPane);
        content.setPadding(new Insets(12));
        content.setPrefWidth(800);
        content.setPrefHeight(550);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        refreshButton.setOnAction(e -> loadMonitorData());

        // Load on open
        loadMonitorData();
    }

    private void loadMonitorData() {
        refreshButton.setDisable(true);
        progressIndicator.setVisible(true);
        treeView.setRoot(null);
        detailArea.clear();
        statusLabel.setText("Loading monitor data...");
        statusLabel.setStyle("-fx-text-fill: gray;");

        Thread.startVirtualThread(() -> {
            try {
                // Search the entire cn=monitor subtree
                List<LDAPEntry> entries = controller.getLdapService().search(
                        "cn=monitor", SearchScope.SUBTREE,
                        "(objectClass=*)", "*", "+");

                // Build tree
                TreeItem<MonitorItem> root = new TreeItem<>(
                        new MonitorItem("cn=monitor", "cn=monitor", Map.of()));
                root.setExpanded(true);

                for (LDAPEntry entry : entries) {
                    String dn = entry.getDn();
                    if (dn.equalsIgnoreCase("cn=monitor")) {
                        // Set root attributes
                        root.setValue(new MonitorItem("cn=monitor", dn, entry.getAttributes()));
                        continue;
                    }

                    String displayName = extractRDN(dn);
                    MonitorItem item = new MonitorItem(displayName, dn, entry.getAttributes());
                    TreeItem<MonitorItem> treeItem = new TreeItem<>(item);

                    // Determine parent: find the closest ancestor already in the tree
                    TreeItem<MonitorItem> parent = findParent(root, dn);
                    if (parent != null) {
                        parent.getChildren().add(treeItem);
                    } else {
                        root.getChildren().add(treeItem);
                    }
                }

                Platform.runLater(() -> {
                    treeView.setRoot(root);
                    refreshButton.setDisable(false);
                    progressIndicator.setVisible(false);
                    statusLabel.setText(entries.size() + " monitor entries loaded.");
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

    private TreeItem<MonitorItem> findParent(TreeItem<MonitorItem> root, String childDN) {
        // The parent DN is everything after the first comma
        int commaIdx = childDN.indexOf(',');
        if (commaIdx < 0) return root;

        String parentDN = childDN.substring(commaIdx + 1).trim();
        return findByDN(root, parentDN);
    }

    private TreeItem<MonitorItem> findByDN(TreeItem<MonitorItem> node, String dn) {
        if (node.getValue() != null && node.getValue().dn().equalsIgnoreCase(dn)) {
            return node;
        }
        for (TreeItem<MonitorItem> child : node.getChildren()) {
            TreeItem<MonitorItem> found = findByDN(child, dn);
            if (found != null) return found;
        }
        return null;
    }

    private void showDetail(MonitorItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("DN: ").append(item.dn()).append("\n\n");

        for (var entry : item.attributes().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\n");
            }
        }

        detailArea.setText(sb.toString());
    }

    private static String extractRDN(String dn) {
        if (dn == null || dn.isEmpty()) return "";
        int commaIdx = dn.indexOf(',');
        return commaIdx > 0 ? dn.substring(0, commaIdx) : dn;
    }

    public record MonitorItem(String displayName, String dn,
                               Map<String, List<String>> attributes) {}
}
