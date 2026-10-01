package com.pointbluetech.arborj.view.ad;

import com.pointbluetech.arborj.controller.MainController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Dialog displaying Active Directory group membership as a tree,
 * with lazy expansion to show nested/transitive group memberships.
 */
public class ADGroupMembershipView extends Dialog<Void> {

    private final MainController controller;
    private final String dn;
    private final TreeView<String> treeView;

    public ADGroupMembershipView(MainController controller, String dn) {
        this(controller, dn, null);
    }

    public ADGroupMembershipView(MainController controller, String dn, javafx.stage.Window owner) {
        this.controller = controller;
        this.dn = dn;

        setTitle("Group Membership");
        setHeaderText(dn);
        setResizable(true);
        if (owner != null) initOwner(owner);

        treeView = new TreeView<>();
        treeView.setShowRoot(true);
        treeView.setCellFactory(tv -> new GroupTreeCell());
        treeView.setPrefWidth(600);
        treeView.setPrefHeight(450);

        // Build root node from the target DN
        String rootLabel = extractCN(dn);
        TreeItem<String> root = new TreeItem<>(rootLabel + "  [" + dn + "]");
        root.setExpanded(true);
        treeView.setRoot(root);

        // Placeholder while loading
        root.getChildren().add(new TreeItem<>("Loading..."));

        VBox content = new VBox(8, treeView);
        content.setPadding(new Insets(12));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        loadMemberOf(root, dn);
    }

    /**
     * Fetch the memberOf attribute for a DN and populate the tree item's children.
     * Each group child gets a placeholder so it can be lazily expanded.
     */
    private void loadMemberOf(TreeItem<String> parentItem, String targetDN) {
        Thread.ofVirtual().start(() -> {
            try {
                Map<String, List<String>> attrs = controller.getLdapService()
                        .fetchAttributes(targetDN, false);

                List<String> memberOf = findAttribute(attrs, "memberOf");

                Platform.runLater(() -> {
                    parentItem.getChildren().clear();

                    if (memberOf == null || memberOf.isEmpty()) {
                        parentItem.getChildren().add(new TreeItem<>("(no group memberships)"));
                        return;
                    }

                    for (String groupDN : memberOf) {
                        String label = extractCN(groupDN) + "  [" + groupDN + "]";
                        TreeItem<String> groupItem = new TreeItem<>(label);
                        // Add a placeholder child so the expand arrow appears
                        groupItem.getChildren().add(new TreeItem<>("Loading..."));
                        parentItem.getChildren().add(groupItem);
                    }

                    // Set up lazy expansion for each group item
                    for (TreeItem<String> child : parentItem.getChildren()) {
                        setupLazyExpansion(child);
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    parentItem.getChildren().clear();
                    parentItem.getChildren().add(new TreeItem<>("Error: " + e.getMessage()));
                });
            }
        });
    }

    /**
     * Attach a listener to a tree item so that when it is first expanded,
     * its nested group memberships are loaded.
     */
    private void setupLazyExpansion(TreeItem<String> item) {
        item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && item.getChildren().size() == 1
                    && "Loading...".equals(item.getChildren().getFirst().getValue())) {
                String groupDN = extractDNFromLabel(item.getValue());
                if (groupDN != null) {
                    loadMemberOf(item, groupDN);
                }
            }
        });
    }

    /**
     * Extract the CN from a DN string. Returns the full DN if no CN is found.
     */
    private String extractCN(String dn) {
        if (dn == null) return "";
        // Look for CN= at the start of the DN
        String upper = dn.toUpperCase();
        if (upper.startsWith("CN=")) {
            int commaIdx = dn.indexOf(',');
            return commaIdx > 0 ? dn.substring(3, commaIdx) : dn.substring(3);
        }
        // Fall back to first RDN value
        int eqIdx = dn.indexOf('=');
        int commaIdx = dn.indexOf(',');
        if (eqIdx > 0) {
            return commaIdx > eqIdx ? dn.substring(eqIdx + 1, commaIdx) : dn.substring(eqIdx + 1);
        }
        return dn;
    }

    /**
     * Extract the DN from a tree item label formatted as "CN  [DN]".
     */
    private String extractDNFromLabel(String label) {
        if (label == null) return null;
        int start = label.lastIndexOf('[');
        int end = label.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return label.substring(start + 1, end);
        }
        return null;
    }

    /**
     * Case-insensitive attribute lookup.
     */
    private List<String> findAttribute(Map<String, List<String>> attrs, String name) {
        List<String> values = attrs.get(name);
        if (values != null) return values;
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Custom tree cell that displays group names without extra decoration.
     */
    private static class GroupTreeCell extends TreeCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item);
        }
    }
}
