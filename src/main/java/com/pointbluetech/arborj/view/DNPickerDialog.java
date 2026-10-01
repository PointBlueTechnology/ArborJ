package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.SearchScope;
import com.pointbluetech.arborj.util.DirectoryTopology;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modal dialog for browsing and selecting a DN from the LDAP directory.
 * Used by the New Entry wizard for parent container selection and anywhere
 * else a DN needs to be picked.
 */
public class DNPickerDialog {

    private final MainController controller;
    private final Stage stage;
    private final TreeView<DNNode> treeView;
    private final TextField dnField;
    private final TextField searchField;
    private final ListView<String> searchResultsList;

    private String selectedDN;

    public DNPickerDialog(MainController controller, String initialDN) {
        this(controller, initialDN, null);
    }

    public DNPickerDialog(MainController controller, String initialDN, javafx.stage.Window owner) {
        this.controller = controller;
        this.stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Select DN");
        stage.setResizable(true);

        // --- DN field (initialize early so listeners can reference it) ---
        dnField = new TextField(initialDN != null ? initialDN : "");

        // --- Tree view (left side) ---
        TreeItem<DNNode> rootItem = new TreeItem<>();
        rootItem.setExpanded(true);

        treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new DNTreeCell());
        treeView.setPrefWidth(350);

        // Update DN field on selection
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                dnField.setText(newVal.getValue().dn);
            }
        });

        // Load root nodes
        loadRootNodes(rootItem);

        // --- Search bar (top) ---
        searchField = new TextField();
        searchField.setPromptText("Search filter, e.g. (cn=admin*)");

        Button searchBtn = new Button("Search");
        HBox searchBar = new HBox(6, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBar.setPadding(new Insets(0, 0, 6, 0));

        // Search results list
        searchResultsList = new ListView<>();
        searchResultsList.setPrefHeight(120);
        searchResultsList.setVisible(false);
        searchResultsList.setManaged(false);

        searchResultsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                dnField.setText(newVal);
            }
        });

        searchBtn.setOnAction(e -> performSearch());
        searchField.setOnAction(e -> performSearch());

        // Left pane: search bar + tree + search results
        VBox leftPane = new VBox(4, searchBar, treeView, searchResultsList);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        dnField.setFont(Font.font("monospaced", 12));
        dnField.setPromptText("Enter or select a DN");

        Label dnLabel = new Label("Selected DN:");
        VBox rightPane = new VBox(6, dnLabel, dnField);
        rightPane.setPadding(new Insets(0, 0, 0, 12));
        rightPane.setPrefWidth(250);

        // --- Buttons (bottom) ---
        Button selectBtn = new Button("Select");
        selectBtn.setDefaultButton(true);
        selectBtn.setOnAction(e -> {
            selectedDN = dnField.getText();
            if (selectedDN != null && selectedDN.isBlank()) {
                selectedDN = null;
            }
            stage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> {
            selectedDN = null;
            stage.close();
        });

        HBox bottomButtons = new HBox(8, cancelBtn, selectBtn);
        bottomButtons.setAlignment(Pos.CENTER_RIGHT);
        bottomButtons.setPadding(new Insets(8, 12, 8, 12));

        // --- Layout ---
        HBox content = new HBox(0, leftPane, rightPane);
        content.setPadding(new Insets(12));
        HBox.setHgrow(leftPane, Priority.ALWAYS);

        VBox root = new VBox(content, new Separator(), bottomButtons);
        VBox.setVgrow(content, Priority.ALWAYS);

        Scene scene = new Scene(root, 680, 480);
        stage.setScene(scene);
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    /**
     * Returns the selected DN, or null if the dialog was cancelled.
     */
    public String getSelectedDN() {
        return selectedDN;
    }

    // --- Root node loading ---

    private void loadRootNodes(TreeItem<DNNode> rootItem) {
        Thread.startVirtualThread(() -> {
            try {
                // Always root the picker at the top of the directory so users can
                // select DNs anywhere in the tree, not just under the profile's baseDN.
                // A one-level search of the root DSE is the real top of the DIT;
                // naming contexts only top it up with what that search cannot reach.
                // Listing every published context flat instead put eDirectory's
                // nested partition roots at the top as well as in their real place.
                Map<String, LDAPEntry> topLevelEntries = new LinkedHashMap<>();
                try {
                    for (LDAPEntry entry : controller.getLdapService().search("",
                            SearchScope.ONE_LEVEL, "(objectClass=*)",
                            DirectoryTopology.TOPOLOGY_ATTRS)) {
                        if (!entry.getDn().isEmpty()) {
                            topLevelEntries.put(entry.getDn(), entry);
                        }
                    }
                } catch (Exception ignored) {}

                Set<String> contexts = DirectoryTopology.selectRootDns(
                        controller.getLdapService().fetchNamingContexts(),
                        topLevelEntries.keySet());

                // For eDirectory, wrap the naming contexts under a synthetic "T=TreeName"
                // node so the user can select the tree root itself (used as the target
                // for tree-wide ACL / effective-rights queries).
                String treeName = null;
                try {
                    treeName = controller.getLdapService().fetchTreeName();
                } catch (Exception ignored) {}

                List<TreeItem<DNNode>> contextItems = new ArrayList<>();
                for (String ctx : contexts) {
                    if (ctx == null || ctx.isEmpty()) continue;
                    // Only a context the one-level search missed needs its own lookup.
                    LDAPEntry entry = topLevelEntries.get(ctx);
                    if (entry == null) entry = fetchTopologyEntry(ctx);

                    List<String> ocs = entry == null
                            ? List.of() : entry.getValues("objectClass");
                    DirectoryTopology.ChildCount count = entry == null
                            ? DirectoryTopology.ChildCount.UNKNOWN
                            : DirectoryTopology.readChildCount(entry);
                    boolean isLeaf = DirectoryTopology.isLeafNamingContext(
                            count, ocs, controller.getSchemaService().isContainer(ocs));

                    DNNode node = new DNNode(ctx, extractRDN(ctx), ocs, isLeaf);
                    contextItems.add(createTreeItem(node));
                }

                if (treeName != null && !treeName.isBlank()) {
                    DNNode treeRoot = new DNNode("T=" + treeName, treeName,
                            List.of("treeRoot"), false);
                    treeRoot.childrenLoaded = true;
                    TreeItem<DNNode> treeRootItem = new TreeItem<>(treeRoot);
                    treeRootItem.getChildren().setAll(contextItems);
                    treeRootItem.setExpanded(true);
                    Platform.runLater(() -> rootItem.getChildren().setAll(treeRootItem));
                } else {
                    Platform.runLater(() -> rootItem.getChildren().setAll(contextItems));
                }

            } catch (Exception e) {
                System.err.println("[DNPickerDialog] Failed to load root nodes: " + e.getMessage());
            }
        });
    }

    /** Read one entry's objectClass and child count, or null if unreadable. */
    private LDAPEntry fetchTopologyEntry(String dn) {
        try {
            var entries = controller.getLdapService().search(
                    dn, SearchScope.BASE, "(objectClass=*)", DirectoryTopology.TOPOLOGY_ATTRS);
            if (!entries.isEmpty()) return entries.getFirst();
        } catch (Exception ignored) {}
        return null;
    }

    // --- Lazy child loading ---

    private TreeItem<DNNode> createTreeItem(DNNode node) {
        TreeItem<DNNode> item = new TreeItem<>(node);

        if (!node.isLeaf) {
            // Add dummy child to show expansion arrow
            item.getChildren().add(new TreeItem<>());

            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (isExpanded && !node.childrenLoaded) {
                    loadChildren(item, node);
                }
            });
        }

        return item;
    }

    private void loadChildren(TreeItem<DNNode> parentItem, DNNode parentNode) {
        parentNode.childrenLoaded = true;

        Thread.startVirtualThread(() -> {
            try {
                List<LDAPEntry> entries = controller.getLdapService().search(
                        parentNode.dn, SearchScope.ONE_LEVEL,
                        "(objectClass=*)", DirectoryTopology.TOPOLOGY_ATTRS);

                List<TreeItem<DNNode>> children = new ArrayList<>();
                for (LDAPEntry entry : entries) {
                    String rdn = extractRDN(entry.getDn());
                    List<String> ocs = entry.getValues("objectClass");
                    // Schema alone used to decide this, so a container whose
                    // objectClass went unrecognised became an unopenable leaf.
                    boolean isLeaf = DirectoryTopology.isLeafEntry(
                            DirectoryTopology.readChildCount(entry),
                            controller.getSchemaService().isContainer(ocs));
                    DNNode child = new DNNode(entry.getDn(), rdn, ocs, isLeaf);
                    children.add(createTreeItem(child));
                }

                // Sort: containers first, then alphabetically
                children.sort((a, b) -> {
                    DNNode na = a.getValue();
                    DNNode nb = b.getValue();
                    if (na.isLeaf != nb.isLeaf) return na.isLeaf ? 1 : -1;
                    return na.rdn.compareToIgnoreCase(nb.rdn);
                });

                Platform.runLater(() -> parentItem.getChildren().setAll(children));

            } catch (Exception e) {
                System.err.println("[DNPickerDialog] Failed to load children of "
                        + parentNode.dn + ": " + e.getMessage());
            }
        });
    }

    // --- Search ---

    private void performSearch() {
        String filter = searchField.getText().trim();
        if (filter.isEmpty()) {
            searchResultsList.setVisible(false);
            searchResultsList.setManaged(false);
            return;
        }

        // Default to subtree search from tree selection or root
        String baseDN = "";
        TreeItem<DNNode> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel != null && sel.getValue() != null) {
            DNNode selNode = sel.getValue();
            // The synthetic "T=TreeName" root isn't a real LDAP base — search from "" instead.
            baseDN = selNode.objectClasses.contains("treeRoot") ? "" : selNode.dn;
        }

        final String searchBase = baseDN;

        Thread.startVirtualThread(() -> {
            try {
                List<LDAPEntry> results = controller.getLdapService().search(
                        searchBase, SearchScope.SUBTREE, filter, "objectClass");

                ObservableList<String> dns = FXCollections.observableArrayList();
                for (LDAPEntry entry : results) {
                    dns.add(entry.getDn());
                }

                Platform.runLater(() -> {
                    searchResultsList.setItems(dns);
                    searchResultsList.setVisible(true);
                    searchResultsList.setManaged(true);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    searchResultsList.setItems(FXCollections.observableArrayList(
                            "Search failed: " + e.getMessage()));
                    searchResultsList.setVisible(true);
                    searchResultsList.setManaged(true);
                });
            }
        });
    }

    // --- Utility ---

    private String extractRDN(String dn) {
        if (dn == null || dn.isEmpty()) return "";
        int commaIdx = dn.indexOf(',');
        return commaIdx > 0 ? dn.substring(0, commaIdx) : dn;
    }

    // --- Inner types ---

    /**
     * Lightweight node record for the DN picker tree.
     */
    static class DNNode {
        final String dn;
        final String rdn;
        final List<String> objectClasses;
        final boolean isLeaf;
        boolean childrenLoaded = false;

        DNNode(String dn, String rdn, List<String> objectClasses, boolean isLeaf) {
            this.dn = dn;
            this.rdn = rdn;
            this.objectClasses = objectClasses;
            this.isLeaf = isLeaf;
        }

        @Override
        public String toString() {
            return rdn;
        }
    }

    /**
     * Tree cell with emoji icons based on objectClass, matching LDAPTreeView style.
     */
    private static class DNTreeCell extends TreeCell<DNNode> {

        @Override
        protected void updateItem(DNNode node, boolean empty) {
            super.updateItem(node, empty);

            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            setText(node.rdn);

            Label icon = new Label(getNodeIcon(node));
            setGraphic(icon);
        }

        private String getNodeIcon(DNNode node) {
            for (String oc : node.objectClasses) {
                String lower = oc.toLowerCase();
                if (lower.equals("treeroot")) return "\uD83C\uDF10";
                if (lower.contains("computer")) return "\uD83D\uDDA5";
                if (lower.contains("user") || lower.contains("person") ||
                        lower.contains("inetorgperson")) return "\uD83D\uDC64";
                if (lower.contains("group")) return "\uD83D\uDC65";
                if (lower.contains("organizationalunit") || lower.equals("ou")) return "\uD83D\uDCC1";
                if (lower.contains("organization") || lower.contains("domain")) return "\uD83C\uDF10";
                if (lower.contains("country")) return "\uD83C\uDFF3";
                if (lower.contains("printer")) return "\uD83D\uDDA8";
                if (lower.contains("volume") || lower.contains("filesystem")) return "\uD83D\uDCBE";
                if (lower.contains("container")) return "\uD83D\uDCE6";
                if (lower.contains("server") || lower.contains("ncp")) return "\uD83D\uDD33";
            }
            return "\uD83D\uDCC4";
        }
    }
}
